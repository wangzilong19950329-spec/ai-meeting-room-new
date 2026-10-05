# GEN-10 通用资料搜索与交叉审核

`GEN-10 General Evidence Search` 是复用当前 n8n 实例的独立子工作流。它接受任意主题的一个问题，不要求股票代码，也不调用东方财富。旧 `MR-60` 保留为会议股票缺口专用工作流；现有会议和股票监控调用链没有自动改接 GEN-10。

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

`status` 为 `CROSS_CHECKED`、`PARTIAL` 或 `UNVERIFIED`。`claims` 只包含在两次隔离的 DeepSeek 智能搜索中，候选链接及复核链接都被 Bridge 从各自的回答界面捕获，且关键字段一致的事实。`PARTIAL` 表示仅部分事实通过，`limitations` 指出其余部分未通过的原因。`audit.trail` 记录每轮候选 ID、链接、审核结论和失败原因。

`CROSS_CHECKED` 只表示通过这套双会话搜索规则，**不表示已读取原文、确认原始出处或消除同模型偏差**。Bridge 捕获的回答链接也不等于网页已打开或链接内容已验证。涉及关键数字时，消费方应保留来源和限定条件；不能把 `UNVERIFIED` 候选送入模型推理当作确定事实。

## 行为及停止条件

1. 搜索节点提出最多三条原子事实，每条需要 `id`、陈述和来源链接；统计范围、时间、数值、单位和限定条件按问题需要填写。更宽的问题可由上游拆成多个输入 item。
2. 审核节点在新隔离会话中重新搜索，逐条核对相关字段并寻找反证，同时结合此前已通过的事实判断是否回答了原问题。审核拒绝时，反馈具体问题和所需来源；已通过的事实不再复搜。若事实正确但问题仍未答全，覆盖范围反馈会驱动下一轮补缺口搜索，并拒绝重复已通过的事实。预算耗尽时，即使个别平台事实通过，若“全球总量”等原问题仍无证据，整体也只能是 `PARTIAL`。若审核回答缺少本轮搜索证明，仅重试审核一次。
3. 最多三轮搜索（初搜加两次定点返工或缺少搜索证明时的重试），每轮至多两次审核，总期限十二分钟。正式 HTTP 调用在准备节点先计算超时：最多五分钟，且至少保留一分钟；若全局期限已不足以容纳一分钟调用，就直接返回 `DEADLINE_EXCEEDED`，不发送请求。Bridge 最多排队九十秒。五分钟覆盖本机 Bridge 当前九十秒排队、三分钟执行和十五秒中止宽限。测试夹具可特意配置一秒来验证超时路径。
4. HTTP 非 2xx、连接中断、超时、缺少搜索动作、格式错误、链接未被 Bridge 捕获，以及期限耗尽均保守拒收。连接超时可能发生在 Bridge 已提交请求之后，因此**不会自动重复该调用**。
5. 工作流默认未发布；调用方要使用时需按其自身部署流程发布。搜索结果不会自动写入会议或股票数据库。

## 之前测试暴露的问题及对应处理

- `MR-60` 的 `symbol` 必须是六位 A 股代码，提示词含“股票”和“不要预测股价”，不能用来回答全球 Token 等通用问题。GEN-10 改为独立的 `question` 合约。
- 临时全球 Token 工作流的 n8n 执行 108 在第一次 `/search` HTTP 调用约四分钟后，以 `The connection was aborted, perhaps the server is offline` 直接结束；它没有执行审核，也没有结构化输出。现用 HTTP `onError: continueRegularOutput` 和响应分类，产生 `UNVERIFIED`、`BRIDGE_TRANSPORT_OUTCOME_UNKNOWN`、空 `claims` 与审计记录。
- 后续直接抓网页试验的执行 109 把 n8n 的文本响应错当成 `body`，实际返回字段是 `data`，解析失败；执行 110 修正后成功。GEN-10 对 Bridge 统一要求完整 JSON HTTP 响应，读取 `body`，并在实际 n8n 节点图中验证此契约。
- 真实 Edge 执行 117、119 返回完整 JSON 时，页面尚未渲染对应消息；Bridge 以 `complete_json_network` 返回，导致 `submittedTurnMatched=false`、`searchCount=null` 和 `links=[]`。执行 119 的页面随后手动刷新曾显示“搜索到 20 个网页”，但不能把这一事后观察归属于已完成的 Bridge 响应。尝试过等待并自动刷新页面；执行 123 仍未得到可用证据，故撤回该改动，GEN-10 保持拒收并在审计记录中输出缺失的证明字段。Bridge 页面渲染/搜索证据采集仍是剩余问题。
- 原临时工作流的问题和输出结构写死了“全球 Token”，审核节点无法复用。GEN-10 由调用方提供问题，搜索/审核都使用通用原子事实结构，允许非数字、无固定时间范围的事实。
- Edge Bridge 有时完整返回模型正文，但本轮搜索次数或消息匹配证据缺失。GEN-10 针对此类已收到响应的初搜调用，在总轮次内重新搜索；若发生在审核，保留搜索候选，仅在新隔离会话重试一次审核。预算耗尽或仍无证明则拒收。
- 旧股票缺口流程只针对一个候选事实。通用问题可能涉及多条事实；GEN-10 可单独保留已通过的条目，只对未通过的 ID 做定点返工，预算耗尽时返回 `PARTIAL` 而非把整批误报为通过。
- 初版 GEN-10 曾在“事实正确但原问题未答全”时直接返回 `PARTIAL`。现在审核的覆盖范围缺口也会反馈到下一轮搜索；新事实不能复用已通过的 ID。
- n8n 编辑器显示的 `1000` 可能来自旧执行数据的期限已过，也可能来自夹具测试故意设置的一秒超时；单凭截图无法区分。正式新执行原本默认五分钟。现在准备节点预先写入 `requestTimeoutMs`，HTTP 节点只读取该值；正式调用若不足一分钟就不启动，避免临近期限的一秒请求。

执行 108 的四分钟等待与 HTTP 超时设置相吻合，但单凭 n8n 错误不能区分 Bridge 排队、浏览器会话卡住、模型生成缓慢或网络中断。GEN-10 修复的是**工作流的失败处理与通用性**；没有声称修复了 Edge Bridge 的潜在慢请求原因。

2026-10-05 的真实 Edge 调用曾出现初搜成功捕获 21 条搜索记录、但审核缺少本轮消息匹配；后续新版本的实测中，三次初搜都在完整正文后缺少本轮消息匹配和搜索记录，最终按预算返回 `UNVERIFIED`，没有进入审核。更深入的网络流诊断在 Bridge 账号身份校验处停滞；同机无凭据请求 `https://chat.deepseek.com/api/v0/users/current` 返回 HTTP 429。HTTP 429 本身不能证明已登录浏览器的校验请求也收到 429，但表明该端点当时存在访问限制。网络流搜索结果结构尚未取得，不能据此绕过证据门槛。

## 生成与验证

```bash
node infra/n8n-stock-watch/scripts/render-general-search-workflow.js
node --test infra/n8n-stock-watch/scripts/general-search-domain.test.js infra/n8n-stock-watch/scripts/render-general-search-workflow.test.js
sh infra/n8n-stock-watch/scripts/smoke-test-general-search.sh
```

生成的工作流位于 `workflows/general/GEN-10-General-Evidence-Search.json`。smoke 使用有鉴权的本地 Bridge fixture，并在真实 n8n 节点图中覆盖：非股票问题通过、审核驳回后定点返工、覆盖范围缺口补搜、初搜或审核缺少搜索证明时分别重试、HTTP 503、HTTP 连接超时。测试脚本在结束时取消发布 GEN-10，且不会覆盖已发布的同名工作流。
