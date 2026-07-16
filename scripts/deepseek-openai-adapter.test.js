'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const {
  handleOpenAiAdapterRequest,
  _internals
} = require('./deepseek-openai-adapter');
const {
  isBearerAuthorized,
  sharedBridgeAuthHeaders,
  shouldRequireSharedBridgeAuth
} = require('./deepseek-bridge-auth');

function mockReq({ method = 'POST', url = '/v1/chat/completions', headers = {}, body = {} } = {}) {
  const req = new EventEmitter();
  req.method = method;
  req.url = url;
  req.headers = headers;
  req.mockBody = typeof body === 'string' ? body : JSON.stringify(body);
  return req;
}

function mockRes() {
  return {
    statusCode: null,
    headers: null,
    body: null,
    writeHead(statusCode, headers) {
      this.statusCode = statusCode;
      this.headers = headers;
    },
    end(body) {
      this.body = body;
    }
  };
}

function testContext({ bridgeResponse, onInvokeSearch } = {}) {
  return {
    readBody: async (req) => req.mockBody || '',
    sendJson: (res, statusCode, payload) => {
      const body = JSON.stringify(payload);
      res.writeHead(statusCode, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body)
      });
      res.end(body);
    },
    invokeSearch: async (payload) => {
      if (onInvokeSearch) onInvokeSearch(payload);
      return bridgeResponse || {
        statusCode: 200,
        payload: {
          ok: true,
          result: {
            answerText: 'hello from bridge',
            contentStatus: 'success',
            completionReason: 'stable'
          }
        }
      };
    }
  };
}

function parsed(res) {
  return JSON.parse(res.body);
}

test('GET /v1/models returns OpenAI-compatible model list', async () => {
  const req = mockReq({ method: 'GET', url: '/v1/models' });
  const res = mockRes();

  const handled = await handleOpenAiAdapterRequest(req, res, testContext());

  assert.equal(handled, true);
  assert.equal(res.statusCode, 200);
  assert.deepEqual(parsed(res).data.map((model) => model.id), ['deepseek-web-bridge', 'deepseek-chat']);
});

test('configured API key is required before serving /v1 routes', async () => {
  const previous = process.env.DEEPSEEK_OPENAI_FACADE_API_KEY;
  process.env.DEEPSEEK_OPENAI_FACADE_API_KEY = 'secret-key';
  try {
    const missingReq = mockReq({ method: 'GET', url: '/v1/models' });
    const missingRes = mockRes();
    await handleOpenAiAdapterRequest(missingReq, missingRes, testContext());
    assert.equal(missingRes.statusCode, 401);
    assert.equal(parsed(missingRes).error.code, 'invalid_api_key');

    const wrongReq = mockReq({
      method: 'GET',
      url: '/v1/models',
      headers: { authorization: 'Bearer wrong' }
    });
    const wrongRes = mockRes();
    await handleOpenAiAdapterRequest(wrongReq, wrongRes, testContext());
    assert.equal(wrongRes.statusCode, 401);
    assert.equal(parsed(wrongRes).error.code, 'invalid_api_key');

    const okReq = mockReq({
      method: 'GET',
      url: '/v1/models',
      headers: { authorization: 'Bearer secret-key' }
    });
    const okRes = mockRes();
    await handleOpenAiAdapterRequest(okReq, okRes, testContext());
    assert.equal(okRes.statusCode, 200);
  } finally {
    if (previous === undefined) {
      delete process.env.DEEPSEEK_OPENAI_FACADE_API_KEY;
    } else {
      process.env.DEEPSEEK_OPENAI_FACADE_API_KEY = previous;
    }
  }
});

test('shared bridge auth is required only for non-loopback bridge hosts with an API key', () => {
  assert.equal(shouldRequireSharedBridgeAuth('127.0.0.1', 'secret-key'), false);
  assert.equal(shouldRequireSharedBridgeAuth('localhost', 'secret-key'), false);
  assert.equal(shouldRequireSharedBridgeAuth('::1', 'secret-key'), false);
  assert.equal(shouldRequireSharedBridgeAuth('0.0.0.0', ''), false);
  assert.equal(shouldRequireSharedBridgeAuth('0.0.0.0', 'secret-key'), true);

  assert.deepEqual(sharedBridgeAuthHeaders('127.0.0.1', 'secret-key'), {});
  assert.deepEqual(sharedBridgeAuthHeaders('0.0.0.0', 'secret-key'), {
    Authorization: 'Bearer secret-key'
  });

  assert.equal(isBearerAuthorized({ headers: { authorization: 'Bearer secret-key' } }, 'secret-key'), true);
  assert.equal(isBearerAuthorized({ headers: { Authorization: 'Bearer secret-key' } }, 'secret-key'), true);
  assert.equal(isBearerAuthorized({ headers: { authorization: 'Bearer wrong' } }, 'secret-key'), false);
});

test('chat completion maps messages to bridge /search payload and response shape', async () => {
  let bridgePayload;
  const req = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      messages: [
        { role: 'system', content: 'You are concise.' },
        { role: 'user', content: 'Say hi.' }
      ],
      stream: false
    }
  });
  const res = mockRes();

  await handleOpenAiAdapterRequest(req, res, testContext({
    onInvokeSearch: (payload) => {
      bridgePayload = payload;
    }
  }));

  assert.equal(res.statusCode, 200);
  assert.equal(bridgePayload.expectedJson, false);
  assert.match(bridgePayload.query, /System instructions:/);
  assert.match(bridgePayload.query, /User task:\nSay hi\./);
  assert.equal(parsed(res).choices[0].message.content, 'hello from bridge');
});

test('chat completion forwards DeepSeek account routing metadata to bridge payload', async () => {
  let bridgePayload;
  const req = mockReq({
    headers: { 'x-deepseek-account-key': 'stock' },
    body: {
      model: 'deepseek-web-bridge',
      metadata: {
        deepseek_session_key: 'stock-watch-smoke'
      },
      messages: [
        { role: 'user', content: 'Say hi.' }
      ]
    }
  });
  const res = mockRes();

  await handleOpenAiAdapterRequest(req, res, testContext({
    onInvokeSearch: (payload) => {
      bridgePayload = payload;
    }
  }));

  assert.equal(res.statusCode, 200);
  assert.equal(bridgePayload.accountKey, 'stock');
  assert.equal(bridgePayload.sessionKey, 'stock-watch-smoke');
});

test('chat completion accepts DeepSeek account key from body and metadata', async () => {
  const seen = [];
  const bodyReq = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      deepseek_account_key: 'body-account',
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const bodyRes = mockRes();
  await handleOpenAiAdapterRequest(bodyReq, bodyRes, testContext({
    onInvokeSearch: (payload) => seen.push(payload.accountKey)
  }));

  const metadataReq = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      metadata: {
        deepseek_account_key: 'metadata-account'
      },
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const metadataRes = mockRes();
  await handleOpenAiAdapterRequest(metadataReq, metadataRes, testContext({
    onInvokeSearch: (payload) => seen.push(payload.accountKey)
  }));

  assert.equal(bodyRes.statusCode, 200);
  assert.equal(metadataRes.statusCode, 200);
  assert.deepEqual(seen, ['body-account', 'metadata-account']);
});

test('explicit mock mode returns a marked response without invoking bridge', async () => {
  const previousMock = process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK;
  const previousText = process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT;
  process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK = '1';
  process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT = 'mock says hi';
  try {
    let invoked = false;
    const req = mockReq({
      body: {
        model: 'deepseek-web-bridge',
        messages: [{ role: 'user', content: 'hi' }]
      }
    });
    const res = mockRes();

    await handleOpenAiAdapterRequest(req, res, testContext({
      onInvokeSearch: () => {
        invoked = true;
      }
    }));

    const body = parsed(res);
    assert.equal(invoked, false);
    assert.equal(res.statusCode, 200);
    assert.equal(body.choices[0].message.content, 'mock says hi');
    assert.equal(body.deepseek_web_bridge.completionReason, 'mock');
    assert.equal(body.deepseek_web_bridge.mock, true);
  } finally {
    if (previousMock === undefined) {
      delete process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK;
    } else {
      process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK = previousMock;
    }
    if (previousText === undefined) {
      delete process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT;
    } else {
      process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT = previousText;
    }
  }
});

test('unknown or missing model is rejected before invoking bridge', async () => {
  let invoked = false;
  const unknownReq = mockReq({
    body: {
      model: 'not-a-real-model',
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const unknownRes = mockRes();

  await handleOpenAiAdapterRequest(unknownReq, unknownRes, testContext({
    onInvokeSearch: () => {
      invoked = true;
    }
  }));

  assert.equal(invoked, false);
  assert.equal(unknownRes.statusCode, 400);
  assert.equal(parsed(unknownRes).error.code, 'model_not_found');

  const missingReq = mockReq({
    body: {
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const missingRes = mockRes();
  await handleOpenAiAdapterRequest(missingReq, missingRes, testContext());
  assert.equal(missingRes.statusCode, 400);
  assert.equal(parsed(missingRes).error.code, 'missing_model');
});

test('explicit mock mode returns valid JSON for JSON mode', async () => {
  const previousMock = process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK;
  const previousText = process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT;
  process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK = 'true';
  process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT = 'not json';
  try {
    const req = mockReq({
      body: {
        model: 'deepseek-web-bridge',
        response_format: { type: 'json_object' },
        messages: [{ role: 'user', content: 'Return JSON.' }]
      }
    });
    const res = mockRes();

    await handleOpenAiAdapterRequest(req, res, testContext());

    const content = parsed(res).choices[0].message.content;
    assert.equal(res.statusCode, 200);
    assert.deepEqual(JSON.parse(content), {
      ok: true,
      source: 'deepseek-openai-adapter-mock'
    });
  } finally {
    if (previousMock === undefined) {
      delete process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK;
    } else {
      process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK = previousMock;
    }
    if (previousText === undefined) {
      delete process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT;
    } else {
      process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT = previousText;
    }
  }
});

test('empty tool arrays and none choices do not trigger tool-calling rejection', async () => {
  const req = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      tools: [],
      tool_choice: 'none',
      functions: [],
      function_call: 'none',
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const res = mockRes();

  await handleOpenAiAdapterRequest(req, res, testContext());

  assert.equal(res.statusCode, 200);
  assert.equal(parsed(res).choices[0].message.content, 'hello from bridge');
});

test('JSON mode sets expectedJson and rejects non-JSON bridge answer', async () => {
  let bridgePayload;
  const req = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      response_format: { type: 'json_object' },
      messages: [{ role: 'user', content: 'Return JSON.' }]
    }
  });
  const res = mockRes();

  await handleOpenAiAdapterRequest(req, res, testContext({
    bridgeResponse: {
      statusCode: 200,
      payload: {
        ok: true,
        result: {
          answerText: 'not json',
          contentStatus: 'success'
        }
      }
    },
    onInvokeSearch: (payload) => {
      bridgePayload = payload;
    }
  }));

  assert.equal(bridgePayload.expectedJson, true);
  assert.equal(res.statusCode, 502);
  assert.equal(parsed(res).error.code, 'invalid_json');
});

test('unsupported Responses API, streaming, and tools fail explicitly', async () => {
  const responsesReq = mockReq({ url: '/v1/responses', body: { input: 'hi' } });
  const responsesRes = mockRes();
  await handleOpenAiAdapterRequest(responsesReq, responsesRes, testContext());
  assert.equal(responsesRes.statusCode, 400);
  assert.equal(parsed(responsesRes).error.code, 'responses_api_unsupported');

  const streamReq = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      stream: true,
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const streamRes = mockRes();
  await handleOpenAiAdapterRequest(streamReq, streamRes, testContext());
  assert.equal(streamRes.statusCode, 400);
  assert.equal(parsed(streamRes).error.code, 'streaming_unsupported');

  const toolsReq = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      tools: [{ type: 'function', function: { name: 'x' } }],
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const toolsRes = mockRes();
  await handleOpenAiAdapterRequest(toolsReq, toolsRes, testContext());
  assert.equal(toolsRes.statusCode, 400);
  assert.equal(parsed(toolsRes).error.code, 'tool_calling_unsupported');
});

test('invalid JSON request returns OpenAI error shape', async () => {
  const req = mockReq({ body: '{"messages":' });
  const res = mockRes();

  await handleOpenAiAdapterRequest(req, res, testContext());

  assert.equal(res.statusCode, 400);
  assert.equal(parsed(res).error.code, 'invalid_json_request');
});

test('bridge invocation exception returns explicit server error', async () => {
  const req = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const res = mockRes();

  await handleOpenAiAdapterRequest(req, res, {
    ...testContext(),
    invokeSearch: async () => {
      throw new Error('connection refused');
    }
  });

  assert.equal(res.statusCode, 502);
  assert.equal(parsed(res).error.code, 'bridge_invocation_failed');
});

test('bridge busy response is mapped to OpenAI error shape', async () => {
  const req = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      messages: [{ role: 'user', content: 'hi' }]
    }
  });
  const res = mockRes();

  await handleOpenAiAdapterRequest(req, res, testContext({
    bridgeResponse: {
      statusCode: 429,
      payload: {
        ok: false,
        error: 'bridge_busy',
        requestId: 7
      }
    }
  }));

  assert.equal(res.statusCode, 429);
  assert.equal(parsed(res).error.code, 'bridge_busy');
});

test('non-text message parts are rejected before invoking bridge', async () => {
  let invoked = false;
  const req = mockReq({
    body: {
      model: 'deepseek-web-bridge',
      messages: [
        {
          role: 'user',
          content: [
            { type: 'text', text: 'look' },
            { type: 'image_url', image_url: { url: 'https://example.test/x.png' } }
          ]
        }
      ]
    }
  });
  const res = mockRes();

  await handleOpenAiAdapterRequest(req, res, testContext({
    onInvokeSearch: () => {
      invoked = true;
    }
  }));

  assert.equal(invoked, false);
  assert.equal(res.statusCode, 400);
  assert.equal(parsed(res).error.code, 'multimodal_unsupported');
});

test('pure helpers keep strict JSON behavior clear', () => {
  assert.equal(_internals.isStrictJson('{"ok":true}'), true);
  assert.equal(_internals.isStrictJson('```json\n{"ok":true}\n```'), false);
  assert.equal(_internals.wantsJson({ response_format: { type: 'json_object' } }), true);
});
