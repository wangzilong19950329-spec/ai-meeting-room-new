'use strict';

const DEFAULT_MODEL_ID = 'deepseek-web-bridge';
const MODEL_ALIASES = new Set([DEFAULT_MODEL_ID, 'deepseek-chat']);

function makeOpenAiError(message, code, type = 'invalid_request_error', param = null) {
  return {
    error: {
      message,
      type,
      param,
      code
    }
  };
}

function getPathname(req) {
  return new URL(req.url || '/', 'http://127.0.0.1').pathname;
}

function getHeader(req, name) {
  const headers = req.headers || {};
  const lower = name.toLowerCase();
  return headers[lower] || headers[name] || '';
}

function isAuthorized(req) {
  const expected = process.env.DEEPSEEK_OPENAI_FACADE_API_KEY;
  if (!expected) return true;
  return getHeader(req, 'authorization') === `Bearer ${expected}`;
}

function modelList(created = 0) {
  return {
    object: 'list',
    data: [
      {
        id: DEFAULT_MODEL_ID,
        object: 'model',
        created,
        owned_by: 'ai-meeting-room'
      },
      {
        id: 'deepseek-chat',
        object: 'model',
        created,
        owned_by: 'ai-meeting-room'
      }
    ]
  };
}

function flattenContent(content) {
  if (typeof content === 'string') {
    return { ok: true, text: content };
  }
  if (!Array.isArray(content)) {
    return { ok: true, text: content == null ? '' : String(content) };
  }

  const parts = [];
  for (const item of content) {
    if (!item || typeof item !== 'object') {
      continue;
    }
    if (item.type === 'text' || item.type === 'input_text') {
      parts.push(typeof item.text === 'string' ? item.text : '');
      continue;
    }
    return {
      ok: false,
      code: 'multimodal_unsupported',
      message: 'deepseek-web-bridge adapter only supports text messages in v1'
    };
  }
  return { ok: true, text: parts.join('\n') };
}

function roleLabel(role) {
  switch (role) {
    case 'system':
      return 'System';
    case 'developer':
      return 'Developer';
    case 'assistant':
      return 'Assistant';
    case 'tool':
      return 'Tool';
    case 'user':
    default:
      return 'User';
  }
}

function normalizeMessages(messages) {
  if (!Array.isArray(messages) || messages.length === 0) {
    return {
      ok: false,
      code: 'missing_messages',
      message: 'messages must be a non-empty array'
    };
  }

  const normalized = [];
  for (const message of messages) {
    if (!message || typeof message !== 'object') {
      return {
        ok: false,
        code: 'invalid_message',
        message: 'each message must be an object'
      };
    }
    const flattened = flattenContent(message.content);
    if (!flattened.ok) {
      return flattened;
    }
    normalized.push({
      role: typeof message.role === 'string' ? message.role : 'user',
      content: flattened.text
    });
  }
  return { ok: true, messages: normalized };
}

function buildBridgeQuery(messages, maxTokens) {
  const systemLines = [];
  const historyLines = [];
  let lastUser = '';

  for (const message of messages) {
    const text = (message.content || '').trim();
    if (!text) continue;

    if (message.role === 'system' || message.role === 'developer') {
      systemLines.push(text);
      continue;
    }

    if (message.role === 'user') {
      lastUser = text;
      continue;
    }

    historyLines.push(`${roleLabel(message.role)}: ${text}`);
  }

  const sections = [];
  if (systemLines.length) {
    sections.push(`System instructions:\n${systemLines.join('\n\n')}`);
  }
  if (historyLines.length) {
    sections.push(`Conversation context:\n${historyLines.join('\n\n')}`);
  }
  sections.push(`User task:\n${lastUser || messages.map((message) => `${roleLabel(message.role)}: ${message.content}`).join('\n\n')}`);

  const outputRules = [
    'Return only the final answer for this task.',
    'Do not mention that you are using a web bridge.',
    'If JSON is requested, return parseable JSON only and do not wrap it in Markdown fences.'
  ];
  if (Number.isFinite(maxTokens) && maxTokens > 0) {
    outputRules.push(`Keep the answer roughly within ${maxTokens} tokens when possible.`);
  }
  sections.push(`Output constraints:\n- ${outputRules.join('\n- ')}`);

  return sections.join('\n\n');
}

function wantsJson(body) {
  if (body && body.expectedJson === true) return true;
  if (body && body.response_format && body.response_format.type === 'json_object') return true;
  if (body && body.metadata && body.metadata.expectedJson === true) return true;
  if (body && body.metadata && body.metadata.expected_json === true) return true;
  return false;
}

function normalizeMode(value) {
  const mode = typeof value === 'string' ? value.trim().toLowerCase() : '';
  if (mode === 'fast' || mode === 'expert') return mode;
  return '';
}

function requestMode(req, body) {
  return normalizeMode(
    getHeader(req, 'x-deepseek-mode')
      || (body && body.deepseek_mode)
      || (body && body.metadata && body.metadata.deepseek_mode)
  );
}

function requestSessionKey(req, body) {
  const value = getHeader(req, 'x-deepseek-session-key')
    || (body && body.metadata && body.metadata.deepseek_session_key)
    || '';
  return typeof value === 'string' ? value.trim() : '';
}

function requestAccountKey(req, body) {
  const value = getHeader(req, 'x-deepseek-account-key')
    || (body && body.deepseek_account_key)
    || (body && body.metadata && body.metadata.deepseek_account_key)
    || '';
  return typeof value === 'string' ? value.trim() : '';
}

function hasUnsupportedTools(body) {
  const hasConfiguredValue = (value) => {
    if (Array.isArray(value)) return value.length > 0;
    return Boolean(value);
  };
  const hasNonNoneChoice = (value) => hasConfiguredValue(value) && value !== 'none';

  return Boolean(
    body && (
      hasConfiguredValue(body.tools)
      || hasNonNoneChoice(body.tool_choice)
      || hasConfiguredValue(body.functions)
      || hasNonNoneChoice(body.function_call)
      || (body.builtInTools && Object.keys(body.builtInTools).length > 0)
    )
  );
}

function validateModel(body) {
  const model = body && typeof body.model === 'string' ? body.model.trim() : '';
  if (!model) {
    return {
      ok: false,
      code: 'missing_model',
      message: 'model is required'
    };
  }
  if (!MODEL_ALIASES.has(model)) {
    return {
      ok: false,
      code: 'model_not_found',
      message: `Model '${model}' is not available from deepseek-web-bridge adapter`
    };
  }
  return { ok: true, model };
}

function isStrictJson(text) {
  if (typeof text !== 'string') return false;
  try {
    JSON.parse(text.trim());
    return true;
  } catch (_) {
    return false;
  }
}

function isMockEnabled() {
  return ['1', 'true', 'yes'].includes(String(process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK || '').toLowerCase());
}

function mockAnswerText(expectedJson) {
  const configured = process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT;
  if (configured && (!expectedJson || isStrictJson(configured))) {
    return configured;
  }
  if (expectedJson) {
    return JSON.stringify({
      ok: true,
      source: 'deepseek-openai-adapter-mock'
    });
  }
  return configured || 'deepseek-openai-adapter mock response';
}

function bridgeErrorToOpenAi(statusCode, payload) {
  const code = payload && payload.error ? payload.error : 'bridge_error';
  const message = payload && payload.message ? payload.message : `DeepSeek web bridge error: ${code}`;
  const type = statusCode >= 500 ? 'server_error' : 'invalid_request_error';
  return makeOpenAiError(message, code, type);
}

function chatCompletionResponse({ body, answerText, bridgeResult, created }) {
  const model = body.model;
  return {
    id: `chatcmpl-dswb-${created}`,
    object: 'chat.completion',
    created,
    model,
    choices: [
      {
        index: 0,
        message: {
          role: 'assistant',
          content: answerText
        },
        finish_reason: 'stop'
      }
    ],
    usage: {
      prompt_tokens: 0,
      completion_tokens: 0,
      total_tokens: 0
    },
    deepseek_web_bridge: {
      contentStatus: bridgeResult && bridgeResult.contentStatus || null,
      completionReason: bridgeResult && bridgeResult.completionReason || null,
      answerLength: answerText.length,
      mock: Boolean(bridgeResult && bridgeResult.mock)
    }
  };
}

async function readJsonBody(req, readBody) {
  let raw;
  try {
    raw = await readBody(req);
  } catch (error) {
    if (error && String(error.message || error).toLowerCase().includes('too large')) {
      return {
        ok: false,
        statusCode: 413,
        payload: makeOpenAiError('Request body is too large', 'request_too_large')
      };
    }
    throw error;
  }

  try {
    return { ok: true, body: raw ? JSON.parse(raw) : {} };
  } catch (_) {
    return {
      ok: false,
      statusCode: 400,
      payload: makeOpenAiError('Request body must be valid JSON', 'invalid_json_request')
    };
  }
}

async function handleChatCompletions(req, res, context) {
  const parsed = await readJsonBody(req, context.readBody);
  if (!parsed.ok) {
    context.sendJson(res, parsed.statusCode, parsed.payload);
    return true;
  }

  const body = parsed.body;
  const modelValidation = validateModel(body);
  if (!modelValidation.ok) {
    context.sendJson(res, 400, makeOpenAiError(modelValidation.message, modelValidation.code, 'invalid_request_error', 'model'));
    return true;
  }
  body.model = modelValidation.model;

  if (body.stream === true) {
    context.sendJson(res, 400, makeOpenAiError(
      'Streaming is not supported by deepseek-web-bridge adapter v1',
      'streaming_unsupported'
    ));
    return true;
  }

  if (hasUnsupportedTools(body)) {
    context.sendJson(res, 400, makeOpenAiError(
      'Tool calling is not supported by deepseek-web-bridge adapter v1',
      'tool_calling_unsupported'
    ));
    return true;
  }

  const normalized = normalizeMessages(body.messages);
  if (!normalized.ok) {
    context.sendJson(res, 400, makeOpenAiError(normalized.message, normalized.code));
    return true;
  }

  const expectedJson = wantsJson(body);
  const bridgeRequest = {
    query: buildBridgeQuery(normalized.messages, body.max_tokens || body.maxTokens),
    expectedJson,
    mode: requestMode(req, body),
    accountKey: requestAccountKey(req, body),
    sessionKey: requestSessionKey(req, body),
    closeTargetAfter: false
  };

  if (isMockEnabled()) {
    const answerText = mockAnswerText(expectedJson);
    const created = Math.floor(Date.now() / 1000);
    context.sendJson(res, 200, chatCompletionResponse({
      body,
      answerText,
      bridgeResult: {
        contentStatus: 'success',
        completionReason: 'mock',
        mock: true
      },
      created
    }));
    return true;
  }

  let bridgeResponse;
  try {
    bridgeResponse = await context.invokeSearch(bridgeRequest);
  } catch (error) {
    context.sendJson(res, 502, makeOpenAiError(
      error && error.message ? error.message : 'Failed to invoke DeepSeek web bridge',
      'bridge_invocation_failed',
      'server_error'
    ));
    return true;
  }
  if (!bridgeResponse || bridgeResponse.statusCode !== 200 || !bridgeResponse.payload || bridgeResponse.payload.ok !== true) {
    const statusCode = bridgeResponse && bridgeResponse.statusCode ? bridgeResponse.statusCode : 502;
    context.sendJson(res, statusCode, bridgeErrorToOpenAi(statusCode, bridgeResponse && bridgeResponse.payload));
    return true;
  }

  const bridgeResult = bridgeResponse.payload.result || {};
  if (bridgeResult.contentStatus === 'incomplete') {
    context.sendJson(res, 504, makeOpenAiError(
      'DeepSeek web bridge returned incomplete generation',
      'incomplete_generation',
      'server_error'
    ));
    return true;
  }

  const answerText = typeof bridgeResult.answerText === 'string' ? bridgeResult.answerText : '';
  if (!answerText.trim()) {
    context.sendJson(res, 502, makeOpenAiError(
      'DeepSeek web bridge returned an empty answer',
      'empty_answer',
      'server_error'
    ));
    return true;
  }

  if (expectedJson && !isStrictJson(answerText)) {
    context.sendJson(res, 502, makeOpenAiError(
      'DeepSeek web bridge returned invalid JSON for JSON mode',
      'invalid_json',
      'server_error'
    ));
    return true;
  }

  const created = Math.floor(Date.now() / 1000);
  context.sendJson(res, 200, chatCompletionResponse({ body, answerText, bridgeResult, created }));
  return true;
}

async function handleOpenAiAdapterRequest(req, res, context) {
  const pathname = getPathname(req);
  if (!pathname.startsWith('/v1/')) {
    return false;
  }

  if (!isAuthorized(req)) {
    context.sendJson(res, 401, makeOpenAiError('Invalid API key', 'invalid_api_key', 'authentication_error'));
    return true;
  }

  if (req.method === 'GET' && pathname === '/v1/models') {
    context.sendJson(res, 200, modelList(Math.floor(Date.now() / 1000)));
    return true;
  }

  if (req.method === 'POST' && pathname === '/v1/responses') {
    context.sendJson(res, 400, makeOpenAiError(
      'Responses API is not supported by deepseek-web-bridge adapter v1. Disable Responses API and use /v1/chat/completions.',
      'responses_api_unsupported'
    ));
    return true;
  }

  if (req.method === 'POST' && pathname === '/v1/chat/completions') {
    return handleChatCompletions(req, res, context);
  }

  context.sendJson(res, 404, makeOpenAiError(`Unsupported OpenAI-compatible endpoint: ${pathname}`, 'not_found'));
  return true;
}

module.exports = {
  handleOpenAiAdapterRequest,
  _internals: {
    buildBridgeQuery,
    chatCompletionResponse,
    flattenContent,
    hasUnsupportedTools,
    isMockEnabled,
    isStrictJson,
    makeOpenAiError,
    modelList,
    mockAnswerText,
    normalizeMessages,
    requestAccountKey,
    wantsJson
  }
};
