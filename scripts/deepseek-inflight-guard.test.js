'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { AccountBroker } = require('./deepseek-account-broker');
const { runWithHardTimeout } = require('./deepseek-inflight-guard');

function account(accountKey) {
  return {
    accountKey,
    enabled: true,
    capabilities: ['stock.technical-analysis'],
    minRequestIntervalMs: 0,
    postRequestCooldownMs: 0
  };
}

function waitFor(predicate, timeoutMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const inspect = () => {
      if (predicate()) return resolve();
      if (Date.now() >= deadline) return reject(new Error('condition was not met'));
      setTimeout(inspect, 5);
    };
    inspect();
  });
}

test('hard timeout aborts underlying work and releases broker only after cancellation settles', async () => {
  const broker = new AccountBroker({
    accounts: [account('technical')],
    queueTimeoutMs: 500,
    circuitFailureThreshold: 99,
    circuitOpenMs: 30
  });
  broker.registerWorkerIdentity('technical', 'a'.repeat(64));
  const events = [];

  const first = broker.submit({ capability: 'stock.technical-analysis' }, async ({ markSubmitting }) => {
    markSubmitting();
    return runWithHardTimeout((signal) => new Promise((resolve, reject) => {
      events.push('first-started');
      signal.addEventListener('abort', () => {
        events.push('first-aborted');
        setTimeout(() => {
          events.push('first-settled');
          reject(signal.reason);
        }, 20);
      }, { once: true });
    }), {
      timeoutMs: 10,
      abortGraceMs: 200,
      onTimeout: () => events.push('marker-written')
    });
  });

  await assert.rejects(first, error => (
    error.code === 'provider_timeout'
      && error.providerOutcomeUnknown === true
      && error.retrySafe === false
  ));
  await waitFor(() => broker.state().accounts[0].activeRequest?.phase === 'QUARANTINED');

  const second = broker.submit({ capability: 'stock.technical-analysis' }, async () => {
    events.push('second-started');
    return 'ok';
  });
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(events.includes('second-started'), false);
  await waitFor(() => events.includes('second-started'));
  assert.equal((await second).result, 'ok');
  assert.deepEqual(events, [
    'first-started',
    'marker-written',
    'first-aborted',
    'first-settled',
    'second-started'
  ]);
});

test('unsettled cancellation invokes fail-stop and never releases the worker', async () => {
  const broker = new AccountBroker({
    accounts: [account('technical')],
    queueTimeoutMs: 50,
    circuitFailureThreshold: 99
  });
  broker.registerWorkerIdentity('technical', 'b'.repeat(64));
  const failStops = [];
  const execution = broker.submit({ capability: 'stock.technical-analysis' }, async ({ markSubmitting }) => {
    markSubmitting();
    return runWithHardTimeout(() => new Promise(() => {}), {
      timeoutMs: 5,
      abortGraceMs: 15,
      onAbortTimeout: details => failStops.push(details)
    });
  });

  await assert.rejects(execution, error => error.providerOutcomeUnknown === true);
  await waitFor(() => failStops.length === 1);
  assert.equal(failStops[0].code, 'provider_timeout_abort_unsettled');
  assert.equal(failStops[0].abortGraceMs, 15);
  assert.equal(broker.state().accounts[0].activeRequest?.phase, 'QUARANTINED');
  let secondError = null;
  const second = broker.submit({ capability: 'stock.technical-analysis' }, async () => 'unsafe')
    .catch(error => {
      secondError = error;
    });
  await waitFor(() => secondError !== null);
  await second;
  assert.equal(secondError.code, 'bridge_queue_timeout');
});

test('hard timeout before markSubmitting stays UNKNOWN and never fails over', async () => {
  const broker = new AccountBroker({
    accounts: [account('first'), account('second')],
    queueTimeoutMs: 200,
    circuitFailureThreshold: 99
  });
  broker.registerWorkerIdentity('first', 'c'.repeat(64));
  broker.registerWorkerIdentity('second', 'd'.repeat(64));
  const attempts = [];

  const execution = broker.submit({ capability: 'stock.technical-analysis' }, async ({ accountKey }) => {
    attempts.push(accountKey);
    return runWithHardTimeout((signal) => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true });
    }), {
      timeoutMs: 5,
      abortGraceMs: 100
    });
  });

  await assert.rejects(execution, error => (
    error.code === 'provider_timeout'
      && error.providerOutcomeUnknown === true
      && error.retrySafe === false
  ));
  assert.deepEqual(attempts, ['first']);
});
