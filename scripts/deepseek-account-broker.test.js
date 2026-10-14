'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const {
  AccountBroker,
  loadAccountConfigs
} = require('./deepseek-account-broker');

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function waitFor(predicate, timeoutMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('waitFor timed out');
    await new Promise(resolve => setTimeout(resolve, 5));
  }
}

function account(accountKey, capabilities = ['general.analysis']) {
  return {
    accountKey,
    enabled: true,
    capabilities,
    weight: 1,
    minRequestIntervalMs: 0,
    postRequestCooldownMs: 0
  };
}

function broker(accounts, options = {}) {
  return new AccountBroker({
    accounts,
    queueTimeoutMs: 1000,
    circuitFailureThreshold: 2,
    circuitOpenMs: 1000,
    stateFile: false,
    ...options
  });
}

test('configuration accepts an arbitrary number of accounts and capability sets', () => {
  const accounts = loadAccountConfigs({
    DEEPSEEK_WEB_BROKER_ACCOUNTS: 'tech-1,monitor-1,shared-1',
    DEEPSEEK_WEB_ACCOUNT_TECH_1_CAPABILITIES: 'stock.technical-analysis',
    DEEPSEEK_WEB_ACCOUNT_MONITOR_1_CAPABILITIES: 'stock.rule-evaluation',
    DEEPSEEK_WEB_ACCOUNT_SHARED_1_CAPABILITIES: 'general.analysis,stock.technical-analysis'
  });

  assert.deepEqual(accounts.map(item => item.accountKey), ['tech-1', 'monitor-1', 'shared-1']);
  assert.deepEqual(Array.from(accounts[0].capabilities), ['stock.technical-analysis']);
  assert.deepEqual(Array.from(accounts[2].capabilities), ['general.analysis', 'stock.technical-analysis']);
});

test('normal requests cannot select a physical account', async () => {
  const instance = broker([account('left'), account('right')]);

  await assert.rejects(
    instance.submit({ capability: 'general.analysis', accountKey: 'left' }, async () => 'unused'),
    error => error.code === 'account_override_forbidden'
  );
});

test('different accounts execute overlapping requests concurrently', async () => {
  const instance = broker([account('left'), account('right')]);
  const releases = new Map();
  const started = [];
  const execute = async ({ accountKey }) => {
    started.push(accountKey);
    const gate = deferred();
    releases.set(accountKey, gate);
    return gate.promise;
  };

  const first = instance.submit({ capability: 'general.analysis' }, execute);
  const second = instance.submit({ capability: 'general.analysis' }, execute);
  await waitFor(() => started.length === 2);

  assert.equal(new Set(started).size, 2);
  for (const gate of releases.values()) gate.resolve('ok');
  const results = await Promise.all([first, second]);
  assert.deepEqual(results.map(item => item.result), ['ok', 'ok']);
});

test('duplicate real account identities are never counted as two workers', async () => {
  const instance = broker([account('left'), account('right')]);
  const fingerprint = 'b'.repeat(64);
  instance.registerWorkerIdentity('right', fingerprint);
  assert.doesNotThrow(() => instance.registerWorkerIdentity('left', fingerprint));
  assert.throws(
    () => instance.registerWorkerIdentity('right', fingerprint),
    error => error.code === 'duplicate_account_identity' && error.duplicateOf === 'left'
  );

  const started = [];
  const first = instance.submit({ capability: 'general.analysis' }, async ({ accountKey }) => {
    started.push(accountKey);
    return accountKey;
  });
  const second = instance.submit({ capability: 'general.analysis' }, async ({ accountKey }) => {
    started.push(accountKey);
    return accountKey;
  });

  assert.deepEqual((await Promise.all([first, second])).map(item => item.result), ['left', 'left']);
  assert.deepEqual(started, ['left', 'left']);
  const right = instance.state().accounts.find(item => item.accountKey === 'right');
  assert.equal(right.routingEligible, false);
  assert.equal(right.identityCollisionWith, 'left');
});

test('identity changes cannot create overlapping work on the same real account', async () => {
  const instance = broker([account('left'), account('right')]);
  instance.registerWorkerIdentity('left', 'a'.repeat(64));
  instance.registerWorkerIdentity('right', 'b'.repeat(64));
  const bothRouted = deferred();
  let routedCount = 0;
  let activeSameIdentity = 0;
  let maxConcurrentSameIdentity = 0;

  const execute = async (context) => {
    routedCount += 1;
    if (routedCount === 2) bothRouted.resolve();
    await bothRouted.promise;
    instance.registerWorkerIdentity(context.accountKey, 'c'.repeat(64));
    context.refreshIdentityLease();
    context.markSubmitting();
    activeSameIdentity += 1;
    maxConcurrentSameIdentity = Math.max(maxConcurrentSameIdentity, activeSameIdentity);
    await new Promise(resolve => setTimeout(resolve, 20));
    activeSameIdentity -= 1;
    return context.accountKey;
  };

  const results = await Promise.all([
    instance.submit({ capability: 'general.analysis' }, execute),
    instance.submit({ capability: 'general.analysis' }, execute)
  ]);

  assert.equal(results.length, 2);
  assert.equal(maxConcurrentSameIdentity, 1);
  assert.equal(instance.state().accounts.filter(item => item.routingEligible).length, 1);
});

test('a verified account change invalidates persisted session affinity', async () => {
  const instance = broker([account('left'), account('right')], { allowAccountOverride: true });
  instance.registerWorkerIdentity('left', 'a'.repeat(64));
  instance.registerWorkerIdentity('right', 'b'.repeat(64));
  await instance.submit(
    { capability: 'general.analysis', accountKey: 'right', sessionKey: 'session-before-login-change' },
    async ({ accountKey, markSubmitting }) => {
      markSubmitting();
      return accountKey;
    }
  );
  assert.equal(instance.state().affinityCount, 1);

  instance.registerWorkerIdentity('right', 'd'.repeat(64));

  assert.equal(instance.state().affinityCount, 0);
});

test('failed identity recheck revokes stale identity and persisted affinity', async () => {
  const instance = broker([account('only')]);
  instance.registerWorkerIdentity('only', 'a'.repeat(64));
  await instance.submit(
    { capability: 'general.analysis', sessionKey: 'stale-login-session' },
    async ({ markSubmitting }) => {
      markSubmitting();
      return 'ok';
    }
  );
  assert.equal(instance.state().affinityCount, 1);

  instance.invalidateWorkerIdentity('only');

  assert.equal(instance.state().accounts[0].identityVerified, false);
  assert.equal(instance.state().affinityCount, 0);
  instance.registerWorkerIdentity('only', 'b'.repeat(64));
  assert.equal(instance.state().accounts[0].identityVerified, true);
});

test('failed startup identity check revokes an affinity loaded before identity verification', async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-broker-login-test-'));
  const stateFile = path.join(tempDir, 'state.json');
  try {
    const original = broker([account('only')], { stateFile });
    original.registerWorkerIdentity('only', 'a'.repeat(64));
    await original.submit(
      { capability: 'general.analysis', sessionKey: 'persisted-login-session' },
      async ({ markSubmitting }) => {
        markSubmitting();
        return 'ok';
      }
    );
    const restarted = broker([account('only')], { stateFile });
    assert.equal(restarted.state().affinityCount, 1);
    assert.equal(restarted.state().accounts[0].identityVerified, false);

    restarted.invalidateWorkerIdentity('only');

    assert.equal(restarted.state().affinityCount, 0);
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
});

test('an identity change after submit cannot restore invalidated affinity', async () => {
  const instance = broker([account('only')]);
  instance.registerWorkerIdentity('only', 'a'.repeat(64));

  await assert.rejects(
    instance.submit(
      { capability: 'general.analysis', sessionKey: 'identity-change-after-submit' },
      async (context) => {
        context.refreshIdentityLease();
        context.markSubmitting();
        context.markSubmitted();
        instance.registerWorkerIdentity('only', 'b'.repeat(64));
        return 'provider-result';
      }
    ),
    error => error.code === 'identity_lease_changed'
      && error.providerOutcomeUnknown === true
      && error.retrySafe === false
  );
  assert.equal(instance.state().affinityCount, 0);
});

test('one account remains strictly single concurrency', async () => {
  const instance = broker([account('only')]);
  const firstGate = deferred();
  const started = [];
  const first = instance.submit({ capability: 'general.analysis' }, async ({ accountKey }) => {
    started.push(`first:${accountKey}`);
    return firstGate.promise;
  });
  const second = instance.submit({ capability: 'general.analysis' }, async ({ accountKey }) => {
    started.push(`second:${accountKey}`);
    return 'second';
  });

  await waitFor(() => started.length === 1);
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.deepEqual(started, ['first:only']);
  firstGate.resolve('first');
  await Promise.all([first, second]);
  assert.deepEqual(started, ['first:only', 'second:only']);
});

test('queue deadline stops applying after execution starts', async () => {
  const instance = broker([account('only')], { queueTimeoutMs: 20 });
  const result = await instance.submit(
    { capability: 'general.analysis' },
    async () => {
      await new Promise(resolve => setTimeout(resolve, 50));
      return 'completed';
    }
  );
  assert.equal(result.result, 'completed');
});

test('timed-out underlying work keeps its worker quarantined until settlement', async () => {
  const instance = broker([account('only')]);
  const release = deferred();
  const error = new Error('provider outcome still pending');
  error.providerOutcomeUnknown = true;
  error.workerReleasePromise = release.promise;

  const first = instance.submit({ capability: 'general.analysis' }, async () => {
    throw error;
  });
  await assert.rejects(first, candidate => candidate.providerOutcomeUnknown === true);
  await waitFor(() => instance.state().accounts[0].activeRequest?.phase === 'QUARANTINED');
  let secondStarted = false;
  const second = instance.submit({ capability: 'general.analysis' }, async () => {
    secondStarted = true;
    return 'second';
  });
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(secondStarted, false);
  release.resolve();
  assert.equal((await second).result, 'second');
});

test('capability routing hides the account choice from callers', async () => {
  const instance = broker([
    account('technical', ['stock.technical-analysis']),
    account('monitor', ['stock.rule-evaluation'])
  ]);

  const technical = await instance.submit(
    { capability: 'stock.technical-analysis' },
    async ({ accountKey }) => accountKey
  );
  const monitor = await instance.submit(
    { capability: 'stock.rule-evaluation' },
    async ({ accountKey }) => accountKey
  );

  assert.equal(technical.result, 'technical');
  assert.equal(monitor.result, 'monitor');
});

test('session affinity keeps later turns on the original account', async () => {
  const instance = broker([account('left'), account('right')]);
  const first = await instance.submit(
    { capability: 'general.analysis', sessionKey: 'conversation-1' },
    async ({ accountKey, markSubmitting }) => {
      markSubmitting();
      return accountKey;
    }
  );
  const second = await instance.submit(
    { capability: 'general.analysis', sessionKey: 'conversation-1' },
    async ({ accountKey }) => accountKey
  );

  assert.equal(second.result, first.result);
});

test('concurrent first turns for one session serialize before affinity exists', async () => {
  const instance = broker([account('left'), account('right')]);
  const firstGate = deferred();
  const started = [];
  const first = instance.submit(
    { capability: 'general.analysis', sessionKey: 'new-concurrent-session' },
    async ({ accountKey, markSubmitting }) => {
      started.push(`first:${accountKey}`);
      markSubmitting();
      await firstGate.promise;
      return accountKey;
    }
  );
  const second = instance.submit(
    { capability: 'general.analysis', sessionKey: 'new-concurrent-session' },
    async ({ accountKey }) => {
      started.push(`second:${accountKey}`);
      return accountKey;
    }
  );

  await waitFor(() => started.length === 1);
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(instance.state().activeSessionCount, 1);
  assert.equal(started.length, 1);
  firstGate.resolve();
  const [firstResult, secondResult] = await Promise.all([first, second]);
  assert.equal(secondResult.result, firstResult.result);
  assert.deepEqual(started, [`first:${firstResult.result}`, `second:${firstResult.result}`]);
  assert.equal(instance.state().activeSessionCount, 0);
});

test('session keys that previously normalized to one value remain isolated', async () => {
  const instance = broker([account('left'), account('right')]);
  const first = await instance.submit(
    { capability: 'general.analysis', sessionKey: 'tenant/A' },
    async ({ accountKey, markSubmitting }) => {
      markSubmitting();
      return accountKey;
    }
  );
  const second = await instance.submit(
    { capability: 'general.analysis', sessionKey: 'tenant_A' },
    async ({ accountKey, markSubmitting }) => {
      markSubmitting();
      return accountKey;
    }
  );
  assert.notEqual(first.result, second.result);
  assert.equal(instance.state().affinityCount, 2);
});

test('pre-submit failure can fail over to another eligible account', async () => {
  const instance = broker([account('left'), account('right')]);
  const attempts = [];
  const response = await instance.submit({ capability: 'general.analysis' }, async ({ accountKey }) => {
    attempts.push(accountKey);
    if (accountKey === 'left') throw new Error('login may be required');
    return 'recovered';
  });

  assert.deepEqual(attempts, ['left', 'right']);
  assert.equal(response.result, 'recovered');
  assert.equal(response.routing.attempt, 2);
  const left = instance.state().accounts.find(item => item.accountKey === 'left');
  assert.equal(left.circuitOpen, true);
  const next = await instance.submit({ capability: 'general.analysis' }, async ({ accountKey }) => accountKey);
  assert.equal(next.result, 'right');
});

test('explicit login_required circuit fails fast and verified login immediately restores routing', async () => {
  const instance = broker([account('only')], { queueTimeoutMs: 50, circuitOpenMs: 1000 });
  instance.registerWorkerIdentity('only', 'a'.repeat(64));
  await assert.rejects(
    instance.submit({ capability: 'general.analysis' }, async () => {
      const error = new Error('Authentication is missing');
      error.code = 'login_required';
      throw error;
    }),
    error => error.code === 'login_required' && error.retrySafe === true
  );
  assert.equal(instance.state().accounts[0].circuitOpen, true);
  await assert.rejects(
    instance.submit({ capability: 'general.analysis' }, async () => 'unexpected'),
    error => error.code === 'login_required' && error.retrySafe === true
  );
  assert.equal(instance.state().queueDepth, 0);

  instance.registerWorkerIdentity('only', 'a'.repeat(64));
  assert.equal(instance.state().accounts[0].circuitOpen, false);
  const restored = await instance.submit({ capability: 'general.analysis' }, async () => 'restored');
  assert.equal(restored.result, 'restored');
});

test('failure after submit begins is unknown and never fails over', async () => {
  const instance = broker([account('left'), account('right')]);
  const attempts = [];

  await assert.rejects(
    instance.submit({ capability: 'general.analysis', sessionKey: 'conversation-unknown' }, async (context) => {
      attempts.push(context.accountKey);
      context.markSubmitting();
      throw new Error('connection lost after submit reservation');
    }),
    error => error.providerOutcomeUnknown === true && error.retrySafe === false
  );
  assert.deepEqual(attempts, ['left']);

  const next = await instance.submit(
    { capability: 'general.analysis', sessionKey: 'conversation-unknown' },
    async ({ accountKey }) => accountKey
  );
  assert.equal(next.result, 'left');
});

test('higher priority queued request runs before lower priority request', async () => {
  const instance = broker([account('only')]);
  const gate = deferred();
  const order = [];
  const blocker = instance.submit({ capability: 'general.analysis' }, async () => gate.promise);
  await waitFor(() => instance.state().accounts[0].busy);

  const low = instance.submit({ capability: 'general.analysis', priority: 10 }, async () => {
    order.push('low');
    return 'low';
  });
  const high = instance.submit({ capability: 'general.analysis', priority: 100 }, async () => {
    order.push('high');
    return 'high';
  });
  gate.resolve('blocker');

  await Promise.all([blocker, low, high]);
  assert.deepEqual(order, ['high', 'low']);
});

test('session affinity survives broker restart', async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-broker-test-'));
  const stateFile = path.join(tempDir, 'state.json');
  try {
    const firstBroker = broker([account('left'), account('right')], { stateFile });
    firstBroker.registerWorkerIdentity('left', 'a'.repeat(64));
    firstBroker.registerWorkerIdentity('right', 'b'.repeat(64));
    const first = await firstBroker.submit(
      { capability: 'general.analysis', sessionKey: 'persistent-session' },
      async ({ accountKey, markSubmitting }) => {
        markSubmitting();
        return accountKey;
      }
    );
    const restarted = broker([account('left'), account('right')], { stateFile });
    restarted.registerWorkerIdentity('left', 'a'.repeat(64));
    restarted.registerWorkerIdentity('right', 'b'.repeat(64));
    const second = await restarted.submit(
      { capability: 'general.analysis', sessionKey: 'persistent-session' },
      async ({ accountKey }) => accountKey
    );

    assert.equal(second.result, first.result);
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
});

test('session affinity is discarded when the account changed while broker was stopped', async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-broker-identity-test-'));
  const stateFile = path.join(tempDir, 'state.json');
  try {
    const firstBroker = broker([account('left'), account('right')], {
      stateFile,
      allowAccountOverride: true
    });
    firstBroker.registerWorkerIdentity('left', 'a'.repeat(64));
    firstBroker.registerWorkerIdentity('right', 'b'.repeat(64));
    await firstBroker.submit(
      { capability: 'general.analysis', accountKey: 'right', sessionKey: 'changed-during-restart' },
      async (context) => {
        context.refreshIdentityLease();
        context.markSubmitting();
        return context.accountKey;
      }
    );
    assert.equal(firstBroker.state().affinityCount, 1);

    const restarted = broker([account('left'), account('right')], {
      stateFile,
      allowAccountOverride: true
    });
    restarted.registerWorkerIdentity('left', 'a'.repeat(64));
    restarted.registerWorkerIdentity('right', 'd'.repeat(64));

    assert.equal(restarted.state().affinityCount, 0);
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
});
