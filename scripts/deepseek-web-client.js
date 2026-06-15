const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME_BIN = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const DEBUG_PORT = Number(process.env.DEEPSEEK_WEB_DEBUG_PORT || 9333);
const PROFILE_ROOT = process.env.DEEPSEEK_WEB_PROFILE_ROOT
  || path.join(os.tmpdir(), 'deepseek-web-profile');
const CHROME_LOG_PATH = process.env.DEEPSEEK_WEB_CHROME_LOG
  || path.join(os.tmpdir(), 'deepseek-web-chrome.log');
const SESSION_STORE_PATH = process.env.DEEPSEEK_WEB_SESSION_STORE
  || path.join(os.homedir(), '.aimeeting-room', 'deepseek-web-sessions.json');
const SOURCE_CHROME_DIR = path.join(os.homedir(), 'Library/Application Support/Google/Chrome');
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

function normalizeSessionKey(sessionKey) {
  return String(sessionKey || '')
    .trim()
    .replace(/[^a-zA-Z0-9_.:-]/g, '_')
    .slice(0, 120);
}

function loadSessionStore() {
  try {
    if (!fs.existsSync(SESSION_STORE_PATH)) {
      return {};
    }
    return JSON.parse(fs.readFileSync(SESSION_STORE_PATH, 'utf8')) || {};
  } catch (_) {
    return {};
  }
}

function saveSessionStore(store) {
  try {
    fs.mkdirSync(path.dirname(SESSION_STORE_PATH), { recursive: true });
    fs.writeFileSync(SESSION_STORE_PATH, JSON.stringify(store || {}, null, 2));
  } catch (_) {}
}

function updateSessionStore(sessionKey, patch) {
  const normalized = normalizeSessionKey(sessionKey);
  if (!normalized) return;
  const store = loadSessionStore();
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
  saveSessionStore(store);
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

function ensureAutomationProfile() {
  if (!fs.existsSync(path.join(PROFILE_ROOT, 'Default'))) {
    fs.mkdirSync(PROFILE_ROOT, { recursive: true });
    for (const item of ['Local State', 'Default']) {
      const src = path.join(SOURCE_CHROME_DIR, item);
      const dest = path.join(PROFILE_ROOT, item);
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
  cleanupProfileLocks();
}

function cleanupProfileLocks() {
  for (const entry of [
    'SingletonCookie',
    'SingletonLock',
    'SingletonSocket',
    path.join('Default', 'LOCK'),
    path.join('Default', 'Cookies-journal')
  ]) {
    const target = path.join(PROFILE_ROOT, entry);
    try {
      if (fs.existsSync(target)) {
        fs.rmSync(target, { force: true, recursive: true });
      }
    } catch (_) {}
  }
}

function readChromeLogTail() {
  try {
    const chromeLog = fs.readFileSync(CHROME_LOG_PATH, 'utf8');
    return chromeLog ? chromeLog.slice(-2000) : '(no chrome log captured)';
  } catch (_) {
    return '(no chrome log captured)';
  }
}

async function getExistingDebuggerVersion() {
  return tryFetchJson(`http://127.0.0.1:${DEBUG_PORT}/json/version`);
}

async function listTargets() {
  return fetchJson(`http://127.0.0.1:${DEBUG_PORT}/json/list`);
}

async function closeTarget(targetId) {
  if (!targetId) return;
  try {
    await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/close/${targetId}`, { method: 'PUT' });
  } catch (_) {}
}

async function waitForDebugger() {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    try {
      return await fetchJson(`http://127.0.0.1:${DEBUG_PORT}/json/version`);
    } catch (_) {
      const crashpadError = readChromeLogTail();
      if (crashpadError.includes('crashpad') && crashpadError.toLowerCase().includes('permission denied')) {
        throw new Error(`Chrome remote debugging blocked by crashpad permission\n${crashpadError}`);
      }
      await sleep(500);
    }
  }
  throw new Error(`Chrome remote debugging endpoint did not become ready\n${readChromeLogTail()}`);
}

function launchChrome() {
  const chromeLogFd = fs.openSync(CHROME_LOG_PATH, 'a');
  return spawn(CHROME_BIN, [
    `--remote-debugging-port=${DEBUG_PORT}`,
    '--remote-debugging-address=127.0.0.1',
    `--user-data-dir=${PROFILE_ROOT}`,
    '--profile-directory=Default',
    '--no-first-run',
    '--no-default-browser-check',
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
    this.ws.addEventListener('message', (event) => {
      const data = JSON.parse(event.data.toString());
      if (data.id && this.pending.has(data.id)) {
        const { resolve, reject } = this.pending.get(data.id);
        this.pending.delete(data.id);
        if (data.error) reject(new Error(JSON.stringify(data.error)));
        else resolve(data.result);
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

  close() {
    try {
      this.ws.close();
    } catch (_) {}
  }
}

async function openPageTarget(options = {}) {
  const targets = await listTargets();
  const matches = targets.filter((item) =>
    item
    && item.type === 'page'
    && item.webSocketDebuggerUrl
    && (item.url || '').includes('chat.deepseek.com')
  );
  const sessionKey = normalizeSessionKey(options.sessionKey);
  if (sessionKey && !options.forceNewSession) {
    const store = loadSessionStore();
    const saved = store[sessionKey];
    const savedTarget = saved && saved.targetId
      ? matches.find(item => item.id === saved.targetId)
      : null;
    if (savedTarget) {
      return {
        targetId: savedTarget.id,
        webSocketDebuggerUrl: savedTarget.webSocketDebuggerUrl,
        reused: true,
        isolatedSession: false,
        sessionKey
      };
    }
  }
  if (!sessionKey && !options.isolatedSession && matches.length > 0) {
    return { targetId: matches[0].id, webSocketDebuggerUrl: matches[0].webSocketDebuggerUrl, reused: true, isolatedSession: false };
  }
  const target = await fetchJson(`http://127.0.0.1:${DEBUG_PORT}/json/new?https://chat.deepseek.com/`, { method: 'PUT' });
  if (sessionKey) {
    updateSessionStore(sessionKey, {
      targetId: target.id,
      url: target.url || 'https://chat.deepseek.com/',
      forceNewSession: !!options.forceNewSession
    });
  }
  return {
    targetId: target.id,
    webSocketDebuggerUrl: target.webSocketDebuggerUrl,
    reused: false,
    isolatedSession: !!options.isolatedSession,
    sessionKey
  };
}

async function resetConversationSurface(client) {
  try {
    await client.send('Page.navigate', { url: 'https://chat.deepseek.com/' });
  } catch (_) {}
  await sleepWithJitter(PAGE_RESET_SETTLE_MS);
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

// 默认 fast：经实测，DeepSeek 网页的 expert 模式不会真正触发联网搜索（newLinkCount 持续为 0），
// 只有 fast（默认对话模式）才能拿到真实搜索结果与可点击 URL。
// 如需 expert（仅做综合推理、不需要联网），显式设置 DEEPSEEK_WEB_MODE=expert。
const BRIDGE_MODE = (process.env.DEEPSEEK_WEB_MODE || 'fast').toLowerCase() === 'expert' ? 'expert' : 'fast';

function buildToggleJs(mode) {
  const wantExpert = mode === 'expert';
  return `
    (() => {
      const updates = [];
      const expert = document.querySelector('[role="radio"][data-model-type="expert"]');
      const quick = document.querySelector('[role="radio"][data-model-type="default"]');
      const wantExpert = ${wantExpert ? 'true' : 'false'};
      if (wantExpert) {
        if (expert) {
          const selected = String(expert.getAttribute('aria-checked') || '').toLowerCase() === 'true';
          if (!selected) {
            expert.click();
          }
          updates.push({ label: '专家模式', found: true, selectedBefore: selected, selectedAfter: String(expert.getAttribute('aria-checked') || '').toLowerCase() === 'true' });
        } else {
          updates.push({ label: '专家模式', found: false });
        }
        if (quick) {
          updates.push({ label: '快速模式', found: true, selected: String(quick.getAttribute('aria-checked') || '').toLowerCase() === 'true' });
        }
      } else {
        if (quick) {
          const selected = String(quick.getAttribute('aria-checked') || '').toLowerCase() === 'true';
          if (!selected) {
            quick.click();
          }
          updates.push({ label: '快速模式', found: true, selectedBefore: selected, selectedAfter: String(quick.getAttribute('aria-checked') || '').toLowerCase() === 'true' });
        } else {
          updates.push({ label: '快速模式', found: false });
        }
        if (expert) {
          updates.push({ label: '专家模式', found: true, selected: String(expert.getAttribute('aria-checked') || '').toLowerCase() === 'true' });
        }
      }

      // Only manipulate deep think / search toggles in expert mode.
      if (wantExpert) {
        const targets = [
          { label: '深度思考', aliases: ['深度思考'] },
          { label: '智能搜索', aliases: ['智能搜索', '联网搜索'] }
        ];
        const buttons = Array.from(document.querySelectorAll('button,[role="button"]'));
        for (const target of targets) {
          const button = buttons.find((el) => {
            const text = (el.innerText || el.getAttribute('aria-label') || '').trim();
            return text && target.aliases.some(alias => text.includes(alias));
          });
          if (!button) {
            updates.push({ label: target.label, found: false, pressedAfter: 'false' });
            continue;
          }
          const pressed = (button.getAttribute('aria-pressed') || '').toLowerCase() === 'true'
            || (button.className || '').includes('ds-toggle-button--selected');
          if (!pressed) {
            button.click();
          }
          const pressedAfter = (button.getAttribute('aria-pressed') || '').toLowerCase() === 'true'
            || (button.className || '').includes('ds-toggle-button--selected');
          updates.push({ label: target.label, found: true, changed: !pressed, pressedAfter: String(pressedAfter) });
        }
      }
      return updates;
    })()
  `;
}

async function ensureToggleModes(client) {
  const js = buildToggleJs(BRIDGE_MODE);
  let lastResult = [];
  for (let i = 0; i < 12; i++) {
    const result = await client.eval(js);
    lastResult = Array.isArray(result) ? result : [];
    if (BRIDGE_MODE === 'fast') {
      const quick = lastResult.find(item => item.label === '快速模式');
      if (quick && quick.found && quick.selectedAfter) {
        break;
      }
    } else {
      const expert = lastResult.find(item => item.label === '专家模式');
      const deep = lastResult.find(item => item.label === '深度思考');
      const search = lastResult.find(item => item.label === '智能搜索');
      const expertReady = expert && expert.found && expert.selectedAfter;
      const deepReady = deep && deep.found && String(deep.pressedAfter).toLowerCase() === 'true';
      const searchReady = search && search.found && String(search.pressedAfter).toLowerCase() === 'true';
      if (expertReady && deepReady && searchReady) {
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

async function captureSearchState(client) {
  return client.eval(`
    (() => {
      const assistantBlocks = Array.from(document.querySelectorAll('.ds-assistant-message-main-content, .ds-markdown.ds-assistant-message-main-content'))
        .map(node => ({
          text: (node.innerText || '').trim(),
          html: (node.outerHTML || '').slice(0, 300),
          className: node.className || ''
        }))
        .filter(item => item.text);
      const lastAssistantBlock = Array.from(document.querySelectorAll('.ds-assistant-message-main-content, .ds-markdown.ds-assistant-message-main-content')).slice(-1)[0] || null;
      const lastMessageContainer = lastAssistantBlock
        ? lastAssistantBlock.closest('[class*="assistant"], [class*="message"]') || lastAssistantBlock.parentElement || lastAssistantBlock
        : null;
      const bodyText = lastMessageContainer
        ? (lastMessageContainer.innerText || '').trim()
        : '';
      const anchors = Array.from((lastAssistantBlock || document).querySelectorAll('a[href^="http"]'))
        .map(a => ({ text: (a.innerText || '').trim(), href: a.href }))
        .filter(item => item.href && !item.href.startsWith('https://chat.deepseek.com'));
      const searchCountMatch = bodyText.match(/搜索到\\s*(\\d+)\\s*个网页/);
      return {
        bodyText,
        searchCount: searchCountMatch ? Number(searchCountMatch[1]) : null,
        links: anchors.slice(-30),
        candidateTexts: assistantBlocks,
        assistantBlockCount: assistantBlocks.length
      };
    })()
  `);
}

function diffSearchState(state, baseline = null) {
  if (!baseline) return state;
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
    assistantBlockCount: Math.max(0, Number(state.assistantBlockCount || 0) - Number(baseline.assistantBlockCount || 0))
  };
}

function buildModeState(toggleUpdates) {
  const updates = Array.isArray(toggleUpdates) ? toggleUpdates : [];
  const find = (label) => updates.find(item => item && item.label === label);
  const expert = find('专家模式');
  const quick = find('快速模式');
  const deep = find('深度思考');
  const search = find('智能搜索');
  const isPressed = (item) => !!(item && item.found && String(item.pressedAfter).toLowerCase() === 'true');
  return {
    bridgeMode: BRIDGE_MODE,
    expertMode: !!(expert && expert.found && (expert.selectedAfter === true || expert.selected === true)),
    quickMode: !!(quick && quick.found && (quick.selectedAfter === true || quick.selected === true)),
    deepThinkEnabled: isPressed(deep),
    searchEnabled: isPressed(search)
  };
}

async function waitForAnswer(client, baseline) {
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
    const state = diffSearchState(await captureSearchState(client), baseline);
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
    const hasTextSignal = hasCandidateText || hasBodyDelta || hasSearchCount;
    const hasLinkOnlySignal = !hasTextSignal && hasEnoughLinks;
    if (hasTextSignal && !firstContentAt) {
      firstContentAt = Date.now();
    }
    if (hasLinkOnlySignal && !firstLinkOnlyAt) {
      firstLinkOnlyAt = Date.now();
    }
    if (hasTextSignal && stableCount >= 1) return finalize(state, 'stable_signal');
    if (hasTextSignal && firstContentAt && Date.now() - firstContentAt >= 6000) return finalize(state, 'signal_budget_reached');
    if (Date.now() >= quickDeadline && lastState && hasTextSignal) return finalize(lastState, 'quick_budget_reached');
    if (firstLinkOnlyAt && Date.now() - firstLinkOnlyAt >= LINK_ONLY_RETURN_BUDGET_MS) return finalize(lastState, 'link_only_budget_reached');
    await sleepWithJitter(POLL_INTERVAL_MS, 800);
  }
  if (lastState) return finalize(lastState, 'timeout_with_last_state');
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
    answerSource = 'new_assistant_block';
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
  return {
    searchCount: raw.searchCount,
    answerText: text,
    links: uniqueLinks,
    providerTimingMs: raw.providerTimingMs || null,
    modeState: raw.modeState || null,
    isolatedSession: !!raw.isolatedSession,
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
    answerLength: text.length
  };
}

async function searchQuery(query, options = {}) {
  if (!query || !query.trim()) {
    throw new Error('Query is required');
  }
  let debuggerVersion = await getExistingDebuggerVersion();
  if (!debuggerVersion) {
    ensureAutomationProfile();
    const chrome = launchChrome();
    chrome.unref();
    debuggerVersion = await waitForDebugger();
  }
  const target = await openPageTarget(options);
  const client = new CdpClient(target.webSocketDebuggerUrl);
  try {
    await client.open();
    await client.send('Page.enable');
    await client.send('Runtime.enable');
    if (!target.reused || options.isolatedSession || options.forceNewSession) {
      await resetConversationSurface(client);
      if (options.isolatedSession || options.sessionKey || options.forceNewSession) {
        await tryStartFreshConversation(client);
      }
    }
    await waitForComposer(client);
    const toggleUpdates = await ensureToggleModes(client);
    const baseline = await captureSearchState(client);
    const searchStartedAt = Date.now();
    await sleepWithJitter(PRE_SUBMIT_DELAY_MS);
    const submit = await submitQuery(client, query.trim());
    if (!submit || !submit.ok) {
      throw new Error(`Failed to submit query: ${JSON.stringify(submit)}`);
    }
    await sleepWithJitter(POST_SUBMIT_SETTLE_MS, 600);
    const raw = await waitForAnswer(client, baseline);
    const finalUrl = await client.eval('location.href').catch(() => '');
    if (options.sessionKey) {
      updateSessionStore(options.sessionKey, {
        targetId: target.targetId || null,
        url: finalUrl || null,
        lastAnswerAt: new Date().toISOString()
      });
    }
    return normalizeResult({
      ...raw,
      providerTimingMs: Date.now() - searchStartedAt,
      modeState: buildModeState(toggleUpdates),
      isolatedSession: !!options.isolatedSession,
      targetId: target.targetId || null,
      sessionKey: options.sessionKey || target.sessionKey || null,
      finalUrl: finalUrl || null
    });
  } finally {
    client.close();
    if (options.closeTargetAfter && target && target.targetId) {
      await closeTarget(target.targetId);
    }
  }
}

module.exports = {
  searchQuery,
  DEBUG_PORT,
  WAIT_TIMEOUT_MS,
  CHROME_LOG_PATH
};
