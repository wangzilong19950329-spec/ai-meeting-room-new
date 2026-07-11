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
  isFatalCompletionCaptureError
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
    generationActive: true
  });

  assert.equal(result.contentStatus, 'incomplete');
  assert.equal(result.completionReason, 'timeout_with_last_state');
  assert.equal(result.generationActive, true);
});
