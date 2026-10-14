'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const domain = require('./meeting-evidence-domain');

const url = 'https://example.invalid/announcement';
const candidate = { subject: '300750', eventDate: '2026-09-20', metric: '储能框架合作',
  value: '12', unit: 'GWh', qualification: '框架协议', sourceUrls: [url] };
const response = answer => ({ statusCode: 200, body: { ok: true, result: {
  contentStatus: 'success', jsonComplete: true, answerSource: 'new_assistant_block',
  modeState: { searchEnabled: true }, isolatedSession: true, searchCount: 2,
  links: [{ href: url }], answerText: JSON.stringify(answer)
} } });
const request = { requestId: 'test-1', symbol: '300750.sz',
  gap: { id: 'project_capacity', question: '储能项目签约规模和状态？' } };

test('independent review accepts only exact fields and captured citations', () => {
  let state = domain.normalizeRequest(request, 0);
  state = domain.prepareSearch(state, 1);
  state = domain.parseSearch(state, response({ status: 'FOUND', claim: candidate, reason: '公告' }));
  state = domain.prepareReview(state, 1);
  assert.equal(state.canReview, true);
  state = domain.applyReview(state, response({ verdict: 'PASS', observed: candidate, reason: '独立搜索一致' }), 2);
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'VERIFIED');
  assert.equal(packet.reasoningPacket.verifiedFacts[0].value, '12');
  assert.equal(packet.audit.searchCalls, 1);
  assert.equal(packet.audit.reviewCalls, 1);
});

test('wrong delivery number is rejected, targeted feedback is reused, budget ends with no fact', () => {
  let state = domain.normalizeRequest(request, 0);
  for (let i = 0; i < 3; i++) {
    state = domain.prepareSearch(state, i + 1);
    if (i > 0) assert.match(state.searchQuery, /上次审核反馈/);
    state = domain.prepareReview(domain.parseSearch(state,
      response({ status: 'FOUND', claim: candidate })), i + 1);
    state = domain.applyReview(state, response({ verdict: 'REJECT', observed: null,
      reason: '找到的是框架协议，不是已交付' }), i + 2);
  }
  const packet = domain.finalPacket(state);
  assert.equal(packet.status, 'UNVERIFIED');
  assert.deepEqual(packet.reasoningPacket.verifiedFacts, []);
  assert.equal(packet.audit.attempts, 3);
  assert.equal(packet.audit.reviewCalls, 3);
});

test('numeric mismatch, missing search proof and fabricated citation fail closed', () => {
  let state = domain.normalizeRequest(request, 0);
  state = domain.parseSearch(state, response({ status: 'FOUND', claim: candidate }));
  state = domain.applyReview(state, response({ verdict: 'PASS', observed: { ...candidate, value: '120' } }), 1);
  assert.equal(state.status, 'PENDING');
  assert.equal(state.feedback, 'REVIEW_FIELDS_MISMATCH');

  const noSearch = response({ status: 'FOUND', claim: candidate });
  noSearch.body.result.modeState.searchEnabled = false;
  assert.equal(domain.parseSearch(domain.normalizeRequest(request, 0), noSearch).status, 'UNVERIFIED');

  const fakeLink = response({ status: 'FOUND', claim: { ...candidate,
    sourceUrls: ['https://fabricated.invalid/claim'] } });
  assert.equal(domain.parseSearch(domain.normalizeRequest(request, 0), fakeLink).reason,
    'CLAIM_CITATION_NOT_CAPTURED');
});

test('endpoint is allowlisted and a failed provider response cannot enter reasoning packet', () => {
  assert.throws(() => domain.normalizeRequest({ ...request,
    bridgeEndpoint: 'http://127.0.0.1:9999/search' }), /UNTRUSTED/);
  const edgeEndpoint = 'http://host.docker.internal:8795/search';
  assert.throws(() => domain.normalizeRequest({ ...request,
    bridgeEndpoint: edgeEndpoint }), /UNTRUSTED/);
  assert.equal(domain.normalizeRequest({ ...request,
    bridgeEndpoint: edgeEndpoint, edgeBridgeCanary: true }).bridgeEndpoint, edgeEndpoint);
  const state = domain.parseSearch(domain.normalizeRequest(request, 0),
    { statusCode: 504, body: { ok: false, providerOutcomeUnknown: true } });
  assert.equal(domain.finalPacket(state).reasoningPacket.verifiedFacts.length, 0);
  assert.equal(state.reason, 'BRIDGE_HTTP_504');
});

test('a late PASS and a recycled answer are never accepted', () => {
  let state = domain.parseSearch(domain.normalizeRequest(request, 0),
    response({ status: 'FOUND', claim: candidate }));
  state = domain.applyReview(state, response({ verdict: 'PASS', observed: candidate }),
    12 * 60 * 1000 + 1);
  assert.equal(state.status, 'UNVERIFIED');
  assert.equal(state.reason, 'SEARCH_DEADLINE_EXCEEDED');
  const recycled = response({ status: 'FOUND', claim: candidate });
  recycled.body.result.conversationRestored = true;
  assert.equal(domain.parseSearch(domain.normalizeRequest(request, 0), recycled).reason,
    'BRIDGE_SEARCH_PROOF_MISSING');
});
