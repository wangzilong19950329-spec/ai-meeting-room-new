#!/usr/bin/env node
'use strict';

const http = require('http');
const { handleOpenAiAdapterRequest } = require('./deepseek-openai-adapter');

const PORT = Number(process.env.DEEPSEEK_WEB_BRIDGE_PORT || process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_PORT || 18789);
const HOST = process.env.DEEPSEEK_WEB_BRIDGE_HOST || process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK_HOST || '127.0.0.1';
const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '::1']);

process.env.DEEPSEEK_OPENAI_ADAPTER_MOCK = '1';

if (!LOOPBACK_HOSTS.has(String(HOST).toLowerCase()) && !process.env.DEEPSEEK_OPENAI_FACADE_API_KEY) {
  console.error(JSON.stringify({
    ok: false,
    event: 'unsafe_non_loopback_bind_without_api_key',
    host: HOST,
    message: 'Set DEEPSEEK_OPENAI_FACADE_API_KEY before binding the mock adapter outside loopback.'
  }));
  process.exit(78);
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 1024 * 1024) {
        reject(new Error('Request body too large'));
        req.destroy();
      }
    });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    return sendJson(res, 200, {
      ok: true,
      mode: 'deepseek_openai_adapter_mock',
      host: HOST,
      port: PORT,
      mock: true
    });
  }

  const handled = await handleOpenAiAdapterRequest(req, res, {
    readBody,
    sendJson,
    invokeSearch: async () => ({
      statusCode: 500,
      payload: {
        ok: false,
        error: 'mock_not_enabled',
        message: 'Mock adapter should not invoke the DeepSeek web bridge.'
      }
    })
  });
  if (handled) {
    return;
  }

  sendJson(res, 404, { ok: false, error: 'not_found' });
});

server.listen(PORT, HOST, () => {
  console.log(JSON.stringify({
    ok: true,
    mode: 'deepseek_openai_adapter_mock',
    host: HOST,
    port: PORT,
    baseUrl: `http://${HOST === '0.0.0.0' ? '127.0.0.1' : HOST}:${PORT}/v1`
  }));
});
