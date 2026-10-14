'use strict';

const MAX_ATTEMPTS = 3; // Initial search plus at most two targeted searches.
const MAX_DURATION_MS = 12 * 60 * 1000;
const TRUSTED_ENDPOINTS = new Set([
  'http://deepseek-facade:8793/search',
  'http://host.docker.internal:8794/search', // Local fixture only.
  'http://host.docker.internal:8795/search' // Local Edge bridge canary only.
]);

function text(value, max = 1000) {
  return typeof value === 'string' ? value.trim().slice(0, max) : '';
}

function normalizeRequest(input, now = Date.now()) {
  if (!input || typeof input !== 'object') throw new Error('EVIDENCE_INPUT_REQUIRED');
  const requestId = text(input.requestId, 128);
  const symbol = text(input.symbol, 32);
  const gap = input.gap || {};
  const gapId = text(gap.id, 128);
  const question = text(gap.question, 1500);
  if (!requestId || !/^[A-Za-z0-9._:-]+$/.test(requestId)) throw new Error('INVALID_REQUEST_ID');
  if (!/^[0-9]{6}\.(sz|sh)$/i.test(symbol)) throw new Error('INVALID_SYMBOL');
  if (!gapId || !/^[A-Za-z0-9._:-]+$/.test(gapId) || !question) throw new Error('INVALID_GAP');
  const endpoint = text(input.bridgeEndpoint, 200) || 'http://deepseek-facade:8793/search';
  if (!TRUSTED_ENDPOINTS.has(endpoint)
    || (endpoint.includes('8794') && input.fixtureMode !== true)
    || (endpoint.includes('8795') && input.edgeBridgeCanary !== true)) {
    throw new Error('UNTRUSTED_BRIDGE_ENDPOINT');
  }
  return {
    schemaVersion: 'meeting.evidence-gap.v1', requestId, symbol,
    gap: { id: gapId, question }, bridgeEndpoint: endpoint,
    startedAt: new Date(now).toISOString(), deadlineAt: now + MAX_DURATION_MS,
    status: 'PENDING', attempts: 0, searchCalls: 0, reviewCalls: 0,
    feedback: '', candidate: null, evidence: null, auditTrail: []
  };
}

function remainingMs(state, now = Date.now()) {
  return Math.max(0, Number(state.deadlineAt) - now);
}

function prepareSearch(state, now = Date.now()) {
  if (state.status !== 'PENDING' || state.attempts >= MAX_ATTEMPTS || remainingMs(state, now) < 1000) {
    return { ...state, status: 'UNVERIFIED', reason: 'SEARCH_BUDGET_EXHAUSTED', canSearch: false };
  }
  const query = [
    '请实际使用智能搜索，只调查下列一个缺失字段。不要预测股价，不要使用先前对话内容。',
    `股票：${state.symbol}；缺口：${state.gap.id}；问题：${state.gap.question}`,
    state.feedback ? `上次审核反馈（只返工这个缺口）：${state.feedback}` : '',
    '只返回 JSON 对象：{"status":"FOUND|UNKNOWN","claim":{"subject":"","eventDate":"","metric":"","value":"","unit":"","qualification":"","sourceUrls":[""]},"reason":""}。',
    'FOUND 时每个字段必须来自搜索结果；框架协议、签约、交付、营收等状态不得混同。找不到可靠资料时 status=UNKNOWN 且 claim=null。链接必须是搜索结果中的网址。'
  ].filter(Boolean).join('\n');
  return { ...state, canSearch: true, searchQuery: query };
}

function responseBody(transport) {
  const statusCode = Number(transport && (transport.statusCode || transport.status));
  const body = transport && transport.body !== undefined ? transport.body : transport;
  if (statusCode && statusCode !== 200) throw new Error('BRIDGE_HTTP_' + statusCode);
  if (!body || body.ok !== true || !body.result) throw new Error('BRIDGE_RESPONSE_INVALID');
  const result = body.result;
  if (result.contentStatus !== 'success' || result.jsonComplete !== true ||
      !['completion_stream', 'new_assistant_block'].includes(result.answerSource) ||
      result.modeState?.searchEnabled !== true || result.isolatedSession !== true ||
      result.submittedTurnMatched === false || result.conversationRestored === true ||
      !(Number(result.searchCount) > 0) || !text(result.answerText)) {
    throw new Error('BRIDGE_SEARCH_PROOF_MISSING');
  }
  let answer;
  try { answer = JSON.parse(result.answerText.trim()); }
  catch (_) { throw new Error('BRIDGE_JSON_INVALID'); }
  if (!answer || typeof answer !== 'object' || Array.isArray(answer)) throw new Error('BRIDGE_JSON_INVALID');
  return { answer, result };
}

function normalizeClaim(claim) {
  if (!claim || typeof claim !== 'object') throw new Error('CLAIM_MISSING');
  const fields = ['subject', 'eventDate', 'metric', 'value', 'unit', 'qualification'];
  const normalized = {};
  for (const field of fields) normalized[field] = text(claim[field], 500);
  if (!normalized.subject || !normalized.eventDate || !normalized.metric ||
      !normalized.value || !normalized.qualification) {
    throw new Error('CLAIM_FIELDS_MISSING');
  }
  const urls = Array.isArray(claim.sourceUrls) ? claim.sourceUrls : [];
  normalized.sourceUrls = urls.map(url => text(url, 1000)).filter(url => /^https?:\/\//i.test(url)).slice(0, 5);
  if (!normalized.sourceUrls.length) throw new Error('CLAIM_SOURCE_MISSING');
  return normalized;
}

function hasCapturedCitation(claim, result) {
  const captured = new Set((Array.isArray(result.links) ? result.links : [])
    .map(link => typeof link === 'string' ? link : link && link.href)
    .map(url => text(url, 1000)).filter(Boolean));
  return claim.sourceUrls.some(url => captured.has(url));
}

function parseSearch(state, transport) {
  const attempts = state.attempts + 1;
  const auditTrail = [...state.auditTrail];
  try {
    const { answer, result } = responseBody(transport);
    if (answer.status !== 'FOUND' && answer.status !== 'UNKNOWN') throw new Error('SEARCH_SCHEMA_INVALID');
    const candidate = answer.status === 'FOUND' ? normalizeClaim(answer.claim) : null;
    if (candidate && !hasCapturedCitation(candidate, result)) throw new Error('CLAIM_CITATION_NOT_CAPTURED');
    auditTrail.push({ attempt: attempts, phase: 'search', status: answer.status,
      reason: text(answer.reason, 500), searchCount: result.searchCount,
      links: candidate ? candidate.sourceUrls : [] });
    return { ...state, attempts, searchCalls: state.searchCalls + 1,
      candidate, searchStatus: answer.status, auditTrail, canReview: true };
  } catch (error) {
    auditTrail.push({ attempt: attempts, phase: 'search', status: 'ERROR', reason: error.message });
    return { ...state, attempts, searchCalls: state.searchCalls + 1, auditTrail,
      status: 'UNVERIFIED', reason: error.message, canReview: false };
  }
}

function prepareReview(state, now = Date.now()) {
  if (!state.canReview || state.status !== 'PENDING') return { ...state, canReview: false };
  if (remainingMs(state, now) < 1000) {
    return { ...state, canReview: false, status: 'UNVERIFIED',
      reason: 'SEARCH_DEADLINE_EXCEEDED' };
  }
  const query = [
    '请开启全新会话并实际使用智能搜索，独立核验这一条资料。主动找反证；不要只重复候选链接的摘要。不要预测股价。',
    `股票：${state.symbol}；缺口：${state.gap.id}；问题：${state.gap.question}`,
    `待核候选：${JSON.stringify(state.candidate)}`,
    '只返回 JSON 对象：{"verdict":"PASS|REJECT|UNKNOWN","observed":{"subject":"","eventDate":"","metric":"","value":"","unit":"","qualification":"","sourceUrls":[""]},"reason":""}。',
    'PASS 仅在独立搜索结果支持候选的全部字段、数字、单位、时点和状态时使用；observed 填核实所得字段和链接。没有证据或无法判断用 UNKNOWN；发现不符用 REJECT，reason 精确指出差异。'
  ].join('\n');
  return { ...state, canReview: true, reviewQuery: query };
}

function sameClaim(a, b) {
  return ['subject', 'eventDate', 'metric', 'value', 'unit', 'qualification']
    .every(field => a[field] === b[field]);
}

function applyReview(state, transport, now = Date.now()) {
  const auditTrail = [...state.auditTrail];
  try {
    const { answer, result } = responseBody(transport);
    if (!['PASS', 'REJECT', 'UNKNOWN'].includes(answer.verdict)) throw new Error('REVIEW_SCHEMA_INVALID');
    let observed = null;
    if (answer.verdict === 'PASS') observed = normalizeClaim(answer.observed);
    if (observed && !hasCapturedCitation(observed, result)) throw new Error('REVIEW_CITATION_NOT_CAPTURED');
    const pass = answer.verdict === 'PASS' && state.candidate &&
      sameClaim(state.candidate, observed) && now <= Number(state.deadlineAt);
    const reason = pass ? 'INDEPENDENT_SEARCH_MATCH' :
      (now > Number(state.deadlineAt) ? 'SEARCH_DEADLINE_EXCEEDED' :
        (answer.verdict === 'PASS' ? 'REVIEW_FIELDS_MISMATCH' : text(answer.reason, 500) || 'REVIEW_UNVERIFIED'));
    auditTrail.push({ attempt: state.attempts, phase: 'review', verdict: pass ? 'PASS' : answer.verdict,
      reason, searchCount: result.searchCount, links: observed ? observed.sourceUrls : [] });
    if (pass) return { ...state, reviewCalls: state.reviewCalls + 1, auditTrail,
      status: 'VERIFIED', evidence: { ...state.candidate, source: 'DEEPSEEK_BRIDGE_CROSS_SEARCH',
        candidateLinks: state.candidate.sourceUrls, reviewLinks: observed.sourceUrls }, reason };
    const canRetry = state.attempts < MAX_ATTEMPTS && remainingMs(state, now) >= 1000;
    return { ...state, reviewCalls: state.reviewCalls + 1, auditTrail, candidate: null,
      status: canRetry ? 'PENDING' : 'UNVERIFIED', feedback: reason,
      reason: canRetry ? '' : (now > Number(state.deadlineAt)
        ? 'SEARCH_DEADLINE_EXCEEDED' : 'SEARCH_BUDGET_EXHAUSTED') };
  } catch (error) {
    auditTrail.push({ attempt: state.attempts, phase: 'review', status: 'ERROR', reason: error.message });
    return { ...state, reviewCalls: state.reviewCalls + 1, auditTrail,
      status: 'UNVERIFIED', candidate: null, reason: error.message };
  }
}

function finalPacket(state) {
  const verified = state.status === 'VERIFIED' && state.evidence;
  return {
    schemaVersion: state.schemaVersion, requestId: state.requestId, symbol: state.symbol,
    gapId: state.gap.id, status: verified ? 'VERIFIED' : 'UNVERIFIED',
    reasoningPacket: {
      verifiedFacts: verified ? [{ gapId: state.gap.id, ...state.evidence }] : [],
      limitations: verified ? [] : [`${state.gap.id}: ${state.reason || '未能交叉核验'}`]
    },
    audit: { attempts: state.attempts, searchCalls: state.searchCalls,
      reviewCalls: state.reviewCalls, trail: state.auditTrail }
  };
}

module.exports = { MAX_ATTEMPTS, normalizeRequest, prepareSearch, parseSearch,
  prepareReview, applyReview, finalPacket, responseBody };
