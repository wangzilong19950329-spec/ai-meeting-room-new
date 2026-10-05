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
  }]), 4);
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

test('HTTP timeout and provider uncertainty return no accepted claims without resubmission', () => {
  let state = domain.prepareSearch(domain.normalizeRequest(input, 0), 1);
  state = domain.parseSearch(state, { error: 'The connection was aborted, perhaps the server is offline' }, 2);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'UNVERIFIED');
  assert.deepEqual(packet.claims, []);
  assert.deepEqual(packet.limitations, ['BRIDGE_TRANSPORT_OUTCOME_UNKNOWN']);
  assert.equal(packet.audit.searchCalls, 1);
  assert.equal(packet.audit.reviewCalls, 0);
  assert.equal(domain.prepareSearch(state, 3).canSearch, false);
});

test('missing citation, late result, mismatched review and untrusted endpoint fail closed', () => {
  const state = domain.normalizeRequest(input, 0);
  assert.equal(domain.parseSearch(state, response({ status: 'FOUND', claims: [claim] },
    ['https://example.invalid/unrelated']), 1).reason, 'CLAIM_CITATION_NOT_CAPTURED');
  assert.equal(domain.parseSearch(state, response({ status: 'FOUND', claims: [
    { ...claim, sourceUrls: [source, reviewSource] }
  ] }, [source]), 1).reason, 'CLAIM_CITATION_NOT_CAPTURED');
  assert.equal(domain.parseSearch(state, response({ status: 'FOUND', claims: [claim] }),
    12 * 60 * 1000 + 1).reason, 'DEADLINE_EXCEEDED');
  let pending = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [claim] }), 1), 2);
  pending = domain.applyReview(pending, review([pass({ ...claim, value: '100' })]), 3);
  assert.equal(pending.status, 'PENDING');
  assert.match(pending.feedback, /REVIEW_FIELDS_MISMATCH/);
  pending = domain.prepareReview(domain.parseSearch(state,
    response({ status: 'FOUND', claims: [claim] }), 1), 2);
  pending = domain.applyReview(pending, review([{ ...pass(), observed: {
    ...claim, sourceUrls: [reviewSource, source]
  } }]), 3);
  assert.match(pending.feedback, /REVIEW_CITATION_NOT_CAPTURED/);
  assert.throws(() => domain.normalizeRequest({ ...input,
    bridgeEndpoint: 'http://127.0.0.1:9999/search' }), /UNTRUSTED/);
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

test('production HTTP calls have at least one minute or stop before sending', () => {
  const deadline = 12 * 60 * 1000;
  const request = domain.normalizeRequest(input, 0);
  assert.equal(domain.prepareSearch(request, 1).requestTimeoutMs, 300000);
  const search = domain.prepareSearch(request, deadline - 61000);
  assert.equal(search.canSearch, true);
  assert.equal(search.requestTimeoutMs, 60000);
  const skipped = domain.prepareSearch(request, deadline - 60000);
  assert.equal(skipped.canSearch, false);
  assert.equal(skipped.reason, 'DEADLINE_EXCEEDED');

  const reviewState = { ...request, canReview: true, candidate: [claim] };
  assert.equal(domain.prepareReview(reviewState, deadline - 61000).requestTimeoutMs, 60000);
  const skippedReview = domain.prepareReview(reviewState, deadline - 60000);
  assert.equal(skippedReview.canReview, false);
  assert.equal(skippedReview.reason, 'DEADLINE_EXCEEDED');

  const fixture = domain.normalizeRequest({ ...input, fixtureMode: true,
    bridgeEndpoint: 'http://host.docker.internal:8794/search', httpTimeoutMs: 1000 }, 0);
  assert.equal(domain.prepareSearch(fixture, 1).requestTimeoutMs, 1000);
});
