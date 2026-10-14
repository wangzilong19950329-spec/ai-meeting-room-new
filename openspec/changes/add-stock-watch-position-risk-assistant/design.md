# 持仓风险助手详细技术设计

日期：2026-10-05。状态：待实施设计。只设计、不改业务代码的范围由用户本轮明确确认。本功能与原 bug 修复并存，发布顺序见 [统一路线](../../../docs/design/stock-watch-unified-delivery-plan-2026-10-05.md)，本轮目标审核、反证和路径裁决见 [审核记录](review.md)。

## Context

### 1. 已核对的实现与证据边界

| 编号 | 当前证据 | 设计含义 |
| --- | --- | --- |
| E1 | `StockWatchItem` 只有 symbol/market/group/policy 等字段；`StockWatch.vue` 有 `holding` 分组 | 分组不是账户持仓；不能推出数量、成本、买入批次或可卖资格 |
| E2 | `StockWatchMonitorService.scheduledScan/runOnce` 默认 600000ms，串行运行报价、风险事实和 AI rule | 不能把每 10 分钟的完整扫描直接当快速持仓保护；不能靠缩短周期迫使全链高频调用模型 |
| E3 | `StockQuoteService` 先尝试 push2delay；报价有 f124 时间，Map 不保留具体命中 URL；按代码关联后覆盖 requested market | 新接口必须验证供应商身份、来源及真实时延；换域名不自动证明实时性，不能仅相信请求标的 |
| E4 | `fetchIntradayBars` 有 fetchedAt/asOfTime 与 OHLC，但 VALID 主要检查非空，endTime 由 startTime+1min 推出 | 现有分钟图不能直接当已完成、完整且时间语义已核验的评价路径 |
| E5 | `StockWatchAlertDeliveryService` 已有事务、持久化 obligation、lease、初报与模型补报；当前只发送邮件 | 复用交付 owner，但持仓归属、升级、时效、取消和 transport UNKNOWN 需要增量合同 |
| E6 | `StockWatchAnalysisProjectionService.project` 事务内写预测并标 task success；模型提交归 canonical task/Bridge | 新风险功能不能成为另一模型提交入口，也不能因附加风险提取失败回滚现有业务成功 |
| E7 | `AuthInterceptor` 由 token 得 userId，放入 `currentUserId`；`/api/**` 有认证拦截 | 持仓 API 必须据服务端 userId 授权，不能据客户端 ownerId 或 symbol 访问他人持仓 |
| E8 | 历史报告：通富 2026-07-27 11:23 有风险文字但 T1=SIDEWAYS，次日相对参考价 -7.84%；另有次日上涨的强措辞反例 | 风险与方向需独立验收；用户自述避损是个人使用证据，不等于长期总体效果 |

另有 n8n/Postgres 的 `sw_position`、`sw_risk_policy` 及压力报告原型（`V010__phase2_claims_positions_and_policy.sql`、`phase2-domain.js:634`），不能说整个仓库从未有持仓设施。它按symbol/market保存数量，迁移带 `LOCAL_QA_FIXTURE`，不具备本设计所需的用户/批次/交易事件合同；缺输入按0处理也不作为新助手语义。首期以Java/MySQL持仓owner服务现有工作台，不把原型样本自动导入或双写；后续若要接n8n，只能经该owner授权接口，不建立第二份实际持仓真相。

本轮核对的是当前工作区源文件与历史研究工件，没有重新查询实时持仓、交易账户、生产时延或通知送达。上述默认值不代表实际部署配置。索引核对时为 current；实施前须重新冻结 HEAD、dirty 哈希与部署版本。

### 2. 外部领域约束

普通 A 股新买部分不能默认当日卖出，适用回转交易品种另有规则；身份、交易方式与日历必须明确。[深交所现行规则，3.1.4-3.1.5](https://docs.static.szse.cn/www/lawrules/rule/trade/current/W020260424690713155663.pdf)、[上交所现行发布通知](https://www.sse.com.cn/lawandrules/sselawsrules2025/trade/universal/c/c_20260424_10816492.shtml)。

报价与实际成交不等同，发送委托也不意味着按屏幕价格立即成交。[SEC 执行说明](https://www.investor.gov/introduction-investing/investing-basics/how-stock-markets-work/executing-order)。本设计不移植美国订单规则到 A 股，只引用这项执行证据边界。

供应商资金是分类统计，不是实名主力身份；东方财富终端的官方 FAQ 不能替代本系统具体接口的单位、区间或主动性合同。[东方财富 FAQ，33/34/36](https://emdesk.eastmoney.com/pc_activity/pages/viptrade/pages/answer.html)。

## Goals / Non-Goals

### Goal Contract v1

权威来源：用户本轮明确的人工选股入场、及时持仓退出、方向预测次级、原修复与新功能都保留，以及最新“本轮只完成统一设计方案”。v0 的“重点短线研究/优先避免新买”不再代表当前产品优先级；修复与冻结研究义务不被撤销。

| 义务 | 设计验收 | 后续工程/效果验收 |
| --- | --- | --- |
| G1 保留旧修复并新增助手 | 原 79 项保留，统一路线逐包映射 | 分批证明原 bug 链修复，不能拿新功能覆盖原问题 |
| G2 服务已有持仓、人工退出 | 持仓/批次/可卖/动作模型明确 | 注册至风险事件、通知、人工反馈的真实链 |
| G3 尽早且及时 | 快慢路径、时间字段、迟到与覆盖合同 | 源时延、计算与通知分段量测，模型失败不阻断 |
| G4 兼顾盘中与次日风险 | 预警时间与兑现窗口分开 | 信号后路径、次日跳空、不可成交单独验真 |
| G5 保留完整预测与并行意图 | 独立 provenance、显示区和指标 | 方向/意图不控制快速保护开关，不共用真值 |
| G6 不以命中个例承诺避损 | 无告警机会、误报漏报、执行代理、效果门 | 前向对照与成本；个人效果另需成交记录 |
| G7 本轮仅设计 | 只写方案/规格/任务/审核 | 本轮不部署、不交易、不发通知、不写业务库 |

首期首选已核验机制的普通沪深股票、多笔次交易日可卖的人工持仓。HK、ETF、特殊标的不从既有分析中删除；只是新助手未核验时显式 `UNSUPPORTED`，不声称可卖或无风险。用户可后续扩展各 instrument profile。

### Non-Goals

- 不自动买卖、不代替券商、不自动把模型判断变成市价/止损单。
- 不替用户选股入场，不要求完整预测高准确才启动保护条件提醒。
- 不证明真实主力身份，不把“派发”确认为主体行为，不把 confidence 当命中概率。
- 不默认新增模型调用、新闻爬取、Level-2 采购、手机 push 或券商连接。
- 不回写历史评估、改变 RETURN_SIGN、priceFailure V1 hash/120日期/30成熟信号门或旧 frozen task。

## Decisions

### D01. 两条交付线、三个独立结果

旧 change 继续负责可信成绩单、运行链、响应合同、研究与模型消融。新 change 负责持仓保护和风险交付；共享已验证的数据/认证/通知能力，不共享一个含糊的“分析正确率”。

| 结果 | 提问 | 主验收 |
| --- | --- | --- |
| 持仓风险 | 是否及时暴露值得重新评估持有的危险？ | 漏报、误报、提前量、覆盖、可执行性 |
| 完整预测 | 固定期限的方向/价格带如何？ | 原版本化预测评估、概率/区间质量 |
| 意图假设 | 哪个解释的可观察预言得到支持或证伪？ | 原 active-test 与竞争预言，不是身份真值 |

替代方案：只继续方向预测改动较少，却不能解释“方向不准但退出有用”；全部改成卖出预测又删除用户仍需的能力。采用并存 owner，代价是明确版本、时间与指标。

### D02. 领域词汇与正交状态

| 名称 | 定义 |
| --- | --- |
| Position | 一位用户、一个标的、一个账户别名下登记的持仓聚合；不是券商实时库存 |
| PositionLot | 买入/初始登记批次，含买入交易日、剩余数量和来源可信度 |
| PositionEvent | 已登记成交、修正、策略修改的不可变事件；系统不发委托 |
| ProtectionPolicy | 用户明确设定的保护价格、回撤或金额条件及通知选择 |
| MarketObservation | 持仓风险用途的来源快照，保存真实源时点、接收时点与口径 |
| Assessment | 某机会下的风险结果与数据覆盖；无告警与无法计算也保存 |
| RiskEpisode | 持续风险过程的投影，聚合多个观测、升级与恢复，不是独立预测行计数 |
| AvailableAt | 此版本风险结果提交后可从认证 API 读取的时间，不是源报价时间 |
| DeliveryReceipt | 页面展示或 transport 接受证据；不自动等于用户已读 |
| ActionRecord | 用户确认、保持、计划退出或登记成交；ACK 不减少仓位 |

状态分开存储：

```text
riskLevel       = null (NO_ELIGIBLE_ASSESSMENT) | NONE | WATCH | WARNING | CRITICAL
evidenceClass   = USER_POLICY | OBSERVED_PATTERN | MODEL_HYPOTHESIS
validationState = USER_DEFINED | EXPERIMENTAL | PROSPECTIVE_APPROVED
coverageState   = HEALTHY | DEGRADED | STALE | PAUSED | UNSUPPORTED
sellability     = USER_CONFIRMED | RULE_ESTIMATED | NONE_TODAY | UNKNOWN
episodeState    = OPEN | RECOVERING | RESOLVED | EXPIRED | CLOSED_POSITION
```

无证据不写 riskLevel=NONE 冒充安全；首次没有合格assessment时为null并附原因，有历史风险则保留其出处/年龄，不因coverage未知清除。marketCoverage与inventoryQuality分别返回，summary coverage不能掩盖过期持仓，但某项降级不阻断另一项合格保护。CRITICAL 是已知保护条件的紧急等级，不是“暴跌必然发生”。EXPERIMENTAL 模型假设不能自行写成可信 CRITICAL。

### D03. 持仓录入、批次与可卖资格

首期手动录入 symbol/market、instrumentType、账户别名、买入日期、数量；成本可空，币种按标的核验。已有 `holding` 分组仅提供待确认列表，导入后保持 `DRAFT`，不替用户填数量/日期或默认激活。

Position 有 `ownerUserId`、`status=DRAFT|ACTIVE|PAUSED|CLOSED`、`rowVersion`、`policyVersion` 和 `monitorStartedAt`。同用户/账户别名/标的仅一个活动聚合；曾清仓后重新买入创建新 position/generation，不续用旧 risk episode 或高水位。

批次保存 `boughtTradeDate`、`quantity`、`remainingQuantity`、`reportedAt`、`source=USER_REPORTED`。普通 A 股按已核验交易日历推导预计可卖日；不能只比较自然日期。停牌、账户冻结、券商风控、企业行动未知时，可卖信息降为 UNKNOWN/估计，不能显示“保证可以卖”。

用户确认可卖数量保存确认时间、来源和适用交易日；不得超过登记剩余量，跨日重新计算。今天新买与旧仓分开。例如旧批次1000股、今天加仓500股，不能把1500股都标当日可卖。行情风险评估仍覆盖整仓，退出辅助提示可卖部分及剩余锁定部分。

持仓另有 `inventoryConfirmedAt`、`inventoryConfirmedTradeDate`、`inventoryQuality` 和确认失效策略。首期建议每交易日开始前确认余额，且每次实际买卖/企业行动后更新；这是待用户确认的操作约定，不是已经具备券商同步。超期或出现未核验余额变化时，量/金额及可卖结论降为STALE/UNKNOWN，禁用金额P03与个人金额效果；仍保留标的价格保护与“持仓信息过期”提示，不把过期持仓当0或漏掉危险。

部分卖出登记必须明确数量、时间、价格和批次分配；可提供用户可确认的分配建议，不以隐藏 FIFO 当实际成交真值。`clientEventId` 幂等、expectedRowVersion CAS、批次余额非负；同一事务追加 event、扣减 lot、更新 position 版本和关闭/重评相关 episode。重复提交不再次扣仓，确认提醒不等于成交。

修改历史买入日期/成本使用新 correction event 和版本，不改过去 assessment 的冻结持仓。实际登记可早于 reportedAt，但不能据后到信息改写当时的风险机会。登记成本缺失时，停用金额亏损条件，价格条件仍可用。

### D04. 系统结构与唯一 owner

```text
人工持仓/保护策略 -> Position owner -> 活跃标的集合
                                        |
已核验报价/分钟/日历 -> Risk market capture -> observation + quality
                                        |
                               纯确定性评估（快路径）
                                        |
已存模型回复/receipt -> 只读风险解释提取（慢路径）
                                        |
                        assessment -> episode transition
                                        |
                发布 + 邮件 obligation（同一数据库事务）
                          /                     \
                   认证页面轮询             原 delivery owner
                          \                     /
                        用户确认 / 人工成交登记
                                        |
                         独立版本化风险/执行复盘
```

拟新增 `StockWatchPositionService` 管持仓；`StockWatchPositionRiskService` 管评估/episode/发布；风险采集与纯评估器是这两个 owner 内部可测模块。`StockWatchPositionRiskEvaluationService` 管新领域结果，不重新实现旧 T+N forecast owner。

对外主要 Interface：`registerOrRevisePosition`、`recordPositionEvent`、`observeActivePositions`、`consumeCommittedAnalysis`、`reviewPositionRisk`。内部依赖注入 Clock、日历/标的机制、行情 adapter、Mapper 和纯 evaluator；先保留 Java/Vue/MyBatis，不建 Kafka、独立微服务或泛用规则 DSL。

风险快速观测有不同 cadence 和用途，单独保存并可引用已有 canonical fact；不把30秒采样或个人持仓字段塞进旧 factHash，也不提高旧 scheduler/demand 的模型提交频率。共享标的的公开市场观测可以去重，个人 assessment/policy/event 全部按 owner 隔离。

模型只消费已有 committed analysis/task/receipt，append 引用和解释结果。MVP不调模型；后续需要结构化 riskReview 或专门复核时另立冻结响应合同及预算，不偷偷扩展已经冻结的 v6/v7 任务。完整预测仍由旧 owner 写入/计分。

### D05. 数据合同和快速采集

持仓 adapter 返回类型化 `RiskObservationEnvelope`：

| 字段组 | 必须内容 |
| --- | --- |
| 身份 | symbol保留前导零、market、exchange、instrumentType、currency、provider identity证据 |
| 时间 | sourceAt、receivedAt、capturedAt、tradeDate、marketTimezone、sessionPhase、calendarVersion |
| 来源/尺度 | provider、实际endpoint标识、priceBasis、单位合同版本、sourceRevision/dataHash |
| 价格 | 有限正 price、previousClose、open/high/low、OHLC一致性 |
| 区间量 | volume/amount/turnover/fundFlow数值、累计/区间属性、已核验单位、flowSourceAt |
| 质量 | quoteAgeMs、源钟偏差、字段缺失、重复/乱序/修订、QUALITY reasons |
| 路径 | 完成分钟条、bar完成证据、覆盖区间、缺口和捕获 vintage；没有路径明确 unavailable |

验证响应里的身份，不把错误市场强制改为请求市场；记录实际命中的源，而非固定字符串。`Map` 旧消费者保持兼容，新 typed envelope 仅在新领域使用。资格门按能力拆分：`QUOTE_PROTECTION_READY`要求身份、源时间/时延、价格尺度与用户policy；`INTERVAL_FEATURE_READY`另要求相应分钟/资金单位和窗口；`OUTCOME_PATH_READY`另要求完整结果路径。后两门未过不阻断合格报价保护，但不能据报价保护门生成区间特征或正式风险成绩。

同交易日同口径累计流 `F(t2)-F(t1)` 才能作为区间代理；累计量/成交额同理。跨日清零、源切换、未核验修订或长缺口使该区间feature UNKNOWN。无已知修订时累计成交量/成交额的负增量属于异常；有符号累计净资金流的负差值可以是正常的区间净流出，不能一概判异常。也不把当天累计净流出当每30秒新流出。资金缺失不能伪造0，不能阻断用户价格保护条件。

5/15/30分钟 feature 仅取 sourceAt<=当前观测且到 capturedAt 已知的数据，窗口采用交易分钟排除午休。量能比较使用同钟历史可比窗口或已核验相对量，不把10点累计量与全天均量直接比较；缺可比基线停用该 feature。完整日线支持 ATR/价格位置，不让当天未完成日线进入历史基准。

建议容量首期上限20个活跃标的：30秒批量报价；分钟数据按标的每60秒或源允许节奏增量；完整日线/元数据按日缓存。数值是待供应商时延/限流 PoC 验证的工程配置，不是已实现 SLO。AI 不在这个周期内运行。

有界连接超时、有限重试、单源 circuit breaker、标的公平队列、过期任务跳过；不排队补跑已错过的每个 tick。跨实例按租约/持久化 checkpoint 协调 capture 和 assess；tick机会仍留下 MISSED/DEGRADED，不因成功去重丢失停机覆盖证据。

30秒观测默认保留30个交易日热数据，必要证据和已评估窗口保留冻结引用；实际保留期/磁盘上限在 PoC 测量后配置。只归档公开行情安全字段，不存 cookie、账号、私有 query 或完整日志。

### D06. 风险识别：保护条件先行，候选结构分轨

| ID | 内容 | 数据依赖 | 资格/状态 |
| --- | --- | --- | --- |
| P01 | 触及用户明确设定的保护价格 | 新鲜价格、尺度/用户policy | USER_POLICY；首次有效触及即可触发，不等模型 |
| P02 | 从启用后已观察高水位回落超过用户设定幅度 | 相同尺度、有完整监控起点的价格序列 | USER_POLICY；从monitorStartedAt开始，不伪装历史最高收益 |
| P03 | 用户定义的浮亏金额达到阈值 | 数量、成本、币种、费用约定及新鲜价 | USER_POLICY；缺字段仅此项不可用 |
| C01 | 急涨后价格失效/反弹不能收复 | 完成历史价格位置、盘中价格持续性 | EXPERIMENTAL；不能照搬 priceFailure V1 为产品规则 |
| C02 | 支撑破位后持续弱势/量能异常 | 支撑来源、完整窗口、可比量能 | EXPERIMENTAL；用户自定支撑与模型建议分开 |
| C03 | 资金代理持续退出但价格暂稳/冲高回落 | 同口径区间流、价格、有效持续性 | EXPERIMENTAL；资金是加分候选，不是强制门或已证增量 |
| C04 | 盘中相对基准显著转弱 | 同步基准、已核验窗口/波动 | 后续候选；缺基准时不出伪相对弱势 |
| M01 | 原模型当下风险解释、典型派发/出货及条件警告 | 原回复hash/位置、receipt、源事实/时间 | MODEL_HYPOTHESIS，否定/引述/历史/含糊单独记录 |

P01-P03需用户选择，空阈值不擅自补固定2%/5%止损。P02高水位与policy版本/持仓generation绑定，跨企业行动或数据尺度变化未核验时失效，不用当天high（可能早于启用/买入）制造已赚回撤。

候选阈值、组合、观察次数和恢复条件在采集前注册成 `riskRuleSetVersion/specHash`；历史个例仅生成候选。初期不用不透明加权总分、RSI超买即退出、净流出即出货或 probability>=70%即报警。严重等级由已注册条件与验证状态决定，不是模型自由生成数值。

模型提取需保留条件触发点、失效点、竞争解释和引用。自然语言提供的价格不直接成为用户保护policy；非法值/无法验证引用不进入实时等级。派发词未出现的早期“反弹失效/警惕顶部”也进入独立候选分类，避免只等强措辞；此扩展冻结新 extractor/protocol，不改旧 M5-B词典后重算同版成绩。

模型输出冲突时保留事实提醒、标模型分歧，不让 T1=UP/SIDEWAYS 抹去价格保护，也不让单句强措辞压掉完整预测。已到价/模型迟到标 CURRENT_BREACH/LATE，不包装为提前预警。

### D07. 快慢路径、时点和性能验收

```text
sourceAt <= receivedAt <= capturedAt <= computedAt <= committedAt/availableAt
                                                    <= uiDisplayedAt / transportAcceptedAt
```

源钟偏差与网络延迟使不等式无法验证时记录 timeQuality，不能强行改时间；源时点不能由本地 fetchedAt 代替。模型还记录 requestPreparedAt、providerReceivedAt、analysisCommittedAt、extractedAt；风险解释可用时点不能早于这些依赖提交。

快路径只取合格行情和用户policy，发布后异步建立/消费模型解释；邮件初报不等待模型。慢解释必须同股票/市场/源窗口且在 freshness预算内，过期只归档，不能覆盖新 episode。MVP不为解释额外建 demand；未来预算批准后仍经原canonical owner，不能第二次提交同 observation。

建议验证目标（不是当前能力承诺）：capturedAt->published p95<=5s；活跃页面新事件published->display p95<=10s；新鲜源数据的sourceAt->display p95<=90s。quote maxAge建议先60s，实际取供应商可信粒度与试点可接受值；只测本地计算5s不代表总预警90s达标。

邮件单独统计provider接受时延，不承诺送达/已读；页面关闭、手机后台、权限关闭分别显示通知覆盖限制。午休/盘后按适用机制标待可交易，不算计算链故障；临近收盘剩余退出时间不足单独标 `LATE_FOR_CURRENT_SESSION`。

停机期间不发补写的历史“实时”预警。重启先判断持仓仍活跃、时点与最新数据，再创建一个带缺口说明的新当前事件； missed机会不被删除。

### D08. 风险事件生命周期与去重

Assessment 是不可变输出；episode 是 CAS 状态投影。唯一活动键为 `ownerUserId+positionId+generation+riskFamily+protocolVersion`，同一个真实过程内30秒轮询不扩成几十次独立警告。

```text
合格首触发 -> OPEN
OPEN + 更严重/新紧急条件 -> OPEN并追加UPGRADED事件
OPEN + 足够合格恢复证据 -> RECOVERING -> RESOLVED
任何状态 + 数据缺失 -> 原状态保留，coverage降级
窗口过期 -> EXPIRED（不是确认恢复）
清仓/停止监控 -> CLOSED_POSITION / PAUSED，待发送义务取消
恢复后再次真实触发 -> 新episode，保留predecessor引用
```

保护价格首触及不要求等两次快照；候选“持续性”只能使用不同有效source时间，不把重复抓同一行情算持续。恢复次数/交易分钟在规则版本内固定。跨交易日已有风险不得午夜自动变健康，需新源观测确认，并按新窗口形成持续风险更新而非假新命中。

通知 eventKey 包含风险领域、owner、position generation、episode/transition、level和policy版本，不能仅靠 symbol/日去重。冷却压制同级重复，不压制第一次CRITICAL、明显升级或新增已触发保护条件。级别反复波动用恢复滞回，不在每次轻微抖动绕过冷却。

ACK/静音只影响通知，不改市场风险 verdict；有截止时间的静音不取消新严重升级，长期暂停明确 coverage=PAUSED。用户策略修改先结束旧policy事件、按新policy重新评估，防止陈旧提醒仍引用旧阈值。

### D09. 通知、事务、不确定交付

risk owner 在同一事务写 assessment、episode transition、published marker及邮件 obligation。提交后页面可读取；SMTP不在事务中。失败重试来自 durable row，不依赖仅内存事件/异步回调；风险提取或研究故障不回滚旧analysis success。

计算可在事务外使用冻结的positionVersion/policyVersion/generation，但发布事务必须锁定position当前行，核对ACTIVE、上述版本和行情仍合格，再CAS更新episode并写当前发布/obligation。PositionEvent使用同一position行锁与统一锁顺序（position -> episode -> delivery）；并发部分卖出、清仓或改策略先提交时，旧计算只保留为`SUPERSEDED_INPUT/NOT_PUBLISHED`审计，不写当前提醒，不生成发送义务，也不伪造availableAt；以新版本有限重评且不新增模型调用。发布先提交而成交后提交时，后者关闭/重评当前投影并取消未发义务；历史已发布结果保留，在途仍不可召回。读当前风险也检查版本，不把历史事件展示为新policy正在触发。

复用 `StockWatchAlertDeliveryService` 的 owner/lease/transport，建议增量字段：`sourceDomain=LEGACY|POSITION_RISK`、ownerUserId、positionId/generation、episodeId/transitionId、severity、policyVersion、expiresAt、cancellationReason、receiptQuality。legacy默认保持原语义，不能把现有邮件deadline悄悄改为新风险TTL。

新增接口 `createRiskObligations` 在业务事务中写入，仅解析此用户已确认通知配置；不继承按股票的全局收件人导致持仓泄漏。幂等键按transition+recipient+channel，不因邮件失败生成新transition。SUPPLEMENT只补当前episode的有效解释，不用supplement状态充当风险严重升级。

新风险 dispatch 前重新验证owner/持仓活跃、generation、policy、时效与episode；过期/清仓/暂停/新策略使尚未发送项 `EXPIRED|CANCELLED`。在途邮件无法召回，回执保留，不宣称取消成功撤销了已接受邮件。

风险项增加transport UNKNOWN：超时且无法确定接受状态时不盲目自动重发；固定Message-ID仅是关联线索，不是SMTP exactly-once保证。已确认未接受的失败才有限退避重试，且不能超过expiresAt。UNKNOWN/DEAD_LETTER在页面独立显示，已启用的另一合格渠道可使用其自身幂等键发送；不伪装“用户已收到”。

N0先确认用户的实际接收场景，再选择通知路径：常驻页面可采用认证HTTP每5s拉新transition；不常驻则先选用户授权且能满足接收要求的通道，N3完成验收后才承诺该场景。未确定离线通道时可继续持仓/影子开发，不能把页面工程闭环当成离线可用。持仓报价可另频率；不通过URL query token建立EventSource。浏览器通知/声音需用户授权并记录permission/browser支持；不能保证关闭浏览器或移动端后台仍及时接收。邮件只作已配置备用。可靠手机push是单独能力，需另选通道和验收，不列为已具备。

多实例靠数据库claim而非AtomicBoolean；风险新writer启用前所有delivery实例必须升级为领域过滤兼容reader并排空旧dispatcher。旧二进制不知道新领域过滤/fence，不能直接混跑或回滚；这是显式发布前置，不靠单机feature flag声称安全。

### D10. 存储设计与索引

以下全部为新增/增量设计，尚未建表；MySQL生产与H2测试都要确认实际索引/nullable unique语义。

| 表 | 必需内容与约束 |
| --- | --- |
| `stock_watch_position` | owner、instrument identity、accountAlias、generation、status、policy JSON/version/hash、monitorStartedAt、inventory确认时间/日期/quality、rowVersion；活动聚合nullable unique key，closed释放 |
| `stock_watch_position_lot` | positionId、lotId、boughtTradeDate、reportedAt、数量/remaining、cost可空、source/qualification、rowVersion；同position内lot唯一 |
| `stock_watch_position_event` | owner、position、clientEventId、INIT/ADD/SELL/CORRECTION/POLICY_CHANGE、eventAt/reportedAt、allocation与before/after版本；unique(owner,clientEventId)，append-only |
| `stock_watch_risk_market_observation` | instrument/provider/endpoint、source/received/captured、safe quote/path、quality、units/calendar/basis/hash；同源时间同hash幂等，修订另条，不覆盖 |
| `stock_watch_position_risk_assessment` | owner/positionVersion/policyVersion/generation、opportunityKey、obs/analysis refs、rule/feature/extractor版本、level/coverage/reasons、frozen input、publicationStatus与availableAt（未发布为空）；unique(opportunityKey,assessmentVersion,revision) |
| `stock_watch_position_risk_episode` | owner/position/generation/family、活动key、状态、first/lastAt、highestLevel、lastTransition、predecessor、rowVersion；活动key nullable unique，已关闭保留 |
| `stock_watch_position_risk_action` | owner/position/transition、clientActionId、DISPLAY_RECEIPT/ACK/HOLD/PLAN_EXIT、occurredAt/reportedAt、positionEventId可空；确认与成交真值分开 |
| `stock_watch_position_risk_evaluation` | opportunityKey、assessment/episode可空、opportunityOutcome/signal/execution协议版本、scheduledAt/availableAt、冻结窗口/参考价/anchor、路径证据、status/reasons、risk/lead/execution metrics、supersedes；终态不可改 |
| 既有 `stock_watch_alert_delivery_obligation` | 上述D09增量列/索引/状态分支；旧行不改事件含义、不默认拥有某用户 |

完整机会池包含活跃持仓每个计划槽位的 `NO_WARNING|WARNING|DATA_INELIGIBLE|MISSED|MODEL_UNAVAILABLE`。真正未运行的槽位由持久化capture checkpoint和日历/生效policy展开为MISSED，不能从成功assessment倒推100%覆盖；展开只记录机会，不补当时不可知风险结果。

机会`scheduledAt`与发布`availableAt`是不同合同；未发布槽位的availableAt为空。正式候选采集前另冻结`opportunityOutcomeProtocol`：以scheduledAt及预先规定的下一完整交易分钟anchor/最大延迟和结果窗建立所有机会的共同市场标签，使用合格结果路径及vintage，可标`OUTCOME_RECONSTRUCTED`，但不据后来下载的路径声称当时观察到或可执行。缺anchor/路径的MISSED保留UNSCORABLE，不填安全。告警自身效果仍按D13实际availableAt评分，不用scheduledAt提前计算其成功。

查询索引至少覆盖owner+active positions、instrument+source time、owner+position+assessment time/id、episode活动key、risk domain+due delivery、evaluation nextRetryAt。分页使用固定snapshotMaxId+评估asOf；风险统计不是第一页summary。

新增表由专属 readiness gate 管，默认OFF；同时同步 `StockWatchTechnicalSchemaInitializer`、`src/main/resources/sql/init.sql`、`schema-local.sql` 的明确additive升级DDL。新门只隔离新助手，不因新表缺失让旧分析/意图全部停机。禁止只CREATE IF NOT EXISTS冒充已有表ALTER完成。

建表按批次而非八张首批齐建：N1建position/lot/event；N2建market observation/assessment/episode及checkpoint；N3建action并升级既有delivery；N5才建evaluation。最小保护闭环是“登记 -> 合格报价 -> 用户保护条件 -> episode/发布 -> 已授权交付 -> 确认/人工成交登记”，不等待资金/分钟候选或evaluation表，但它只是工程可用子集，不代表完整助手或推断效果完成。

### D11. API与授权

沿 `/api/stock-watch` 新增下列端点；响应仍用 ApiResponse，owner从currentUserId取得。风险API用X-Token header，不在URL传认证或在日志记录持仓明细；所有按id读取/写入/统计/通知配置都检查owner。

| 方法/路径 | 行为 |
| --- | --- |
| GET/POST `/positions` | 本人列表/登记草稿或明确激活；quantity/日期需验证 |
| GET/PATCH `/positions/{id}` | 查看、改元数据/保护策略，expectedRowVersion；冲突409 |
| POST `/positions/{id}/events` | 追加人工成交/修正/暂停等事件，clientEventId幂等 |
| GET `/positions/{id}/risk` | 当前assessment、episode、coverage、可卖来源、辅助预测 |
| GET `/position-risk/events` | 本人transition流，afterId+snapshot上界，适合活跃页面轮询 |
| POST `/position-risk/events/{id}/actions` | DISPLAY_RECEIPT/ACK/HOLD/PLAN_EXIT；显示时点标CLIENT_REPORTED并保存serverReportedAt，不发单、不扣仓 |
| GET `/positions/{id}/risk-history` | 固定版本/来源/时间筛选和游标，全部状态可查 |
| GET `/position-risk/statistics` | 同filter/snapshot/evaluation版本的全范围分母与结果 |
| GET `/position-risk/runtime` | 观测/评估/模型旁路/交付分段新鲜度及本用户覆盖 |

一期不开放风险API到 `/n8n/**` 认证豁免，不返回私有 raw reply、账号、recipient明文、全量委托或模型私有trace。当前既有全局股票配置不是新的持仓授权边界。

JSON合同示例（示例值不代表真实用户持仓或规则有效）：

```json
{
  "positionId": 42,
  "positionVersion": 3,
  "symbol": "002156",
  "market": "sz",
  "riskLevel": "WARNING",
  "evidenceClass": "USER_POLICY",
  "validationState": "USER_DEFINED",
  "coverageState": "HEALTHY",
  "reasonCodes": ["USER_PROTECTION_PRICE_REACHED"],
  "sourceAt": "2026-10-09T02:10:00Z",
  "availableAt": "2026-10-09T02:10:04Z",
  "sellability": {"status": "RULE_ESTIMATED", "estimatedQuantity": 1000},
  "notification": {"uiPublished": true, "email": "UNKNOWN", "readConfirmed": false},
  "modelRisk": {"status": "UNAVAILABLE"},
  "directionForecast": {"status": "AVAILABLE", "evaluationVersion": "LEGACY"}
}
```

### D12. 工作台与人工操作

复用股票工作台导航，新增“持仓风险”视图，不新建营销页。密集表格显示：标的、登记量/可卖估计、现价/源时间、风险级别/来源、覆盖、首警时间/最近升级、通知状态、人工处理。排序按活跃严重事件，再按未确认/覆盖故障；数据陈旧不能排到安全区消失。

详情为未加装饰卡的分区：风险摘要、价格路径与警告标记、实际依据/反证、持仓批次、通知与动作时间线、完整预测辅助、意图研究入口。只展示真实业务内容和必要状态，不用长篇界面说明替代功能。

操作为确认、保持观察、计划退出、登记已卖/部分卖、修正持仓与暂停。明确“登记已卖”而非“卖出”，不会调用券商。通知直达相应事件而不是泛股票详情；未确认风险保持可见，严重升级可再次提醒。图标复用项目已有库，辅助tooltip，状态不只依赖颜色；桌面/窄屏都能看到源时延与锁定数量。

模型风险解释显示出处/形成时间/条件/反例/EXPERIMENTAL状态，与程序价格保护分别展示；完整预测不隐藏，也不挤占紧急风险第一屏。页面显示uiDisplayed/ACK与邮件transport状态的区别。

### D13. 风险窗口、真值与执行分层

预警发生在今天并不要求风险只在今天兑现。研究窗口显式保存：`REMAINDER_SESSION`、`NEXT_SESSION`，固定未来30/60交易分钟只作可比补充，不混合不同窗口准确率；目标日用已核验交易所日历，不用下载到的下根股票bar顺延。

风险评价分三层，不能互相替换：

1. **市场风险事件**：此发布版本可用后是否出现预先定义的不利价格路径，不称真实主力意图或成交收益。
2. **执行代理情景**：按发布后、可卖资格、可交易时段、延迟、成本及无法成交约定的退出 vs 持有，不称真实避损。
3. **用户报告成交复盘**：用户登记真实成交及时间后，在固定比较窗口与仓位上给归因有限的比较，不证明该提醒因果上创造全部收益。

`availableAt`冻结后，risk evidence evaluator选择第一条严格晚于availableAt且满足资格的观测为anchor；若只有分钟条，可另冻结 `NEXT_COMPLETE_MINUTE_ANCHOR_V1`，从信号后下个完整分钟的open开始，明确不评价发布到该分钟之间的风险。不能拿跨过availableAt的整根分钟low冒充信号后low。

anchor不得随迟到数据漂移，保存source/captured时间和是否研究代理；最大anchor延迟预注册。拿不到及时anchor标 `WAITING_ANCHOR|LATE_ANCHOR|UNSCORABLE`，不能删除失败案例。未来到价在通知之前已发生，计迟到/漏报维度，不计提前成功。

风险指标基础式：`worstReturnPct = (minEligiblePostAnchorPrice / frozenAnchorPrice - 1) * 100`；主阈值按窗口/波动分层预注册，-5%只保留为历史可比探索，不设全市场通用门。该式是相对anchor最差收益，不是严格峰谷最大回撤，也不是实际损失。窗口内卖出不截断市场风险结果，否则事后成功卖出会改变标签。

仅有采样报价时可报告 `SAMPLED_OBSERVED_RISK`：看见越阈值可确认观察到事件，但未看见不等于完整路径无事件。正式主统计的正/负标签均使用预注册完成路径与覆盖要求；分钟不完整、第一部分窗口缺失、停牌/尺度未知不能填0或“无风险”。终态冻结，供应商修订只追加新revision。

执行代理从该用户实际可知且能行动的时点开始：有ACK/真实显示使用相应接收证据；仅backendAvailable或SMTPAccepted时不得冒充已看见，可单列延迟假设。午休顺延到下一适用撮合；普通A股当天新买不可卖，停牌/涨跌停排队/盘口不足无证据则 `EXECUTABILITY_UNKNOWN`。不能用事后最低价反推完美卖出价。

比较策略先冻结，如“保持原登记数量至NEXT_SESSION完成收盘”vs“报警后固定反应延迟、仅可卖部分退出”；无再入场策略时不假设抄底回补。冻结原数量`Q`、可退出量`qExit`和两边均保留的`qRemain=Q-qExit`；退出侧为`exitProceedsNet(qExit)+retainedTerminalValue(qRemain)`，持有侧为`holdTerminalValue(Q)`，两边同窗口、币种与终值/费用约定。若剩余量的估值完全一致可相消，`benefitProxy = exitProceedsNet(qExit) - holdTerminalValue(qExit)`；绝不能只卖1000股的收入减持有1500股的终值。负值即错过上涨成本；另外报告尾部损失减少、无法退出比率与资金闲置，但不把风险事件命中率写成策略收益。

用户报告成交显示 `USER_REPORTED`，非券商核验；缺退出前持仓/卖出量/时间/费用或比较窗口不能给完整金额结论。显示/ACK的客户端occurredAt也须标CLIENT_REPORTED并保存serverReportedAt，不能用可篡改客户端时钟证明实际更早可用。成本不同币种不自动合并；真正账户级效果不从公开股票日线估算。

### D14. 机会池、统计与效果准入

机会在看到未来前冻结：活跃持仓、policy有效期、市场/时段、计划槽位、数据资格、模型是否计划能力。包括NO_WARNING、MISSED、MODEL_UNAVAILABLE、UNSCORABLE和通知失败；事件级主分析按episode首次及时警告，原始逐条仍保留。

一期分开保护条件执行质量与推断风险效果。保护提醒评价条件检测的漏发、错发、时延和覆盖，不用未来下跌证明其执行正确；候选/模型评价未来风险精确率、可观察池召回、每持仓日误报数、首警至风险时点、迟到、覆盖及执行代理机会成本。

精确率分母是合格成熟警告事件，召回分母是具有完整结果且在预注册持仓机会池里的风险事件；二者不相互替代，不称全市场召回。跨用户同股票同窗口共享外部价格路径，不能按用户数膨胀独立样本；同股连续警告、同日冲击、窗口重叠按instrument/date/episode成组。

召回使用D10的共同机会标签而非只给已发布告警建标签；告警匹配必须同标的/持仓generation、在注册最大提前窗口内实际可用且严格早于风险首次发生。晚发不补成命中，MISSED/NO_WARNING也可成为未匹配风险。重叠槽位的同一价格风险事件按预注册聚类/恢复规则计一次，逐机会覆盖仍全量保留。例如计划09:50但未运行，可按预注册09:51完整分钟anchor评价后续风险；若10:20发生事件且没有此前合格发布，就是可观察池漏报，不创造09:50发布记录。若缺合格路径则不可评分并进入缺失统计。

同机会池同时冻结简单价格保护/价格失效/价格+资金代理基线；对照不能按未来价格挑。比较相同窗口、源时延和可卖资格；评估盲测规则由research协议持有，不因UI显示用户的持仓路径而向调参流程披露未解盲的合并绩效。

预注册与样本积累不全部等到N6：协议/机会池/基线/结果窗先冻结；各候选的数据门合格后，随N2的市场结构或N4的已存模型回复开始独立前向SHADOW。冻结原始证据与机会后可等N5完成计分器，不能等未来结果已知才选候选或补回“前向”样本。N6负责盲测裁决与扩大准入，而非第一次开始采集；候选工程、预注册和效用未就绪时维持关闭，不为加快积累偷改V1或额外提交模型。

批准候选前必须冻结主事件、基线、最小有用增量、漏报/误报预算、覆盖/迟到上限、日期/标的最低覆盖、样本精度/检验方法与停止规则。具体数值需用户效用和数据PoC确认；工程测通或9个命中个例不是效果准入。旧priceFailure V1的120/30门不复制为所有新规则的通用样本量，也不修改它。

独立发布状态：

| 通道 | 可发布前置 | 不允许声称 |
| --- | --- | --- |
| 用户保护条件 | 用户确认policy+数据/持仓/交付工程门+明确启用 | 阈值最优或保证止损 |
| 观察性结构试点 | 工程门+用户明确试点同意+实验标识+通知预算 | 稳定收益/真实出货/已验证卖出信号 |
| 可信推断风险提醒 | 独立前向效果门+审核+单独启用批准 | 所有未来风险都能规避 |
| 自动交易 | 本设计不支持 | 通过上述门即可自动下单 |

### D15. 验证与测试矩阵

| 层 | 必测反例 | 验收证据 |
| --- | --- | --- |
| 持仓/权限 | 未知成本、今天加仓、部分卖、重复clientEventId、跨用户id、余额负数/并发 | Java纯测试+Mapper事务/H2/MySQL+认证API |
| 数据 | requested/provider市场错、前导零、推迟源、缺f124、乱序/修订、未完成分钟、企业行动/单位未知 | adapter fixture+真实供应商PoC，未知不能变安全 |
| 识别 | P01首触、缺资金不阻断、累计delta跨日、重复源不算持续、P02启用前high不可用 | pure evaluator边界测试与解释引用 |
| 模型 | 否定/引述/历史/条件/模糊、迟到、冲突UP与风险、失败不阻断 | deterministic extractor+无额外submission计数 |
| 事件 | 升级绕冷却、反复抖动、缺数据不恢复、跨日持续、策略切换、清仓重买generation | CAS/unique/重启/多实例事务测试 |
| 通知 | SMTP拒绝/超时UNKNOWN、过期/清仓取消、跨用户收件人、在途无法召回、commit后崩溃 | durable obligation+transport受控测试，不给自己发送外部邮件冒充验收 |
| 路径 | source!=available、窗口前low、anchor迟到、跨午休、停牌、部分分钟、旧历史没有发布 | versioned evaluator fixture+证据id/revision |
| UI | 未登录、窄屏、后台tab、权限关闭、声音静音、点击事件、确认不扣仓、完整预测仍可见 | Playwright/browser截图、网络/console、状态核对 |
| 运行 | 20标的公平、provider限流、模型busy、DB失败、浏览器关闭、停机重启 | 分段p50/p95、coverage缺口、实际事件/交付IDs |
| 回归 | 旧v6/v7、RETURN_SIGN、游标/日期分组、GENERAL提醒、V1 hash/cursor | 旧相关测试+新旧隔离/hash证明 |

实时闭环需在真实有效交易时段做“已确认人工持仓 -> 新鲜源 -> 首警/升级 -> UI/邮件transport -> 人工确认 -> 成交登记 -> 后续完成路径复盘”。不为了烟测下真实单；可用用户明确的测试持仓/历史fixture证明工程链，但不得宣称真实避损。跨两个有效交易日核次日窗口，长效表现另等前向样本。

## Risks / Trade-offs

- 免费源真实延迟可能不适合快速退出：先PoC测量，超预算就DEGRADED/不可启用及时性承诺；采购/换源另确认。
- 公开资金口径不完整：禁止强身份叙事和硬资金准入；先价格保护，资金候选独立检验。
- 模型解释慢/不稳定：快路径不等模型；迟到保存而不装提前，默认零新增调用。
- 频繁误报让用户提前离场：冻结预算、episode去重/恢复滞回，计错过上涨；不能只追召回。
- 未接券商持仓可能过时：显式USER_REPORTED/估计数量、更新提示、未知可卖状态；不执行订单。
- 邮件非实时且超时结果未知：页面与transport区分，有限重试与UNKNOWN，不声称exactly-once/已读。
- shared行情+私有策略混用导致泄漏：个人记录全部owner授权，公开observations只含市场数据；通知不继承全局收件人。
- 新高频观测污染冻结旧研究：风险观测独立，V1输入/规则/游标owner不变；复用纯工具需合同一致。
- 初期证据不足：先保护条件与工程可用性，候选留影子/明确试点；无证据不承诺效果。

## Migration Plan

| 批次 | 内容 | 前置与完成边界 |
| --- | --- | --- |
| N0 | 持仓/分能力数据/通知合同PoC、用户保护条件与接收场景 | 可与旧M0并行；先选常驻/离线路径，只确认支持子集，不声称实时 |
| N1 | Position/lot/event/API/录入工作台 | 认证、schema/事务门；先DRAFT，不发提醒 |
| N2 | 快速报价、P01-P03、coverage、assessment/episode影子；合格候选并行采集 | N0对应能力门+N1；报价不等分钟/资金，候选先冻结协议；不依赖DeepSeek或完整预测效果 |
| N3 | 可靠发布/交付、升级/取消、UI与确认 | N2+delivery兼容reader；用户明确启用保护条件后才通知 |
| N4 | 原回复风险解释候选、与预测/意图独立显示 | 可并行开发；正式采集依旧M3及时间证据，不等M4新合同 |
| N5 | 信号后评价、执行代理、人工成交复盘 | 复用旧M1已验证日历/尺度工具；新增盘中路径需独立验证 |
| N6 | 前向对照裁决、候选效果准入与试点扩大 | 预注册前移至首次N2/N4正式SHADOW前；新样本+审核，不用工程烟测替代 |

配置建议：`position-risk.enabled=false`、`mode=OFF|SHADOW|PILOT|LIVE_PROTECTION`、captureIntervalMs、quoteMaxAgeMs、maxActiveInstruments、enabledRuleSets、modelEvidenceEnabled、notification opt-in、deliveryGeneration。不是当前配置已经存在。

先部署additive schema/dual-reader/readiness，OFF验证权限和upgrade；再SHADOW采集；受控验收保护条件后由用户显式启用。推断候选独立开关和效果状态，不能随LIVE_PROTECTION自动变可信。所有配置/规则变化留下版本/启用时间。

回滚：关闭新capture与publish，取消尚未发送的新领域义务并保留UNKNOWN/在途事实；保留持仓event、assessment、evaluation与协议。旧预测/意图/priceFailure继续运行，不复位其cursor、不开放原UNKNOWN重复提交；不能回滚到不了解新delivery领域的旧二进制。重新启用先取新鲜源、核持仓和缺口，不重发历史事件。

## Open Questions

必须在相应实施/启用前解决，不阻塞本轮设计交付：

1. N0：当前源在真实交易窗口的年龄、身份、单位、完整分钟/收盘完成合同是否满足？不满足可先持仓录入，不能上线快速保护承诺。
2. N1/N3：用户实际持仓范围、保护价格/回撤/金额设置、通知选择与允许误报预算。默认不替用户设止损数值、不发外部通知。
3. N0先选择、N3验收：页面常驻还是需手机后台提醒？一期只保证已测通的覆盖范围，可靠离线手机通道需另选。
4. N4/N6：模型早期条件警告和强措辞的独立新协议、前向效果门与试点同意。未知时保持SHADOW，不借旧V1解盲门偷渡。
5. N5/N6：执行比较窗口、可卖数据更新频率、反应延迟/费用/滑点与无法成交假设。未知则只报市场风险，不报个人节省金额。

本方案不承诺损失一定减少。要证明用户目标，必须同时存在及时信号、有效通知、可卖持仓和可达成交；任一环节失败都可使算法命中却无法避险。
