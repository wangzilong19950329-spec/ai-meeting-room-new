#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const DEFAULT_N8N_ROOT = '/usr/local/lib/node_modules/n8n';

function findLmChatOpenAiNode(root = DEFAULT_N8N_ROOT) {
  const explicit = process.env.N8N_LM_CHAT_OPENAI_NODE_PATH;
  if (explicit) return explicit;

  const pnpmRoot = path.join(root, 'node_modules', '.pnpm');
  const suffix = path.join(
    'node_modules',
    '@n8n',
    'n8n-nodes-langchain',
    'dist',
    'nodes',
    'llms',
    'LMChatOpenAi',
    'LmChatOpenAi.node.js'
  );

  const stack = [pnpmRoot];
  while (stack.length) {
    const current = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch (_) {
      continue;
    }

    for (const entry of entries) {
      const fullPath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        const candidate = path.join(fullPath, suffix);
        if (fs.existsSync(candidate)) {
          return candidate;
        }
        if (!entry.name.startsWith('.')) {
          stack.push(fullPath);
        }
      }
    }
  }

  throw new Error(`Could not find LmChatOpenAi.node.js under ${pnpmRoot}`);
}

function makeN8nContext({ baseUrl, apiKey, modelName, responseFormat }) {
  let runIndex = 0;
  const params = {
    'model.value': modelName,
    responsesApiEnabled: false,
    options: {
      baseURL: baseUrl,
      timeout: Number(process.env.N8N_OPENAI_TIMEOUT_MS || 30000),
      maxRetries: Number(process.env.N8N_OPENAI_MAX_RETRIES || 0),
      responseFormat
    }
  };

  return {
    getCredentials: async () => ({
      apiKey,
      url: baseUrl
    }),
    getNode: () => ({
      name: 'OpenAI Chat Model smoke',
      type: '@n8n/n8n-nodes-langchain.lmChatOpenAi',
      typeVersion: 1.3,
      position: [0, 0],
      parameters: {}
    }),
    getNodeParameter: (name, itemIndex, fallback) => (
      Object.prototype.hasOwnProperty.call(params, name) ? params[name] : fallback
    ),
    getWorkflow: () => ({
      id: 'n8n-openai-chat-model-node-smoke',
      name: 'n8n OpenAI Chat Model smoke'
    }),
    getExecutionId: () => 'n8n-openai-chat-model-node-smoke-execution',
    getMode: () => 'manual',
    getNextRunIndex: () => runIndex,
    addInputData: () => ({ index: runIndex++ }),
    addOutputData: () => ({ index: runIndex }),
    sendMessageToUI: () => {},
    isToolExecution: false,
    helpers: {},
    logger: {
      debug: () => {},
      info: () => {},
      warn: () => {},
      error: () => {}
    }
  };
}

async function invokeSmoke({ jsonMode = false } = {}) {
  const nodePath = findLmChatOpenAiNode();
  const { LmChatOpenAi } = require(nodePath);
  const instance = new LmChatOpenAi();

  const baseUrl = process.env.N8N_OPENAI_BASE_URL
    || process.env.DEEPSEEK_OPENAI_BASE_URL
    || 'http://host.docker.internal:18789/v1';
  const apiKey = process.env.N8N_OPENAI_API_KEY
    || process.env.DEEPSEEK_OPENAI_API_KEY
    || process.env.DEEPSEEK_OPENAI_FACADE_API_KEY
    || 'local-dev';
  const modelName = process.env.N8N_OPENAI_MODEL || 'deepseek-web-bridge';
  const responseFormat = jsonMode ? 'json_object' : 'text';
  const prompt = jsonMode
    ? 'Return JSON exactly like {"ok":true,"source":"n8n-node-smoke"}.'
    : 'Return exactly: n8n-node-smoke-ok';

  const context = makeN8nContext({ baseUrl, apiKey, modelName, responseFormat });
  const supplied = await instance.supplyData.call(context, 0);
  const response = await supplied.response.invoke(prompt);
  const content = typeof response.content === 'string' ? response.content : JSON.stringify(response.content);

  let parsedJson = null;
  if (jsonMode) {
    parsedJson = JSON.parse(content);
  }

  return {
    nodePath,
    baseUrl,
    modelName,
    responseFormat,
    content,
    parsedJson,
    responseMetadata: response.response_metadata || null
  };
}

async function main() {
  const runJson = process.argv.includes('--json');
  const text = await invokeSmoke({ jsonMode: false });
  const result = { ok: true, text };
  if (runJson) {
    result.json = await invokeSmoke({ jsonMode: true });
  }
  console.log(JSON.stringify(result, null, 2));
}

main().catch((error) => {
  console.error(JSON.stringify({
    ok: false,
    error: error && error.message ? error.message : String(error),
    stack: error && error.stack ? error.stack : undefined
  }, null, 2));
  process.exit(1);
});
