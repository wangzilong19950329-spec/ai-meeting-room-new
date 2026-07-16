'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  buildTurnSignature,
  diffSearchState,
  hasCompleteJson,
  parseDeepSeekCompletionSse,
  resolveNonJsonCompletionReason,
  normalizeResult,
  normalizeBridgeMode,
  buildModeState,
  isFatalCompletionCaptureError,
  extractUserDataDirFromCommandLine,
  normalizeAccountKey,
  parseProfilePathFromChromeVersionText,
  resolveWorkerConfig
} = require('./deepseek-web-client')._internals;

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
    finished: true
  });
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
