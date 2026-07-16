'use strict';

const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '::1']);

function normalizeHost(host) {
  return String(host || '').trim().toLowerCase();
}

function shouldRequireSharedBridgeAuth(host, apiKey = process.env.DEEPSEEK_OPENAI_FACADE_API_KEY) {
  return Boolean(apiKey) && !LOOPBACK_HOSTS.has(normalizeHost(host));
}

function getHeader(headers, name) {
  const lower = name.toLowerCase();
  if (!headers) return '';
  if (headers[lower] || headers[name]) {
    return headers[lower] || headers[name];
  }
  const matched = Object.keys(headers).find((key) => key.toLowerCase() === lower);
  return matched ? headers[matched] : '';
}

function isBearerAuthorized(req, apiKey = process.env.DEEPSEEK_OPENAI_FACADE_API_KEY) {
  if (!apiKey) return true;
  return getHeader(req && req.headers, 'authorization') === `Bearer ${apiKey}`;
}

function sharedBridgeAuthHeaders(host, apiKey = process.env.DEEPSEEK_OPENAI_FACADE_API_KEY) {
  if (!shouldRequireSharedBridgeAuth(host, apiKey)) {
    return {};
  }
  return {
    Authorization: `Bearer ${apiKey}`
  };
}

module.exports = {
  LOOPBACK_HOSTS,
  getHeader,
  isBearerAuthorized,
  sharedBridgeAuthHeaders,
  shouldRequireSharedBridgeAuth
};
