'use strict';

const assert = require('node:assert/strict');
const http = require('node:http');
const test = require('node:test');

const { assertSafeProxyBind, createProxyServer, normalizeUpstream } = require('./deepseek-bridge-proxy');

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
}

test('proxy forwards old entrypoints to the canonical broker unchanged', async () => {
  let received = null;
  const upstream = http.createServer((request, response) => {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      received = {
        method: request.method,
        url: request.url,
        authorization: request.headers.authorization,
        body: Buffer.concat(chunks).toString('utf8')
      };
      response.writeHead(201, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ ok: true, mode: 'deepseek_account_broker' }));
    });
  });
  const upstreamPort = await listen(upstream);
  const proxy = createProxyServer({ upstream: `http://127.0.0.1:${upstreamPort}` });
  const proxyPort = await listen(proxy);

  try {
    const result = await fetch(`http://127.0.0.1:${proxyPort}/v1/chat/completions?trace=1`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer local-test',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ capability: 'stock.technical-analysis' })
    });
    assert.equal(result.status, 201);
    assert.equal(result.headers.get('x-deepseek-broker-proxy'), 'true');
    assert.deepEqual(await result.json(), { ok: true, mode: 'deepseek_account_broker' });
    assert.deepEqual(received, {
      method: 'POST',
      url: '/v1/chat/completions?trace=1',
      authorization: 'Bearer local-test',
      body: JSON.stringify({ capability: 'stock.technical-analysis' })
    });
  } finally {
    await close(proxy);
    await close(upstream);
  }
});

test('proxy refuses non-loopback and path-bearing upstream URLs', () => {
  assert.throws(() => normalizeUpstream('https://example.com'), /trusted local HTTP/);
  assert.throws(() => normalizeUpstream('http://127.0.0.1:8791/search'), /must not include a path/);
  assert.equal(normalizeUpstream('http://host.docker.internal:8791').hostname, 'host.docker.internal');
});

test('configured proxy API key requires authentication on every bind', async () => {
  assert.throws(
    () => assertSafeProxyBind('0.0.0.0', ''),
    error => error.code === 'unsafe_non_loopback_bind_without_api_key'
  );
  assert.doesNotThrow(() => assertSafeProxyBind('0.0.0.0', 'secret'));

  const upstream = http.createServer((request, response) => response.end('forwarded'));
  const upstreamPort = await listen(upstream);
  const proxy = createProxyServer({
    upstream: `http://127.0.0.1:${upstreamPort}`,
    apiKey: 'secret'
  });
  const proxyPort = await listen(proxy);
  try {
    const unauthorized = await fetch(`http://127.0.0.1:${proxyPort}/health`);
    assert.equal(unauthorized.status, 401);
    assert.equal(unauthorized.headers.get('x-deepseek-broker-proxy'), 'true');
    const authorized = await fetch(`http://127.0.0.1:${proxyPort}/health`, {
      headers: { Authorization: 'Bearer secret' }
    });
    assert.equal(authorized.status, 200);
    assert.equal(await authorized.text(), 'forwarded');
  } finally {
    await close(proxy);
    await close(upstream);
  }
});

test('proxy connection failure is explicitly safe to retry', async () => {
  const upstream = http.createServer((request, response) => response.end('unused'));
  const upstreamPort = await listen(upstream);
  await close(upstream);
  const proxy = createProxyServer({ upstream: `http://127.0.0.1:${upstreamPort}` });
  const proxyPort = await listen(proxy);
  try {
    const response = await fetch(`http://127.0.0.1:${proxyPort}/health`);
    assert.equal(response.status, 502);
    const payload = await response.json();
    assert.equal(payload.error, 'broker_upstream_unavailable');
    assert.equal(payload.message, 'Analysis capacity is temporarily unavailable.');
    assert.doesNotMatch(payload.message, /127\.0\.0\.1|localhost|\d{4,5}|ECONNREFUSED/i);
    assert.equal(payload.providerOutcomeUnknown, false);
    assert.equal(payload.retrySafe, true);
  } finally {
    await close(proxy);
  }
});

test('proxy never authorizes retry after an upstream accepted the request', async () => {
  let upstreamReceivedFullBody = false;
  const upstream = http.createServer((request) => {
    request.resume();
    request.on('end', () => {
      upstreamReceivedFullBody = true;
      request.socket.destroy();
    });
  });
  const upstreamPort = await listen(upstream);
  const proxy = createProxyServer({ upstream: `http://127.0.0.1:${upstreamPort}` });
  const proxyPort = await listen(proxy);
  try {
    const response = await fetch(`http://127.0.0.1:${proxyPort}/search`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'accepted-before-disconnect' })
    });
    assert.equal(upstreamReceivedFullBody, true);
    assert.equal(response.status, 502);
    const payload = await response.json();
    assert.equal(payload.error, 'broker_upstream_unavailable');
    assert.equal(payload.message, 'Analysis capacity is temporarily unavailable.');
    assert.equal(payload.providerOutcomeUnknown, true);
    assert.equal(payload.retrySafe, false);
  } finally {
    await close(proxy);
    await close(upstream);
  }
});
