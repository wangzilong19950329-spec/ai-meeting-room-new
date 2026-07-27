const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME_BIN = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const DEBUG_PORT = Number(process.env.DEEPSEEK_WEB_DEBUG_PORT || 9333);
const DEFAULT_PROFILE_ROOT = process.env.DEEPSEEK_WEB_PROFILE_ROOT
  || path.join(os.tmpdir(), 'deepseek-web-profile');
const DEFAULT_CHROME_LOG_PATH = process.env.DEEPSEEK_WEB_CHROME_LOG
  || path.join(os.tmpdir(), 'deepseek-web-chrome.log');
const DEFAULT_SESSION_STORE_PATH = process.env.DEEPSEEK_WEB_SESSION_STORE
  || path.join(os.homedir(), '.aimeeting-room', 'deepseek-web-sessions.json');
const SESSION_STORE_META_KEY = '__meta';
const SOURCE_CHROME_DIR = path.join(os.homedir(), 'Library/Application Support/Google/Chrome');
const DEFAULT_ACCOUNT_KEY = normalizeAccountKey(process.env.DEEPSEEK_WEB_DEFAULT_ACCOUNT_KEY || 'default');
const ACCOUNT_WORKERS = new Map();
const WAIT_TIMEOUT_MS = Number(process.env.DEEPSEEK_WEB_WAIT_TIMEOUT_MS || 120000);
const QUICK_RETURN_BUDGET_MS = Number(process.env.DEEPSEEK_WEB_QUICK_RETURN_BUDGET_MS || 12000);
const LINK_ONLY_RETURN_BUDGET_MS = Number(process.env.DEEPSEEK_WEB_LINK_ONLY_RETURN_BUDGET_MS || 30000);
const PAGE_RESET_SETTLE_MS = Number(process.env.DEEPSEEK_WEB_PAGE_RESET_SETTLE_MS || 5000);
const MODE_SETTLE_MS = Number(process.env.DEEPSEEK_WEB_MODE_SETTLE_MS || 2500);
const PRE_SUBMIT_DELAY_MS = Number(process.env.DEEPSEEK_WEB_PRE_SUBMIT_DELAY_MS || 3500);
const POST_SUBMIT_SETTLE_MS = Number(process.env.DEEPSEEK_WEB_POST_SUBMIT_SETTLE_MS || 2500);
const POLL_INTERVAL_MS = Number(process.env.DEEPSEEK_WEB_POLL_INTERVAL_MS || 3500);
const HUMAN_JITTER_MS = Number(process.env.DEEPSEEK_WEB_HUMAN_JITTER_MS || 1200);

async function sleep(ms) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function sleepWithJitter(baseMs, jitterMs = HUMAN_JITTER_MS) {
  const safeBase = Math.max(0, Number(baseMs) || 0);
  const safeJitter = Math.max(0, Number(jitterMs) || 0);
  const extra = safeJitter > 0 ? Math.floor(Math.random() * (safeJitter + 1)) : 0;
  await sleep(safeBase + extra);
}

function normalizeAccountKey(accountKey) {
  const normalized = String(accountKey || '')
    .trim()
    .replace(/[^a-zA-Z0-9_.:-]/g, '_')
    .slice(0, 80);
  return normalized || DEFAULT_ACCOUNT_KEY || 'default';
}

function accountEnvSuffix(accountKey) {
  return normalizeAccountKey(accountKey).replace(/[^a-zA-Z0-9]/g, '_').toUpperCase();
}

function accountEnvValue(accountKey, suffix) {
  const scoped = process.env[`DEEPSEEK_WEB_ACCOUNT_${accountEnvSuffix(accountKey)}_${suffix}`];
  if (scoped) return scoped;
  if (normalizeAccountKey(accountKey) === 'stock') {
    const stockScoped = process.env[`STOCK_WATCH_DEEPSEEK_WEB_${suffix}`];
    if (stockScoped) return stockScoped;
  }
  return '';
}

function stableAccountOffset(accountKey) {
  const key = normalizeAccountKey(accountKey);
  let hash = 2166136261;
  for (let index = 0; index < key.length; index += 1) {
    hash ^= key.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return 1 + ((hash >>> 0) % 400);
}

function allocateDebugPort(accountKey, explicitPort) {
  const used = new Set(Array.from(ACCOUNT_WORKERS.values()).map(worker => worker.debugPort));
  const configured = Number(explicitPort);
  if (Number.isFinite(configured) && configured > 0) {
    return configured;
  }
  let port = DEBUG_PORT + stableAccountOffset(accountKey);
  while (used.has(port)) {
    port += 1;
  }
  return port;
}

function accountScopedPath(prefix, accountKey, suffix) {
  return path.join(os.homedir(), '.aimeeting-room', `${prefix}-${normalizeAccountKey(accountKey)}-${suffix}`);
}

function normalizeProfileBootstrap(raw, fallback) {
  const value = String(raw || fallback || '').trim().toLowerCase();
  return value === 'copy' ? 'copy' : 'empty';
}

function canonicalPath(value) {
  const resolved = path.resolve(String(value || ''));
  try {
    return fs.realpathSync.native(resolved);
  } catch (_) {
    return resolved;
  }
}

function isSamePathOrNested(child, parent) {
  const childPath = canonicalPath(child);
  const parentPath = canonicalPath(parent);
  return childPath === parentPath || childPath.startsWith(`${parentPath}${path.sep}`);
}

function assertNoWorkerConfigConflict(candidate) {
  const candidateProfileRoot = canonicalPath(candidate.profileRoot);
  const candidateSessionStore = canonicalPath(candidate.sessionStorePath);
  for (const worker of ACCOUNT_WORKERS.values()) {
    if (worker.accountKey === candidate.accountKey) continue;
    if (worker.debugPort === candidate.debugPort) {
      throw new Error(`DeepSeek account worker debug port conflict: ${candidate.accountKey} and ${worker.accountKey} both use ${candidate.debugPort}`);
    }
    if (canonicalPath(worker.profileRoot) === candidateProfileRoot) {
      throw new Error(`DeepSeek account worker profile conflict: ${candidate.accountKey} and ${worker.accountKey} both use ${candidate.profileRoot}`);
    }
    if (canonicalPath(worker.sessionStorePath) === candidateSessionStore) {
      throw new Error(`DeepSeek account worker session store conflict: ${candidate.accountKey} and ${worker.accountKey} both use ${candidate.sessionStorePath}`);
    }
  }
}

function resolveWorkerConfig(options = {}) {
  const accountKey = normalizeAccountKey(options.accountKey || options.profileKey || DEFAULT_ACCOUNT_KEY);
  if (ACCOUNT_WORKERS.has(accountKey)) {
    return ACCOUNT_WORKERS.get(accountKey);
  }

  const isDefault = accountKey === DEFAULT_ACCOUNT_KEY || accountKey === 'default';
  const profileRoot = accountEnvValue(accountKey, 'PROFILE_ROOT')
    || (isDefault ? DEFAULT_PROFILE_ROOT : accountScopedPath('deepseek-web', accountKey, 'profile'));
  const sessionStorePath = accountEnvValue(accountKey, 'SESSION_STORE')
    || (isDefault ? DEFAULT_SESSION_STORE_PATH : accountScopedPath('deepseek-web', accountKey, 'sessions.json'));
  const chromeLogPath = accountEnvValue(accountKey, 'CHROME_LOG')
    || (isDefault ? DEFAULT_CHROME_LOG_PATH : accountScopedPath('deepseek-web', accountKey, 'chrome.log'));
  const profileBootstrap = normalizeProfileBootstrap(
    accountEnvValue(accountKey, 'PROFILE_BOOTSTRAP') || process.env.DEEPSEEK_WEB_PROFILE_BOOTSTRAP,
    isDefault ? 'copy' : 'empty'
  );
  const debugPort = isDefault
    ? Number(accountEnvValue(accountKey, 'DEBUG_PORT') || DEBUG_PORT)
    : allocateDebugPort(accountKey, accountEnvValue(accountKey, 'DEBUG_PORT'));

  const worker = {
    accountKey,
    debugPort,
    profileRoot,
    sessionStorePath,
    chromeLogPath,
    profileBootstrap
  };
  assertNoWorkerConfigConflict(worker);
  ACCOUNT_WORKERS.set(accountKey, worker);
  return worker;
}

function workerPublicState(worker) {
  return {
    accountKey: worker.accountKey,
    debugPort: worker.debugPort,
    profileRoot: worker.profileRoot,
    sessionStorePath: worker.sessionStorePath,
    chromeLogPath: worker.chromeLogPath,
    profileBootstrap: worker.profileBootstrap,
    debuggerProfileVerified: worker.debuggerProfileVerified === true,
    debuggerProfileCheck: worker.debuggerProfileCheck || null
  };
}

function getWorkerStates() {
  if (ACCOUNT_WORKERS.size === 0) {
    resolveWorkerConfig();
  }
  return Array.from(ACCOUNT_WORKERS.values()).map(workerPublicState);
}

function normalizeSessionKey(sessionKey) {
  return String(sessionKey || '')
    .trim()
    .replace(/[^a-zA-Z0-9_.:-]/g, '_')
    .slice(0, 120);
}

function loadSessionStore(worker = resolveWorkerConfig()) {
  try {
    if (!fs.existsSync(worker.sessionStorePath)) {
      return {};
    }
    return JSON.parse(fs.readFileSync(worker.sessionStorePath, 'utf8')) || {};
  } catch (_) {
    return {};
  }
}

function saveSessionStore(store, worker = resolveWorkerConfig()) {
  try {
    fs.mkdirSync(path.dirname(worker.sessionStorePath), { recursive: true });
    fs.writeFileSync(worker.sessionStorePath, JSON.stringify(store || {}, null, 2));
  } catch (_) {}
}

function sessionStoreEntries(store) {
  return Object.entries(store || {})
    .filter(([key, value]) => key !== SESSION_STORE_META_KEY && value && typeof value === 'object');
}

function isReusableConversationUrl(url) {
  try {
    const parsed = new URL(String(url || ''));
    return parsed.origin === 'https://chat.deepseek.com'
      && /^\/a\/chat\/s\/[^/]+\/?$/.test(parsed.pathname);
  } catch (_) {
    return false;
  }
}

function selectSharedTarget(matches, store, preferredSessionKey = '') {
  const available = Array.isArray(matches) ? matches : [];
  const byId = new Map(available.map(item => [item.id, item]));
  const metaTargetId = store && store[SESSION_STORE_META_KEY]
    ? store[SESSION_STORE_META_KEY].workerTargetId
    : '';
  if (metaTargetId && byId.has(metaTargetId)) {
    return byId.get(metaTargetId);
  }

  const preferred = preferredSessionKey && store ? store[preferredSessionKey] : null;
  if (preferred && preferred.targetId && byId.has(preferred.targetId)) {
    return byId.get(preferred.targetId);
  }

  const recentMapped = sessionStoreEntries(store)
    .map(([, value]) => value)
    .filter(value => value.targetId && byId.has(value.targetId))
    .sort((left, right) => String(right.updatedAt || '').localeCompare(String(left.updatedAt || '')));
  if (recentMapped.length > 0) {
    return byId.get(recentMapped[0].targetId);
  }
  return available[0] || null;
}

function bindSessionStoreToSharedTarget(store, targetId) {
  const next = { ...(store || {}) };
  next[SESSION_STORE_META_KEY] = {
    ...(next[SESSION_STORE_META_KEY] || {}),
    mode: 'single_worker',
    workerTargetId: targetId,
    updatedAt: new Date().toISOString()
  };
  for (const [key, value] of sessionStoreEntries(next)) {
    next[key] = { ...value, targetId };
  }
  return next;
}

function updateSessionStore(sessionKey, patch, worker = resolveWorkerConfig()) {
  const normalized = normalizeSessionKey(sessionKey);
  if (!normalized) return;
  const store = loadSessionStore(worker);
  const current = store[normalized] || {};
  store[normalized] = {
    ...current,
    ...patch,
    sessionKey: normalized,
    updatedAt: new Date().toISOString()
  };
  if (!store[normalized].createdAt) {
    store[normalized].createdAt = store[normalized].updatedAt;
  }
  saveSessionStore(store, worker);
}

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} for ${url}`);
  }
  return response.json();
}

async function tryFetchJson(url, options) {
  try {
    return await fetchJson(url, options);
  } catch (_) {
    return null;
  }
}

function ensureAutomationProfile(worker = resolveWorkerConfig()) {
  if (!fs.existsSync(path.join(worker.profileRoot, 'Default'))) {
    fs.mkdirSync(worker.profileRoot, { recursive: true });
    if (worker.profileBootstrap === 'empty') {
      fs.mkdirSync(path.join(worker.profileRoot, 'Default'), { recursive: true });
      cleanupProfileLocks(worker);
      return;
    }
    for (const item of ['Local State', 'Default']) {
      const src = path.join(SOURCE_CHROME_DIR, item);
      const dest = path.join(worker.profileRoot, item);
      if (!fs.existsSync(src)) continue;
      fs.cpSync(src, dest, {
        recursive: true,
        force: true,
        filter(source) {
          const base = path.basename(source);
          return !['Cache', 'Code Cache', 'GPUCache', 'GrShaderCache', 'GraphiteDawnCache'].includes(base);
        }
      });
    }
  }
  cleanupProfileLocks(worker);
}

function cleanupProfileLocks(worker = resolveWorkerConfig()) {
  for (const entry of [
    'SingletonCookie',
    'SingletonLock',
    'SingletonSocket',
    path.join('Default', 'LOCK'),
    path.join('Default', 'Cookies-journal')
  ]) {
    const target = path.join(worker.profileRoot, entry);
    try {
      if (fs.existsSync(target)) {
        fs.rmSync(target, { force: true, recursive: true });
      }
    } catch (_) {}
  }
}

function readChromeLogTail(worker = resolveWorkerConfig()) {
  try {
    const chromeLog = fs.readFileSync(worker.chromeLogPath, 'utf8');
    return chromeLog ? chromeLog.slice(-2000) : '(no chrome log captured)';
  } catch (_) {
    return '(no chrome log captured)';
  }
}

async function getExistingDebuggerVersion(worker = resolveWorkerConfig()) {
  return tryFetchJson(`http://127.0.0.1:${worker.debugPort}/json/version`);
}

async function listTargets(worker = resolveWorkerConfig()) {
  return fetchJson(`http://127.0.0.1:${worker.debugPort}/json/list`);
}

async function closeTarget(targetId, worker = resolveWorkerConfig()) {
  if (!targetId) return;
  try {
    await fetch(`http://127.0.0.1:${worker.debugPort}/json/close/${targetId}`, { method: 'PUT' });
  } catch (_) {}
}

async function waitForDebugger(worker = resolveWorkerConfig()) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    try {
      return await fetchJson(`http://127.0.0.1:${worker.debugPort}/json/version`);
    } catch (_) {
      const crashpadError = readChromeLogTail(worker);
      if (crashpadError.includes('crashpad') && crashpadError.toLowerCase().includes('permission denied')) {
        throw new Error(`Chrome remote debugging blocked by crashpad permission\n${crashpadError}`);
      }
      await sleep(500);
    }
  }
  throw new Error(`Chrome remote debugging endpoint did not become ready for account ${worker.accountKey}\n${readChromeLogTail(worker)}`);
}

function launchChrome(worker = resolveWorkerConfig()) {
  fs.mkdirSync(path.dirname(worker.chromeLogPath), { recursive: true });
  const chromeLogFd = fs.openSync(worker.chromeLogPath, 'a');
  return spawn(CHROME_BIN, [
    `--remote-debugging-port=${worker.debugPort}`,
    '--remote-debugging-address=127.0.0.1',
    `--user-data-dir=${worker.profileRoot}`,
    '--profile-directory=Default',
    '--no-first-run',
    '--no-default-browser-check',
    '--enable-automation',
    '--disable-breakpad',
    '--disable-crash-reporter',
    '--disable-crashpad',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-features=MediaRouter,OptimizationHints,AutofillServerCommunication',
    '--metrics-recording-only',
    'https://chat.deepseek.com/'
  ], {
    detached: true,
    stdio: ['ignore', chromeLogFd, chromeLogFd]
  });
}

class CdpClient {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.id = 0;
    this.pending = new Map();
    this.eventListeners = new Map();
    this.ws.addEventListener('message', (event) => {
      const data = JSON.parse(event.data.toString());
      if (data.id && this.pending.has(data.id)) {
        const { resolve, reject } = this.pending.get(data.id);
        this.pending.delete(data.id);
        if (data.error) reject(new Error(JSON.stringify(data.error)));
        else resolve(data.result);
      }
      if (data.method && this.eventListeners.has(data.method)) {
        for (const handler of this.eventListeners.get(data.method)) {
          try {
            handler(data.params || {});
          } catch (_) {}
        }
      }
    });
  }

  async open() {
    if (this.ws.readyState === WebSocket.OPEN) return;
    await new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, { once: true });
      this.ws.addEventListener('error', reject, { once: true });
    });
  }

  async send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
  }

  async eval(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true
    });
    return result.result ? result.result.value : null;
  }

  on(method, handler) {
    if (!this.eventListeners.has(method)) {
      this.eventListeners.set(method, new Set());
    }
    this.eventListeners.get(method).add(handler);
    return () => this.eventListeners.get(method)?.delete(handler);
  }

  close() {
    try {
      this.ws.close();
    } catch (_) {}
  }
}

function extractUserDataDirFromCommandLine(args) {
  const commandLine = Array.isArray(args) ? args : [];
  for (let index = 0; index < commandLine.length; index += 1) {
    const arg = String(commandLine[index] || '');
    if (arg.startsWith('--user-data-dir=')) {
      return arg.slice('--user-data-dir='.length);
    }
    if (arg === '--user-data-dir' && commandLine[index + 1]) {
      return String(commandLine[index + 1]);
    }
  }
  return '';
}

async function readBrowserCommandLine(debuggerVersion) {
  const browserWs = debuggerVersion && debuggerVersion.webSocketDebuggerUrl;
  if (!browserWs) return null;
  const client = new CdpClient(browserWs);
  try {
    await client.open();
    return await client.send('Browser.getBrowserCommandLine');
  } finally {
    client.close();
  }
}

function parseProfilePathFromChromeVersionText(text) {
  const normalized = String(text || '');
  for (const rawLine of normalized.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    if (/^(profile path|个人资料路径|設定檔路徑|プロフィール パス|프로필 경로)\b/i.test(line)) {
      const cleaned = line
        .replace(/^(profile path|个人资料路径|設定檔路徑|プロフィール パス|프로필 경로)\s*/i, '')
        .replace(/^[:：]\s*/, '')
        .trim();
      if (cleaned) return cleaned;
    }
  }
  return '';
}

async function readChromeVersionText(worker) {
  let target = null;
  let client = null;
  try {
    target = await fetchJson(`http://127.0.0.1:${worker.debugPort}/json/new?chrome://version/`, { method: 'PUT' });
    if (!target || !target.webSocketDebuggerUrl) return '';
    client = new CdpClient(target.webSocketDebuggerUrl);
    await client.open();
    await client.send('Runtime.enable');
    await sleep(1000);
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const text = await client.eval('document.body ? document.body.innerText : ""').catch(() => '');
      if (text && (text.includes(worker.profileRoot) || /profile path/i.test(text) || text.includes('个人资料路径'))) {
        return text;
      }
      await sleep(500);
    }
    return '';
  } finally {
    if (client) client.close();
    if (target && target.id) {
      await closeTarget(target.id, worker);
    }
  }
}

async function verifyDebuggerProfile(worker, debuggerVersion) {
  const expectedProfileRoot = worker.profileRoot;
  const check = {
    verified: false,
    expectedProfileRoot,
    method: '',
    actualProfilePath: '',
    error: ''
  };

  try {
    const commandLine = await readBrowserCommandLine(debuggerVersion);
    const userDataDir = extractUserDataDirFromCommandLine(commandLine && commandLine.arguments);
    if (userDataDir) {
      check.method = 'Browser.getBrowserCommandLine';
      check.actualProfilePath = userDataDir;
      if (isSamePathOrNested(userDataDir, expectedProfileRoot)) {
        check.verified = true;
        worker.debuggerProfileVerified = true;
        worker.debuggerProfileCheck = check;
        return check;
      }
    }
  } catch (error) {
    check.error = error && error.message ? error.message : String(error);
  }

  try {
    const versionText = await readChromeVersionText(worker);
    const profilePath = parseProfilePathFromChromeVersionText(versionText)
      || (versionText.includes(expectedProfileRoot) ? expectedProfileRoot : '');
    if (profilePath) {
      check.method = check.method || 'chrome://version';
      check.actualProfilePath = profilePath;
      if (isSamePathOrNested(profilePath, expectedProfileRoot)) {
        check.verified = true;
        worker.debuggerProfileVerified = true;
        worker.debuggerProfileCheck = check;
        return check;
      }
    }
  } catch (error) {
    check.error = check.error || (error && error.message ? error.message : String(error));
  }

  worker.debuggerProfileVerified = false;
  worker.debuggerProfileCheck = check;
  if (['1', 'true', 'yes'].includes(String(process.env.DEEPSEEK_WEB_ALLOW_UNVERIFIED_DEBUGGER || '').toLowerCase())) {
    console.error(JSON.stringify({
      ok: false,
      event: 'debugger_profile_unverified_allowed',
      accountKey: worker.accountKey,
      debugPort: worker.debugPort,
      ...check
    }));
    return check;
  }

  throw new Error(
    `DeepSeek Chrome debugger profile mismatch or unverified for account ${worker.accountKey}: `
    + `debugPort=${worker.debugPort} expectedProfileRoot=${expectedProfileRoot} `
    + `actualProfilePath=${check.actualProfilePath || 'unknown'} method=${check.method || 'none'} `
    + `error=${check.error || 'none'}`
  );
}

// Bridge requests are serialized, so logical sessions can safely share one Chrome page.
// Conversation continuity comes from the saved DeepSeek URL instead of a permanent tab.
async function openPageTarget(options = {}) {
  const worker = resolveWorkerConfig(options);
  const targets = await listTargets(worker);
  const matches = targets.filter((item) =>
    item
    && item.type === 'page'
    && item.webSocketDebuggerUrl
    && (item.url || '').includes('chat.deepseek.com')
  );
  const sessionKey = normalizeSessionKey(options.sessionKey);
  const store = loadSessionStore(worker);
  const savedSession = sessionKey ? store[sessionKey] : null;
  const requestedMode = normalizeBridgeMode(options.mode || options.webMode || DEFAULT_BRIDGE_MODE);
  const savedBridgeMode = String(savedSession && savedSession.bridgeMode || '').toLowerCase();
  const resumeUrl = sessionKey
    && !options.forceNewSession
    && savedSession
    && isReusableConversationUrl(savedSession.url)
    && !(requestedMode === 'expert' && savedBridgeMode !== 'expert')
    ? savedSession.url
    : '';
  let target = selectSharedTarget(matches, store, sessionKey);
  const reused = !!target;
  if (!target) {
    target = await fetchJson(`http://127.0.0.1:${worker.debugPort}/json/new?https://chat.deepseek.com/`, { method: 'PUT' });
  }

  const duplicateTargets = matches.filter(item => item.id !== target.id);
  await Promise.all(duplicateTargets.map(item => closeTarget(item.id, worker)));
  saveSessionStore(bindSessionStoreToSharedTarget(store, target.id), worker);
  if (sessionKey) {
    updateSessionStore(sessionKey, {
      targetId: target.id,
      forceNewSession: !!options.forceNewSession
    }, worker);
  }
  return {
    targetId: target.id,
    webSocketDebuggerUrl: target.webSocketDebuggerUrl,
    reused,
    isolatedSession: !!options.isolatedSession,
    accountKey: worker.accountKey,
    debugPort: worker.debugPort,
    profileRoot: worker.profileRoot,
    sessionKey,
    resumeUrl,
    sharedTarget: true,
    closedDuplicateTargets: duplicateTargets.length
  };
}

async function ensureDebuggerAvailable(options = {}) {
  const worker = resolveWorkerConfig(options);
  let debuggerVersion = await getExistingDebuggerVersion(worker);
  if (!debuggerVersion) {
    ensureAutomationProfile(worker);
    const chrome = launchChrome(worker);
    chrome.unref();
    debuggerVersion = await waitForDebugger(worker);
  }
  await verifyDebuggerProfile(worker, debuggerVersion);
  return debuggerVersion;
}

async function prepareSharedTarget(options = {}) {
  const worker = resolveWorkerConfig(options);
  await ensureDebuggerAvailable(worker);
  const target = await openPageTarget({ ...options, prepareOnly: true, accountKey: worker.accountKey });
  return {
    mode: 'single_worker',
    accountKey: worker.accountKey,
    debugPort: worker.debugPort,
    profileRoot: worker.profileRoot,
    targetId: target.targetId,
    reused: target.reused,
    closedDuplicateTargets: target.closedDuplicateTargets
  };
}

async function resetConversationSurface(client) {
  try {
    await client.send('Page.navigate', { url: 'https://chat.deepseek.com/' });
  } catch (_) {}
  await sleepWithJitter(PAGE_RESET_SETTLE_MS);
}

async function restoreConversationSurface(client, url) {
  const currentUrl = await client.eval('location.href').catch(() => '');
  if (currentUrl === url) {
    return { restored: true, navigated: false, url };
  }
  await client.send('Page.navigate', { url });
  await sleepWithJitter(PAGE_RESET_SETTLE_MS);
  return { restored: true, navigated: true, url };
}

async function tryStartFreshConversation(client) {
  const js = `
    (() => {
      const candidates = Array.from(document.querySelectorAll('button,[role="button"],a'))
        .filter(el => {
          const text = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || '').trim();
          return text && /新对话|开启新对话|新建对话|New chat|new chat|New conversation/i.test(text);
        });
      const target = candidates[0];
      if (!target) return { clicked: false };
      target.click();
      return {
        clicked: true,
        label: (target.innerText || target.getAttribute('aria-label') || target.getAttribute('title') || '').trim().slice(0, 80)
      };
    })()
  `;
  try {
    const result = await client.eval(js);
    if (result && result.clicked) {
      await sleepWithJitter(PAGE_RESET_SETTLE_MS, 800);
    }
    return result || { clicked: false };
  } catch (_) {
    return { clicked: false };
  }
}

async function waitForComposer(client) {
  const deadline = Date.now() + 60000;
  const js = `
    (() => {
      const textarea = document.querySelector('textarea');
      const sendButton = Array.from(document.querySelectorAll('button')).find(btn => {
        const label = (btn.innerText || btn.getAttribute('aria-label') || '').toLowerCase();
        return label.includes('发送') || label.includes('send');
      });
      return {
        href: location.href,
        ready: !!textarea,
        textareaHtml: textarea ? textarea.outerHTML : null,
        sendLabel: sendButton ? (sendButton.innerText || sendButton.getAttribute('aria-label') || '') : null
      };
    })()
  `;
  while (Date.now() < deadline) {
    const state = await client.eval(js);
    if (state && state.ready) return state;
    await sleep(1000);
  }
  throw new Error('DeepSeek composer textarea did not appear; login may be required');
}

// 默认 fast：经实测，DeepSeek 网页的 expert 模式不适合作为通用联网搜索入口，
// 只有 fast（默认对话模式）更稳定拿到真实搜索结果与可点击 URL。
// 需要专业/专家模式的调用方可以在单次请求里传 mode=expert，避免影响其它搜索链路。
function normalizeBridgeMode(raw) {
  const value = String(raw || '').trim().toLowerCase();
  return value === 'expert' || value === 'professional' ? 'expert' : 'fast';
}

const DEFAULT_BRIDGE_MODE = normalizeBridgeMode(process.env.DEEPSEEK_WEB_MODE || 'fast');

function buildToggleJs(mode) {
  const wantExpert = mode === 'expert';
  return `
    (() => {
      const updates = [];
      const wantExpert = ${wantExpert ? 'true' : 'false'};
      const textOf = (el) => ((el && (el.innerText || el.textContent || el.getAttribute('aria-label') || el.getAttribute('title'))) || '').trim();
      const classOf = (el) => String((el && el.className) || '');
      const rectOf = (el) => {
        if (!el || !el.getBoundingClientRect) return { x: 0, y: 0, width: 0, height: 0 };
        return el.getBoundingClientRect();
      };
      const isVisible = (el) => {
        if (!el || !el.getBoundingClientRect) return false;
        const rect = rectOf(el);
        const style = getComputedStyle(el);
        return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
      };
      const isSelected = (el) => {
        if (!el) return false;
        const checked = String(el.getAttribute('aria-checked') || '').toLowerCase() === 'true';
        const pressed = String(el.getAttribute('aria-pressed') || '').toLowerCase() === 'true';
        const selected = String(el.getAttribute('aria-selected') || '').toLowerCase() === 'true';
        const className = classOf(el);
        return checked || pressed || selected || className.includes('selected') || className.includes('--active') || className.includes('active');
      };
      const isModeText = (text, aliases) => {
        const normalized = String(text || '').replace(/\\s+/g, '');
        if (!normalized || !aliases.some(alias => normalized.includes(alias.replace(/\\s+/g, '')))) return false;
        const modeHits = ['快速模式', '专家模式', '专业模式', '识图模式'].filter(label => normalized.includes(label)).length;
        return modeHits <= 1;
      };
      const isLikelyClickable = (el) => {
        if (!el || el === document.body || el === document.documentElement) return false;
        const tag = String(el.tagName || '').toLowerCase();
        const role = String(el.getAttribute('role') || '').toLowerCase();
        const className = classOf(el);
        const tabIndex = el.getAttribute('tabindex');
        const cursor = getComputedStyle(el).cursor;
        return tag === 'button'
          || role === 'button'
          || role === 'radio'
          || el.hasAttribute('data-model-type')
          || el.hasAttribute('aria-pressed')
          || el.hasAttribute('aria-checked')
          || el.hasAttribute('aria-selected')
          || tabIndex === '0'
          || className.includes('toggle')
          || className.includes('button')
          || className.includes('segment')
          || className.includes('mode')
          || cursor === 'pointer';
      };
      const clickableAncestor = (node, stopAt = null) => {
        let current = node;
        let fallback = null;
        while (current && current !== document.body && current !== stopAt) {
          if (isVisible(current)) {
            const rect = rectOf(current);
            if (!fallback && rect.width >= 48 && rect.height >= 20 && rect.width <= 360 && rect.height <= 90) {
              fallback = current;
            }
            if (isLikelyClickable(current)) return current;
          }
          current = current.parentElement;
        }
        return fallback || node;
      };
      const controls = Array.from(document.querySelectorAll(
        'button,[role="button"],[role="radio"],[aria-pressed],[aria-checked],[data-model-type],.ds-toggle-button'
      )).filter(isVisible);
      const visibleNodes = Array.from(document.querySelectorAll(
        'button,div,span,[role="button"],[role="radio"],[aria-pressed],[aria-checked],[aria-selected],[data-model-type],.ds-toggle-button'
      )).filter(isVisible);
      const modeContainer = visibleNodes.find((el) => {
        const text = textOf(el).replace(/\\s+/g, '');
        const rect = rectOf(el);
        return text.includes('快速模式')
          && (text.includes('专家模式') || text.includes('专业模式'))
          && text.includes('识图模式')
          && rect.width <= 760
          && rect.height <= 140;
      });
      const findByModelType = (types) => controls.find((el) => {
        const value = String(el.getAttribute('data-model-type') || '').toLowerCase();
        return types.includes(value);
      });
      const findByText = (aliases) => visibleNodes.find((el) => {
        const text = textOf(el);
        return isModeText(text, aliases);
      });
      const findInModeContainer = (aliases) => {
        if (!modeContainer) return null;
        const descendants = Array.from(modeContainer.querySelectorAll('button,div,span,[role="button"],[role="radio"],[data-model-type],[aria-selected],[aria-checked]'))
          .filter(isVisible);
        const exact = descendants.find((el) => aliases.some(alias => textOf(el).replace(/\\s+/g, '') === alias.replace(/\\s+/g, '')));
        const loose = exact || descendants.find((el) => isModeText(textOf(el), aliases));
        return loose ? clickableAncestor(loose, modeContainer) : null;
      };
      const findModeOption = (aliases, modelTypes) => {
        const byType = findByModelType(modelTypes);
        if (byType) return byType;
        const inContainer = findInModeContainer(aliases);
        if (inContainer) return inContainer;
        const byText = findByText(aliases);
        return byText ? clickableAncestor(byText) : null;
      };
      const modeFromText = () => {
        const compactBody = (document.body.innerText || '').replace(/\\s+/g, '');
        if (compactBody.includes('使用专家模式开始对话') || compactBody.includes('使用专业模式开始对话')) return 'expert';
        if (compactBody.includes('使用快速模式开始对话')) return 'fast';
        const headerMode = visibleNodes
          .map((el) => ({ el, text: textOf(el), rect: rectOf(el) }))
          .filter(item => item.rect.y >= 0 && item.rect.y <= 110)
          .find(item => ['快速模式', '专家模式', '专业模式', '识图模式'].includes(item.text));
        if (!headerMode) return '';
        if (headerMode.text === '专家模式' || headerMode.text === '专业模式') return 'expert';
        if (headerMode.text === '快速模式') return 'fast';
        if (headerMode.text === '识图模式') return 'vision';
        return '';
      };
      const expert = findModeOption(['专家模式', '专业模式'], ['expert', 'professional']);
      const quick = findModeOption(['快速模式'], ['default', 'fast', 'quick']);

      if (wantExpert) {
        if (expert) {
          const selected = isSelected(expert);
          if (!selected) {
            expert.click();
          }
          updates.push({ label: '专家模式', found: true, selectedBefore: selected, selectedAfter: isSelected(expert), text: textOf(expert).slice(0, 80) });
        } else {
          updates.push({ label: '专家模式', found: false });
        }
        if (quick) {
          updates.push({ label: '快速模式', found: true, selected: isSelected(quick), text: textOf(quick).slice(0, 80) });
        }
      } else {
        if (quick) {
          const selected = isSelected(quick);
          if (!selected) {
            quick.click();
          }
          updates.push({ label: '快速模式', found: true, selectedBefore: selected, selectedAfter: isSelected(quick), text: textOf(quick).slice(0, 80) });
        } else {
          updates.push({ label: '快速模式', found: false });
        }
        if (expert) {
          updates.push({ label: '专家模式', found: true, selected: isSelected(expert), text: textOf(expert).slice(0, 80) });
        }
      }

      // Only manipulate deep think / search toggles in expert mode. DeepSeek currently renders
      // these controls as div.ds-toggle-button with aria-pressed, not always as real buttons.
      if (wantExpert) {
        const targets = [
          { label: '深度思考', aliases: ['深度思考'] },
          { label: '智能搜索', aliases: ['智能搜索', '联网搜索'] }
        ];
        for (const target of targets) {
          const button = controls.find((el) => target.aliases.some(alias => textOf(el).includes(alias)));
          if (!button) {
            updates.push({ label: target.label, found: false, pressedAfter: 'false' });
            continue;
          }
          const pressed = isSelected(button);
          if (!pressed) {
            button.click();
          }
          const pressedAfter = isSelected(button);
          updates.push({ label: target.label, found: true, changed: !pressed, pressedAfter: String(pressedAfter) });
        }
      }
      const detectedMode = modeFromText()
        || (expert && isSelected(expert) ? 'expert' : '')
        || (quick && isSelected(quick) ? 'fast' : '');
      const requestedMode = wantExpert ? 'expert' : 'fast';
      updates.push({
        label: '页面模式',
        found: !!detectedMode,
        mode: detectedMode || 'unknown',
        requestedMode,
        modeVerified: detectedMode === requestedMode
      });
      return updates;
    })()
  `;
}

async function ensureToggleModes(client, mode = DEFAULT_BRIDGE_MODE) {
  const bridgeMode = normalizeBridgeMode(mode);
  const js = buildToggleJs(bridgeMode);
  let lastResult = [];
  for (let i = 0; i < 12; i++) {
    const result = await client.eval(js);
    lastResult = Array.isArray(result) ? result : [];
    if (bridgeMode === 'fast') {
      const quick = lastResult.find(item => item.label === '快速模式');
      if (quick && quick.found && quick.selectedAfter) {
        break;
      }
    } else {
      const expert = lastResult.find(item => item.label === '专家模式');
      const deep = lastResult.find(item => item.label === '深度思考');
      const search = lastResult.find(item => item.label === '智能搜索');
      const pageMode = lastResult.find(item => item.label === '页面模式');
      const expertReady = expert && expert.found && (expert.selectedAfter || expert.selected || pageMode?.mode === 'expert');
      const modeReady = pageMode && pageMode.modeVerified === true;
      const deepReady = deep && deep.found && String(deep.pressedAfter).toLowerCase() === 'true';
      const searchReady = !search || !search.found || String(search.pressedAfter).toLowerCase() === 'true';
      if (expertReady && modeReady && deepReady && searchReady) {
        break;
      }
    }
    await sleepWithJitter(900, 500);
  }
  await sleepWithJitter(MODE_SETTLE_MS);
  return lastResult;
}

async function submitQuery(client, query) {
  const escaped = JSON.stringify(query);
  return client.eval(`
    (() => {
      const textarea = document.querySelector('textarea');
      if (!textarea) return { ok: false, reason: 'no_textarea' };
      textarea.focus();
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
      setter.call(textarea, ${escaped});
      textarea.dispatchEvent(new Event('input', { bubbles: true }));
      textarea.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        cancelable: true,
        data: ${escaped},
        inputType: 'insertText'
      }));
      textarea.dispatchEvent(new Event('change', { bubbles: true }));

      const collectComposerScopes = () => {
        const scopes = [];
        let node = textarea.parentElement;
        while (node && scopes.length < 6) {
          scopes.push(node);
          node = node.parentElement;
        }
        return scopes;
      };
      const findSendButton = () => {
        const scopes = collectComposerScopes();
        for (const scope of scopes) {
          const exact = Array.from(scope.querySelectorAll('button,[role="button"]')).find(btn =>
            (btn.className || '').includes('_52c986b')
          );
          if (exact) return exact;
        }
        for (const scope of scopes) {
          const labeled = Array.from(scope.querySelectorAll('button,[role="button"]')).find(btn => {
            const label = (btn.innerText || btn.getAttribute('aria-label') || '').toLowerCase();
            return label.includes('发送') || label.includes('send');
          });
          if (labeled) return labeled;
        }
        return Array.from(document.querySelectorAll('button,[role="button"]')).find(btn =>
          (btn.className || '').includes('_52c986b')
        ) || null;
      };
      const sendButton = findSendButton();
      if (sendButton) {
        if ((sendButton.getAttribute('aria-disabled') || '').toLowerCase() === 'true' || sendButton.disabled) {
          return {
            ok: false,
            reason: 'send_button_disabled',
            valueLength: textarea.value.length,
            buttonHtml: (sendButton.outerHTML || '').slice(0, 500)
          };
        }
        sendButton.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
        sendButton.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
        sendButton.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
        sendButton.click();
        return {
          ok: true,
          method: 'button',
          valueLength: textarea.value.length,
          buttonHtml: (sendButton.outerHTML || '').slice(0, 500)
        };
      }
      textarea.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Enter',
        code: 'Enter',
        keyCode: 13,
        which: 13,
        bubbles: true,
        cancelable: true
      }));
      textarea.dispatchEvent(new KeyboardEvent('keyup', {
        key: 'Enter',
        code: 'Enter',
        keyCode: 13,
        which: 13,
        bubbles: true
      }));
      return { ok: true, method: 'enter', valueLength: textarea.value.length };
    })()
  `);
}

function buildTurnSignature(text) {
  const normalized = String(text || '').replace(/\s+/g, ' ').trim();
  if (!normalized) return '';
  let hash = 2166136261;
  for (let index = 0; index < normalized.length; index += 1) {
    hash ^= normalized.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `${normalized.length}:${(hash >>> 0).toString(16)}`;
}

async function captureSearchState(client, expectedTurnSignature = '') {
  const serializedExpectedTurnSignature = JSON.stringify(expectedTurnSignature || '');
  return client.eval(`
    (() => {
      const assistantSelector = '.ds-assistant-message-main-content, .ds-markdown.ds-assistant-message-main-content';
      const expectedTurnSignature = ${serializedExpectedTurnSignature};
      const buildTurnSignature = (text) => {
        const normalized = String(text || '').replace(/\\s+/g, ' ').trim();
        if (!normalized) return '';
        let hash = 2166136261;
        for (let index = 0; index < normalized.length; index += 1) {
          hash ^= normalized.charCodeAt(index);
          hash = Math.imul(hash, 16777619);
        }
        return normalized.length + ':' + (hash >>> 0).toString(16);
      };
      const toAssistantItem = (node) => ({
          text: (node.innerText || '').trim(),
          html: (node.outerHTML || '').slice(0, 300),
          className: node.className || ''
        });
      const allAssistantNodes = Array.from(document.querySelectorAll(assistantSelector));
      const messageContainers = Array.from(document.querySelectorAll('.ds-message'));
      let matchedUserIndex = -1;
      if (expectedTurnSignature) {
        for (let index = messageContainers.length - 1; index >= 0; index -= 1) {
          const container = messageContainers[index];
          if (container.querySelector(assistantSelector)) continue;
          if (buildTurnSignature(container.innerText || '') === expectedTurnSignature) {
            matchedUserIndex = index;
            break;
          }
        }
      }
      const turnAssistantNodes = [];
      const seenTurnNodes = new Set();
      if (matchedUserIndex >= 0) {
        for (const container of messageContainers.slice(matchedUserIndex + 1)) {
          for (const node of container.querySelectorAll(assistantSelector)) {
            if (!seenTurnNodes.has(node)) {
              seenTurnNodes.add(node);
              turnAssistantNodes.push(node);
            }
          }
        }
      }
      // Once the submitted user turn is visible, only inspect assistant blocks after it.
      // This avoids treating a repeated structured answer as historical content merely
      // because its class name and opening text match the previous turn.
      const submittedTurnMatched = matchedUserIndex >= 0;
      const selectedAssistantNodes = submittedTurnMatched ? turnAssistantNodes : allAssistantNodes;
      const assistantBlocks = selectedAssistantNodes.map(toAssistantItem).filter(item => item.text);
      const lastAssistantBlock = selectedAssistantNodes.slice(-1)[0] || null;
      const lastMessageContainer = lastAssistantBlock
        ? lastAssistantBlock.closest('.ds-message') || lastAssistantBlock.parentElement || lastAssistantBlock
        : null;
      const bodyText = lastMessageContainer
        ? (lastMessageContainer.innerText || '').trim()
        : '';
      const anchors = Array.from((lastAssistantBlock || document.createElement('div')).querySelectorAll('a[href^="http"]'))
        .map(a => ({ text: (a.innerText || '').trim(), href: a.href }))
        .filter(item => item.href && !item.href.startsWith('https://chat.deepseek.com'));
      const searchCountMatch = bodyText.match(/搜索到\\s*(\\d+)\\s*个网页/);
      const actionButtons = Array.from(document.querySelectorAll('button,[role="button"]'));
      const generationActive = actionButtons.some(button => {
        const label = (button.innerText || button.getAttribute('aria-label') || button.getAttribute('title') || '').trim();
        return /停止生成|停止回答|stop generating|stop response/i.test(label);
      });
      return {
        bodyText,
        searchCount: searchCountMatch ? Number(searchCountMatch[1]) : null,
        links: anchors.slice(-30),
        candidateTexts: assistantBlocks,
        assistantBlockCount: assistantBlocks.length,
        allAssistantBlockCount: allAssistantNodes.length,
        submittedTurnMatched,
        generationActive
      };
    })()
  `);
}

function diffSearchState(state, baseline = null) {
  if (!baseline) return state;
  if (state && state.submittedTurnMatched) {
    return {
      ...state,
      bodyDelta: state.bodyText || ''
    };
  }
  const buildSignature = (item) => {
    if (!item) return '';
    const text = (item.text || '').trim().slice(0, 240);
    const cls = (item.className || '').trim();
    return `${cls}::${text}`;
  };
  const baselineSignatureCounts = new Map();
  for (const signature of (baseline.candidateTexts || []).map(buildSignature).filter(Boolean)) {
    baselineSignatureCounts.set(signature, (baselineSignatureCounts.get(signature) || 0) + 1);
  }
  const baselineLinks = new Set((baseline.links || []).map(item => item.href).filter(Boolean));
  const baselineBody = baseline.bodyText || '';
  const bodyText = state.bodyText || '';
  const changedCandidateTexts = (state.candidateTexts || []).filter((item) => {
    const signature = buildSignature(item);
    if (!signature) return false;
    const count = baselineSignatureCounts.get(signature) || 0;
    if (count > 0) {
      baselineSignatureCounts.set(signature, count - 1);
      return false;
    }
    return true;
  });
  return {
    bodyText,
    bodyDelta: bodyText.startsWith(baselineBody) ? bodyText.slice(baselineBody.length).trim() : bodyText,
    searchCount: state.searchCount,
    links: (state.links || []).filter(item => item && item.href && !baselineLinks.has(item.href)),
    candidateTexts: changedCandidateTexts,
    assistantBlockCount: Math.max(0, Number(state.assistantBlockCount || 0) - Number(baseline.assistantBlockCount || 0)),
    generationActive: !!state.generationActive
  };
}

function buildModeState(toggleUpdates, mode = DEFAULT_BRIDGE_MODE) {
  const updates = Array.isArray(toggleUpdates) ? toggleUpdates : [];
  const find = (label) => updates.find(item => item && item.label === label);
  const expert = find('专家模式');
  const quick = find('快速模式');
  const deep = find('深度思考');
  const search = find('智能搜索');
  const pageMode = find('页面模式');
  const isPressed = (item) => !!(item && item.found && String(item.pressedAfter).toLowerCase() === 'true');
  const bridgeMode = normalizeBridgeMode(mode);
  const expertExplicit = !!(expert && expert.found && (expert.selectedAfter === true || expert.selected === true));
  const quickExplicit = !!(quick && quick.found && (quick.selectedAfter === true || quick.selected === true));
  const deepThinkEnabled = isPressed(deep);
  const headerMode = pageMode && pageMode.found ? pageMode.mode : null;
  const modeVerified = pageMode && pageMode.modeVerified === true
    ? true
    : (bridgeMode === 'expert' ? expertExplicit : quickExplicit);
  return {
    bridgeMode,
    expertMode: bridgeMode === 'expert' && (expertExplicit || headerMode === 'expert'),
    quickMode: quickExplicit || headerMode === 'fast',
    headerMode,
    modeVerified,
    deepThinkEnabled,
    searchEnabled: isPressed(search)
  };
}

function hasCompleteJson(text) {
  if (!text || !text.trim()) return false;
  let value = text.trim().replace(/^```[a-zA-Z]*\s*/, '').replace(/\s*```$/, '').trim();
  try {
    JSON.parse(value);
    return true;
  } catch (_) {}
  const start = value.indexOf('{');
  const end = value.lastIndexOf('}');
  if (start < 0 || end <= start) return false;
  try {
    JSON.parse(value.slice(start, end + 1));
    return true;
  } catch (_) {
    return false;
  }
}

function parseDeepSeekCompletionSse(body) {
  let answerText = '';
  let activeFragmentType = '';
  let activePatchPath = '';
  let finished = false;
  const appendFragment = (fragment) => {
    if (!fragment || typeof fragment !== 'object') return;
    activeFragmentType = String(fragment.type || '').toUpperCase();
    if (activeFragmentType === 'RESPONSE' && typeof fragment.content === 'string') {
      answerText += fragment.content;
    }
  };
  const inspectBatch = (items) => {
    for (const item of Array.isArray(items) ? items : []) {
      if (!item || typeof item !== 'object') continue;
      if ((item.p === 'status' || item.p === 'quasi_status') && String(item.v || '').toUpperCase() === 'FINISHED') {
        finished = true;
      }
    }
  };

  for (const rawLine of String(body || '').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === 'event: close') {
      finished = true;
      continue;
    }
    if (!line.startsWith('data:')) continue;
    const serialized = line.slice(5).trim();
    if (!serialized || serialized === '[DONE]') continue;
    let payload;
    try {
      payload = JSON.parse(serialized);
    } catch (_) {
      continue;
    }

    const response = payload && payload.v && payload.v.response;
    if (response && typeof response === 'object') {
      for (const fragment of response.fragments || []) appendFragment(fragment);
      if (String(response.status || response.quasi_status || '').toUpperCase() === 'FINISHED') finished = true;
    }

    if (payload && typeof payload.p === 'string') {
      activePatchPath = payload.p;
      if (payload.p === 'response/fragments') {
        for (const fragment of Array.isArray(payload.v) ? payload.v : [payload.v]) appendFragment(fragment);
      } else if (payload.p.endsWith('/content') && activeFragmentType === 'RESPONSE' && typeof payload.v === 'string') {
        answerText += payload.v;
      } else if (payload.p === 'response/status' && String(payload.v || '').toUpperCase() === 'FINISHED') {
        finished = true;
      } else if (payload.p === 'response' && payload.o === 'BATCH') {
        inspectBatch(payload.v);
      }
    } else if (activePatchPath.endsWith('/content') && activeFragmentType === 'RESPONSE' && typeof payload.v === 'string') {
      answerText += payload.v;
    }
  }

  return { answerText, finished };
}

function createDeepSeekCompletionCapture(client) {
  const trackedRequestIds = new Set();
  const state = {
    answerText: '',
    completed: false,
    responseStatus: null,
    error: ''
  };

  client.on('Network.responseReceived', (params) => {
    const response = params && params.response;
    if (!response || !String(response.url || '').includes('/api/v0/chat/completion')) return;
    trackedRequestIds.add(params.requestId);
    state.responseStatus = Number(response.status || 0);
    if (state.responseStatus >= 400) {
      state.error = `DeepSeek completion HTTP ${state.responseStatus}`;
    }
  });
  client.on('Network.loadingFailed', (params) => {
    if (!params || !trackedRequestIds.has(params.requestId)) return;
    state.completed = true;
    state.error = `DeepSeek completion network failed: ${params.errorText || 'unknown_error'}`;
  });
  client.on('Network.loadingFinished', (params) => {
    if (!params || !trackedRequestIds.has(params.requestId)) return;
    client.send('Network.getResponseBody', { requestId: params.requestId })
      .then((result) => {
        const parsed = parseDeepSeekCompletionSse(result && result.body || '');
        state.answerText = parsed.answerText;
        state.completed = true;
        if (!state.answerText && !state.error) {
          state.error = 'DeepSeek completion stream finished without response content';
        }
      })
      .catch((error) => {
        state.completed = true;
        state.error = `DeepSeek completion body unavailable: ${error && error.message ? error.message : error}`;
      });
  });
  return state;
}

function isFatalCompletionCaptureError(error) {
  const message = String(error || '').toLowerCase();
  if (!message) return false;
  return message.includes('http 4')
    || message.includes('http 5')
    || message.includes('network failed');
}

function resolveNonJsonCompletionReason({
  hasTextSignal,
  hasLinkOnlySignal,
  stableCount,
  generationActive,
  networkAnswerComplete,
  firstContentAt,
  firstLinkOnlyAt,
  quickDeadline,
  now
}) {
  if (networkAnswerComplete && hasTextSignal) return 'complete_network';
  if (generationActive) return null;
  if (hasTextSignal && stableCount >= 2) return 'stable_signal';
  if (hasTextSignal && firstContentAt && now - firstContentAt >= 6000) return 'signal_budget_reached';
  if (hasTextSignal && now >= quickDeadline) return 'quick_budget_reached';
  if (hasLinkOnlySignal && firstLinkOnlyAt && now - firstLinkOnlyAt >= LINK_ONLY_RETURN_BUDGET_MS) {
    return 'link_only_budget_reached';
  }
  return null;
}

async function waitForAnswer(client, baseline, options = {}) {
  const deadline = Date.now() + WAIT_TIMEOUT_MS;
  const startedAt = Date.now();
  let stableCount = 0;
  let lastDigest = '';
  let firstContentAt = 0;
  let firstLinkOnlyAt = 0;
  let lastState = null;
  const quickDeadline = Date.now() + QUICK_RETURN_BUDGET_MS;
  const finalize = (state, completionReason) => ({
    ...(state || {}),
    waitDurationMs: Date.now() - startedAt,
    completionReason
  });
  while (Date.now() < deadline) {
    let state = diffSearchState(
      await captureSearchState(client, options.submittedTurnSignature || ''),
      baseline
    );
    const completionCapture = options.completionCapture;
    const networkAnswerComplete = !!(
      completionCapture
      && completionCapture.completed
      && completionCapture.answerText
      && completionCapture.answerText.trim()
    );
    if (networkAnswerComplete) {
      const networkAnswer = completionCapture.answerText.trim();
      state = {
        ...state,
        bodyText: networkAnswer,
        bodyDelta: networkAnswer,
        candidateTexts: [{
          text: networkAnswer,
          html: '',
          className: 'deepseek-completion-stream'
        }],
        assistantBlockCount: 1,
        generationActive: false,
        networkAnswerCaptured: true
      };
    }
    lastState = state;
    const digest = JSON.stringify({
      bodyTail: (state.bodyDelta || state.bodyText || '').slice(-1000),
      links: state.links,
      candidateTexts: state.candidateTexts
    });
    stableCount = digest === lastDigest ? stableCount + 1 : 0;
    lastDigest = digest;
    const hasCandidateText = Array.isArray(state.candidateTexts) && state.candidateTexts.some(item => item && item.text && item.text.trim().length >= 40);
    const hasEnoughLinks = Array.isArray(state.links) && state.links.length >= 2;
    const hasSearchCount = /搜索到\s*\d+\s*个网页/.test(state.bodyText || '');
    const hasBodyDelta = ((state.bodyDelta || '').trim().length >= 80);
    const hasTextSignal = hasCandidateText || hasBodyDelta || hasSearchCount || networkAnswerComplete;
    const answerCandidate = (state.candidateTexts || []).map(item => item && item.text || '').filter(Boolean).slice(-1)[0]
      || state.bodyDelta || state.bodyText || '';
    const jsonComplete = options.expectedJson === true && hasCompleteJson(answerCandidate);
    const hasLinkOnlySignal = !hasTextSignal && hasEnoughLinks;
    if (hasTextSignal && !firstContentAt) {
      firstContentAt = Date.now();
    }
    if (hasLinkOnlySignal && !firstLinkOnlyAt) {
      firstLinkOnlyAt = Date.now();
    }
    if (options.expectedJson === true) {
      if (completionCapture
        && completionCapture.completed
        && completionCapture.error
        && !completionCapture.answerText
        && isFatalCompletionCaptureError(completionCapture.error)) {
        throw new Error(completionCapture.error);
      }
      if (completionCapture && completionCapture.completed && jsonComplete && state.networkAnswerCaptured) {
        return finalize({ ...state, jsonComplete: true }, 'complete_json_network');
      }
      if (jsonComplete && !state.generationActive && stableCount >= 2) return finalize({ ...state, jsonComplete: true }, 'complete_json_stable');
      await sleepWithJitter(POLL_INTERVAL_MS, 800);
      continue;
    }
    const nonJsonCompletionReason = resolveNonJsonCompletionReason({
      hasTextSignal,
      hasLinkOnlySignal,
      stableCount,
      generationActive: !!state.generationActive,
      networkAnswerComplete,
      firstContentAt,
      firstLinkOnlyAt,
      quickDeadline,
      now: Date.now()
    });
    if (nonJsonCompletionReason) return finalize(state, nonJsonCompletionReason);
    await sleepWithJitter(POLL_INTERVAL_MS, 800);
  }
  if (lastState) return finalize({ ...lastState, jsonComplete: hasCompleteJson(
    (lastState.candidateTexts || []).map(item => item && item.text || '').filter(Boolean).slice(-1)[0]
      || lastState.bodyDelta || lastState.bodyText || ''
  ) }, options.expectedJson === true ? 'timeout_incomplete_json' : 'timeout_with_last_state');
  throw new Error('Timed out waiting for DeepSeek web answer');
}

function normalizeResult(raw) {
  const candidateTexts = (raw.candidateTexts || []).filter(item => item && item.text);
  const answerFromCandidate = (candidateTexts[candidateTexts.length - 1]?.text || '').trim()
    || candidateTexts.map(item => item.text).join('\n\n').trim();
  const answerFromDelta = (raw.bodyDelta || '').trim();
  const answerFromBody = (raw.bodyText || '').trim();
  const uniqueLinks = [];
  const seen = new Set();
  for (const item of raw.links || []) {
    if (!item.href || seen.has(item.href)) continue;
    seen.add(item.href);
    uniqueLinks.push(item);
  }
  let answerSource = 'empty';
  let text = '';
  if (answerFromCandidate) {
    answerSource = raw.networkAnswerCaptured ? 'completion_stream' : 'new_assistant_block';
    text = answerFromCandidate;
  } else if (answerFromDelta) {
    answerSource = 'body_delta';
    text = answerFromDelta;
  } else if (answerFromBody) {
    answerSource = 'page_fallback';
    text = answerFromBody;
  }
  let contentStatus = 'success';
  let contentNote = '已抓到提交后的新增回答块。';
  if (raw.networkAnswerCaptured) {
    contentNote = '网页消息区未稳定渲染，已从完成的 DeepSeek completion 流恢复回答。';
  }
  if (!text && uniqueLinks.length === 0) {
    contentStatus = 'empty';
    contentNote = '未抓到新增回答内容或引用链接。';
  } else if (!text && uniqueLinks.length > 0) {
    answerSource = 'link_only';
    contentStatus = 'link_only';
    contentNote = '已抓到新的引用链接，但还未稳定抓到新增回答正文。';
  } else if (answerSource === 'body_delta') {
    contentStatus = 'weak_signal';
    contentNote = '未明确抓到新增回答块，当前仅根据页面增量文本提取结果。';
  } else if (answerSource === 'page_fallback') {
    contentStatus = 'possible_history_pollution';
    contentNote = '未抓到新增回答块，已回退到整段页面文本，存在历史会话污染风险。';
  }
  if (raw.expectedJson && !raw.jsonComplete) {
    contentStatus = 'incomplete';
    contentNote = '结构化JSON尚未完整生成，当前内容不作为成功结果。';
  } else if (raw.completionReason === 'timeout_with_last_state' && raw.generationActive) {
    contentStatus = 'incomplete';
    contentNote = 'DeepSeek 网页仍在生成时已达到等待上限，当前正文可能截断，不作为成功结果。';
  }
  return {
    searchCount: raw.searchCount,
    answerText: text,
    links: uniqueLinks,
    providerTimingMs: raw.providerTimingMs || null,
    modeState: raw.modeState || null,
    isolatedSession: !!raw.isolatedSession,
    accountKey: raw.accountKey || null,
    debugPort: raw.debugPort || null,
    profileRoot: raw.profileRoot || null,
    targetId: raw.targetId || null,
    sessionKey: raw.sessionKey || null,
    finalUrl: raw.finalUrl || null,
    contentStatus,
    contentNote,
    answerSource,
    candidateBlockCount: candidateTexts.length,
    assistantBlockDeltaCount: raw.assistantBlockCount || 0,
    newLinkCount: uniqueLinks.length,
    bodyDeltaLength: answerFromDelta.length,
    waitDurationMs: raw.waitDurationMs || null,
    completionReason: raw.completionReason || null,
    answerLength: text.length,
    jsonComplete: !!raw.jsonComplete,
    networkAnswerCaptured: !!raw.networkAnswerCaptured,
    generationActive: !!raw.generationActive,
    submittedTurnMatched: !!raw.submittedTurnMatched,
    allAssistantBlockCount: Number(raw.allAssistantBlockCount || 0),
    targetReuseMode: raw.targetReuseMode || null,
    conversationRestored: !!raw.conversationRestored
  };
}

async function searchQuery(query, options = {}) {
  if (!query || !query.trim()) {
    throw new Error('Query is required');
  }
  const worker = resolveWorkerConfig(options);
  const bridgeMode = normalizeBridgeMode(options.mode || options.webMode || DEFAULT_BRIDGE_MODE);
  await ensureDebuggerAvailable(worker);
  const target = await openPageTarget({ ...options, accountKey: worker.accountKey });
  const client = new CdpClient(target.webSocketDebuggerUrl);
  let conversationSurface = null;
  try {
    await client.open();
    await client.send('Page.enable');
    await client.send('Runtime.enable');
    await client.send('Network.enable');
    const completionCapture = createDeepSeekCompletionCapture(client);
    if (target.resumeUrl) {
      conversationSurface = await restoreConversationSurface(client, target.resumeUrl);
    } else {
      await resetConversationSurface(client);
      const fresh = await tryStartFreshConversation(client);
      conversationSurface = { restored: false, fresh };
    }
    await waitForComposer(client);
    const toggleUpdates = await ensureToggleModes(client, bridgeMode);
    const modeState = buildModeState(toggleUpdates, bridgeMode);
    if (bridgeMode === 'expert' && !modeState.modeVerified) {
      throw new Error(`DeepSeek expert mode was not verified before submit: ${JSON.stringify(modeState)}`);
    }
    const baseline = await captureSearchState(client);
    const searchStartedAt = Date.now();
    await sleepWithJitter(PRE_SUBMIT_DELAY_MS);
    if (typeof options.onSubmitting === 'function') {
      await options.onSubmitting({
        targetId: target.targetId || null,
        sessionKey: options.sessionKey || target.sessionKey || null,
        turnSignature: buildTurnSignature(query.trim())
      });
    }
    const submit = await submitQuery(client, query.trim());
    if (!submit || !submit.ok) {
      throw new Error(`Failed to submit query: ${JSON.stringify(submit)}`);
    }
    if (typeof options.onSubmitted === 'function') {
      await options.onSubmitted({
        targetId: target.targetId || null,
        sessionKey: options.sessionKey || target.sessionKey || null,
        turnSignature: buildTurnSignature(query.trim())
      });
    }
    await sleepWithJitter(POST_SUBMIT_SETTLE_MS, 600);
    const raw = await waitForAnswer(client, baseline, {
      ...options,
      submittedTurnSignature: buildTurnSignature(query.trim()),
      completionCapture
    });
    const finalUrl = await client.eval('location.href').catch(() => '');
    if (options.sessionKey) {
      updateSessionStore(options.sessionKey, {
        targetId: target.targetId || null,
        url: finalUrl || null,
        bridgeMode: modeState.bridgeMode,
        modeVerified: modeState.modeVerified,
        headerMode: modeState.headerMode,
        expertMode: modeState.expertMode,
        quickMode: modeState.quickMode,
        lastAnswerAt: new Date().toISOString()
      }, worker);
    }
    return normalizeResult({
      ...raw,
      providerTimingMs: Date.now() - searchStartedAt,
      modeState,
      isolatedSession: !!options.isolatedSession,
      accountKey: worker.accountKey,
      debugPort: worker.debugPort,
      profileRoot: worker.profileRoot,
      targetId: target.targetId || null,
      sessionKey: options.sessionKey || target.sessionKey || null,
      finalUrl: finalUrl || null,
      expectedJson: options.expectedJson === true,
      targetReuseMode: 'single_worker',
      conversationRestored: !!(conversationSurface && conversationSurface.restored)
    });
  } finally {
    client.close();
    if (options.closeTargetAfter && target && target.targetId && !target.sharedTarget) {
      await closeTarget(target.targetId, worker);
    }
  }
}

module.exports = {
  searchQuery,
  prepareSharedTarget,
  getWorkerStates,
  DEBUG_PORT,
  WAIT_TIMEOUT_MS,
  CHROME_LOG_PATH: DEFAULT_CHROME_LOG_PATH,
  _internals: {
    accountEnvSuffix,
    extractUserDataDirFromCommandLine,
    getWorkerStates,
    isReusableConversationUrl,
    normalizeAccountKey,
    parseProfilePathFromChromeVersionText,
    resolveWorkerConfig,
    selectSharedTarget,
    bindSessionStoreToSharedTarget,
    buildTurnSignature,
    diffSearchState,
    hasCompleteJson,
    parseDeepSeekCompletionSse,
    resolveNonJsonCompletionReason,
    normalizeResult,
    normalizeBridgeMode,
    buildModeState,
    isFatalCompletionCaptureError
  }
};
