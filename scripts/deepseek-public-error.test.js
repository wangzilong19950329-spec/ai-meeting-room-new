'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const {
  PUBLIC_BRIDGE_ERROR_MESSAGES,
  publicBridgeErrorMessage,
  publicLedgerErrorMessage
} = require('./deepseek-public-error');

test('public bridge errors never contain physical worker identifiers', () => {
  const messages = Object.keys(PUBLIC_BRIDGE_ERROR_MESSAGES)
    .map(publicBridgeErrorMessage)
    .concat(publicBridgeErrorMessage('unknown_internal_error'));

  for (const message of messages) {
    assert.doesNotMatch(message, /technical|monitor|accountKey|debugPort|profileRoot/i);
  }
});

test('ledger replay returns stable messages instead of persisted internal errors', () => {
  assert.equal(
    publicLedgerErrorMessage('DIGEST_MISMATCH'),
    'The submission key was already used for a different operation.'
  );
  assert.equal(
    publicLedgerErrorMessage('UNKNOWN'),
    'The provider outcome is not retryable; query status with the same submission identity.'
  );
});
