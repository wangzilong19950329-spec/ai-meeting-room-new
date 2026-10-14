'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { bridgeReadiness } = require('./deepseek-bridge-health');

function account(capabilities = ['general.analysis']) {
  return { accountKey: 'meeting', enabled: true, capabilities };
}

function brokerAccount(overrides = {}) {
  return {
    accountKey: 'meeting', enabled: true, capabilities: ['general.analysis'],
    routingEligible: true, identityVerified: true, circuitOpen: false,
    ...overrides
  };
}

test('health rejects a failed shared target despite a stale verified broker identity', () => {
  const readiness = bridgeReadiness(
    [account()],
    { accounts: [brokerAccount()] },
    new Map([['meeting', { status: 'error', error: 'login required' }]]),
    true
  );
  assert.equal(readiness.ready, false);
  assert.equal(readiness.capabilityReady, false);
  assert.equal(readiness.capacityReady, false);
  assert.equal(readiness.eligibleIdentityCount, 1);
  assert.equal(readiness.usableAccountCount, 0);
  assert.deepEqual(readiness.unavailableCapabilities, ['general.analysis']);
});

test('health excludes circuit-open and wildcard-only accounts from usable capacity', () => {
  const readiness = bridgeReadiness(
    [account(['*'])],
    { accounts: [brokerAccount({ capabilities: ['*'], circuitOpen: true })] },
    new Map([['meeting', { status: 'ready' }]]),
    true
  );
  assert.equal(readiness.capabilityReady, true);
  assert.equal(readiness.capacityReady, false);
  assert.equal(readiness.ready, false);
});

test('health does not claim readiness during an in-flight identity recheck', () => {
  const readiness = bridgeReadiness(
    [account()],
    { accounts: [brokerAccount()] },
    new Map([['meeting', { status: 'preparing' }]]),
    true
  );
  assert.equal(readiness.ready, false);
  assert.equal(readiness.usableAccountCount, 0);
});
