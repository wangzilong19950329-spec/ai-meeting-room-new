# GEN-10 通用资料搜索与交叉审核

`GEN-10 General Evidence Search` 是复用当前 n8n 实例的通用搜索审核工作流。它接受任意主题的一个问题，不要求股票代码，也不调用东方财富。旧 `MR-60` 保留为会议股票缺口工作流；会议圆桌和自定义自动节点只有在 `AI_MEETING_REVIEWED_SEARCH_ENABLED=true` 时调用 GEN-10，股票监控调用链不受此开关影响。圆桌会议先取东方财富结构化数据，再把其未覆盖的公开事实交给 GEN-10；自定义自动节点按阶段搜索契约提交通用问题。

会议室可通过 `POST http://127.0.0.1:5678/webhook/meeting-evidence-v1` 调用同一工作流，使用 `X-Meeting-Evidence-Token` 鉴权。Webhook 只接受 `requestId`、`question`、`asOf`；请求体内的 `bridgeEndpoint`、`fixtureMode` 不会传给 GEN-10。部署时执行 `sh infra/n8n-stock-watch/scripts/deploy-meeting-evidence.sh`，它会导入凭据、发布工作流并重启 n8n 注册生产 Webhook；再执行 `sh infra/n8n-stock-watch/scripts/configure-meeting-evidence.sh` 同步 Java 客户端凭据并开启受控模式。请在没有其他活动执行时部署。

## 输入

用 n8n `Execute Sub-workflow` 节点按 **Run once for each item** 调用 `GEN10GeneralEvidenceSearch`，每次执行传一个问题：

```json
{
  "requestId": "research-global-token-20261005",
  "question": "Google 官方披露的 2026 年 5 月月度 Token 处理量是多少？",
  "asOf": "2026-10-05"
}
```

默认使用本机 Edge Bridge `http://host.docker.internal:8795/search` 和现有 `SW DeepSeek Facade Header` 凭据，需先确认 Edge Bridge 服务运行。仅 fixture 测试可使用 8794；现有 8793 facade 可由调用方显式指定，其余端点拒绝。不要把凭据写入输入或工作流 JSON。

## 输出及可信度

`status` 为 `CROSS_CHECKED`、`PARTIAL`、`UNVERIFIED` 或 `ERROR`。`claims` 只包含在两次隔离的 DeepSeek 智能搜索中，候选链接及复核链接都被 Bridge 从对应回答页面或本轮 completion 流中的 `TOOL_SEARCH` / `TOOL_OPEN` 结果捕获，且关键字段一致的事实。`PARTIAL` 表示仅部分事实通过，`limitations` 指出其余部分未通过的原因。`audit.trail` 记录每轮候选 ID、链接、审核结论和失败原因。即使审核模型认为问题已覆盖，只要候选中仍有 `REJECT` 或 `UNKNOWN`，本轮不得升级为 `CROSS_CHECKED`。

`provisionalClaims` 单独保存初搜有本轮搜索证明、链接也由 Bridge 捕获，但独立复搜明确回答 `UNKNOWN` 的候选事实。它们标记 `reviewVerdict=UNKNOWN`、`confidenceBand=LOW`、`confidenceScore=0.25`、`confidenceBasis=UNCALIBRATED_POLICY_SCORE` 与具体 `limitation`。0.25 是展示不确定性的固定策略分数，**不是经历史数据校准的真实概率**。独立复搜明确 `REJECT`、观察到字段矛盾，或此前同一 ID 已被明确驳回的候选不得进入此数组；审核调用未完成、超时、身份或搜索证明缺失时也不得进入。`ERROR` 输出的 `claims` 和 `provisionalClaims` 均为空，详情只保留在审计记录中。每个 `provisionalClaims` 数组最多三条候选，原始 `claims` 仍只含通过项。

`CROSS_CHECKED` 只表示通过这套双会话搜索规则，**不表示已读取原文、确认原始出处或消除同模型偏差**。Bridge 捕获的回答链接也不等于网页已打开或链接内容已验证。涉及关键数字时，消费方应保留来源和限定条件；`provisionalClaims` 只能作为明确标注低置信度的待确认材料，不能当作已审核通过的确定事实。

## 行为及停止条件

1. 搜索节点提出最多三条原子事实，每条需要 `id`、陈述和来源链接；统计范围、时间、数值、单位和限定条件按问题需要填写。更宽的问题可由上游拆成多个输入 item。
2. 审核节点在新隔离会话中重新搜索，逐条核对相关字段并寻找反证，同时结合此前已通过的事实判断是否回答了原问题。审核拒绝时，反馈具体问题和所需来源；已通过的事实不再复搜。若事实正确但问题仍未答全，覆盖范围反馈会驱动下一轮补缺口搜索，并拒绝重复已通过的事实。预算耗尽时，即使个别平台事实通过，若“全球总量”等原问题仍无证据，整体也只能是 `PARTIAL`。若审核回答缺少本轮搜索证明，仅重试审核一次。
3. 最多三轮搜索（初搜加两次定点返工或缺少搜索证明时的重试），每轮至多两次审核，总期限十二分钟。正式 HTTP 超时上限为五分钟，同时受剩余总期限约束；剩余不足两秒时不再发起调用。Bridge 最多排队九十秒。五分钟覆盖本机 Bridge 当前九十秒排队、三分钟执行和十五秒中止宽限。测试夹具可特意配置一秒来验证超时路径。
4. HTTP 非 2xx、连接中断、超时、缺少搜索动作、格式错误、链接未被 Bridge 捕获，以及期限耗尽均保守拒收。连接超时可能发生在 Bridge 已提交请求之后，因此**不会自动重复该调用**。
5. 独立安装时工作流默认未发布；`deploy-meeting-evidence.sh` 会发布带 Header 鉴权的 Webhook。Java 会议服务先核对 `requestId`、问题和审核轨迹；`CROSS_CHECKED` 的 `claims` 可作为通过资料，`PARTIAL`、`UNVERIFIED` 中明确 `UNKNOWN` 的 `provisionalClaims` 仅可标记为低置信待核验材料。`ERROR` 或身份、搜索证明无效时拒收，不能把候选事实当作已通过资料。

会议议员推理调用 DeepSeek Bridge 时显式传 `enableSearch:false`，并要求回传 `modeVerified=true`、`searchEnabled=false`；否则拒收推理结果。审核模式下的投票、聚类、摘要、流程节点及审核员也使用所选模型的严格路由，模型不可用时不切换模型。搜索节点仍保持智能搜索开启。

## 之前测试暴露的问题及对应处理

- `MR-60` 的 `symbol` 必须是六位 A 股代码，提示词含“股票”和“不要预测股价”，不能用来回答全球 Token 等通用问题。GEN-10 改为独立的 `question` 合约。
- 临时全球 Token 工作流的 n8n 执行 108 在第一次 `/search` HTTP 调用约四分钟后，以 `The connection was aborted, perhaps the server is offline` 直接结束；它没有执行审核，也没有结构化输出。现用 HTTP `onError: continueRegularOutput` 和响应分类，产生 `ERROR`、`BRIDGE_TRANSPORT_OUTCOME_UNKNOWN`、空 `claims` 与审计记录。
- 后续直接抓网页试验的执行 109 把 n8n 的文本响应错当成 `body`，实际返回字段是 `data`，解析失败；执行 110 修正后成功。GEN-10 对 Bridge 统一要求完整 JSON HTTP 响应，读取 `body`，并在实际 n8n 节点图中验证此契约。
- 真实 Edge 执行 117、119 返回完整 JSON 时，页面尚未渲染对应消息；Bridge 以 `complete_json_network` 返回，导致 `submittedTurnMatched=false`、`searchCount=null` 和 `links=[]`。执行 119 的页面随后手动刷新曾显示“搜索到 20 个网页”，但事后页面观察不能证明当时响应。后续改为把对应 POST 的原始 prompt 与本轮提交的问题匹配，并从同一 completion 流的 `TOOL_SEARCH` / `TOOL_OPEN` 结果提取搜索次数和链接；DOM 未就绪时仍可归属本轮，缺少工具结果时继续拒收。
- 原临时工作流的问题和输出结构写死了“全球 Token”，审核节点无法复用。GEN-10 由调用方提供问题，搜索/审核都使用通用原子事实结构，允许非数字、无固定时间范围的事实。
- Edge Bridge 有时完整返回模型正文，但本轮搜索次数或消息匹配证据缺失。GEN-10 针对此类已收到响应的初搜调用，在总轮次内重新搜索；若发生在审核，保留搜索候选，仅在新隔离会话重试一次审核。预算耗尽或仍无证明则拒收。
- 旧股票缺口流程只针对一个候选事实。通用问题可能涉及多条事实；GEN-10 可单独保留已通过的条目，只对未通过的 ID 做定点返工，预算耗尽时返回 `PARTIAL` 而非把整批误报为通过。
- 初版 GEN-10 曾在“事实正确但原问题未答全”时直接返回 `PARTIAL`。现在审核的覆盖范围缺口也会反馈到下一轮搜索；新事实不能复用已通过的 ID。
- n8n 编辑器显示的 `1000` 可能来自旧执行数据的期限已过，也可能来自夹具测试故意设置的一秒超时；单凭截图无法区分。正式新执行默认五分钟，HTTP 节点在发起调用时按剩余总期限计算本次超时。此前加入的一分钟最低调用窗口已撤回。

执行 108 的四分钟等待与 HTTP 超时设置相吻合，但单凭 n8n 错误不能区分 Bridge 排队、浏览器会话卡住、模型生成缓慢或网络中断。GEN-10 修复的是**工作流的失败处理与通用性**；没有声称修复了 Edge Bridge 的潜在慢请求原因。

2026-10-05 的真实 Edge 调用曾出现初搜成功捕获 21 条搜索记录、但审核缺少本轮消息匹配；后续新版本的实测中，三次初搜都在完整正文后缺少本轮消息匹配和搜索记录，最终按预算返回 `UNVERIFIED`。2026-10-06 通过本轮 completion 工具记录补齐了这个缺口。Edge 的旧页面偶尔会让身份校验的 CDP `Runtime.evaluate` 连续超时；Bridge 此时新开同一账号配置中的页面，验证身份后关闭旧页面并重新绑定会话。单次失败或 `login_required` 不会触发换页绕过账号校验。

## 生成与验证

```bash
node infra/n8n-stock-watch/scripts/render-general-search-workflow.js
node --test infra/n8n-stock-watch/scripts/general-search-domain.test.js infra/n8n-stock-watch/scripts/render-general-search-workflow.test.js
sh infra/n8n-stock-watch/scripts/smoke-test-general-search.sh
```

生成的工作流位于 `workflows/general/GEN-10-General-Evidence-Search.json`。smoke 使用有鉴权的本地 Bridge fixture，并在真实 n8n 节点图中覆盖：非股票问题通过、审核驳回后定点返工、覆盖范围缺口补搜、明确 `UNKNOWN` 低置信输出、明确 `REJECT` 排除、初搜或审核缺少搜索证明时分别重试、HTTP 503、HTTP 连接超时。若 GEN-10 已发布，先用部署脚本更新，再运行 smoke；测试脚本只导入夹具入口并保持原有发布状态。若原本未发布，测试会临时发布并在结束后取消发布。

## 2026-10-06 接入验证

- 已发布受 Header 鉴权保护的会议 Webhook，并在本地 Java 环境开启 `AI_MEETING_REVIEWED_SEARCH_ENABLED=true`；后端已重启。
- Edge Bridge 的独立推理 canary 返回 `modeVerified=true`、`searchEnabled=false`、`completionReason=complete_network`，证明本次非搜索生成实际关闭了智能搜索。
- 修复前真实 Webhook 曾返回 HTTP 200、`UNVERIFIED`，审计为搜索一次、审核零次；缺失的是本轮消息匹配、搜索次数和捕获链接。修复后独立 GEN-10 Webhook 返回 `CROSS_CHECKED`，初搜和独立复核各一次，覆盖范围 `COMPLETE`。完整会议测试的 GEN-10 执行 195 也返回 `CROSS_CHECKED`，同样为一次搜索、一次复核。
- 完整会议测试继续暴露两个下游问题：旧通用提示词偏向近期行业事实；即使问题所需事实已通过，额外未通过候选仍触发复搜；会议推理走旧 `8789` 代理返回请求摘要 409。已分别改成问题优先、依据已通过事实判断覆盖范围，并将会议推理配置到与 GEN-10 相同的专用 Edge Bridge `8795`。`8795` 的关闭搜索调用已实测返回 `searchEnabled=false`、`modeVerified=true`。
- 最终一次性单议员会议 83 的 GEN-10 执行 197 返回 `CROSS_CHECKED`：1 轮搜索、1 轮独立复核、3 条通过事实。两次 Bridge 响应均有 `searchEvidenceSource=completion_tool_results`、`submittedTurnMatched=true`；初搜记录 23 条搜索结果及 23 个链接，复核记录 58 条搜索结果及 50 个链接。会议第 1 轮写入 1 条发言，长度 528 字符，发言证据含 `reviewedSearchStatus=CROSS_CHECKED`，当时 `blockedReason=null`。测试会议与账号随后删除；此测试验证“搜索通过→会议发言”，没有验证后续投票或结论阶段。
- 聚焦 Node 和 Java 测试通过。当时的实现对未通过复核的候选一律拒收；下节记录了之后加入的低置信处理，同时继续拒收传输或证明失败的资料。

## 2026-10-06 低置信发言接入

- 审核完成但独立复搜返回 `UNKNOWN` 时，GEN-10 把候选单列于 `provisionalClaims`，用固定策略分 0.25 和限制说明标注。明确 `REJECT` 或字段不符的候选不进入该数组；Bridge 传输、身份、搜索证明和审核格式错误返回 `ERROR`，不把服务故障当作低置信资料。
- 会议端为 `PARTIAL`、`UNVERIFIED` 保存 `LOW_CONFIDENCE` 缺口状态。议员可以继续用已通过的事实、可用结构化数据和明确标注的待核验假设做条件分析，并参加投票；发言与最终结论增加资料限制提示。这个策略分不是数值为真的概率。
- 本机 n8n 已部署更新后的 GEN-10，23 项 Node 单元测试和真实 n8n 节点图夹具测试通过。夹具实际输出经 Java 审核包校验器验证：通过项接收 1 条、规则分 1；`UNKNOWN` 候选接收 1 条、规则分 0.25；明确反证接收 0 条；HTTP 503 的 `ERROR` 拒收。Java 聚焦测试覆盖数据库落账、失败后恢复发言及低置信投票。真实 DeepSeek 搜索产生低置信包后进入完整会议的路径尚未单独实测。
