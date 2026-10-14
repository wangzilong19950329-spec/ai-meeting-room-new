'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const domain = require('./general-search-domain');

const source = 'https://example.invalid/primary';
const reviewSource = 'https://example.invalid/review';
const claim = { id: 'usage', statement: '某平台月处理量为 10 万亿 Token',
  scope: '某平台', period: '2026-05', value: '10', unit: '万亿 Token/月',
  qualification: '平台自身披露', sourceUrls: [source] };
const input = { requestId: 'generic-token-growth', question: '全球 AI Token 用量和增速？' };

function response(answer, links = [source]) {
  return { statusCode: 200, body: { ok: true, result: {
    answerText: JSON.stringify(answer), contentStatus: 'success',
    answerSource: 'new_assistant_block', jsonComplete: true,
    modeState: { searchEnabled: true }, isolatedSession: true,
    submittedTurnMatched: true,
    searchCount: 2, links: links.map(href => ({ href }))
  } } };
}

function review(verdicts, coverage = 'COMPLETE') {
  return response({ coverage, coverageReason: coverage === 'COMPLETE' ? '' : '全球总量尚无证据',
    verdicts }, [reviewSource]);
}
function pass(item = claim) {
  return { id: item.id, verdict: 'PASS', observed: {
    ...item, sourceUrls: [reviewSource]
  }, reason: '另一来源一致' };
}

test('non-stock question is accepted and two searched citations produce cross-checked claims', () => {
  let state = domain.prepareSearch(domain.normalizeRequest(input, 0), 1);
  assert.equal(state.bridgeEndpoint, 'http://host.docker.internal:8795/search');
  assert.match(state.searchQuery, /全球 AI Token/);
  state = domain.parseSearch(state, response({ status: 'FOUND', claims: [claim] }), 2);
  state = domain.prepareReview(state, 3);
  state = domain.applyReview(state, review([pass()]), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'CROSS_CHECKED');
  assert.equal(packet.claims.length, 1);
  assert.deepEqual(packet.claims[0].reviewLinks, [reviewSource]);
  assert.equal(packet.audit.searchCalls, 1);
  assert.equal(packet.audit.reviewCalls, 1);
});

test('only rejected claim is researched again; accepted claim is preserved', () => {
  const other = { ...claim, id: 'growth', statement: '增速为 5 倍', value: '5', unit: '倍' };
  let state = domain.prepareSearch(domain.normalizeRequest(input, 0), 1);
  state = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [claim, other] }), 2), 3);
  state = domain.applyReview(state, review([pass(), {
    id: 'growth', verdict: 'REJECT', observed: null, reason: '基期口径不同',
    missingFields: ['comparisonPeriod'], requestedSource: '同口径历史统计'
  }], 'PARTIAL'), 4);
  assert.equal(state.status, 'PENDING');
  assert.deepEqual(state.retryIds, ['growth']);
  state = domain.prepareSearch(state, 5);
  assert.match(state.searchQuery, /只返工这些未通过的事实 ID：growth/);
  assert.match(state.searchQuery, /同口径历史统计/);
  state = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [other] }), 6), 7);
  state = domain.applyReview(state, review([pass(other)]), 8);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'CROSS_CHECKED');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage', 'growth']);
  assert.equal(packet.audit.searchCalls, 2);
});

test('an explicit UNKNOWN review retains only the captured first-search claim at low policy confidence', () => {
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([{
    id: claim.id, verdict: 'UNKNOWN', observed: null, reason: '未找到独立同口径数据',
    missingFields: ['comparisonPeriod'], requestedSource: '同口径原始统计'
  }], 'UNKNOWN'), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'UNVERIFIED');
  assert.deepEqual(packet.claims, []);
  assert.equal(packet.provisionalClaims.length, 1);
  assert.deepEqual(packet.provisionalClaims[0].sourceUrls, [source]);
  assert.equal(packet.provisionalClaims[0].reviewVerdict, 'UNKNOWN');
  assert.equal(packet.provisionalClaims[0].confidenceScore, 0.25);
  assert.equal(packet.provisionalClaims[0].confidenceBand, 'LOW');
  assert.equal(packet.provisionalClaims[0].confidenceBasis, 'UNCALIBRATED_POLICY_SCORE');
  assert.equal(packet.provisionalClaims[0].limitation, '未找到独立同口径数据');
  const reviewAudit = packet.audit.trail.find(item => item.phase === 'review');
  assert.equal(reviewAudit.verdict, 'UNKNOWN');
  assert.equal(reviewAudit.reviewerVerdict, 'UNKNOWN');
  assert.equal(reviewAudit.searchCount, 2);
});

test('UNKNOWN with empty observed template fields is still uncertain rather than contradicted', () => {
  const state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  const reviewed = domain.applyReview(state, review([{
    id: claim.id, verdict: 'UNKNOWN', reason: '尚无独立来源',
    observed: { statement: '', scope: '', period: '', value: '', unit: '', qualification: '' }
  }], 'UNKNOWN'), 4);
  assert.equal(domain.finalPacket(reviewed).provisionalClaims.length, 1);
  assert.equal(reviewed.auditTrail.find(item => item.phase === 'review').verdict, 'UNKNOWN');
});

test('PARTIAL output keeps PASS claims separate from uncertain candidates', () => {
  const uncertain = { ...claim, id: 'growth', statement: '增速为 5 倍', value: '5', unit: '倍' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim, uncertain] }), 2), 3);
  state = domain.applyReview(state, review([pass(), {
    id: uncertain.id, verdict: 'UNKNOWN', reason: '没有同口径基期'
  }], 'PARTIAL'), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage']);
  assert.deepEqual(packet.provisionalClaims.map(item => item.id), ['growth']);
  assert.equal(packet.provisionalClaims[0].reviewVerdict, 'UNKNOWN');
});

test('a contradicted or mismatched candidate is excluded, while a later PASS replaces provisional', () => {
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([{
    id: claim.id, verdict: 'UNKNOWN', reason: '目前无法核实'
  }], 'UNKNOWN'), 4);
  assert.equal(state.provisional.length, 1);
  state = domain.prepareReview(domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [claim] }), 6), 7);
  state = domain.applyReview(state, review([pass()]), 8);
  assert.equal(domain.finalPacket(state).status, 'CROSS_CHECKED');
  assert.deepEqual(domain.finalPacket(state).provisionalClaims, []);

  const rejected = domain.applyReview(domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3), review([{
      id: claim.id, verdict: 'REJECT', reason: '数值与独立搜索结果矛盾'
    }], 'UNKNOWN'), 4);
  assert.deepEqual(domain.finalPacket(rejected).provisionalClaims, []);
  assert.equal(rejected.auditTrail.find(item => item.phase === 'review').verdict, 'REJECT');

  const misleadingUnknown = domain.applyReview(domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3), review([{
      id: claim.id, verdict: 'UNKNOWN', observed: { value: '100' }, reason: '再确认'
    }], 'UNKNOWN'), 4);
  assert.deepEqual(domain.finalPacket(misleadingUnknown).provisionalClaims, []);
  assert.equal(misleadingUnknown.auditTrail.find(item => item.phase === 'review').verdict, 'REJECT');
  assert.equal(misleadingUnknown.auditTrail.find(item => item.phase === 'review').reviewerVerdict,
    'UNKNOWN');
});

test('a previously rejected ID cannot reappear as provisional after a later UNKNOWN', () => {
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([{
    id: claim.id, verdict: 'REJECT', reason: '原始数字矛盾'
  }], 'UNKNOWN'), 4);
  state = domain.prepareReview(domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [claim] }), 6), 7);
  state = domain.applyReview(state, review([{
    id: claim.id, verdict: 'UNKNOWN', reason: '本轮找不到更多来源'
  }], 'UNKNOWN'), 8);
  assert.deepEqual(domain.finalPacket(state).provisionalClaims, []);
  assert.equal(state.auditTrail.filter(item => item.phase === 'review').at(-1).verdict, 'UNKNOWN');

  const corrected = { ...claim, value: '9', statement: '某平台月处理量为 9 万亿 Token' };
  state = domain.prepareReview(domain.parseSearch(domain.prepareSearch(state, 9),
    response({ status: 'FOUND', claims: [corrected] }), 10), 11);
  state = domain.applyReview(state, review([{
    id: corrected.id, verdict: 'UNKNOWN', reason: '修正数字仍缺独立证据'
  }], 'UNKNOWN'), 12);
  assert.deepEqual(domain.finalPacket(state).provisionalClaims, []);
});

test('review transport failure or missing review proof never grants provisional evidence', () => {
  const reviewed = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  const transportFailure = domain.applyReview(reviewed, { error: 'socket closed' }, 4);
  assert.equal(domain.finalPacket(transportFailure).status, 'ERROR');
  assert.deepEqual(domain.finalPacket(transportFailure).provisionalClaims, []);
  const noProof = review([{ id: claim.id, verdict: 'UNKNOWN', reason: '证据不足' }], 'UNKNOWN');
  noProof.body.result.submittedTurnMatched = false;
  const retry = domain.applyReview(reviewed, noProof, 4);
  assert.equal(retry.retryReviewProof, true);
  assert.deepEqual(domain.finalPacket(retry).provisionalClaims, []);
  const exhausted = domain.applyReview(retry, noProof, 5);
  assert.equal(domain.finalPacket(exhausted).status, 'ERROR');
  assert.deepEqual(domain.finalPacket(exhausted).provisionalClaims, []);
});

test('HTTP timeout and provider uncertainty return ERROR without resubmission', () => {
  let state = domain.prepareSearch(domain.normalizeRequest(input, 0), 1);
  state = domain.parseSearch(state, { error: 'The connection was aborted, perhaps the server is offline' }, 2);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'ERROR');
  assert.deepEqual(packet.claims, []);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.deepEqual(packet.limitations, ['BRIDGE_TRANSPORT_OUTCOME_UNKNOWN']);
  assert.equal(packet.audit.searchCalls, 1);
  assert.equal(packet.audit.reviewCalls, 0);
  assert.equal(domain.prepareSearch(state, 3).canSearch, false);
});

test('explicit incomplete review keeps earlier verified claims and never promotes the pending candidate', () => {
  const pending = { ...claim, id: 'growth', statement: '增速为 5 倍',
    value: '5', unit: '倍' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  state = domain.prepareReview(domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [pending] }), 6), 7);
  state = domain.applyReview(state, { statusCode: 504, body: {
    ok: false, error: 'incomplete_generation', contentStatus: 'incomplete', retrySafe: false
  } }, 8);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage']);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.deepEqual(packet.limitations, ['REVIEW_INCOMPLETE', 'BRIDGE_GENERATION_INCOMPLETE']);
  assert.equal(packet.audit.trail.at(-1).status, 'INCOMPLETE');
  assert.equal(state.canReview, false);
});

test('explicit incomplete first review yields zero-fact UNVERIFIED, other HTTP 504 stays ERROR', () => {
  const pending = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  const incomplete = domain.finalPacket(domain.applyReview(pending,
    { statusCode: 504, body: { error: 'incomplete_generation',
      contentStatus: 'incomplete', retrySafe: false } }, 4));
  assert.equal(incomplete.status, 'UNVERIFIED');
  assert.deepEqual(incomplete.claims, []);
  assert.deepEqual(incomplete.provisionalClaims, []);
  assert.deepEqual(incomplete.limitations, ['REVIEW_INCOMPLETE', 'BRIDGE_GENERATION_INCOMPLETE']);
  assert.equal(incomplete.audit.reviewCalls, 1);
  const ambiguous = domain.finalPacket(domain.applyReview(pending,
    { statusCode: 504, body: { error: 'upstream_error' } }, 4));
  assert.equal(ambiguous.status, 'ERROR');
  assert.deepEqual(ambiguous.limitations, ['BRIDGE_HTTP_504']);
});

test('non-JSON completed review cannot erase a prior verified claim or approve a new one', () => {
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  const pending = { ...claim, id: 'growth', statement: '增速为 5 倍',
    value: '5', unit: '倍' };
  state = domain.prepareReview(domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [pending] }), 6), 7);
  const invalidJson = review([]);
  invalidJson.body.result.answerText = 'The answer was not JSON';
  state = domain.applyReview(state, invalidJson, 8);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage']);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.deepEqual(packet.limitations, ['REVIEW_INCOMPLETE', 'BRIDGE_JSON_INVALID']);
  assert.equal(packet.audit.trail.at(-1).status, 'INCOMPLETE');
});

test('four search claims retain three reviewable candidates and mark uncovered overflow', () => {
  const claims = [claim, ...['growth', 'margin', 'capacity'].map((id, index) => ({
    ...claim, id, statement: `${id} is ${index + 1}`, value: String(index + 1)
  }))];
  let state = domain.parseSearch(domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims }), 2);
  assert.deepEqual(state.candidate.map(item => item.id), ['usage', 'growth', 'margin']);
  assert.deepEqual(state.excessClaimIds, ['capacity']);
  assert.deepEqual(state.auditTrail.at(-1).excessClaimIds, ['capacity']);
  state = domain.applyReview(domain.prepareReview(state, 3),
    review(state.candidate.map(pass)), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage', 'growth', 'margin']);
  assert.deepEqual(packet.limitations,
    ['SEARCH_INCOMPLETE', 'SEARCH_CLAIM_LIMIT_EXCEEDED']);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.equal(domain.prepareSearch(state, 5).canSearch, false);
});

test('uncaptured citations are discarded while a captured source remains, and empty evidence fails closed', () => {
  const state = domain.normalizeRequest(input, 0);
  assert.equal(domain.parseSearch(state, response({ status: 'FOUND', claims: [claim] },
    ['https://example.invalid/unrelated']), 1).reason, 'CLAIM_CITATION_NOT_CAPTURED');
  const filtered = domain.parseSearch(state, response({ status: 'FOUND', claims: [
    { ...claim, sourceUrls: [source, reviewSource] }
  ] }, [source]), 1);
  assert.deepEqual(filtered.candidate[0].sourceUrls, [source]);
  assert.equal(filtered.auditTrail[0].discardedCitationCount, 1);
  const fragmentMatched = domain.parseSearch(state, response({ status: 'FOUND', claims: [
    { ...claim, sourceUrls: [`${source}#answer`] }
  ] }, [`${source}#tool`]), 1);
  assert.deepEqual(fragmentMatched.candidate[0].sourceUrls, [`${source}#tool`]);
  assert.equal(domain.parseSearch(state, response({ status: 'FOUND', claims: [claim] }),
    12 * 60 * 1000 + 1).reason, 'DEADLINE_EXCEEDED');
  let pending = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [claim] }), 1), 2);
  pending = domain.applyReview(pending, review([pass({ ...claim, value: '100' })], 'PARTIAL'), 3);
  assert.equal(pending.status, 'PENDING');
  assert.match(pending.feedback, /REVIEW_FIELDS_MISMATCH/);
  pending = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [claim] }), 1), 2);
  pending = domain.applyReview(pending, review([{ ...pass(), observed: {
    ...claim, sourceUrls: [reviewSource, source]
  } }]), 3);
  assert.equal(domain.finalPacket(pending).status, 'CROSS_CHECKED');
  assert.deepEqual(pending.accepted[0].reviewLinks, [reviewSource]);
  assert.equal(pending.auditTrail.find(item => item.phase === 'review').discardedCitationCount, 1);
  pending = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [claim] }), 1), 2);
  pending = domain.applyReview(pending, review([{ ...pass(), observed: {
    ...claim, sourceUrls: [source]
  } }], 'PARTIAL'), 3);
  assert.equal(domain.finalPacket(pending).status, 'UNVERIFIED');
  assert.deepEqual(domain.finalPacket(pending).claims, []);
  assert.deepEqual(domain.finalPacket(pending).provisionalClaims, []);
  assert.deepEqual(domain.finalPacket(pending).limitations,
    ['REVIEW_INCOMPLETE', 'REVIEW_CITATION_NOT_CAPTURED']);
  assert.equal(pending.reason, 'REVIEW_CITATION_NOT_CAPTURED');
  assert.throws(() => domain.normalizeRequest({ ...input,
    bridgeEndpoint: 'http://127.0.0.1:9999/search' }), /UNTRUSTED/);
});

test('mixed captured citations keep only supported claims and cannot claim complete coverage', () => {
  const uncaptured = { ...claim, id: 'growth', statement: '增速为 5 倍',
    value: '5', unit: '倍', sourceUrls: ['https://example.invalid/not-captured'] };
  let state = domain.prepareSearch(domain.normalizeRequest(input, 0), 1);
  state = domain.parseSearch(state,
    response({ status: 'FOUND', claims: [claim, uncaptured] }, [source]), 2);
  assert.deepEqual(state.candidate.map(item => item.id), ['usage']);
  assert.deepEqual(state.uncapturedClaimIds, ['growth']);
  assert.deepEqual(state.auditTrail[0].discardedClaimIds, ['growth']);
  assert.deepEqual(state.auditTrail[0].ids, ['usage']);
  state = domain.applyReview(domain.prepareReview(state, 3), review([pass()]), 4);
  assert.equal(state.status, 'PENDING');
  assert.equal(domain.finalPacket(state).status, 'PARTIAL');
  assert.deepEqual(domain.finalPacket(state).claims.map(item => item.id), ['usage']);
  assert.deepEqual(domain.finalPacket(state).provisionalClaims, []);
  assert.equal(domain.finalPacket(state).limitations[0], 'CLAIM_CITATION_NOT_CAPTURED');
  assert.match(domain.prepareSearch(state, 5).searchQuery, /growth/);

  state = domain.prepareSearch(state, 5);
  state = domain.parseSearch(state,
    response({ status: 'FOUND', claims: [{ ...uncaptured, sourceUrls: [source] }] }, [source]), 6);
  state = domain.applyReview(domain.prepareReview(state, 7), review([pass({
    ...uncaptured, sourceUrls: [source]
  })]), 8);
  assert.equal(domain.finalPacket(state).status, 'CROSS_CHECKED');
  assert.deepEqual(domain.finalPacket(state).claims.map(item => item.id), ['usage', 'growth']);
});

test('a candidate with no source does not discard a separately cited candidate', () => {
  const missingSource = { ...claim, id: 'growth', statement: '增速为 5 倍',
    value: '5', unit: '倍', sourceUrls: [] };
  let state = domain.parseSearch(domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim, missingSource] }, [source]), 2);
  assert.equal(state.status, 'PENDING');
  assert.equal(state.canReview, true);
  assert.deepEqual(state.candidate.map(item => item.id), ['usage']);
  assert.deepEqual(state.uncapturedClaimIds, ['growth']);
  assert.deepEqual(state.auditTrail[0].missingSourceClaimIds, ['growth']);
  assert.deepEqual(state.auditTrail[0].discardedClaimIds, ['growth']);

  state = domain.applyReview(domain.prepareReview(state, 3), review([pass()]), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage']);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.deepEqual(packet.limitations, ['CLAIM_CITATION_NOT_CAPTURED']);
  assert.match(domain.prepareSearch(state, 5).searchQuery, /growth/);
});

test('only missing-source candidates retry at most three searches then return zero facts', () => {
  const missingSource = { ...claim, sourceUrls: [] };
  let state = domain.normalizeRequest(input, 0);
  for (let attempt = 1; attempt <= domain.MAX_ATTEMPTS; attempt++) {
    state = domain.parseSearch(domain.prepareSearch(state, attempt * 10),
      response({ status: 'FOUND', claims: [missingSource] }, [source]), attempt * 10 + 1);
    assert.equal(state.canReview, false);
    assert.equal(state.retrySearchProof, attempt < domain.MAX_ATTEMPTS);
    assert.deepEqual(state.auditTrail.at(-1).missingSourceClaimIds, ['usage']);
  }
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'UNVERIFIED');
  assert.deepEqual(packet.claims, []);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.deepEqual(packet.limitations, ['CLAIM_CITATION_NOT_CAPTURED']);
  assert.equal(packet.audit.searchCalls, domain.MAX_ATTEMPTS);
  assert.equal(packet.audit.reviewCalls, 0);
});

test('a malformed claim is isolated without losing another reviewable claim', () => {
  const malformed = { ...claim, id: 'bad id', sourceUrls: [] };
  let state = domain.parseSearch(domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim, malformed] }, [source]), 2);
  assert.deepEqual(state.candidate.map(item => item.id), ['usage']);
  assert.equal(state.auditTrail[0].invalidClaims[0].reason, 'CLAIM_FIELDS_MISSING');
  state = domain.applyReview(domain.prepareReview(state, 3), review([pass()]), 4);
  assert.equal(domain.finalPacket(state).status, 'PARTIAL');
  assert.deepEqual(domain.finalPacket(state).claims.map(item => item.id), ['usage']);
  assert.ok(domain.finalPacket(state).limitations.includes('SEARCH_INCOMPLETE'));
});

test('duplicate overflow ID is never silently marked reviewed', () => {
  const growth = { ...claim, id: 'growth', statement: '增速为 5 倍', value: '5' };
  const margin = { ...claim, id: 'margin', statement: '利润率为 8%', value: '8' };
  const conflictingDuplicate = { ...claim, statement: '同一 ID 却是不同陈述', value: '999' };
  let state = domain.parseSearch(domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim, growth, margin, conflictingDuplicate] }), 2);
  assert.deepEqual(state.candidate.map(item => item.id), ['growth', 'margin']);
  assert.equal(state.auditTrail[0].invalidClaims.filter(item =>
    item.reason === 'CLAIM_ID_DUPLICATE').length, 2);
  state = domain.applyReview(domain.prepareReview(state, 3),
    review([pass(growth), pass(margin)]), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['growth', 'margin']);
  assert.ok(packet.limitations.includes('SEARCH_INCOMPLETE'));
  assert.equal(packet.audit.trail[0].originalClaimCount, 4);
});

test('a fourth cited claim is reviewed when the first three lack captured links', () => {
  const uncaptured = ['a', 'b', 'c'].map(id => ({ ...claim, id,
    sourceUrls: ['https://example.invalid/not-captured'] }));
  let state = domain.parseSearch(domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [...uncaptured, claim] }, [source]), 2);
  assert.deepEqual(state.candidate.map(item => item.id), ['usage']);
  assert.deepEqual(state.uncapturedClaimIds, ['a', 'b', 'c']);
  state = domain.applyReview(domain.prepareReview(state, 3), review([pass()]), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage']);
  assert.equal(packet.audit.trail[0].originalClaimCount, 4);
  assert.equal(packet.audit.trail[0].discardedClaimIds.length
    + packet.audit.trail[0].ids.length, 4);
});

test('a later malformed search candidate cannot erase an earlier verified claim', () => {
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  state = domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [{ ...claim, id: 'bad id' }] }), 6);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage']);
  assert.equal(packet.audit.searchCalls, 2);
  assert.equal(packet.audit.reviewCalls, 1);
  assert.ok(packet.limitations.includes('SEARCH_INCOMPLETE'));
});

test('a later conflicting search quarantines the previously accepted claim', () => {
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  const conflicting = { ...claim, statement: '相反的事实陈述', value: '0' };
  state = domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [conflicting] }), 6);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'UNVERIFIED');
  assert.deepEqual(packet.claims, []);
  assert.deepEqual(state.quarantinedClaimIds, [claim.id]);
  assert.ok(packet.limitations.includes('CLAIM_CONTRADICTED'));
  assert.deepEqual(packet.audit.trail.at(-1).conflictedAcceptedIds, [claim.id]);
});

test('an independent reviewer can reject an earlier accepted claim without promoting it', () => {
  const later = { ...claim, id: 'growth', statement: '另一个待核验事实', value: '5' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  state = domain.prepareReview(domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [later] }), 6), 7);
  state = domain.applyReview(state, review([pass(later), {
    id: claim.id, verdict: 'REJECT', reason: '发现反证'
  }]), 8);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['growth']);
  assert.ok(packet.limitations.includes('CLAIM_CONTRADICTED'));
  assert.equal(packet.audit.trail.some(item => item.phase === 'review'
    && item.id === claim.id && item.verdict === 'REJECT'), true);
});

test('an accepted claim stays quarantined when the later review omits another verdict', () => {
  const later = { ...claim, id: 'growth', statement: '另一个待核验事实', value: '5' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  state = domain.prepareReview(domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [later] }), 6), 7);
  state = domain.applyReview(state, review([{
    id: claim.id, verdict: 'REJECT', reason: '发现反证'
  }], 'PARTIAL'), 8);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'UNVERIFIED');
  assert.deepEqual(packet.claims, []);
  assert.deepEqual(state.quarantinedClaimIds, [claim.id]);
  assert.ok(packet.limitations.includes('CLAIM_CONTRADICTED'));
  assert.equal(packet.audit.trail.some(item => item.phase === 'review'
    && item.id === claim.id && item.verdict === 'REJECT'), true);
  assert.equal(packet.audit.trail.at(-1).reason, 'REVIEW_VERDICTS_INCOMPLETE');
});

test('an accepted claim stays quarantined when another PASS citation is uncaptured', () => {
  const later = { ...claim, id: 'growth', statement: '另一个待核验事实', value: '5' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  state = domain.prepareReview(domain.parseSearch(domain.prepareSearch(state, 5),
    response({ status: 'FOUND', claims: [later] }), 6), 7);
  state = domain.applyReview(state, review([{
    ...pass(later), observed: { ...later, sourceUrls: ['https://example.invalid/not-captured'] }
  }, { id: claim.id, verdict: 'REJECT', reason: '发现反证' }], 'PARTIAL'), 8);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'UNVERIFIED');
  assert.deepEqual(packet.claims, []);
  assert.deepEqual(state.quarantinedClaimIds, [claim.id]);
  assert.ok(packet.limitations.includes('CLAIM_CONTRADICTED'));
  assert.equal(packet.audit.trail.at(-1).reason, 'REVIEW_CITATION_NOT_CAPTURED');
});

test('all uncaptured citations retry within budget then return zero-fact UNVERIFIED', () => {
  const uncaptured = { ...claim, sourceUrls: ['https://example.invalid/not-captured'] };
  let state = domain.normalizeRequest(input, 0);
  for (let attempt = 1; attempt <= 3; attempt++) {
    state = domain.prepareSearch(state, attempt * 10);
    state = domain.parseSearch(state,
      response({ status: 'FOUND', claims: [uncaptured] }, [source]), attempt * 10 + 1);
    assert.equal(state.canReview, false);
    assert.equal(state.retrySearchProof, attempt < 3);
    assert.equal(state.reason, 'CLAIM_CITATION_NOT_CAPTURED');
  }
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'UNVERIFIED');
  assert.deepEqual(packet.claims, []);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.deepEqual(packet.limitations, ['CLAIM_CITATION_NOT_CAPTURED']);
  assert.equal(packet.audit.searchCalls, 3);
  assert.equal(packet.audit.reviewCalls, 0);
  assert.deepEqual(packet.audit.trail.map(event => event.discardedClaimIds),
    [['usage'], ['usage'], ['usage']]);
});

test('coverage COMPLETE cannot promote a packet containing rejected candidates', () => {
  const extra = { ...claim, id: 'extra', statement: '额外但无关的事实' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest({
      requestId: 'charter-year', question: '联合国宪章在哪一年签署？'
    }, 0), 1), response({ status: 'FOUND', claims: [claim, extra] }), 2), 3);
  assert.match(state.reviewQuery, /coverage 只能依据此前已通过及本轮 PASS/);
  state = domain.applyReview(state, review([pass(), {
    id: 'extra', verdict: 'REJECT', reason: '无对应来源'
  }], 'COMPLETE'), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.equal(state.status, 'PENDING');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage']);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.equal(packet.audit.searchCalls, 1);
  assert.equal(packet.audit.reviewCalls, 1);
  assert.equal(packet.audit.trail.find(item => item.id === 'extra').verdict, 'REJECT');
});

test('coverage COMPLETE cannot promote UNKNOWN or prior provisional claims', () => {
  const extra = { ...claim, id: 'extra', statement: '额外但尚未核实的事实' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim, extra] }), 2), 3);
  state = domain.applyReview(state, review([pass(), {
    id: extra.id, verdict: 'UNKNOWN', reason: '独立搜索未确认'
  }], 'COMPLETE'), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.deepEqual(packet.claims.map(item => item.id), ['usage']);
  assert.deepEqual(packet.provisionalClaims.map(item => item.id), ['extra']);
  assert.equal(packet.audit.trail.find(item => item.id === 'extra').verdict, 'UNKNOWN');
});

test('a later provider error discards earlier PASS and UNKNOWN material from the packet', () => {
  const uncertain = { ...claim, id: 'extra', statement: '尚未核实的事实' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim, uncertain] }), 2), 3);
  state = domain.applyReview(state, review([pass(), {
    id: uncertain.id, verdict: 'UNKNOWN', reason: '独立搜索未确认'
  }], 'PARTIAL'), 4);
  assert.equal(domain.finalPacket(state).status, 'PARTIAL');
  state = domain.parseSearch(domain.prepareSearch(state, 5), { error: 'socket closed' }, 6);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'ERROR');
  assert.deepEqual(packet.claims, []);
  assert.deepEqual(packet.provisionalClaims, []);
  assert.deepEqual(packet.limitations, ['BRIDGE_TRANSPORT_OUTCOME_UNKNOWN']);
});

test('incomplete review schema and pre-review deadline yield zero-fact uncertainty', () => {
  const searched = domain.parseSearch(domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2);
  const reviewed = domain.prepareReview(searched, 3);
  const missingVerdict = domain.applyReview(reviewed,
    review([], 'UNKNOWN'), 4);
  assert.equal(domain.finalPacket(missingVerdict).status, 'UNVERIFIED');
  assert.equal(missingVerdict.reason, 'REVIEW_VERDICTS_INCOMPLETE');
  assert.deepEqual(domain.finalPacket(missingVerdict).provisionalClaims, []);

  const invalidCoverage = domain.applyReview(reviewed,
    response({ coverage: 'NOT_SURE', verdicts: [{ id: claim.id, verdict: 'UNKNOWN' }] },
      [reviewSource]), 4);
  assert.equal(domain.finalPacket(invalidCoverage).status, 'UNVERIFIED');
  assert.equal(invalidCoverage.reason, 'REVIEW_SCHEMA_INVALID');

  const deadline = domain.prepareReview(searched, 12 * 60 * 1000 - 1000);
  assert.equal(domain.finalPacket(deadline).status, 'UNVERIFIED');
  assert.deepEqual(domain.finalPacket(deadline).claims, []);
  assert.deepEqual(domain.finalPacket(deadline).limitations,
    ['REVIEW_INCOMPLETE', 'REVIEW_DEADLINE_EXCEEDED']);
});

test('search toggle alone is not proof that a submitted turn performed a search', () => {
  const state = domain.normalizeRequest(input, 0);
  const returned = response({ status: 'FOUND', claims: [claim] }, []);
  returned.body.result.submittedTurnMatched = false;
  returned.body.result.searchCount = null;
  returned.body.result.networkAnswerCaptured = true;
  const rejected = domain.parseSearch(state, returned, 1);
  assert.equal(rejected.reason, 'BRIDGE_TURN_NOT_MATCHED');
  assert.equal(rejected.auditTrail[0].proof.searchEnabled, true);
  assert.equal(rejected.auditTrail[0].proof.capturedLinkCount, 0);
  assert.deepEqual(domain.finalPacket(rejected).claims, []);
});

test('matching platform facts cannot complete a broader unanswered global question', () => {
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'PARTIAL');
  assert.equal(packet.claims.length, 1);
  assert.deepEqual(packet.limitations, ['全球总量尚无证据']);
});

test('partial question coverage searches the missing gap without repeating accepted facts', () => {
  const global = { ...claim, id: 'global', statement: '全球月用量为 20 万亿 Token',
    scope: '全球', value: '20' };
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  state = domain.applyReview(state, review([pass()], 'PARTIAL'), 4);
  assert.equal(state.status, 'PENDING');
  state = domain.prepareSearch(state, 5);
  assert.match(state.searchQuery, /覆盖范围不足/);
  assert.match(state.searchQuery, /不重复已通过的 ID：usage/);
  state = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [global] }), 6), 7);
  state = domain.applyReview(state, review([pass(global)]), 8);
  assert.equal(domain.finalPacket(state).status, 'CROSS_CHECKED');
  assert.deepEqual(state.accepted.map(item => item.id), ['usage', 'global']);
});

test('timeless nonnumeric fact is accepted without stock or metric fields', () => {
  const fact = { id: 'capital', statement: '法国首都是巴黎', sourceUrls: [source] };
  let state = domain.prepareSearch(domain.normalizeRequest({
    requestId: 'capital-question', question: '法国首都是哪里？'
  }, 0), 1);
  state = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [fact] }), 2), 3);
  state = domain.applyReview(state, review([pass(fact)]), 4);
  assert.equal(domain.finalPacket(state).status, 'CROSS_CHECKED');
  assert.equal(state.accepted[0].statement, fact.statement);
});

test('a mismatched review turn retries review once without repeating search', () => {
  let state = domain.prepareReview(domain.parseSearch(
    domain.prepareSearch(domain.normalizeRequest(input, 0), 1),
    response({ status: 'FOUND', claims: [claim] }), 2), 3);
  const unproven = review([pass()]);
  unproven.body.result.submittedTurnMatched = false;
  unproven.body.result.searchCount = null;
  state = domain.applyReview(state, unproven, 4);
  assert.equal(state.retryReviewProof, true);
  assert.equal(state.searchCalls, 1);
  assert.equal(state.reviewCalls, 1);
  state = domain.prepareReview(state, 5);
  state = domain.applyReview(state, review([pass()]), 6);
  assert.equal(domain.finalPacket(state).status, 'CROSS_CHECKED');
  assert.equal(state.searchCalls, 1);
  assert.equal(state.reviewCalls, 2);
});

test('missing search proof retries only initial search within the search budget', () => {
  let state = domain.prepareSearch(domain.normalizeRequest(input, 0), 1);
  const unproven = response({ status: 'FOUND', claims: [claim] });
  unproven.body.result.submittedTurnMatched = false;
  unproven.body.result.searchCount = null;
  state = domain.parseSearch(state, unproven, 2);
  assert.equal(state.retrySearchProof, true);
  assert.equal(state.canReview, false);
  assert.equal(state.attempts, 1);
  state = domain.prepareSearch(state, 3);
  state = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [claim] }), 4), 5);
  state = domain.applyReview(state, review([pass()]), 6);
  assert.equal(domain.finalPacket(state).status, 'CROSS_CHECKED');
  assert.equal(state.searchCalls, 2);
  assert.equal(state.reviewCalls, 1);
});

test('production HTTP timeout stays five minutes when the full budget remains', () => {
  const deadline = 12 * 60 * 1000;
  const request = domain.normalizeRequest(input, 0);
  assert.equal(request.httpTimeoutMs, 300000);
  assert.equal(domain.prepareSearch(request, 1).canSearch, true);
  const search = domain.prepareSearch(request, deadline - 61000);
  assert.equal(search.canSearch, true);
  const skipped = domain.prepareSearch(request, deadline - 1999);
  assert.equal(skipped.canSearch, false);
  assert.equal(skipped.reason, 'DEADLINE_EXCEEDED');

  const reviewState = { ...request, canReview: true, candidate: [claim] };
  assert.equal(domain.prepareReview(reviewState, deadline - 61000).canReview, true);
  const skippedReview = domain.prepareReview(reviewState, deadline - 1999);
  assert.equal(skippedReview.canReview, false);
  assert.equal(skippedReview.reason, 'REVIEW_DEADLINE_EXCEEDED');

  const fixture = domain.normalizeRequest({ ...input, fixtureMode: true,
    bridgeEndpoint: 'http://host.docker.internal:8794/search', httpTimeoutMs: 1000 }, 0);
  assert.equal(fixture.httpTimeoutMs, 1000);
});
