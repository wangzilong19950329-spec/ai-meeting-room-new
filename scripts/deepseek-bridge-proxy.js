#!/usr/bin/env node
'use strict';

const http = require('http');
const { LOOPBACK_HOSTS, isBearerAuthorized } = require('./deepseek-bridge-auth');
const { publicBridgeErrorMessage } = require('./deepseek-public-error');
const TRUSTED_LOCAL_UPSTREAM_HOSTS = new Set([...LOOPBACK_HOSTS, 'host.docker.internal']);
const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade'
]);

function normalizeUpstream(raw) {
  const upstream = new URL(String(raw || 'http://127.0.0.1:8791'));
  if (upstream.protocol !== 'http:' || !TRUSTED_LOCAL_UPSTREAM_HOSTS.has(upstream.hostname)) {
    throw new Error('DeepSeek broker proxy upstream must be a trusted local HTTP URL');
  }
  if (upstream.pathname !== '/' || upstream.search || upstream.hash) {
    throw new Error('DeepSeek broker proxy upstream must not include a path, query, or fragment');
  }
  return upstream;
}

function forwardedHeaders(headers, upstream) {
  const result = {};
  for (const [name, value] of Object.entries(headers || {})) {
    if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase())) result[name] = value;
  }
  result.host = upstream.host;
  return result;
}

function assertSafeProxyBind(host, apiKey) {
  if (!LOOPBACK_HOSTS.has(String(host || '').trim().toLowerCase()) && !apiKey) {
    const error = new Error('Set DEEPSEEK_OPENAI_FACADE_API_KEY before binding the DeepSeek broker proxy outside loopback');
    error.code = 'unsafe_non_loopback_bind_without_api_key';
    throw error;
  }
}

function createProxyServer(options = {}) {
  const upstream = normalizeUpstream(options.upstream || process.env.DEEPSEEK_BROKER_UPSTREAM);
  const apiKey = options.apiKey || process.env.DEEPSEEK_OPENAI_FACADE_API_KEY || '';
  const requireAuth = options.requireAuth === true || Boolean(apiKey);
  return http.createServer((request, response) => {
    if (requireAuth && !isBearerAuthorized(request, apiKey)) {
      const body = JSON.stringify({ ok: false, error: 'invalid_api_key', message: 'Invalid API key' });
      response.writeHead(401, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
        'x-deepseek-broker-proxy': 'true'
      });
      response.end(body);
      return;
    }
    const target = new URL(request.url || '/', upstream);
    let upstreamConnected = false;
    const proxyRequest = http.request({
      protocol: upstream.protocol,
      hostname: upstream.hostname,
      port: upstream.port,
      method: request.method,
      path: `${target.pathname}${target.search}`,
      headers: forwardedHeaders(request.headers, upstream)
    }, (proxyResponse) => {
      const headers = forwardedHeaders(proxyResponse.headers, upstream);
      delete headers.host;
      headers['x-deepseek-broker-proxy'] = 'true';
      response.writeHead(proxyResponse.statusCode || 502, headers);
      proxyResponse.pipe(response);
    });
    proxyRequest.on('socket', (socket) => {
      if (!socket.connecting) upstreamConnected = true;
      socket.once('connect', () => { upstreamConnected = true; });
    });
    proxyRequest.on('error', (error) => {
      if (response.headersSent) {
        response.destroy(error);
        return;
      }
      const providerOutcomeUnknown = upstreamConnected
        && !['GET', 'HEAD', 'OPTIONS'].includes(String(request.method || '').toUpperCase());
      const body = JSON.stringify({
        ok: false,
        error: 'broker_upstream_unavailable',
        message: publicBridgeErrorMessage('broker_upstream_unavailable'),
        providerOutcomeUnknown,
        retrySafe: !providerOutcomeUnknown
      });
      response.writeHead(502, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
        'x-deepseek-broker-proxy': 'true'
      });
      response.end(body);
    });
    request.on('error', error => proxyRequest.destroy(error));
    request.pipe(proxyRequest);
  });
}

if (require.main === module) {
  const host = process.env.DEEPSEEK_BROKER_PROXY_HOST || '127.0.0.1';
  const port = Number(process.env.DEEPSEEK_BROKER_PROXY_PORT || 8789);
  const upstream = normalizeUpstream(process.env.DEEPSEEK_BROKER_UPSTREAM);
  const apiKey = process.env.DEEPSEEK_OPENAI_FACADE_API_KEY || '';
  try {
    assertSafeProxyBind(host, apiKey);
  } catch (error) {
    console.error(JSON.stringify({ ok: false, error: error.code, message: error.message }));
    process.exit(78);
  }
  const server = createProxyServer({
    upstream: upstream.toString(),
    requireAuth: Boolean(apiKey),
    apiKey
  });
  server.listen(port, host, () => {
    console.log(JSON.stringify({
      ok: true,
      mode: 'deepseek_broker_proxy',
      host,
      port,
      upstream: upstream.origin
    }));
  });
}

module.exports = {
  assertSafeProxyBind,
  createProxyServer,
  forwardedHeaders,
  normalizeUpstream
};
