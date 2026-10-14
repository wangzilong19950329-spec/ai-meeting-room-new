'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { normalizeSessionKey } = require('./deepseek-session-identity');

function normalizeAccountKey(value) {
  const normalized = String(value || '')
    .trim()
    .replace(/[^a-zA-Z0-9_.:-]/g, '_')
    .slice(0, 80);
  return normalized || 'default';
}

function normalizeCapability(value) {
  if (String(value || '').trim() === '*') return '*';
  const normalized = String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_.:-]/g, '-')
    .replace(/-+/g, '-')
    .slice(0, 120);
  return normalized || 'general.analysis';
}

function accountEnvSuffix(accountKey) {
  return normalizeAccountKey(accountKey).replace(/[^a-zA-Z0-9]/g, '_').toUpperCase();
}

function splitList(value) {
  return String(value || '')
    .split(',')
    .map(item => item.trim())
    .filter(Boolean);
}

function parseBoolean(value, fallback = false) {
  if (value == null || String(value).trim() === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(value).trim().toLowerCase());
}

function positiveNumber(value, fallback, minimum = 0) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= minimum ? parsed : fallback;
}

function loadAccountConfigs(env = process.env) {
  const defaultAccountKey = normalizeAccountKey(env.DEEPSEEK_WEB_DEFAULT_ACCOUNT_KEY || 'default');
  const configuredKeys = splitList(env.DEEPSEEK_WEB_BROKER_ACCOUNTS);
  const accountKeys = configuredKeys.length > 0 ? configuredKeys : [defaultAccountKey];
  const seen = new Set();
  const accounts = [];

  for (const rawKey of accountKeys) {
    const accountKey = normalizeAccountKey(rawKey);
    if (seen.has(accountKey)) continue;
    seen.add(accountKey);
    const suffix = accountEnvSuffix(accountKey);
    const capabilities = splitList(env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_CAPABILITIES`])
      .map(normalizeCapability);
    accounts.push({
      accountKey,
      enabled: parseBoolean(env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_ENABLED`], true),
      capabilities: new Set(capabilities.length > 0 ? capabilities : ['*']),
      weight: positiveNumber(env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_WEIGHT`], 1, 0.01),
      minRequestIntervalMs: positiveNumber(
        env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_MIN_REQUEST_INTERVAL_MS`],
        positiveNumber(env.DEEPSEEK_WEB_MIN_REQUEST_INTERVAL_MS, 45000)
      ),
      postRequestCooldownMs: positiveNumber(
        env[`DEEPSEEK_WEB_ACCOUNT_${suffix}_POST_REQUEST_COOLDOWN_MS`],
        positiveNumber(env.DEEPSEEK_WEB_POST_REQUEST_COOLDOWN_MS, 15000)
      )
    });
  }

  if (!accounts.some(account => account.enabled)) {
    throw new Error('DeepSeek account broker has no enabled accounts');
  }
  return accounts;
}

function accountSupports(account, capability) {
  return account.enabled && (account.capabilities.has('*') || account.capabilities.has(capability));
}

function publicError(code, message, details = {}) {
  const error = new Error(message || code);
  error.code = code;
  Object.assign(error, details);
  return error;
}

function classifyWorkerFailure(error) {
  const message = String(error && error.message || error || '').toLowerCase();
  if ((error && error.code === 'login_required')
    || message.includes('login may be required') || message.includes('login_required')) {
    return { code: 'login_required', immediateCircuit: true };
  }
  if (message.includes('remote debugging endpoint did not become ready')
    || message.includes('debugger profile mismatch')
    || message.includes('debugger_unavailable')
    || message.includes('crashpad permission')) {
    return { code: 'debugger_unavailable', immediateCircuit: true };
  }
  if (message.includes('timed out waiting for deepseek web answer')
    || message.includes('hard timeout')
    || message.includes('exceeded max_inflight')
    || message.includes('provider_timeout')) {
    return { code: 'provider_timeout', immediateCircuit: true };
  }
  return { code: error && error.code || 'worker_error', immediateCircuit: false };
}

class AccountBroker {
  constructor(options = {}) {
    const accounts = options.accounts || loadAccountConfigs(options.env || process.env);
    this.accounts = new Map();
    this.workers = new Map();
    for (const account of accounts) {
      const normalized = {
        ...account,
        accountKey: normalizeAccountKey(account.accountKey),
        enabled: account.enabled !== false,
        capabilities: account.capabilities instanceof Set
          ? new Set(Array.from(account.capabilities).map(normalizeCapability))
          : new Set((account.capabilities || ['*']).map(normalizeCapability)),
        weight: positiveNumber(account.weight, 1, 0.01),
        minRequestIntervalMs: positiveNumber(account.minRequestIntervalMs, 0),
        postRequestCooldownMs: positiveNumber(account.postRequestCooldownMs, 0)
      };
      if (this.accounts.has(normalized.accountKey)) {
        throw new Error(`Duplicate DeepSeek broker account: ${normalized.accountKey}`);
      }
      this.accounts.set(normalized.accountKey, normalized);
      this.workers.set(normalized.accountKey, {
        accountKey: normalized.accountKey,
        routingEligible: true,
        identityFingerprint: null,
        identityRevision: 0,
        identityCollisionWith: null,
        busy: false,
        activeRequest: null,
        nextAllowedAt: 0,
        assignedCount: 0,
        successCount: 0,
        failureCount: 0,
        consecutiveFailures: 0,
        circuitOpenUntil: 0,
        lastAssignedAt: 0,
        lastSuccessAt: 0,
        lastFailureAt: 0,
        lastFailureCode: null,
        lastFailureMessage: null
      });
    }

    this.queueTimeoutMs = positiveNumber(options.queueTimeoutMs, 180000);
    this.circuitFailureThreshold = positiveNumber(options.circuitFailureThreshold, 3, 1);
    this.circuitOpenMs = positiveNumber(options.circuitOpenMs, 300000);
    this.allowAccountOverride = options.allowAccountOverride === true;
    this.stateFile = options.stateFile === false
      ? ''
      : (options.stateFile || path.join(os.homedir(), '.aimeeting-room', 'deepseek-account-broker-state.json'));
    this.maxAffinities = positiveNumber(options.maxAffinities, 5000, 1);
    this.now = options.now || (() => Date.now());
    this.queue = [];
    this.affinities = new Map();
    this.sessionLeases = new Map();
    this.identityOwners = new Map();
    this.sequence = 0;
    this.dispatchScheduled = false;
    this.wakeTimer = null;
    this.loadState();
  }

  loadState() {
    if (!this.stateFile || !fs.existsSync(this.stateFile)) return;
    try {
      const parsed = JSON.parse(fs.readFileSync(this.stateFile, 'utf8'));
      const affinities = parsed && parsed.affinities && typeof parsed.affinities === 'object'
        ? parsed.affinities
        : {};
      for (const [sessionKey, binding] of Object.entries(affinities)) {
        if (!binding || !this.accounts.has(binding.accountKey)) continue;
        this.affinities.set(normalizeSessionKey(sessionKey), {
          accountKey: binding.accountKey,
          identityFingerprint: /^[a-f0-9]{64}$/.test(String(binding.identityFingerprint || ''))
            ? String(binding.identityFingerprint).toLowerCase()
            : null,
          updatedAt: Number(binding.updatedAt) || this.now()
        });
      }
    } catch (error) {
      throw new Error(`Failed to load DeepSeek broker state: ${error.message}`);
    }
  }

  persistState() {
    if (!this.stateFile) return;
    const ordered = Array.from(this.affinities.entries())
      .sort((left, right) => right[1].updatedAt - left[1].updatedAt)
      .slice(0, this.maxAffinities);
    this.affinities = new Map(ordered);
    fs.mkdirSync(path.dirname(this.stateFile), { recursive: true });
    const temporaryPath = `${this.stateFile}.${process.pid}.tmp`;
    const fileDescriptor = fs.openSync(temporaryPath, 'w');
    try {
      fs.writeFileSync(fileDescriptor, JSON.stringify({
        version: 1,
        affinities: Object.fromEntries(ordered)
      }, null, 2));
      fs.fsyncSync(fileDescriptor);
    } finally {
      fs.closeSync(fileDescriptor);
    }
    fs.renameSync(temporaryPath, this.stateFile);
    const directoryDescriptor = fs.openSync(path.dirname(this.stateFile), 'r');
    try {
      fs.fsyncSync(directoryDescriptor);
    } finally {
      fs.closeSync(directoryDescriptor);
    }
  }

  bindSession(sessionKey, accountKey) {
    if (!sessionKey) return;
    const worker = this.workers.get(accountKey);
    const identityFingerprint = worker && worker.identityFingerprint || null;
    const existing = this.affinities.get(sessionKey);
    if (existing && existing.accountKey === accountKey
      && existing.identityFingerprint === identityFingerprint) {
      existing.updatedAt = this.now();
    } else {
      this.affinities.set(sessionKey, { accountKey, identityFingerprint, updatedAt: this.now() });
    }
    this.persistState();
  }

  clearSession(sessionKey) {
    if (!sessionKey || !this.affinities.delete(sessionKey)) return;
    this.persistState();
  }

  invalidateAffinitiesForAccounts(accountKeys) {
    if (!accountKeys || accountKeys.size === 0) return;
    let changed = false;
    for (const [sessionKey, binding] of this.affinities.entries()) {
      if (binding && accountKeys.has(binding.accountKey)) {
        this.affinities.delete(sessionKey);
        changed = true;
      }
    }
    if (changed) this.persistState();
  }

  registerWorkerIdentity(accountKey, identityFingerprint) {
    const normalizedAccountKey = normalizeAccountKey(accountKey);
    const fingerprint = String(identityFingerprint || '').trim().toLowerCase();
    const worker = this.workers.get(normalizedAccountKey);
    if (!worker) {
      throw publicError('account_not_configured', `DeepSeek account '${normalizedAccountKey}' is not configured`);
    }
    if (!/^[a-f0-9]{64}$/.test(fingerprint)) {
      throw publicError('account_identity_unverified', 'DeepSeek account identity fingerprint is invalid');
    }

    const previousFingerprint = worker.identityFingerprint;
    const previousEligibility = new Map(Array.from(this.workers.values()).map(candidate => [
      candidate.accountKey,
      {
        routingEligible: candidate.routingEligible,
        identityCollisionWith: candidate.identityCollisionWith
      }
    ]));
    if (previousFingerprint !== fingerprint) {
      worker.identityFingerprint = fingerprint;
      worker.identityRevision += 1;
    }
    this.recomputeIdentityEligibility();

    const invalidatedAccounts = new Set();
    if (!previousFingerprint) {
      const hasStaleAffinity = Array.from(this.affinities.values()).some(binding => (
        binding && binding.accountKey === normalizedAccountKey
        && binding.identityFingerprint !== fingerprint
      ));
      if (hasStaleAffinity) invalidatedAccounts.add(normalizedAccountKey);
    }
    if (previousFingerprint && previousFingerprint !== fingerprint) {
      invalidatedAccounts.add(normalizedAccountKey);
    }
    for (const candidate of this.workers.values()) {
      const previous = previousEligibility.get(candidate.accountKey);
      if (previous && (previous.routingEligible !== candidate.routingEligible
        || previous.identityCollisionWith !== candidate.identityCollisionWith)) {
        invalidatedAccounts.add(candidate.accountKey);
      }
    }
    this.invalidateAffinitiesForAccounts(invalidatedAccounts);

    const activeDuplicate = Array.from(this.workers.values()).find(candidate => (
      candidate.accountKey !== normalizedAccountKey
      && candidate.busy
      && candidate.identityFingerprint === fingerprint
    ));
    if (activeDuplicate) {
      throw publicError(
        'identity_lease_conflict',
        `DeepSeek account identity is already active on worker '${activeDuplicate.accountKey}'`,
        { accountKey: normalizedAccountKey, duplicateOf: activeDuplicate.accountKey }
      );
    }
    if (worker.identityCollisionWith) {
      throw publicError(
        'duplicate_account_identity',
        `DeepSeek workers '${worker.identityCollisionWith}' and '${normalizedAccountKey}' are logged into the same account`,
        { accountKey: normalizedAccountKey, duplicateOf: worker.identityCollisionWith }
      );
    }

    if (worker.lastFailureCode === 'login_required') {
      worker.circuitOpenUntil = 0;
      worker.consecutiveFailures = 0;
    }

    return {
      accountKey: normalizedAccountKey,
      identityRevision: worker.identityRevision,
      identityFingerprintShort: fingerprint.slice(0, 12)
    };
  }

  invalidateWorkerIdentity(accountKey) {
    const normalizedAccountKey = normalizeAccountKey(accountKey);
    const worker = this.workers.get(normalizedAccountKey);
    if (!worker) {
      throw publicError('account_not_configured', `DeepSeek account '${normalizedAccountKey}' is not configured`);
    }
    const previousEligibility = new Map(Array.from(this.workers.values()).map(candidate => [
      candidate.accountKey,
      {
        routingEligible: candidate.routingEligible,
        identityCollisionWith: candidate.identityCollisionWith
      }
    ]));
    if (worker.identityFingerprint) {
      worker.identityFingerprint = null;
      worker.identityRevision += 1;
      this.recomputeIdentityEligibility();
    }
    const invalidatedAccounts = new Set([normalizedAccountKey]);
    for (const candidate of this.workers.values()) {
      const previous = previousEligibility.get(candidate.accountKey);
      if (previous && (previous.routingEligible !== candidate.routingEligible
        || previous.identityCollisionWith !== candidate.identityCollisionWith)) {
        invalidatedAccounts.add(candidate.accountKey);
      }
    }
    this.invalidateAffinitiesForAccounts(invalidatedAccounts);
  }

  recomputeIdentityEligibility() {
    this.identityOwners.clear();
    for (const account of this.accounts.values()) {
      const worker = this.workers.get(account.accountKey);
      worker.routingEligible = true;
      worker.identityCollisionWith = null;
      if (!worker.identityFingerprint) continue;
      const owner = this.identityOwners.get(worker.identityFingerprint);
      if (owner) {
        worker.routingEligible = false;
        worker.identityCollisionWith = owner;
      } else {
        this.identityOwners.set(worker.identityFingerprint, worker.accountKey);
      }
    }
  }

  submit(request, execute) {
    if (typeof execute !== 'function') {
      return Promise.reject(publicError('broker_executor_required', 'DeepSeek broker executor is required'));
    }
    const capability = normalizeCapability(request && (request.capability || request.purpose));
    const sessionKey = normalizeSessionKey(request && request.sessionKey);
    const requestedAccountKey = String(request && request.accountKey || '').trim();
    if (requestedAccountKey && !this.allowAccountOverride) {
      return Promise.reject(publicError(
        'account_override_forbidden',
        'Normal DeepSeek requests must declare a capability instead of selecting an account'
      ));
    }
    const forceNewSession = request && request.forceNewSession === true;
    if (forceNewSession) this.clearSession(sessionKey);

    const now = this.now();
    const queueTimeoutMs = positiveNumber(request && request.queueTimeoutMs, this.queueTimeoutMs);
    const explicitDeadline = Number(request && request.deadlineAt);
    const deadlineAt = Number.isFinite(explicitDeadline) && explicitDeadline > now
      ? Math.min(explicitDeadline, now + queueTimeoutMs)
      : now + queueTimeoutMs;

    return new Promise((resolve, reject) => {
      const entry = {
        id: ++this.sequence,
        capability,
        sessionKey,
        requestedAccountKey: requestedAccountKey ? normalizeAccountKey(requestedAccountKey) : '',
        forceNewSession,
        priority: Number(request && request.priority) || 0,
        receivedAt: now,
        deadlineAt,
        attemptedAccounts: new Set(),
        phase: 'QUEUED',
        execute,
        resolve,
        reject,
        timeout: null
      };
      this.armTimeout(entry);
      this.queue.push(entry);
      this.scheduleDispatch();
    });
  }

  armTimeout(entry) {
    if (entry.timeout) clearTimeout(entry.timeout);
    entry.timeout = setTimeout(() => {
      const index = this.queue.indexOf(entry);
      if (index >= 0) this.queue.splice(index, 1);
      this.releaseSessionLease(entry);
      entry.timeout = null;
      entry.reject(publicError('bridge_queue_timeout', 'Timed out waiting for a DeepSeek account worker', {
        capability: entry.capability,
        waitedMs: this.now() - entry.receivedAt
      }));
    }, Math.max(1, entry.deadlineAt - this.now()));
    if (typeof entry.timeout.unref === 'function') entry.timeout.unref();
  }

  scheduleDispatch(delayMs = 0) {
    if (delayMs > 0) {
      if (this.wakeTimer) clearTimeout(this.wakeTimer);
      this.wakeTimer = setTimeout(() => {
        this.wakeTimer = null;
        this.scheduleDispatch();
      }, delayMs);
      if (typeof this.wakeTimer.unref === 'function') this.wakeTimer.unref();
      return;
    }
    if (this.dispatchScheduled) return;
    this.dispatchScheduled = true;
    setImmediate(() => {
      this.dispatchScheduled = false;
      this.dispatch();
    });
  }

  dispatch() {
    if (this.queue.length === 0) return;
    this.queue.sort((left, right) => right.priority - left.priority || left.id - right.id);
    let assignedAny = false;
    let earliestWakeAt = Infinity;

    for (const entry of [...this.queue]) {
      if (this.now() >= entry.deadlineAt) continue;
      let selection;
      try {
        selection = this.selectWorker(entry);
      } catch (error) {
        this.removeQueuedEntry(entry);
        entry.reject(error);
        continue;
      }
      if (selection.worker) {
        this.removeQueuedEntry(entry);
        this.runEntry(entry, selection.worker);
        assignedAny = true;
      } else if (selection.wakeAt) {
        earliestWakeAt = Math.min(earliestWakeAt, selection.wakeAt);
      }
    }

    if (this.queue.length > 0 && Number.isFinite(earliestWakeAt)) {
      this.scheduleDispatch(Math.max(1, earliestWakeAt - this.now()));
    } else if (assignedAny && this.queue.length > 0) {
      this.scheduleDispatch();
    }
  }

  removeQueuedEntry(entry, clearTimer = true) {
    const index = this.queue.indexOf(entry);
    if (index >= 0) this.queue.splice(index, 1);
    if (clearTimer && entry.timeout) {
      clearTimeout(entry.timeout);
      entry.timeout = null;
    }
  }

  selectWorker(entry) {
    const now = this.now();
    const sessionLease = entry.sessionKey ? this.sessionLeases.get(entry.sessionKey) : null;
    if (sessionLease && sessionLease !== entry.id) {
      return { worker: null, wakeAt: 0 };
    }
    const affinity = entry.sessionKey && !entry.forceNewSession
      ? this.affinities.get(entry.sessionKey)
      : null;
    let candidates;
    if (entry.requestedAccountKey) {
      candidates = [entry.requestedAccountKey];
    } else if (affinity) {
      candidates = [affinity.accountKey];
    } else {
      candidates = Array.from(this.accounts.values())
        .filter(account => accountSupports(account, entry.capability))
        .map(account => account.accountKey);
    }

    if (candidates.length === 0) {
      throw publicError(
        'capability_unavailable',
        `No DeepSeek account provides capability '${entry.capability}'`,
        { capability: entry.capability }
      );
    }

    const capabilityCandidates = candidates.filter(accountKey => {
      const account = this.accounts.get(accountKey);
      return account && accountSupports(account, entry.capability);
    });
    if (capabilityCandidates.length === 0) {
      const code = affinity ? 'session_affinity_capability_conflict' : 'account_override_capability_conflict';
      throw publicError(code, `Selected DeepSeek account cannot provide '${entry.capability}'`, {
        capability: entry.capability,
        accountKey: candidates[0]
      });
    }
    const validCandidates = capabilityCandidates.filter(accountKey => (
      this.workers.get(accountKey).routingEligible !== false
    ));
    if (validCandidates.length === 0) {
      throw publicError(
        'duplicate_account_identity',
        'All matching DeepSeek workers are disabled because they duplicate another logged-in account',
        { capability: entry.capability }
      );
    }

    let earliestWakeAt = Infinity;
    const available = [];
    let circuitBlocked = false;
    for (const accountKey of validCandidates) {
      if (entry.attemptedAccounts.has(accountKey)) continue;
      const worker = this.workers.get(accountKey);
      if (worker.circuitOpenUntil > now) {
        circuitBlocked = true;
        earliestWakeAt = Math.min(earliestWakeAt, worker.circuitOpenUntil);
        continue;
      }
      if (worker.busy) continue;
      if (worker.nextAllowedAt > now) {
        earliestWakeAt = Math.min(earliestWakeAt, worker.nextAllowedAt);
        continue;
      }
      available.push(worker);
    }

    if (available.length > 0) {
      available.sort((left, right) => {
        const leftAccount = this.accounts.get(left.accountKey);
        const rightAccount = this.accounts.get(right.accountKey);
        const leftLoad = left.assignedCount / leftAccount.weight;
        const rightLoad = right.assignedCount / rightAccount.weight;
        return leftLoad - rightLoad || left.lastAssignedAt - right.lastAssignedAt
          || left.accountKey.localeCompare(right.accountKey);
      });
      return { worker: available[0] };
    }

    const untried = validCandidates.filter(accountKey => !entry.attemptedAccounts.has(accountKey));
    if (untried.length === 0) {
      throw publicError('all_workers_failed_pre_submit', 'All eligible DeepSeek workers failed before submit', {
        capability: entry.capability,
        attemptedAccounts: Array.from(entry.attemptedAccounts)
      });
    }
    if (untried.every(accountKey => {
      const worker = this.workers.get(accountKey);
      return worker.circuitOpenUntil > now && worker.lastFailureCode === 'login_required';
    })) {
      throw publicError('login_required', 'All matching DeepSeek workers require provider login', {
        capability: entry.capability,
        retrySafe: true
      });
    }
    if (affinity && circuitBlocked) {
      throw publicError('session_worker_unavailable', 'The account bound to this session is unavailable', {
        capability: entry.capability,
        accountKey: affinity.accountKey,
        retrySafe: true
      });
    }
    return { worker: null, wakeAt: Number.isFinite(earliestWakeAt) ? earliestWakeAt : 0 };
  }

  async runEntry(entry, worker) {
    const account = this.accounts.get(worker.accountKey);
    const startedAt = this.now();
    entry.attemptedAccounts.add(worker.accountKey);
    if (entry.sessionKey && !this.sessionLeases.has(entry.sessionKey)) {
      this.sessionLeases.set(entry.sessionKey, entry.id);
    }
    entry.phase = 'PRE_SUBMIT';
    worker.busy = true;
    worker.assignedCount += 1;
    worker.lastAssignedAt = startedAt;
    worker.activeRequest = {
      requestId: entry.id,
      capability: entry.capability,
      sessionKey: entry.sessionKey || null,
      phase: entry.phase,
      startedAt
    };

    let identityRevision = worker.identityRevision;
    let identityLeaseRequired = false;
    const assertIdentityLease = () => {
      if (!worker.identityFingerprint || worker.routingEligible === false
        || worker.identityRevision !== identityRevision) {
        throw publicError(
          'identity_lease_changed',
          `DeepSeek worker '${worker.accountKey}' identity changed after routing`,
          { accountKey: worker.accountKey, retrySafe: entry.phase === 'PRE_SUBMIT' }
        );
      }
      const activeDuplicate = Array.from(this.workers.values()).find(candidate => (
        candidate.accountKey !== worker.accountKey
        && candidate.busy
        && candidate.identityFingerprint === worker.identityFingerprint
      ));
      if (activeDuplicate) {
        throw publicError(
          'identity_lease_conflict',
          `DeepSeek account identity is already active on worker '${activeDuplicate.accountKey}'`,
          { accountKey: worker.accountKey, duplicateOf: activeDuplicate.accountKey, retrySafe: true }
        );
      }
    };
    const context = {
      accountKey: worker.accountKey,
      attempt: entry.attemptedAccounts.size,
      refreshIdentityLease: () => {
        identityRevision = worker.identityRevision;
        identityLeaseRequired = true;
        assertIdentityLease();
        return {
          identityRevision,
          identityFingerprintShort: worker.identityFingerprint.slice(0, 12)
        };
      },
      assertIdentityLease: () => {
        if (identityLeaseRequired) assertIdentityLease();
      },
      markSubmitting: () => {
        if (identityLeaseRequired) assertIdentityLease();
        entry.phase = 'SUBMITTING';
        worker.activeRequest.phase = entry.phase;
        this.bindSession(entry.sessionKey, worker.accountKey);
      },
      markSubmitted: () => {
        if (identityLeaseRequired) assertIdentityLease();
        entry.phase = 'SUBMITTED';
        worker.activeRequest.phase = entry.phase;
        this.bindSession(entry.sessionKey, worker.accountKey);
      }
    };

    let workerReleasePromise = null;
    try {
      const result = await entry.execute(context);
      if (identityLeaseRequired) assertIdentityLease();
      this.bindSession(entry.sessionKey, worker.accountKey);
      worker.successCount += 1;
      worker.consecutiveFailures = 0;
      worker.circuitOpenUntil = 0;
      worker.lastSuccessAt = this.now();
      clearTimeout(entry.timeout);
      entry.timeout = null;
      entry.resolve({
        result,
        routing: {
          accountKey: worker.accountKey,
          capability: entry.capability,
          attempt: context.attempt,
          queueWaitMs: startedAt - entry.receivedAt
        }
      });
      this.releaseSessionLease(entry);
    } catch (error) {
      workerReleasePromise = error && error.workerReleasePromise;
      const failure = classifyWorkerFailure(error);
      worker.failureCount += 1;
      worker.consecutiveFailures += 1;
      worker.lastFailureAt = this.now();
      worker.lastFailureCode = failure.code;
      worker.lastFailureMessage = String(error && error.message || error).slice(0, 500);
      if (failure.immediateCircuit || worker.consecutiveFailures >= this.circuitFailureThreshold) {
        worker.circuitOpenUntil = this.now() + this.circuitOpenMs;
      }

      const affinity = entry.sessionKey ? this.affinities.get(entry.sessionKey) : null;
      const retrySafe = entry.phase === 'PRE_SUBMIT'
        && error.providerOutcomeUnknown !== true
        && !(affinity && affinity.accountKey === worker.accountKey);
      if (retrySafe && this.hasUntriedCandidate(entry)) {
        entry.phase = 'QUEUED';
        this.armTimeout(entry);
        this.queue.push(entry);
      } else {
        clearTimeout(entry.timeout);
        entry.timeout = null;
        if (entry.phase !== 'PRE_SUBMIT') {
          error.providerOutcomeUnknown = true;
          error.retrySafe = false;
          error.accountKey = worker.accountKey;
        } else {
          error.retrySafe = error.providerOutcomeUnknown !== true;
        }
        entry.reject(error);
        this.releaseSessionLease(entry);
      }
    } finally {
      if (workerReleasePromise && typeof workerReleasePromise.then === 'function') {
        worker.activeRequest.phase = 'QUARANTINED';
        await workerReleasePromise.catch(() => {});
      }
      worker.busy = false;
      worker.activeRequest = null;
      worker.nextAllowedAt = this.now() + Math.max(
        account.minRequestIntervalMs,
        account.postRequestCooldownMs
      );
      this.scheduleDispatch();
    }
  }

  releaseSessionLease(entry) {
    if (entry.sessionKey && this.sessionLeases.get(entry.sessionKey) === entry.id) {
      this.sessionLeases.delete(entry.sessionKey);
    }
  }

  hasUntriedCandidate(entry) {
    if (entry.requestedAccountKey) return false;
    if (entry.sessionKey && this.affinities.has(entry.sessionKey)) return false;
    return Array.from(this.accounts.values()).some(account => (
      accountSupports(account, entry.capability)
      && this.workers.get(account.accountKey).routingEligible !== false
      && !entry.attemptedAccounts.has(account.accountKey)
    ));
  }

  configuredAccounts() {
    return Array.from(this.accounts.values()).map(account => ({
      accountKey: account.accountKey,
      enabled: account.enabled,
      capabilities: Array.from(account.capabilities),
      weight: account.weight,
      minRequestIntervalMs: account.minRequestIntervalMs,
      postRequestCooldownMs: account.postRequestCooldownMs
    }));
  }

  state() {
    const now = this.now();
    return {
      mode: 'account_broker',
      queueDepth: this.queue.length,
      affinityCount: this.affinities.size,
      activeSessionCount: this.sessionLeases.size,
      allowAccountOverride: this.allowAccountOverride,
      accounts: this.configuredAccounts().map(account => {
        const worker = this.workers.get(account.accountKey);
        return {
          ...account,
          routingEligible: worker.routingEligible,
          identityVerified: Boolean(worker.identityFingerprint),
          identityRevision: worker.identityRevision,
          identityFingerprintShort: worker.identityFingerprint
            ? worker.identityFingerprint.slice(0, 12)
            : null,
          identityCollisionWith: worker.identityCollisionWith,
          busy: worker.busy,
          activeRequest: worker.activeRequest,
          cooldownRemainingMs: Math.max(0, worker.nextAllowedAt - now),
          circuitOpen: worker.circuitOpenUntil > now,
          circuitRemainingMs: Math.max(0, worker.circuitOpenUntil - now),
          assignedCount: worker.assignedCount,
          successCount: worker.successCount,
          failureCount: worker.failureCount,
          consecutiveFailures: worker.consecutiveFailures,
          lastSuccessAt: worker.lastSuccessAt || null,
          lastFailureAt: worker.lastFailureAt || null,
          lastFailureCode: worker.lastFailureCode,
          lastFailureMessage: worker.lastFailureMessage
        };
      })
    };
  }
}

module.exports = {
  AccountBroker,
  accountSupports,
  classifyWorkerFailure,
  loadAccountConfigs,
  normalizeAccountKey,
  normalizeCapability,
  parseBoolean,
  publicError
};
