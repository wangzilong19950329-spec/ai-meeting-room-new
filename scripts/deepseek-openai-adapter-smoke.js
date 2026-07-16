#!/usr/bin/env node
'use strict';

const DEFAULT_BASE_URL = process.env.DEEPSEEK_OPENAI_BASE_URL || 'http://127.0.0.1:8789/v1';
const DEBUG_PORT = Number(process.env.DEEPSEEK_WEB_DEBUG_PORT || 9333);
const RUN_CHAT = process.argv.includes('--chat') || process.argv.includes('--real-chat');
const RUN_JSON_CHAT = process.argv.includes('--json-chat');

function apiUrl(path) {
  return `${DEFAULT_BASE_URL.replace(/\/$/, '')}${path}`;
}

async function request(path, options = {}) {
  const response = await fetch(apiUrl(path), {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${process.env.DEEPSEEK_OPENAI_API_KEY || process.env.DEEPSEEK_OPENAI_FACADE_API_KEY || 'local-dev'}`,
      ...(options.headers || {})
    }
  });
  const text = await response.text();
  let payload;
  try {
    payload = text ? JSON.parse(text) : {};
  } catch (_) {
    payload = { raw: text };
  }
  return {
    status: response.status,
    payload
  };
}

function assert(condition, message, details) {
  if (!condition) {
    const error = new Error(message);
    error.details = details;
    throw error;
  }
}

async function checkModels() {
  const result = await request('/models', { method: 'GET' });
  const ids = result.payload && Array.isArray(result.payload.data)
    ? result.payload.data.map((model) => model.id)
    : [];
  assert(result.status === 200, 'GET /v1/models did not return HTTP 200', result);
  assert(ids.includes('deepseek-web-bridge'), 'GET /v1/models did not include deepseek-web-bridge', result);
  return { status: result.status, models: ids };
}

async function checkResponsesUnsupported() {
  const result = await request('/responses', {
    method: 'POST',
    body: JSON.stringify({ input: 'hi' })
  });
  assert(result.status === 400, 'POST /v1/responses did not return HTTP 400', result);
  assert(
    result.payload && result.payload.error && result.payload.error.code === 'responses_api_unsupported',
    'POST /v1/responses did not return responses_api_unsupported',
    result
  );
  return { status: result.status, code: result.payload.error.code };
}

async function checkStreamingUnsupported() {
  const result = await request('/chat/completions', {
    method: 'POST',
    body: JSON.stringify({
      model: 'deepseek-web-bridge',
      stream: true,
      messages: [{ role: 'user', content: 'hi' }]
    })
  });
  assert(result.status === 400, 'stream=true did not return HTTP 400', result);
  assert(
    result.payload && result.payload.error && result.payload.error.code === 'streaming_unsupported',
    'stream=true did not return streaming_unsupported',
    result
  );
  return { status: result.status, code: result.payload.error.code };
}

async function checkToolsUnsupported() {
  const result = await request('/chat/completions', {
    method: 'POST',
    body: JSON.stringify({
      model: 'deepseek-web-bridge',
      tools: [{ type: 'function', function: { name: 'noop' } }],
      messages: [{ role: 'user', content: 'hi' }]
    })
  });
  assert(result.status === 400, 'tools did not return HTTP 400', result);
  assert(
    result.payload && result.payload.error && result.payload.error.code === 'tool_calling_unsupported',
    'tools did not return tool_calling_unsupported',
    result
  );
  return { status: result.status, code: result.payload.error.code };
}

async function checkDeepSeekLoginState() {
  let targets;
  try {
    const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`);
    targets = await response.json();
  } catch (error) {
    return {
      status: 'unknown',
      debugPort: DEBUG_PORT,
      message: error && error.message ? error.message : String(error)
    };
  }

  const deepseekTargets = Array.isArray(targets)
    ? targets.filter((target) => {
      const haystack = `${target.title || ''} ${target.url || ''}`.toLowerCase();
      return target.type === 'page' && haystack.includes('chat.deepseek.com');
    })
    : [];

  if (deepseekTargets.length === 0) {
    return {
      status: 'missing_deepseek_target',
      debugPort: DEBUG_PORT,
      message: 'No chat.deepseek.com page target found'
    };
  }

  const target = deepseekTargets[0];
  if ((target.url || '').includes('/sign_in')) {
    return {
      status: 'login_required',
      debugPort: DEBUG_PORT,
      targetId: target.id,
      title: target.title || '',
      url: target.url
    };
  }

  return {
    status: 'maybe_logged_in',
    debugPort: DEBUG_PORT,
    targetId: target.id,
    title: target.title || '',
    url: target.url || ''
  };
}

async function checkRealChat() {
  const result = await request('/chat/completions', {
    method: 'POST',
    body: JSON.stringify({
      model: 'deepseek-web-bridge',
      stream: false,
      max_tokens: 20,
      messages: [
        { role: 'system', content: '你是一个本地冒烟测试助手。' },
        { role: 'user', content: '请只回复两个汉字：通过' }
      ]
    })
  });

  const content = result.payload && result.payload.choices
    && result.payload.choices[0]
    && result.payload.choices[0].message
    && result.payload.choices[0].message.content;
  assert(result.status === 200, 'real chat did not return HTTP 200', result);
  assert(typeof content === 'string' && content.trim(), 'real chat returned empty content', result);
  return {
    status: result.status,
    content,
    bridge: result.payload.deepseek_web_bridge
  };
}

async function checkJsonChat() {
  const result = await request('/chat/completions', {
    method: 'POST',
    body: JSON.stringify({
      model: 'deepseek-web-bridge',
      stream: false,
      max_tokens: 80,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: 'You are a local smoke test assistant.' },
        { role: 'user', content: 'Return JSON exactly like {"ok":true,"kind":"json-smoke"}.' }
      ]
    })
  });

  const content = result.payload && result.payload.choices
    && result.payload.choices[0]
    && result.payload.choices[0].message
    && result.payload.choices[0].message.content;
  assert(result.status === 200, 'JSON chat did not return HTTP 200', result);
  assert(typeof content === 'string' && content.trim(), 'JSON chat returned empty content', result);

  let parsed;
  try {
    parsed = JSON.parse(content);
  } catch (_) {
    assert(false, 'JSON chat content was not parseable JSON', result);
  }

  return {
    status: result.status,
    content: parsed,
    bridge: result.payload.deepseek_web_bridge
  };
}

async function main() {
  const checks = {
    baseUrl: DEFAULT_BASE_URL,
    models: await checkModels(),
    responses: await checkResponsesUnsupported(),
    streaming: await checkStreamingUnsupported(),
    tools: await checkToolsUnsupported(),
    deepseekLogin: await checkDeepSeekLoginState()
  };

  if (RUN_CHAT) {
    checks.chatCompletion = await checkRealChat();
  } else {
    checks.chatCompletion = 'skipped; pass --chat to submit a chat completion request';
  }

  if (RUN_JSON_CHAT) {
    checks.jsonChatCompletion = await checkJsonChat();
  } else {
    checks.jsonChatCompletion = 'skipped; pass --json-chat to submit a JSON chat completion request';
  }

  console.log(JSON.stringify({ ok: true, checks }, null, 2));
}

main().catch((error) => {
  console.error(JSON.stringify({
    ok: false,
    error: error.message,
    details: error.details || null
  }, null, 2));
  process.exit(1);
});
