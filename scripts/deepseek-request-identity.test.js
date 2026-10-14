'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const {
  canonicalOperationPayload,
  requiresSubmissionIdentity,
  sha256Operation,
  sha256Query,
  validateSubmissionIdentity
} = require('./deepseek-request-identity');

test('submission identity verifies the digest against the exact query', () => {
  const query = 'current provider request';
  const digest = sha256Query(query);
  assert.deepEqual(validateSubmissionIdentity('stock:technical:1', digest, query), {
    submissionKey: 'stock:technical:1',
    requestDigest: digest
  });
  assert.throws(
    () => validateSubmissionIdentity('stock:technical:1', digest, `${query} changed`),
    error => error.code === 'request_digest_mismatch'
  );
});

test('operation digest binds provider-affecting business semantics', () => {
  const operation = {
    query: 'current provider request',
    capability: 'stock.technical-analysis',
    sessionKey: 'stock-session-1',
    mode: 'expert',
    expectedJson: true,
    forceNewSession: false,
    isolatedSession: false,
    closeTargetAfter: false
  };
  const digest = sha256Operation(operation);

  assert.deepEqual(validateSubmissionIdentity('stock:technical:2', digest, operation), {
    submissionKey: 'stock:technical:2',
    requestDigest: digest
  });
  assert.notEqual(digest, sha256Operation({ ...operation, capability: 'stock.rule-evaluation' }));
  assert.notEqual(digest, sha256Operation({ ...operation, sessionKey: 'stock-session-2' }));
  assert.notEqual(digest, sha256Operation({ ...operation, expectedJson: false }));
  assert.notEqual(digest, sha256Operation({ ...operation, requireSearchEvidence: true }));
  assert.notEqual(digest, sha256Operation({ ...operation, enableSearch: false }));
  assert.match(canonicalOperationPayload(operation), /^deepseek-operation-v1\n/);
});

test('operation digest binds the query exactly as the bridge submits it', () => {
  const operation = {
    query: '  current provider request\n',
    capability: 'stock.technical-analysis',
    sessionKey: 'stock-session-1',
    mode: 'expert',
    expectedJson: true
  };

  assert.equal(
    sha256Operation(operation),
    sha256Operation({ ...operation, query: 'current provider request' })
  );
  assert.equal(
    sha256Operation({ ...operation, query: '\u00a0\ufeffcurrent provider request\u3000\u2029' }),
    sha256Operation({ ...operation, query: 'current provider request' })
  );
  assert.equal(
    sha256Operation({
      ...operation,
      query: '\u00a0current provider request\u3000',
      capability: '\ufeffstock.technical-analysis\u2029',
      sessionKey: '\u00a0stock-session-1\u3000',
      mode: '\u2028expert\ufeff',
      accountKey: '\u00a0\u3000'
    }),
    sha256Operation(operation)
  );
});

test('operation digest matches the Java contract fixture', () => {
  assert.equal(sha256Operation({
    query: '分析当前行情',
    capability: 'stock.technical-analysis',
    sessionKey: 'tenant/A',
    mode: 'expert',
    expectedJson: true,
    forceNewSession: false,
    isolatedSession: false,
    closeTargetAfter: false,
    accountKey: ''
  }), '5b182803d73751e8a87e74e0ea389aeecabc0acb549f6dbd20c4c56adccde71f');
  assert.equal(sha256Operation({
    query: '分析当前行情',
    capability: 'stock.technical-analysis',
    sessionKey: 'tenant/A',
    mode: 'expert',
    expectedJson: true,
    forceNewSession: false,
    isolatedSession: false,
    closeTargetAfter: false,
    accountKey: '',
    enableSearch: false
  }), 'b87ce3296f03064e7a7e192d29bf099020ff709fc2e54e446774d4cfff0cddce');
});

test('submission identity rejects malformed keys and non-SHA digests', () => {
  assert.throws(
    () => validateSubmissionIdentity('bad\nkey', 'a'.repeat(64)),
    error => error.code === 'submission_key_invalid'
  );
  assert.throws(
    () => validateSubmissionIdentity('valid-key', 'not-a-digest'),
    error => error.code === 'request_digest_invalid'
  );
});

test('stock capabilities require idempotency while general analysis remains compatible', () => {
  assert.equal(requiresSubmissionIdentity('stock.technical-analysis'), true);
  assert.equal(requiresSubmissionIdentity('stock.rule-evaluation'), true);
  assert.equal(requiresSubmissionIdentity('general.analysis'), false);
});
