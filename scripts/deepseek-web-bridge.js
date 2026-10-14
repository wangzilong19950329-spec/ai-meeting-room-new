#!/usr/bin/env node

const http = require('http');
const os = require('os');
const path = require('path');
const {
  searchQuery,
  prepareSharedTarget,
  getWorkerStates,
  markAccountQuarantined,
  hasAccountQuarantine,
  closeAccountBrowserAndVerify,
  DEBUG_PORT,
  WAIT_TIMEOUT_MS
} = require('./deepseek-web-client');
const { handleOpenAiAdapterRequest } = require('./deepseek-openai-adapter');
const { RequestLedger } = require('./deepseek-request-ledger');
const { requiresSubmissionIdentity, validateSubmissionIdentity } = require('./deepseek-request-identity');
const { normalizeSessionKey } = require('./deepseek-session-identity');
const { publicSearchResult } = require('./deepseek-public-result');
const { AccountBroker, loadAccountConfigs, normalizeCapability } = require('./deepseek-account-broker');
const {
  LOOPBACK_HOSTS,
  isSharedBridgeRequestAuthorized,
  sharedBridgeAuthHeaders,
} = require('./deepseek-bridge-auth');
const { publicBridgeErrorMessage, publicLedgerErrorMessage } = require('./deepseek-public-error');
const { bridgeResultResponse } = require('./deepseek-bridge-result-response');
const { bridgeReadiness } = require('./deepseek-bridge-health');
const { runWithHardTimeout } = require('./deepseek-inflight-guard');

const PORT = Number(process.env.DEEPSEEK_WEB_BRIDGE_PORT || 8789);
const HOST = process.env.DEEPSEEK_WEB_BRIDGE_HOST || '127.0.0.1';
const REQUEST_LEDGER_PATH = process.env.DEEPSEEK_WEB_REQUEST_LEDGER
  || path.join(os.homedir(), '.aimeeting-room', `deepseek-web-${PORT}-requests.json`);
const requestLedger = new RequestLedger(REQUEST_LEDGER_PATH, process.env.DEEPSEEK_WEB_REQUEST_LEDGER_MAX_ENTRIES);
const WAIT_FOR_FREE_MS = Number(process.env.DEEPSEEK_WEB_BRIDGE_QUEUE_TIMEOUT_MS || 180000);
const MAX_INFLIGHT_MS = Number(process.env.DEEPSEEK_WEB_MAX_INFLIGHT_MS || (WAIT_TIMEOUT_MS + 60000));
const ABORT_GRACE_MS = Number(process.env.DEEPSEEK_WEB_ABORT_GRACE_MS || 15000);
let requestSeq = 0;
const BROKER_STATE_PATH = process.env.DEEPSEEK_WEB_BROKER_STATE
  || path.join(os.homedir(), '.aimeeting-room', `deepseek-web-${PORT}-broker-state.json`);
const accountBroker = new AccountBroker({
  accounts: loadAccountConfigs(),
  queueTimeoutMs: WAIT_FOR_FREE_MS,
  circuitFailureThreshold: Number(process.env.DEEPSEEK_WEB_BROKER_CIRCUIT_FAILURE_THRESHOLD || 3),
  circuitOpenMs: Number(process.env.DEEPSEEK_WEB_BROKER_CIRCUIT_OPEN_MS || 300000),
  allowAccountOverride: ['1', 'true', 'yes'].includes(
    String(process.env.DEEPSEEK_WEB_ALLOW_ACCOUNT_OVERRIDE || '').toLowerCase()
  ),
  stateFile: BROKER_STATE_PATH,
  maxAffinities: Number(process.env.DEEPSEEK_WEB_BROKER_MAX_AFFINITIES || 5000)
});
const sharedTargetStates = new Map();
const accountPreparePromises = new Map();

if (!LOOPBACK_HOSTS.has(String(HOST).toLowerCase()) && !process.env.DEEPSEEK_OPENAI_FACADE_API_KEY) {
  console.error(JSON.stringify({
    ok: false,
    event: 'unsafe_non_loopback_bind_without_api_key',
    host: HOST,
    message: 'Set DEEPSEEK_OPENAI_FACADE_API_KEY before binding the DeepSeek OpenAI adapter bridge outside loopback.'
  }));
  process.exit(78);
}

function prepareAccountTarget(accountKey) {
  if (accountPreparePromises.has(accountKey)) return accountPreparePromises.get(accountKey);
  const preparation = prepareAccountTargetOnce(accountKey)
    .finally(() => accountPreparePromises.delete(accountKey));
  accountPreparePromises.set(accountKey, preparation);
  return preparation;
}

async function prepareAccountTargetOnce(accountKey) {
  sharedTargetStates.set(accountKey, {
    mode: 'account_worker',
    accountKey,
    status: 'preparing'
  });
  try {
    const state = await prepareSharedTarget({ accountKey });
    accountBroker.registerWorkerIdentity(accountKey, state.identityFingerprint);
    const { identityFingerprint, ...publicState } = state;
    const ready = { ...publicState, status: 'ready' };
    sharedTargetStates.set(accountKey, ready);
    console.log(JSON.stringify({ ok: true, event: 'account_target_ready', ...ready }));
    return ready;
  } catch (error) {
    const failed = {
      mode: 'account_worker',
      accountKey,
      status: 'error',
      error: error && error.message ? error.message : String(error)
    };
    sharedTargetStates.set(accountKey, failed);
    try {
      accountBroker.invalidateWorkerIdentity(accountKey);
    } catch (invalidationError) {
      console.error(JSON.stringify({
        ok: false,
        event: 'account_identity_invalidation_failed',
        accountKey,
        error: invalidationError && invalidationError.message
          ? invalidationError.message : String(invalidationError)
      }));
    }
    console.error(JSON.stringify({ ok: false, event: 'account_target_prepare_failed', ...failed }));
    throw error;
  }
}

let startupPreparationComplete = false;
const brokerStartupReady = Promise.allSettled(
  accountBroker.configuredAccounts().map(account => prepareAccountTarget(account.accountKey))
).finally(() => {
  startupPreparationComplete = true;
});

function classifyBridgeError(error) {
  const message = error && error.message ? error.message : String(error || 'unknown_error');
  const lower = message.toLowerCase();
  const code = error && error.code ? String(error.code) : '';
  if (code === 'account_override_forbidden'
    || code === 'account_override_capability_conflict'
    || code === 'session_affinity_capability_conflict') {
    return { statusCode: 400, error: code, message };
  }
  if (code === 'bridge_queue_timeout') {
    return { statusCode: 429, error: code, message };
  }
  if (code === 'capability_unavailable'
    || code === 'all_workers_failed_pre_submit'
    || code === 'session_worker_unavailable'
    || code === 'duplicate_account_identity'
    || code === 'account_identity_unverified'
    || code === 'identity_lease_changed'
    || code === 'identity_lease_conflict') {
    return { statusCode: 503, error: code, message };
  }
  if (error && error.providerOutcomeUnknown === true) {
    return { statusCode: 504, error: 'provider_outcome_unknown', message };
  }
  if (code === 'login_required') {
    return { statusCode: 503, error: code, message };
  }
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

function requestCapability(parsed) {
  if (parsed && typeof parsed.capability === 'string' && parsed.capability.trim()) {
    return normalizeCapability(parsed.capability);
  }
  const purpose = parsed && typeof parsed.purpose === 'string' ? parsed.purpose.trim() : '';
  if (!purpose || purpose === 'stock-analysis-v1') return 'general.analysis';
  return normalizeCapability(purpose);
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
      result: { ...publicSearchResult(lookup.entry.result), idempotentReplay: true },
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
    message: publicLedgerErrorMessage(lookup.status)
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
    if (!isSharedBridgeRequestAuthorized(req, HOST)) {
      return sendAuthError(res);
    }
    const healthAccountKey = String(requestUrl.searchParams.get('accountKey') || '').trim();
    const healthCapability = String(requestUrl.searchParams.get('capability') || '').trim();
    const prepare = ['1', 'true', 'yes'].includes(String(requestUrl.searchParams.get('prepare') || '').toLowerCase());
    const configuredAccounts = accountBroker.configuredAccounts();
    const targets = healthAccountKey
      ? configuredAccounts.filter(account => account.accountKey === healthAccountKey)
      : configuredAccounts.filter(account => !healthCapability
        || account.capabilities.includes('*')
        || account.capabilities.includes(normalizeCapability(healthCapability)));
    const accountHealth = {
      requestedAccountKey: healthAccountKey || null,
      requestedCapability: healthCapability || null,
      prepareRequested: prepare,
      status: prepare
        ? 'preparing'
        : (startupPreparationComplete ? 'startup_prepared' : 'startup_preparing'),
      note: prepare
        ? 'Preparing and verifying matching account workers.'
        : 'Use ready and capability availability for business readiness.'
    };
    const preparedTargets = [];
    if (prepare) {
      if (targets.length === 0) {
        return sendJson(res, 404, {
          ok: false,
          error: 'broker_target_not_found',
          accountHealth,
          broker: accountBroker.state()
        });
      }
      try {
        preparedTargets.push(...await Promise.all(
          targets.map(account => prepareAccountTarget(account.accountKey))
        ));
        accountHealth.status = 'prepared';
        accountHealth.preparedAccountKeys = preparedTargets.map(target => target.accountKey);
      } catch (error) {
        const classified = classifyBridgeError(error);
        accountHealth.status = 'error';
        accountHealth.message = error && error.message ? error.message : String(error);
        return sendJson(res, 503, {
          ok: false,
          error: classified.error,
          message: error && error.message ? error.message : String(error),
          accountKey: healthAccountKey || null,
          accountHealth,
          broker: accountBroker.state(),
          workers: getWorkerStates()
        });
      }
    }
    const brokerState = accountBroker.state();
    const readiness = bridgeReadiness(
      configuredAccounts, brokerState, sharedTargetStates, startupPreparationComplete
    );
    const activeAccounts = brokerState.accounts.filter(account => account.busy);
    const defaultAccountKey = String(process.env.DEEPSEEK_WEB_DEFAULT_ACCOUNT_KEY || configuredAccounts[0].accountKey);
    const defaultSharedTarget = sharedTargetStates.get(defaultAccountKey) || null;
    return sendJson(res, 200, {
      ok: true,
      ...readiness,
      mode: 'deepseek_account_broker',
      debugPort: DEBUG_PORT,
      defaultDebugPort: DEBUG_PORT,
      waitTimeoutMs: WAIT_TIMEOUT_MS,
      maxInFlightMs: MAX_INFLIGHT_MS,
      abortGraceMs: ABORT_GRACE_MS,
      startupPreparationComplete,
      busy: activeAccounts.length > 0,
      busyForMs: activeAccounts.reduce((maximum, account) => Math.max(
        maximum,
        account.activeRequest ? Date.now() - Number(account.activeRequest.startedAt || Date.now()) : 0
      ), 0),
      cooldownRemainingMs: brokerState.accounts.reduce(
        (minimum, account) => Math.min(minimum, account.cooldownRemainingMs),
        brokerState.accounts.length > 0 ? Infinity : 0
      ),
      activeRequest: activeAccounts.length === 1 ? activeAccounts[0].activeRequest : null,
      activeRequests: activeAccounts.map(account => account.activeRequest),
      sharedTarget: defaultSharedTarget,
      defaultSharedTarget,
      accountHealth,
      preparedTarget: preparedTargets.length === 1 ? preparedTargets[0] : null,
      preparedTargets,
      sharedTargets: Object.fromEntries(sharedTargetStates),
      broker: brokerState,
      requestLedger: requestLedger.summary(),
      workers: getWorkerStates()
    });
  }

  if (req.method === 'GET' && pathname === '/request-status') {
    if (!isSharedBridgeRequestAuthorized(req, HOST)) {
      return sendAuthError(res);
    }
    let submissionKey = (requestUrl.searchParams.get('submissionKey') || '').trim();
    let requestDigest = (requestUrl.searchParams.get('requestDigest') || '').trim();
    if (!submissionKey || !requestDigest) {
      return sendJson(res, 400, { ok: false, error: 'submission_identity_required' });
    }
    try {
      ({ submissionKey, requestDigest } = validateSubmissionIdentity(submissionKey, requestDigest));
    } catch (error) {
      return sendJson(res, 400, { ok: false, error: error.code, message: error.message });
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
    if (!isSharedBridgeRequestAuthorized(req, HOST)) {
      return sendAuthError(res);
    }

    const requestId = ++requestSeq;
    const receivedAt = Date.now();
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
      const sessionKey = normalizeSessionKey(parsed.sessionKey);
      const forceNewSession = parsed.forceNewSession === true || parsed.resetSession === true;
      const expectedJson = parsed.expectedJson === true;
      const requireSearchEvidence = parsed.requireSearchEvidence === true;
      const enableSearch = parsed.enableSearch !== false;
      const mode = typeof parsed.mode === 'string' ? parsed.mode.trim() : '';
      const accountKey = typeof parsed.accountKey === 'string' ? parsed.accountKey.trim() : '';
      const capability = requestCapability(parsed);
      const priority = Number(parsed.priority) || 0;
      const queueTimeoutMs = Number(parsed.queueTimeoutMs || parsed.deadlineMs) || WAIT_FOR_FREE_MS;
      const closeTargetAfter = !sessionKey && isolatedSession && parsed.closeTargetAfter !== false;
      submissionKey = typeof parsed.submissionKey === 'string' ? parsed.submissionKey.trim() : '';
      requestDigest = typeof parsed.requestDigest === 'string' ? parsed.requestDigest.trim() : '';
      if ((submissionKey && !requestDigest) || (!submissionKey && requestDigest)) {
        return sendJson(res, 400, { ok: false, error: 'submission_identity_incomplete' });
      }
      if (!submissionKey && requiresSubmissionIdentity(capability)) {
        return sendJson(res, 400, { ok: false, error: 'submission_identity_required' });
      }
      if (submissionKey) {
        try {
          ({ submissionKey, requestDigest } = validateSubmissionIdentity(submissionKey, requestDigest, {
            query,
            capability,
            sessionKey,
            mode,
            expectedJson,
            requireSearchEvidence,
            enableSearch,
            forceNewSession,
            isolatedSession,
            closeTargetAfter,
            accountKey
          }));
        } catch (error) {
          return sendJson(res, error.code === 'request_digest_mismatch' ? 409 : 400, {
            ok: false,
            error: error.code,
            message: error.message
          });
        }
        const existing = requestLedger.lookup(submissionKey, requestDigest);
        if (sendLedgerResult(res, existing)) return;
        const claim = requestLedger.begin(submissionKey, requestDigest, {
          purpose: typeof parsed.purpose === 'string' ? parsed.purpose : null,
          capability,
          sessionKey: sessionKey || null,
          requestId
        });
        if (!claim.created) {
          sendLedgerResult(res, claim);
          return;
        }
        ledgerClaimed = true;
      }
      console.log(JSON.stringify({
        ok: true,
        event: 'request_start',
        requestId,
        capability,
        accountOverrideRequested: Boolean(accountKey),
        queryLength: query.length,
        expectedJson,
        mode: mode || null,
        priority,
        brokerQueueDepth: accountBroker.state().queueDepth
      }));
      await brokerStartupReady;
      const routed = await accountBroker.submit({
        capability,
        sessionKey,
        forceNewSession,
        accountKey,
        priority,
        queueTimeoutMs
      }, async (routing) => {
        console.log(JSON.stringify({
          ok: true,
          event: 'request_acquired',
          requestId,
          capability,
          accountKey: routing.accountKey,
          attempt: routing.attempt,
          waitedMs: Date.now() - receivedAt
        }));
        await prepareAccountTarget(routing.accountKey);
        routing.refreshIdentityLease();
        return runWithHardTimeout((signal) => searchQuery(query, {
            isolatedSession,
            closeTargetAfter,
            sessionKey,
            forceNewSession,
            expectedJson,
            requireSearchEvidence,
            enableSearch,
            mode,
            accountKey: routing.accountKey,
            signal,
            onSubmitting: async (metadata) => {
              await prepareAccountTarget(routing.accountKey);
              routing.assertIdentityLease();
              routing.markSubmitting();
              if (ledgerClaimed) {
                requestLedger.markSubmitting(submissionKey, requestDigest, {
                  ...metadata,
                  accountKey: routing.accountKey,
                  capability
                });
              }
            },
            onSubmitted: (metadata) => {
              routing.markSubmitted();
              if (ledgerClaimed) {
                requestLedger.markSubmitted(submissionKey, requestDigest, {
                  ...metadata,
                  accountKey: routing.accountKey,
                  capability
                });
              }
            }
          }), {
          timeoutMs: MAX_INFLIGHT_MS,
          abortGraceMs: ABORT_GRACE_MS,
          onTimeout: () => {
            markAccountQuarantined({ accountKey: routing.accountKey }, {
              requestId,
              reason: 'provider_hard_timeout'
            });
          },
          onAbortTimeout: (details) => {
            void (async () => {
              const markerPresent = hasAccountQuarantine({ accountKey: routing.accountKey });
              let browserClosed = false;
              if (!markerPresent) {
                browserClosed = await closeAccountBrowserAndVerify({ accountKey: routing.accountKey });
              }
              if (!markerPresent && !browserClosed) {
                console.error(JSON.stringify({
                  ok: false,
                  event: 'provider_abort_unsettled_fail_stop_suppressed',
                  requestId,
                  capability,
                  accountKey: routing.accountKey,
                  reason: 'quarantine_marker_missing_and_browser_close_unconfirmed',
                  ...details
                }));
                return;
              }
              console.error(JSON.stringify({
                ok: false,
                event: 'provider_abort_unsettled_fail_stop',
                requestId,
                capability,
                accountKey: routing.accountKey,
                recoveryProof: markerPresent ? 'persistent_quarantine_marker' : 'debugger_closed',
                ...details
              }));
              process.exit(70);
            })().catch((failStopError) => {
              console.error(JSON.stringify({
                ok: false,
                event: 'provider_abort_unsettled_fail_stop_suppressed',
                requestId,
                capability,
                accountKey: routing.accountKey,
                reason: 'emergency_browser_close_failed',
                message: failStopError && failStopError.message ? failStopError.message : String(failStopError),
                ...details
              }));
            });
          }
        }).then((result) => {
          routing.assertIdentityLease();
          return result;
        });
      });
      const result = publicSearchResult(routed.result);
      let ledgerOutcome = null;
      if (ledgerClaimed) {
        ledgerOutcome = requestLedger.complete(submissionKey, requestDigest, result);
      }
      const response = bridgeResultResponse(result, ledgerOutcome);
      const resultVerified = response.payload.ok === true;
      console.log(JSON.stringify({
        ok: resultVerified,
        event: 'request_end',
        requestId,
        status: resultVerified ? 'success' : 'unverified_result',
        ...(resultVerified ? {} : { error: response.payload.error }),
        capability,
        accountKey: routed.routing.accountKey,
        attempt: routed.routing.attempt,
        elapsedMs: Date.now() - receivedAt,
        queueWaitMs: routed.routing.queueWaitMs,
        contentStatus: result && result.contentStatus || null,
        completionReason: result && result.completionReason || null,
        answerLength: result && result.answerLength || 0
      }));
      return sendJson(res, response.statusCode, response.payload);
    } catch (error) {
      const classified = classifyBridgeError(error);
      if (ledgerClaimed) {
        try {
          const current = requestLedger.lookup(submissionKey, requestDigest);
          const reservationCount = Number(current.entry && current.entry.submitReservationCount || 0);
          if (reservationCount === 0 && error.providerOutcomeUnknown !== true) {
            requestLedger.markFailedPreSubmit(submissionKey, requestDigest, classified.error);
          } else {
            requestLedger.markUnknown(submissionKey, requestDigest, classified.error);
          }
        } catch (ledgerError) {
          console.error(JSON.stringify({ ok: false, event: 'request_ledger_update_failed',
            submissionKey, message: ledgerError.message }));
        }
      }
      console.error(JSON.stringify({
        ok: false,
        event: 'request_end',
        requestId,
        status: 'error',
        elapsedMs: Date.now() - receivedAt,
        error: classified.error,
        message: classified.message,
        accountKey: error && error.accountKey || null,
        retrySafe: error && error.retrySafe,
        broker: accountBroker.state()
      }));
      return sendJson(res, classified.statusCode, {
        ok: false,
        error: classified.error,
        message: publicBridgeErrorMessage(classified.error),
        requestId,
        providerOutcomeUnknown: error && error.providerOutcomeUnknown === true,
        retrySafe: error && typeof error.retrySafe === 'boolean' ? error.retrySafe : null
      });
    }
  }

  sendJson(res, 404, { ok: false, error: 'not_found' });
});

server.listen(PORT, HOST, () => {
  console.log(JSON.stringify({
    ok: true,
    mode: 'deepseek_account_broker',
    host: HOST,
    port: PORT,
    debugPort: DEBUG_PORT,
    defaultDebugPort: DEBUG_PORT,
    broker: accountBroker.state(),
    workers: getWorkerStates()
  }));
});
