# DeepSeek Web Bridge OpenAI-Compatible Facade 技术方案

更新时间：2026-07-11  
目标仓库：`/Users/wangzilong/IdeaProjects/ai-meeting-room-new`  
目标：让 n8n 和 Langflow 能以 OpenAI-compatible Chat Completions 的方式接入本项目现有 DeepSeek Web Bridge。

## 1. 背景判断

当前会议室已经具备 DeepSeek 网页端调用链：

```text
Chrome 9333 -> DeepSeek Web Bridge 8789 -> Spring Boot -> MySQL
```

现有 bridge 的核心接口是本地 HTTP `POST /search`，请求体以 `query` 为主，辅以 `expectedJson`、`mode`、`sessionKey`、`isolatedSession` 等字段。它不是 OpenAI-compatible API。

n8n 和 Langflow 都更自然地接入 OpenAI-compatible chat model，但这个判断有前置条件：

- n8n 的 `OpenAI Chat Model` 节点支持自定义 Base URL，但新版节点默认可能启用 Responses API，且 node-level Base URL override 会经过 credential URL/domain restriction 校验。
- Langflow 的 `DeepSeek` / `OpenAI` / `LM Studio` 组件都走 `langchain_openai.ChatOpenAI`，并暴露 `api_base` / `base_url`；但 Langflow 默认启用 SSRF protection，会阻止 `127.0.0.1` / `localhost` 这类 loopback 地址，除非配置 allowlist 或调整运行拓扑。

因此推荐先做 OpenAI-compatible adapter，而不是直接改 n8n / Langflow 源码，也不是把 n8n / Langflow 改造成直接调用 `/search`。adapter 的进程拓扑需要在编码前做一个小型 spike：优先比较“同进程挂到现有 bridge”与“独立 Node facade 进程”，再定最终落点。

## 2. 目标与非目标

### 2.1 目标

1. 提供本地 OpenAI-compatible `.../v1` 兼容接口；默认候选地址为 `http://127.0.0.1:8790/v1`，最终地址取决于拓扑 spike。
2. 支持：
   - `GET /v1/models`
   - `POST /v1/chat/completions`
   - `GET /health`
3. 将 OpenAI Chat Completions 请求转换为现有 DeepSeek Web Bridge `/search` 请求。
4. 支持普通文本输出和 JSON 输出。
5. 给 n8n / Langflow 提供明确配置方式。
6. 对不支持能力给出显式错误，避免假装支持导致 workflow 静默错误。

### 2.2 第一版非目标

1. 不支持 streaming。
2. 不支持 tool calling / function calling。
3. 不支持 embeddings。
4. 不支持 OpenAI Responses API；n8n 必须关闭 Responses API，或后续补 `/v1/responses`。
5. 不改 n8n / Langflow 源码。
6. 不把该 facade 默认加入生产自动化链路；第一版以本地低并发验证为主。

## 3. 推荐架构

### 3.1 架构主线：OpenAI-compatible adapter

先新增一个协议 adapter，把 OpenAI Chat Completions 请求转换为现有 `/search` 请求。adapter 可以有两种实现拓扑：

#### 方案 T1：同进程 bridge adapter module

候选改动：

```text
scripts/deepseek-openai-adapter.js
scripts/deepseek-web-bridge.js
scripts/deepseek-openai-adapter.test.js
```

特点：

1. 在现有 `scripts/deepseek-web-bridge.js` 进程内增加 `/v1/models` 和 `/v1/chat/completions` route。
2. 直接复用现有 `inFlight`、队列、cooldown、health、日志和进程生命周期。
3. 少一个端口和进程，启动/停止脚本改动更小。
4. 风险是会触碰现有稳定 bridge 文件，需要把新增路由封装成独立模块，避免影响 `/search`。

#### 方案 T2：独立 Node facade 进程

候选新增：

```text
scripts/deepseek-openai-facade.js
scripts/start-deepseek-openai-facade.sh
scripts/deepseek-openai-facade.test.js
```

默认端口：

```text
DEEPSEEK_OPENAI_FACADE_PORT=8790
DEEPSEEK_WEB_BRIDGE_URL=http://127.0.0.1:8789/search
```

特点：

1. 降低改动风险，不干扰当前 `/search` 调用方。
2. facade 可以独立重启、独立调试。
3. 未来如果要换成 Spring Boot controller 或 MCP server，不需要动底层浏览器自动化 bridge。
4. n8n / Langflow 只依赖 facade，不依赖 Spring Boot。
5. 风险是多一个端口、进程、health、start/stop、日志和失败面；现有 stack 脚本需要补充管理。

### 3.2 编码前拓扑 spike

编码前必须做一个不连真实 DeepSeek 的 mock spike，比较 T1/T2：

1. mock bridge 返回固定 `answerText`。
2. 两个拓扑都实现 `/v1/models` 和 `/v1/chat/completions` 的最小 happy path。
3. 比较改动面：
   - 需要改哪些脚本。
   - 启动/停止/health 是否清晰。
   - 单元测试复杂度。
   - 对现有 `/search` 的回归风险。
4. 如果 T1 能用独立 module 控制改动面，默认优先 T1；如果 T1 对现有 bridge 风险过高，再采用 T2。

### 3.3 调用链

T1 同进程形态：

```text
n8n / Langflow
  -> http://<bridge-host>:8789/v1/chat/completions
  -> scripts/deepseek-web-bridge.js route
  -> scripts/deepseek-openai-adapter.js
  -> searchQuery(...)
  -> Chrome Remote Debugging 9333
  -> chat.deepseek.com
```

T2 独立进程形态：

```text
n8n / Langflow
  -> http://127.0.0.1:8790/v1/chat/completions
  -> scripts/deepseek-openai-facade.js
  -> http://127.0.0.1:8789/search
  -> scripts/deepseek-web-bridge.js
  -> Chrome Remote Debugging 9333
  -> chat.deepseek.com
```

### 3.4 边界原则

adapter 只负责协议适配，不负责：

- 浏览器自动化。
- prompt 业务策略。
- 股票盯盘落库。
- 会议室成员/工作流状态。
- n8n / Langflow workflow 执行。
- n8n / Langflow 容器网络配置。
- Langflow SSRF allowlist 策略。

这样可以保持当前会议室主链稳定，同时把外部工具接入面收敛到 OpenAI-compatible 协议。

## 4. API 设计

### 4.1 `GET /health`

返回 adapter 和下游 bridge 的可用性。

示例：

```json
{
  "ok": true,
  "mode": "deepseek_openai_facade",
  "facadePort": 8790,
  "bridgeUrl": "http://127.0.0.1:8789/search",
  "bridge": {
    "ok": true,
    "busy": false,
    "cooldownRemainingMs": 0
  }
}
```

如果 bridge health 不通，facade health 返回 `ok=false`，HTTP status 可保持 `200`，让上层能读取诊断；也可增加 `?strict=1` 返回 `503`。

### 4.2 `GET /v1/models`

返回最小 OpenAI-compatible model list。

```json
{
  "object": "list",
  "data": [
    {
      "id": "deepseek-web-bridge",
      "object": "model",
      "created": 1783737600,
      "owned_by": "ai-meeting-room"
    },
    {
      "id": "deepseek-chat",
      "object": "model",
      "created": 1783737600,
      "owned_by": "ai-meeting-room"
    }
  ]
}
```

`deepseek-chat` 作为兼容别名，便于 Langflow 的 DeepSeek 组件默认值和 n8n 里常见模型名继续使用。

### 4.3 `POST /v1/chat/completions`

支持字段：

```json
{
  "model": "deepseek-web-bridge",
  "messages": [
    {"role": "system", "content": "你是..."},
    {"role": "user", "content": "请分析..."}
  ],
  "temperature": 0.7,
  "max_tokens": 1200,
  "response_format": {"type": "json_object"},
  "stream": false
}
```

第一版忽略但接受：

- `temperature`
- `top_p`
- `presence_penalty`
- `frequency_penalty`
- `seed`

第一版显式拒绝：

- `stream=true`
- `tools`
- `tool_choice`
- `functions`
- `function_call`
- `POST /v1/responses`

拒绝原因：当前 DeepSeek 网页 bridge 只能返回最终正文，无法稳定生成 OpenAI tool call 结构或 SSE 增量流。

`/v1/responses` 第一版返回明确错误，而不是 silent fallback：

```json
{
  "error": {
    "message": "Responses API is not supported by deepseek-web-bridge adapter v1. Disable Responses API and use /v1/chat/completions.",
    "type": "unsupported_feature",
    "code": "responses_api_unsupported"
  }
}
```

## 5. 消息转换规则

### 5.1 content flatten

OpenAI messages 可能出现 string 或 multi-part content。第一版支持：

1. string content：原样使用。
2. array content：拼接 `type=text` 的块；非文本块标记为未支持。

如果检测到图片、音频、文件块：

```json
{
  "error": {
    "message": "deepseek-web-bridge facade only supports text messages in v1",
    "type": "unsupported_feature",
    "code": "multimodal_unsupported"
  }
}
```

### 5.2 prompt 拼装

将 messages 拼装为一个 `query`：

```text
【系统指令】
<所有 system / developer 消息>

【历史上下文】
用户: ...
助手: ...
工具: ...

【用户任务】
<最后一条 user 消息；如无最后 user，则使用全部消息摘要>

【输出约束】
- 只输出本次任务需要的最终内容，不要解释你正在使用网页端。
- 如果任务要求 JSON，必须输出可解析 JSON，不要包 Markdown 代码块。
- 尽量将回答控制在约 <max_tokens> tokens 以内。
```

这与 `MeetingDeepSeekWebBridgeService.buildQuery()` 的既有策略保持一致，但不依赖 Spring Boot。

### 5.3 JSON mode

满足任一条件时传给 bridge：

```json
{"expectedJson": true}
```

条件：

1. `response_format.type === "json_object"`。
2. prompt 中包含明显 JSON-only 约束。
3. model kwargs 或请求扩展字段显式传入 `expectedJson=true`。

## 6. Bridge 请求映射

facade 调用现有 bridge：

```json
{
  "query": "<拼装后的 query>",
  "expectedJson": true,
  "mode": "expert",
  "closeTargetAfter": false,
  "sessionKey": "optional"
}
```

`mode` 决策：

1. 默认 `expert`。
2. 如果 header `x-deepseek-mode: fast`，使用 `fast`。
3. 如果 request metadata / extra body 包含 `deepseek_mode`，优先使用。
4. 仅接受 `fast` / `expert`，其他值回落默认。

`sessionKey` 决策：

1. header `x-deepseek-session-key`。
2. request `metadata.deepseek_session_key`。
3. request `user` 字段可选映射为 session key，但默认不启用，避免把外部用户 ID 直接变成 DeepSeek 会话标识。

## 7. OpenAI 响应映射

成功响应：

```json
{
  "id": "chatcmpl-dswb-20260711-000001",
  "object": "chat.completion",
  "created": 1783737600,
  "model": "deepseek-web-bridge",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "<answerText>"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
  },
  "deepseek_web_bridge": {
    "contentStatus": "success",
    "completionReason": "stable",
    "answerLength": 1234
  }
}
```

`usage` 第一版不做 token 估算，统一返回 0，避免伪造精确计费指标。后续可以加粗略估算字段，但不要冒充真实 token 统计。

如果请求使用 JSON mode，adapter 应在返回前做一次可选 JSON parse 校验：

1. `answerText` 可解析为 JSON：返回成功。
2. `answerText` 不可解析为 JSON：返回 `502 invalid_json`，避免 n8n / Langflow 把脏 JSON 当成功。
3. bridge 已返回 `contentStatus=incomplete`：仍按 `504 incomplete_generation` 处理。

## 8. 错误映射

| 场景 | Bridge / facade 信号 | HTTP | OpenAI error code |
|---|---|---:|---|
| bridge busy | `/search` 429 `bridge_busy` | 429 | `bridge_busy` |
| DeepSeek 未登录 | `/search` 503 `login_required` | 503 | `login_required` |
| Chrome 调试端口不可用 | `/search` 503 `debugger_unavailable` | 503 | `debugger_unavailable` |
| 网页提交失败 | `/search` 502 `submit_failed` | 502 | `submit_failed` |
| 网页生成超时 | `/search` 504 `provider_timeout` | 504 | `provider_timeout` |
| JSON 未完整生成 | result `contentStatus=incomplete` | 504 | `incomplete_generation` |
| 空正文 | no `answerText` | 502 | `empty_answer` |
| 请求太大 | body > limit | 413 | `request_too_large` |
| tool calling | request includes tools | 400 | `tool_calling_unsupported` |
| streaming | `stream=true` | 400 | `streaming_unsupported` |
| Responses API | `POST /v1/responses` | 400 | `responses_api_unsupported` |
| JSON mode 返回不可解析 JSON | JSON parse failed | 502 | `invalid_json` |

错误响应形状：

```json
{
  "error": {
    "message": "DeepSeek web bridge is busy",
    "type": "server_error",
    "param": null,
    "code": "bridge_busy"
  }
}
```

## 9. 安全与运行策略

### 9.1 绑定地址

默认只监听：

```text
127.0.0.1
```

不默认暴露到 LAN 或公网。

如果 n8n / Langflow 运行在 Docker 容器中，容器内的 `127.0.0.1` 指向容器自身，不是宿主机 adapter。此时有三种选择：

1. 让 adapter 监听宿主机可达地址，并强制配置 `DEEPSEEK_OPENAI_FACADE_API_KEY`。
2. 在容器中使用 `host.docker.internal` 或宿主网关地址访问 adapter。
3. 把 adapter 和 n8n / Langflow 放到同一 Docker network，并用 service name 访问。

只要 adapter 不再只绑定 `127.0.0.1`，就必须启用 API key，并明确防火墙/网络边界。

### 9.2 API key

n8n / Langflow 往往要求填写 API key。facade 支持两种模式：

1. `DEEPSEEK_OPENAI_FACADE_API_KEY` 未配置：接受任意 bearer token，但只监听 localhost。
2. 已配置：必须匹配 `Authorization: Bearer <key>`。

这能兼容本地开发，又保留后续安全开关。

如果采用 T1 同进程 adapter，环境变量名称仍可沿用 `DEEPSEEK_OPENAI_FACADE_API_KEY`，但文档和日志中应称为 adapter API key，避免把进程拓扑和协议角色混在一起。

### 9.3 并发

adapter 本身不增加并发，仍尊重底层 bridge 的串行、队列、cooldown。adapter 不重试 `/search`，避免 n8n / Langflow 一次调用在网页端形成多次提交。

### 9.4 日志

adapter 日志只记录：

- request id
- model
- query length
- expectedJson
- mode
- bridge status
- elapsedMs
- answer length

不默认记录完整 prompt 和回答，避免泄露业务内容。

## 10. n8n 接入方式

第一版推荐用 `OpenAI Chat Model`，不要用官方 `DeepSeek Chat Model`。

关键前置：

1. **关闭 `Use Responses API`**。当前 n8n `OpenAI Chat Model` v1.3 默认值是 `true`，第一版 adapter 只支持 Chat Completions。
2. **优先在 OpenAI credential 里把 Base URL 设置为 adapter 地址**。node-level `options.baseURL` 会经过 credential allowed URL / pinned URL 校验；如果 credential 仍是 `https://api.openai.com/v1`，node-level override 可能被拦。
3. **不要连接 tool 节点，也不要启用 built-in tools**。第一版 adapter 会明确拒绝 tools/function calling。
4. 如果 n8n 在 Docker 容器内运行，不要直接填宿主机 `127.0.0.1`，应使用 `host.docker.internal`、宿主网关或同 Docker network 的 service name。

推荐配置：

```text
Node: OpenAI Chat Model
OpenAI credential Base URL: http://127.0.0.1:8790/v1 或 http://host.docker.internal:8790/v1
API Key: local-dev 或 DEEPSEEK_OPENAI_FACADE_API_KEY
Model: deepseek-web-bridge
Use Responses API: false
Streaming: false
Tools: 不连接工具节点
Response Format: text 或 json_object
Timeout: >= 480000 ms
Max Retries: 0 或 1
```

node-level `Base URL` override 可以作为备选，但必须先验证不会被 credential URL restriction 拦截。

为什么不用 n8n 的 `DeepSeek Chat Model`：

它的 DeepSeek credential 默认绑定 `https://api.deepseek.com`，Base URL 不是普通 UI 参数；使用 OpenAI Chat Model 的 Base URL override 改动最小。

后续如果确认长期使用，可以考虑写 n8n community node 或 patch DeepSeek credential，但不是第一步。

## 11. Langflow 接入方式

Langflow 组件有 OpenAI-compatible 接入点，但默认不是“直接填 `127.0.0.1` 就能通”。

关键前置：

1. Langflow 默认 `ssrf_protection_enabled=true`，会阻止 `127.0.0.1` / `localhost` / private IP。
2. 本地开发需要配置 allowlist，例如：

```bash
LANGFLOW_SSRF_ALLOWED_HOSTS=127.0.0.1,localhost
```

3. 如果 Langflow 在 Docker 容器内运行，容器内 `127.0.0.1` 不是宿主机；应使用 `host.docker.internal`、宿主网关地址，或同 Docker network 的 service name，并同步配置 SSRF allowlist。
4. `Stream` 必须关闭。
5. Agent 场景不要启用工具调用；第一版只验证文本/JSON model slot。

满足上述前置后，可以走两种组件：

### 11.1 DeepSeek 组件

```text
Component: DeepSeek
DeepSeek API Base: http://127.0.0.1:8790/v1 或容器可达地址
DeepSeek API Key: local-dev 或 DEEPSEEK_OPENAI_FACADE_API_KEY
Model Name: deepseek-web-bridge 或 deepseek-chat
Stream: false
JSON Mode: 按需开启
```

### 11.2 LM Studio / OpenAI 组件

```text
Base URL: http://127.0.0.1:8790/v1 或容器可达地址
API Key: local-dev
Model: deepseek-web-bridge
```

Langflow 的优势是后续可通过 `LANGFLOW_COMPONENTS_PATH` 加自定义组件，但第一版无需这样做。

## 12. 实施步骤

### Phase 0：只做文档和评审

当前阶段。

输出：

- 本方案文档。
- `challenge` 独立评审。
- `optimize` 独立评审。

### Phase 0.5：拓扑与接入前置补证

编码前必须先补这一步，否则 S2/S3/S4 只能算弱支持。

任务：

1. 做 T1 同进程 adapter module 与 T2 独立 facade 进程的 mock spike。
2. 用 mock OpenAI-compatible endpoint 验证 n8n：
   - OpenAI credential Base URL 指向本地 adapter。
   - `Use Responses API=false` 后只访问 `/v1/models` 和 `/v1/chat/completions`。
   - node-level Base URL override 是否会被 credential restriction 拦截。
3. 用 mock OpenAI-compatible endpoint 验证 Langflow：
   - 默认 SSRF protection 下访问 `127.0.0.1` 是否失败。
   - 加 `LANGFLOW_SSRF_ALLOWED_HOSTS=127.0.0.1,localhost` 后能否访问。
   - 容器场景使用 `host.docker.internal` 或 service name 的可行性。
4. 根据 spike 结果决定采用 T1 或 T2。

Phase 0.5 的通过标准：

```text
OpenAI-compatible adapter 主线保留；
拓扑选择从“推测最优”变成“有 mock spike 证据支持”；
n8n/Langflow 接入文档从“可配置”变成“已知前置条件 + 已验证路径”。
```

### Phase 1：facade 最小可运行

根据 Phase 0.5 决策落地 T1 或 T2。

如果采用 T1，新增/修改：

- `scripts/deepseek-openai-adapter.js`
- `scripts/deepseek-openai-adapter.test.js`
- `scripts/deepseek-web-bridge.js`

如果采用 T2，新增：

- `scripts/deepseek-openai-facade.js`
- `scripts/start-deepseek-openai-facade.sh`

能力：

- `/health`
- `/v1/models`
- `/v1/chat/completions`
- 文本消息转换。
- JSON mode 映射。
- 明确拒绝 streaming / tools / multimodal。
- 明确拒绝 `/v1/responses`。
- 错误映射。
- JSON mode 返回前 parse 校验。

验收：

```bash
node scripts/deepseek-web-bridge.js # T1
# 或 node scripts/deepseek-openai-facade.js # T2
curl http://127.0.0.1:<adapter-port>/v1/models
curl -X POST http://127.0.0.1:<adapter-port>/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer local-dev' \
  -d '{"model":"deepseek-web-bridge","messages":[{"role":"user","content":"用一句话介绍你自己"}],"stream":false}'
```

### Phase 2：单元测试与 mock bridge

新增或更新：

- `scripts/deepseek-openai-adapter.test.js` 或 `scripts/deepseek-openai-facade.test.js`

测试覆盖：

1. `/v1/models` 返回模型列表。
2. 普通 chat 请求正确转换为 bridge `/search`。
3. JSON mode 正确设置 `expectedJson=true`。
4. bridge `contentStatus=incomplete` 映射为 504。
5. `stream=true` 返回 400。
6. `tools` 返回 400。
7. `POST /v1/responses` 返回 400。
8. JSON mode 下不可解析 JSON 返回 502。
9. bearer token 校验。
10. bridge busy 429 透传为 OpenAI error shape。
11. 非 localhost 绑定时必须配置 API key。

### Phase 3：真实 bridge 冒烟

前置：

```bash
scripts/start-deepseek-web-bridge-safe.sh
# T1 不需要额外 facade 进程；T2 需要：
scripts/start-deepseek-openai-facade.sh
```

验证：

1. `/health` 能读到底层 bridge 状态。
2. 文本回答成功。
3. JSON-only prompt 返回可解析 JSON。
4. busy/cooldown 时 n8n / Langflow 能看到明确错误。
5. `stream/tools/responses` 请求都明确失败。

### Phase 4：n8n / Langflow 冒烟

n8n：

1. 启动本地 n8n。
2. 建一个不连接 tools 的最小 workflow。
3. OpenAI credential Base URL 指向 adapter。
4. 关闭 Responses API，不连接 tool 节点。
5. 运行文本和 JSON 两个样例。
6. 从 adapter 日志确认 n8n 只调用 `/v1/models` 与 `/v1/chat/completions`。

Langflow：

1. 启动 Langflow。
2. 配置 `LANGFLOW_SSRF_ALLOWED_HOSTS` 或使用可达 service 地址。
3. 用 DeepSeek 或 OpenAI component 指向 adapter。
4. 运行普通文本和 JSON mode。
5. 不开启 stream。
6. 从 adapter 日志确认 Langflow 只调用 `/v1/models` 与 `/v1/chat/completions`。

### Phase 5：可选增强

仅在 Phase 1-4 稳定后考虑：

1. `/v1/responses` 兼容 n8n 新版 Responses API；如果 Phase 0.5 证明 n8n UI 难以关闭 Responses API，则提前进入 Phase 1。
2. SSE 伪 streaming：等底层完整返回后一次性发出增量事件；仅改善兼容性，不代表真流式。
3. request metadata 控制 sessionKey / mode。
4. MCP tool server：把 DeepSeek Web Bridge 作为工具暴露给 n8n / Langflow agent。
5. n8n community node / Langflow custom component。

## 13. 验收标准

### 13.1 功能验收

1. n8n 在关闭 Responses API、credential URL 指向 adapter、无 tools 的条件下，能通过 OpenAI Chat Model 调用 DeepSeek Web Bridge 并得到文本。
2. Langflow 在 SSRF allowlist/网络拓扑配置正确、关闭 stream、无 tools 的条件下，能通过 DeepSeek/OpenAI/LM Studio component 调用 DeepSeek Web Bridge 并得到文本。
3. JSON mode 下返回可解析 JSON；不完整 JSON 不被标记成功。
4. bridge busy、未登录、超时都有明确错误。
5. n8n / Langflow 调用日志显示只访问 `/v1/models` 与 `/v1/chat/completions`。

### 13.2 边界验收

1. 不支持 streaming 时必须明确失败。
2. 不支持 tool calling 时必须明确失败。
3. 不支持 Responses API 时必须明确失败。
4. adapter 不绕过 bridge 的串行/cooldown。
5. 不把 prompt/answer 全量写入默认日志。
6. 不修改 n8n / Langflow 源码。
7. 非 localhost 访问必须启用 API key。

### 13.3 回归验收

1. 原会议室 `MeetingDeepSeekWebBridgeService` 不受影响。
2. 原 `scripts/deepseek-web-bridge.js` `/search` 协议不破坏。
3. 股票盯盘技术分析仍可直接使用原 bridge。
4. 如果采用 T1，同进程新增 `/v1/*` 路由不改变 `/health` 和 `/search` 现有响应形状。

## 14. 已知风险

1. DeepSeek 网页端不是稳定 API，页面结构、登录态、风控都可能变化。
2. n8n / Langflow 的 agent 场景如果需要 tool calling，第一版 facade 不能满足。
3. OpenAI-compatible 客户端可能默认开启 streaming 或 Responses API，需要在配置中关闭。
4. DeepSeek 网页端串行和 cooldown 会让 n8n 自动化吞吐很低。
5. token usage 无法真实统计。
6. JSON mode 依赖 prompt + bridge 的完整 JSON 检测，不能等同 API 级 schema guarantee。
7. Langflow 默认 SSRF protection 会阻止 loopback/private IP；不配置 allowlist 时本地接入会失败。
8. Docker 场景中 `127.0.0.1` 容易指错网络命名空间；需要显式说明 host/network 地址。
9. 独立进程拓扑会增加 start/stop/health/log 管理成本；同进程拓扑会增加现有 bridge 回归风险。

## 15. 关键替代方案

### A1：直接修改 n8n / Langflow 源码

暂不采用。维护成本高，升级冲突多，且两边都已经能吃 OpenAI-compatible endpoint。

### A2：把 OpenAI-compatible endpoint 写进 Spring Boot

暂不采用。n8n / Langflow 会额外依赖会议室后端运行；而 bridge 本来就是 Node + Chrome 自动化，本地 facade 与它放在同一运行层更轻。

### A3：同进程扩展 `scripts/deepseek-web-bridge.js`

不是直接把大量逻辑塞进 bridge 文件，而是把 OpenAI-compatible 转换逻辑拆到独立 module，再在 bridge 进程挂 `/v1/*` route。该方案已升级为 Phase 0.5 需要优先比较的 T1，因为它可能比独立进程少一个端口和生命周期管理面。

如果 mock spike 证明：

1. T1 不改变 `/search` 行为；
2. T1 测试隔离清晰；
3. T1 启动/停止/health 更简单；

则第一版优先采用 T1。

### A4：只把 bridge 作为 n8n HTTP Tool / Langflow API Request

可作为辅助，但不是主接入方式。它只能把 DeepSeek 当工具，不是 language model，无法自然接入 AI Agent / Chain 的 model slot。

### A5：独立 Node facade 进程

仍是候选方案 T2。只有在 Phase 0.5 证明同进程扩展对现有 bridge 回归风险明显更高，或确实需要独立重启/独立日志/独立安全边界时，才作为第一版落地。

## 16. 评审用 Audit Packet

```json
{
  "objective": {
    "primary": "让 n8n 和 Langflow 以最小源码侵入方式接入现有 DeepSeek Web Bridge",
    "secondary": [
      "保护当前会议室和股票盯盘主链不被破坏",
      "清晰暴露不支持能力，避免静默错误",
      "保留未来扩展到 Responses API、MCP、custom node/component 的余地"
    ]
  },
  "constraints": [
    "第一版不改 n8n / Langflow 源码",
    "第一版不改变现有 /search 协议",
    "第一版只支持低并发本地调用",
    "不能假装支持 streaming / tool calling / embeddings",
    "需要能被 n8n OpenAI Chat Model 和 Langflow DeepSeek/OpenAI/LM Studio 组件调用"
  ],
  "claims": [
    {
      "id": "S1",
      "statement": "当前 DeepSeek Web Bridge 不是 OpenAI-compatible API，而是本地 /search 查询协议。",
      "type": "fact",
      "dependsOn": ["E1", "E2"],
      "supportingEvidence": ["E1", "E2"],
      "hiddenAssumptions": [],
      "falsifiers": [
        "仓库中已经存在可直接给 n8n/Langflow 使用的 /v1/chat/completions endpoint",
        "现有 /search 已经完全兼容 OpenAI Chat Completions 请求/响应结构"
      ],
      "alternativesConsidered": [],
      "confidence": "high"
    },
    {
      "id": "S2",
      "statement": "n8n 具备可指向本地 OpenAI-compatible adapter 的接入点，但第一版可用路径必须满足 credential Base URL 指向 adapter、关闭 Responses API、无 tools，并通过 mock/冒烟验证。",
      "type": "inference",
      "dependsOn": ["E3", "E7", "E8"],
      "supportingEvidence": ["E3", "E7", "E8"],
      "hiddenAssumptions": [
        "n8n 运行环境能访问 adapter 地址；Docker 场景不把 127.0.0.1 误认为宿主机",
        "使用者能在节点中关闭 Responses API",
        "OpenAI credential 的 Base URL 可以配置为本地 adapter 地址"
      ],
      "falsifiers": [
        "OpenAI credential 不能保存或测试本地 Base URL",
        "关闭 Responses API 后仍调用 /v1/responses",
        "node-level Base URL override 被 credential URL restriction 拦截且 credential Base URL 也不可改"
      ],
      "alternativesConsidered": [
        {
          "id": "A1",
          "summary": "修改 n8n DeepSeek credential 或写 community node",
          "rejectedBecause": "第一版维护成本更高；只有当 OpenAI credential/Base URL 路径验证失败时才进入备选"
        }
      ],
      "confidence": "medium"
    },
    {
      "id": "S3",
      "statement": "Langflow 具备 OpenAI-compatible base_url/api_base 接入点，但本地 loopback/private 地址默认会受 SSRF protection 影响，必须配置 allowlist 或调整网络拓扑后再验证。",
      "type": "inference",
      "dependsOn": ["E4", "E5", "E9", "E10"],
      "supportingEvidence": ["E4", "E5", "E9", "E10"],
      "hiddenAssumptions": [
        "LANGFLOW_SSRF_ALLOWED_HOSTS 或等效配置能在用户运行方式中生效",
        "Langflow 运行环境能访问 adapter 地址；Docker 场景不把 127.0.0.1 误认为宿主机",
        "Langflow 组件 stream 可关闭，且目标 flow 不要求 tool calling"
      ],
      "falsifiers": [
        "SSRF allowlist 配置后仍不能访问 adapter",
        "组件强制 streaming 或强制使用不兼容 endpoint",
        "容器网络无法访问宿主机或同网络 adapter"
      ],
      "alternativesConsidered": [
        {
          "id": "A2",
          "summary": "通过 LANGFLOW_COMPONENTS_PATH 写自定义组件",
          "rejectedBecause": "第一版无需自定义组件即可验证模型接入"
        }
      ],
      "confidence": "medium"
    },
    {
      "id": "S4",
      "statement": "第一版应优先做 OpenAI-compatible adapter 作为公共协议层，而不是改 n8n/Langflow 源码或让它们直接调用 /search。",
      "type": "recommendation",
      "dependsOn": ["S1", "S2", "S3", "E6"],
      "supportingEvidence": ["E1", "E2", "E3", "E4", "E5", "E6"],
      "hiddenAssumptions": [
        "n8n/Langflow 的第一版目标是 model slot 文本/JSON 调用，而不是完整 agent tool calling",
        "统一协议适配比维护两个外部项目 fork 更低成本"
      ],
      "falsifiers": [
        "n8n/Langflow 当前版本都无法稳定使用自定义 OpenAI-compatible Base URL",
        "用户第一版目标必须是 tool-calling agent，而不是文本/JSON model slot",
        "现有 /search 协议本身已被 n8n/Langflow 作为模型节点直接支持"
      ],
      "alternativesConsidered": [
        {
          "id": "A3",
          "summary": "把 OpenAI-compatible route 挂到现有 bridge 同进程",
          "rejectedBecause": "未拒绝；已作为 T1 进入 Phase 0.5 spike"
        },
        {
          "id": "A4",
          "summary": "在 Spring Boot 中增加 /v1 endpoints",
          "rejectedBecause": "会让外部工具依赖会议室后端，而不是只依赖 bridge 层"
        }
      ],
      "confidence": "medium"
    },
    {
      "id": "S7",
      "statement": "T1 同进程 adapter 与 T2 独立 Node facade 哪个更适合作为第一版，目前尚未被证明，编码前必须通过 Phase 0.5 mock spike 决策。",
      "type": "recommendation",
      "dependsOn": ["S1", "S4", "E1", "E6", "E11", "E12"],
      "supportingEvidence": ["E1", "E6", "E11", "E12"],
      "hiddenAssumptions": [
        "mock spike 足以暴露启动/停止/health/路由隔离的主要工程差异",
        "现有 stack 脚本是本地使用者的重要运行入口"
      ],
      "falsifiers": [
        "已有未记录的运行约定强制要求独立 facade 进程",
        "已有未记录的运行约定强制要求所有 bridge endpoint 同进程",
        "mock spike 不能覆盖实际 n8n/Langflow 调用路径"
      ],
      "alternativesConsidered": [
        {
          "id": "T1",
          "summary": "同进程 bridge adapter module",
          "rejectedBecause": "未拒绝；如果 route 隔离清晰且不破坏 /search，优先采用"
        },
        {
          "id": "T2",
          "summary": "独立 Node facade 进程",
          "rejectedBecause": "未拒绝；如果同进程回归风险更高或需要独立生命周期，则采用"
        }
      ],
      "confidence": "high"
    },
    {
      "id": "S5",
      "statement": "第一版必须显式拒绝 streaming 和 tool calling，而不是做假兼容。",
      "type": "recommendation",
      "dependsOn": ["S1", "E6"],
      "supportingEvidence": ["E1", "E6"],
      "hiddenAssumptions": [
        "DeepSeek 网页 bridge 无法稳定产生 OpenAI SSE 增量事件和 tool_calls 结构",
        "明确失败比返回不完整或误导性结构更有利于 workflow 调试"
      ],
      "falsifiers": [
        "现有 bridge 已有稳定增量输出事件流",
        "n8n/Langflow 的目标场景必须第一版就使用 tool calling"
      ],
      "alternativesConsidered": [
        {
          "id": "A5",
          "summary": "等底层完整返回后伪造 SSE",
          "rejectedBecause": "只能改善兼容性，不能代表真正流式，放到后续增强"
        },
        {
          "id": "A6",
          "summary": "把 tool calls 转成 prompt 内 JSON action",
          "rejectedBecause": "会偏离 OpenAI tool calling 语义，容易让 agent 执行链错误"
        }
      ],
      "confidence": "medium"
    },
    {
      "id": "S6",
      "statement": "该方案第一版可作为本地低并发验证路径，但不能宣称达到生产级 n8n 自动化模型服务。",
      "type": "recommendation",
      "dependsOn": ["S1", "S4", "S5", "E6"],
      "supportingEvidence": ["E6"],
      "hiddenAssumptions": [
        "DeepSeek 网页端登录态、页面结构和账号风控仍然是不稳定外部依赖",
        "n8n 企业自动化的吞吐和可靠性要求通常高于单 Chrome 网页自动化"
      ],
      "falsifiers": [
        "已有长期稳定压测证据证明网页 bridge 可承受 n8n 生产负载",
        "用户目标仅限手动低频实验，不涉及自动化可靠性"
      ],
      "alternativesConsidered": [
        {
          "id": "A7",
          "summary": "直接使用 DeepSeek 官方 API",
          "rejectedBecause": "这不满足复用现有网页 bridge、降低 API 开销的当前目标"
        }
      ],
      "confidence": "high"
    }
  ],
  "conclusion": {
    "statement": "当前证据支持先做 OpenAI-compatible adapter 作为 n8n/Langflow 接入 DeepSeek Web Bridge 的最低侵入主线；但 T1 同进程还是 T2 独立进程必须先做 Phase 0.5 mock spike 决策。第一版只应定位为本地低并发文本/JSON 模型接入，不应宣称支持 streaming、tool calling、Responses API 或生产级高并发。",
    "dependsOn": ["S1", "S2", "S3", "S4", "S5", "S6", "S7"]
  }
}
```

## 17. 代码/事实证据

- E1 `scripts/deepseek-web-bridge.js:139`、`:170-176`、`:260-266`：当前 Node bridge 暴露 health、读取 `/search` 请求中的 `query`、`expectedJson`、`mode`、`sessionKey` 等字段，并绑定 `127.0.0.1:8789`。直接证据。
- E2 `src/main/java/com/aimeeting/room/service/MeetingDeepSeekWebBridgeService.java:115-122`、`:180-181`：会议室后端将 system/user prompt 拼接为 `query`，设置 `expectedJson`，再读取 `result.answerText`。直接证据。
- E3 `/tmp/codex-research-ai-meeting/n8n/packages/nodes-base/credentials/OpenAiApi.credentials.ts:35-40`、`:78-82`：n8n OpenAI credential 自带 Base URL，并用该 URL 测 `/models`。直接证据。
- E4 `/tmp/codex-research-ai-meeting/langflow/src/lfx/src/lfx/components/deepseek/deepseek.py:49-55`、`:114-123`：Langflow DeepSeek 组件暴露 `api_base` 并传入 `ChatOpenAI(base_url=...)`，且使用 SSRF-protected client kwargs。直接证据。
- E5 `/tmp/codex-research-ai-meeting/langflow/src/lfx/src/lfx/components/lmstudio/lmstudiomodel.py:74-80`、`:109-123`：Langflow LM Studio 组件按 OpenAI-compatible base URL 接本地模型服务，并使用 `ChatOpenAI(base_url=...)`。直接证据。
- E6 `scripts/deepseek-web-bridge.js:7-18`、`:112-125`、`:185-228`：bridge 内部串行 `inFlight`、`waitForFreeSlot()`、cooldown、hard timeout，说明底层是低并发网页自动化。直接证据。
- E7 `/tmp/codex-research-ai-meeting/n8n/packages/@n8n/nodes-langchain/nodes/llms/LMChatOpenAi/LMChatOpenAi.node.ts:250-256`、`:825-827`：n8n OpenAI Chat Model v1.3 的 `Use Responses API` 默认 `true`，启用后会设置 `fields.useResponsesApi=true`。直接证据。
- E8 `/tmp/codex-research-ai-meeting/n8n/packages/@n8n/nodes-langchain/nodes/llms/LMChatOpenAi/LMChatOpenAi.node.ts:761-772`：node-level `options.baseURL` 会经过 `assertCredentialAllowsUrl`，否则使用 credential `url`。直接证据。
- E9 `/tmp/codex-research-ai-meeting/langflow/src/lfx/src/lfx/services/settings/groups/security.py:19-34`：Langflow 默认 `ssrf_protection_enabled=true`，并说明会阻止 private IP、localhost、metadata endpoint，可用 `ssrf_allowed_hosts` 放行。直接证据。
- E10 `/tmp/codex-research-ai-meeting/langflow/src/lfx/src/lfx/utils/ssrf_protection.py:44-50`、`:497-503`：Langflow SSRF blocked ranges 包含 `127.0.0.0/8`，阻断时提示加入 `LANGFLOW_SSRF_ALLOWED_HOSTS`。直接证据。
- E11 `scripts/start-stock-watch-stack.sh:150-165`：现有 stack 启动脚本只管理 DeepSeek bridge，不管理独立 OpenAI facade。直接证据。
- E12 `scripts/stop-stock-watch-stack.sh:68-70`：现有 stack 停止脚本只停止 frontend/backend/deepseek-web-bridge，不管理独立 OpenAI facade。直接证据。

未验证：

- 尚未启动 n8n / Langflow 做真实 UI 冒烟。
- 尚未用 mock endpoint 实测 n8n 在 `Use Responses API=false` 后是否只访问 `/v1/models` 与 `/v1/chat/completions`。
- 尚未用 mock endpoint 实测 n8n credential Base URL 与 node-level Base URL override 的 UI 配置差异。
- 尚未用 mock endpoint 实测 Langflow 在默认 SSRF 与 allowlist 配置下的实际访问结果。
- 尚未做 T1/T2 拓扑 spike，因此不能声称独立 Node facade 或同进程 adapter 已被证明为最优。

## 18. 设计边界

核心 invariant：

```text
现有 /search bridge 协议保持稳定；新增 adapter 只做协议适配，不改变底层网页自动化行为。
```

边界说明：

1. n8n / Langflow 只看见 OpenAI-compatible endpoint。
2. adapter 只看见 OpenAI-compatible request 和 bridge `/search` response。
3. bridge 继续只负责 DeepSeek 网页端会话、模式切换、提交、轮询和完整性检测。
4. 会议室 Spring Boot 继续负责业务编排、证据留痕、股票盯盘和落库。

第一版不跨越的边界：

- 不把 n8n workflow 变成会议室 workflow。
- 不把 Langflow flow 直接写入会议室数据库。
- 不让外部工具绕过 bridge cooldown。
- 不伪造 OpenAI 原生能力。

## 19. 推理图说明

1. E1/E2 支持 S1：现有 bridge 是 `/search` 协议，不是 `/v1/chat/completions`。
2. E3/E7/E8 支持 S2：n8n 有 OpenAI Base URL 接入点，但默认 Responses API 和 URL restriction 是前置风险。
3. E4/E5/E9/E10 支持 S3：Langflow 有 OpenAI-compatible base_url/api_base 接入点，但默认 SSRF 会阻断 loopback/private 地址。
4. S1+S2+S3 推出 S4：与其改两个外部项目，不如提供它们共同能吃的 OpenAI-compatible adapter。
5. E11/E12 支持 S7：独立进程会增加现有 stack 生命周期管理面；T1 同进程也有 `/search` 回归风险，所以必须先 spike。
6. E6 支持 S5/S6：底层网页 bridge 串行、冷却、依赖 Chrome，不适合宣称完整模型 API。
7. S4/S5/S6/S7 合并得出结论：adapter 主线当前证据支持，但进程拓扑和实际 n8n/Langflow 接入路径仍需补证。

会推翻结论的条件：

1. n8n / Langflow 当前版本都无法稳定使用自定义 OpenAI Base URL。
2. Langflow SSRF protection 无法通过 allowlist 或网络拓扑允许 adapter endpoint。
3. 用户第一版目标必须是支持 tool-calling agent，而不是文本/JSON 模型调用。
4. Phase 0.5 证明 T1/T2 两种拓扑都无法在不破坏 `/search` 的前提下提供稳定 `/v1/chat/completions`。
