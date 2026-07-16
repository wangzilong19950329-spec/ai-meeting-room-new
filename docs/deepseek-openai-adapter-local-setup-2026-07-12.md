# DeepSeek OpenAI-Compatible Adapter Local Setup

This is the local setup guide for the first n8n/Langflow integration version.

## 1. Start The Bridge

```bash
scripts/start-deepseek-web-bridge-safe.sh
```

Adapter base URL:

```text
http://127.0.0.1:8789/v1
```

Quick protocol smoke:

```bash
node scripts/deepseek-openai-adapter-smoke.js
```

This does not submit a real DeepSeek prompt. It checks:

1. `/v1/models`
2. `/v1/responses` explicit rejection
3. `stream=true` explicit rejection
4. `tools` explicit rejection
5. current DeepSeek login state from Chrome debug port `9333`

## 2. Log In To DeepSeek

The bridge Chrome profile must be logged in before real chat completions can work.

Open the Chrome instance attached to debug port `9333`, or inspect:

```bash
curl http://127.0.0.1:9333/json/list
```

If the DeepSeek target is on:

```text
https://chat.deepseek.com/sign_in
```

finish login first.

After login:

```bash
node scripts/deepseek-openai-adapter-smoke.js --chat
```

For the full real-adapter readiness check, use:

```bash
scripts/deepseek-openai-local-real-smoke.js
```

Earlier 2026-07-13 pre-login result: the real adapter `/v1/models` endpoint was reachable on `http://127.0.0.1:8789/v1`, and both Docker containers could reach `http://host.docker.internal:8789/v1/models`, but the DeepSeek Chrome target was still `https://chat.deepseek.com/sign_in`, so the script exited with `rc=2` and `login.status=login_required`.

Later 2026-07-13 post-login result: real text and JSON chat completions passed through an isolated bridge on `8790` with Chrome debug port `9344`. The isolated bridge was used because the local Spring Boot app on `8127` was continuously calling the default bridge on `8789`, which made `8789` a shared serial resource and polluted n8n/Langflow verification.

After logging in, verify real generation first:

```bash
scripts/deepseek-openai-local-real-smoke.js --chat --json-chat
```

If you want the command to activate the DeepSeek tab and wait while you scan or finish login:

```bash
scripts/deepseek-openai-local-real-smoke.js \
  --focus-login \
  --wait-login \
  --chat \
  --json-chat
```

Then intentionally refresh the saved n8n fixture from mock `18789` to real `8789` and run both saved runtimes:

```bash
scripts/deepseek-openai-local-real-smoke.js --workflows --refresh-n8n
```

The `--refresh-n8n` flag is required because the n8n smoke fixture is normally protected by a stale Base URL guard and remains pointed at the mock adapter until you explicitly switch it.

One-command gated path:

```bash
scripts/deepseek-openai-local-real-smoke.js --all-after-login
```

This activates the DeepSeek tab, waits for login, verifies real text/JSON generation, then refreshes and runs the n8n/Langflow saved runtimes. If login is still incomplete, it exits before submitting chat or changing fixtures.

When the meeting-room backend is also running and using the default `8789` bridge, run an isolated bridge for n8n/Langflow verification:

```bash
DEEPSEEK_WEB_BRIDGE_PORT=8790 \
DEEPSEEK_WEB_DEBUG_PORT=9344 \
DEEPSEEK_WEB_PROFILE_ROOT=/Users/wangzilong/.aimeeting-room/deepseek-web-stock-profile \
node scripts/deepseek-web-bridge.js
```

Then point real smokes at the isolated bridge:

```bash
DEEPSEEK_OPENAI_BASE_URL=http://127.0.0.1:8790/v1 \
DEEPSEEK_OPENAI_CONTAINER_BASE_URL=http://host.docker.internal:8790/v1 \
DEEPSEEK_WEB_DEBUG_PORT=9344 \
node scripts/deepseek-openai-local-real-smoke.js
```

This is only an isolation tactic for local validation. The default local adapter remains `http://127.0.0.1:8789/v1`.

## 3. Optional Mock Mode For n8n/Langflow Wiring

Use mock mode when DeepSeek is not logged in yet but you want to verify that n8n/Langflow can talk to the OpenAI-compatible adapter.

Start a separate pure mock adapter server:

```bash
scripts/start-deepseek-openai-adapter-mock.sh
```

If n8n/Langflow run in Docker and need to reach the mock adapter through
`host.docker.internal`, bind the mock adapter to all local interfaces:

```bash
DEEPSEEK_OPENAI_ADAPTER_MOCK_HOST=0.0.0.0 \
  DEEPSEEK_OPENAI_FACADE_API_KEY=local-dev \
  scripts/start-deepseek-openai-adapter-mock.sh
```

Mock adapter base URL:

```text
http://127.0.0.1:18789/v1
http://host.docker.internal:18789/v1
```

Verify it:

```bash
DEEPSEEK_OPENAI_BASE_URL=http://127.0.0.1:18789/v1 \
  node scripts/deepseek-openai-adapter-smoke.js --chat --json-chat
```

Expected marker:

```text
deepseek_web_bridge.mock = true
deepseek_web_bridge.completionReason = mock
```

Only use this for local wiring smoke tests. It does not start Chrome and does not prove real DeepSeek generation.

## 4. Optional Docker UI Startup

If n8n or Langflow are not installed locally, use the provided compose file:

```bash
docker compose -f docker-compose.deepseek-openai-local.yml up n8n
docker compose -f docker-compose.deepseek-openai-local.yml up langflow
```

The compose file defaults to the versions used for this smoke pass:

```text
N8N_IMAGE default: n8nio/n8n:2.29.10
LANGFLOW_IMAGE default: langflowai/langflow:1.10.2
```

Override `N8N_IMAGE` or `LANGFLOW_IMAGE` only when intentionally revalidating a newer version.

UI URLs:

```text
n8n:      http://127.0.0.1:5678
Langflow: http://127.0.0.1:7860
```

The compose file publishes both UI ports only on host loopback (`127.0.0.1`).

Inside these containers, use `host.docker.internal` to reach the host adapter:

```text
Mock adapter: http://host.docker.internal:18789/v1
Real adapter: http://host.docker.internal:8789/v1
Isolated real adapter for validation: http://host.docker.internal:8790/v1
```

The compose file also sets:

```text
LANGFLOW_SSRF_ALLOWED_HOSTS=host.docker.internal,127.0.0.1,localhost
LANGFLOW_SUPERUSER=langflow
LANGFLOW_SUPERUSER_PASSWORD=langflow-local-dev
```

Langflow local login for the compose service:

```text
Username: langflow
Password: langflow-local-dev
```

Current note: on this machine, `docker compose -f docker-compose.deepseek-openai-local.yml config` validates successfully. The pinned n8n and Langflow image tags exist, and their containers have both been started.

Docker container reachability and component-level model calls were verified against the mock adapter:

```text
n8n container -> http://host.docker.internal:18789/v1/models -> 200
n8n container -> /v1/chat/completions -> 200, deepseek_web_bridge.mock=true
n8n container -> JSON mode chat completion -> 200, parseable JSON
n8n LMChatOpenAi node class -> text chat -> 200, deepseek_web_bridge.mock=true
n8n LMChatOpenAi node class -> JSON mode chat -> 200, parseable JSON
Langflow container -> http://host.docker.internal:18789/v1/models -> 200
Langflow container -> /v1/chat/completions -> 200, deepseek_web_bridge.mock=true
Langflow DeepSeekModelComponent -> model list, text chat, JSON mode chat -> 200
Langflow LMStudioModelComponent -> text chat -> 200
```

This proves the installed n8n/Langflow model component code can call the adapter. A stronger runtime smoke also now exists for saved n8n workflows and saved Langflow flows; see the commands in the n8n and Langflow sections below.

## 5. n8n Local Config

Use `OpenAI Chat Model`, not the DeepSeek node.

```text
Credential: OpenAI
Credential Base URL: http://127.0.0.1:8789/v1
API Key: local-dev
Model: deepseek-web-bridge
Use Responses API: false
Streaming: false
Tools: none
Max retries: 0 or 1
Timeout: >= 480000 ms
```

For pre-login wiring smoke, temporarily use:

```text
Credential Base URL: http://127.0.0.1:18789/v1
```

If using the Docker compose n8n container:

```text
Credential Base URL: http://host.docker.internal:18789/v1
```

Container node-level smoke:

```bash
docker cp scripts/n8n-openai-chat-model-node-smoke.js ai-meeting-room-n8n-local:/tmp/
docker exec \
  -e N8N_OPENAI_BASE_URL=http://host.docker.internal:18789/v1 \
  -e N8N_OPENAI_API_KEY=local-dev \
  -e N8N_OPENAI_MODEL=deepseek-web-bridge \
  ai-meeting-room-n8n-local \
  node /tmp/n8n-openai-chat-model-node-smoke.js --json
```

Saved-workflow runtime smoke against the local Docker n8n service:

```bash
N8N_OPENAI_BASE_URL=http://host.docker.internal:18789/v1 \
  N8N_OPENAI_API_KEY=local-dev \
  node scripts/n8n-saved-workflow-runtime-smoke.js
```

Verified on 2026-07-13:

```text
n8n version observed: 2.29.10
workflowId: DswbN8nSmoke001
credentialId: DswbOpenAiCred1
executionId: 11
executionStatus: success
workflowVersionId: fb558e46-25a3-485e-b43f-6d84f1d319a9
savedWorkflowConfig.baseURL: http://host.docker.internal:18789/v1
tempCleanup.hostTmpCreated: false
tempCleanup.containerTmpCreated: false
executionFingerprint: 3091f1127f77d07c
```

This proves a saved n8n workflow can execute through the n8n runtime against the mock OpenAI-compatible adapter, and that the saved OpenAI Chat Model node config points at the expected Base URL/model with Responses API disabled. It does not prove n8n credential-store internals beyond the credential ID existing and being usable by the saved workflow. It is still not proof of real DeepSeek generation until the bridge Chrome profile is logged in and the Base URL is switched to the real adapter.

When switching an existing saved workflow between mock `18789` and real `8789`, refresh the workflow through n8n REST:

```bash
N8N_SMOKE_FORCE_IMPORT=1 \
  N8N_OPENAI_BASE_URL=http://host.docker.internal:8789/v1 \
  N8N_OPENAI_API_KEY=local-dev \
  node scripts/n8n-saved-workflow-runtime-smoke.js
```

Only add `N8N_SMOKE_IMPORT_WITH_CLI=1` when the credential/workflow fixture is missing and must be created from scratch. Avoid CLI import while the n8n server is running if an existing workflow can be updated through REST.

Without `N8N_SMOKE_FORCE_IMPORT=1`, the script validates the saved workflow `baseURL`, model, `responsesApiEnabled=false`, timeout, retry count, and response format before running, and fails rather than accidentally executing stale mock configuration. The normal refresh path is REST-only and does not create temporary credential/workflow JSON. Temporary JSON fixture files are only written when `N8N_SMOKE_IMPORT_WITH_CLI=1` is set, and are removed from both the host temp directory and the n8n container `/tmp`. Historical host `os.tmpdir()` smoke leftovers from the earlier import script were found by review and removed; current host `os.tmpdir()` and n8n container `/tmp` checks are clean.

After DeepSeek login, switch back to:

```text
Credential Base URL: http://127.0.0.1:8789/v1
```

If using Docker compose after DeepSeek login:

```text
Credential Base URL: http://host.docker.internal:8789/v1
```

If a Docker container cannot reach the real adapter on `8789`, start the real bridge with both `DEEPSEEK_WEB_BRIDGE_HOST=0.0.0.0` and `DEEPSEEK_OPENAI_FACADE_API_KEY=local-dev` for the local test window, then keep it firewalled to the local machine/network. In that mode, both `/v1/*` and raw `/search` require the same `Authorization: Bearer <key>` header.

Do not use `tools`, `tool_choice`, built-in tools, or Responses API in local v1.

## 6. Langflow Local Config

Set SSRF allowlist before starting Langflow:

```bash
export LANGFLOW_SSRF_ALLOWED_HOSTS=127.0.0.1,localhost
```

Prefer the DeepSeek or LM Studio component for the first smoke.

DeepSeek component:

```text
DeepSeek API Base: http://127.0.0.1:8789/v1
DeepSeek API Key: local-dev
Model Name: deepseek-web-bridge
Stream: false
```

LM Studio component:

```text
Base URL: http://127.0.0.1:8789/v1
API Key: local-dev
Model: deepseek-web-bridge
```

The OpenAI component is a secondary smoke path and should be checked separately.

For pre-login wiring smoke, temporarily use:

```text
Base URL: http://127.0.0.1:18789/v1
```

If using the Docker compose Langflow container:

```text
Base URL: http://host.docker.internal:18789/v1
```

Container component-level smoke:

```bash
docker cp scripts/langflow-openai-component-smoke.py ai-meeting-room-langflow-local:/tmp/
docker exec \
  -e LANGFLOW_OPENAI_BASE_URL=http://host.docker.internal:18789/v1 \
  -e LANGFLOW_OPENAI_API_KEY=local-dev \
  -e LANGFLOW_OPENAI_MODEL=deepseek-web-bridge \
  ai-meeting-room-langflow-local \
  /app/.venv/bin/python /tmp/langflow-openai-component-smoke.py --json
```

Saved-flow runtime smoke against the local Docker Langflow service:

```bash
LANGFLOW_OPENAI_BASE_URL=http://host.docker.internal:18789/v1 \
  LANGFLOW_OPENAI_API_KEY=local-dev \
  scripts/langflow-saved-flow-runtime-smoke.py
```

Verified on 2026-07-13:

```text
Langflow version: 1.10.2
flowId: 0f76ad31-1e75-4c1f-84ca-0df7e885c50b
flowName: DeepSeek OpenAI Adapter Saved Flow Smoke
outputText: {"ok":true,"source":"deepseek-openai-adapter-mock"}
outputFingerprint: 0d3a1ceb163e0dc7
outputSource: deepseek-web-bridge
runFingerprint: e37c08e66b03e0c0
```

Implementation note: the saved-flow smoke sets `api_key.load_from_db=false` on the Langflow `DeepSeekModelComponent`. Langflow treats `load_from_db=true` secret values as global variable names, so a local literal smoke key such as `local-dev` resolves to empty unless this flag is disabled or a matching Langflow global variable is created. This smoke therefore proves the saved-flow runtime path with a local literal test key; it does not prove Langflow global-variable or secret-store handling for real secrets.

After DeepSeek login, switch back to:

```text
Base URL: http://127.0.0.1:8789/v1
```

If using Docker compose after DeepSeek login:

```text
Base URL: http://host.docker.internal:8789/v1
```

If a Docker container cannot reach the real adapter on `8789`, start the real bridge with both `DEEPSEEK_WEB_BRIDGE_HOST=0.0.0.0` and `DEEPSEEK_OPENAI_FACADE_API_KEY=local-dev` for the local test window, then keep it firewalled to the local machine/network. In that mode, both `/v1/*` and raw `/search` require the same `Authorization: Bearer <key>` header.

## 7. Expected Unsupported Errors

These failures are intentional in local v1:

```text
POST /v1/responses -> 400 responses_api_unsupported
stream=true -> 400 streaming_unsupported
tools/function calling -> 400 tool_calling_unsupported
multimodal content -> 400 multimodal_unsupported
```

## 8. Current Verified Boundary

Verified on 2026-07-13:

1. `GET /v1/models` returns `deepseek-web-bridge` and `deepseek-chat`.
2. `POST /v1/responses` fails explicitly.
3. `stream=true` fails explicitly.
4. `tools` fails explicitly.
5. `node scripts/deepseek-openai-adapter-smoke.js` reports DeepSeek login state before submitting real chat; before login it returns `deepseekLogin.status=login_required`.
6. `scripts/start-deepseek-openai-adapter-mock.sh` starts a pure mock adapter server and can serve marked mock text and JSON chat completions on `http://127.0.0.1:18789/v1`.
7. `docker-compose.deepseek-openai-local.yml` validates with `docker compose config`.
8. n8n Docker container starts on `http://127.0.0.1:5678`, published only on host loopback.
9. n8n container can reach the mock adapter through `http://host.docker.internal:18789/v1` for `/models`, text chat, and JSON chat.
10. `scripts/n8n-openai-chat-model-node-smoke.js` verifies the installed n8n `LMChatOpenAi` node class can call the mock adapter for text and JSON mode through a fake minimal node context.
11. `scripts/n8n-saved-workflow-runtime-smoke.js` reuses saved n8n credential/workflow IDs by default, validates the saved workflow OpenAI Chat Model node Base URL/model/Responses API setting before execution through n8n REST, and executes the saved workflow through n8n REST runtime successfully against the mock adapter. It only writes temporary JSON fixtures when explicit CLI import is requested, and current host/container temp checks are clean after removing old smoke leftovers.
12. Langflow Docker container starts on `http://127.0.0.1:7860` after setting a local superuser password, published only on host loopback.
13. Langflow container can reach the mock adapter through `http://host.docker.internal:18789/v1` for `/models` and text chat.
14. `scripts/langflow-openai-component-smoke.py` verifies the installed Langflow `DeepSeekModelComponent` can call the mock adapter for model listing, text, and JSON mode, and `LMStudioModelComponent` can call it for text.
15. `scripts/langflow-saved-flow-runtime-smoke.py` creates or updates a saved Langflow flow and executes it through `/api/v1/run/{flow_id}` successfully against the mock adapter using a local literal test key.
16. `scripts/deepseek-openai-local-real-smoke.js` verifies the real adapter `/v1/models` endpoint and Docker container reachability. Before login it exits `rc=2` without submitting chat or refreshing fixtures; after login, an isolated `8790/9344` run verified direct text/JSON chat with `mock=false`.
17. Saved n8n and Langflow runtimes both completed against the isolated real adapter on `http://host.docker.internal:8790/v1`: n8n output `{"ok":true,"source":"n8n-saved-workflow-smoke"}` and Langflow output `{"ok":true,"source":"langflow-saved-flow-smoke"}`.
18. The current local n8n SQLite file passes read-only `PRAGMA integrity_check;` with result `ok`; earlier `IOERR/NOTADB` logs are retained as historical risk context from the old CLI-import path.
19. If the real bridge is temporarily bound to a non-loopback host with `DEEPSEEK_OPENAI_FACADE_API_KEY`, unauthenticated raw `/search` returns 401 and authenticated `/search` reaches the existing handler.
