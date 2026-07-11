#!/usr/bin/env node

const http = require('http');
const { searchQuery, prepareSharedTarget, DEBUG_PORT, WAIT_TIMEOUT_MS } = require('./deepseek-web-client');

const PORT = Number(process.env.DEEPSEEK_WEB_BRIDGE_PORT || 8789);
let inFlight = false;
let inFlightStartedAt = 0;
const WAIT_FOR_FREE_MS = Number(process.env.DEEPSEEK_WEB_BRIDGE_QUEUE_TIMEOUT_MS || 180000);
const MIN_REQUEST_INTERVAL_MS = Number(process.env.DEEPSEEK_WEB_MIN_REQUEST_INTERVAL_MS || 45000);
const POST_REQUEST_COOLDOWN_MS = Number(process.env.DEEPSEEK_WEB_POST_REQUEST_COOLDOWN_MS || 15000);
// 单个 /search 请求的硬上限：超时即强制释放 inFlight，避免 Chrome 卡死导致 bridge 永久 busy。
// 默认 = WAIT_TIMEOUT_MS（client 内答案等待）+ 60s 缓冲（含页面加载/模式切换/提交）。
const MAX_INFLIGHT_MS = Number(process.env.DEEPSEEK_WEB_MAX_INFLIGHT_MS || (WAIT_TIMEOUT_MS + 60000));
const REQUEST_HARD_TIMEOUT_MS = Number(
  process.env.DEEPSEEK_WEB_REQUEST_HARD_TIMEOUT_MS
    || (Math.max(MIN_REQUEST_INTERVAL_MS, POST_REQUEST_COOLDOWN_MS) + MAX_INFLIGHT_MS + 60000)
);
let nextAllowedAt = 0;
let requestSeq = 0;
let sharedTargetState = {
  mode: 'single_worker',
  status: 'initializing'
};
let activeRequestState = null;

const sharedTargetReady = prepareSharedTarget()
  .then((state) => {
    sharedTargetState = { ...state, status: 'ready' };
    console.log(JSON.stringify({ ok: true, event: 'shared_target_ready', ...sharedTargetState }));
  })
  .catch((error) => {
    sharedTargetState = {
      mode: 'single_worker',
      status: 'error',
      error: error && error.message ? error.message : String(error)
    };
    console.error(JSON.stringify({ ok: false, event: 'shared_target_prepare_failed', ...sharedTargetState }));
  });

function isInFlightStale() {
  return inFlight && inFlightStartedAt > 0 && (Date.now() - inFlightStartedAt) > REQUEST_HARD_TIMEOUT_MS;
}

function forceResetInFlight(reason) {
  if (inFlight) {
    console.error(JSON.stringify({
      ok: false,
      event: 'force_reset_inflight',
      reason,
      heldForMs: Date.now() - inFlightStartedAt,
      activeRequest: activeRequestState
    }));
  }
  inFlight = false;
  inFlightStartedAt = 0;
  activeRequestState = null;
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
  if (lower.includes('hard timeout') || lower.includes('exceeded max_inflight')) {
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
  while (Date.now() < deadline) {
    if (isInFlightStale()) {
      forceResetInFlight('stale_inflight_wait_for_slot');
    }
    if (!inFlight) {
      inFlight = true;
      inFlightStartedAt = Date.now();
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  return false;
}

async function waitForCooldown() {
  while (Date.now() < nextAllowedAt) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}

function updateActiveRequest(patch) {
  if (!activeRequestState) return;
  activeRequestState = { ...activeRequestState, ...patch, updatedAt: new Date().toISOString() };
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
      requestHardTimeoutMs: REQUEST_HARD_TIMEOUT_MS,
      busy: inFlight,
      busyForMs: inFlight && inFlightStartedAt > 0 ? Date.now() - inFlightStartedAt : 0,
      cooldownRemainingMs: Math.max(0, nextAllowedAt - Date.now()),
      activeRequest: activeRequestState,
      sharedTarget: sharedTargetState
    });
  }

  if (req.method === 'POST' && req.url === '/search') {
    const requestId = ++requestSeq;
    const receivedAt = Date.now();
    let slotAcquired = false;
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
      const expectedJson = parsed.expectedJson === true;
      const mode = typeof parsed.mode === 'string' ? parsed.mode.trim() : '';
      const closeTargetAfter = !sessionKey && isolatedSession && parsed.closeTargetAfter !== false;
      console.log(JSON.stringify({
        ok: true,
        event: 'request_start',
        requestId,
        queryLength: query.length,
        expectedJson,
        mode: mode || null,
        cooldownRemainingMs: Math.max(0, nextAllowedAt - Date.now())
      }));
      const available = await waitForFreeSlot();
      if (!available) {
        return sendJson(res, 429, { ok: false, error: 'bridge_busy', requestId });
      }
      slotAcquired = true;
      activeRequestState = {
        requestId,
        phase: 'cooldown',
        startedAt: new Date(inFlightStartedAt).toISOString(),
        queryLength: query.length,
        expectedJson,
        mode: mode || null
      };
      console.log(JSON.stringify({
        ok: true,
        event: 'request_acquired',
        requestId,
        waitedMs: Date.now() - receivedAt
      }));
      await waitForCooldown();
      updateActiveRequest({ phase: 'prepare_target' });
      await sharedTargetReady;
      updateActiveRequest({ phase: 'search' });
      const searchStartedAt = Date.now();
      // 给 searchQuery 加一个硬上限：超时立即返回错误，let finally 释放锁。
      const result = await Promise.race([
        searchQuery(query, { isolatedSession, closeTargetAfter, sessionKey, forceNewSession, expectedJson, mode }),
        new Promise((_, reject) => setTimeout(
          () => reject(new Error('searchQuery exceeded MAX_INFLIGHT_MS hard timeout')),
          MAX_INFLIGHT_MS
        ))
      ]);
      console.log(JSON.stringify({
        ok: true,
        event: 'request_end',
        requestId,
        status: 'success',
        elapsedMs: Date.now() - receivedAt,
        searchElapsedMs: Date.now() - searchStartedAt,
        contentStatus: result && result.contentStatus || null,
        completionReason: result && result.completionReason || null,
        answerLength: result && result.answerLength || 0
      }));
      return sendJson(res, 200, { ok: true, result });
    } catch (error) {
      const classified = classifyBridgeError(error);
      console.error(JSON.stringify({
        ok: false,
        event: 'request_end',
        requestId,
        status: 'error',
        elapsedMs: Date.now() - receivedAt,
        error: classified.error,
        message: classified.message,
        activeRequest: activeRequestState
      }));
      return sendJson(res, classified.statusCode, {
        ok: false,
        error: classified.error,
        message: classified.message,
        requestId
      });
    } finally {
      if (slotAcquired) {
        nextAllowedAt = Date.now() + Math.max(MIN_REQUEST_INTERVAL_MS, POST_REQUEST_COOLDOWN_MS);
        inFlight = false;
        inFlightStartedAt = 0;
        activeRequestState = null;
      }
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
