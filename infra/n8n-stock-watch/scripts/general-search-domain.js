'use strict';

const MAX_ATTEMPTS = 3;
const MAX_DURATION_MS = 12 * 60 * 1000;
const MAX_CLAIMS = 3;
const REVIEW_INCONCLUSIVE_ERRORS = new Set([
  'BRIDGE_GENERATION_INCOMPLETE', 'BRIDGE_JSON_INVALID',
  'REVIEW_SCHEMA_INVALID', 'REVIEW_ID_DUPLICATE',
  'REVIEW_VERDICTS_INCOMPLETE', 'REVIEW_CITATION_NOT_CAPTURED',
  'DEADLINE_EXCEEDED'
]);
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
    accepted: [], provisional: [], rejectedIds: [], retryIds: [], feedback: '',
    uncapturedClaimIds: [], excessClaimIds: [], invalidClaimRefs: [],
    quarantinedClaimIds: [],
    candidate: [], auditTrail: [], reason: ''
  };
}

function remainingMs(state, now = Date.now()) {
  return Math.max(0, Number(state.deadlineAt) - now);
}

function prepareSearch(state, now = Date.now()) {
  if (state.status !== 'PENDING' || state.attempts >= MAX_ATTEMPTS
      || state.accepted.length >= MAX_CLAIMS || remainingMs(state, now) < 2000) {
    const deadlineExceeded = state.status === 'PENDING' && state.attempts < MAX_ATTEMPTS
      && state.accepted.length < MAX_CLAIMS && remainingMs(state, now) < 2000;
    return { ...state, status: deadlineExceeded ? 'ERROR' :
      state.status === 'PENDING' ? finalStatus(state) : state.status,
      reason: deadlineExceeded ? 'DEADLINE_EXCEEDED' :
        state.status === 'PENDING' ? state.accepted.length >= MAX_CLAIMS
          ? 'SEARCH_CLAIM_LIMIT_EXCEEDED' : 'SEARCH_BUDGET_EXHAUSTED' : state.reason,
      canSearch: false };
  }
  const target = state.retryIds.length
    ? `只返工这些未通过的事实 ID：${state.retryIds.join(', ')}；保留 ID。`
    : state.excessClaimIds.length
      ? `只补查未处理的事实 ID：${state.excessClaimIds.join(', ')}；保留 ID，最多 ${MAX_CLAIMS - state.accepted.length} 条。`
    : state.accepted.length
      ? `只搜索尚未回答的部分，不重复已通过的 ID：${state.accepted.map(item => item.id).join(', ')}。`
      : '最多 3 条原子事实，ID 唯一。';
  const searchQuery = [
    `请实际联网搜索并核查：${state.question}`,
    state.asOf ? `截至：${state.asOf}` : '', target,
    state.feedback ? `返工原因：${state.feedback}` : '',
    '仅返回 JSON：{"status":"FOUND|UNKNOWN","claims":[{"id":"","statement":"","scope":"","period":"","value":"","unit":"","qualification":"","sourceUrls":[""]}],"reason":""}。',
    '链接须逐字复制本次搜索结果或实际打开页面的原始 URL，不要补全或改写；无证据填 UNKNOWN、claims=[]。范围、时间、数值及单位仅在相关时填写，不将局部样本外推为总体。'
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
    if (status === 504 && body && body.error === 'incomplete_generation'
        && body.contentStatus === 'incomplete' && body.retrySafe === false) {
      throw new Error('BRIDGE_GENERATION_INCOMPLETE');
    }
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
    searchEvidenceSource: result.searchEvidenceSource,
    searchToolCallCount: result.searchToolCallCount,
    openedPageCount: result.openedPageCount,
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
  catch (_) { reject('BRIDGE_JSON_INVALID'); }
  if (!answer || typeof answer !== 'object' || Array.isArray(answer)) reject('BRIDGE_JSON_INVALID');
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
  return claim;
}

function capturedCitation(url, captured) {
  if (captured.has(url)) return url;
  let target;
  try { target = new URL(url); } catch (_) { return ''; }
  target.hash = '';
  for (const candidate of captured) {
    try {
      const parsed = new URL(candidate);
      parsed.hash = '';
      if (parsed.href === target.href) return candidate;
    } catch (_) {}
  }
  return '';
}

function retainCapturedCitations(claim, captured) {
  const resolved = claim.sourceUrls.map(url => capturedCitation(url, captured));
  const sourceUrls = [...new Set(resolved.filter(Boolean))];
  return { claim: { ...claim, sourceUrls }, discarded: resolved.filter(url => !url).length };
}

function finalStatus(state) {
  return state.accepted.length ? 'PARTIAL' : 'UNVERIFIED';
}

// These are policy scores for presenting incomplete evidence, not calibrated probabilities.
// A candidate is eligible only after the first isolated search and its links were verified above.
function upsertProvisional(provisional, candidate, limitation) {
  const others = provisional.filter(item => item.id !== candidate.id);
  if (others.length >= MAX_CLAIMS) return others;
  return [...others, { ...candidate, reviewVerdict: 'UNKNOWN',
    confidenceScore: 0.25,
    confidenceBand: 'LOW', confidenceBasis: 'UNCALIBRATED_POLICY_SCORE',
    limitation: text(limitation, 500) || 'REVIEW_INCOMPLETE' }];
}

function parseSearch(state, transport, now = Date.now()) {
  const attempts = state.attempts + 1;
  const auditTrail = [...state.auditTrail];
  let completedSearchCount = 0;
  try {
    if (remainingMs(state, now) < 1000) throw new Error('DEADLINE_EXCEEDED');
    const { answer, captured, searchCount } = bridgeResponse(transport);
    completedSearchCount = searchCount;
    if (!['FOUND', 'UNKNOWN'].includes(answer.status)) throw new Error('SEARCH_SCHEMA_INVALID');
    const claims = answer.status === 'FOUND' ? answer.claims : [];
    if (!Array.isArray(claims) ||
        (answer.status === 'FOUND' && claims.length === 0)) throw new Error('SEARCH_SCHEMA_INVALID');
    const invalidClaims = [];
    const retained = [];
    claims.forEach((raw, index) => {
      try { retained.push({ ...retainCapturedCitations(normalizeClaim(raw), captured), index }); }
      catch (error) {
        invalidClaims.push({ index, id: text(raw && raw.id, 80),
          reason: text(error.message, 80) || 'CLAIM_INVALID' });
      }
    });
    const counts = new Map();
    for (const item of retained) counts.set(item.claim.id, (counts.get(item.claim.id) || 0) + 1);
    const duplicateIds = [...counts].filter(([, count]) => count > 1).map(([id]) => id);
    const conflictedAcceptedIds = [...new Set(retained.filter(item => {
      const earlier = state.accepted.find(accepted => accepted.id === item.claim.id);
      return earlier && !sameClaim(earlier, item.claim);
    }).map(item => item.claim.id))];
    const quarantinedClaimIds = [...new Set([
      ...(state.quarantinedClaimIds || []), ...conflictedAcceptedIds
    ])];
    const accepted = state.accepted.filter(item => !quarantinedClaimIds.includes(item.id));
    const availableSlots = Math.max(0, MAX_CLAIMS - accepted.length);
    const inScope = retained.filter(item => {
      const id = item.claim.id;
      if (quarantinedClaimIds.includes(id)) return false;
      if (duplicateIds.includes(id)) return false;
      const earlier = accepted.find(verified => verified.id === id);
      if (earlier) {
        return false;
      }
      if (state.retryIds.length && !state.retryIds.includes(id)) {
        invalidClaims.push({ index: item.index, id, reason: 'SEARCH_RETRY_SCOPE_VIOLATION' });
        return false;
      }
      return true;
    });
    for (const item of retained.filter(entry => duplicateIds.includes(entry.claim.id))) {
      invalidClaims.push({ index: item.index, id: item.claim.id, reason: 'CLAIM_ID_DUPLICATE' });
    }
    for (const item of retained.filter(entry => conflictedAcceptedIds.includes(entry.claim.id))) {
      invalidClaims.push({ index: item.index, id: item.claim.id,
        reason: 'SEARCH_ACCEPTED_CLAIM_CONFLICT' });
    }
    const normalized = inScope.map(item => item.claim);
    const supported = normalized.filter(claim => claim.sourceUrls.length > 0);
    const selected = supported.slice(0, availableSlots);
    const overflow = supported.slice(availableSlots);
    const invalidClaimRefs = [...new Set([...(state.invalidClaimRefs || []),
      ...invalidClaims.map(item => `${attempts}:${item.index}:${item.id || 'missing'}:${item.reason}`)])];
    const excessClaimIds = [...new Set([
      ...(state.excessClaimIds || []).filter(id => !selected.some(claim => claim.id === id)
        && !accepted.some(claim => claim.id === id)),
      ...overflow.map(claim => claim.id)
    ])];
    const discardedClaimIds = normalized.filter(claim => claim.sourceUrls.length === 0)
      .map(claim => claim.id);
    const missingSourceClaimIds = inScope.filter(item => item.claim.sourceUrls.length === 0 &&
      item.discarded === 0).map(item => item.claim.id);
    const uncapturedClaimIds = [...new Set([...(state.uncapturedClaimIds || []),
      ...discardedClaimIds])];
    auditTrail.push({ attempt: attempts, phase: 'search', status: answer.status,
      ids: selected.map(claim => claim.id), searchCount,
      links: selected.flatMap(claim => claim.sourceUrls), discardedClaimIds,
      missingSourceClaimIds, excessClaimIds, invalidClaims, conflictedAcceptedIds,
      originalClaimCount: claims.length,
      discardedCitationCount: retained.reduce((total, item) => total + item.discarded, 0) });
    if ((discardedClaimIds.length || invalidClaims.length) && selected.length === 0) {
      const retrySearchProof = attempts < MAX_ATTEMPTS && remainingMs(state, now) >= 30000;
      return { ...state, accepted, quarantinedClaimIds,
        rejectedIds: [...new Set([...state.rejectedIds, ...quarantinedClaimIds])],
        attempts, searchCalls: state.searchCalls + 1,
        reviewProofRetries: 0, auditTrail, uncapturedClaimIds, excessClaimIds,
        invalidClaimRefs,
        candidate: [], canReview: false, retrySearchProof,
        feedback: `缺少捕获链接的 ID：${discardedClaimIds.join(', ')}；字段或 ID 无效：${invalidClaims.map(item => item.id || item.index).join(', ')}。请逐条纠正，复制本次搜索原始 URL。`,
        status: retrySearchProof ? 'PENDING' : finalStatus({ accepted }),
        reason: invalidClaims.length ? 'SEARCH_CLAIM_INVALID' : 'CLAIM_CITATION_NOT_CAPTURED' };
    }
    return { ...state, accepted, quarantinedClaimIds,
      rejectedIds: [...new Set([...state.rejectedIds, ...quarantinedClaimIds])],
      attempts, searchCalls: state.searchCalls + 1,
      reviewProofRetries: 0, auditTrail, uncapturedClaimIds, excessClaimIds,
      invalidClaimRefs,
      candidate: selected, canReview: selected.length > 0,
      status: selected.length ? 'PENDING' : finalStatus({ accepted }),
      reason: selected.length ? '' : excessClaimIds.length
        ? 'SEARCH_CLAIM_LIMIT_EXCEEDED' : (text(answer.reason, 500) || 'NO_SEARCH_RESULT') };
  } catch (error) {
    const completedButInvalid = ['BRIDGE_JSON_INVALID', 'SEARCH_SCHEMA_INVALID'].includes(error.message)
      && (completedSearchCount > 0 || Number(error.proof?.searchCount) > 0);
    auditTrail.push({ attempt: attempts, phase: 'search',
      status: completedButInvalid ? 'UNKNOWN' : 'ERROR',
      reason: error.message,
      ...(completedButInvalid ? { searchCount: completedSearchCount || Number(error.proof.searchCount) } : {}),
      ...(error.proof ? { proof: error.proof } : {}) });
    if (completedButInvalid) return { ...state, attempts, searchCalls: state.searchCalls + 1,
      auditTrail, candidate: [], canReview: false, retrySearchProof: false,
      invalidClaimRefs: [...new Set([...(state.invalidClaimRefs || []), `${attempts}:${error.message}`])],
      status: finalStatus(state), reason: 'SEARCH_INCOMPLETE' };
    const retryProof = ['BRIDGE_TURN_NOT_MATCHED', 'BRIDGE_SEARCH_NOT_OBSERVED'].includes(error.message) &&
      attempts < MAX_ATTEMPTS && remainingMs(state, now) >= 30000;
    return { ...state, attempts, searchCalls: state.searchCalls + 1, auditTrail,
      candidate: [], canReview: false, retrySearchProof: retryProof,
      status: retryProof ? 'PENDING' : 'ERROR', reason: error.message };
  }
}

function prepareReview(state, now = Date.now()) {
  if (!state.canReview || state.status !== 'PENDING') return { ...state, canReview: false };
  if (remainingMs(state, now) < 2000) {
    return { ...state, canReview: false, candidate: [],
      status: finalStatus(state), reason: 'REVIEW_DEADLINE_EXCEEDED',
      auditTrail: [...state.auditTrail, { attempt: state.attempts,
        phase: 'review', status: 'INCOMPLETE', reason: 'REVIEW_DEADLINE_EXCEEDED' }] };
  }
  const reviewQuery = [
    '请独立联网搜索、找反证，逐条核查候选事实的数值、单位、范围、日期及限定条件。',
    `问题：${state.question}`,
    `候选：${JSON.stringify(state.candidate)}`,
    state.accepted.length ? `此前已通过：${JSON.stringify(state.accepted)}` : '',
    state.uncapturedClaimIds?.length
      ? `先前有候选 ID ${state.uncapturedClaimIds.join(', ')} 缺少本次捕获的原始链接；这些候选不得计入已核验覆盖。` : '',
    '仅返回 JSON：{"coverage":"COMPLETE|PARTIAL|UNKNOWN","coverageReason":"","verdicts":[{"id":"","verdict":"PASS|REJECT|UNKNOWN","observed":{"id":"","statement":"","scope":"","period":"","value":"","unit":"","qualification":"","sourceUrls":[""]},"reason":"","missingFields":[],"requestedSource":""}]}。',
    'PASS 时 observed 按候选字段原样填写，仅链接换成从本次搜索结果或实际打开页面逐字复制的原始 URL；不符则 REJECT 并指出字段和所需来源，没证据用 UNKNOWN。',
    'coverage 只能依据此前已通过及本轮 PASS 且有本轮搜索链接的事实判断；REJECT、UNKNOWN 或无对应搜索链接的陈述不能用于宣称问题已答全。'
  ].filter(Boolean).join('\n');
  return { ...state, canReview: true, reviewQuery };
}

function sameClaim(a, b) {
  return ['id', 'statement', 'scope', 'period', 'value', 'unit', 'qualification']
    .every(field => a[field] === b[field]);
}

function hasExplicitConflict(candidate, observed) {
  if (!observed || typeof observed !== 'object' || Array.isArray(observed)) return false;
  return ['id', 'statement', 'scope', 'period', 'value', 'unit', 'qualification']
    .some(field => {
      if (observed[field] == null) return false;
      const value = text(observed[field], field === 'statement' ? 1000 : 300);
      return value !== '' && value !== candidate[field];
    });
}

function applyReview(state, transport, now = Date.now()) {
  const auditTrail = [...state.auditTrail];
  try {
    if (remainingMs(state, now) < 1000) throw new Error('DEADLINE_EXCEEDED');
    const { answer, captured, searchCount } = bridgeResponse(transport);
    const contradictedAcceptedIds = Array.isArray(answer.verdicts)
      ? [...new Set(answer.verdicts.filter(verdict => verdict
        && verdict.verdict === 'REJECT'
        && state.accepted.some(item => item.id === verdict.id)).map(verdict => verdict.id))]
      : [];
    if (contradictedAcceptedIds.length) {
      const quarantinedClaimIds = [...new Set([
        ...(state.quarantinedClaimIds || []), ...contradictedAcceptedIds
      ])];
      state = { ...state,
        accepted: state.accepted.filter(item => !quarantinedClaimIds.includes(item.id)),
        rejectedIds: [...new Set([...state.rejectedIds, ...quarantinedClaimIds])],
        quarantinedClaimIds };
      for (const id of contradictedAcceptedIds) {
        auditTrail.push({ attempt: state.attempts, phase: 'review', id,
          verdict: 'REJECT', reviewerVerdict: 'REJECT',
          reason: 'PREVIOUSLY_ACCEPTED_CLAIM_CONTRADICTED', searchCount,
          links: [...captured] });
      }
    }
    if (!Array.isArray(answer.verdicts) ||
        !['COMPLETE', 'PARTIAL', 'UNKNOWN'].includes(answer.coverage)) {
      throw new Error('REVIEW_SCHEMA_INVALID');
    }
    const coverage = answer.coverage;
    const verdictIds = answer.verdicts.map(v => v && v.id);
    if (new Set(verdictIds).size !== verdictIds.length) throw new Error('REVIEW_ID_DUPLICATE');
    if (answer.verdicts.length !== state.candidate.length + contradictedAcceptedIds.length ||
        !state.candidate.every(candidate => answer.verdicts.some(item =>
          item && item.id === candidate.id && ['PASS', 'REJECT', 'UNKNOWN'].includes(item.verdict)))) {
      throw new Error('REVIEW_VERDICTS_INCOMPLETE');
    }
    const quarantinedClaimIds = state.quarantinedClaimIds || [];
    const accepted = [...state.accepted];
    let provisional = [...state.provisional];
    const rejectedIds = [...state.rejectedIds];
    const rejected = [];
    for (const candidate of state.candidate) {
      const verdict = answer.verdicts.find(item => item && item.id === candidate.id);
      let reason = text(verdict && verdict.reason, 500) || 'REVIEW_MISSING_VERDICT';
      let pass = false;
      let reviewLinks = [];
      let discardedCitationCount = 0;
      let outcome = verdict && verdict.verdict === 'UNKNOWN' ? 'UNKNOWN' : 'REJECT';
      if (verdict.verdict === 'PASS') {
        const retained = retainCapturedCitations(normalizeClaim(verdict.observed), captured);
        discardedCitationCount = retained.discarded;
        const observed = retained.claim;
        reviewLinks = observed.sourceUrls;
        if (!reviewLinks.length) throw new Error('REVIEW_CITATION_NOT_CAPTURED');
        pass = sameClaim(candidate, observed);
        if (!pass) reason = 'REVIEW_FIELDS_MISMATCH';
      }
      if (outcome === 'UNKNOWN' && hasExplicitConflict(candidate, verdict.observed)) {
        outcome = 'REJECT';
        reason = 'REVIEW_FIELDS_MISMATCH';
      }
      auditTrail.push({ attempt: state.attempts, phase: 'review', id: candidate.id,
        verdict: pass ? 'PASS' : outcome,
        reviewerVerdict: verdict.verdict,
        reason, searchCount, links: reviewLinks,
        discardedCitationCount });
      provisional = provisional.filter(item => item.id !== candidate.id);
      if (pass) {
        accepted.push({ ...candidate, reviewLinks,
          reviewMethod: 'TWO_ISOLATED_DEEPSEEK_SEARCHES' });
      } else {
        if (outcome === 'REJECT' && !rejectedIds.includes(candidate.id)) {
          rejectedIds.push(candidate.id);
        }
        if (outcome === 'UNKNOWN' && !rejectedIds.includes(candidate.id)) {
          provisional = upsertProvisional(provisional, candidate, reason);
        }
        rejected.push({ id: candidate.id, reason,
          missingFields: Array.isArray(verdict?.missingFields) ? verdict.missingFields.slice(0, 8) : [],
          requestedSource: text(verdict?.requestedSource, 300) });
      }
    }
    auditTrail.push({ attempt: state.attempts, phase: 'coverage', verdict: coverage,
      reason: text(answer.coverageReason, 500) });
    const uncapturedClaimIds = (state.uncapturedClaimIds || [])
      .filter(id => !accepted.some(claim => claim.id === id));
    const excessClaimIds = (state.excessClaimIds || [])
      .filter(id => !accepted.some(claim => claim.id === id));
    const invalidClaimRefs = state.invalidClaimRefs || [];
    const fullyCrossChecked = coverage === 'COMPLETE' && accepted.length > 0 &&
      rejected.length === 0 && provisional.length === 0
      && uncapturedClaimIds.length === 0 && excessClaimIds.length === 0
      && invalidClaimRefs.length === 0 && quarantinedClaimIds.length === 0;
    if (fullyCrossChecked) {
      return { ...state, accepted, provisional, rejectedIds, quarantinedClaimIds,
        uncapturedClaimIds, excessClaimIds,
        candidate: [], reviewCalls: state.reviewCalls + 1,
        auditTrail, retryReviewProof: false, retryIds: [], feedback: '',
        status: 'CROSS_CHECKED', reason: '' };
    }
    if (!rejected.length) {
      const coverageReason = quarantinedClaimIds.length ? 'CLAIM_CONTRADICTED'
        : invalidClaimRefs.length ? 'SEARCH_CLAIM_INVALID'
        : uncapturedClaimIds.length
        ? 'CLAIM_CITATION_NOT_CAPTURED'
        : excessClaimIds.length ? 'SEARCH_CLAIM_LIMIT_EXCEEDED'
        : text(answer.coverageReason, 500) || 'QUESTION_NOT_FULLY_ANSWERED';
      const retryCoverage = (coverage !== 'COMPLETE' || uncapturedClaimIds.length > 0)
        || excessClaimIds.length > 0;
      const canRetryCoverage = retryCoverage && accepted.length < MAX_CLAIMS
        && state.attempts < MAX_ATTEMPTS && remainingMs(state, now) >= 30000;
      return { ...state, accepted, provisional, rejectedIds, quarantinedClaimIds,
        uncapturedClaimIds,
        excessClaimIds,
        candidate: [],
        reviewCalls: state.reviewCalls + 1, auditTrail, retryReviewProof: false,
        retryIds: [],
        feedback: canRetryCoverage ? `覆盖范围不足：${coverageReason}；缺链接候选 ID：${uncapturedClaimIds.join(', ')}；未处理候选 ID：${excessClaimIds.join(', ')}` : '',
        status: canRetryCoverage ? 'PENDING' : finalStatus({ accepted }),
        reason: coverageReason };
    }
    const canRetry = accepted.length < MAX_CLAIMS && state.attempts < MAX_ATTEMPTS
      && remainingMs(state, now) >= 2000;
    return { ...state, accepted, provisional, rejectedIds, quarantinedClaimIds,
      uncapturedClaimIds,
      excessClaimIds,
      candidate: [], reviewCalls: state.reviewCalls + 1,
      auditTrail, retryIds: rejected.map(item => item.id),
      retryReviewProof: false,
      feedback: JSON.stringify(rejected).slice(0, 1500),
      status: canRetry ? 'PENDING' : (accepted.length ? 'PARTIAL' : 'UNVERIFIED'),
      reason: canRetry ? '' : uncapturedClaimIds.length
        ? 'CLAIM_CITATION_NOT_CAPTURED' : excessClaimIds.length
          ? 'SEARCH_CLAIM_LIMIT_EXCEEDED' : 'SEARCH_BUDGET_EXHAUSTED' };
  } catch (error) {
    const inconclusiveReview = REVIEW_INCONCLUSIVE_ERRORS.has(error.message)
      && state.searchCalls > 0;
    auditTrail.push({ attempt: state.attempts, phase: 'review',
      status: inconclusiveReview ? 'INCOMPLETE' : 'ERROR',
      reason: error.message, ...(error.proof ? { proof: error.proof } : {}) });
    if (inconclusiveReview) {
      return { ...state, reviewCalls: state.reviewCalls + 1, auditTrail,
        candidate: [], canReview: false, retryReviewProof: false,
        status: finalStatus(state), reason: error.message };
    }
    const retryProof = ['BRIDGE_TURN_NOT_MATCHED', 'BRIDGE_SEARCH_NOT_OBSERVED'].includes(error.message) &&
      state.reviewProofRetries < 1 && remainingMs(state, now) >= 30000;
    if (retryProof) return { ...state, reviewCalls: state.reviewCalls + 1,
      reviewProofRetries: state.reviewProofRetries + 1, auditTrail,
      status: 'PENDING', reason: error.message, canReview: true,
      retryReviewProof: true };
    return { ...state, reviewCalls: state.reviewCalls + 1, auditTrail,
      candidate: [], status: 'ERROR', reason: error.message,
      retryReviewProof: false };
  }
}

function finalPacket(state) {
  const fatal = state.status === 'ERROR';
  const unresolvedCitations = (state.uncapturedClaimIds || [])
    .some(id => !state.accepted.some(claim => claim.id === id));
  const unresolvedExcess = (state.excessClaimIds || [])
    .some(id => !state.accepted.some(claim => claim.id === id));
  const unresolvedInvalid = (state.invalidClaimRefs || []).length > 0;
  const quarantinedClaimIds = state.quarantinedClaimIds || [];
  const lastAudit = state.auditTrail[state.auditTrail.length - 1];
  const reviewIncomplete = lastAudit && lastAudit.phase === 'review'
    && lastAudit.status === 'INCOMPLETE';
  const crossChecked = !fatal && state.status === 'CROSS_CHECKED'
    && !unresolvedCitations && !unresolvedExcess && !unresolvedInvalid
    && quarantinedClaimIds.length === 0;
  const safeClaims = state.accepted.filter(claim => !quarantinedClaimIds.includes(claim.id));
  return {
    schemaVersion: state.schemaVersion, requestId: state.requestId,
    question: state.question, asOf: state.asOf, status: fatal ? 'ERROR' :
      crossChecked ? 'CROSS_CHECKED' : finalStatus(state),
    claims: fatal ? [] : safeClaims,
    provisionalClaims: fatal || crossChecked ? [] :
      (state.provisional || []).filter(item => !state.accepted.some(claim => claim.id === item.id)
        && !(state.rejectedIds || []).includes(item.id))
        .slice(0, MAX_CLAIMS),
    limitations: crossChecked ? [] : [...new Set([
      ...(unresolvedCitations ? ['CLAIM_CITATION_NOT_CAPTURED'] : []),
      ...(unresolvedExcess || unresolvedInvalid ? ['SEARCH_INCOMPLETE'] : []),
      ...(quarantinedClaimIds.length ? ['CLAIM_CONTRADICTED'] : []),
      ...(reviewIncomplete ? ['REVIEW_INCOMPLETE'] : []),
      state.reason || 'REVIEW_INCOMPLETE'
    ])],
    audit: { attempts: state.attempts, searchCalls: state.searchCalls,
      reviewCalls: state.reviewCalls, trail: state.auditTrail }
  };
}

module.exports = { MAX_ATTEMPTS, normalizeRequest, prepareSearch, parseSearch,
  prepareReview, applyReview, finalPacket, bridgeResponse };
