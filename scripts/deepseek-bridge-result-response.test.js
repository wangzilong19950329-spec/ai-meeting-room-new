'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { bridgeResultResponse } = require('./deepseek-bridge-result-response');

test('returns a success response only for a verified, nonempty result', () => {
  const result = { answerText: '完整回答', contentStatus: 'success',
    completionReason: 'complete_network' };
  assert.deepEqual(bridgeResultResponse(result, { status: 'SUCCESS' }), {
    statusCode: 200,
    payload: { ok: true, result }
  });
});

test('budget-limited generation is an explicit non-retriable HTTP failure without partial text', () => {
  const result = { answerText: '未完成回答', contentStatus: 'incomplete',
    completionReason: 'quick_budget_reached' };
  const response = bridgeResultResponse(result, {
    status: 'INVALID', error: 'provider_response_incomplete'
  });
  assert.equal(response.statusCode, 504);
  assert.equal(response.payload.ok, false);
  assert.equal(response.payload.error, 'incomplete_generation');
  assert.equal(response.payload.ledgerStatus, 'INVALID');
  assert.equal(response.payload.retrySafe, false);
  assert.equal(response.payload.completionReason, 'quick_budget_reached');
  assert.equal(JSON.stringify(response).includes('未完成回答'), false);
});

test('stale producer success label cannot make a budget result successful', () => {
  const response = bridgeResultResponse({ answerText: 'partial', contentStatus: 'success',
    completionReason: 'quick_budget_reached' });
  assert.equal(response.statusCode, 504);
  assert.equal(response.payload.ok, false);
});

test('missing search proof and invalid ledger outcome cannot return successful HTTP bodies', () => {
  const missingProof = bridgeResultResponse({ answerText: 'plausible',
    contentStatus: 'search_evidence_missing', completionReason: 'complete_json_network' });
  assert.equal(missingProof.statusCode, 502);
  assert.equal(missingProof.payload.error, 'search_evidence_missing');
  assert.equal(missingProof.payload.ok, false);
  assert.equal(missingProof.payload.result, undefined);

  const invalidLedger = bridgeResultResponse({ answerText: 'plausible',
    contentStatus: 'success', completionReason: 'complete_network' },
  { status: 'INVALID', error: 'provider_response_unverified' });
  assert.equal(invalidLedger.statusCode, 502);
  assert.equal(invalidLedger.payload.ok, false);
});
