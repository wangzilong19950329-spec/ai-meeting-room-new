# MR-60 缺口资料搜索与交叉审核

`MR-60 Evidence Gap Cross-Search` 是可被其他 n8n 工作流调用的子工作流。上游先从东方财富接口获取结构化行情和财务字段，再把**东方财富没有覆盖的单个事实缺口**传给 MR-60。MR-60 不修改会议、股票监控或东方财富数据。

## 输入与输出

用 n8n 的 **Execute Sub-workflow** 节点调用 `MR60EvidenceGapCrossSearch`，每个 item 传一个缺口。多个缺口用 `Run once for each item`；某个缺口的返工不会重搜其他缺口。

```json
{
  "requestId": "meeting-123-round-1-project-capacity",
  "symbol": "300750.sz",
  "gap": {
    "id": "project_capacity",
    "question": "该储能项目签约规模是多少、属于框架协议还是已交付？"
  }
}
```

输出的 `reasoningPacket.verifiedFacts` 只包含审核通过的事实；`audit.trail` 留存每次搜索、审核及驳回原因。会议议员只能消费 `reasoningPacket`，不能把 `audit.trail` 中的待核候选当成事实。

```json
{
  "status": "UNVERIFIED",
  "reasoningPacket": {
    "verifiedFacts": [],
    "limitations": ["project_capacity: SEARCH_BUDGET_EXHAUSTED"]
  },
  "audit": { "attempts": 3, "searchCalls": 3, "reviewCalls": 3, "trail": [] }
}
```

## 节点行为

1. 搜索节点用 DeepSeek bridge `/search` 提出一条结构化候选事实和搜索链接。
2. 审核节点使用 `forceNewSession=true`、`isolatedSession=true` 和另一段提示词独立搜索；要求数字、单位、日期及“框架/签约/交付”等状态逐字段吻合。
3. 审核驳回时，只把该缺口的具体原因返回下一次搜索。最多两次定点复搜，即每个缺口最多三次搜索和三次审核。
4. 搜索与审核均要求 bridge 回报搜索已开启、搜索次数大于零、独立会话、完整 JSON、新生成回答和捕获的引用链接。提供者错误、链接未捕获、字段不符、超时或预算耗尽均输出 `UNVERIFIED`，且不产生推理事实。

工作流不读取引用链接的原文。这是两次 DeepSeek 搜索的交叉检查；`VERIFIED` 表示符合这套审核规则，并不等于已独立核对原始公告。两次搜索可能共享模型偏差。若以后需要更高可信度，可在不改动会议推理接口的前提下增加原文核验层。

`deadlineAt` 为入口时间后 12 分钟。每次 HTTP 请求超时取剩余预算与 240 秒中的较小值；超时后不能接受迟到的 PASS。该工作流仅负责事实搜索与审核，不提供议员观点，也不在模型失败时生成降级观点。

每次 bridge 请求最多排队 90 秒。单账号 bridge 完成搜索后有 45 秒冷却期，审核请求必须能等过冷却期；排队仍超时则输出 `UNVERIFIED`，不会把未审候选交给推理。

## 安装与测试

工作流使用现有本地 `SW DeepSeek Facade Header` 凭据，连接 `http://deepseek-facade:8793/search`。生成并导入：

```bash
node infra/n8n-stock-watch/scripts/render-meeting-evidence-workflow.js
docker compose -f infra/n8n-stock-watch/docker-compose.yml --env-file infra/n8n-stock-watch/.env run --rm n8n-import import:workflow --input=/workflows/meeting/MR-60-Evidence-Gap-Cross-Search.json --activeState=false
```

本地测试：

```bash
node --test infra/n8n-stock-watch/scripts/meeting-evidence-domain.test.js infra/n8n-stock-watch/scripts/render-meeting-evidence-workflow.test.js
sh infra/n8n-stock-watch/scripts/smoke-test-meeting-evidence.sh
```

smoke 脚本启动临时 8794 fixture 服务，并在 n8n 中运行三个缺口：一个先驳回错误的“已交付 120 GWh”，定点复搜后通过“12 GWh 框架协议”；另一个连续驳回三次后输出 `UNVERIFIED`；第三个模拟 HTTP 503。它临时发布 MR-60 以允许子工作流调用，结束时取消发布。fixture 中的数字和链接都是虚构测试数据。

HTTP Request 节点的 `Never Error` 必须放在 `options.response.response.neverError`，否则非 2xx 会中断工作流，无法进入拒收路径。

本地真实提供者 canary 可以传 `bridgeEndpoint: "http://host.docker.internal:8795/search"` 和 `edgeBridgeCanary: true`，指向独立 Edge bridge；该入口只用于本机测试，默认工作流仍走 `deepseek-facade:8793`。专用 Edge 会话需要单独登录，普通 Edge 会话不会共享登录状态。

在仓库根目录运行 `bash scripts/start-meeting-deepseek-edge-bridge-safe.sh` 可启动该本地 bridge。它从 `~/.aimeeting-room/local.env` 读取已有 bridge 凭据，使用独立的 `~/.aimeeting-room/deepseek-web-meeting-edge-profile`，不会改动 8791 股票监控 bridge 的 Chrome 资料。

当前模块已导入本地 n8n，默认保持未发布。Java 会议流程尚未接入 MR-60；这是信息审核模块的独立交付。生产使用前还需上游缺口生成与下游冻结证据包的接口。
