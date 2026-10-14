# 股票盯盘可信评估与短线风险辅助改动方案

日期：2026-10-05。状态：设计草案，可拆分实施；不是实施完成或效果证明。代码基线 HEAD 为 `f28683a0ce4b358ed7cc1c31080aff6cecce2056`，另有大量既有 dirty/untracked 改动，实施时必须再次冻结逐文件基线。

推荐路线：**先修成绩单与运行链，再独立验证高位风险，主力意图继续并行，最后用对照实验优化 DeepSeek。** 不将历史几次命中升级为已经可靠的避险策略。

后续目标澄清：原 bug 修复全部保留；用户已明确人工选股入场，持仓风险识别与人工退出为最高产品优先级，完整预测为辅助。新增功能见 [持仓风险助手](../add-stock-watch-position-risk-assistant/design.md)，跨两条线顺序以 [统一路线](../../../docs/design/stock-watch-unified-delivery-plan-2026-10-05.md) 为准。本文件原v0与冻结协议保留，不因新功能视为已修复。

## Context

### 1. 当前事实与证据边界

本轮只读 SQL 核验：本地 `ai_meeting` 有预测 2,999 条，其中 EVALUATED 2,540、PENDING 459，最新预测日期 2026-08-14；事实 1,935 条，最后创建时间 2026-08-14 21:02:38；价格失效 assessment 和 ledger 都为 0。这证明本库覆盖不足，**不证明唯一故障在 scheduler 或 Bridge**。

| 现状 | 当前指针 | 本方案处理 |
| --- | --- | --- |
| 有未来日线即结算，无完成门；qfq 结果价直接除源报价 | `StockWatchPredictionService.evaluateOne`；`EastmoneyMarketDataService.fetchDailyBars` | P0 统一成熟度、尺度、证据和存量重评 |
| 只扫描 PENDING，旧 EVALUATED 不复核 | `StockWatchPredictionMapper.xml:82` | 新版本独立扫描，不改旧终态含义 |
| 全分页列表配第一页 summary | `StockWatchPredictionService.historyPayload`；`predictionHistory.mjs`；`StockWatch.vue:2727` | 后端全范围聚合，明确统计口径和筛选范围 |
| v6 普通结论由可信 renderer 生成；模型预测另存；模型意图为审计材料 | `StockWatchTechnicalAnalysisService.applyTrustedV6Payload` | 保存和显示来源，不把所有结论归于 DeepSeek |
| 历史已经 eligible-only 过滤 | `buildHistoryPromptContext` | 保留回归测试，核实部署，不重新开发相同修复 |
| DAY 人设称 30 分钟分析，但不是明确的完成 30m OHLCV 合同 | `buildPrompt`；`toPromptMarketDataPayload`；`buildBundle` | 改任务语义，不先购买/重建高频行情 |
| 已有独立价格失效研究和不可变 entry | `StockWatchPriceFailureResearchService/Rule` | 先验收现有设施，禁止混入模型原文 |
| active-test 成熟且未触发失效可为 SUPPORTED | `StockWatchFactPersistenceService.evaluateExistingTests` | 不称“意图确认”，补可观察预言研究 |

历史研究基线来自 [统计报告](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/docs/research/stock-watch-existing-data-findings-2026-10-04.md)、[质量边界](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/docs/research/stock-watch-existing-data-quality-2026-10-04.md) 与 [整体诊断](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/docs/research/stock-watch-usability-priorities-2026-10-05.md)。353 个可重建 T1 样本的旧三分类命中 27.2%、方向子集表现和高置信组结果只能用于暴露问题与设计候选，不能外推当前全量准确率。

### 2. 领域原则

同期价格与订单流的联系不能证明真实主体意图或未来收益。意图是待证伪假设，方向是固定期限预测，下行风险是可观察事件，交易效果还需要可用时点与执行情景。[Cont et al.](https://arxiv.org/abs/1011.6402)

方向分类、所选方向概率、场景价格带与风险事件概率是不同合同；校准需要明确事件和样本外验证。[Gneiting and Raftery](https://sites.stat.washington.edu/raftery/Research/PDF/Gneiting2007jasa.pdf)

市场完成状态不能只写死 15:00/16:00。港股部分证券有收市竞价；日历、标的机制和供应商最终数据状态须共同验证。[HKEX Trading Mechanism](https://www.hkex.com.hk/Services/Trading/Securities/Overview/Trading-Mechanism?sc_lang=en)

时间切分优于随机预测行切分，但普通 `gap` 不自动解决多股票及重叠标签泄漏，本方案要求显式清除跨窗口事件。[TimeSeriesSplit 文档](https://scikit-learn.org/stable/modules/generated/sklearn.model_selection.TimeSeriesSplit.html)

## Goals / Non-Goals

### Goal Contract v0

来源：用户“出一个详细的改动方案”，继承本会话短线避险、主力意图并行及专业领域设计要求。

- G1：交付覆盖整体领域设计和局部问题的分阶段方案，包含明确代码边界、数据合同、存量、验收、发布与回滚。
- G2：风险辅助可以独立开发，不依赖意图成功或新增模型调用；现有意图、方向、规则、通知与 canonical 任务不能被破坏。
- G3：将工程可信性、预测增量、风险事件命中、纸面避损和真实净收益分别验收；不得承诺尚未验证的准确率提升。
- G4：明确首期必须做、后续研究做、暂不做及待确认产品约束；方案能按依赖拆成实施批次。

### Goal Contract v1（用户后续明确澄清）

来源：用户“选股和入场人工决策，想提前或识别危险后快速卖出”“完整预测只是优先级不最高”“之前bug与新增助手都要做”，并确认本轮只完成统一设计。

- 原 G1-G4、M0-M6 与79项实施义务继续有效；新增助手不替代结果真值、运行链和存量修复。
- M5/M6主要产品效用改为已有持仓的风险与人工退出；避免新买降为可保留的独立次要研究情景。
- 原v0中“首期优先避免新买”的推定及基于该推定的产品优先级失效；不改历史证据、RETURN_SIGN、V1规则、研究盲测或原预测合同。
- 新助手保护条件可独立开发，但仍需其数据/持仓/交付门；候选推断风险效果继续独立验收，不因用户用过个例就视为总体有效。
- 本轮依旧只修改设计工件，不修改业务源码、数据库、服务、模型提交或通知。

### Non-Goals（继续有效）

- 本轮不改业务代码、不迁移数据库、不重启服务、不重新调 DeepSeek。
- 不建自动交易、订单 hard veto、账户主体识别或操纵认定。
- 不重做 Spring/Vue/行情系统、不将 Qlib 嵌入在线链、不新建全局 run manifest 状态机。
- 不用新的同源多模型辩论代替前向验证，不预设 70% 阈值有效。
- 不静默回填历史、不改原始预测、旧评估、V1 冻结规则或用户已明确的 RETURN_SIGN 语义。

## Decisions

### D01. 保留现有领域 owner，按任务分层

优先级对应不同收益，不将所有改动都包装成“提高模型准确率”：

| 优先级 | 改动 | 预期直接收益 | 不能先声称 |
| --- | --- | --- | --- |
| P0 必做 | 成熟度/尺度/重评/全范围统计、真实运行链 | 成绩单可解释、样本可用、消除工程假成绩与停滞 | DeepSeek推理本身更准确 |
| P1 重点 | 明确任务与拒答、独立风险采集、意图预言 | 减少任务混淆，验证更适合短线的风险辅助候选 | 已有稳定预测或避险优势 |
| P2 有证据再做 | 持续性/背离/合格历史/事件消融、校准、纸面执行 | 找到真正有增量且负担可接受的版本 | 堆字段必然提升准确率 |
| 暂不做 | 全量重写、强专家人设、更多辩论、未评估历史原文、事后抬高confidence阈值 | 当前无优先价值证据，且增加成本/锚定/复杂度风险 | 它们永远无效，或已测得收益为零 |

```text
报价/日线/分钟来源 + 标的机制/交易日历
                    |
         事实观察 + 数据资格/来源/时间
                    |
       +------------+------------+
       |            |            |
   意图假设       价格预测       下行风险研究
   active tests   Prediction    priceFailure V1
   可观察预言     评估版本      模型措辞独立队列
       |            |            |
       +------ 各自结果/基线 ------+
                    |
          只读统计/人工辅助/纸面情景
```

| 数据/行为 | 唯一 owner | 不允许 |
| --- | --- | --- |
| canonical 行情与派生事实 | 现有 market-data + fact observation | 研究元数据改变 factHash 或生产 observationKey |
| 模型不可逆提交 | `StockWatchAnalysisTaskService` + Bridge receipt | demand、研究任务或邮件再建提交真相；UNKNOWN 盲目重发 |
| 分能力结果投影 | `StockWatchAnalysisProjectionService` | 不同能力自行更改已冻结请求 |
| forecast 与它的评估版本 | `StockWatchPredictionService` | 新服务重建第二套 T+N owner |
| 模式/意图反证 | 现有 active test/result | 用预测涨跌替换意图 verdict |
| 价格失效研究 | 已有 priceFailure owner | 导入 DeepSeek 原文/历史回填 |
| 模型措辞风险样本 | 独立只读 research owner | 写入 priceFailure V1、生产通知触发事实 |
| 统计、UI、邮件 | 各 owner 的只读投影 | 回写 verdict 或代替评估 owner |

允许抽取被两个 owner 实际共用的纯函数，例如完成日线选择、价格尺度校验与收益计算；禁止引入“通用预测风险意图表”或独立可变总账。大服务不因行数多全量重构，只提取本次需要的深模块。

替代方案比较：继续在一个自然语言结论里混用三个目标改动少但无法验收；全量重写切换风险高；分 owner 增量方案优先，代价是必须维护版本与清楚的接口。

### D02. 首期任务与时间合同

新增显式 `taskDefinitionVersion`，将调度频率与观测尺度分开：`cadenceMinutes` 只表示触发频率，`inputGranularity` 描述真实数据，不再声称截取 15 条趋势就是完成 30m K 线。

| 任务 | 首期定义 | 参考价与结果 | 不代表 |
| --- | --- | --- | --- |
| 当前状态描述 | 形成时点已知的价量、资金代理与程序结构 | 无未来命中率 | 买卖建议 |
| T1/T5 方向 | 源交易日之后第 1/5 个交易所交易日的收盘价格变化 | 模型形成前冻结 source quote 的一致尺度 | 从收到信号可交易价格算收益 |
| 意图研究 | 互相竞争的解释及未来可观察行为预言 | 每个预言固定窗口与反证 | 账户级吸筹/派发真值 |
| 语言风险研究 | 可用后至预先固定 T1 窗口的价格低点风险 | 冻结 entry 代理、完成窗口 | 实际避免亏损 |

新评估版本按交易所日历确定目标日，不以“恰好下载到 N 根股票日线”替代 N 个交易日。停牌/缺 bar 不把 T1 自动挪到数周后；目标日缺数据要等待或不可评分。旧基于未来 bar 数的结果保留为旧版本，不在新统计混用。W1/M1 的 5/20 交易日含义继续显式记录，不声称一定等于自然周/月。

时间字段至少区分：`quoteSourceAt`、`factObservedAt`、`requestPreparedAt`、`providerReceivedAt`、`projectionCommittedAt`、`signalComputedAt`、`signalPublishedAt`、`notificationSentAt`、`evaluatedAt`。统一 UTC epoch/ISO 存储并带市场时区，展示可转本地时间；缺失时间不得以当前时钟补成历史事实。

`researchAvailableAt = max(已验证回复可读时点, 研究信号计算完成时点)`；用户可用时点另取实际发布/送达证据。后台晚处理旧回复不回填为当时可用预警。首期不依赖消息已读来声称用户收到。

新方向主统计要求结果在窗口第一交易时段开始前可用；迟到到已经进入预测窗口的回复单独标为 `LATE_RESULT`，不混入主样本。方向研究从源价评分与执行研究从可交易代理评分分别保存，不能互换。

最小数值例：源价 10，目标日完成收盘 9.4，一致尺度收益 -6%；UP 按 RETURN_SIGN 为背离，DOWN 不背离。收到结果前已跌到 9.4，则不能据此声称能在 10 卖出避损。目标日仍在交易时，即使接口已有 close=9.4，也保持未成熟。

### D03. 预测评估完整问题链（P0）

#### 3.1 完成日线与资格

由 market-data 边界提供 `EvaluationBarsEnvelope`，而不是给评估器裸 `List<Map>`：标的、交易日历/机制版本、抓取时间、来源、调整口径、bars、每根完成证据、质量原因和完整 digest。内部先在这条链使用类型 DTO，不进行全项目 Map 替换。

评分资格顺序固定：

1. 原始预测合同、source date/quote 和来源可识别。
2. 目标交易日可按已验证日历计算；处理节假日、半日市、停牌和标的适用机制。
3. 必要窗口已结束，供应商 bar 在结束后取得且满足已核验完成规则。只有日期或固定时钟不充分；无法确认记录 `BAR_COMPLETION_UNVERIFIED`。
4. 窗口必要 OHLC 有限、顺序合法，high/low/open/close 一致，来源/市场匹配且无未来时间。
5. source quote 与结果价属于相同价格尺度，转换证据完整。
6. 一次纯计算生成结果，由 forecast owner 幂等写入版本记录。

已在后续交易日重取的目标日数据可以用于历史重评，但须记录抓取 vintage；第一次终结后发现供应商修订，只追加 revision，不覆盖已冻结结果。

#### 3.2 价格基准

主事件定义为 `QUOTE_BASIS_PRICE_RETURN_V1`：在源报价单位上的价格变化，不含交易成本，不自动等于含现金分红的总收益。目标价带与失效价也使用同一源报价单位。

首期优先使用验证过的不复权价格与明确的同单位窗口。企业行动跨窗、不同调整口径、供应商切换或无法验证尺度时不评分，原因如 `PRICE_BASIS_UNVERIFIED`、`CORPORATE_ACTION_UNRESOLVED`。只有具备可追溯、覆盖整段窗口的转换因子/企业行动合同，才启用事件尺度转换；禁止默认乘 1，禁止把 `previousClose / 某根 qfq close` 代理当生产通用真值。

实施前先验证当前接口能否提供这个合同。若免费来源无法给充分证据，先发布“可信子集 + 其余不可评分”；不能为了全量覆盖降级真实性。暂不采购数据，采购需要另行确认。

#### 3.3 评估版本数据

建议新增 `stock_watch_prediction_evaluation`，归 PredictionService 管理，而非新 evaluator owner：

| 字段组 | 最小内容 |
| --- | --- |
| 身份 | id、prediction_id、evaluation_version、revision、result_hash；unique(prediction_id, evaluation_version, revision) |
| 合同 | outcome_definition_version、direction_policy_version、calendar_version、price_basis_version、evaluator_version |
| 状态 | WAITING_MATURITY / WAITING_DATA / UNSCORABLE / SCORED，reason_codes，next_retry_at，attempt_count，last_attempt_at |
| 时间/标的 | market、symbol、forecast_source_date、target_date、window_start/end、available_at及其quality |
| 证据 | source quote 引用、bar snapshot JSON 或安全 artifact 引用、source/adjustment/digest、完成证据、转换证据、captured_at |
| 结果 | close/high/low、return、actual_direction、direction_hit、range_close_hit、range_extrema_overlap、invalidation_observed、selected_event_brier、scored_at |
| 审计 | provenance_quality、supersedes_evaluation_id、gmt_create/modify、row_version |

每个 revision 的等待信息可 CAS 更新；一旦 SCORED/UNSCORABLE 终结，其合同、证据和结果不可修改。重评产生新 revision，并链接被替代记录。`selectedEventBrier` 只在所选方向事件概率有效时计算；缺 probability 不补 0.5，均值分母仅含有分数的事件。它不是三分类概率向量 Brier。

新增结果名 `rangeExtremaOverlap`，明确只是窗口极值与目标带重叠，不证明实际触价、触价顺序或成交。日线只有失效价曾被覆盖的信息；需要顺序的结果为 `ORDER_UNKNOWN`，不能给胜负交易标签。

旧 `stock_watch_prediction` 的 forecast 字段和原 evaluation 原样保留。新合同字段采用 additive nullable 列或版本化 snapshot；投影在明确切换后读取选定评估版本，保留 `legacyEvaluation` 对照。不向旧 actual_* 列镜像写新分数，避免双写不同真相。

#### 3.4 存量修复与选择器

- 只读盘点所有状态，而不是仅 PENDING：输出质量分桶和预测 id 清单，不先删除“异常收益”。
- 按 horizon/market/源版本拆批追加；先 dry-run，再真实写入。每批有 batch id、范围、版本、输入哈希、计数与错误原因。
- 缺时间、源报价或尺度时标 `UNSCORABLE`；研究重建代理另标 `RECONSTRUCTED_PROXY`，不进入可信主成绩单。
- 重跑同批次不新增相同终结结果；必须显式新 revision 才接受新数据 vintage。
- pending 重试按到期、next_retry_at 和分页游标取样；缺失很久的个股不堵死后续已到期预测，跨实例靠唯一约束/CAS。
- 查询旧结果与新结果时返回 `evaluationVersion`、可评分分母、缺失原因和重评数量，不悄悄把旧命中率改掉。

### D04. 全范围统计与接口（P0）

保留 `/stock-watch/technical-analysis/predictions` 的 `beforeDate + beforeId` 游标和旧字段。迁移期 `summary` 明确 `scope=PAGE`，新 UI 不再把它当全范围；新增 `GET /stock-watch/technical-analysis/prediction-statistics`。列表与统计共用服务端 filter 解析：symbol/market、dateFrom/dateTo、horizon、direction、evaluationVersion、provenance、eventPolicy。

新统计必须从全范围查询聚合，不依赖客户端已加载页数。返回 `scope=FILTERED_ALL`、`asOf`、`filterDigest`、`metricsDefinitionVersion`、`evaluationVersion` 和实际分母。列表返回 `snapshotMaxId`，多页固定该上界；统计使用同一上界和明确的评估 `asOf`，避免期间新记录/重评导致数字跳动。

默认看事件级结果，同时可切换原始记录级审计。生产每个 canonical task 的原始记录全部保留；主研究事件按 `market+symbol+sourceDate+horizon+taskDefinitionVersion`，选该日第一个事实合格的计划机会，先选机会、再看回复是否及时和有效；该机会失败/迟到/拒答不能用后来的成功回复顶替。机会由既有需求及 frozen task 关联派生，单独报告手工/异动，不随机把重叠 T5 当独立样本。

前向阶段由现有 scheduler/demand owner 在模型提交前记录计划能力、资格及跳过原因；不是新 provider ledger。实际到 due 却连 demand 都没有的槽位由运行新鲜度检查报告，不从成功预测倒推完整机会池。没有历史策略版本/计划证据的存量只报 `OBSERVED_RECORD_COHORT`，不宣称全历史计划机会覆盖率。

公开分母至少包括 planned opportunities、data-ineligible、provider-failed、late、abstained、invalid-capability、not-matured、matured-waiting-data、unscorable、scored、具有概率分数数量。有效性命中率分母为 scored；输出覆盖率分母包含计划合格机会，拒答不记成方向错，但不能从覆盖率消失。

保留 RETURN_SIGN：UP 且收益<0 / DOWN 且收益>0 才背离，收益=0 不背离；SIDEWAYS 不进方向分母。新评估可信收益优先，旧版本按原合同；禁止用缺失值=0。HORIZON_V2 与 LEGACY_1PCT 不混算概率分数；统计清楚区分规则命中和 RETURN_SIGN 不背离频率。

验收：例如同一筛选 304 条记录，不同 pageSize 都给 304 的记录级统计；事件数可小于 304，但必须显示选择规则。点击某个统计指标，列表过滤后样本 id/分母和它一致。

### D05. 运行连续性（P0，先诊断再修）

复用 `StockWatchRuntimeStatusService` 和既有 scheduler ownership，不新建运行状态机。新增只读 freshness/progress 投影：最新 fact/source time、最新 demand/task/analysis/prediction、provider receipt、成熟评估时间、各阶段积压年龄/原因、研究 cursor/max fact 差距及最近扫描。

区分：配置已启用、进程存活、能力 ready、实际成功、新鲜业务链、结果已成熟。查询失败返回 UNKNOWN，不返回 0 或“健康”。非交易日、用户暂停和尚未到 due 不误报停机；根据市场日历和持久化策略判新鲜度。

实施顺序：

1. 核对实际 listener 二进制/启动时间与工作区、配置、schema gate、ownership、个股策略。
2. 沿 fact -> demand -> canonical task -> exact submissionKey 的 Bridge ledger -> receipt -> SUCCESS analysis -> prediction -> mature evaluation 逐段找断点。
3. 对现有 priceFailure 检查扫描是否运行、ruleSpec hash、cursor CAS、事实资格与排除原因。游标落后与 ledger=0不是同一种故障。
4. 仅修确认断点；Bridge busy/QUARANTINED/重复账号不凭 health 归为唯一原因。已提交 UNKNOWN 先核对原 task/receipt，禁止按恢复方案重复发送。
5. 在正常交易窗口实际验收新事实、及时模型结果、独立研究扫描与次日成熟评估。可用固定旧数据测代码，但不是新鲜运行证明。

运行验收不能只给 `/health ok`。记录模型提交次数、失败率、p50/p95 延迟、积压、缺失原因。验收窗口推荐至少两个相连有效交易日，T5 还需等满其窗口；这是工程烟测，不是统计效果样本量。

### D06. 新响应合同与概率语义（P1）

建议引入独立 `response_contract_version=7`，不把所有其他版本跟着改名。冻结任务继续保存当前 envelope、schema/prompt/feature/方向规则和远程不可控参数边界。旧 prepared v6 用 v6 parser；新 contract 只对新任务生成生效，沿用原 canonical observation 的单提交约束，不因改版本对同一 observation 再调一次模型。

新 forecast 每个必需 horizon 仍要一个结果项，但项可以：

```json
{
  "horizon": "T1",
  "decision": "ABSTAIN",
  "reasonCodes": ["INSUFFICIENT_DIRECTIONAL_EVIDENCE"],
  "direction": null,
  "probability": null,
  "targetLow": null,
  "targetHigh": null
}
```

`FORECAST` 才要求合法 UP/DOWN/SIDEWAYS、有限正价和合法价格带；概率可空，非空必须在 [0,1]，新合同不靠 clamp 修正非法概率。`ABSTAIN` 不能携带冲突方向/目标价，不能用 SIDEWAYS 替代。`forecastDecision` 属于版本化能力结果；拒答、缺失或能力非法保存在分析的 `capabilityResultsJson`，不伪造价格预测行，也不为拒答放宽现有 prediction.direction/target 约束。仅合法 FORECAST 进入预测表。

能力状态 `VALID / ABSTAINED / INVALID / UNAVAILABLE` 与任务提交状态分离。合法 root、trusted base 及至少一个合法能力结果（VALID或ABSTAINED）可投影成功，另一个 horizon/intent 不合格要保留显式结果与原文；root JSON 无法解析、身份/版本/证据引用不可信仍整体 INVALID。合法的全期限拒答也可以完成任务，此时输出覆盖明确为0，没有预测行；所有模型能力均INVALID/UNAVAILABLE时不伪装模型成功。某能力失败不能制造有效结果，也不能进行第二次 LLM 修复。代码测试和 API 必须把“task 完成”和“某能力可用”分别断言。

所有数值信心携带 `sourceKind=MODEL_REPORTED|RULE_HEURISTIC|CALIBRATED_MODEL`、事件、期限、校准状态和版本。普通界面将旧 probability 命名为“模型自报概率（未校准）”；规则 confidence 标“规则分数”，不是正确率。默认不以其阈值作为新的风险提醒准入条件。

targetLow/High 首期明确 `bandKind=SCENARIO_BAND`，不是置信区间；可以统计收盘落带频率和带宽，但不声称 80%/95% 覆盖。后续若试验固定 80% 预测区间，需新 intervalContract 预先固定覆盖目标、评分和模型输出约束，不能给存量带强贴覆盖标签。

market 与 instrumentType 必须进入任务角色/资格，HK 不再隐含使用 A 股涨跌停常识。供应商“主力”字段标为资金代理，写明单位和已核验口径；尚未核实的接口口径记 UNKNOWN。泛用的 9.5%/1 亿阈值先只纠正机制误用和语义，标准化阈值优化留到实验，不擅改所有存量 GENERAL 提醒。

### D07. 独立短线风险研究（P1，可并行开发）

#### 7.1 轨道 A：沿用已有价格失效 V1

版本 `RESEARCH_HIGH_POSITION_PRICE_FAILURE_V1`，当前 ruleSpec hash `f5811c387c6bb922f14927808add85823ed1b163b3f8e51f4a15e031d6ca38e7`。保留：无历史回填、首个股/源日信号、最多 3 个同刻合格对照、10 分钟匹配窗口、冻结 entry、120 个合格源日期且至少 30 个成熟 signal 后允许解盲。**解盲资格不等于效果合格。**

它明确禁止 DeepSeek/raw_response/mainForceIntent/notification/position 输入。先修运行和状态证据，不改规则阈值或解盲条件。现有 nextObservedDate、POST_CLOSE 与 entry 代理不是交易所成交真值；若最终日历、停牌、价格尺度或时点审查要求改变其标签/entry/控制匹配语义，必须启动 V2，保留 V1/hash 与结果，不在原版本偷偷改。

#### 7.2 轨道 B：模型“派发/出货”措辞队列

新增隔离 owner，例如 `StockWatchModelRiskResearchService` 与 `stock_watch_model_risk_research_sample`。只消费已经持久化的原始回复及来源事实，先做确定性版本化提取，不为提取追加模型请求。

研究协议 `MODEL_DISTRIBUTION_WARNING_RESEARCH_V1` 先冻结，再收集未来新回复：

- 来源固定为 MODEL_RAW_RESPONSE，不从 trusted renderer 标题或服务器意图画像冒充 DeepSeek 判断。
- 词汇/否定/引述/时态/强弱规则明确：例如“典型高位派发/压制性派发/出货意愿明确”是候选；“不是派发”“尚不能确认派发”“历史曾派发”不得按当前强警告计入。模糊/冲突输出单独 `AMBIGUOUS`，人工复核不看未来价格。
- 保存 raw-response hash、原文字符位置/短片段、extractorVersion、analysis/task/fact 引用、source time、provider receipt、parse 完成与真实可用时间、exclusionReason。API 只暴露脱敏摘要，原文沿已有授权审计接口。
- 记录每条可提取分析，包括 NO_WARNING、AMBIGUOUS、PARSE_FAILED、迟到；不能只存命中的句子然后宣称召回率。
- 冻结股/目标风险窗口的第一个及时强警告为主事件，其他同日/同窗警告作为重复引用，不增加独立样本。盘中与收盘后、周末形成但下一交易日前可用的信号分层，不简单删除所有盘后信号。
- 后台扫描旧回复仅 EXPLORATORY；旧文字曾存在不等于新风险功能当时发布。新增功能的可用时点不得回填成 quote 时间。
- 模型措辞不进入 V1 价格失效账本，不修改生产 canonical hash，不作为触发 L0 告警的确定性事实。

建议研究存储最小合同如下，不扩展生产prediction表为风险通用表：

| 存储 | 最小字段与约束 |
| --- | --- |
| 专属protocol/扫描状态 | protocol_version、spec_json/hash、正式cutoff、extractor_version、last_consumed_id、row_version；版本内spec不可改，旧回复扫描与正式cutoff分开 |
| `stock_watch_model_risk_research_sample` | analysis/task/fact/response引用、sourceKind、extractor_version、extraction_status、短片段位置、source/computed/available/published时间及quality、frozen_features/baselines、eligibility_reasons、duplicate_of；unique(protocol_version, source_receipt_id, extractor_version) |
| 主事件去重 | primary_signal_key为nullable unique，仅首个合格警告持有；包含protocol/market/symbol/target_window，NO_WARNING等保留完整记录，不占警告去重键 |
| 专属outcome子记录 | sample_id、outcome_definition_version、revision、frozen_entry引用与价格、目标窗口、bar/path来源及hash、status/reasons、worstReturn/closeReturn/upside、scored_at；unique(sample_id, outcome_version, revision)，终态不可改 |

输入样本和终结结果分别冻结，缺数据重试可CAS；供应商修订要追加outcome revision。研究扫描失败不回滚已经成功的业务分析；通过既有receipt/id幂等再消费，不能为研究重发模型。必要的新schema全部归该研究owner，不形成第二套全局任务状态。

首期研究主事件建议：`T1_MIN_RETURN_FROM_ENTRY_PROXY_LE_MINUS_5_V1`，不是严格的路径最大回撤。`worstReturnPct=(T1 eligible low / frozen entry price - 1)*100`。entry 为计算完成后第一个合格观察价；跨收盘取下个有效开盘代理但需来源证据。只从 entry 之后算低点；若 entry 落在目标日盘中，没有之后路径就不能拿全天 low（可能发生在 entry 前）评分。缺数据记不可评分。

这个事件和 V1 价格失效的既有结果不自动相同。允许复用纯计算工具，但分别保存 outcomeDefinitionVersion 和数据资格。盘中剩余当日风险作为次要独立窗口 `REMAINDER_SESSION`；必须有完成分钟/更细路径，未具备则不评分，不能用当日 OHLC倒推。

模型队列协议**不直接照搬**轨道A的120日期/30信号门。该门是A既有冻结协议，不是对所有风险研究的通用样本量定理。B在正式收集前根据最小有用增量、误报预算、保守事件率与日期/个股相关性确定样本量或精度目标、最小覆盖日期和固定解盲规则，并包含足够对照/无警告合格机会；不能看到几次命中就提前停止。数据不足可以按预先规定延长，不能边看绩效边改终点。这不改变轨道A的既有门。

#### 7.3 对照、指标与发布

轨道 B 的简单基线必须在同一可用时点、股票集合和事件窗口计算：价格位置/波动基线、纯价格失效、价格+资金代理。使用当时已有的特征，不能用已知后续表现选 matched control；控制不足保留样本并报告，不补未来可见的股票。

主报告给精确率、与匹配基线的风险率差/相对风险及不确定性；次报告给召回率、机会覆盖率、NO_WARNING 风险率、误报、预警延迟、盘中/盘后分层、同股同窗重复数。召回率只对有完整风险结果的预先定义观察池成立，不称市场全量召回。按日期/股票事件处理相关性；小样本不使用独立行假设包装极窄区间。

风险语言显示为研究候选，不自动产生邮件或买卖提示。后续普通提醒上线仍走现有通知能力/冷却/去重，未经新准入协议不让模型警告直接打开 `RISK_PATTERN_REQUIRED` 门。生产提醒需另一次产品确认和效果门，不能因通过本地烟测开启。

#### 7.4 纸面执行情景（P2，非券商自动执行）

优先区分“不建立新仓”与“已有且依法可卖持仓”。没有真实持仓/计划买入记录时只能构造明确的标准情景，不计算个人实际节省金额。使用信号发布后的下一合格价格、适用卖出限制、不能成交/排队、费用与滑点假设；基准策略固定，报告避免的下跌与错过上涨机会成本。

日线 high/low 同时跨止盈止损只能记录顺序未知或保守边界；绝不推定理想顺序。供应商快照为 `EXECUTION_PROXY`，没有委托/成交证据不叫实际收益或实际避损。

### D08. 主力意图研究继续并行（P1/P2）

保留当前 v6 的审计-only 模型意图与服务器弱证据画像，普通 UI 不恢复“模型已确认派发/吸筹”。新增研究投影绑定原 task，而不是再调模型。

每个候选假设保存 hypothesisVersion、来源、竞争解释、evidenceRefs、可观察预言、期限、失败条件及替代解释。例如“下跌+流入”可同时列承接与被动买盘解释；预言可以是未来两个完成窗口价格相对低点改善且资金代理持续，而不是直接把未来上涨标签命名为吸筹正确。

程序只接受已注册 metricPath/operator/window 的有限预言模板；模型不能提供任意可执行表达式、改阈值或写测试 verdict。先验证旧 test，再创建新 test，不能用同一 observation 自证；需要正向预言的模板应有正向 predicate，不将“未达到 invalidation”当正向成立。

现有 active-test verdict 和旧数据不改名回写；API/研究报告把 SUPPORTED 展示为“该程序测试获支持/观察期未触发既定反证”，附规则定义。若没有账户级真值，仅报告预言兑现率、证伪率、不可判断率、覆盖率、不同解释区分力，**不报告真实主力意图准确率**。进入 prompt 的 history 仍需 eligible-only、同版本和窗口资格。

R01-R04 是 observable risk pattern，不与模型意图混算；“典型派发警告的下跌命中率”属于 D07，不是这里的真实身份真值。该线研究和风险线可同时跑，互不作为发布前提。

### D09. DeepSeek 增量实验与概率校准（P2）

先修真值/运行，后看输入增量。预设顺序：

| 版本 | 内容 | 问题 |
| --- | --- | --- |
| A0 | 简洁、完整的价格/资金代理/成交/位置事实 | 基础状态能提供多少时间外价值 |
| A1 | A0 + 固定持续性/背离特征 | 背离是否提升风险或预言区分力 |
| A2 | A1 + 同版本 eligible 历史摘要 | 历史是否有真正增量，是否增加锚定 |
| A3 | 前一合格版本 + 按任务相关的公开事件 | 事件的增量能否覆盖成本/延迟 |

不要一次改完特征、提示词、标签和模型后归因。实验版本固定事件、输入 snapshot hash、输出合同、session policy、FULL/DELTA、模型路由和实际可获参数。网页模型版本/隐含上下文不可固定时明确记录，不宣称严格可重复。

生产每个 canonical observation 仍最多一次提交。默认以连续前向影子设计逐次验证；若做同事件多版本模型消融，须另行批准调用预算并隔离 research namespace/会话/存储，不能把多个输出灌回同一生产 task、预测或通知。离线重放已存响应只能测 parser/计分，不能证明不同输入会得到不同模型答案。

冻结时间外切分，以同源事件成组；清除训练与测试跨期重叠标签，T5等按各事件真实窗口处理；预测失败/拒答也保留。先留出最后一期不用于选规则，发现不支持时允许候选失败，不在同一留出集循环寻优。

所选方向概率以固定二元事件进行 Brier/可靠性分桶，输出每桶样本量、实测频率及区间，不混市场/期限/方向规则/模型合同；必要时合并桶，数据不足不拟合。若未来输出三分类概率向量，必须另立合同，和旧 selected-direction 分数分开。校准拟合用训练/验证期，评估用未见未来期，所有边界预先冻结。

效果门同时看：对基线的配对增量、概率或价格带质量、机会覆盖、失败/拒答、运行延迟、调用次数/成本。准确率提高但覆盖骤降、极晚预警或只有高波动样本不能自动发布。

每个正式实验只冻结一个主事件与主对照；协议在收集前确定最小有用增量、误报/覆盖/延迟上限、不确定性方法和多次比较处理。建议统计门是预设区间对基线的保守增量仍超过最小有用增量，业务门是误报与机会成本在用户接受范围内。具体数字需从效用和初始事件率推导，不以30条或70%概率拍板。门未冻结、区间过宽或未见稳定增量时输出 `INSUFFICIENT_EVIDENCE` / `NO_INCREMENTAL_EDGE`，不以“看起来不错”发布。

### D10. 代码与存储改动边界

以下是预计文件边界，不是声称这些文件已修改；新类名可在实现时按既有模式小幅调整。

| 工作包 | 主要现有文件/边界 | 新增最小设施 | 主要回归 |
| --- | --- | --- | --- |
| P0 结果真值 | `StockWatchPredictionService`、`EastmoneyMarketDataService`、Prediction Mapper/entity | 评估 DTO、纯计算器、evaluation 子表/Mapper | 完成门、尺度、原方向规则、旧读者 |
| P0 统计 | `StockWatchTechnicalAnalysisController`、PredictionService/Mapper、`StockWatch.vue`、`runtime/predictionHistory.mjs` | statistics 接口/共享筛选与版本选择 | 游标、304多页、RETURN_SIGN、样本筛选一致 |
| P0 运行 | RuntimeStatus、scheduler/demand/task/Bridge既有投影 | freshness/progress 字段及诊断脚本 | 暂停、非交易日、UNKNOWN、单提交 |
| P1 任务合同 | TechnicalAnalysisService、AnalysisProjection、analysis entity/Mapper、trusted renderer 的元数据 | v7 parser、capabilityResults、decision/time/source 元数据 | 冻结 v6、部分能力失败、重放与事务 |
| P1 风险研究 | 现有 priceFailure 服务保持 V1；model receipt/analysis只读 | 模型警告研究样本与专属协议/扫描状态 | 隔离、无额外模型、迟到/否定/entry冻结 |
| P1/P2 意图 | FactPersistence/active-test owner、意图审计投影 | 预言模板/研究元数据 | 不自证、无账户真值、eligible历史 |
| P2 实验 | `docs/research` 与离线脚本 | 版本化协议、dataset manifest、报告 | 无未来特征、窗口清除、成本与覆盖 |

Schema 必须同步 `StockWatchTechnicalSchemaInitializer`、`src/main/resources/sql/init.sql`、`schema-local.sql` 和对应 MySQL/H2 测试；需要 ALTER 的现有表不能只 CREATE IF NOT EXISTS。所有新 API 沿现有认证/授权，不向普通用户返回账号、cookie、完整私有 query/raw provider trace。

UI 使用现有密集工作台风格，不新增营销页面。股票详情按“当前状态 / 预测复盘 / 意图研究 / 风险研究”组织来源与结果；盲测阶段普通用户只看到研究进度与数据质量，不看到提前解盲的绩效；迟到、拒答、不可评分独立可筛选。统计点击可定位确切事件与原记录，不用词语颜色暗示未经验证的交易建议。

## Risks / Trade-offs

| 风险 | 处理 |
| --- | --- |
| 当前来源不能提供完成状态、企业行动或精确接口资金口径 | 前置数据合同 PoC；限定可信子集，公开不可评分；不伪造支持全市场 |
| 新真值门使“准确率”样本变少 | 同时公开覆盖/缺失/迟到；不通过筛掉困难事件证明优化 |
| 新合同允许部分结果会掩盖失败 | 显式 capability 状态；root与身份失败仍整体 invalid，API/统计纳入失败分母 |
| v6 与新版本混用、回滚后旧程序读新记录 | 读写版本 fence、dual-reader先行、rollback只到兼容二进制；不可直接回不识别新合同的版本 |
| 评估 revision 和原 evaluation 产生两种分数 | 显式选版本，旧结果只读保留，禁止镜像回写旧列 |
| 数据不足且前向验证很慢 | 工程交付与效果验证分开；120合格日期不能压成几天烟测 |
| 强措辞提取易错、模型/会话漂移 | 版本化词典、否定/历史/模糊测试、不看未来的复核、漂移分桶 |
| 为追求效果增加调用、积压变重 | 默认零额外业务调用，多版本调用实验单独批准预算 |
| 同股/日期/期限相关导致误差低估 | 成组与重叠窗口清除，预注册不确定性方法，不按行当独立 |
| 当前既有 dirty/untracked 与其他任务并行 | 先冻结逐文件 hash，只提交本次明确文件；不覆盖 n8n/Bridge等无关改动 |

## Migration Plan

### 1. 分批交付与依赖

| 批次 | 内容 | 依赖 | 完成定义 |
| --- | --- | --- | --- |
| M0 | 数据合同 PoC、部署/配置/故障链盘点、冻结基线 | 无 | 已知支持市场/来源与真实断点；其余有原因和边界 |
| M1 | 成熟度/尺度纯计算、evaluation schema、shadow评估 | M0数据证据 | 未完成不终结；无尺度不评分；同输入同结果；旧结果不变 |
| M2 | 存量批次重评、全范围统计、UI版本/分母 | M1 | dry-run可复核、幂等、分页统计一致、可信/代理分开 |
| M3 | 运行链确认修复、真实窗口烟测、priceFailure V1推进 | M0断点；M1成熟评估 | 端到端新业务证据，单提交、研究 cursor推进；不是效果合格 |
| M4 | v7合同与来源/时间/拒答，逐能力投影 | M1；M2读者兼容 | 新任务灰度，v6重放不变，拒答/局部失败独立可见 |
| M5 | 模型措辞独立前向研究，意图预言研究 | 可提前开发；采集依赖M3与相关时间合同 | 协议冻结、未来样本正确积累、隔离与盲测；不启用新通知 |
| M6 | 消融、校准和纸面执行情景 | M1-M5产生合格样本与产品效用约束 | 留出结果报告，达到预注册发布门或明确失败 |

可并行：M0运行定位与数据合同；M1评估与M5独立队列的代码开发；意图模板与风险协议设计。不可并行越门：用坏标签证明模型增量、以旧快照作前向收集、未到解盲条件看绩效选阈值。

首期建议只承诺 M0-M3 与 M5 的隔离采集基础，M4 分能力合同另批灰度；M6 不承诺日历完成日。工期在 M0 后依实际市场/数据缺口估算，不能给“几天后提高到70%”承诺。

M5 必需的时间/来源记录可以先在研究样本与既有receipt关联中增量实现，不要求先切换模型响应合同7；必须证明采集时点和entry资格，做不到的样本不评分。这使风险研究能独立开发，M4的拒答/局部投影改造不会成为采集启动的强前置。

### 2. 发布顺序

1. 备份及字段/索引/schema readiness 盘点，冻结旧结果的数量/hash；记录受保护既有改动。
2. additive schema + dual-reader 二进制先部署；新 writer/统计默认 shadow，旧评估写入以持久化 writer fence 切换，不能两版自动评估抢写。
3. 新评估先固定样本 shadow，比较差异原因；代码真值门通过后按小范围新预测启用，不根据“哪个分数更漂亮”选版本。
4. 存量 dry-run人工核对异常、边界和不可评分样本；追加结果版本分批执行，限流、可断点续跑。
5. 新统计读选定版本，旧API明确scope；新 UI先支持双版本，稳定后将可信版本设为默认。无新结果标不可用，不自动回落旧结果装作可信。
6. 新 response contract仅新task灰度；原 prepared/submitted/UNKNOWN任务按冻结合同收敛，绝不因切换补发。
7. 研究队列启用前冻结协议/hash/cutoff，按未来可用时点采集；模型不可用时确定性事实/priceFailure仍推进。
8. 完成实际 API/UI与跨成熟窗口烟测；新提醒/执行情景继续关闭，待效果门另行批准。

建议配置名（实现前与现有配置统一）：`prediction.evaluator-mode=LEGACY|SHADOW|VERSIONED`、`prediction.statistics-version`、`technical-analysis.response-contract-version`、`model-risk-research.enabled`、`model-risk-research.mode=PROSPECTIVE_ONLY`。评估 writer fence 必须持久化且带 generation/rowVersion，单机配置不能保证滚动部署互斥。

### 3. 回滚

- 回滚读者可以切回明确标注的 legacy 统计，但不能宣称旧成绩单已经可信。
- 停止新评估/研究 writer，保留已追加结果与协议/cursor；不删除结果、不复位游标、不重建同version协议。
- 灰度 contract退回6仅作用于尚未冻结的新任务；已冻结7保留7 reader完成。只能回滚到 dual-reader兼容版本，不能用旧二进制读新schema/status冒险。
- 不回滚canonical reservation/receipt，UNKNOWN不重新开放同 observation提交；v18调度ownership继续使用其现有fence语义。
- 效果不支持时回退候选规则/提示词版本而非改标签；研究失败记录保留，未通过的能力不通知。

### 4. 验收矩阵

| 层 | 必测案例 | 通过证据 |
| --- | --- | --- |
| 纯计算 | 未完成bar、节假日/半日/停牌、价格口径冲突、企业行动、非有限数、缺概率、方向阈值边界 | 固定fixture测试，不终结/正确原因及可重复结果 |
| 持久化 | 重跑、并发CAS、终态不可变、revision、旧EVALUATED重评、H2/MySQL升级 | 数量/唯一键/hash/读写一致，旧数据不变 |
| API | 全范围stats、分页上界、筛选同口径、v6/v7、ABSTAIN与局部失败、权限 | 真实HTTP返回与DB样本集合相符 |
| UI | 日期分组/游标回归、统计点击样本、不可评分/来源、盲测不泄绩效、宽窄屏 | 浏览器实际观察，非空页面且数字/状态准确 |
| 工作流 | 新fact->task->receipt->analysis->forecast->成熟评估；模型失败仍推进事实/研究 | exact IDs、submissionKey、一次提交、跨真实成熟窗口 |
| 风险研究 | 否定/引述/迟到/盘后分层、entry冻结、entry后路径、NO_WARNING分母、隔离 | 固定parser测试+DB隔离+未来真实样本 |
| 意图 | 正向预言/反证/缺数据、不自证、竞争解释、SUPPORTED非真值 | 模板测试与研究输出定义一致 |
| 统计效果 | 冻结事件/阈值/基线/机会池、成组留出、校准与成本、执行代理限制 | 后续新样本报告，不以本期工程烟测替代 |

实现期至少跑相关 Java 单元/集成、frontend 单测/build、schema smoke 和真实API/UI；Maven采用单writer，最后关键验证raw执行。material修复交付前按AGENTS记录shadow-eval的fix及边界；运行或后验反例不得省略。

## Open Questions

### 必须在对应阶段启动前解决

1. **M0数据**：当前供应商最终bar与企业行动/调整口径能否获得可核验证据？未知市场默认不评分；这项影响覆盖率，不能靠提示词解决。
2. **M3运行**：本库为何停止到8月14日、研究游标为何未推进？本方案不预填唯一根因，按真实链定位后才冻结修复范围。
3. **M5/M6产品效用已澄清**：用户人工选股入场，优先已有持仓危险识别与人工退出；完整预测仍需要但优先级较低。真实持仓/成交并未由此获得，研究仍须登记或显式标准情景，不能推定个人节省金额。新增持仓领域和统一路线见顶部链接。
4. **M5协议**：每日/每股可接受多少误报？错过上涨与一次风险命中的相对代价？没有该效用约束可以先收集，不可自动选阈值上线。
5. **M6资源**：是否允许多版本模型消融额外调用及多少预算？默认不允许额外业务调用，不阻塞P0。

建议默认但不是用户已确认：先支持已核验机制的普通内地股票主队列，HK/ETF/特殊板块独立分层；T1优先，T5保留；-5%仅沿用探索候选用于预注册，不宣称最优；价格研究只读采集，生产通知关闭；不购买新数据或接券商。

上述默认只限制新增可信评分/研究准入，不自动停止既有港股/ETF分析、意图研究或存量通知。既有能力是否应停用必须根据具体数据质量与用户策略单独裁决，不能借新方案悄悄删除现有功能。

## 审核与方案边界

以下为原v0设计审核记录，保留历史；本次目标v1和新增助手的审查见新change的 [审核包](../add-stock-watch-position-risk-assistant/audit-packet.json) 与 [审核记录](../add-stock-watch-position-risk-assistant/review.md)。它不表示已经实施或证明了旧修复效果。

本轮采用 `conclusion-auditor` 的本地目标/证据/反证/路径检查，**非独立自审**，没有新启动 Challenge/Optimize 子代理。前几轮统计已受审核，不等于这份新增数据库/API/合同方案也已受独立审核。OpenSpec与audit-packet结构通过只证明工件格式和声明覆盖，不证明实现正确或预测有效。

本方案支持：增量改造而非全量重写、评估真值先行、风险与意图分轨、运行与效果分门。仍未证明：准确率提升、预警优于简单基线、实际避损/净收益、真实账户意图。发布前须对实际实现、存量批次和前向结果再次独立审核；发现效用/机制反证允许停止候选发布。
