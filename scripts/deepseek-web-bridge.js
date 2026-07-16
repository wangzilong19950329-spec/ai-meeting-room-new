#!/usr/bin/env node

const http = require('http');
const os = require('os');
const path = require('path');
const { searchQuery, prepareSharedTarget, getWorkerStates, DEBUG_PORT, WAIT_TIMEOUT_MS } = require('./deepseek-web-client');
const { handleOpenAiAdapterRequest } = require('./deepseek-openai-adapter');
const { RequestLedger } = require('./deepseek-request-ledger');
const {
  LOOPBACK_HOSTS,
  isBearerAuthorized,
  sharedBridgeAuthHeaders,
  shouldRequireSharedBridgeAuth
} = require('./deepseek-bridge-auth');

const PORT = Number(process.env.DEEPSEEK_WEB_BRIDGE_PORT || 8789);
const HOST = process.env.DEEPSEEK_WEB_BRIDGE_HOST || '127.0.0.1';
const REQUEST_LEDGER_PATH = process.env.DEEPSEEK_WEB_REQUEST_LEDGER
  || path.join(os.homedir(), '.aimeeting-room', `deepseek-web-${PORT}-requests.json`);
const requestLedger = new RequestLedger(REQUEST_LEDGER_PATH, process.env.DEEPSEEK_WEB_REQUEST_LEDGER_MAX_ENTRIES);
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

if (!LOOPBACK_HOSTS.has(String(HOST).toLowerCase()) && !process.env.DEEPSEEK_OPENAI_FACADE_API_KEY) {
  console.error(JSON.stringify({
    ok: false,
    event: 'unsafe_non_loopback_bind_without_api_key',
    host: HOST,
    message: 'Set DEEPSEEK_OPENAI_FACADE_API_KEY before binding the DeepSeek OpenAI adapter bridge outside loopback.'
  }));
  process.exit(78);
}

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
  if (lower.includes('remote debugging endpoint did not become ready')
    || lower.includes('crashpad permission')
    || lower.includes('debugger profile mismatch')
    || lower.includes('debugger profile')
  ) {
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

function sendAuthError(res) {
  return sendJson(res, 401, {
    ok: false,
    error: 'invalid_api_key',
    message: 'Invalid API key'
  });
}

function sendLedgerResult(res, lookup) {
  if (!lookup || lookup.status === 'NOT_FOUND') return false;
  if (lookup.status === 'SUCCESS') {
    sendJson(res, 200, {
      ok: true,
      result: { ...(lookup.entry.result || {}), idempotentReplay: true },
      ledgerStatus: 'SUCCESS'
    });
    return true;
  }
  const statusCode = lookup.status === 'DIGEST_MISMATCH' ? 409 : 425;
  sendJson(res, statusCode, {
    ok: false,
    error: lookup.status === 'DIGEST_MISMATCH'
      ? 'submission_digest_mismatch'
      : 'provider_outcome_not_retriable',
    ledgerStatus: lookup.status,
    message: lookup.entry && lookup.entry.error || null
  });
  return true;
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

async function invokeLocalSearch(payload) {
  const response = await fetch(`http://127.0.0.1:${PORT}/search`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...sharedBridgeAuthHeaders(HOST)
    },
    body: JSON.stringify(payload)
  });
  const text = await response.text();
  let parsed;
  try {
    parsed = text ? JSON.parse(text) : {};
  } catch (_) {
    parsed = {
      ok: false,
      error: 'invalid_bridge_response',
      message: text || 'Bridge returned a non-JSON response'
    };
  }
  return {
    statusCode: response.status,
    payload: parsed
  };
}

const server = http.createServer(async (req, res) => {
  const requestUrl = new URL(req.url || '/', `http://${HOST}`);
  const pathname = requestUrl.pathname;

  if (req.method === 'GET' && pathname === '/health') {
    if (isInFlightStale()) {
      forceResetInFlight('stale_inflight_on_health_check');
    }
    const healthAccountKey = String(requestUrl.searchParams.get('accountKey') || '').trim();
    const prepare = ['1', 'true', 'yes'].includes(String(requestUrl.searchParams.get('prepare') || '').toLowerCase());
    const accountHealth = healthAccountKey ? {
      requestedAccountKey: healthAccountKey,
      prepareRequested: prepare,
      status: prepare ? 'preparing' : 'not_prepared_without_prepare',
      note: prepare
        ? 'Preparing and verifying the requested account worker.'
        : 'Pass prepare=1 to verify and prepare this account worker; sharedTarget below is the default worker state.'
    } : null;
    let preparedTarget = null;
    if (prepare) {
      if (shouldRequireSharedBridgeAuth(HOST) && !isBearerAuthorized(req)) {
        return sendAuthError(res);
      }
      try {
        preparedTarget = await prepareSharedTarget({ accountKey: healthAccountKey });
        if (accountHealth) {
          accountHealth.status = 'prepared';
          accountHealth.effectiveAccountKey = preparedTarget.accountKey || null;
          accountHealth.preparedTarget = preparedTarget;
        }
      } catch (error) {
        if (accountHealth) {
          accountHealth.status = 'error';
          accountHealth.message = error && error.message ? error.message : String(error);
        }
        return sendJson(res, 503, {
          ok: false,
          error: 'debugger_unavailable',
          message: error && error.message ? error.message : String(error),
          accountKey: healthAccountKey || null,
          accountHealth,
          workers: getWorkerStates()
        });
      }
    }
    return sendJson(res, 200, {
      ok: true,
      mode: 'deepseek_web_bridge',
      debugPort: DEBUG_PORT,
      defaultDebugPort: DEBUG_PORT,
      waitTimeoutMs: WAIT_TIMEOUT_MS,
      maxInFlightMs: MAX_INFLIGHT_MS,
      requestHardTimeoutMs: REQUEST_HARD_TIMEOUT_MS,
      busy: inFlight,
      busyForMs: inFlight && inFlightStartedAt > 0 ? Date.now() - inFlightStartedAt : 0,
      cooldownRemainingMs: Math.max(0, nextAllowedAt - Date.now()),
      activeRequest: activeRequestState,
      sharedTarget: sharedTargetState,
      defaultSharedTarget: sharedTargetState,
      accountHealth,
      preparedTarget,
      requestLedger: requestLedger.summary(),
      workers: getWorkerStates()
    });
  }

  if (req.method === 'GET' && pathname === '/request-status') {
    if (shouldRequireSharedBridgeAuth(HOST) && !isBearerAuthorized(req)) {
      return sendAuthError(res);
    }
    const submissionKey = (requestUrl.searchParams.get('submissionKey') || '').trim();
    const requestDigest = (requestUrl.searchParams.get('requestDigest') || '').trim();
    if (!submissionKey || !requestDigest) {
      return sendJson(res, 400, { ok: false, error: 'submission_identity_required' });
    }
    const lookup = requestLedger.lookup(submissionKey, requestDigest);
    if (lookup.status === 'NOT_FOUND') {
      return sendJson(res, 404, { ok: false, ledgerStatus: 'NOT_FOUND' });
    }
    return sendLedgerResult(res, lookup);
  }

  const adapterHandled = await handleOpenAiAdapterRequest(req, res, {
    readBody,
    sendJson,
    invokeSearch: invokeLocalSearch
  });
  if (adapterHandled) {
    return;
  }

  if (req.method === 'POST' && pathname === '/search') {
    if (shouldRequireSharedBridgeAuth(HOST) && !isBearerAuthorized(req)) {
      return sendAuthError(res);
    }

    const requestId = ++requestSeq;
    const receivedAt = Date.now();
    let slotAcquired = false;
    let ledgerClaimed = false;
    let submissionKey = '';
    let requestDigest = '';
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
      const accountKey = typeof parsed.accountKey === 'string' ? parsed.accountKey.trim() : '';
      submissionKey = typeof parsed.submissionKey === 'string' ? parsed.submissionKey.trim() : '';
      requestDigest = typeof parsed.requestDigest === 'string' ? parsed.requestDigest.trim() : '';
      if ((submissionKey && !requestDigest) || (!submissionKey && requestDigest)) {
        return sendJson(res, 400, { ok: false, error: 'submission_identity_incomplete' });
      }
      if (submissionKey) {
        const existing = requestLedger.lookup(submissionKey, requestDigest);
        if (sendLedgerResult(res, existing)) return;
      }
      const closeTargetAfter = !sessionKey && isolatedSession && parsed.closeTargetAfter !== false;
      console.log(JSON.stringify({
        ok: true,
        event: 'request_start',
        requestId,
        accountKey: accountKey || null,
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
        accountKey: accountKey || null,
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
      if (submissionKey) {
        const claim = requestLedger.begin(submissionKey, requestDigest, {
          purpose: typeof parsed.purpose === 'string' ? parsed.purpose : null,
          sessionKey: sessionKey || null,
          accountKey: accountKey || null
        });
        if (!claim.created) {
          sendLedgerResult(res, claim);
          return;
        }
        ledgerClaimed = true;
      }
      updateActiveRequest({ phase: 'prepare_target' });
      if (accountKey) {
        await prepareSharedTarget({ accountKey });
      } else {
        await sharedTargetReady;
      }
      updateActiveRequest({ phase: 'search' });
      const searchStartedAt = Date.now();
      // 给 searchQuery 加一个硬上限：超时立即返回错误，let finally 释放锁。
      const result = await Promise.race([
        searchQuery(query, { isolatedSession, closeTargetAfter, sessionKey, forceNewSession, expectedJson, mode, accountKey }),
        new Promise((_, reject) => setTimeout(
          () => reject(new Error('searchQuery exceeded MAX_INFLIGHT_MS hard timeout')),
          MAX_INFLIGHT_MS
        ))
      ]);
      if (ledgerClaimed) {
        requestLedger.complete(submissionKey, requestDigest, result);
      }
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
      if (ledgerClaimed) {
        try {
          requestLedger.markUnknown(submissionKey, requestDigest,
            error && error.message ? error.message : String(error));
        } catch (ledgerError) {
          console.error(JSON.stringify({ ok: false, event: 'request_ledger_update_failed',
            submissionKey, message: ledgerError.message }));
        }
      }
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

server.listen(PORT, HOST, () => {
  console.log(JSON.stringify({
    ok: true,
    mode: 'deepseek_web_bridge',
    host: HOST,
    port: PORT,
    debugPort: DEBUG_PORT,
    defaultDebugPort: DEBUG_PORT,
    workers: getWorkerStates()
  }));
});
