'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { sha256Operation, validateSubmissionIdentity } = require('./deepseek-request-identity');
const { buildSmokePayload, requestHeaders } = require('./stock-watch-ai-rule-expert-smoke');

test('AI rule expert smoke binds its identity to the complete bridge operation', () => {
  const payload = buildSmokePayload('fixture-token');

  assert.equal(payload.requestDigest, sha256Operation(payload));
  assert.doesNotThrow(() => validateSubmissionIdentity(
    payload.submissionKey,
    payload.requestDigest,
    payload
  ));
  assert.notEqual(payload.requestDigest, sha256Operation({
    ...payload,
    capability: 'stock.technical-analysis'
  }));
});

test('AI rule expert smoke authenticates when the bridge key is configured', () => {
  const headers = requestHeaders('{}', 'smoke-secret');

  assert.equal(headers.Authorization, 'Bearer smoke-secret');
  assert.equal(requestHeaders('{}', '').Authorization, undefined);
});
