#!/usr/bin/env node

const http = require('http');
const { searchQuery, DEBUG_PORT, WAIT_TIMEOUT_MS } = require('./deepseek-web-client');

const PORT = Number(process.env.DEEPSEEK_WEB_BRIDGE_PORT || 8789);
let inFlight = false;
let inFlightStartedAt = 0;
const WAIT_FOR_FREE_MS = Number(process.env.DEEPSEEK_WEB_BRIDGE_QUEUE_TIMEOUT_MS || 180000);
const MIN_REQUEST_INTERVAL_MS = Number(process.env.DEEPSEEK_WEB_MIN_REQUEST_INTERVAL_MS || 45000);
const POST_REQUEST_COOLDOWN_MS = Number(process.env.DEEPSEEK_WEB_POST_REQUEST_COOLDOWN_MS || 15000);
// 单个 /search 请求的硬上限：超时即强制释放 inFlight，避免 Chrome 卡死导致 bridge 永久 busy。
// 默认 = WAIT_TIMEOUT_MS（client 内答案等待）+ 60s 缓冲（含页面加载/模式切换/提交）。
const MAX_INFLIGHT_MS = Number(process.env.DEEPSEEK_WEB_MAX_INFLIGHT_MS || (WAIT_TIMEOUT_MS + 60000));
let nextAllowedAt = 0;

function isInFlightStale() {
  return inFlight && inFlightStartedAt > 0 && (Date.now() - inFlightStartedAt) > MAX_INFLIGHT_MS;
}

function forceResetInFlight(reason) {
  if (inFlight) {
    console.error(JSON.stringify({
      ok: false,
      event: 'force_reset_inflight',
      reason,
      heldForMs: Date.now() - inFlightStartedAt
    }));
  }
  inFlight = false;
  inFlightStartedAt = 0;
}

// 定时巡检：如果 inFlight 超过硬上限，强制清掉，避免 bridge 永远卡 busy=true。
setInterval(() => {
  if (isInFlightStale()) {
    forceResetInFlight('stale_inflight_watchdog');
  }
}, 10000).unref();

function classifyBridgeError(error) {
  const message = error && error.message ? error.message : String(error || 'unknown_error');
  const lower = message.toLowerCase();
  if (lower.includes('timed out waiting for deepseek web answer')) {
    return { statusCode: 504, error: 'provider_timeout', message };
  }
  if (lower.includes('login may be required')) {
    return { statusCode: 503, error: 'login_required', message };
  }
  if (lower.includes('remote debugging endpoint did not become ready') || lower.includes('crashpad permission')) {
    return { statusCode: 503, error: 'debugger_unavailable', message };
  }
  if (lower.includes('failed to submit query')) {
    return { statusCode: 502, error: 'submit_failed', message };
  }
  return { statusCode: 500, error: 'search_failed', message };
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

async function waitForFreeSlot() {
  const deadline = Date.now() + WAIT_FOR_FREE_MS;
  while (inFlight && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  return !inFlight;
}

async function waitForCooldown() {
  while (Date.now() < nextAllowedAt) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    if (isInFlightStale()) {
      forceResetInFlight('stale_inflight_on_health_check');
    }
    return sendJson(res, 200, {
      ok: true,
      mode: 'deepseek_web_bridge',
      debugPort: DEBUG_PORT,
      waitTimeoutMs: WAIT_TIMEOUT_MS,
      maxInFlightMs: MAX_INFLIGHT_MS,
      busy: inFlight,
      busyForMs: inFlight && inFlightStartedAt > 0 ? Date.now() - inFlightStartedAt : 0
    });
  }

  if (req.method === 'POST' && req.url === '/search') {
    if (inFlight) {
      // 进入排队前先做一次 stale 检查，防止排队等到一个其实早该释放的死锁。
      if (isInFlightStale()) {
        forceResetInFlight('stale_inflight_at_request_entry');
      } else {
        const available = await waitForFreeSlot();
        if (!available) {
          return sendJson(res, 429, { ok: false, error: 'bridge_busy' });
        }
      }
    }
    await waitForCooldown();
    inFlight = true;
    inFlightStartedAt = Date.now();
    try {
      const raw = await readBody(req);
      const parsed = raw ? JSON.parse(raw) : {};
      const query = (parsed.query || '').trim();
      if (!query) {
        return sendJson(res, 400, { ok: false, error: 'missing_query' });
      }
      const isolatedSession = parsed.isolatedSession === true || parsed.isolated === true;
      const sessionKey = typeof parsed.sessionKey === 'string' ? parsed.sessionKey.trim() : '';
      const forceNewSession = parsed.forceNewSession === true || parsed.resetSession === true;
      const closeTargetAfter = !sessionKey && isolatedSession && parsed.closeTargetAfter !== false;
      // 给 searchQuery 加一个硬上限：超时立即返回错误，let finally 释放锁。
      const result = await Promise.race([
        searchQuery(query, { isolatedSession, closeTargetAfter, sessionKey, forceNewSession }),
        new Promise((_, reject) => setTimeout(
          () => reject(new Error('searchQuery exceeded MAX_INFLIGHT_MS hard timeout')),
          MAX_INFLIGHT_MS
        ))
      ]);
      return sendJson(res, 200, { ok: true, result });
    } catch (error) {
      const classified = classifyBridgeError(error);
      return sendJson(res, classified.statusCode, {
        ok: false,
        error: classified.error,
        message: classified.message
      });
    } finally {
      nextAllowedAt = Date.now() + Math.max(MIN_REQUEST_INTERVAL_MS, POST_REQUEST_COOLDOWN_MS);
      inFlight = false;
      inFlightStartedAt = 0;
    }
  }

  sendJson(res, 404, { ok: false, error: 'not_found' });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(JSON.stringify({
    ok: true,
    mode: 'deepseek_web_bridge',
    port: PORT,
    debugPort: DEBUG_PORT
  }));
});
