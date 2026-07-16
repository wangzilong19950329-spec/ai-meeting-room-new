#!/usr/bin/env node
'use strict';

const { execFileSync } = require('child_process');
const path = require('path');

const ROOT_DIR = path.resolve(__dirname, '..');
const BASE_URL = (process.env.DEEPSEEK_OPENAI_BASE_URL || 'http://127.0.0.1:8789/v1').replace(/\/$/, '');
const CONTAINER_BASE_URL = (process.env.DEEPSEEK_OPENAI_CONTAINER_BASE_URL || 'http://host.docker.internal:8789/v1').replace(/\/$/, '');
const API_KEY = process.env.DEEPSEEK_OPENAI_API_KEY || process.env.DEEPSEEK_OPENAI_FACADE_API_KEY || 'local-dev';
const DEBUG_PORT = Number(process.env.DEEPSEEK_WEB_DEBUG_PORT || 9333);
const N8N_CONTAINER = process.env.N8N_CONTAINER || 'ai-meeting-room-n8n-local';
const LANGFLOW_CONTAINER = process.env.LANGFLOW_CONTAINER || 'ai-meeting-room-langflow-local';

const rawArgs = process.argv.slice(2);
const args = new Set(rawArgs);
const ALL_AFTER_LOGIN = args.has('--all-after-login');
const RUN_ADAPTER_CHAT = ALL_AFTER_LOGIN || args.has('--chat') || args.has('--json-chat');
const RUN_WORKFLOWS = ALL_AFTER_LOGIN || args.has('--workflows');
const REFRESH_N8N = ALL_AFTER_LOGIN || args.has('--refresh-n8n');
const SKIP_DOCKER = args.has('--skip-docker');
const WAIT_LOGIN = ALL_AFTER_LOGIN || args.has('--wait-login');
const FOCUS_LOGIN = args.has('--focus-login') || WAIT_LOGIN;

function optionNumber(name, fallback) {
  const prefix = `${name}=`;
  const found = rawArgs.find((arg) => arg.startsWith(prefix));
  const value = found ? Number(found.slice(prefix.length)) : Number(process.env[name.replace(/^--/, '').replace(/-/g, '_').toUpperCase()] || fallback);
  return Number.isFinite(value) && value >= 0 ? value : fallback;
}

const WAIT_LOGIN_TIMEOUT_MS = optionNumber('--wait-login-timeout-ms', 180000);
const WAIT_LOGIN_INTERVAL_MS = optionNumber('--wait-login-interval-ms', 3000);
const N8N_OPENAI_TIMEOUT_MS = String(optionNumber('--n8n-openai-timeout-ms', 480000));
const N8N_EXECUTION_TIMEOUT_MS = String(optionNumber('--n8n-execution-timeout-ms', 600000));
const N8N_EXECUTION_POLL_INTERVAL_MS = String(optionNumber('--n8n-execution-poll-interval-ms', 2000));

function run(cmd, cmdArgs, options = {}) {
  return execFileSync(cmd, cmdArgs, {
    cwd: ROOT_DIR,
    encoding: 'utf8',
    stdio: options.stdio || ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      ...(options.env || {})
    }
  });
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers || {})
    }
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch (_) {
    json = { raw: text };
  }
  return { status: response.status, json, text };
}

async function checkModels(baseUrl) {
  const response = await requestJson(`${baseUrl}/models`, { method: 'GET' });
  const models = response.json && Array.isArray(response.json.data)
    ? response.json.data.map((model) => model.id)
    : [];
  return {
    ok: response.status === 200 && models.includes('deepseek-web-bridge'),
    status: response.status,
    models
  };
}

async function checkLoginState() {
  let targets;
  try {
    const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`);
    targets = await response.json();
  } catch (error) {
    return {
      ok: false,
      status: 'debug_port_unavailable',
      debugPort: DEBUG_PORT,
      message: error && error.message ? error.message : String(error)
    };
  }

  const deepseekTargets = Array.isArray(targets)
    ? targets.filter((target) => {
      const haystack = `${target.title || ''} ${target.url || ''}`.toLowerCase();
      return target.type === 'page' && haystack.includes('chat.deepseek.com');
    })
    : [];

  if (!deepseekTargets.length) {
    return {
      ok: false,
      status: 'missing_deepseek_target',
      debugPort: DEBUG_PORT,
      message: 'No chat.deepseek.com page target found'
    };
  }

  const target = deepseekTargets[0];
  const url = target.url || '';
  const loginRequired = url.includes('/sign_in');
  return {
    ok: !loginRequired,
    status: loginRequired ? 'login_required' : 'maybe_logged_in',
    debugPort: DEBUG_PORT,
    targetId: target.id,
    title: target.title || '',
    url
  };
}

async function focusDeepSeekTarget(loginState) {
  if (!loginState || !loginState.targetId) {
    return {
      ok: false,
      skipped: true,
      reason: 'no_deepseek_target_id'
    };
  }
  try {
    const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/activate/${encodeURIComponent(loginState.targetId)}`);
    const text = await response.text();
    return {
      ok: response.ok,
      status: response.status,
      targetId: loginState.targetId,
      text: text.slice(0, 200)
    };
  } catch (error) {
    return {
      ok: false,
      targetId: loginState.targetId,
      error: error && error.message ? error.message : String(error)
    };
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForLogin(initialLogin) {
  const startedAt = Date.now();
  let current = initialLogin;
  let attempts = 0;

  if (!WAIT_LOGIN) {
    return {
      enabled: false,
      attempts,
      waitedMs: 0,
      login: current
    };
  }

  while (!current.ok && Date.now() - startedAt < WAIT_LOGIN_TIMEOUT_MS) {
    attempts += 1;
    await sleep(WAIT_LOGIN_INTERVAL_MS);
    current = await checkLoginState();
  }

  return {
    enabled: true,
    attempts,
    waitedMs: Date.now() - startedAt,
    timeoutMs: WAIT_LOGIN_TIMEOUT_MS,
    intervalMs: WAIT_LOGIN_INTERVAL_MS,
    login: current
  };
}

function dockerReachability() {
  if (SKIP_DOCKER) {
    return {
      skipped: true,
      reason: '--skip-docker'
    };
  }

  const checks = {};
  try {
    const output = run('docker', [
      'exec',
      '-e',
      `CHECK_URL=${CONTAINER_BASE_URL}/models`,
      '-e',
      `CHECK_KEY=${API_KEY}`,
      N8N_CONTAINER,
      'node',
      '-e',
      'fetch(process.env.CHECK_URL,{headers:{Authorization:`Bearer ${process.env.CHECK_KEY}`}}).then(async r=>{console.log(JSON.stringify({status:r.status,text:(await r.text()).slice(0,300)}));}).catch(e=>{console.log(JSON.stringify({error:e.message})); process.exit(2);})'
    ]).trim();
    const parsed = JSON.parse(output);
    checks.n8n = {
      ok: parsed.status === 200 && parsed.text.includes('deepseek-web-bridge'),
      ...parsed
    };
  } catch (error) {
    checks.n8n = {
      ok: false,
      error: error && error.message ? error.message : String(error)
    };
  }

  try {
    const output = run('docker', [
      'exec',
      '-e',
      `CHECK_URL=${CONTAINER_BASE_URL}/models`,
      '-e',
      `CHECK_KEY=${API_KEY}`,
      LANGFLOW_CONTAINER,
      '/app/.venv/bin/python',
      '-c',
      'import json, os, urllib.request; req=urllib.request.Request(os.environ["CHECK_URL"], headers={"Authorization":"Bearer "+os.environ["CHECK_KEY"]}); r=urllib.request.urlopen(req, timeout=10); text=r.read(300).decode(); print(json.dumps({"status":r.status,"text":text}))'
    ]).trim();
    const parsed = JSON.parse(output);
    checks.langflow = {
      ok: parsed.status === 200 && parsed.text.includes('deepseek-web-bridge'),
      ...parsed
    };
  } catch (error) {
    checks.langflow = {
      ok: false,
      error: error && error.message ? error.message : String(error)
    };
  }

  return checks;
}

function runAdapterChat() {
  const smokeArgs = ['scripts/deepseek-openai-adapter-smoke.js', '--chat'];
  if (args.has('--json-chat')) {
    smokeArgs.push('--json-chat');
  }
  const output = run(process.execPath, smokeArgs, {
    env: {
      DEEPSEEK_OPENAI_BASE_URL: BASE_URL,
      DEEPSEEK_OPENAI_API_KEY: API_KEY,
      DEEPSEEK_WEB_DEBUG_PORT: String(DEBUG_PORT)
    }
  });
  return JSON.parse(output);
}

function runWorkflows() {
  if (!REFRESH_N8N) {
    throw new Error('Refusing to mutate the saved n8n smoke fixture without --refresh-n8n. Re-run with --workflows --refresh-n8n after DeepSeek login to switch the fixture to the real adapter.');
  }

  const n8nOutput = run(process.execPath, ['scripts/n8n-saved-workflow-runtime-smoke.js'], {
    env: {
      N8N_OPENAI_BASE_URL: CONTAINER_BASE_URL,
      N8N_OPENAI_API_KEY: API_KEY,
      N8N_SMOKE_FORCE_IMPORT: '1',
      N8N_OPENAI_TIMEOUT_MS,
      N8N_EXECUTION_TIMEOUT_MS,
      N8N_EXECUTION_POLL_INTERVAL_MS
    }
  });
  const langflowOutput = run('scripts/langflow-saved-flow-runtime-smoke.py', [], {
    env: {
      LANGFLOW_OPENAI_BASE_URL: CONTAINER_BASE_URL,
      LANGFLOW_OPENAI_API_KEY: API_KEY
    }
  });

  return {
    n8n: JSON.parse(n8nOutput),
    langflow: JSON.parse(langflowOutput)
  };
}

async function main() {
  const initialLogin = await checkLoginState();
  const loginFocus = FOCUS_LOGIN ? await focusDeepSeekTarget(initialLogin) : {
    skipped: true,
    reason: 'pass --focus-login or --wait-login to activate the DeepSeek target'
  };
  const loginWait = await waitForLogin(initialLogin);
  const summary = {
    ok: false,
    baseUrl: BASE_URL,
    containerBaseUrl: CONTAINER_BASE_URL,
    checks: {
      models: await checkModels(BASE_URL),
      login: loginWait.login,
      loginFocus,
      loginWait,
      dockerReachability: dockerReachability()
    },
    adapterChat: 'skipped; pass --chat or --json-chat after login',
    workflows: 'skipped; pass --workflows --refresh-n8n after login, or --all-after-login for the full gated path',
    nextAction: ''
  };

  if (!summary.checks.models.ok) {
    summary.nextAction = `Start the real bridge, then verify ${BASE_URL}/models.`;
    console.log(JSON.stringify(summary, null, 2));
    process.exit(1);
  }

  if (!summary.checks.login.ok) {
    summary.nextAction = summary.checks.login.status === 'login_required'
      ? `Open the Chrome profile on debug port ${DEBUG_PORT}, finish DeepSeek login on ${summary.checks.login.url}, then rerun this script. You can also run with --all-after-login to activate the tab, wait while you log in, verify real text/JSON, then refresh and run saved workflows.`
      : `Open https://chat.deepseek.com in the Chrome profile on debug port ${DEBUG_PORT}, finish login, then rerun this script.`;
    console.log(JSON.stringify(summary, null, 2));
    process.exit(2);
  }

  if (!SKIP_DOCKER) {
    const dockerChecks = summary.checks.dockerReachability || {};
    if (!dockerChecks.n8n || !dockerChecks.n8n.ok || !dockerChecks.langflow || !dockerChecks.langflow.ok) {
      summary.nextAction = `Make ${CONTAINER_BASE_URL} reachable from the n8n/Langflow containers, then rerun this script.`;
      console.log(JSON.stringify(summary, null, 2));
      process.exit(3);
    }
  }

  if (RUN_ADAPTER_CHAT) {
    summary.adapterChat = runAdapterChat();
  }

  if (RUN_WORKFLOWS) {
    summary.workflows = runWorkflows();
  }

  summary.ok = true;
  summary.nextAction = RUN_WORKFLOWS
    ? 'Real adapter and saved workflow/flow smokes passed.'
    : 'Real adapter is reachable and DeepSeek appears logged in. Run with --chat --json-chat for adapter generation, then --workflows --refresh-n8n for saved n8n/Langflow runtime.';
  console.log(JSON.stringify(summary, null, 2));
}

main().catch((error) => {
  console.error(JSON.stringify({
    ok: false,
    error: error && error.message ? error.message : String(error),
    stack: error && error.stack ? error.stack : undefined
  }, null, 2));
  process.exit(1);
});
