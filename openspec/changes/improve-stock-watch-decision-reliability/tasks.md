# 实施任务清单

本轮仅出方案，所有实施项保持未完成。执行顺序以 design.md 的 M0-M6 和前置门为准；研究协议与开发可以并行，但不得绕过真值、预算、盲测或效果门。本清单不授权下单、发新告警、购买数据或额外模型消融调用。

用户后续确认：本清单79项全部保留，新增持仓风险助手另外实施，不替代本清单；本轮仍只完成统一设计，不改业务代码。跨线依赖与产品优先级见 [统一路线](../../../docs/design/stock-watch-unified-delivery-plan-2026-10-05.md)，新增清单见 [持仓风险助手任务](../add-stock-watch-position-risk-assistant/tasks.md)。

## 1. M0 基线与前置核验

- [ ] 1.1 冻结实际 HEAD、dirty/untracked 文件哈希、部署进程版本、相关表字段/索引及既有受保护变更清单。
- [ ] 1.2 导出只读统计基线：预测所有状态、来源/版本/时间缺口、原评估分桶、research cursor和计划机会证据。
- [ ] 1.3 核验当前日线完成状态、交易日历、特殊交易日和停牌数据合同，列出首期确实支持的市场/标的。
- [ ] 1.4 用真实供应商样本验证未复权/qfq、企业行动和来源切换的价格基准；无法证明的情况冻结不可评分原因。
- [ ] 1.5 沿实际 fact/demand/task/exact Bridge ledger/receipt/analysis/prediction 定位8月14日后本库无新数据的断点，不预填唯一根因。
- [ ] 1.6 核验priceFailure V1规则/hash、扫描注册、游标CAS、资格排除与事实增长，解释零ledger与cursor不前进的差别。
- [ ] 1.7 冻结M1工程验收合同、支持范围与待补证边界，并对实际问题链进行实施前结论审核。

## 2. M1 结果真值与版本化评估

- [ ] 2.1 为评估链引入EvaluationBarsEnvelope和版本化时间/来源/完成证据DTO，保持生产其他Map消费者兼容。
- [ ] 2.2 在既有market-data边界实现按已核验交易日历/标的机制选目标日及完成bar资格，不用缺失bar顺延目标。
- [ ] 2.3 实现QUOTE_BASIS_PRICE_RETURN_V1的基准验证，先支持已验证无尺度冲突的子集，未证实企业行动/转换fail closed。
- [ ] 2.4 抽取forecast owner调用的纯计分函数，保留LEGACY_1PCT、HORIZON_V2和RETURN_SIGN边界，分别输出带内/极值重叠/失效观察。
- [ ] 2.5 定义选定方向事件Brier的有效概率分母，禁止新评估补0.5或把二元分数称三分类分数。
- [ ] 2.6 增加evaluation子表/entity/Mapper及唯一约束、等待CAS、终态冻结、revision和supersedes引用。
- [ ] 2.7 同步initializer、init.sql、schema-local.sql，补升级DDL和H2/MySQL metadata readiness测试。
- [ ] 2.8 在PredictionService接入shadow/versioned writer并设置持久化writer fence，阻止新旧实例竞态写入。
- [ ] 2.9 实现按目标日/next_retry_at分页的公平重试，记录逐事件缺失原因，不让旧缺失记录长期堵塞后续成熟事件。
- [ ] 2.10 补未完成bar、半日市/节假日/停牌、无尺度、非法OHLC、除权、来源修订、非有限值、概率缺失与阈值测试。
- [ ] 2.11 补幂等、并发、重启、终态不可变、新revision和旧结果不变的持久化测试；完成固定样本shadow差异报告。

## 3. M2 存量重评与全范围复盘

- [ ] 3.1 建立存量re-evaluation dry-run与分批脚本，包含所有旧状态、版本、provenance和逐id原因清单，不写旧forecast/evaluation列。
- [ ] 3.2 人工核对提前结算、尺度异常、零收益、迟到、缺源日期和缺bar的代表样本后，冻结写入批次范围与输入hash。
- [ ] 3.3 对缺证据记录按UNSCORABLE或EXPLORATORY代理处理，明确不进入可信主统计，验证重复批次无额外终态。
- [ ] 3.4 共享列表/统计filter解析，新增全范围prediction-statistics API，返回版本、scope、asOf、filterDigest和snapshot边界。
- [ ] 3.5 增加计划能力/资格的前向记录投影及事件选择，先选机会再看结果，存量无计划证据标OBSERVED_RECORD_COHORT。
- [ ] 3.6 返回失败、迟到、拒答、未成熟、缺数据、不可评分、scored及概率分数各分母；区分原始记录和主事件。
- [ ] 3.7 保留beforeDate/beforeId并加入固定分页上界；旧summary声明PAGE，不由客户端第一页充当全量。
- [ ] 3.8 更新StockWatch.vue与predictionHistory消费者的统计、版本与筛选，保留日期展开/方向背离交互。
- [ ] 3.9 使指标点击能定位同一版本和筛选下的确切事件及原始记录，核对列表集合和分母一致。
- [ ] 3.10 测试304条多页、不同pageSize、并发新增、不同eval版本、首事件失败后成功及混合方向规则，完成真实API/UI验证。

## 4. M3 运行连续性与真实窗口验收

- [ ] 4.1 在现有RuntimeStatus投影增加逐阶段freshness、阻塞年龄、最新业务成功、研究扫描/游标和不确定原因。
- [ ] 4.2 根据持久化策略与已核验日历区分暂停、无due、非交易日、missed slot和实际阻塞；查询失败明确UNKNOWN。
- [ ] 4.3 仅修1.5/1.6已证明的运行断点，复用scheduler ownership、demand、canonical task和receipt恢复链。
- [ ] 4.4 验证SUBMITTED/UNKNOWN恢复只能关联/reconcile原任务，重复tick/重启不额外模型提交，不改GENERAL_LEGACY边界。
- [ ] 4.5 在受控真实交易窗口核验新fact/demand/receipt/analysis/prediction与priceFailure扫描；记录准确IDs、提交次数和实际部署版本。
- [ ] 4.6 跨至少两个相邻有效交易日验证T1成熟评分、缺失原因和研究推进；T5另外等满窗口，不能以T1代验T5。
- [ ] 4.7 测试provider unavailable时确定性事实/风险/研究仍推进；核验p50/p95延迟、积压和错误原因。
- [ ] 4.8 对确认修复运行raw最终验证、按AGENTS记录shadow-eval修复证据和剩余边界，不将health或queued作为端到端通过。

## 5. M4 任务语义与新合同灰度

- [ ] 5.1 定义taskDefinitionVersion、真实inputGranularity、市场/标的资格以及各能力sourceKind/definitionVersion。
- [ ] 5.2 保存quote/fact/request/receipt/projection/计算/发布时点与quality，缺失历史时间不伪造，模型迟到进入独立分母。
- [ ] 5.3 新增response contract7的FORECAST/ABSTAIN项和严格校验，拒答写capabilityResults，不创建假SIDEWAYS预测行。
- [ ] 5.4 在现有projection事务实现逐能力VALID/ABSTAINED/INVALID/UNAVAILABLE，身份/root失败整体invalid，其余可合法部分投影。
- [ ] 5.5 更新confidence与band元数据和UI，标注未校准/规则分数/SCENARIO_BAND，不把非法概率clamp成合法输出。
- [ ] 5.6 修正HK使用A股人设和机制不明的near-limit语义，资金字段标代理/单位/口径未知，不随意调整存量GENERAL阈值。
- [ ] 5.7 实现v6/v7 dual-reader与冻结parser选择，验证旧prepared/submitted/UNKNOWN不因版本切换重发或重解释。
- [ ] 5.8 补合法T1+非法T5、全拒答、root非法、引用越界、invalid probability、replay和事务测试，再做新任务小范围API/UI灰度。
- [ ] 5.9 验证兼容二进制回滚、schema readiness和不支持新合同的旧二进制fence，保留所有新旧记录。

## 6. M5-A 价格失效研究继续前向运行

- [ ] 6.1 确认V1不改ruleSpec/hash、120源日期/30成熟信号门、无历史回填和禁止模型原文输入。
- [ ] 6.2 核对V1的nextObservedDate、POST_CLOSE、entry与价格尺度语义及实际适用范围；若需实质改变，另立V2而非覆盖V1。
- [ ] 6.3 回归冻结entry、RECORDED_ENTRY_INCOMPLETE、后到早事实不重写、控制匹配无未来信息和重复扫描幂等。
- [ ] 6.4 以真实新增合格/不合格事实验证assessment、cursor、signal/control和到期状态推进，仅公开盲测允许的质量/进度。

## 7. M5-B DeepSeek派发措辞独立研究

- [ ] 7.1 冻结独立MODEL_DISTRIBUTION_WARNING_RESEARCH_V1协议、词典/否定/引述/强弱规则、机会池、可用时点、窗口与去重。
- [ ] 7.2 确认该协议的最小效用、误报约束、解盲最低门和不确定性/样本量方法；不改变价格失效V1协议。
- [ ] 7.3 新增隔离model-risk研究样本、专属扫描状态及outcome revision/schema readiness，落实receipt幂等/nullable unique主事件键，只消费已存reply/task/fact，不增加模型调用。
- [ ] 7.4 提取MODEL_RAW_RESPONSE短片段/位置/hash/来源，记录STRONG_WARNING、NO_WARNING、AMBIGUOUS、PARSE_FAILED、迟到和排除原因。
- [ ] 7.5 持久化信号计算/可用/发布时间，旧回复只EXPLORATORY，首个合格强警告冻结，重复引用不扩充分母。
- [ ] 7.6 复用合格纯价格工具计算entry之后的T1最差收益，缺后entry路径不得用全天low；盘中剩余风险另窗口。
- [ ] 7.7 同期记录纯价格、价格失效、价格+资金代理基线/对照，不用未来结果筛控制或补控制。
- [ ] 7.8 测试典型出货、否定、引述、反例、周末/盘后、迟到、entry后路径缺失、NO_WARNING分母与协议漂移。
- [ ] 7.9 证明不改canonical factHash/task身份、不写priceFailure V1、不改生产通知、不因研究失败回滚业务成功。
- [ ] 7.10 在未来真实回复验证采集和隔离，普通UI只看进度/质量，样本成熟前不披露或利用盲测绩效选规则。

## 8. M5-C 主力意图并行研究

- [ ] 8.1 保留当前model intent审计-only及server弱证据画像，明确不恢复真实主体确认式展示。
- [ ] 8.2 定义少量竞争解释与注册可观察预言模板，含正向predicate、失效条件、窗口和缺失策略，不允许任意表达式执行。
- [ ] 8.3 在既有active-test owner扩展必要模板与研究元数据，先验证旧test后创建新test，模型不能改verdict。
- [ ] 8.4 修正研究/展示的SUPPORTED解释，不回写旧verdict；分别报告预言兑现、证伪、未解和覆盖，不声称意图真值准确率。
- [ ] 8.5 维持eligible-only与同版本历史门，补R01-R04/模型意图/模型风险队列不混用及风险未解盲仍可推进的测试。

## 9. M6 效果实验与纸面执行

- [ ] 9.1 在M1-M5合格结果基础上冻结一个主事件和主基线、最小有用增量、覆盖/误报/延迟/成本门及分析计划。
- [ ] 9.2 实现时间/事件分组与重叠T5等标签purge，生成可复核dataset manifest并保留失败/拒答机会。
- [ ] 9.3 顺序测试A0简洁事实、A1持续性/背离、A2合格历史、A3任务相关事件；每次只变固定增量。
- [ ] 9.4 单独确认多模型版本研究调用预算；未经批准不额外提交，批准后用独立research namespace/session/store隔离。
- [ ] 9.5 使用训练/验证期拟合选定方向二元概率校准，仅在未来留出期评分，分事件/期限/版本报告样本与区间。
- [ ] 9.6 给场景价格带报告收盘落带与宽度；若另立固定覆盖interval合同，预先冻结覆盖目标与适当评分再采集。
- [ ] 9.7 在不提前解盲的条件下报告各风险队列的基线增量、精确率、观察池召回、覆盖、延迟、误报、分层与不确定性。
- [ ] 9.8 另行确认避免新买/已有可卖持仓的标准纸面情景，冻结发布后entry、限制、成本、无法成交和机会损失。
- [ ] 9.9 计算仅有资格路径的EXECUTION_PROXY结果，缺顺序/成交证据标未知，禁止报告个人实际避损或实盘净收益。
- [ ] 9.10 按预注册门裁决PASS/NO_INCREMENTAL_EDGE/INSUFFICIENT_EVIDENCE；未通过候选不启用新提醒、不改标签挽救结果。

## 10. 分批审核与发布收尾

- [ ] 10.1 每批冻结验收数据/版本和改动白名单，独立Challenge核结论、Optimize核路径，记录属实/不属实/待补证及边界。
- [ ] 10.2 单writer完成相关Java测试、H2/MySQL schema smoke、frontend测试/build，并raw运行最后关键验证。
- [ ] 10.3 逐批部署后确认实际listener加载版本，再做真实API/UI及对应workflow/maturity验收，区分工程与效果完成。
- [ ] 10.4 演练writer/reader/contract独立回滚，保留terminal revisions与研究协议，不复位cursor、不释放UNKNOWN重复提交。
- [ ] 10.5 只提交本次范围内文件，记录已完成、未完成、验证和可观察剩余边界；不夹带既有dirty/untracked改动。
