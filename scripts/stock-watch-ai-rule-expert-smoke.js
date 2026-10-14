#!/usr/bin/env node
'use strict';

const http = require('http');
const https = require('https');
const { sha256Operation } = require('./deepseek-request-identity');

const bridgeUrl = process.env.STOCK_WATCH_AI_RULES_BRIDGE_URL
  || process.env.STOCK_WATCH_TECH_BRIDGE_URL
  || process.env.DEEPSEEK_WEB_BRIDGE_URL
  || 'http://127.0.0.1:8789/search';
const timeoutMs = Number(process.env.SMOKE_TIMEOUT_MS || 240000);
const requireDeepThink = String(process.env.SMOKE_REQUIRE_DEEP_THINK || '').toLowerCase() === 'true';
const bridgeApiKey = process.env.DEEPSEEK_OPENAI_FACADE_API_KEY || '';
const token = `stock_watch_ai_rule_expert_smoke_${Date.now()}`;

function requestHeaders(body, apiKey = bridgeApiKey) {
  return {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {})
  };
}

function requestJson(url, payload) {
  const parsed = new URL(url);
  const client = parsed.protocol === 'https:' ? https : http;
  const body = JSON.stringify(payload);
  return new Promise((resolve, reject) => {
    const req = client.request({
      protocol: parsed.protocol,
      hostname: parsed.hostname,
      port: parsed.port,
      path: `${parsed.pathname}${parsed.search}`,
      method: 'POST',
      timeout: timeoutMs,
      headers: requestHeaders(body)
    }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        let parsedBody = null;
        try {
          parsedBody = data ? JSON.parse(data) : null;
        } catch (error) {
          reject(new Error(`invalid_json_response status=${res.statusCode} error=${error.message} body=${data.slice(0, 300)}`));
          return;
        }
        resolve({ statusCode: res.statusCode, body: parsedBody });
      });
    });
    req.on('timeout', () => req.destroy(new Error(`request_timeout_${timeoutMs}ms`)));
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

function assertSmoke(result) {
  const body = result.body || {};
  const payload = body.result || {};
  const modeState = payload.modeState || {};
  const answerText = String(payload.answerText || '');
  const failures = [];
  const warnings = [];

  if (result.statusCode !== 200) failures.push(`httpStatus=${result.statusCode}`);
  if (body.ok !== true) failures.push(`ok=${body.ok}`);
  if (!answerText.includes(token)) failures.push('answer_missing_current_token');
  if (modeState.bridgeMode !== 'expert') failures.push(`bridgeMode=${modeState.bridgeMode}`);
  if (modeState.expertMode !== true) failures.push(`expertMode=${modeState.expertMode}`);
  if (modeState.quickMode !== false) failures.push(`quickMode=${modeState.quickMode}`);
  if (modeState.modeVerified !== true) failures.push(`modeVerified=${modeState.modeVerified}`);
  if (!['expert', 'unified'].includes(modeState.headerMode)) failures.push(`headerMode=${modeState.headerMode}`);
  if (modeState.headerMode === 'unified' && modeState.searchEnabled !== true) {
    failures.push(`searchEnabled=${modeState.searchEnabled}`);
  }
  if (modeState.deepThinkEnabled !== true) {
    const message = `deepThinkEnabled=${modeState.deepThinkEnabled}`;
    if (requireDeepThink) failures.push(message);
    else warnings.push(message);
  }
  if (payload.jsonComplete !== true) failures.push(`jsonComplete=${payload.jsonComplete}`);

  const summary = {
    ok: failures.length === 0,
    bridgeUrl,
    token,
    httpStatus: result.statusCode,
    contentStatus: payload.contentStatus || null,
    completionReason: payload.completionReason || null,
    jsonComplete: payload.jsonComplete,
    answerHasToken: answerText.includes(token),
    requireDeepThink,
    modeState,
    warnings,
    failures
  };
  console.log(JSON.stringify(summary, null, 2));
  if (failures.length > 0) {
    process.exitCode = 1;
  }
}

function buildSmokePayload(smokeToken) {
  const query = `你是DeepSeek网页专业模式冒烟测试。只输出一个JSON对象，字段固定为 {"ok":true,"token":"${smokeToken}"}，不要输出Markdown或解释。`;
  const payload = {
    query,
    capability: 'stock.rule-evaluation',
    priority: 300,
    sessionKey: 'stock-watch-ai-rule-expert-smoke',
    forceNewSession: true,
    closeTargetAfter: false,
    expectedJson: true,
    mode: 'expert',
    submissionKey: `stock-rule-smoke:${smokeToken}`
  };
  payload.requestDigest = sha256Operation(payload);
  return payload;
}

async function main() {
  const payload = buildSmokePayload(token);
  const result = await requestJson(bridgeUrl, payload);
  assertSmoke(result);
}

if (require.main === module) {
  main().catch((error) => {
    console.log(JSON.stringify({
      ok: false,
      bridgeUrl,
      token,
      error: error && error.message ? error.message : String(error)
    }, null, 2));
    process.exit(1);
  });
}

module.exports = { buildSmokePayload, requestHeaders };
