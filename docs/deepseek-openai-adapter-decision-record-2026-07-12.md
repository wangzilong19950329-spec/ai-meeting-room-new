# DeepSeek OpenAI-Compatible Adapter Phase 0.5 Decision Record

Date: 2026-07-12

## Decision

Use **T1 same-process local adapter** for the first local version.

The adapter is mounted into the existing DeepSeek Web Bridge process and exposes OpenAI-compatible routes under the existing bridge port:

```text
http://127.0.0.1:8789/v1
```

The first implementation deliberately delegates chat requests back through the existing local `/search` HTTP route instead of refactoring the `/search` implementation into shared internals. This keeps the current bridge behavior stable while proving the n8n/Langflow protocol surface.

## Selected Topology

```text
n8n / Langflow
  -> http://127.0.0.1:8789/v1/chat/completions
  -> scripts/deepseek-openai-adapter.js
  -> http://127.0.0.1:8789/search
  -> existing DeepSeek Web Bridge /search handler
  -> Chrome Remote Debugging 9333
  -> chat.deepseek.com
```

## Why T1 Wins For Local V1

1. The user explicitly wants a local first version.
2. No extra local port or process is needed.
3. Existing start/stop scripts already manage the bridge process.
4. The `/search` handler is not refactored in this spike, reducing regression risk.
5. The adapter module is pure enough to be reused by a future T2 standalone facade if needed.

## Adapter Base URL

For local n8n and Langflow:

```text
http://127.0.0.1:8789/v1
```

If the bridge port is overridden:

```text
http://127.0.0.1:${DEEPSEEK_WEB_BRIDGE_PORT}/v1
```

## Start Command

Use the existing bridge start path:

```bash
scripts/start-deepseek-web-bridge-safe.sh
```

No separate OpenAI facade process is required for local V1.

## Current Support Boundary

Supported:

1. `GET /v1/models`
2. `POST /v1/chat/completions`
3. Text messages
4. JSON mode via `response_format.type=json_object` with strict JSON parse validation
5. Explicit OpenAI-shaped errors for bridge failures

Explicitly unsupported:

1. `POST /v1/responses`
2. `stream=true`
3. `tools`, `tool_choice`, `functions`, `function_call`
4. Multimodal message parts
5. Embeddings

## Fallback Conditions To T2

Move to a standalone T2 process only if one of these is observed:

1. Same-process `/v1/*` routing changes existing `/health` or `/search` behavior.
2. Local self-delegation from `/v1/chat/completions` to `/search` causes server deadlock, timeout, or unacceptable latency.
3. n8n/Langflow need a separate restart/log/security boundary from the bridge process.
4. Future LAN/container usage requires a distinct adapter security profile.

## Verification

Commands run:

```bash
node --test scripts/deepseek-openai-adapter.test.js scripts/deepseek-web-client.test.js
node --check scripts/deepseek-openai-adapter.js
node --check scripts/deepseek-web-bridge.js
node --check scripts/deepseek-bridge-auth.js
node --check scripts/deepseek-openai-adapter-mock-server.js
node --check scripts/deepseek-openai-adapter-smoke.js
node --check scripts/n8n-openai-chat-model-node-smoke.js
python3 -m py_compile scripts/langflow-openai-component-smoke.py
node scripts/deepseek-openai-adapter-smoke.js
DEEPSEEK_OPENAI_ADAPTER_MOCK_PORT=18792 \
  DEEPSEEK_OPENAI_ADAPTER_MOCK_DEBUG_PORT=19392 \
  DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT='script mock ok' \
  scripts/start-deepseek-openai-adapter-mock.sh
DEEPSEEK_OPENAI_BASE_URL=http://127.0.0.1:18792/v1 \
  DEEPSEEK_WEB_DEBUG_PORT=19392 \
  node scripts/deepseek-openai-adapter-smoke.js --chat --json-chat
docker compose -f docker-compose.deepseek-openai-local.yml config
docker compose -f docker-compose.deepseek-openai-local.yml up -d n8n
docker compose -f docker-compose.deepseek-openai-local.yml up -d langflow
DEEPSEEK_OPENAI_ADAPTER_MOCK_HOST=0.0.0.0 \
  DEEPSEEK_OPENAI_FACADE_API_KEY=local-dev \
  scripts/start-deepseek-openai-adapter-mock.sh
docker exec ai-meeting-room-n8n-local node -e 'fetch("http://host.docker.internal:18789/v1/models", ...)'
docker exec ai-meeting-room-n8n-local node -e 'fetch("http://host.docker.internal:18789/v1/chat/completions", ...)'
docker cp scripts/n8n-openai-chat-model-node-smoke.js ai-meeting-room-n8n-local:/tmp/
docker exec ai-meeting-room-n8n-local node /tmp/n8n-openai-chat-model-node-smoke.js --json
docker exec ai-meeting-room-langflow-local python -c 'urllib.request.urlopen("http://host.docker.internal:18789/v1/models", ...)'
docker exec ai-meeting-room-langflow-local python -c 'urllib.request.urlopen("http://host.docker.internal:18789/v1/chat/completions", ...)'
docker cp scripts/langflow-openai-component-smoke.py ai-meeting-room-langflow-local:/tmp/
docker exec ai-meeting-room-langflow-local /app/.venv/bin/python /tmp/langflow-openai-component-smoke.py --json
N8N_OPENAI_BASE_URL=http://host.docker.internal:18789/v1 \
  N8N_OPENAI_API_KEY=local-dev \
  node scripts/n8n-saved-workflow-runtime-smoke.js
# Expected failure: stale saved-workflow Base URL guard.
N8N_OPENAI_BASE_URL=http://host.docker.internal:8789/v1 \
  N8N_OPENAI_API_KEY=local-dev \
  node scripts/n8n-saved-workflow-runtime-smoke.js
LANGFLOW_OPENAI_BASE_URL=http://host.docker.internal:18789/v1 \
  LANGFLOW_OPENAI_API_KEY=local-dev \
  scripts/langflow-saved-flow-runtime-smoke.py
scripts/deepseek-openai-local-real-smoke.js
scripts/deepseek-openai-local-real-smoke.js --focus-login --wait-login --wait-login-timeout-ms=1000 --wait-login-interval-ms=250
scripts/deepseek-openai-local-real-smoke.js --all-after-login --wait-login-timeout-ms=1000 --wait-login-interval-ms=250
DEEPSEEK_OPENAI_BASE_URL=http://127.0.0.1:8790/v1 \
  DEEPSEEK_OPENAI_CONTAINER_BASE_URL=http://host.docker.internal:8790/v1 \
  DEEPSEEK_WEB_DEBUG_PORT=9344 \
  node scripts/deepseek-openai-local-real-smoke.js
DEEPSEEK_OPENAI_BASE_URL=http://127.0.0.1:8790/v1 \
  DEEPSEEK_OPENAI_API_KEY=local-dev \
  DEEPSEEK_WEB_DEBUG_PORT=9344 \
  node scripts/deepseek-openai-adapter-smoke.js --chat --json-chat
N8N_OPENAI_BASE_URL=http://host.docker.internal:8790/v1 \
  N8N_OPENAI_API_KEY=local-dev \
  N8N_SMOKE_FORCE_IMPORT=1 \
  N8N_OPENAI_TIMEOUT_MS=480000 \
  N8N_EXECUTION_TIMEOUT_MS=600000 \
  N8N_EXECUTION_POLL_INTERVAL_MS=2000 \
  node scripts/n8n-saved-workflow-runtime-smoke.js
LANGFLOW_OPENAI_BASE_URL=http://host.docker.internal:8790/v1 \
  LANGFLOW_OPENAI_API_KEY=local-dev \
  LANGFLOW_RUN_TIMEOUT_SECONDS=600 \
  scripts/langflow-saved-flow-runtime-smoke.py
DEEPSEEK_WEB_BRIDGE_PORT=18789 node scripts/deepseek-web-bridge.js
curl http://127.0.0.1:18789/health
curl http://127.0.0.1:18789/v1/models
curl -X POST http://127.0.0.1:18789/v1/responses -d '{"input":"hi"}'
DEEPSEEK_WEB_BRIDGE_PORT=18790 node scripts/deepseek-web-bridge.js
curl -X POST http://127.0.0.1:18790/search -d '{}'
DEEPSEEK_WEB_BRIDGE_HOST=0.0.0.0 DEEPSEEK_WEB_BRIDGE_PORT=18794 \
  DEEPSEEK_OPENAI_FACADE_API_KEY=secret-key node scripts/deepseek-web-bridge.js
curl -X POST http://127.0.0.1:18794/search -d '{}'
curl -X POST http://127.0.0.1:18794/search \
  -H 'Authorization: Bearer secret-key' -d '{}'
curl -X POST http://127.0.0.1:8789/v1/chat/completions -d '{...}'
curl http://127.0.0.1:9333/json/list
```

Result:

```text
28 tests passed, including the n8n saved-workflow output negative test that proves prompt markers in `inputOverride` do not satisfy the smoke.
Syntax checks passed.
Default adapter smoke passed for /v1/models, /v1/responses rejection, stream rejection, tools rejection, and DeepSeek login-state diagnosis.
Mock adapter start script now runs a pure mock server, starts no Chrome process, and returned marked text and JSON chat completions with deepseek_web_bridge.mock=true.
Docker compose config for local n8n/Langflow validates.
The compose file now defaults to pinned smoke-tested image tags: n8nio/n8n:2.29.10 and langflowai/langflow:1.10.2. Both image tags were verified with docker manifest inspect; N8N_IMAGE and LANGFLOW_IMAGE remain available for intentional revalidation.
n8n Docker image was pulled, container started on http://127.0.0.1:5678 with host-loopback-only port publishing, and container-to-host mock adapter calls returned HTTP 200 for /models, text chat, and JSON chat.
n8n's installed LMChatOpenAi node class called the mock adapter successfully for text and JSON mode with responsesApiEnabled=false and Base URL=http://host.docker.internal:18789/v1.
n8n saved-workflow runtime smoke succeeded against the mock adapter through the default REST-only path: workflowId=DswbN8nSmoke001, credentialId=DswbOpenAiCred1, executionId=11, executionStatus=success, workflowVersionId=fb558e46-25a3-485e-b43f-6d84f1d319a9, savedWorkflowConfig.baseURL=http://host.docker.internal:18789/v1, tempCleanup.hostTmpCreated=false, tempCleanup.containerTmpCreated=false, executionFingerprint=3091f1127f77d07c. The script now fails fast if the saved workflow OpenAI Chat Model node Base URL/model/Responses API/timeout/retry/responseFormat setting does not match the current smoke env; use N8N_SMOKE_FORCE_IMPORT=1 to refresh an existing workflow through n8n REST PATCH, and only add N8N_SMOKE_IMPORT_WITH_CLI=1 when the fixture is missing and must be created. Default validation does not read the host SQLite database and does not write temporary credential/workflow JSON; old host os.tmpdir smoke leftovers from the earlier importer were found by review and removed. This is saved workflow node config + runtime evidence, not proof that n8n credential-store URL/key internals are updated by REST PATCH.
Langflow Docker image was pulled, compose now sets LANGFLOW_SUPERUSER_PASSWORD, container started on http://127.0.0.1:7860 with host-loopback-only port publishing, and container-to-host mock adapter calls returned HTTP 200 for /models and text chat.
Langflow's installed DeepSeekModelComponent called the mock adapter successfully for model listing, text, and JSON mode; LMStudioModelComponent called it successfully for text.
Langflow saved-flow runtime smoke succeeded against the mock adapter: Langflow version=1.10.2, flowId=0f76ad31-1e75-4c1f-84ca-0df7e885c50b, outputText={"ok":true,"source":"deepseek-openai-adapter-mock"}, outputFingerprint=0d3a1ceb163e0dc7, outputSource=deepseek-web-bridge, runFingerprint=e37c08e66b03e0c0. This proves the saved-flow runtime path with api_key.load_from_db=false and a local literal test key, not Langflow secret/global-variable storage.
Real-adapter readiness smoke now exists as scripts/deepseek-openai-local-real-smoke.js. Pre-login runs returned rc=2 with /v1/models reachable on http://127.0.0.1:8789/v1 and from both Docker containers through http://host.docker.internal:8789/v1, while DeepSeek login status was login_required at https://chat.deepseek.com/sign_in. The `--focus-login --wait-login` and `--all-after-login` paths activated the DeepSeek target and timed out cleanly without submitting chat or refreshing fixtures.
Non-loopback bridge binding now fails fast unless DEEPSEEK_OPENAI_FACADE_API_KEY is configured; with a key configured, unauthenticated raw `/search` returned HTTP 401 and authenticated `/search` reached the existing handler and returned HTTP 400 missing_query for an empty body.
GET /v1/models returned deepseek-web-bridge and deepseek-chat.
POST /v1/responses returned HTTP 400 responses_api_unsupported.
POST /search with empty body still returned HTTP 400 missing_query.
Pre-login real /v1/chat/completions reached the adapter and bridge, then returned HTTP 503 login_required.
Post-login isolated real bridge verification on 2026-07-13 passed through `http://127.0.0.1:8790/v1` with Chrome debug port `9344`: `/v1/models` returned `deepseek-web-bridge` and `deepseek-chat`; n8n and Langflow containers both reached `http://host.docker.internal:8790/v1/models`; direct adapter chat returned HTTP 200 with `mock=false`, text content `通过`, and JSON content `{"ok":true,"kind":"json-smoke"}`.
n8n saved-workflow runtime smoke passed against the real adapter through `http://host.docker.internal:8790/v1`: workflowId=DswbN8nSmoke001, executionId=14, executionStatus=success, workflowVersionId=84a03028-8e1d-4f2c-bd6e-a9887ba7d0de, savedWorkflowConfig.timeout=480000, maxRetries=0, responseFormat=json_object, workflowImport=Updated saved workflow via n8n REST PATCH, executionContentSnippet={"ok":true,"source":"n8n-saved-workflow-smoke"}, executionFingerprint=0df43f2d66bae81b. The smoke now parses n8n's serialized `resultData.runData` and only accepts saved workflow output items or OpenAI model generation responses, not prompt markers.
Langflow saved-flow runtime smoke passed against the real adapter through `http://host.docker.internal:8790/v1`: Langflow version=1.10.2, flowId=0f76ad31-1e75-4c1f-84ca-0df7e885c50b, outputText={"ok":true,"source":"langflow-saved-flow-smoke"}, outputFingerprint=37fd34468f6268ec, outputSource=deepseek-web-bridge, runFingerprint=de56ee3eb70d4438.
The default `8789` bridge should be treated as a shared serial resource when the meeting-room Spring Boot backend is running. During this validation the backend on `8127` repeatedly called `8789`, so real n8n/Langflow proof used isolated port `8790` and the already logged-in `9344` Chrome profile.
The current local n8n SQLite file passed read-only `PRAGMA integrity_check;` with result `ok` after the earlier CLI-import IOERR/NOTADB log entries.
```

## Remaining Proof Before Calling n8n/Langflow Done

1. Optional manual UI pass: open the saved n8n workflow and Langflow flow in their UIs and run them once by hand, mainly to validate operator ergonomics rather than protocol compatibility.
2. Run one clean `scripts/deepseek-openai-local-real-smoke.js --all-after-login` pass when the default `8789` bridge is not being used by the meeting-room backend, or keep using an isolated bridge port for tool-only validation.
3. If Docker-based real adapter access through `host.docker.internal:8789` changes, start the bridge with both `DEEPSEEK_WEB_BRIDGE_HOST=0.0.0.0` and `DEEPSEEK_OPENAI_FACADE_API_KEY=local-dev` during the local test window.
