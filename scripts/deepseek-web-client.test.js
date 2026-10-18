'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  buildTurnSignature,
  diffSearchState,
  hasCompleteJson,
  parseDeepSeekCompletionSse,
  createDeepSeekCompletionCapture,
  resolveNonJsonCompletionReason,
  acceptsNonJsonCompletionReason,
  hasCompletedNetworkAnswer,
  acceptsStableJsonCompletion,
  normalizeResult,
  normalizeBridgeMode,
  buildToggleJs,
  buildModeState,
  isFatalCompletionCaptureError,
  extractUserDataDirFromCommandLine,
  normalizeAccountKey,
  parseProfilePathFromChromeVersionText,
  resolveWorkerConfig,
  validateAccountIdentityReceipt,
  shouldRecoverIdentityOnFreshTarget,
  retryTransientIdentityRead,
  CdpClient,
  closeTargetAndVerify,
  closeAccountBrowserAndVerify,
  resolveWaitTimeout,
  waitForAnswer,
  markAccountQuarantined,
  hasAccountQuarantine,
  recoverAccountQuarantine
} = require('./deepseek-web-client')._internals;

test('inference toggle script requests intelligent search off', () => {
  assert.match(buildToggleJs('expert', false), /const wantSearch = false/);
  assert.match(buildToggleJs('expert', true), /const wantSearch = true/);
});

class FakeWebSocket {
  static OPEN = 1;
  static CLOSED = 3;

  constructor() {
    this.readyState = FakeWebSocket.OPEN;
    this.listeners = new Map();
    this.sent = [];
  }

  addEventListener(name, listener) {
    if (!this.listeners.has(name)) this.listeners.set(name, new Set());
    this.listeners.get(name).add(listener);
  }

  removeEventListener(name, listener) {
    this.listeners.get(name)?.delete(listener);
  }

  send(value) {
    this.sent.push(value);
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED;
    for (const listener of this.listeners.get('close') || []) listener({ code: 1000 });
  }
}

test('CDP abort rejects pending commands and prevents later sends', async () => {
  const controller = new AbortController();
  const client = new CdpClient('ws://unit.test', {
    signal: controller.signal,
    WebSocketImpl: FakeWebSocket
  });
  await client.open();

  const pending = client.send('Runtime.evaluate');
  controller.abort(new Error('hard timeout'));

  await assert.rejects(pending, /hard timeout/);
  await assert.rejects(client.send('Page.enable'), /hard timeout/);
});

test('CDP command that never answers times out and releases pending work', async () => {
  const client = new CdpClient('ws://unit.test', {
    WebSocketImpl: FakeWebSocket,
    commandTimeoutMs: 25
  });
  await client.open();
  const pending = client.send('Runtime.evaluate');
  await assert.rejects(Promise.race([
    pending,
    new Promise((_, reject) => setTimeout(() => reject(new Error('test deadline')), 100))
  ]), /CDP command timed out/);
  assert.equal(client.pending.size, 0);
  assert.equal(client.ws.readyState, FakeWebSocket.CLOSED);
});

test('aborted target cleanup verifies the old browser target is gone', async () => {
  const originalFetch = global.fetch;
  const calls = [];
  let targets = [{ id: 'old-target' }];
  global.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).includes('/json/close/old-target')) {
      targets = [];
      return { ok: true };
    }
    return { ok: true, json: async () => targets };
  };
  try {
    assert.equal(await closeTargetAndVerify('old-target', { debugPort: 19333 }), true);
    assert.equal(calls.some(url => url.includes('/json/list')), true);
  } finally {
    global.fetch = originalFetch;
  }
});

test('missing quarantine marker can fail-stop after the account debugger is confirmed closed', async () => {
  const originalFetch = global.fetch;
  let debuggerUp = true;
  class ClosingBrowserWebSocket extends FakeWebSocket {
    send(value) {
      super.send(value);
      const request = JSON.parse(value);
      if (request.method === 'Browser.close') debuggerUp = false;
    }
  }
  global.fetch = async () => debuggerUp
    ? { ok: true, json: async () => ({ webSocketDebuggerUrl: 'ws://browser.test' }) }
    : { ok: false, status: 503 };
  try {
    const closed = await closeAccountBrowserAndVerify({
      accountKey: 'technical',
      debugPort: 19336,
      profileRoot: '/tmp/deepseek-emergency-close-test',
      debuggerProfileVerified: true
    }, {
      WebSocketImpl: ClosingBrowserWebSocket,
      commandTimeoutMs: 5,
      verificationTimeoutMs: 50,
      requiredAbsentCount: 3,
      pollIntervalMs: 1
    });
    assert.equal(closed, true);
    assert.equal(debuggerUp, false);
  } finally {
    global.fetch = originalFetch;
  }
});

test('emergency browser cleanup is unconfirmed while the debugger remains reachable', async () => {
  const originalFetch = global.fetch;
  global.fetch = async () => ({
    ok: true,
    json: async () => ({ webSocketDebuggerUrl: 'ws://browser-still-running.test' })
  });
  try {
    assert.equal(await closeAccountBrowserAndVerify({
      accountKey: 'technical',
      debugPort: 19337,
      profileRoot: '/tmp/deepseek-emergency-close-block-test',
      debuggerProfileVerified: true
    }, {
      WebSocketImpl: FakeWebSocket,
      commandTimeoutMs: 2,
      verificationTimeoutMs: 10,
      pollIntervalMs: 1
    }), false);
  } finally {
    global.fetch = originalFetch;
  }
});

test('one missed debugger probe followed by recovery is not confirmed shutdown', async () => {
  const originalFetch = global.fetch;
  let probes = 0;
  global.fetch = async () => {
    probes += 1;
    if (probes === 1) return { ok: false, status: 503 };
    return { ok: true, json: async () => ({ webSocketDebuggerUrl: 'ws://browser-recovered.test' }) };
  };
  try {
    assert.equal(await closeAccountBrowserAndVerify({
      accountKey: 'technical',
      debugPort: 19338,
      profileRoot: '/tmp/deepseek-emergency-close-flap-test',
      debuggerProfileVerified: true
    }, {
      WebSocketImpl: FakeWebSocket,
      verificationTimeoutMs: 10,
      pollIntervalMs: 1
    }), false);
    assert.ok(probes > 1);
  } finally {
    global.fetch = originalFetch;
  }
});

test('three consecutive absent debugger probes confirm shutdown', async () => {
  const originalFetch = global.fetch;
  let probes = 0;
  global.fetch = async () => {
    probes += 1;
    return { ok: false, status: 503 };
  };
  try {
    assert.equal(await closeAccountBrowserAndVerify({
      accountKey: 'technical',
      debugPort: 19339,
      profileRoot: '/tmp/deepseek-emergency-close-absent-test',
      debuggerProfileVerified: true
    }, {
      verificationTimeoutMs: 20,
      requiredAbsentCount: 3,
      pollIntervalMs: 1
    }), true);
    assert.equal(probes, 3);
  } finally {
    global.fetch = originalFetch;
  }
});

test('soft answer timeout remains UNKNOWN while provider generation is active', () => {
  assert.throws(
    () => resolveWaitTimeout({ generationActive: true }, { expectedJson: true }),
    error => error.code === 'provider_timeout'
      && error.providerOutcomeUnknown === true
      && error.retrySafe === false
  );
  assert.equal(
    resolveWaitTimeout({ generationActive: false }, { expectedJson: true }).completionReason,
    'timeout_incomplete_json'
  );
});

test('JSON network completion waits for the submitted turn search evidence when requested', async () => {
  const states = [
    { bodyText: '', candidateTexts: [], links: [], searchCount: null,
      assistantBlockCount: 0, submittedTurnMatched: false, generationActive: false },
    { bodyText: '搜索到 21 个网页', candidateTexts: [],
      links: [{ href: 'https://example.org/source', text: 'source' }], searchCount: 21,
      assistantBlockCount: 1, submittedTurnMatched: true, generationActive: false }
  ];
  let reads = 0;
  const client = { eval: async () => states[Math.min(reads++, states.length - 1)] };
  const result = await waitForAnswer(client, null, {
    expectedJson: true,
    requireSearchEvidence: true,
    submittedTurnSignature: 'submitted-query',
    completionCapture: { completed: true, answerText: '{"status":"ok"}' }
  });
  assert.equal(reads, 2);
  assert.equal(result.submittedTurnMatched, true);
  assert.equal(result.searchCount, 21);
  assert.equal(result.links.length, 1);
  assert.equal(result.answerText, undefined);
});

test('matched completion tool results recover search proof when the page has no messages', async () => {
  const client = { eval: async () => ({ bodyText: '', candidateTexts: [], links: [], searchCount: null,
    assistantBlockCount: 0, submittedTurnMatched: false, generationActive: false }) };
  const raw = await waitForAnswer(client, null, {
    expectedJson: true,
    requireSearchEvidence: true,
    completionCapture: { completed: true, answerText: '{"status":"FOUND"}',
      requestMatched: true, requestSearchEnabled: true, searchCount: 2,
      searchToolCallCount: 1, links: [{ href: 'https://www.un.org/example', text: 'UN' }] }
  });
  const result = normalizeResult({ ...raw, expectedJson: true, requireSearchEvidence: true });
  assert.equal(result.contentStatus, 'success');
  assert.equal(result.submittedTurnMatched, true);
  assert.equal(result.searchEvidenceSource, 'completion_tool_results');
  assert.equal(result.searchCount, 2);
  assert.deepEqual(result.links.map(item => item.href), ['https://www.un.org/example']);
});

test('completed JSON without matching search proof is not a successful search result', () => {
  const result = normalizeResult({
    bodyText: '{"status":"FOUND"}',
    candidateTexts: [{ text: '{"status":"FOUND"}' }],
    jsonComplete: true,
    networkAnswerCaptured: true,
    requireSearchEvidence: true,
    submittedTurnMatched: false,
    searchCount: null,
    links: []
  });
  assert.equal(result.contentStatus, 'search_evidence_missing');
  assert.equal(result.submittedTurnMatched, false);
});

test('persisted account quarantine closes stale targets before startup routing', async () => {
  const profileRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-quarantine-test-'));
  const worker = { accountKey: 'technical', debugPort: 19334, profileRoot };
  const originalFetch = global.fetch;
  let targets = [{
    id: 'stale-target',
    type: 'page',
    url: 'https://chat.deepseek.com/a/chat/s/stale',
    webSocketDebuggerUrl: 'ws://stale'
  }];
  global.fetch = async (url) => {
    if (String(url).includes('/json/close/stale-target')) {
      targets = [];
      return { ok: true };
    }
    return { ok: true, json: async () => targets };
  };
  try {
    markAccountQuarantined(worker, { requestId: 42, targetId: 'stale-target' });
    assert.equal(hasAccountQuarantine(worker), true);
    assert.equal((await recoverAccountQuarantine(worker)).recovered, true);
    assert.equal(hasAccountQuarantine(worker), false);
  } finally {
    global.fetch = originalFetch;
    fs.rmSync(profileRoot, { recursive: true, force: true });
  }
});

test('startup keeps account quarantined when stale target cleanup is unconfirmed', async () => {
  const profileRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-quarantine-block-test-'));
  const worker = { accountKey: 'technical', debugPort: 19335, profileRoot };
  const originalFetch = global.fetch;
  const staleTarget = {
    id: 'stale-target',
    type: 'page',
    url: 'https://chat.deepseek.com/a/chat/s/stale',
    webSocketDebuggerUrl: 'ws://stale'
  };
  global.fetch = async (url) => {
    if (String(url).includes('/json/close/stale-target')) return { ok: false };
    return { ok: true, json: async () => [staleTarget] };
  };
  try {
    markAccountQuarantined(worker, { requestId: 43, targetId: 'stale-target' });
    await assert.rejects(
      recoverAccountQuarantine(worker, { verificationTimeoutMs: 20 }),
      error => error.code === 'account_quarantine_cleanup_unconfirmed'
    );
    assert.equal(hasAccountQuarantine(worker), true);
  } finally {
    global.fetch = originalFetch;
    fs.rmSync(profileRoot, { recursive: true, force: true });
  }
});

test('buildTurnSignature is stable across whitespace-only rendering differences', () => {
  assert.equal(
    buildTurnSignature('上一条结构化回答不完整。\n只输出 JSON。'),
    buildTurnSignature('  上一条结构化回答不完整。  只输出 JSON。  ')
  );
  assert.notEqual(buildTurnSignature('first turn'), buildTurnSignature('second turn'));
});

test('normalizeBridgeMode accepts per-request professional mode aliases', () => {
  assert.equal(normalizeBridgeMode('expert'), 'expert');
  assert.equal(normalizeBridgeMode('professional'), 'expert');
  assert.equal(normalizeBridgeMode('fast'), 'fast');
  assert.equal(normalizeBridgeMode(''), 'fast');
});

test('account identity receipt exposes only a stable fingerprint', () => {
  const fingerprint = 'a'.repeat(64);
  assert.deepEqual(validateAccountIdentityReceipt({
    httpStatus: 200,
    code: 0,
    fingerprint
  }), {
    identityVerified: true,
    identityFingerprint: fingerprint,
    identityFingerprintShort: fingerprint.slice(0, 12)
  });
  assert.throws(
    () => validateAccountIdentityReceipt({ httpStatus: 200, code: 40003, fingerprint: '' }),
    /login may be required/
  );
});

test('account identity check retries one transient CDP stall but never retries login failure', async () => {
  let calls = 0;
  const receipt = await retryTransientIdentityRead(async () => {
    calls += 1;
    if (calls === 1) throw new Error('CDP command timed out: Runtime.evaluate after 20000ms');
    return { identityVerified: true };
  });
  assert.equal(calls, 2);
  assert.equal(receipt.identityVerified, true);
  calls = 0;
  await assert.rejects(retryTransientIdentityRead(async () => {
    calls += 1;
    throw new Error('DeepSeek account identity could not be verified; login may be required');
  }), /login may be required/);
  assert.equal(calls, 1);
});

test('a throttled or failing identity check is not mistaken for a logged-out account', () => {
  for (const httpStatus of [429, 503]) {
    assert.throws(
      () => validateAccountIdentityReceipt({ httpStatus, code: undefined, fingerprint: '' }),
      (error) => error.code === 'identity_check_unavailable'
        && !/login may be required/.test(error.message)
        && error.message.includes(`http ${httpStatus}`)
    );
  }
  assert.throws(
    () => validateAccountIdentityReceipt({ httpStatus: 401, code: 40001, fingerprint: '' }),
    (error) => error.code === 'login_required' && error.message.includes('http 401')
  );
  assert.throws(
    () => validateAccountIdentityReceipt({ httpStatus: 0, code: -1, fingerprint: '' }),
    (error) => error.code === 'login_required'
  );
});

test('an unavailable identity check is retried once and recovers on a fresh target, a login failure is not', async () => {
  let calls = 0;
  const receipt = await retryTransientIdentityRead(async () => {
    calls += 1;
    if (calls === 1) {
      const error = new Error('DeepSeek account identity check temporarily failed (http 429)');
      error.code = 'identity_check_unavailable';
      throw error;
    }
    return { identityVerified: true };
  });
  assert.equal(calls, 2);
  assert.equal(receipt.identityVerified, true);
  const unavailable = new Error('DeepSeek account identity check temporarily failed (http 503)');
  unavailable.code = 'identity_check_unavailable';
  assert.equal(shouldRecoverIdentityOnFreshTarget(unavailable), true);
  assert.equal(shouldRecoverIdentityOnFreshTarget(new Error('CDP command timed out: Runtime.evaluate after 20000ms')), true);
  const loggedOut = new Error('DeepSeek account identity could not be verified; login may be required (http 401)');
  loggedOut.code = 'login_required';
  assert.equal(shouldRecoverIdentityOnFreshTarget(loggedOut), false);
});

test('non-default account worker uses isolated profile/session store by default', () => {
  const previousBootstrap = process.env.DEEPSEEK_WEB_PROFILE_BOOTSTRAP;
  delete process.env.DEEPSEEK_WEB_PROFILE_BOOTSTRAP;
  try {
    const accountKey = 'unit-account-isolation-test';
    const worker = resolveWorkerConfig({ accountKey });

    assert.equal(worker.accountKey, accountKey);
    assert.equal(worker.profileBootstrap, 'empty');
    assert.match(worker.profileRoot, /deepseek-web-unit-account-isolation-test-profile$/);
    assert.match(worker.sessionStorePath, /deepseek-web-unit-account-isolation-test-sessions\.json$/);
    assert.notEqual(worker.debugPort, resolveWorkerConfig().debugPort);
  } finally {
    if (previousBootstrap === undefined) {
      delete process.env.DEEPSEEK_WEB_PROFILE_BOOTSTRAP;
    } else {
      process.env.DEEPSEEK_WEB_PROFILE_BOOTSTRAP = previousBootstrap;
    }
  }
});

test('two non-default account workers do not share debug port, profile, or session store', () => {
  const left = resolveWorkerConfig({ accountKey: 'unit-two-account-left' });
  const right = resolveWorkerConfig({ accountKey: 'unit-two-account-right' });

  assert.notEqual(left.debugPort, right.debugPort);
  assert.notEqual(left.profileRoot, right.profileRoot);
  assert.notEqual(left.sessionStorePath, right.sessionStorePath);
  assert.equal(left.profileBootstrap, 'empty');
  assert.equal(right.profileBootstrap, 'empty');
});

test('account worker config accepts explicit per-account environment overrides', () => {
  const accountKey = 'unit-env-account';
  const suffix = 'UNIT_ENV_ACCOUNT';
  const previous = {
    debugPort: process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_DEBUG_PORT`],
    profileRoot: process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_PROFILE_ROOT`],
    sessionStore: process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_SESSION_STORE`],
    chromeLog: process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_CHROME_LOG`],
    bootstrap: process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_PROFILE_BOOTSTRAP`]
  };
  process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_DEBUG_PORT`] = '19444';
  process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_PROFILE_ROOT`] = '/tmp/deepseek-web-unit-env-profile';
  process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_SESSION_STORE`] = '/tmp/deepseek-web-unit-env-sessions.json';
  process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_CHROME_LOG`] = '/tmp/deepseek-web-unit-env-chrome.log';
  process.env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_PROFILE_BOOTSTRAP`] = 'copy';
  try {
    const worker = resolveWorkerConfig({ accountKey });

    assert.equal(normalizeAccountKey('unit env/account'), 'unit_env_account');
    assert.equal(worker.debugPort, 19444);
    assert.equal(worker.profileRoot, '/tmp/deepseek-web-unit-env-profile');
    assert.equal(worker.sessionStorePath, '/tmp/deepseek-web-unit-env-sessions.json');
    assert.equal(worker.chromeLogPath, '/tmp/deepseek-web-unit-env-chrome.log');
    assert.equal(worker.profileBootstrap, 'copy');
  } finally {
    for (const [name, value] of Object.entries({
      DEBUG_PORT: previous.debugPort,
      PROFILE_ROOT: previous.profileRoot,
      SESSION_STORE: previous.sessionStore,
      CHROME_LOG: previous.chromeLog,
      PROFILE_BOOTSTRAP: previous.bootstrap
    })) {
      const envKey = `DEEPSEEK_WEB_ACCOUNT_${suffix}_${name}`;
      if (value === undefined) {
        delete process.env[envKey];
      } else {
        process.env[envKey] = value;
      }
    }
  }
});

test('account worker config rejects profile, session store, and debug port conflicts', () => {
  const suffix = (accountKey) => accountKey.replace(/[^a-zA-Z0-9]/g, '_').toUpperCase();
  const setEnv = (accountKey, values) => {
    const envSuffix = suffix(accountKey);
    for (const [name, value] of Object.entries(values)) {
      process.env[`DEEPSEEK_WEB_ACCOUNT_${envSuffix}_${name}`] = String(value);
    }
  };
  const clearEnv = (accountKey) => {
    const envSuffix = suffix(accountKey);
    for (const name of ['DEBUG_PORT', 'PROFILE_ROOT', 'SESSION_STORE', 'CHROME_LOG', 'PROFILE_BOOTSTRAP']) {
      delete process.env[`DEEPSEEK_WEB_ACCOUNT_${envSuffix}_${name}`];
    }
  };

  const base = 'unit-conflict-base';
  const sameProfile = 'unit-conflict-same-profile';
  const sameSession = 'unit-conflict-same-session';
  const samePort = 'unit-conflict-same-port';
  try {
    setEnv(base, {
      DEBUG_PORT: 19550,
      PROFILE_ROOT: '/tmp/dswb-unit-conflict-profile-a',
      SESSION_STORE: '/tmp/dswb-unit-conflict-sessions-a.json',
      CHROME_LOG: '/tmp/dswb-unit-conflict-a.log'
    });
    setEnv(sameProfile, {
      DEBUG_PORT: 19551,
      PROFILE_ROOT: '/tmp/dswb-unit-conflict-profile-a',
      SESSION_STORE: '/tmp/dswb-unit-conflict-sessions-b.json',
      CHROME_LOG: '/tmp/dswb-unit-conflict-b.log'
    });
    setEnv(sameSession, {
      DEBUG_PORT: 19552,
      PROFILE_ROOT: '/tmp/dswb-unit-conflict-profile-c',
      SESSION_STORE: '/tmp/dswb-unit-conflict-sessions-a.json',
      CHROME_LOG: '/tmp/dswb-unit-conflict-c.log'
    });
    setEnv(samePort, {
      DEBUG_PORT: 19550,
      PROFILE_ROOT: '/tmp/dswb-unit-conflict-profile-d',
      SESSION_STORE: '/tmp/dswb-unit-conflict-sessions-d.json',
      CHROME_LOG: '/tmp/dswb-unit-conflict-d.log'
    });

    resolveWorkerConfig({ accountKey: base });
    assert.throws(() => resolveWorkerConfig({ accountKey: sameProfile }), /profile conflict/);
    assert.throws(() => resolveWorkerConfig({ accountKey: sameSession }), /session store conflict/);
    assert.throws(() => resolveWorkerConfig({ accountKey: samePort }), /debug port conflict/);
  } finally {
    for (const accountKey of [base, sameProfile, sameSession, samePort]) {
      clearEnv(accountKey);
    }
  }
});

test('debugger profile helpers read user-data-dir and chrome version profile path', () => {
  assert.equal(
    extractUserDataDirFromCommandLine(['chrome', '--flag', '--user-data-dir=/tmp/dswb-profile']),
    '/tmp/dswb-profile'
  );
  assert.equal(
    extractUserDataDirFromCommandLine(['chrome', '--user-data-dir', '/tmp/dswb-profile-2']),
    '/tmp/dswb-profile-2'
  );
  assert.equal(
    parseProfilePathFromChromeVersionText('Executable Path /Applications/Chrome\nProfile Path /tmp/dswb-profile/Default\nCommand Line ...'),
    '/tmp/dswb-profile/Default'
  );
});

test('expert mode state does not treat deep-think alone as professional mode', () => {
  assert.deepEqual(buildModeState([
    { label: '专家模式', found: false },
    { label: '快速模式', found: true, selected: true },
    { label: '页面模式', found: true, mode: 'fast', requestedMode: 'expert', modeVerified: false },
    { label: '深度思考', found: true, pressedAfter: 'true' },
    { label: '智能搜索', found: false, pressedAfter: 'false' }
  ], 'expert'), {
    bridgeMode: 'expert',
    expertMode: false,
    quickMode: true,
    headerMode: 'fast',
    modeVerified: false,
    deepThinkEnabled: true,
    searchEnabled: false
  });
});

test('expert mode state requires the professional page mode to be verified', () => {
  assert.deepEqual(buildModeState([
    { label: '专家模式', found: true, selectedAfter: true },
    { label: '快速模式', found: true, selected: false },
    { label: '页面模式', found: true, mode: 'expert', requestedMode: 'expert', modeVerified: true },
    { label: '深度思考', found: true, pressedAfter: 'true' },
    { label: '智能搜索', found: true, pressedAfter: 'true' }
  ], 'expert'), {
    bridgeMode: 'expert',
    expertMode: true,
    quickMode: false,
    headerMode: 'expert',
    modeVerified: true,
    deepThinkEnabled: true,
    searchEnabled: true
  });
});

test('unified DeepSeek UI verifies expert through deep thinking and search toggles', () => {
  assert.deepEqual(buildModeState([
    { label: '页面模式', found: true, mode: 'unified', requestedMode: 'expert', modeVerified: true },
    { label: '深度思考', found: true, pressedAfter: 'true' },
    { label: '智能搜索', found: true, pressedAfter: 'true' }
  ], 'expert'), {
    bridgeMode: 'expert',
    expertMode: true,
    quickMode: false,
    headerMode: 'unified',
    modeVerified: true,
    deepThinkEnabled: true,
    searchEnabled: true
  });
});

test('unified DeepSeek UI verifies fast only after deep thinking is off and search is on', () => {
  const updates = [
    { label: '页面模式', found: true, mode: 'unified', requestedMode: 'fast', modeVerified: true },
    { label: '深度思考', found: true, pressedAfter: 'false' },
    { label: '智能搜索', found: true, pressedAfter: 'true' }
  ];
  assert.deepEqual(buildModeState(updates, 'fast'), {
    bridgeMode: 'fast',
    expertMode: false,
    quickMode: true,
    headerMode: 'unified',
    modeVerified: true,
    deepThinkEnabled: false,
    searchEnabled: true
  });
  updates[0].modeVerified = false;
  updates[2].pressedAfter = 'false';
  assert.equal(buildModeState(updates, 'fast').modeVerified, false);
});

test('submitted turn keeps an identical retry answer instead of filtering it as history', () => {
  const repeatedAnswer = {
    text: '{"stance":"中性","forecasts":[]}',
    className: 'ds-markdown ds-assistant-message-main-content'
  };
  const baseline = {
    bodyText: repeatedAnswer.text,
    links: [],
    candidateTexts: [repeatedAnswer],
    assistantBlockCount: 1
  };
  const current = {
    bodyText: repeatedAnswer.text,
    links: [],
    candidateTexts: [repeatedAnswer],
    assistantBlockCount: 1,
    allAssistantBlockCount: 2,
    submittedTurnMatched: true,
    generationActive: false
  };

  const result = diffSearchState(current, baseline);

  assert.equal(result.submittedTurnMatched, true);
  assert.equal(result.candidateTexts.length, 1);
  assert.equal(result.candidateTexts[0].text, repeatedAnswer.text);
  assert.equal(result.bodyDelta, repeatedAnswer.text);
});

test('legacy baseline diff remains available when the submitted user turn is not visible', () => {
  const baseline = {
    bodyText: 'old answer',
    links: [],
    candidateTexts: [{ text: 'old answer', className: 'assistant' }],
    assistantBlockCount: 1
  };
  const current = {
    bodyText: 'new answer',
    links: [],
    candidateTexts: [
      { text: 'old answer', className: 'assistant' },
      { text: 'new answer', className: 'assistant' }
    ],
    assistantBlockCount: 2,
    submittedTurnMatched: false,
    generationActive: false
  };

  const result = diffSearchState(current, baseline);

  assert.deepEqual(result.candidateTexts.map(item => item.text), ['new answer']);
  assert.equal(result.assistantBlockCount, 1);
});

test('hasCompleteJson accepts fenced or surrounded JSON but rejects truncation', () => {
  assert.equal(hasCompleteJson('```json\n{"ok":true}\n```'), true);
  assert.equal(hasCompleteJson('说明文字\n{"ok":true}\n请核对'), true);
  assert.equal(hasCompleteJson('{"ok":'), false);
});

test('parseDeepSeekCompletionSse rebuilds the RESPONSE fragment independently of DOM rendering', () => {
  const body = [
    'event: ready',
    'data: {"request_message_id":5,"response_message_id":6}',
    '',
    'data: {"v":{"response":{"status":"WIP","fragments":[{"type":"THINK","content":"思考中"}]}}}',
    '',
    'data: {"p":"response/fragments","o":"APPEND","v":[{"type":"RESPONSE","content":"{\\"status\\":\\""}]}',
    'data: {"p":"response/fragments/-1/content","v":"ok"}',
    'data: {"v":"\\",\\"check\\":\\"network\\"}"}',
    'data: {"p":"response/status","o":"SET","v":"FINISHED"}',
    '',
    'event: close'
  ].join('\n');

  assert.deepEqual(parseDeepSeekCompletionSse(body), {
    answerText: '{"status":"ok","check":"network"}',
    finished: true,
    responseFinished: true,
    searchCount: 0,
    searchToolCallCount: 0,
    openedPageCount: 0,
    links: []
  });
});

test('DeepSeek TOOL_SEARCH results provide source URLs without a rendered page', () => {
  const body = [
    'data: {"v":{"response":{"status":"WIP","fragments":[]}}}',
    'data: {"p":"response","o":"BATCH","v":[{"p":"fragments","o":"APPEND","v":[{"id":11,"type":"TOOL_SEARCH","results":[]}]}]}',
    'data: {"p":"response/fragments/-1/results","o":"SET","v":[{"url":"https://www.un.org/a","title":"A"}]}',
    'data: {"p":"response/fragments/-1/results","o":"SET","v":[{"url":"https://www.un.org/a","title":"A"},{"url":"https://www.un.org/b","title":"B"}]}',
    'data: {"p":"response","o":"BATCH","v":[{"p":"fragments","o":"APPEND","v":[{"id":15,"type":"TOOL_OPEN","result":{"url":"https://www.un.org/charter","title":"Charter"}}]}]}',
    'data: {"p":"response/fragments","o":"APPEND","v":[{"id":12,"type":"RESPONSE","content":"{\\"ok\\":true}"}]}',
    'data: {"p":"response/status","o":"SET","v":"FINISHED"}',
    'event: close'
  ].join('\n');
  assert.deepEqual(parseDeepSeekCompletionSse(body), {
    answerText: '{"ok":true}', finished: true, responseFinished: true, searchCount: 2,
    searchToolCallCount: 1,
    openedPageCount: 1,
    links: [{ href: 'https://www.un.org/a', text: 'A' }, { href: 'https://www.un.org/b', text: 'B' },
      { href: 'https://www.un.org/charter', text: 'Charter' }]
  });
});

test('completion capture accepts only a request with the submitted prompt', async () => {
  const listeners = new Map();
  const client = {
    on: (name, handler) => listeners.set(name, handler),
    send: async () => ({ body: [
      'data: {"p":"response/fragments","o":"APPEND","v":[{"id":1,"type":"TOOL_SEARCH","results":[{"url":"https://www.un.org/a"}]}]}',
      'data: {"p":"response/fragments","o":"APPEND","v":[{"id":2,"type":"RESPONSE","content":"ok"}]}',
      'event: close'
    ].join('\n') })
  };
  const capture = createDeepSeekCompletionCapture(client, 'submitted prompt');
  listeners.get('Network.requestWillBeSent')({ requestId: 'wrong', request: {
    url: 'https://chat.deepseek.com/api/v0/chat/completion', method: 'POST',
    postData: JSON.stringify({ prompt: 'historical prompt', search_enabled: true })
  } });
  listeners.get('Network.loadingFinished')({ requestId: 'wrong' });
  assert.equal(capture.requestMatched, false);
  listeners.get('Network.requestWillBeSent')({ requestId: 'current', request: {
    url: 'https://chat.deepseek.com/api/v0/chat/completion', method: 'POST',
    postData: JSON.stringify({ prompt: 'submitted prompt', search_enabled: true })
  } });
  listeners.get('Network.loadingFinished')({ requestId: 'current' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(capture.requestMatched, true);
  assert.equal(capture.responseFinished, false);
  assert.equal(capture.requestSearchEnabled, true);
  assert.equal(capture.searchCount, 1);
  assert.deepEqual(capture.links.map(item => item.href), ['https://www.un.org/a']);
});

test('empty completion stream is non-fatal so DOM polling can continue', () => {
  assert.equal(isFatalCompletionCaptureError('DeepSeek completion stream finished without response content'), false);
  assert.equal(isFatalCompletionCaptureError('DeepSeek completion body unavailable: missing'), false);
  assert.equal(isFatalCompletionCaptureError('DeepSeek completion HTTP 500'), true);
  assert.equal(isFatalCompletionCaptureError('DeepSeek completion network failed: net::ERR_FAILED'), true);
});

test('non-json quick return waits while DeepSeek generation is still active', () => {
  const now = 100_000;
  const base = {
    hasTextSignal: true,
    hasLinkOnlySignal: false,
    stableCount: 5,
    generationActive: true,
    networkAnswerComplete: false,
    firstContentAt: now - 20_000,
    firstLinkOnlyAt: 0,
    quickDeadline: now - 1,
    now
  };

  assert.equal(resolveNonJsonCompletionReason(base), null);
  assert.equal(resolveNonJsonCompletionReason({ ...base, generationActive: false }), 'stable_signal');
  assert.equal(resolveNonJsonCompletionReason({ ...base, networkAnswerComplete: true }), 'complete_network');
});

test('no-search model inference requires a completed response stream', () => {
  for (const reason of ['quick_budget_reached', 'signal_budget_reached', 'stable_signal']) {
    assert.equal(acceptsNonJsonCompletionReason(reason, false), false, reason);
    assert.equal(acceptsNonJsonCompletionReason(reason, true), true, reason);
  }
  assert.equal(acceptsNonJsonCompletionReason('complete_network', false), true);
  assert.equal(acceptsNonJsonCompletionReason(null, false), false);
});

test('no-search inference rejects closed partial streams and failed network captures', () => {
  const partial = parseDeepSeekCompletionSse([
    'data: {"p":"response/fragments","o":"APPEND","v":[{"type":"RESPONSE","content":"Partial answer"}]}',
    'event: close'
  ].join('\n'));
  assert.equal(partial.finished, true);
  assert.equal(partial.responseFinished, false);
  const capture = { completed: true, requestMatched: true,
    answerText: partial.answerText, responseFinished: partial.responseFinished };
  assert.equal(hasCompletedNetworkAnswer(capture, false), false);
  assert.equal(hasCompletedNetworkAnswer({ ...capture, responseFinished: true,
    error: 'DeepSeek completion HTTP 500' }, false), false);
  assert.equal(hasCompletedNetworkAnswer({ ...capture, responseFinished: true }, false), true);
  assert.equal(hasCompletedNetworkAnswer({ ...capture, responseFinished: true,
    requestMatched: false }, false), false);
});

test('no-search JSON inference rejects stable DOM text without network completion proof', () => {
  assert.equal(acceptsStableJsonCompletion(true, false, 3, false), false);
  assert.equal(acceptsStableJsonCompletion(true, false, 3, true), true);
  assert.equal(acceptsStableJsonCompletion(true, true, 3, true), false);
  assert.equal(acceptsStableJsonCompletion(false, false, 3, true), false);
});

test('normalizeResult marks active timeout text as incomplete instead of success', () => {
  const result = normalizeResult({
    candidateTexts: [{ text: '这是一段仍在生成中的会议分析正文，已经超过四十个字符但没有完成。' }],
    links: [],
    completionReason: 'timeout_with_last_state',
    accountKey: 'stock',
    debugPort: 9344,
    profileRoot: '/tmp/deepseek-stock-profile',
    generationActive: true
  });

  assert.equal(result.contentStatus, 'incomplete');
  assert.equal(result.completionReason, 'timeout_with_last_state');
  assert.equal(result.accountKey, 'stock');
  assert.equal(result.debugPort, 9344);
  assert.equal(result.profileRoot, '/tmp/deepseek-stock-profile');
  assert.equal(result.generationActive, true);
});

test('normalizeResult does not accept a non-JSON budget or timeout as completed inference', () => {
  for (const completionReason of [
    'quick_budget_reached',
    'signal_budget_reached',
    'timeout_with_last_state'
  ]) {
    const result = normalizeResult({
      candidateTexts: [{ text: '==REPLY==\n这段回答来自当前页面，但尚无稳定或网络完成证据。' }],
      links: [],
      completionReason,
      generationActive: false
    });
    assert.equal(result.contentStatus, 'incomplete', completionReason);
    assert.equal(result.completionReason, completionReason);
    assert.ok(result.answerText);
  }
});
