#!/usr/bin/env node
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const crypto = require('crypto');

const CONTAINER = process.env.N8N_CONTAINER || 'ai-meeting-room-n8n-local';
const BASE_URL = process.env.N8N_OPENAI_BASE_URL || 'http://host.docker.internal:18789/v1';
const API_KEY = process.env.N8N_OPENAI_API_KEY || process.env.DEEPSEEK_OPENAI_FACADE_API_KEY || 'local-dev';
const MODEL = process.env.N8N_OPENAI_MODEL || 'deepseek-web-bridge';
const CREDENTIAL_ID = process.env.N8N_SMOKE_CREDENTIAL_ID || 'DswbOpenAiCred1';
const WORKFLOW_ID = process.env.N8N_SMOKE_WORKFLOW_ID || 'DswbN8nSmoke001';
const CREDENTIAL_NAME = 'DeepSeek OpenAI Adapter Local Smoke';
const WORKFLOW_NAME = 'DeepSeek OpenAI Adapter Saved Workflow Smoke';
const N8N_BASE_URL = process.env.N8N_BASE_URL || 'http://127.0.0.1:5678';
const N8N_OWNER_EMAIL = process.env.N8N_OWNER_EMAIL || 'local-dev@example.com';
const N8N_OWNER_PASSWORD = process.env.N8N_OWNER_PASSWORD || 'Local-dev-password-123';
const OPENAI_TIMEOUT_MS = positiveInteger(process.env.N8N_OPENAI_TIMEOUT_MS, 480000);
const OPENAI_MAX_RETRIES = nonNegativeInteger(process.env.N8N_OPENAI_MAX_RETRIES, 0);
const EXECUTION_TIMEOUT_MS = positiveInteger(
  process.env.N8N_EXECUTION_TIMEOUT_MS,
  Math.max(OPENAI_TIMEOUT_MS + 120000, 600000)
);
const EXECUTION_POLL_INTERVAL_MS = positiveInteger(process.env.N8N_EXECUTION_POLL_INTERVAL_MS, 2000);
const MOCK_SOURCE = 'deepseek-openai-adapter-mock';
const EXPECTED_SOURCE = 'n8n-saved-workflow-smoke';

function positiveInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : fallback;
}

function nonNegativeInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : fallback;
}

function run(cmd, args, options = {}) {
  return execFileSync(cmd, args, {
    encoding: 'utf8',
    stdio: options.stdio || ['ignore', 'pipe', 'pipe'],
    ...options
  });
}

function dockerExec(args, options = {}) {
  return run('docker', ['exec', CONTAINER, ...args], options);
}

function dockerExecRoot(args, options = {}) {
  return run('docker', ['exec', '-u', '0', CONTAINER, ...args], options);
}

function writeJson(filePath, value) {
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`);
}

async function request(pathname, { method = 'GET', cookie = '', body } = {}) {
  const response = await fetch(`${N8N_BASE_URL}${pathname}`, {
    method,
    headers: {
      ...(cookie ? { Cookie: cookie } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      'push-ref': 'codex-smoke'
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch (_) {
    // Keep the raw text for the caller's error message.
  }
  return { ok: response.ok, status: response.status, headers: response.headers, text, json };
}

function authCookieFrom(response) {
  const setCookie = response.headers.get('set-cookie') || '';
  const match = setCookie.match(/(?:^|,\s*)(n8n-auth=[^;]+)/);
  return match ? match[1] : '';
}

function dataOf(response) {
  return response && response.json && Object.prototype.hasOwnProperty.call(response.json, 'data')
    ? response.json.data
    : response && response.json;
}

function extractUserId(json) {
  const data = json && Object.prototype.hasOwnProperty.call(json, 'data') ? json.data : json;
  if (!data || typeof data !== 'object') return '';
  if (typeof data.id === 'string') return data.id;
  if (data.user && typeof data.user.id === 'string') return data.user.id;
  return '';
}

async function loginOrSetupOwner() {
  const settings = await request('/rest/settings');
  if (!settings.ok) {
    throw new Error(`Could not read n8n settings: HTTP ${settings.status} ${settings.text.slice(0, 200)}`);
  }

  if (settings.json && settings.json.data && settings.json.data.userManagement && settings.json.data.userManagement.showSetupOnFirstLoad) {
    const setup = await request('/rest/owner/setup', {
      method: 'POST',
      body: {
        email: N8N_OWNER_EMAIL,
        firstName: 'Local',
        lastName: 'Dev',
        password: N8N_OWNER_PASSWORD
      }
    });
    if (!setup.ok) {
      throw new Error(`Could not set up n8n owner: HTTP ${setup.status} ${setup.text.slice(0, 300)}`);
    }
    const cookie = authCookieFrom(setup);
    if (!cookie) {
      throw new Error('n8n owner setup did not return an auth cookie.');
    }
    return { cookie, setupPerformed: true, userId: extractUserId(setup.json) };
  }

  const login = await request('/rest/login', {
    method: 'POST',
    body: {
      emailOrLdapLoginId: N8N_OWNER_EMAIL,
      password: N8N_OWNER_PASSWORD
    }
  });
  if (!login.ok) {
    throw new Error(`Could not log in to n8n as ${N8N_OWNER_EMAIL}: HTTP ${login.status} ${login.text.slice(0, 300)}`);
  }
  const cookie = authCookieFrom(login);
  if (!cookie) {
    throw new Error('n8n login did not return an auth cookie.');
  }
  return { cookie, setupPerformed: false, userId: extractUserId(login.json) };
}

function arrayFrom(value) {
  if (Array.isArray(value)) return value;
  if (!value || typeof value !== 'object') return [];
  if (Array.isArray(value.data)) return value.data;
  if (Array.isArray(value.items)) return value.items;
  if (Array.isArray(value.results)) return value.results;
  return [];
}

async function getPersonalProjectId(cookie, userId) {
  if (process.env.N8N_PROJECT_ID) {
    return process.env.N8N_PROJECT_ID;
  }
  const projectsResponse = await request('/rest/projects', { cookie });
  if (!projectsResponse.ok) {
    throw new Error(`Could not read n8n projects: HTTP ${projectsResponse.status} ${projectsResponse.text.slice(0, 300)}`);
  }
  const projects = arrayFrom(dataOf(projectsResponse));
  const personalProject = projects.find((project) => {
    if (!project || typeof project !== 'object') return false;
    if (project.type === 'personal') return true;
    const relations = arrayFrom(project.relations);
    return relations.some((relation) => relation && relation.userId === userId && relation.role === 'project:personalOwner');
  }) || projects[0];
  if (!personalProject || !personalProject.id) {
    throw new Error('Could not resolve n8n personal project from /rest/projects.');
  }
  return personalProject.id;
}

async function readCredential(cookie) {
  const response = await request(`/rest/credentials/${encodeURIComponent(CREDENTIAL_ID)}`, { cookie });
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new Error(`Could not read n8n credential ${CREDENTIAL_ID}: HTTP ${response.status} ${response.text.slice(0, 300)}`);
  }
  return dataOf(response);
}

async function readWorkflow(cookie) {
  const response = await request(`/rest/workflows/${encodeURIComponent(WORKFLOW_ID)}`, { cookie });
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new Error(`Could not read n8n workflow ${WORKFLOW_ID}: HTTP ${response.status} ${response.text.slice(0, 300)}`);
  }
  return dataOf(response);
}

function makeCredential() {
  return [{
    id: CREDENTIAL_ID,
    name: CREDENTIAL_NAME,
    type: 'openAiApi',
    data: {
      apiKey: API_KEY,
      url: BASE_URL
    }
  }];
}

function makeWorkflow() {
  return {
    id: WORKFLOW_ID,
    name: WORKFLOW_NAME,
    active: false,
    nodes: [
      {
        id: 'manual-trigger',
        name: 'When clicking Execute workflow',
        type: 'n8n-nodes-base.manualTrigger',
        typeVersion: 1,
        position: [0, 0],
        parameters: {}
      },
      {
        id: 'basic-llm-chain',
        name: 'Basic LLM Chain',
        type: '@n8n/n8n-nodes-langchain.chainLlm',
        typeVersion: 1.9,
        position: [300, 0],
        parameters: {
          promptType: 'define',
          text: 'Return JSON exactly like {"ok":true,"source":"n8n-saved-workflow-smoke"}. The word json is intentionally included.',
          messages: {
            messageValues: [
              {
                type: 'SystemMessagePromptTemplate',
                message: 'Return only parseable JSON and do not use Markdown fences.'
              }
            ]
          },
          hasOutputParser: false,
          needsFallback: false,
          batching: {
            batchSize: 1,
            delayBetweenBatches: 0
          }
        }
      },
      {
        id: 'openai-chat-model',
        name: 'OpenAI Chat Model',
        type: '@n8n/n8n-nodes-langchain.lmChatOpenAi',
        typeVersion: 1.3,
        position: [300, 260],
        parameters: {
          model: {
            __rl: true,
            mode: 'id',
            value: MODEL
          },
          responsesApiEnabled: false,
          options: {
            baseURL: BASE_URL,
            timeout: OPENAI_TIMEOUT_MS,
            maxRetries: OPENAI_MAX_RETRIES,
            responseFormat: 'json_object'
          }
        },
        credentials: {
          openAiApi: {
            id: CREDENTIAL_ID,
            name: CREDENTIAL_NAME
          }
        }
      },
      {
        id: 'after-chain',
        name: 'After Chain',
        type: 'n8n-nodes-base.noOp',
        typeVersion: 1,
        position: [620, 0],
        parameters: {}
      }
    ],
    connections: {
      'When clicking Execute workflow': {
        main: [
          [
            {
              node: 'Basic LLM Chain',
              type: 'main',
              index: 0
            }
          ]
        ]
      },
      'OpenAI Chat Model': {
        ai_languageModel: [
          [
            {
              node: 'Basic LLM Chain',
              type: 'ai_languageModel',
              index: 0
            }
          ]
        ]
      },
      'Basic LLM Chain': {
        main: [
          [
            {
              node: 'After Chain',
              type: 'main',
              index: 0
            }
          ]
        ]
      }
    },
    settings: {
      executionOrder: 'v1'
    },
    meta: {
      deepseekOpenAiAdapterSmoke: true,
      baseUrl: BASE_URL
    }
  };
}

function readSavedWorkflowConfig(workflow) {
  if (!workflow) {
    return {
      exists: false,
      baseURL: '',
      model: '',
      responsesApiEnabled: null,
      timeout: null,
      maxRetries: null,
      responseFormat: ''
    };
  }
  const nodes = Array.isArray(workflow.nodes)
    ? workflow.nodes
    : typeof workflow.nodes === 'string'
      ? JSON.parse(workflow.nodes)
      : [];
  const openAiNode = Array.isArray(nodes)
    ? nodes.find((node) => node && node.type === '@n8n/n8n-nodes-langchain.lmChatOpenAi')
    : null;
  if (!openAiNode) {
    return {
      exists: true,
      baseURL: '',
      model: '',
      responsesApiEnabled: null,
      timeout: null,
      maxRetries: null,
      responseFormat: ''
    };
  }
  const parameters = openAiNode.parameters || {};
  const options = parameters.options || {};
  return {
    exists: true,
    baseURL: options.baseURL ? options.baseURL : '',
    model: parameters.model && typeof parameters.model === 'object' ? parameters.model.value : parameters.model,
    responsesApiEnabled: parameters.responsesApiEnabled,
    timeout: options.timeout,
    maxRetries: options.maxRetries,
    responseFormat: options.responseFormat || ''
  };
}

function savedWorkflowMismatches(config) {
  const mismatches = [];
  if (config.baseURL !== BASE_URL) {
    mismatches.push(`baseURL saved=${config.baseURL || '<empty>'} expected=${BASE_URL}`);
  }
  if (config.model !== MODEL) {
    mismatches.push(`model saved=${config.model || '<empty>'} expected=${MODEL}`);
  }
  if (config.responsesApiEnabled !== false) {
    mismatches.push(`responsesApiEnabled saved=${config.responsesApiEnabled} expected=false`);
  }
  if (Number(config.timeout) !== OPENAI_TIMEOUT_MS) {
    mismatches.push(`timeout saved=${config.timeout == null ? '<empty>' : config.timeout} expected=${OPENAI_TIMEOUT_MS}`);
  }
  if (Number(config.maxRetries) !== OPENAI_MAX_RETRIES) {
    mismatches.push(`maxRetries saved=${config.maxRetries == null ? '<empty>' : config.maxRetries} expected=${OPENAI_MAX_RETRIES}`);
  }
  if (config.responseFormat !== 'json_object') {
    mismatches.push(`responseFormat saved=${config.responseFormat || '<empty>'} expected=json_object`);
  }
  return mismatches;
}

function assertSavedWorkflowMatchesExpected({ forceImport, workflow }) {
  const config = readSavedWorkflowConfig(workflow);
  if (!config.exists) {
    throw new Error(`Saved n8n workflow ${WORKFLOW_ID} was not found after import.`);
  }
  const mismatches = savedWorkflowMismatches(config);
  if (!mismatches.length) {
    return config;
  }
  const refreshHint = forceImport
    ? 'The workflow was refreshed but still does not match the expected adapter settings.'
    : 'Set N8N_SMOKE_FORCE_IMPORT=1 to refresh the existing saved smoke workflow via n8n REST. Set N8N_SMOKE_IMPORT_WITH_CLI=1 only when the fixture is missing.';
  throw new Error(`Saved n8n workflow ${WORKFLOW_ID} does not match this smoke run: ${mismatches.join('; ')}. ${refreshHint}`);
}

async function updateExistingWorkflowWithRest(cookie, workflow) {
  const body = makeWorkflow();
  if (workflow && workflow.checksum) {
    body.expectedChecksum = workflow.checksum;
  }
  const response = await request(`/rest/workflows/${encodeURIComponent(WORKFLOW_ID)}?forceSave=true`, {
    method: 'PATCH',
    cookie,
    body
  });
  if (!response.ok) {
    throw new Error(`Could not update n8n workflow ${WORKFLOW_ID} through REST: HTTP ${response.status} ${response.text.slice(0, 700)}`);
  }
  return dataOf(response);
}

function assertCredentialReuseIsSafe({ credentialExists, forceImport }) {
  if (!credentialExists || forceImport) {
    return;
  }
  if (API_KEY !== 'local-dev' && (process.env.N8N_OPENAI_API_KEY || process.env.DEEPSEEK_OPENAI_FACADE_API_KEY)) {
    throw new Error(`Saved n8n credential ${CREDENTIAL_ID} already exists and a non-default API key was provided. Set N8N_SMOKE_FORCE_IMPORT=1 N8N_SMOKE_IMPORT_WITH_CLI=1 to refresh the credential before running this smoke.`);
  }
}

function isExpectedModelContent(text) {
  return typeof text === 'string' && (text.includes(MOCK_SOURCE) || text.includes(EXPECTED_SOURCE));
}

function parseIndexedExecutionData(value) {
  if (typeof value !== 'string') {
    return value;
  }
  let table;
  try {
    table = JSON.parse(value);
  } catch (_) {
    return value;
  }
  if (!Array.isArray(table)) {
    return table;
  }
  const decoded = table.map((item) => {
    if (Array.isArray(item)) return [];
    return item && typeof item === 'object' ? {} : item;
  });
  const resolve = (item) => {
    if (typeof item === 'string' && /^\d+$/.test(item)) {
      const index = Number(item);
      if (index >= 0 && index < decoded.length) {
        return decoded[index];
      }
    }
    return item;
  };
  table.forEach((item, index) => {
    if (Array.isArray(item)) {
      for (const child of item) {
        decoded[index].push(resolve(child));
      }
      return;
    }
    if (item && typeof item === 'object') {
      for (const [key, child] of Object.entries(item)) {
        decoded[index][key] = resolve(child);
      }
    }
  });
  return decoded[0];
}

function executionOutputScope(execution) {
  const parsedData = parseIndexedExecutionData(execution && execution.data);
  if (parsedData && parsedData.resultData && parsedData.resultData.runData) {
    return parsedData.resultData.runData;
  }
  return parsedData || execution;
}

function findModelContent(value) {
  const seen = new Set();
  const stack = [value];
  while (stack.length) {
    const current = stack.pop();
    if (typeof current === 'string') {
      if (isExpectedModelContent(current)) {
        return current;
      }
      continue;
    }
    if (!current || typeof current !== 'object') continue;
    if (seen.has(current)) continue;
    seen.add(current);
    if (current.json && typeof current.json === 'object') {
      const serializedJson = JSON.stringify(current.json);
      if (isExpectedModelContent(serializedJson)) {
        return serializedJson;
      }
    }
    if (isExpectedModelContent(current.text)) {
      return current.text;
    }
    if (isExpectedModelContent(current.output)) {
      return current.output;
    }
    if (isExpectedModelContent(current.response)) {
      return current.response;
    }
    for (const [key, child] of Object.entries(current)) {
      if (key === 'inputOverride') {
        continue;
      }
      if (child != null) stack.push(child);
    }
  }
  return '';
}

function arrayAt(value) {
  return Array.isArray(value) ? value : [];
}

function findSavedWorkflowOutputContent(runData) {
  const basicRuns = arrayAt(runData && runData['Basic LLM Chain']);
  for (const run of basicRuns) {
    for (const outputGroup of arrayAt(run && run.data && run.data.main)) {
      for (const item of arrayAt(outputGroup)) {
        if (item && item.json && typeof item.json === 'object') {
          const serializedJson = JSON.stringify(item.json);
          if (isExpectedModelContent(serializedJson)) {
            return serializedJson;
          }
        }
        const content = findModelContent(item);
        if (content) {
          return content;
        }
      }
    }
  }

  const modelRuns = arrayAt(runData && runData['OpenAI Chat Model']);
  for (const run of modelRuns) {
    const aiOutputs = arrayAt(run && run.data && run.data.ai_languageModel);
    for (const outputGroup of aiOutputs) {
      for (const item of arrayAt(outputGroup)) {
        const content = findModelContent(item && item.json ? item.json.response : item);
        if (content) {
          return content;
        }
      }
    }
  }
  return '';
}

async function runSavedWorkflow(cookie) {
  const runResponse = await request(`/rest/workflows/${WORKFLOW_ID}/run`, {
    method: 'POST',
    cookie,
    body: {
      triggerToStartFrom: {
        name: 'When clicking Execute workflow'
      },
      destinationNode: {
        nodeName: 'After Chain'
      }
    }
  });
  if (!runResponse.ok) {
    throw new Error(`Could not run saved n8n workflow: HTTP ${runResponse.status} ${runResponse.text.slice(0, 500)}`);
  }
  const executionId = runResponse.json && runResponse.json.data && runResponse.json.data.executionId;
  if (!executionId) {
    throw new Error(`n8n run response did not include executionId: ${runResponse.text.slice(0, 500)}`);
  }

  const startedAt = Date.now();
  while (Date.now() - startedAt < EXECUTION_TIMEOUT_MS) {
    const executionResponse = await request(`/rest/executions/${executionId}`, { cookie });
    if (!executionResponse.ok) {
      throw new Error(`Could not read n8n execution ${executionId}: HTTP ${executionResponse.status} ${executionResponse.text.slice(0, 500)}`);
    }
    const execution = executionResponse.json && executionResponse.json.data;
    if (execution && execution.status && execution.status !== 'running') {
      return execution;
    }
    await new Promise((resolve) => setTimeout(resolve, EXECUTION_POLL_INTERVAL_MS));
  }
  throw new Error(`Timed out waiting for n8n execution to finish after ${EXECUTION_TIMEOUT_MS}ms.`);
}

async function main() {
  const auth = await loginOrSetupOwner();
  const forceImport = process.env.N8N_SMOKE_FORCE_IMPORT === '1';
  const importWithCli = process.env.N8N_SMOKE_IMPORT_WITH_CLI === '1';
  let projectId = '';
  let tmpDir = '';
  let credentialContainerPath = '';
  let workflowContainerPath = '';
  const cleanup = {
    hostTmpCreated: false,
    hostTmpRemoved: null,
    containerTmpCreated: false,
    containerTmpRemoved: null
  };
  const cleanupTempFiles = () => {
    if (credentialContainerPath || workflowContainerPath) {
      try {
        dockerExecRoot(['rm', '-f', credentialContainerPath, workflowContainerPath]);
        cleanup.containerTmpRemoved = true;
      } catch (_) {
        cleanup.containerTmpRemoved = false;
      }
    }
    if (tmpDir) {
      try {
        fs.rmSync(tmpDir, { recursive: true, force: true });
        cleanup.hostTmpRemoved = true;
      } catch (_) {
        cleanup.hostTmpRemoved = false;
      }
    }
  };

  try {
    let credential = await readCredential(auth.cookie);
    let workflow = await readWorkflow(auth.cookie);
    let credentialExists = Boolean(credential);
    let workflowExists = Boolean(workflow);
    assertCredentialReuseIsSafe({ credentialExists, forceImport });

    let credentialImport = 'Skipped import; credential already exists.';
    let workflowImport = 'Skipped import; workflow already exists.';
    const cliImportNeeded = !credentialExists || !workflowExists;
    if (cliImportNeeded) {
      if (!importWithCli) {
        throw new Error(`n8n smoke fixture is missing (credentialExists=${credentialExists}, workflowExists=${workflowExists}). Default smoke mode will only update an existing workflow through REST. Set N8N_SMOKE_IMPORT_WITH_CLI=1 to import the missing fixture explicitly.`);
      }

      projectId = await getPersonalProjectId(auth.cookie, auth.userId);
      tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'n8n-dswb-smoke-'));
      cleanup.hostTmpCreated = true;
      const credentialPath = path.join(tmpDir, 'credential.json');
      const workflowPath = path.join(tmpDir, 'workflow.json');
      credentialContainerPath = `/tmp/${path.basename(tmpDir)}-credential.json`;
      workflowContainerPath = `/tmp/${path.basename(tmpDir)}-workflow.json`;
      writeJson(credentialPath, makeCredential());
      writeJson(workflowPath, makeWorkflow());

      run('docker', ['cp', credentialPath, `${CONTAINER}:${credentialContainerPath}`]);
      run('docker', ['cp', workflowPath, `${CONTAINER}:${workflowContainerPath}`]);
      cleanup.containerTmpCreated = true;

      credentialImport = !credentialExists
        ? dockerExec([
          'n8n',
          'import:credentials',
          '--input',
          credentialContainerPath,
          '--projectId',
          projectId
        ]).trim()
        : credentialImport;
      workflowImport = !workflowExists
        ? dockerExec([
          'n8n',
          'import:workflow',
          '--input',
          workflowContainerPath,
          '--projectId',
          projectId
        ]).trim()
        : workflowImport;
      cleanupTempFiles();

      credential = await readCredential(auth.cookie);
      workflow = await readWorkflow(auth.cookie);
      credentialExists = Boolean(credential);
      workflowExists = Boolean(workflow);
    }

    if (workflowExists && forceImport) {
      workflow = await updateExistingWorkflowWithRest(auth.cookie, workflow);
      workflowImport = 'Updated saved workflow via n8n REST PATCH.';
    }

    const savedWorkflowConfig = assertSavedWorkflowMatchesExpected({ forceImport, workflow });
    const execution = await runSavedWorkflow(auth.cookie);
    const content = findSavedWorkflowOutputContent(executionOutputScope(execution));

    const ok = execution.status === 'success' && isExpectedModelContent(content);
    const summary = {
      ok,
      container: CONTAINER,
      n8nBaseUrl: N8N_BASE_URL,
      ownerSetupPerformed: auth.setupPerformed,
      userId: auth.userId || '',
      projectId,
      credentialId: CREDENTIAL_ID,
      workflowId: WORKFLOW_ID,
      baseUrl: BASE_URL,
      model: MODEL,
      credentialImport,
      workflowImport,
      savedWorkflowConfig,
      timeoutPolicy: {
        openAiNodeTimeoutMs: OPENAI_TIMEOUT_MS,
        executionTimeoutMs: EXECUTION_TIMEOUT_MS,
        executionPollIntervalMs: EXECUTION_POLL_INTERVAL_MS
      },
      executionOutputScope: 'resultData.runData',
      expectedContentSources: [MOCK_SOURCE, EXPECTED_SOURCE],
      tempCleanup: cleanup,
      executionId: execution.id,
      executionStatus: execution.status,
      executionFinished: execution.finished,
      workflowVersionId: execution.workflowVersionId,
      executionContentLength: content.length,
      executionContentSnippet: content.length > 500 ? `${content.slice(0, 500)}...` : content,
      executionFingerprint: crypto.createHash('sha256').update(JSON.stringify(execution)).digest('hex').slice(0, 16)
    };
    console.log(JSON.stringify(summary, null, 2));
    if (!ok) {
      console.error(JSON.stringify(execution, null, 2).slice(0, 4000));
      process.exitCode = 1;
    }
  } finally {
    cleanupTempFiles();
  }
}

if (require.main === module) {
  main().catch((error) => {
    console.error(JSON.stringify({
      ok: false,
      error: error && error.message ? error.message : String(error),
      stack: error && error.stack ? error.stack : undefined
    }, null, 2));
    process.exit(1);
  });
}

module.exports = {
  executionOutputScope,
  findSavedWorkflowOutputContent,
  parseIndexedExecutionData
};
