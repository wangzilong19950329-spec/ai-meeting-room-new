'use strict';

const MAX_ATTEMPTS = 3;
const MAX_DURATION_MS = 12 * 60 * 1000;
const MAX_CLAIMS = 3;
const ENDPOINTS = new Set([
  'http://deepseek-facade:8793/search',
  'http://host.docker.internal:8794/search',
  'http://host.docker.internal:8795/search'
]);

function text(value, max = 1000) {
  return typeof value === 'string' ? value.trim().slice(0, max) : '';
}

function normalizeRequest(input, now = Date.now()) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('SEARCH_INPUT_REQUIRED');
  const requestId = text(input.requestId, 128);
  const question = text(input.question, 3000);
  if (!requestId || !/^[A-Za-z0-9._:-]+$/.test(requestId)) throw new Error('INVALID_REQUEST_ID');
  if (!question) throw new Error('QUESTION_REQUIRED');
  const bridgeEndpoint = text(input.bridgeEndpoint, 200) || 'http://host.docker.internal:8795/search';
  if (!ENDPOINTS.has(bridgeEndpoint) ||
      (bridgeEndpoint.includes('8794') && input.fixtureMode !== true)) {
    throw new Error('UNTRUSTED_BRIDGE_ENDPOINT');
  }
  const httpTimeoutMs = input.fixtureMode === true && Number.isInteger(input.httpTimeoutMs)
    ? Math.max(1000, Math.min(300000, input.httpTimeoutMs)) : 300000;
  return {
    schemaVersion: 'general.evidence-search.v1', requestId, question,
    asOf: text(input.asOf, 64), bridgeEndpoint, httpTimeoutMs,
    deadlineAt: now + MAX_DURATION_MS, status: 'PENDING', attempts: 0,
    searchCalls: 0, reviewCalls: 0, reviewProofRetries: 0,
    accepted: [], retryIds: [], feedback: '',
    candidate: [], auditTrail: [], reason: ''
  };
}

function remainingMs(state, now = Date.now()) {
  return Math.max(0, Number(state.deadlineAt) - now);
}

function prepareSearch(state, now = Date.now()) {
  if (state.status !== 'PENDING' || state.attempts >= MAX_ATTEMPTS || remainingMs(state, now) < 2000) {
    return { ...state, status: state.accepted.length ? 'PARTIAL' : 'UNVERIFIED',
      reason: remainingMs(state, now) < 2000 ? 'DEADLINE_EXCEEDED' : 'SEARCH_BUDGET_EXHAUSTED',
      canSearch: false };
  }
  const target = state.retryIds.length
    ? `只返工这些未通过的事实 ID：${state.retryIds.join(', ')}；保留 ID。`
    : state.accepted.length
      ? `只搜索尚未回答的部分，不重复已通过的 ID：${state.accepted.map(item => item.id).join(', ')}。`
      : '最多 3 条原子事实，ID 唯一。';
  const searchQuery = [
    `请实际联网搜索并核查：${state.question}`,
    state.asOf ? `截至：${state.asOf}` : '', target,
    state.feedback ? `返工原因：${state.feedback}` : '',
    '仅返回 JSON：{"status":"FOUND|UNKNOWN","claims":[{"id":"","statement":"","scope":"","period":"","value":"","unit":"","qualification":"","sourceUrls":[""]}],"reason":""}。',
    '链接须来自本次搜索；无证据填 UNKNOWN、claims=[]。范围、时间、数值及单位仅在相关时填写，不将局部样本外推为总体。'
  ].filter(Boolean).join('\n');
  return { ...state, canSearch: true, retryReviewProof: false,
    retrySearchProof: false, searchQuery };
}

function bridgeResponse(transport) {
  if (transport && transport.error) {
    const message = text(typeof transport.error === 'string' ? transport.error : transport.error.message, 500);
    throw new Error(/timeout|timed out|abort|socket|connection/i.test(message)
      ? 'BRIDGE_TRANSPORT_OUTCOME_UNKNOWN' : 'BRIDGE_TRANSPORT_ERROR');
  }
  const status = Number(transport && (transport.statusCode || transport.status));
  const body = transport && transport.body !== undefined ? transport.body : transport;
  if (status && status !== 200) {
    if (body && body.providerOutcomeUnknown === true) throw new Error('BRIDGE_PROVIDER_OUTCOME_UNKNOWN');
    throw new Error(`BRIDGE_HTTP_${status}`);
  }
  if (!body || body.ok !== true || !body.result) throw new Error('BRIDGE_RESPONSE_INVALID');
  const result = body.result;
  const proof = {
    contentStatus: result.contentStatus, jsonComplete: result.jsonComplete,
    answerSource: result.answerSource, searchEnabled: result.modeState?.searchEnabled,
    isolatedSession: result.isolatedSession,
    submittedTurnMatched: result.submittedTurnMatched,
    conversationRestored: result.conversationRestored,
    searchCount: result.searchCount, capturedLinkCount: Array.isArray(result.links) ? result.links.length : 0,
    networkAnswerCaptured: result.networkAnswerCaptured
  };
  function reject(code) { const error = new Error(code); error.proof = proof; throw error; }
  if (result.contentStatus !== 'success' || result.jsonComplete !== true ||
      !['completion_stream', 'new_assistant_block'].includes(result.answerSource) ||
      result.isolatedSession !== true || result.conversationRestored === true ||
      !text(result.answerText)) reject('BRIDGE_RESPONSE_UNTRUSTED');
  if (result.modeState?.searchEnabled !== true) reject('BRIDGE_SEARCH_NOT_ENABLED');
  if (result.submittedTurnMatched !== true) reject('BRIDGE_TURN_NOT_MATCHED');
  if (!(Number(result.searchCount) > 0)) reject('BRIDGE_SEARCH_NOT_OBSERVED');
  let answer;
  try { answer = JSON.parse(result.answerText.trim()); }
  catch (_) { throw new Error('BRIDGE_JSON_INVALID'); }
  if (!answer || typeof answer !== 'object' || Array.isArray(answer)) throw new Error('BRIDGE_JSON_INVALID');
  const captured = new Set((Array.isArray(result.links) ? result.links : [])
    .map(link => typeof link === 'string' ? link : link && link.href)
    .map(url => text(url, 1000)).filter(Boolean));
  return { answer, captured, searchCount: Number(result.searchCount) };
}

function normalizeClaim(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('CLAIM_INVALID');
  const claim = {};
  for (const field of ['id', 'statement', 'scope', 'period', 'value', 'unit', 'qualification']) {
    claim[field] = text(raw[field], field === 'statement' ? 1000 : 300);
  }
  if (!/^[A-Za-z0-9._:-]{1,80}$/.test(claim.id) || !claim.statement) {
    throw new Error('CLAIM_FIELDS_MISSING');
  }
  claim.sourceUrls = (Array.isArray(raw.sourceUrls) ? raw.sourceUrls : [])
    .map(url => text(url, 1000)).filter(url => /^https?:\/\//i.test(url)).slice(0, 5);
  if (!claim.sourceUrls.length) throw new Error('CLAIM_SOURCE_MISSING');
  return claim;
}

function finalStatus(state) {
  return state.accepted.length ? 'PARTIAL' : 'UNVERIFIED';
}

function parseSearch(state, transport, now = Date.now()) {
  const attempts = state.attempts + 1;
  const auditTrail = [...state.auditTrail];
  try {
    if (remainingMs(state, now) < 1000) throw new Error('DEADLINE_EXCEEDED');
    const { answer, captured, searchCount } = bridgeResponse(transport);
    if (!['FOUND', 'UNKNOWN'].includes(answer.status)) throw new Error('SEARCH_SCHEMA_INVALID');
    const claims = answer.status === 'FOUND' ? answer.claims : [];
    if (!Array.isArray(claims) || claims.length > MAX_CLAIMS ||
        (answer.status === 'FOUND' && claims.length === 0)) throw new Error('SEARCH_SCHEMA_INVALID');
    const normalized = claims.map(normalizeClaim);
    const ids = normalized.map(claim => claim.id);
    if (new Set(ids).size !== ids.length) throw new Error('CLAIM_ID_DUPLICATE');
    if (ids.some(id => state.accepted.some(item => item.id === id))) {
      throw new Error('SEARCH_ALREADY_ACCEPTED_CLAIM');
    }
    if (state.retryIds.length && ids.some(id => !state.retryIds.includes(id))) {
      throw new Error('SEARCH_RETRY_SCOPE_VIOLATION');
    }
    if (normalized.some(claim => !claim.sourceUrls.every(url => captured.has(url)))) {
      throw new Error('CLAIM_CITATION_NOT_CAPTURED');
    }
    auditTrail.push({ attempt: attempts, phase: 'search', status: answer.status,
      ids, searchCount, links: normalized.flatMap(claim => claim.sourceUrls) });
    return { ...state, attempts, searchCalls: state.searchCalls + 1,
      reviewProofRetries: 0, auditTrail,
      candidate: normalized, canReview: normalized.length > 0,
      status: normalized.length ? 'PENDING' : finalStatus(state),
      reason: normalized.length ? '' : (text(answer.reason, 500) || 'NO_SEARCH_RESULT') };
  } catch (error) {
    auditTrail.push({ attempt: attempts, phase: 'search', status: 'ERROR',
      reason: error.message, ...(error.proof ? { proof: error.proof } : {}) });
    const retryProof = ['BRIDGE_TURN_NOT_MATCHED', 'BRIDGE_SEARCH_NOT_OBSERVED'].includes(error.message) &&
      attempts < MAX_ATTEMPTS && remainingMs(state, now) >= 30000;
    return { ...state, attempts, searchCalls: state.searchCalls + 1, auditTrail,
      candidate: [], canReview: false, retrySearchProof: retryProof,
      status: retryProof ? 'PENDING' : finalStatus(state), reason: error.message };
  }
}

function prepareReview(state, now = Date.now()) {
  if (!state.canReview || state.status !== 'PENDING') return { ...state, canReview: false };
  if (remainingMs(state, now) < 2000) {
    return { ...state, canReview: false, status: finalStatus(state), reason: 'DEADLINE_EXCEEDED' };
  }
  const reviewQuery = [
    '请独立联网搜索、找反证，逐条核查候选事实的数值、单位、范围、日期及限定条件。',
    `问题：${state.question}`,
    `候选：${JSON.stringify(state.candidate)}`,
    state.accepted.length ? `此前已通过：${JSON.stringify(state.accepted)}` : '',
    '仅返回 JSON：{"coverage":"COMPLETE|PARTIAL|UNKNOWN","coverageReason":"","verdicts":[{"id":"","verdict":"PASS|REJECT|UNKNOWN","observed":{"id":"","statement":"","scope":"","period":"","value":"","unit":"","qualification":"","sourceUrls":[""]},"reason":"","missingFields":[],"requestedSource":""}]}。',
    'PASS 时 observed 按候选字段原样填写，仅链接换成本次搜索捕获的来源；不符则 REJECT 并指出字段和所需来源，没证据用 UNKNOWN。'
  ].filter(Boolean).join('\n');
  return { ...state, canReview: true, reviewQuery };
}

function sameClaim(a, b) {
  return ['id', 'statement', 'scope', 'period', 'value', 'unit', 'qualification']
    .every(field => a[field] === b[field]);
}

function applyReview(state, transport, now = Date.now()) {
  const auditTrail = [...state.auditTrail];
  try {
    if (remainingMs(state, now) < 1000) throw new Error('DEADLINE_EXCEEDED');
    const { answer, captured, searchCount } = bridgeResponse(transport);
    if (!Array.isArray(answer.verdicts)) throw new Error('REVIEW_SCHEMA_INVALID');
    const coverage = ['COMPLETE', 'PARTIAL', 'UNKNOWN'].includes(answer.coverage)
      ? answer.coverage : 'UNKNOWN';
    const verdictIds = answer.verdicts.map(v => v && v.id);
    if (new Set(verdictIds).size !== verdictIds.length) throw new Error('REVIEW_ID_DUPLICATE');
    const accepted = [...state.accepted];
    const rejected = [];
    for (const candidate of state.candidate) {
      const verdict = answer.verdicts.find(item => item && item.id === candidate.id);
      let reason = text(verdict && verdict.reason, 500) || 'REVIEW_MISSING_VERDICT';
      let pass = false;
      let reviewLinks = [];
      if (verdict && verdict.verdict === 'PASS') {
        try {
          const observed = normalizeClaim(verdict.observed);
          reviewLinks = observed.sourceUrls;
          pass = sameClaim(candidate, observed) && reviewLinks.every(url => captured.has(url));
          if (!pass) reason = sameClaim(candidate, observed)
            ? 'REVIEW_CITATION_NOT_CAPTURED' : 'REVIEW_FIELDS_MISMATCH';
        } catch (error) {
          reason = error.message;
        }
      } else if (verdict && !['REJECT', 'UNKNOWN'].includes(verdict.verdict)) {
        reason = 'REVIEW_VERDICT_INVALID';
      }
      auditTrail.push({ attempt: state.attempts, phase: 'review', id: candidate.id,
        verdict: pass ? 'PASS' : 'REJECT', reason, searchCount, links: reviewLinks });
      if (pass) accepted.push({ ...candidate, reviewLinks,
        reviewMethod: 'TWO_ISOLATED_DEEPSEEK_SEARCHES' });
      else rejected.push({ id: candidate.id, reason,
        missingFields: Array.isArray(verdict?.missingFields) ? verdict.missingFields.slice(0, 8) : [],
        requestedSource: text(verdict?.requestedSource, 300) });
    }
    auditTrail.push({ attempt: state.attempts, phase: 'coverage', verdict: coverage,
      reason: text(answer.coverageReason, 500) });
    if (!rejected.length) {
      const coverageReason = text(answer.coverageReason, 500) || 'QUESTION_NOT_FULLY_ANSWERED';
      const retryCoverage = coverage !== 'COMPLETE' && state.attempts < MAX_ATTEMPTS &&
        remainingMs(state, now) >= 30000;
      return { ...state, accepted, candidate: [],
        reviewCalls: state.reviewCalls + 1, auditTrail, retryReviewProof: false,
        retryIds: [],
        feedback: retryCoverage ? `覆盖范围不足：${coverageReason}` : '',
        status: coverage === 'COMPLETE' ? 'CROSS_CHECKED' :
          retryCoverage ? 'PENDING' : finalStatus({ accepted }),
        reason: coverage === 'COMPLETE' ? '' : coverageReason };
    }
    const canRetry = state.attempts < MAX_ATTEMPTS && remainingMs(state, now) >= 2000;
    return { ...state, accepted, candidate: [], reviewCalls: state.reviewCalls + 1,
      auditTrail, retryIds: rejected.map(item => item.id),
      retryReviewProof: false,
      feedback: JSON.stringify(rejected).slice(0, 1500),
      status: canRetry ? 'PENDING' : (accepted.length ? 'PARTIAL' : 'UNVERIFIED'),
      reason: canRetry ? '' : 'SEARCH_BUDGET_EXHAUSTED' };
  } catch (error) {
    auditTrail.push({ attempt: state.attempts, phase: 'review', status: 'ERROR',
      reason: error.message, ...(error.proof ? { proof: error.proof } : {}) });
    const retryProof = ['BRIDGE_TURN_NOT_MATCHED', 'BRIDGE_SEARCH_NOT_OBSERVED'].includes(error.message) &&
      state.reviewProofRetries < 1 && remainingMs(state, now) >= 30000;
    if (retryProof) return { ...state, reviewCalls: state.reviewCalls + 1,
      reviewProofRetries: state.reviewProofRetries + 1, auditTrail,
      status: 'PENDING', reason: error.message, canReview: true,
      retryReviewProof: true };
    return { ...state, reviewCalls: state.reviewCalls + 1, auditTrail,
      candidate: [], status: finalStatus(state), reason: error.message,
      retryReviewProof: false };
  }
}

function finalPacket(state) {
  return {
    schemaVersion: state.schemaVersion, requestId: state.requestId,
    question: state.question, asOf: state.asOf, status: state.status === 'CROSS_CHECKED' ?
      'CROSS_CHECKED' : finalStatus(state),
    claims: state.accepted, limitations: state.status === 'CROSS_CHECKED' ? [] :
      [state.reason || 'REVIEW_INCOMPLETE'],
    audit: { attempts: state.attempts, searchCalls: state.searchCalls,
      reviewCalls: state.reviewCalls, trail: state.auditTrail }
  };
}

module.exports = { MAX_ATTEMPTS, normalizeRequest, prepareSearch, parseSearch,
  prepareReview, applyReview, finalPacket, bridgeResponse };
