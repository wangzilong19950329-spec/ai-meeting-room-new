## Why

现有股票盯盘已具备事实快照、单次模型任务、冻结请求、程序风险结构和连续验证基础，但评估真值、运行覆盖、任务语义与实用效果尚未闭合。用户是短线交易者，希望重点验证高位风险辅助，同时并行研究主力意图和价格预测；本变更先建立可信的成绩单，再验证能否形成增量价值，而不是继续堆提示词或重写系统。

## What Changes

- 修复预测成熟度与价格尺度合同，保存版本化结果证据；不再用未完成日线或无法对齐的价格评分。
- 对存量预测追加独立评估版本，保留原预测和原结果；增加全范围统计、不可评分原因和事件级分母，保留用户指定的 `RETURN_SIGN` 复核。
- 复用现有调度、demand、canonical task、Bridge receipt，补充业务新鲜度、阶段阻塞、积压和研究游标状态；实际恢复前先核实部署与故障链。
- 明确当前状态、意图假设、未来方向、下行风险四种语义，分别保存来源、时间、版本和可用性。
- **BREAKING（仅新合同灰度）**：新增响应合同允许逐期限 `ABSTAIN` 与逐能力结果状态；不再把 `SIDEWAYS` 当“不知道”，也不因某个预测能力缺失而丢掉其他合法结果。旧 v6 冻结任务仍按旧合同解析。
- 沿用现有价格失效研究 V1 及冻结协议；DeepSeek “典型派发/出货”建立独立、只读原回复的前向研究，不混入 V1，不额外调用模型。
- 意图继续并行，以竞争假设及可观察预言进行证伪；`SUPPORTED` 不升级为真实主体意图确认。
- 用冻结基线、事件去重、时间外实验和成本/延迟约束决定是否增加历史、持续性、新闻等输入；未经验证不发布为交易信号或校准概率。

## Capabilities

### New Capabilities

当前没有已发布的股票领域 OpenSpec spec；以下是对现有实现的增量正式化，不代表从零开发。

- `stock-watch-outcome-integrity`: 成熟度、价格基准、版本化评估、存量重评、全范围统计及审计。
- `stock-watch-runtime-continuity`: 业务链新鲜度、阻塞分段、研究进度和真实窗口运行验收。
- `stock-watch-task-semantics`: 来源、时间、概率语义、分能力状态、拒答与版本兼容。
- `stock-watch-downside-research`: 价格失效与模型风险措辞分队列的前向验证、风险标签与执行边界。
- `stock-watch-intent-hypotheses`: 竞争意图假设、可观察预言、反证与非真值语义。
- `stock-watch-experiment-governance`: 无泄漏样本、基线、消融、校准、效果发布门及成本控制。

### Modified Capabilities

无。现有未完成的会议工作流 OpenSpec change 不在本次范围内；不修改其工件。

## Impact

- 主要复用 `StockWatchPredictionService`、`EastmoneyMarketDataService`、`StockWatchAnalysisProjectionService`、`StockWatchTechnicalAnalysisService`、事实/active-test owner、`StockWatchRuntimeStatusService` 和现有 price-failure 服务。
- 增量影响预测 Mapper/entity、三处 schema、技术分析 API、股票详情的预测/运行/研究视图；保留当前游标分页和按日期分组。
- 新增预测评估版本子记录；新增隔离的模型风险研究样本存储。它们是各领域 owner 的数据，不是新的全局状态机或第二套 provider 提交账本。
- 不换 Java/Vue/Bridge，不接券商，不建订单 hard veto，不自动买卖，不引入 Qlib 在线依赖，不自动增加模型调用。
- 分 P0/P1/P2 独立发布，采用 additive schema、schema readiness、版本冻结、灰度读取与回滚 fence。收益、概率校准、真实意图和实际避损均需后续证据，不作为方案已成立的事实。
- 本轮只生成方案、规格和任务清单；不修改业务源码、数据库或服务运行状态。
