# 股票盯盘 L0 风险结构预筛与邮件同步技术方案

状态：独立 Challenge/Optimize 第八轮对本文内部方案复审通过；主 agent 最终跨方案自审不通过，禁止按本文独立编码。

本文已经历七轮独立 reviewer 质疑、逐项核实和修订；第八轮 Challenge 与 Optimize 对同一版本均明确判定通过。主 agent 随后进行跨文档最终自审，发现本文与仓库中声明为唯一实施规范的 `stock-watch-continuous-main-force-intent-plan-v16.md` 存在 material ownership 冲突：本文不能作为独立实施规范，只能保留为 L0 风险公式、事实边界和邮件展示的候选 addendum。当前系统已有 quote、日 K、技术指标、主力资金、最近两周历史技术分析，足以做一层 L0 级别的“风险结构/追高风险”预筛；它不足以证明主力操纵、账户组行为、对倒、控盘，也不足以完整实现所有 Skill 包里的 L1/L2/L3 模式。

## 1. 目标

1. 在异动判断时，把最近 14 个自然日内每个唯一成功 slot 的技术分析纳入“全量紧凑观点账本”，并选择近期/转折/证伪记录作为详细上下文；让模型不是总结行情，而是连续验证“之前判断被支持、削弱、证伪或仍未决”，逐步缩小主力意图假设范围。
2. 在模型调用前增加本地确定性 L0 风险结构预筛，让 DeepSeek 解释“哪些可观察风险结构正在出现、哪些反证正在削弱旧判断、哪些正常解释仍然存在”，避免模型被单一技术指标误导。
3. 把这部分“风险结构 + 历史观点复核 + 主力意图连续判断”同步写入 AI 预警邮件，邮件不是只发当日 DeepSeek 分析。
4. 保持现有盯盘链路：程序计算事实，模型做解释和假设更新；本轮触发依据仍只能来自 quote 或 `aiContext.currentFacts`，历史分析只能用于 `analysis/beliefUpdate`。

## 2. 非目标

1. 不在本阶段接入 L2 逐笔、盘口队列、委托撤单、账户组、龙虎榜席位等更高等级数据。
2. 不在本阶段输出“操纵/坐庄/违法/对倒/控盘”等事实性结论。
3. 不做第二次 DeepSeek 复核调用；所有后校验只在本地完成，避免增加封号和速率压力。
4. 不重构技术分析、异动判断、邮件投递三条链路，只在现有 AI 盯盘上下文和邮件内容上增量扩展。
5. 不把所有 Skill 包模式一次性实现。第一批只实现当前数据能支撑的 4 个 L0 风险模式。

## 3. 当前代码事实

代码/事实证据均为本轮重新检查，不引用过期记忆作为当前事实。

| Evidence | 类型 | 代码位置 | 说明 |
|---|---|---|---|
| E1 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:602` | `buildAiContext` 负责给 AI 异动判断构造上下文。 |
| E2 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:603-618` | 即使不需要增强行情，只要有最近技术分析，也会传 `recentTechnicalAnalysis`。 |
| E3 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:622-647` | 增强上下文由 `EastmoneyMarketDataService.buildBundle(..., "DAY")` 生成，并放入 `currentFacts`。 |
| E4 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:666-671` | `requiresEnhancedAiContext` 当前只识别技术面、均线、MACD、RSI、资金流、主力等词，还不包含“失败突破/放量滞涨/长上影/追高风险”等风险结构词。 |
| E5 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java:34-41` | AI 输出 JSON 字段固定包含 `beliefUpdate`，可以在其中承载风险结构复核，不必先新增顶层字段。 |
| E6 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java:314-315` | prompt 已声明事实优先级：本轮 quote > `aiContext.currentFacts` > 历史技术分析验证；历史只能用于分析、反证、风险和观察点。 |
| E7 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java:319-335` | `sanitizeAiContext` 只特殊裁剪 `recentTechnicalAnalysis`，其它字段原样通过；风险预筛需要显式控制大小和字段。 |
| E8 | 直接证据 | `src/main/java/com/aimeeting/room/service/EastmoneyMarketDataService.java:61-95` | `buildBundle` 已提供 quote、日 K、sourceTradeDate、technicalIndicators、anchorFacts。 |
| E9 | 直接证据 | `src/main/java/com/aimeeting/room/service/EastmoneyMarketDataService.java:197-225` | 日 K 字段包括 open/close/high/low/amount/changePct/turnoverPct。 |
| E10 | 直接证据 | `src/main/java/com/aimeeting/room/service/EastmoneyMarketDataService.java:615-632` | `anchorFacts.unitPolicy` 明确金额为人民币元，比例字段为百分数，不能让模型重复换算。 |
| E11 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:1543-1580` | AI 预警邮件当前只有触发摘要、关键行情、DeepSeek 分析、触发依据、观察条件，没有风险结构/反证/历史观点复核段落。 |
| E12 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:1583-1610` | AI 邮件通过单股 `StockWatchDeliveryConfig` 发出，改造点应在 `sendAiTriggeredEmail` 内容构建，不需要改 NotificationService。 |
| E13 | 间接证据 | `src/test/java/com/aimeeting/room/service/StockWatchAiJudgeServiceTest.java` 与 `StockWatchMonitorServiceTest.java` | 当前已有 AI prompt、历史技术分析上下文、邮件发送门控相关测试，可增量补测试；但还没有 risk pattern 测试，也没有专门验证邮件正文新段落的测试。 |
| E14 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java:232-245` 与 `StockWatchMonitorService.java:348-354` | 结构化规则和模型共用顶层 `confidence`，且该值直接参与告警阈值判断；风险解释 cap 不能无条件覆盖它。 |
| E15 | 直接证据 | `src/main/resources/application.yml:154-156`、`StockWatchTechnicalAnalysisService.java:59-70`、`StockWatchMonitorService.java:692-735,1344-1357` | 14 天窗口最多理论包含约 100 个半小时 slot；当前只取 48 条且字符裁剪继续删除最旧记录，不能声称“每轮都进入上下文”。 |
| E16 | 直接证据 | `src/main/java/com/aimeeting/room/service/EastmoneyMarketDataService.java:74-95,647-663` | `buildBundle` 会重新抓 quote，现有日期质量只比较 bundle quote 与 dailyKline，没有比较盯盘外层 quote。 |
| E17 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:857-920` | 当前 `priorValidation` 是程序基于本轮 quote/currentFacts 对每条历史意图生成的 verdict；它比模型自由叙事更可审计，但尚未尊重历史判断的时点和 `timeWindow`，不能直接当作主力真实意图事实。 |
| E18 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:350-393` | AI 邮件仅在规则触发、达到阈值并通过告警/频控门后发送；本方案不新增“每轮都发邮件”的独立链路。 |
| E19 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:387-390,696-698` 与 `StockWatchMonitorService.java:880-920` | 每条成功技术分析已保存带 `timeWindow` 的结构化 mainForceIntent 和完整 dataSnapshot；当前 priorValidation 却只用本轮 quote/currentFacts 重算，没有使用下一 slot 快照，也没有检查 timeWindow。 |
| E20 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:340-350,692-709` | 当前 AI 入口只校验 `quote.ok`，没有相对执行时点的新鲜度校验；历史窗口上界使用系统 `now`，不是 canonical quote timestamp。 |
| E21 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:212-218,323-330,1740-1748` | 手工分析默认允许交易时段外执行，并可生成任意分钟 runSlot；14 天唯一 slot 数不能无条件假设最多约 100。 |
| E22 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchAiRuleService.java:275-286` 与 `StockWatchMonitorService.java:348-354` | AI 规则默认 `minConfidence=0.75` 且顶层 confidence 直接控制告警；最高 0.70 的 risk cap 若作用于顶层 confidence，会使默认纯风险规则无法触发。 |
| E23 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:923-996` | 当前方向复核直接读取后继时点累计 `changePercent/mainInflow`；同日连续判断若不改用相邻区间 delta，会把“仍为正但已明显回落”误判为继续支持。 |
| E24 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchAiRuleService.java:116-130,275-289` | 当前规则 schema 没有 capability/domain 字段；仅靠 conditionText 关键词升级风险域会改变包含重叠词的存量规则语义。 |
| E25 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:621-645` | 当前 bundle 任一 `requiredMissing` 都会提前返回并不构建 currentFacts；freshness 缺口若写入全局 requiredMissing，会违背“只跳过 risk、不影响 GENERAL”的兼容目标。 |
| E26 | 直接证据 | `src/main/java/com/aimeeting/room/controller/StockWatchAiRuleController.java:31-47` | 规则 create/update 已捕获 `IllegalArgumentException` 并返回 `ApiResponse.fail`，非法 capability/mode 可沿用现有参数失败通路，无需静默归一化。 |
| E27 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java:302-309` | 当前 prompt 直接序列化整个 `AiWatchRule`；新增字段即使为空也可能进入 GENERAL prompt，必须用旧字段投影锁住兼容形状。 |
| E28 | 直接证据 | `src/main/java/com/aimeeting/room/service/StockWatchAiRuleService.java:97-113`、`StockWatchMonitorService.java:153-190` 与 `StockWatchMonitorController.java:27-32` | 手工 monitor API 可传 transient aiRules；当前 parse 异常没有 controller 参数失败边界，且解析位于整轮行情/评估之前，需保证非法请求零部分执行。 |
| E29 | 直接证据 | `src/main/java/com/aimeeting/room/entity/StockWatchTechnicalAnalysis.java:39` 与 `StockWatchTechnicalAnalysisService.java:212-218,301-323` | 技术分析记录已有实际生成时间 `gmtCreate`，而 manual 分析没有跨请求串行锁；只比较 quote timestamp 会让较早完成的并发记录成为较晚观点的“后继证据”，产生回看泄漏。 |

## 4. 审核后修订结论

### 4.1 降级后的核心判断

当前数据能支撑：

1. L0 可观察风险结构：基于 quote、日 K、成交额/换手率/量比、主力资金、均线/布林/MACD/RSI 生成风险提示。
2. 历史观点连续验证：基于最近 14 个自然日的唯一成功 slot 观点账本，用下一次可用分析快照验证上一次判断，并按 `timeWindow` 生成截至本轮的程序化复核；模型只能解释旧假设被支持、削弱、证伪、混合、到期未验证或仍未决。任何被截断、遗漏或缺少窗口末端证据的范围必须显式记录。
3. 邮件同步：把模型的连续判断和本地风险结构摘要发给用户，形成可追踪审计材料。

当前数据不能支撑：

1. 操纵事实认定。
2. L1/L2 盘口行为归因。
3. 行业真实广度确认。
4. 对资金来源、账户性质、机构席位进行事实判断。

### 4.2 第一阶段只做 4 个 L0 模式

| ID | 名称 | 状态 | 需要数据 | 允许结论 | 不允许结论 |
|---|---|---|---|---|---|
| R01 | 失败突破 / 假突破风险 | 第一批实现 | 最近 N 日高点、当日 high/close/open/changePct/amount/turnover | “突破后收盘未站稳，存在追高失败风险” | “主力诱多/操纵出货” |
| R02 | 放量滞涨 / 高换手无进展 | 第一批实现 | amount、turnoverPct、volumeRatio、close、近 N 日收益 | “资金活跃但价格推进效率下降” | “对倒放量” |
| R03 | 高开回落弱版本 | 第一批实现 | open、close、high、previousClose、changePct | “高开后回落，日内承接不充分” | “消息兑现/利好出尽” |
| R04 | 长上影线聚集 | 第一批实现 | open/close/high/low，近 3-5 日 K 线形态 | “上方抛压反复出现” | “庄家压盘” |
| R10 | 板块不确认 | 暂缓或降级 | 当前有 marketBenchmark、行业/板块标签、新闻等弱代理，但没有行业广度确认 | “市场/基准弱确认代理” | “行业资金不配合” |
| R12 | 大额成交/流动性脆弱 | 暂缓 | 原模式需要 L1/L2；当前只有 amount/turnover | 最多作为观察项 | “撤单/盘口失衡” |

## 5. L0 数据口径与确定性公式

比例字段统一使用百分数，例如 3.5 表示 3.5%，与 `anchorFacts.unitPolicy` 对齐。所有阈值均为第一版固定参数，后续只能通过配置和影子评测调整，不能让模型自行改变。

### 5.1 当前观察与历史基线

1. `canonicalQuote` 固定为 `StockWatchMonitorService.evaluateAiRule` 已取得的外层 quote。它提供当前交易日的 `price/open/high/low/previousClose/amount/turnover/volumeRatio/mainInflow/timestamp`。
2. 给 `EastmoneyMarketDataService.buildBundle` 增加可选 `canonicalQuote` 重载；传入有效 quote 时不得再次联网抓 quote，原有无参重载保留给其它调用方。
3. 每次评估显式传入 `evaluationAt`，并从 `canonicalQuote.timestamp` 按 Asia/Shanghai 解析 `quoteObservedAt/quoteTradeDate`，计算 `quoteAgeSeconds` 和 `freshnessStatus`。无法解析时，R01-R04 全部 `skipped=current_quote_timestamp_missing`，不能回退系统日期制造本轮事实。
4. `completedDailyBars` 只包含 `bar.date < quoteTradeDate` 的日 K。若日 K 已含当日未完成 bar，必须从历史均值、百分位和突破基线中排除。
5. 最新日 K 日期等于 quote 日期或落在 quote 日期之前都可以成立；“日 K 比盘中 quote 晚一个交易日”本身不是错误。只有日 K 日期晚于 quote 日期、bundle symbol/market 不一致，或 canonical quote 与可选内部 quote 交易日不一致时才判冲突。
6. 实时扫描的新鲜度规则固定为：交易时段内 `quoteTradeDate` 必须等于 `evaluationAt` 的上海日期，`-30 <= quoteAgeSeconds <= 300`；任一不满足时 `freshnessStatus=STALE_DATE/STALE_AGE/FUTURE_QUOTE`。周末、节假日或盘前手工执行为 `OUTSIDE_REALTIME_SESSION`；盘后同日收盘 quote 单独按下一条判断。这些状态只让 R01-R04 skipped，不改变原有结构化规则和普通模型 quote 规则的兼容行为。
7. `marketPhase` 由 `evaluationAt` 会话位置与 freshness 共同确定，不能只看 quote 自身时钟：实时交易窗口且 quote 新鲜为 `INTRADAY`；工作日同一交易日收盘后、quote 时间不早于 14:57 且 OHLC/amount 有效为 `POST_CLOSE`，不套用盘中 300 秒阈值；其它为 `OUTSIDE_SESSION`。本阶段没有交易所日历时，工作日只是一项必要条件，quote 日期和时效仍必须同时成立。
8. INTRADAY 时，累计成交额和累计换手率不能与历史完整日直接比较：`amountRatio20`、`turnoverPercentile20` 只计算为观察值但 `eligibleForTrigger=false`。盘中量能触发只使用行情源提供的时间归一化 `volumeRatio`。
9. POST_CLOSE 时，才允许 `amountRatio20`、`turnoverPercentile20` 进入触发条件。
10. 最近两周历史查询的上界固定为 `quoteObservedAt`，不得把 canonical quote 之后才生成的分析记录带入本轮上下文；quote 不新鲜时仍可构建历史账本，但本轮 risk observations 全部 skipped。
11. R01/R02 需要至少 20 根已完成日 K；R04 需要当前 quote 合成 K + 至少 4 根已完成日 K。数据不足时对应模式直接 skipped，不用低置信度猜测。
12. 任何分母为 null、非有限数或 `<= 0` 时，派生值为 missing；不使用 epsilon 把无效行情伪装成极端比例。

### 5.2 基础派生量

1. `close = canonicalQuote.price`。
2. `dailyReturnPct = canonicalQuote.changePercent`；缺失时才计算 `(close - previousClose) / previousClose * 100`。
3. `gapOpenPct = (open - previousClose) / previousClose * 100`。
4. `intradayFromOpenPct = (close - open) / open * 100`。
5. `closeLocationValue = (close - low) / (high - low)`，合法范围裁剪到 0-1。
6. `upperShadowPctOfRange = (high - max(open, close)) / (high - low)`，合法范围裁剪到 0-1。
7. `upExcursionShare = max(0, high - open) / (high - low)`。
8. `breakoutHigh20 = max(high of previous 20 completedDailyBars)`。
9. `breakoutPct = (high / breakoutHigh20 - 1) * 100`。
10. `amountRatio20 = canonicalQuote.amount / avg(amount of previous 20 completedDailyBars)`，仅 POST_CLOSE 可触发。
11. `turnoverPercentile20 = count(previousTurnoverPct <= canonicalQuote.turnover) / 20 * 100`，取值 0-100，仅 POST_CLOSE 可触发。

### 5.3 R01 失败突破风险

1. 突破：`breakoutPct >= 0.5`。
2. 确认失败：`close <= breakoutHigh20`；弱承接观察：`close > breakoutHigh20 && closeLocationValue <= 0.45`。
3. 活跃度：INTRADAY 使用 `volumeRatio >= 1.2`；POST_CLOSE 使用 `volumeRatio >= 1.2 || amountRatio20 >= 1.2 || turnoverPercentile20 >= 70`。
4. `status=triggered`：突破、确认失败、活跃度三项都满足；`status=watch`：突破后确认失败但活跃度不足，或突破后仍站上突破位但出现弱承接观察；其它为 `not_triggered`。
5. `riskReviewConfidenceCap=0.70`。允许结论仅为“突破后未站稳，存在追高失败风险”。

### 5.4 R02 放量滞涨 / 高换手无进展

1. 活跃度：INTRADAY 使用 `volumeRatio >= 1.8`；POST_CLOSE 使用 `volumeRatio >= 1.8 || amountRatio20 >= 1.5 || turnoverPercentile20 >= 80`。
2. 价格无明显推进：`-1.5 <= dailyReturnPct <= 1.5` 且 `abs(intradayFromOpenPct) <= 1.0`。
3. `status=triggered`：1、2 同时满足；否则为 `not_triggered`。`dailyReturnPct < -1.5` 属于明显下跌，不得误标为“滞涨”。
4. `mainInflow > 0` 且价格无推进只能形成“资金流与价格效率背离”观察，不能输出派发/对倒结论。
5. `riskReviewConfidenceCap=0.68`。

### 5.5 R03 高开回落弱版本

1. `gapOpenPct >= 1.5`。
2. `intradayFromOpenPct < 0` 且满足 `intradayFromOpenPct <= -1.0 || closeLocationValue <= 0.35`。
3. `upExcursionShare >= 0.25`。
4. 三项同时满足才 `status=triggered`，否则 `not_triggered`；`riskReviewConfidenceCap=0.62`。
5. 只允许输出“高开后回落、日内承接不足风险”。没有带时间和标题的当日公告/新闻事实时，不得输出“消息兑现/利好出尽”。

### 5.6 R04 长上影线聚集

1. 单根长上影定义：`upperShadowPctOfRange >= 0.45 && closeLocationValue <= 0.55`。
2. 将当前 canonical quote 合成 K 与之前 4 根 completedDailyBars 组成 5 根窗口。
3. 当前 K 满足条件 1，且 5 根中至少 2 根满足条件 1，才 `status=triggered`；否则 `not_triggered`。
4. 与前高、布林上轨、MA60 的“接近”仅作为确定性观察：`abs(referencePrice / level - 1) <= 0.02`。它不改变 status，也不产生 score。
5. 第一版删除未定义 `score` 字段；`riskReviewConfidenceCap=0.66`。

### 5.7 两种置信度不可混用

1. `alertTriggerConfidence` 对应现有 `AiDecision.confidence`，只决定规则是否达到告警阈值。
2. `riskReviewConfidence` 是模型对 L0 风险结构解释的可信度，只存在于本地校验后的内部字段和 `beliefUpdate.riskPatternReview`。
3. `riskReviewConfidenceCap` 永远只约束 `riskReviewConfidence`，绝不约束 `alertTriggerConfidence`。否则 R01-R04 最高 0.70 会在默认 `minConfidence=0.75` 下天然无法触发邮件。
4. 触发来源使用集合 `triggerEvidenceSources={STRUCTURED_RULE, MODEL_QUOTE, RISK_PATTERN}`，不再用“structured 未命中就等于 risk pattern”的单一枚举。`MODEL_QUOTE` 和现有 `STRUCTURED_RULE` 的顶层 confidence 语义保持不变。
5. `alertTriggerConfidence` 由现有模型 decision 与结构化规则合并逻辑产生；后校验只在合法触发依据被全部删除时撤销触发，不用 risk cap 重写该数值。邮件分栏展示顶层告警 confidence 与已封顶的风险解释 confidence。

## 6. 上下文结构与历史覆盖

### 6.1 事实与解释候选分层

`currentFacts` 只放程序计算的可复核事实：

```json
{
  "currentFacts": {
    "riskPatternObservations": {
      "dataLevel": "L0",
      "evaluationStatus": "ACTIVE",
      "asOfTradeDate": "2026-07-16",
      "marketPhase": "INTRADAY",
      "riskEligibilityMissing": [],
      "sourceQuality": {
        "canonicalQuoteSource": "monitor.quote",
        "canonicalQuoteTradeDate": "2026-07-16",
        "latestDailyKlineDate": "2026-07-16",
        "completedBaselineBarCount": 20,
        "consistent": true
      },
      "derived": {
        "dailyReturnPct": 0.8,
        "gapOpenPct": 1.2,
        "intradayFromOpenPct": -0.4,
        "closeLocationValue": 0.38,
        "upperShadowPctOfRange": 0.48,
        "breakoutPct": 0.7,
        "volumeRatio": 1.82
      },
      "patterns": [
        {
          "patternId": "R01",
          "status": "triggered",
          "conditionsMet": ["breakoutPct=0.7 >= 0.5"],
          "evidencePaths": ["quote.high", "currentFacts.riskPatternObservations.derived.breakoutPct"]
        }
      ]
    }
  },
  "riskPatternGuidance": {
    "dataLevel": "L0",
    "riskReviewConfidenceCaps": {"R01": 0.70},
    "counterEvidenceCandidates": ["主力净流入仍为正，不能直接推断派发"],
    "normalExplanationCandidates": ["临近历史压力位的正常换手"],
    "invalidatesWhen": ["后续放量站稳突破位"],
    "skippedPatterns": [{"patternId": "R12", "reason": "requires_l1_l2"}],
    "legalConclusion": "not_determined"
  }
}
```

事实边界：`triggered/reason/matchedSignals` 只能引用 quote 或 `currentFacts.riskPatternObservations`；`riskPatternGuidance` 只能用于 analysis、反证、正常解释和下一轮证伪点。

`evaluationStatus` 是程序三态：至少一个 R01-R04 为 triggered/watch 时为 `ACTIVE`；没有 active 且 R01-R04 全部完成评估时为 `EVALUATED_NONE`；没有 active 且任一模式因 freshness/source/data 缺失未评估时为 `SKIPPED_UNAVAILABLE`，并列出 `riskEligibilityMissing/skippedPatterns`。后两者不得互换。

大小控制：事实部分必须保留 R01-R04 全四个模式的最小状态；每个最多 3 条条件和 4 条 evidence path，目标不超过 2KB；guidance 目标不超过 1KB。不能在模型输入前按“最多 3 个”裁掉某个 triggered pattern；邮件展示可以独立限长。`sanitizeAiContext` 必须对两部分使用 allowlist 和硬长度限制。

### 6.2 最近两周观点账本

1. mapper 新增“以 `quoteObservedAt` 为上界、向前 14 个自然日的全部 SUCCESS、按时间倒序、不设 LIMIT”查询；Java 用 `(analysis_date, run_slot, period_type)` 做 key，只保留每个 slot 最新一条。手工执行产生的任意分钟 slot 也属于候选，不能假设唯一记录永远最多 100 条。

上界失败规则：opt-in 分支若 canonical quote timestamp 缺失/不可解析，禁止回退 `evaluationAt/runSlot`，也不执行增强历史查询；`recentTechnicalAnalysis.status=unavailable`、ledger/detail 为空，coverage 写 `upperBoundStatus=UNAVAILABLE/upperBoundReason=quote_timestamp_missing/fullLedgerComplete=false`。ADVISORY 仍可由旧 MODEL_QUOTE/STRUCTURED_RULE 触发，但模型和邮件不得声称完成两周复核。

2. 每条历史记录只从 `dataSnapshotJson` 提取 compact anchorFacts 和 quote timestamp，不把原始 snapshot 传给模型；同时读取记录 `gmtCreate` 作为 `decisionAvailableAt`。为记录 i 寻找 immediate evidence 时必须满足 `evidenceObservationAt > max(sourceObservationAt, decisionAvailableAt)` 且证据位于原 timeWindow 内。共享同一 snapshot 的 manual 记录不能互相验证；并发记录即使 quote 更晚，只要证据观测早于观点实际生成，也不能验证该观点。`runSlot` 只用于标签和去重。
3. 同日 immediate 比较必须使用区间增量：`priceDeltaPct=(price2/price1-1)*100`、`mainInflowDelta=mainInflow2-mainInflow1`、`amountDelta=amount2-amount1`。mainInflow 的正负变化本身有意义；只有字段缺失/非有限，或本应单调的 amount 出现负 delta 等明确重置证据时，对应 delta 才为 missing。跨交易日只使用区间价格收益和下一交易日独立资金观察，不能把两个交易日的累计 mainInflow 相减。
4. 同时生成 `currentAsOfVerdict`，但必须尊重 `mainForceIntent.timeWindow` 和成熟度：窗口开放时为 `maturity=PROVISIONAL`；真实窗口终点已经由后继 completed K 固化且有精确末端 observation 时为 `FINAL`；窗口已结束但缺少末端证据时为 `EXPIRED_UNVERIFIED`。不得拿原窗口外或早于观点生成的数据宣称旧判断被支持/证伪。
5. `recentTechnicalAnalysis.ledgerSchema` 固定为 `[id,observationAtEpoch,decisionAvailableAtEpoch,slot,intent,timeWindow,intentSource,intentConfidence,immediateVerdict,currentVerdict,priorClaim]`，`ledgerRows` 使用紧凑数组和 epoch 秒；detail 才使用 ISO 时间。`priorClaim` 初始最多 120 字符。`legacy_text_fallback` 必须显式标记，不能与结构化校验通过的 intent 等权。
6. `detailRecords` 最多 12 条，按以下顺序去重选择：最近 6 条；immediate/current outcome 为 FALSIFIED/MIXED；相邻 observation 的 intent 发生变化；剩余额度补最新记录。
7. 每次必须输出覆盖元数据：`queriedRowCount/duplicateRowsDropped/uniqueSlotCount/ledgerIncludedCount/omittedSlotCount/detailIncludedCount/fullLedgerComplete/truncatedByChars/oldestIncludedAt/newestIncludedAt/omittedOldestAt/omittedNewestAt`。`fullLedgerComplete` 固定定义为 `ledgerIncludedCount == uniqueSlotCount && allSlotKeysIncluded && allRequiredVerdictsIncluded`；它只表示紧凑账本覆盖完整，不表示 `priorClaim` 未截断，也不表示每条判断都已有最终结论。
8. 在正常定时 slot 约 100 条时，裁剪顺序为 priorClaim 120 -> 80 -> 40 字符，再裁 detail evidence/checks 和 detail 数量，不得先删 ledger。若手工/异常数据使最小 ledger 仍超过 24KB，则按真实 observationAt 保留最新可容纳行，记录 omitted 范围，并令 `fullLedgerComplete=false/allSlotKeysIncluded=false`；固定硬预算优先于“任意数量记录全保留”的不可能承诺。
9. 上下文总预算目标 32KB：ledger 24KB、detail 5KB、risk facts/guidance 3KB。`stock-watch.monitor.ai-context-technical-max-chars` 默认从 16000 调整为 29000，`effectiveTechnicalMaxChars` 上限从 30000 调整为 40000。coverage 同时记录 `allSlotKeysIncluded/allRequiredVerdictsIncluded/allPriorClaimsIncluded/claimTruncatedCount`；只有前三项都为 true 时才允许模型声称已复核全部历史观点内容。
10. immediate/current verdict 都是“程序化复核”，不是主力真实意图的事实标签。模型只能据此解释假设如何收窄，不能覆盖 verdict 或升级为事实认定。

### 6.3 程序化复核契约

1. `observationAt` 仅使用历史 `dataSnapshotJson.quote.timestamp`，`decisionAvailableAt` 仅使用记录 `gmtCreate`；两者先归一化为 Instant 再比较，上海日期只用于交易日归属。`anchorFacts.sourceTradeDate` 只做一致性校验，`runSlot` 只做标签。任一时间缺失/不可解析、sourceTradeDate 矛盾，或 `observationAt > decisionAvailableAt` 时该观点不可程序验证，不回退 runSlot、系统时间或其它记录时间。
2. `INTRADAY` 的窗口终点固定为分析交易日 15:00，`windowEndStatus=FIXED`。`THREE_DAY/FIVE_DAY/TWENTY_DAY` 不预测未来交易日：只统计分析交易日之后已实际出现的去重 completed daily K，保存 `requiredTradingDays=3/5/20` 与 `elapsedCompletedTradingDays`；少于 N 根时 `windowEnd=null/windowEndStatus=PENDING`，第 N 根出现后才把该 bar 日期收盘固化为 `windowEnd/windowEndStatus=RESOLVED`。不按自然日推算，也不预猜节假日。
3. 机器可验证内容只包括结构化 intent direction，以及带 `metricPath/operator/threshold` 的结构化 watchPoint。自由文本 watchPoint 和 `legacy_text_fallback` 只用于模型解释，程序 verdict 为 `UNRESOLVED`，不能靠关键词猜测阈值。
4. 每个 verdict 必须同时保存 `sourceObservationAt/decisionAvailableAt/evidenceObservationAt/basedOnRecordId/windowEnd/windowEndStatus/requiredTradingDays/elapsedCompletedTradingDays/outcome/maturity/checksSummary`。`outcome` 只能是 `SUPPORTED/WEAKENED/FALSIFIED/MIXED/UNRESOLVED`；`maturity` 只能是 `PROVISIONAL/FINAL/EXPIRED_UNVERIFIED`。邮件和模型必须把 PENDING/PROVISIONAL 明示为阶段性，不能写成最终支持或最终证伪。
5. 聚合规则固定为：主 intent direction 明确反向时才可直接 `FALSIFIED`；显式 `falsifyOnMiss=true` 的结构化 watchPoint 未命中且其它检查存在支持时记 `MIXED`；只有支持项时为 `SUPPORTED`；只有削弱项时为 `WEAKENED`；支持与削弱并存为 `MIXED`；没有可验证项为 `UNRESOLVED`。不得沿用“任一附属检查 FALSIFIED 就覆盖全部判断”的聚合方式。
6. `immediateNextSlotVerdict` 回答“下一次可用观察的区间变化是否支持上一次判断”；`currentAsOfVerdict` 回答“截至本轮、在原时间窗口内判断处于何种状态”。两者不得互相覆盖，模型和邮件必须标出使用的是哪一种。
7. intent direction 先独立计算 price/flow 证据，再聚合，避免 support-first：同日使用 `priceDeltaPct/mainInflowDelta`，跨日使用区间价格收益和下一交易日独立 mainInflow。对 `ACCUMULATE/PULL_UP/SUPPORT`，price >= 1% 或 flow >= 3000 万分别形成 support，price < 0 或 flow < 0 分别形成 oppose，只有 price <= -1% 且 flow <= -1 亿同时成立才是 strong falsify；对 `DISTRIBUTE/SUPPRESS` 完全对称反向。support 与任何 oppose 并存固定为 MIXED；只有 support 为 SUPPORTED；没有 support 且 strong falsify 为 FALSIFIED；只有 oppose 为 WEAKENED；其余 UNRESOLVED。因此 flow=+5000万、price=-2% 必须为 MIXED。`WASH` 仅在 price < 0 且 flow > -1 亿时 SUPPORTED、flow <= -1 亿时 WEAKENED，其余 UNRESOLVED。
8. current verdict 的候选 evidence 必须满足 `evidenceObservationAt > max(sourceObservationAt, decisionAvailableAt)`。N_DAY 窗口 PENDING 时使用截至 evaluationAt 的最新合法后继 observation并标 PROVISIONAL；窗口 RESOLVED 后，价格检查必须使用 windowEnd 当日精确 completed daily bar。INTRADAY FINAL snapshot 必须落在 14:55-15:05。字段不存在则对应 check UNRESOLVED；没有满足末端要求的后继证据时 maturity=EXPIRED_UNVERIFIED。
9. immediate verdict 也受原窗口约束：证据必须满足上一条的严格后继条件，且在 FIXED/RESOLVED 时 `evidenceObservationAt <= windowEnd`；N_DAY PENDING 时 evidence 所在交易日序号不得超过 requiredTradingDays。第一条可用记录已超窗时不得计算方向 outcome，返回 `UNRESOLVED + EXPIRED_UNVERIFIED`。THREE/FIVE/TWENTY_DAY 都必须覆盖超窗反例。

## 7. 代码接入设计

### 7.1 `EastmoneyMarketDataService` 的 canonical quote 重载

1. 保留现有 `buildBundle(symbol, market, periodType)`，内部转调新重载。
2. 新增 `buildBundle(symbol, market, periodType, canonicalQuote)`；canonicalQuote 有效时直接写入 payload/source trace，不再次调用 `fetchQuote`。
3. symbol/market 不匹配仍进入 bundle 的 `requiredMissing`；timestamp 缺失、日期错位、过旧或未来等 freshness 问题只进入独立 `riskEligibilityMissing`，不得让整个 bundle/currentFacts 变为 unavailable，也不得静默抓第二份 quote 回退。
4. 其它调用方不传 quote 时行为不变，因此改动只影响 AI 盯盘上下文，且减少一次行情网络请求。

### 7.2 新增纯计算 `StockWatchRiskPatternService`

1. 输入：typed `CanonicalQuoteSnapshot`、`completedDailyBars`、technicalIndicators、marketPhase。
2. 输出：typed `RiskPatternContext`，内部明确分成 `observations` 和 `guidance`；序列化为 Map 只发生在 AI prompt 边界。
3. 不访问网络、不调用模型、不写库。
4. 负责数据资格检查、派生量、R01-R04、evidence paths、risk review cap 和 skipped 原因；不负责改写 `AiDecision`。
5. 第一版不输出 score，不输出操纵/对倒/控盘等归因词。

### 7.3 两周历史上下文

1. 以下 ledger/detail 新形状只供 capability opt-in 规则使用；GENERAL 保留当前 recentTechnicalAnalysis 查询、shape 和 sanitizer，避免 prompt/context 漂移。mapper 新增 `selectAllSuccessfulSlotsByDateRange`，14 天范围不设 LIMIT，并以 canonical `quoteObservedAt` 为时间上界；opt-in context 在 Java 中按 slot 去重并生成 `ledger/detailRecords/coverage`。
2. 对 ledger 中每条记录构建两类程序化复核：使用下一条可用成功记录快照的 `immediateNextSlotVerdict`，以及尊重 `timeWindow` 的 `currentAsOfVerdict`。ledger 仅保留 verdict/summary，详细 checks 只进入被选中的 detailRecords；不得继续用单一 `refreshPriorValidations(currentQuote)` 覆盖两类语义。
3. `sanitizeRecentTechnicalAnalysis` 使用独立 allowlist；不得沿用当前“超长就从 records[0] 删除”的策略处理 ledger。
4. 模型输入中的所有合法 `recordId` 形成 allowlist，供后校验使用。

### 7.4 `StockWatchMonitorService.buildAiContext`

1. 仅在 capability opt-in 分支冻结 `evaluationAt` 和 canonical quote，完成 freshness 校验；再以 `quoteObservedAt` 为上界生成最近两周 ledger，并用同一 canonical quote 调 bundle 重载。GENERAL 继续原实现路径。
2. 在 `currentFacts` 中写入 `riskPatternObservations`；在 `aiContext` 顶层写入 `riskPatternGuidance`。
3. currentFacts 完成后补齐最新记录在有效窗口内可由 canonical quote 产生的 current verdict，再按固定规则选 detailRecords；历史记录的 immediate verdict 必须来自后继快照，不得被本轮 quote 重写。
4. 固定采用 `AiEmailContext` 数据通路，不再保留二选一：它包含 canonical quote、compact observations、compact guidance、compact history ledger、validated narrative 和 coverage。
5. event 只存 compact metadata 和已校验的 `belief_update`，不存完整 aiContext。
6. `appendAiContextMetadata` 记录：pattern ids、evaluationAt/quoteObservedAt/quoteAgeSeconds/freshnessStatus/marketPhase、source quality、facts/guidance size、history unique/included/omitted counts、fullLedgerComplete、truncated、validator actions、两种 confidence 及 triggerEvidenceSources。

### 7.5 显式 capability opt-in

1. 给 `AiWatchRule` 的 JSON schema 增加可选 `requiredCapabilities` 字符串数组，第一版唯一允许值为 `L0_RISK_PATTERN`；`fromPayload/copy/normalize` 必须保留并 allowlist 该字段。存量规则字段缺失时固定归一化为空数组，`ruleTriggerDomain=GENERAL`，禁止根据“高换手/长上影/异动风险”等重叠自然语言词自动升级语义。
2. 同时增加 `riskPatternTriggerMode=ADVISORY|REQUIRED`。包含 `L0_RISK_PATTERN` 且字段缺失时默认 `ADVISORY`：生成 R01-R04、历史连续复核和邮件三态，但不改变本条规则原有 MODEL_QUOTE/STRUCTURED_RULE 触发结果。只有显式 REQUIRED 才把合法本地 risk pattern 作为额外告警门。
3. `ruleTriggerDomain` 因而固定为 `GENERAL|RISK_PATTERN_ADVISORY|RISK_PATTERN_REQUIRED`。REQUIRED 的最终触发必须同时满足：原始模型先通过 historical/ref/wording guards 后的 `acceptedPreMergeModelTriggered=true`，以及至少一个合法 RISK_PATTERN triggerEvidenceRef；同一规则的局部 structured 命中不能替代任一条件。ADVISORY 没有 risk-required 门，但仍受 opt-in removal fail-closed 约束。
4. `requiresEnhancedAiContext` 在 capability opt-in 时直接返回 true；非 opt-in 规则继续只走现有关键词逻辑和旧 prompt/context 形状，既有 conditionText、MODEL_QUOTE、STRUCTURED_RULE、confidence 和 trigger 结果不变。第一版不再新增任何风险关键词作为隐式触发条件，包括泛词 `异动`。
5. 本阶段只扩规则服务/API JSON，不增加 UI 控件；创建/更新风险规则时由调用方显式传 `requiredCapabilities=["L0_RISK_PATTERN"]`，只有专门风险告警才再传 `riskPatternTriggerMode="REQUIRED"`。后续若需要 UI，再用 capability 多选和 mode 分段控件暴露，不从文本反推。
6. 配置必须 fail closed：create/update 中任何未知非空 capability、未知 mode、或未包含 `L0_RISK_PATTERN` 却设置 ADVISORY/REQUIRED 都抛 `IllegalArgumentException`，沿用 controller 的 `ApiResponse.fail` 参数失败通路；只有 opt-in 且 mode 缺失可以默认 ADVISORY。加载 store-file 时遇到非法存量项，不得归一化成更宽松模式，也不得覆盖原文件；该项隔离为 disabled/invalid、从 `listEnabled` 排除并记录 validation error。
7. 全局 feature flag 关闭时策略固定为：GENERAL 完全不受影响；ADVISORY 回退原 prompt/context/告警行为且不渲染 risk 段；REQUIRED fail closed，不调用模型、不发告警，原因记为 `required_capability_disabled`。实现不得自行选择把 REQUIRED 降级为 GENERAL。
8. persisted CRUD、store-file 和 `parseTransientRules` 必须调用同一个严格 validator。`runOnce` 必须先解析/校验请求中的 transient override，再判断交易时段；`StockWatchMonitorController.runOnce` 捕获 `IllegalArgumentException` 并返回 `ApiResponse.fail`。解析失败时整次请求不得评估任何规则、抓行情或调用模型，避免盘外早退吞错或部分执行。

### 7.6 `StockWatchAiJudgeService` prompt

以下 prompt/schema 扩展只对 feature-on capability opt-in 规则注入。由于当前 `buildPrompt` 会直接序列化整个 `AiWatchRule`，新增字段后 GENERAL 不能再直接传扩展 DTO；必须使用 `LegacyAiRulePromptView/toLegacyPromptRuleMap`，只投影改造前已有字段并保持固定顺序。feature-off ADVISORY 也使用同一旧投影、旧 aiContext shape；只有 feature-on ADVISORY/REQUIRED 使用扩展 rule/context。opt-in 分支保留现有 JSON 顶层字段，不新增顶层 `riskPatternReview`，而是在 `beliefUpdate` 里要求：

```json
{
  "beliefUpdate": {
    "mainForceIntentContinuity": {
      "narrative": "...",
      "evidenceRefs": ["history:123:immediate", "quote:price"]
    },
    "historicalViews": [
      {
        "recordId": 123,
        "priorClaim": "...",
        "verdict": "supported|weakened|falsified|mixed|unresolved|expired_unverified",
        "maturity": "provisional|final|expired_unverified",
        "evidenceRefs": ["history:123:immediate", "history:123:current"],
        "evidence": "...",
        "whatNarrowed": "..."
      }
    ],
    "riskPatternReview": {
      "usedPatternIds": ["R01", "R02"],
      "triggerEvidenceRefs": ["pattern:R01:currentFacts.riskPatternObservations.derived.breakoutPct"],
      "riskReviewConfidence": 0.62,
      "riskSummary": {
        "narrative": "...",
        "evidenceRefs": ["pattern:R01", "quote:high"]
      },
      "counterEvidence": ["..."],
      "normalExplanations": ["..."],
      "invalidatesWhen": ["..."],
      "legalConclusion": "not_determined"
    }
  }
}
```

prompt 修订要点：

1. 明确 `currentFacts.riskPatternObservations` 是程序计算的本轮事实；`riskPatternGuidance` 只是解释候选，不得作为 triggered 依据。
2. 当规则条件涉及风险结构时，`triggered/reason/matchedSignals` 只能引用 observations 中存在的 patternId、conditionsMet 和 evidencePaths。
3. `analysis` 必须连接 history ledger 与本轮事实；只有 `coverage.fullLedgerComplete=true && coverage.allPriorClaimsIncluded=true` 才允许声称“已复核全部两周观点内容”。只有前者为 true 时最多声称“已覆盖全部两周 slot key 和必需程序化 verdict”。
4. 禁止模型把 R01-R04 解释成操纵、坐庄、违法或盘口 L2 行为。
5. `historicalViews[].recordId` 必须在 ledger allowlist；该 view 的每个 evidenceRef 都必须严格匹配 `history:<同一recordId>:immediate|current`。任一引用缺失、非法或跨 recordId 时整条模型 historicalView fail closed 删除，不尝试从自由文本中“部分保留”；本地 ledger 不受影响。模型 verdict 只是解释建议，邮件优先展示本地 immediate/current 程序化 verdict，并明确它不是主力真实意图事实。
6. 如果 observations/guidance 存在 missing/skipped，模型必须写入 missingData 或 riskPatternReview。
7. `mainForceIntentContinuity/riskSummary` 只是模型解释，必须携带 allowlist 内的 evidenceRefs；其 narrative 不得进入本地事实段，也不得把 PROVISIONAL 省略成最终结论。
8. 本地只生成三类引用 allowlist：`quote:<path>`、`pattern:<patternId>:<evidencePath>`、`history:<recordId>:immediate|current`。`usedPatternIds` 只表示模型讨论过模式；只有 `riskPatternReview.triggerEvidenceRefs` 指向本地 `status=triggered` 模式及其真实 evidencePath 时，才能形成 RISK_PATTERN 触发来源，watch/skipped 模式只能用于 analysis。

### 7.7 本地后校验与内部元数据

新增 `finalizeAiDecision`，所有正常、解析失败、structured fallback 和异常 fallback 返回路径都必须经过它。正常模型路径顺序为：

1. 模型 JSON 解析后先做现有字段规范化，并保存不可变的原始 trigger snapshot（raw triggered/reason/matchedSignals/triggerEvidenceRefs）；此时不得 merge，也不得让 structured reason 覆盖原始字段。
2. `GENERAL` 继续严格执行当前顺序 `guardHistoricalTriggerSource -> mergeStructuredDecision`，不执行新增 risk 措辞删除、引用校验、risk cap 或触发重算；`finalizeAiDecision` 对 GENERAL 只是统一返回入口，不能改变当前结果。
3. 仅对 `RISK_PATTERN_ADVISORY/REQUIRED`，先在原始 snapshot 上执行现有 historical trigger guard，再做顶层引用/措辞校验；任一 historical guard 或肯定性非法触发依据删除都持久记录到 `triggerBasisRemoved`，不能被后续字段覆盖清除。
4. 完成所有 pre-merge guard 后，才冻结 `acceptedPreMergeModelTriggered = guardedModelTriggered && !triggerBasisRemoved`，并校验 narrative 的 patternId/recordId/evidencePath/evidenceRefs；
5. 然后才执行 `mergeStructuredDecision(structuredDecision, guardedDecision)`，再计算合法 `triggerEvidenceSources`、`hasAnyValidatedTriggerBasis`。ADVISORY 在未 removal 时为 `existingMergedTriggered && hasAnyValidatedTriggerBasis`；REQUIRED 为 `acceptedPreMergeModelTriggered && hasValidRiskPatternRef`。structured 覆盖 reason 或命中局部条件都不能抹掉 pre-merge removal，也不能把 accepted false 改 true；
6. 保留 alertTriggerConfidence、仅封顶 riskReviewConfidence，生成只供内部使用的 `validationActions`；内部字段不得从模型 JSON 解析。

校验规则：

1. `usedPatternIds`、`triggerEvidenceRefs`、`historicalViews.recordId`、evidencePath 和 narrative evidenceRefs 不在本轮 allowlist 时删除对应模型项或句子，追加 missingData 和 validation action；未知引用不得进入邮件。只有顶层触发依据的删除设置 `triggerBasisRemoved=true`，历史/分析叙事删除不应无关地撤销告警。
2. 以下新增措辞护栏只对 capability opt-in 分支生效：`reason/matchedSignals` 肯定性声称操纵、坐庄、违法、对倒、控盘、账户组、虚假申报、撤单等 L0 不支持事实时，删除整条触发依据并设置 `triggerBasisRemoved=true`，不做全局字符串替换。若同句含“不能、无法、不足以、未证实、不构成、不得、禁止”，它是边界说明：从顶层触发依据移到“模型解释（非事实）”，不设置 removal flag；移走后若无其它合法 basis，仍由 `hasAnyValidatedTriggerBasis=false` 撤销触发。
3. opt-in 分支的 `analysis/beliefUpdate` 按句处理：包含上述词且同时包含“不能、无法、不足以、未证实、不构成、不得、禁止”的句子可作为边界说明保留；其它句子整句替换为“现有 L0 数据仅支持风险结构提示，资金行为性质仍未证实”。
4. 发生第 2/3 项时追加 `requires_l1_l2_or_regulatory_evidence_for_market_manipulation_claim`，并把 `riskReviewConfidence` 封顶 0.60；不得因此降低 alertTriggerConfidence。
5. `ruleTriggerDomain` 只由显式 capability/mode 得到。opt-in 后校验后重新计算来源集合：同一规则内结构化局部命中加入 STRUCTURED_RULE；普通模型 quote 判断保留 MODEL_QUOTE；只有合法 `triggerEvidenceRefs` 实际指向本地 triggered pattern 时才加入 RISK_PATTERN。不能把 usedPatternIds、watch pattern 或“structured 未命中”归类为 RISK_PATTERN，也不能从 conditionText 关键词反推 domain。
6. 同一条 REQUIRED 规则必须 `triggerBasisRemoved=false`、保留至少一个合法 RISK_PATTERN 来源且 `acceptedPreMergeModelTriggered=true`；即使该条件被现有结构化解析器局部命中，也不能绕过。ADVISORY 同样在 `triggerBasisRemoved=true` 时 fail closed；另一条独立 GENERAL/ADVISORY 规则在自己的评估中仍按自身结果触发。本阶段不尝试把一条自由文本混合条件解析成完整 AST。
7. risk cap 在所有来源下都只封顶 riskReviewConfidence，永远不改 alertTriggerConfidence。默认阈值 0.75、模型 confidence 0.80、risk cap 0.68 的合法风险规则仍可触发。
8. `riskEligibilityMissing` 非空或 source quality 不合格时，RISK_PATTERN 不得进入来源集合，riskReviewConfidence 封顶 0.55，并追加明确 missingData；它不能写入 bundle 全局 requiredMissing。GENERAL 规则的 STRUCTURED_RULE/MODEL_QUOTE、currentFacts 和 confidence 兼容行为不因本功能改变，但邮件要标示风险结构不可用。
9. `mainForceIntentContinuity/riskSummary/whatNarrowed` 只能渲染在明确标为“模型解释（非事实）”的段落。未绑定 allowlist evidenceRefs 的句子删除；模型叙事不得向本地事实段提供 pattern 状态、行情数值或程序 verdict。
10. `AiDecision` 增加内部字段 `triggerEvidenceSources/riskReviewConfidence/validationActions`；它们不属于模型输出 schema。
11. 后校验仍只是已知越界的第一阶段护栏，不声明完整语义安全；后续漏网进入影子评测。

### 7.8 告警 fingerprint

1. 当前 gate 在 cooldown 结束后依赖 `signalShapeFingerprint` 判断是否值得再次发信；因此 capability opt-in 规则的 fingerprint 追加本地 `evaluationStatus`、排序后的全部 `status=triggered` pattern IDs、排序后的合法 `triggerEvidenceSources`。
2. fingerprint 只能读取本地 observations 和后校验内部字段，不能读取模型自由叙事或未校验 usedPatternIds。R02 -> R01、EVALUATED_NONE -> ACTIVE 等变化必须改变 fingerprint；顺序变化不得改变 fingerprint。
3. GENERAL 规则沿用原 fingerprint 字节序列，不追加空 risk 字段，保证存量去重行为不变。该扩展不绕过现有 cooldown，只在 cooldown 结束后的 changed 判断中生效。

## 8. 邮件改造方案

当前 `sendAiTriggeredEmail` 只发 DeepSeek 分析和触发依据。需要把本次分析中的风险结构和历史复核一起发出。

### 8.1 邮件新增段落

建议调整为：

1. 一、触发摘要
2. 二、关键行情
3. 三、DeepSeek 连续分析
4. 四、风险结构与反证
5. 五、历史观点复核
6. 六、触发依据
7. 七、观察条件

### 8.2 段落来源

先定义一个 package-private 邮件正文 helper，建议命名为 `StockWatchAiEmailContentBuilder`：

1. 输入：`AiWatchRule rule`、`AiDecision decision`、`Map quote`、`AiEmailContext emailContext`。
2. 输出：纯正文字符串，不负责频控、收件人、发送状态。
3. `sendAiTriggeredEmail` 只负责准备 compact context、调用 builder、走 `sendAiAlertWithFrequencyGate`。
4. 好处：邮件正文可以脱离 mapper、频控、NotificationService 直接单测。

`一、触发摘要` 同时展示：

1. 告警触发置信度及来源；
2. L0 风险解释置信度；
3. 两者不可合并成一个数字。

`四、风险结构与反证`：

1. 来源优先级：
   - 本地 `AiEmailContext.compactRiskPatternObservations` 提供 patternId/status/conditions/evidence；
   - 本地 `compactRiskPatternGuidance` 提供 cap/counterEvidence candidates/normal explanations/invalidatesWhen；
   - 经 evidenceRefs allowlist 和措辞校验后的 `decision.beliefUpdate.riskPatternReview` 只进入单列的“模型解释（非事实）”，不混入本地风险事实；
   - `evaluationStatus=ACTIVE` 时展示模式；`EVALUATED_NONE` 时才显示“本轮已完成 L0 评估，未触发风险结构”；`SKIPPED_UNAVAILABLE` 时显示“本轮 L0 风险结构未完成评估”及缺失原因。feature/capability 未启用时不渲染本段，不能伪装成阴性结果。
2. 内容：
   - active pattern id/name/status/riskReviewConfidenceCap；
   - conditionsMet；
   - counterEvidence；
   - normalExplanations；
   - invalidatesWhen；
   - `legalConclusion=not_determined`。
3. 长度限制：
   - 最多 4 个模式（R01-R04 全部可展示）；
   - 每类最多 3 条；
   - 本段最多约 1800 字符，超出标记“已截断”。

`五、历史观点复核`：

1. 来源优先级：
   - 本地 history ledger 提供 recordId、历史意图、`timeWindow`、immediate/current 程序化 verdict；
   - 经 recordId/evidenceRefs allowlist 校验后的 `decision.beliefUpdate.historicalViews` 提供 whatNarrowed；
   - 经校验后的 `mainForceIntentContinuity` 只进入“模型解释（非事实）”。
2. 内容：
   - 覆盖范围和完整性；
   - 最近/转折/证伪的关键历史判断；
   - 本地程序化复核的 outcome 与 `PROVISIONAL/FINAL/EXPIRED_UNVERIFIED` maturity；
   - 本轮缩小后的主力意图假设；
   - 下一轮最关键证伪点。
3. 模型 verdict 与本地 immediate/current verdict 冲突时，邮件展示本地程序化复核，并在模型叙事旁标记“模型解释与程序化复核冲突，已降级”；不得称本地 verdict 为真实意图事实，也不得省略 PROVISIONAL 制造成最终结论。

注意：邮件正文只能读取已经完成本地后校验后的 `decision` 和 compact context。不能直接渲染模型原始 `rawResponse`，也不能渲染未经降级的 `beliefUpdate`。

邮件发送边界固定为：只有 AI 规则实际触发、达到 minConfidence、通过 alert gate 和单股邮件频控时，才把上述分析附加到预警邮件。本阶段不新增未触发分析的逐轮邮件，避免邮件噪音和另一条投递状态链。

### 8.3 邮件安全边界

邮件固定加一行：

> 风险边界：本段仅为基于公开行情和历史分析的 L0 异常结构预警，不构成操纵事实认定，也不构成投资建议。

这样邮件对用户有实际价值，同时不会把模型的高风险措辞当作事实推送。

## 9. 测试与冒烟计划

### 9.1 单元测试

新增或扩展：

1. `StockWatchRiskPatternServiceTest`
   - R01 失败突破触发、watch、不触发；
   - R02 放量滞涨触发，并验证 `dailyReturnPct < -1.5` 不会被误判为滞涨；
   - R03 高开回落的 `upExcursionShare` 边界；
   - R04 长上影聚集触发；
   - canonical quote 当日 K 从 completedDailyBars 排除；
   - 当前交易时段收到前一交易日 quote、quote 超过 300 秒、未来 quote、周末手工执行时，R01-R04 skipped 且 freshnessStatus 准确；
   - 同日 15:00 后合法收盘 quote 可进入 POST_CLOSE，不能被盘中新鲜度阈值误拒；
   - INTRADAY 时 raw amount/turnover ratio 不参与触发，POST_CLOSE 时才可用；
   - quote trade date 缺失、未来日 K、少于所需 bars 时 pattern skipped；
   - 百分数单位、零分母、`turnoverPercentile20` 0-100 边界；
   - 输出没有 score，observations 与 guidance 完全分层。
2. `StockWatchMonitorServiceTest`
   - 存量规则缺 `requiredCapabilities` 时默认 GENERAL；即使 conditionText 包含“高换手/长上影”也不能自动升级，原 MODEL_QUOTE 行为不变；
   - 显式 `requiredCapabilities=[L0_RISK_PATTERN]` 才生成风险上下文；mode 缺失默认 ADVISORY，只有显式 REQUIRED 才增加 risk trigger 门；未知非空 capability/mode 必须拒绝；
   - create/update 的未知非空 capability/mode、REQUIRED/ADVISORY 缺少 L0_RISK_PATTERN 一律返回现有 ApiResponse 参数失败；store-file 非法项被隔离禁用且不覆写原文件，不能 fail-open；
   - transient aiRules 使用同一 validator；monitor run API 在交易时段内外收到非法 transient 都返回 ApiResponse.fail，且 quote fetch/evaluation/model 调用计数均为 0；
   - canonical quote 传入 bundle，不能发生第二次 quote 抓取；
   - 历史查询以 quoteObservedAt 为上界，晚于 canonical quote 的记录不能进入 ledger；
   - quote timestamp 缺失时 opt-in 增强 ledger 为空且 upperBoundStatus=UNAVAILABLE，不能回退 evaluationAt/runSlot；ADVISORY 由旧依据发信时邮件明确两周复核不可用；
   - 100 个唯一 scheduled slot 全部进入 ledger；重复 slot 只保留最新 SUCCESS；
   - 250 个任意分钟 manual slot 超过最小 ledger 预算时按最新 observation 保留、记录 omitted 范围且 fullLedgerComplete=false；
   - 记录 i+1 的真实 quote timestamp/snapshot 用于生成记录 i 的 immediate verdict，本轮 quote 不得重写已有历史 immediate verdict；
   - 不同 manual slot 共享同一个 quote timestamp 时互不验证，必须跳到严格更晚 snapshot；没有更晚证据则保持 UNRESOLVED/PROVISIONAL 或 EXPIRED_UNVERIFIED；
   - 并发 manual 反例：A 的 decisionAvailableAt=10:10，B 的 evidenceObservationAt=10:05，即使 B quote 晚于 A source quote 也不得验证 A；旧记录缺 gmtCreate 时 fail closed；
   - 同日相邻快照的累计 changePercent 仍为正但 priceDeltaPct 为负时，不能错误输出 SUPPORTED；mainInflow 使用区间 delta；
   - 跨日不相减累计 mainInflow，只使用区间价格收益和下一交易日独立资金观察；
   - INTRADAY 只接受观点生成后的同交易日 observation，FINAL 末端 timestamp 必须在 14:55-15:05；THREE_DAY/FIVE_DAY/TWENTY_DAY 按实际出现的后继 completed K 计数；
   - N_DAY 开放窗口在跨周末/节假日时 windowEnd=null/status=PENDING 且为 PROVISIONAL；第 N 根后继 completed K 出现后才固化 RESOLVED windowEnd；
   - THREE/FIVE/TWENTY_DAY 的第一条后继记录若落在原窗口之外，immediate outcome 必须 UNRESOLVED/EXPIRED_UNVERIFIED，不能用第10日验证三日判断；
   - positive/negative/WASH intent 使用固定 delta 阈值，附属 watchPoint falsified 不得无条件覆盖主 intent 聚合结果；
   - 偏多 intent 的 flow=+5000万、price=-2% 以及偏空的对称异号样本都必须聚合为 MIXED，不能因 support-first 变 SUPPORTED；
   - 长窗口尚未到期为 PROVISIONAL，准确到期且命中精确末端 bar 为 FINAL，缺少窗口末端证据为 EXPIRED_UNVERIFIED；旧 INTRADAY 判断不得被数日后的 quote 证伪；
   - legacy text fallback 被显式标记且信任级别低于结构化 mainForceIntent；
   - detail 超长时优先保留 ledger；只有最小 ledger 自身超硬预算时才能省略行并准确降级 coverage；
   - 默认 29000 字符与 40000 硬上限生效，正常 100 slot 裁剪时先缩 claim/detail 而不删除 slot key 和 verdict；
   - observations 放 currentFacts，guidance 放 aiContext 顶层；
   - R01-R04 四个模式同时 active 时模型上下文全部保留，不能因 3-item 展示上限漏掉触发依据；
   - stale quote 只写 riskEligibilityMissing，GENERAL 规则的 bundle/currentFacts、触发和 confidence 保持旧语义；
   - opt-in ADVISORY 在 EVALUATED_NONE/SKIPPED_UNAVAILABLE 时仍可由原 MODEL_QUOTE/STRUCTURED_RULE 触发，从而使邮件三态可达；
   - opt-in fingerprint 包含排序后的本地 triggered pattern IDs/evaluationStatus/合法来源，R02 -> R01 会变化、输入顺序变化不会变化；GENERAL fingerprint 字节序列保持原样；
   - `AiEmailContext` 和 event metadata 包含 coverage、source quality、两种 confidence、validator actions；
   - feature flag 关闭时 GENERAL/ADVISORY 回退原链路和原 fingerprint；REQUIRED fail closed、无模型调用/邮件并记录 required_capability_disabled。
3. `StockWatchAiJudgeServiceTest`
   - STRUCTURED_RULE、MODEL_QUOTE、RISK_PATTERN 及混合集合能准确重算，structured 未命中不能自动归类为 risk pattern；
   - usedPatternIds 或 watch/skipped pattern 不能形成 RISK_PATTERN 来源；只有指向本地 triggered pattern/evidencePath 的合法 triggerEvidenceRefs 才能形成来源；
   - risk cap 在所有来源下只封顶 riskReviewConfidence，不改 alertTriggerConfidence；默认 minConfidence=0.75、模型 confidence=0.80、risk cap=0.68 时仍可触发；
   - GENERAL 走兼容 finalize 分支；包括高风险措辞在内的 reason/analysis、触发和 confidence 都不被新增 risk 护栏修改；
   - 捕获 GENERAL 和 feature-off ADVISORY 的完整 prompt payload，与改造前 fixture 对比：rule 只含旧字段且没有 capability/mode/risk context；
   - 伪造 patternId、recordId、evidencePath 被删除并留下 validation action；
   - RISK_PATTERN_REQUIRED 的全部伪造引用被删除后，无论同一规则是否有局部 structured 命中，都强制撤销 triggered/shouldNotify；
   - 同一 RISK_PATTERN_REQUIRED 混合条件即使被结构化解析器局部命中，只要没有合法 RISK_PATTERN 来源仍必须撤销告警；另一条独立 GENERAL 规则不受影响；
   - REQUIRED 在 acceptedPreMergeModelTriggered=false 时，即使 structured=true 且 triggerEvidenceRefs 合法也必须不触发；guard 后 accepted=true 且合法 risk ref 才可进入 merged confidence 门；
   - ADVISORY 保留模型/structured 兼容触发，但若新增护栏删除了全部 reason/matchedSignals 且无 structured/risk basis，则撤销本条触发；
   - opt-in 自由文本复合条件中，一个 conjunct 合法、另一个顶层触发依据被删除时 triggerBasisRemoved=true，ADVISORY/REQUIRED 都必须 fail closed，不能把 AND 降成剩余单条件；
   - 原始模型 reason 含肯定性非法 conjunct 或历史触发来源，同时 structured=true 且 risk ref 合法时，pre-merge guard 先记录 removal，ADVISORY/REQUIRED 均不能被 merge 重新触发；
   - 未绑定 evidenceRefs 的 riskSummary/mainForceIntentContinuity 句子被删除，模型数值、状态和 verdict 不进入本地事实段；
   - historicalViews 的 recordId 合法但 evidenceRefs 缺失、伪造或引用另一 recordId 时，整条模型 view fail closed；全部引用绑定自身时才保留；
   - “不能证明操纵”从顶层依据移到模型解释且不设置 triggerBasisRemoved；肯定性越界触发句被删除并设置 flag；两者在无剩余合法 basis 时都不触发；
   - 正常、解析失败、structured fallback、异常 fallback 都经过 `finalizeAiDecision`；
   - `validationActions` 不接受模型输入。
4. `StockWatchAiEmailContentBuilderTest`
   - 不触发真实邮件发送；
   - 本地 observations 和 immediate/current 程序化 verdict 永远先于模型叙事；
   - 模型与本地 verdict 冲突时显示本地程序化复核和冲突标识，但不标成事实真值；
   - PROVISIONAL 必须显示“阶段性”，FINAL 才能显示“窗口终局”，EXPIRED_UNVERIFIED 显示证据缺口；
   - 模型 narrative 只在“模型解释（非事实）”段落，不能向事实段注入数值、pattern 状态或 verdict；
   - risk evaluation 三态准确：ACTIVE 展示模式，EVALUATED_NONE 才写“已评估未触发”，SKIPPED_UNAVAILABLE 写“未完成评估+原因”；GENERAL/feature off 不渲染风险段；
   - stale quote 由 GENERAL/STRUCTURED 来源继续发信时，邮件不能把 SKIPPED_UNAVAILABLE 写成“未触发”；
   - 展示 alertTriggerConfidence 与 riskReviewConfidence 两个不同字段；
   - 验证风险结构、历史观点覆盖、风险边界、缺失数据、截断提示；
   - 验证未经后校验的 raw response 不进入邮件正文。

### 9.2 本地验证命令

建议编码后运行：

```bash
mvn -Dtest=StockWatchRiskPatternServiceTest,StockWatchAiJudgeServiceTest,StockWatchMonitorServiceTest,StockWatchAiEmailContentBuilderTest test
```

### 9.3 真实数据冒烟

编码后再做，不在本方案阶段执行：

1. 选 `002979.sz 雷赛智能`。
2. 用真实 Eastmoney quote + dailyKline 生成 `riskPatternObservations/riskPatternGuidance`。
3. 先不调用 DeepSeek，验证 canonical quote、completedDailyBars、R01-R04、两周 coverage 和大小限制。
4. 再用 bridge 低频调用一次 DeepSeek，验证：
   - 模型分析不是简单总结两周行情；
   - 它引用的 patternId/recordId 都在本地 allowlist；
   - 它引用本地 immediate/current 程序化复核解释历史观点被支持/削弱/证伪/混合/到期未验证/未决，不把复核结果升级成真实意图事实；
   - 主力意图分析是连续过程；
   - 任意触发来源的 alertTriggerConfidence 都没有被 risk cap 降低，riskReviewConfidence 单独封顶；
   - 通过单股邮件配置真实发送一封，正文包含两种 confidence、风险结构、历史复核和风险边界。

## 10. 实施顺序

1. 增加全局配置开关 `stock-watch.ai-rules.risk-pattern-prefilter.enabled=true`，并给 `AiWatchRule` 增加显式 `requiredCapabilities`、`riskPatternTriggerMode` API/JSON 字段；persisted/transient 共用严格 validator，monitor controller 捕获参数错误；存量规则默认 GENERAL，opt-in 默认 ADVISORY，补 allowlist、copy/persist/transient 和 on/off 测试。
2. 给 `EastmoneyMarketDataService` 增加 canonical quote 重载和 evaluationAt freshness 校验，先用测试证明不重复抓 quote、陈旧 quote 只跳过 L0 risk、当日 K 不进入历史基线。
3. 写 typed `StockWatchRiskPatternService` 与 R01-R04 单元测试；此阶段不动 prompt。
4. 新增以 quoteObservedAt 为上界的 14 天 SUCCESS 查询，在 Java 按 slot 取最新记录；使用 gmtCreate 建立 decisionAvailableAt，生成严格后继且不超窗的 interval-delta immediate verdict、PENDING/RESOLVED timeWindow current verdict、两层 history ledger/detail/coverage，并完成并发 manual、正常 100 slot、异常超预算、重复记录、窗口边界/节假日和 legacy fallback 测试；同步把历史上下文默认字符数调为 29000、硬上限调为 40000。
5. 接入 `buildAiContext`，分开写入 `currentFacts.riskPatternObservations` 与顶层 `riskPatternGuidance`，生成固定 `AiEmailContext`。
6. 更新 capability 驱动的 `requiresEnhancedAiContext`、ruleTriggerDomain 和 event metadata；禁止从 conditionText 关键词推导风险域。
7. 先给 GENERAL/feature-off ADVISORY 增加旧字段 prompt 投影和 fixture 回归；再更新 opt-in prompt、原始 snapshot pre-merge guards、acceptedPreMerge trigger、evidenceRefs allowlist、触发来源集合/重算和两种 confidence；所有返回路径统一经过 `finalizeAiDecision`。
8. 仅对 opt-in 规则扩展本地告警 fingerprint，纳入 evaluationStatus、排序后 triggered pattern IDs 和合法来源；GENERAL 保持原 fingerprint。
9. 新增 `StockWatchAiEmailContentBuilder`，邮件固定本地事实优先，`sendAiTriggeredEmail` 只负责发送和频控。
10. 跑全部相关单元测试与现有回归测试。
11. 启动应用，给真实 `002979.sz` 测试规则显式配置 ADVISORY capability，低频 bridge 冒烟并真实发送一封测试邮件；另用 REQUIRED 规则验证 risk trigger 门。
12. 若出现模型输出越界，修本地校验、allowlist 或 prompt，不增加额外模型调用。

## 11. 风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| 风险上下文过大 | 模型上下文膨胀、封号风险上升 | ledger 与 detail 分层；异常 manual slot 超预算时确定性省略并降低 coverage；固定硬预算 |
| risk cap 误伤告警 | 合法告警达不到 minConfidence | risk cap 永远只作用于 riskReviewConfidence，alertTriggerConfidence 保持现有合并语义 |
| ADVISORY/REQUIRED 配错 | 风险解释意外变成告警门或反之 | opt-in 默认 ADVISORY；REQUIRED 必须显式配置并记录 metadata；两种 mode 分别测试 |
| 陈旧 quote 被当成当前行情 | 风险模式时点错位 | evaluationAt、quoteAgeSeconds、freshnessStatus；陈旧时只跳过 R01-R04 |
| 累计值伪装区间趋势 | 连续验证方向错误 | 同日使用 price/mainInflow delta；跨日不相减累计资金；maturity 明示阶段/终局 |
| 盘中累计量与完整日比较 | R01/R02 错判 | INTRADAY 只用 volumeRatio 触发；amount/turnover 历史比较仅 POST_CLOSE 可触发 |
| 双 quote 或当日 K 混入基线 | 当前事实和历史基线错位 | canonical quote 重载；completedDailyBars 严格早于 quoteTradeDate |
| 模型伪造 pattern/record 引用 | 邮件出现不存在的事实或误触发 | 本地 allowlist、删除后重算来源和触发、冲突时本地程序复核优先 |
| pattern 切换被旧 fingerprint 抑制 | 新风险结构不再发信 | 仅 opt-in fingerprint 纳入本地三态、排序 triggered IDs 和合法来源；保留 cooldown |
| 模型继续输出操纵类措辞 | 邮件误导用户 | 按字段/句子降级、追加 missingData，仅封顶 riskReviewConfidence |
| L0 模式误报 | 用户收到噪音 | 公式完全确定；邮件并列 counterEvidence、normal explanations、invalidatesWhen |
| 邮件过长 | 可读性下降 | 段落长度和模式数量上限 |

回滚方式：

1. 增加配置开关 `stock-watch.ai-rules.risk-pattern-prefilter.enabled=true`。
2. 如线上异常，可关掉 risk pattern 接入；GENERAL 与 ADVISORY 回到原 AI 盯盘、原历史上下文和邮件链路。
3. feature flag 关闭时，REQUIRED 固定 fail closed 并记录 `required_capability_disabled`，不能静默降级。若业务要恢复该规则旧告警，必须显式把 mode 改为 ADVISORY/移除 capability，或回滚代码与配置作为同一变更。
4. feature off 时 risk observations/guidance、增强 history ledger、risk validator 和 risk fingerprint 分支 no-op；GENERAL/ADVISORY 的原结构化 quote 与 MODEL_QUOTE 告警仍工作。
5. 回滚不得恢复“统一 confidence cap”或“模型事实优先”的旧行为。

配置和可观测性必须一起落地：

1. `application.yml` 增加默认配置项和注释。
2. 日志或 event metadata 记录 enabled、ruleTriggerDomain/mode、facts/guidance/ledger/detail size、coverage、truncated、validation actions、triggerEvidenceSources、两种 confidence、evaluationAt/quote freshness/source quality、risk fingerprint fields、email sections rendered/missing。
3. feature flag 关闭时，GENERAL/ADVISORY 邮件只显示原有内容和原历史上下文，不显示本地 L0 风险结构或新连续复核段；REQUIRED 不发送。

## 12. 审核意见处理记录

本轮实际完成独立 reviewer：Challenge=Lovelace（`019f681d-0fcf-7641-9f5d-f02011910b27`），Optimize=Zeno（`019f681d-1063-7c20-a760-5e053546f6b0`）。前七轮中，Challenge 均判定“不通过”；Optimize 第七轮虽已通过，但因 Challenge 意见导致方案继续修订，两者共同复审了最终同一版本。第八轮结果为 Challenge=通过、Optimize=通过：未发现影响逻辑正确性、旧告警兼容、历史连续验证真实性、可实现性、邮件事实边界或去重语义的 blocker，也没有在既定约束下收益足以改道的替代架构。以下 material findings 已核实并修订。早先 Dirac/Jason 两个会话因运行时长期无输出被关闭，不计入审核结论。

| Reviewer finding | 处理结论 | 文档修订 |
|---|---|---|
| 邮件不能依赖 `event.aiContext.currentFacts.riskPatternPrefilter`，当前 event 没有完整 aiContext | 属实 | 固定使用 `AiEmailContext`，event 只保存 compact metadata |
| 风险上下文大小控制不是当前 sanitizer 事实 | 属实 | observations/guidance 分别 allowlist 和硬限制，并补 size test |
| `normalExplanations/counterEvidence` 不是硬事实 | 属实 | observations 只保留 derived/status/conditions/evidencePaths；其它字段移到 guidance |
| 后校验不能证明覆盖所有高风险措辞 | 属实 | 降级为第一阶段护栏，只覆盖已列高风险词、缺失 evidencePath、confidence cap，并要求影子评测补漏 |
| 后校验必须覆盖 `beliefUpdate` | 属实 | 校验字段扩展为 `analysis/reason/matchedSignals/beliefUpdate` |
| confidence cap 必须在 `mergeStructuredDecision` 后按触发源生效 | 属实并在第二轮进一步修正 | 来源改为集合；risk cap 永远只影响 riskReviewConfidence，不再封顶任何 alertTriggerConfidence |
| schema 示例引用 `derived.amountRatio20` 但没有 derived | 属实 | schema 增加 `derived` 字段 |
| `turnoverRank20` 单位不清 | 属实 | 改为 `turnoverPercentile20`，取值 0-100 |
| 应抽邮件正文 helper | 采纳 | 新增 `StockWatchAiEmailContentBuilder` 方案和单测计划 |
| feature flag/可观测性不闭合 | 采纳 | 实施顺序和回滚段补配置、on/off 测试、metadata |
| 全局 risk cap 会压低告警，且纯风险 cap 最高 0.70 会被默认 0.75 永久拦截 | 属实，blocking | 拆分 alertTriggerConfidence/riskReviewConfidence；risk cap 永远不作用于顶层告警 confidence |
| “两周每轮”无法由 48 条/16000 字保证 | 属实，blocking | mapper 按唯一 slot 取全量 ledger；details 单独限量；增加 coverage。Optimize 所称“只取14条”不准确，实际配置为48条，但不影响该结论 |
| 外层 quote、bundle quote、dailyKline 未形成统一口径 | 属实，blocking/material | 采用 canonical quote 重载；completedDailyBars 排除当日；盘中量能只用 volumeRatio |
| 邮件先读取模型 beliefUpdate，权威顺序错误 | 属实，blocking | 本地 observations 和 immediate/current 程序化复核先展示，模型仅提供 evidenceRefs allowlist 校验后的“模型解释（非事实）” |
| R02 会把大跌判成滞涨，R01/R03/R04 和 score 不完整 | 属实，blocking | 重写确定性公式，R02 增加下界和绝对开盘偏移，删除 score |
| currentFacts 混入解释候选 | 属实，material | observations 留在 currentFacts；guidance 移到 aiContext 顶层 |
| 风险关键词扩大所有规则的数据抓取或改变旧规则语义 | 属实，material，第三轮升级为 explicit opt-in | 第一版不新增隐式风险词；只有 `requiredCapabilities=[L0_RISK_PATTERN]` 才构建风险上下文和 required domain |
| 本地词表全局替换会误伤“不能证明操纵” | 属实，material | reason/signal 整项删除；analysis/belief 按句结合否定词处理，不做全局替换 |
| “现有相关测试通过”可证明新方案可靠 | 不成立，不作为通过证据 | 现有测试没有覆盖新 invariant；以新增跨链测试和真实冒烟为准 |
| 当前 `priorValidation` 能实现“第二次验证第一次” | 不成立，blocking | 改为从每条 `dataSnapshotJson` 提取 anchorFacts，用下一次可用成功记录生成 immediate verdict，并按结构化 `timeWindow` 生成 current verdict；过期且缺证据输出 EXPIRED_UNVERIFIED |
| canonical quote 只校验可解析日期即可代表本轮行情 | 不成立，blocking | 增加 evaluationAt/quoteAgeSeconds/freshnessStatus；陈旧或交易时段外只跳过 L0 risk，不改变旧告警兼容行为 |
| 14 天唯一 slot 最多约 100，24KB 可无条件全保留 | 不成立，blocking | manual slot 可任意增长；正常 100 slot 全保留，最小 ledger 超预算时确定性省略并显式降级 coverage |
| 后继快照累计涨幅/资金可直接验证上一判断 | 不成立，blocking | 同日改用区间 price/mainInflow delta，跨日禁止累计资金相减；current verdict 增加 PROVISIONAL/FINAL/EXPIRED_UNVERIFIED |
| allowlist 删除伪造引用后可沿用模型原 triggered | 不成立，blocking | 删除后重算 triggerEvidenceSources；risk-required 无合法 RISK_PATTERN 来源时撤销本条规则告警 |
| freshness 进入全局 requiredMissing 仍会破坏 GENERAL currentFacts | 属实，blocking | 新增独立 riskEligibilityMissing；只移除 RISK_PATTERN，不让整个 bundle unavailable |
| 同一 risk-required 混合规则可被局部 STRUCTURED_RULE 命中绕过 | 属实，blocking | 同一规则必须有合法 RISK_PATTERN 来源；另一条独立 GENERAL 规则才按自身结果触发 |
| 用风险关键词自动推导 required domain 不改变存量规则 | 不成立，blocking | 新增显式 requiredCapabilities opt-in，存量规则默认 GENERAL，不从 conditionText 反推 |
| 不同 manual slot 可用相同收盘 snapshot 互相验证 | 不成立，blocking | evidenceObservationAt 必须严格大于 sourceObservationAt；相同 timestamp 跳过 |
| 无 active pattern 等于已经评估且未触发 | 不成立，blocking | risk 邮件改为 ACTIVE/EVALUATED_NONE/SKIPPED_UNAVAILABLE 三态 |
| historicalViews 只校验 recordId 足以约束自由叙事 | 不成立，blocking | 每条 view 增加 evidenceRefs；无合法引用时删除模型 evidence/whatNarrowed/verdict 叙事 |
| capability opt-in 天然等于 risk trigger required | 不成立，blocking | 新增 ADVISORY/REQUIRED；opt-in 默认 ADVISORY，只有 REQUIRED 增加 risk 告警门，邮件三态因此可达 |
| 全局 finalize risk 护栏不影响 GENERAL | 不成立，blocking | GENERAL 走当前行为等价分支；新增引用删除、措辞、cap、触发重算仅作用于 opt-in |
| 模型上下文 active pattern 最多 3 个足够 | 不成立，blocking | R01-R04 全四个最小状态必须保留；邮件独立限长，不得在触发前漏依据 |
| REQUIRED 可由 merge 后 structured=true 加合法 risk ref 触发 | 不成立，blocking | REQUIRED 同时要求 guard 后 acceptedPreMergeModelTriggered=true 和合法 triggered-pattern ref |
| 异号 price/flow 可以 support-first 聚合 | 不成立，blocking | price/flow 先分项；support 与 oppose 并存固定 MIXED，strong falsify 需双条件 |
| historicalView 可引用任意合法 record | 不成立，blocking | 每个 evidenceRef 必须绑定 view 自身 recordId；任一跨记录/非法引用整条 view fail closed |
| pattern 切换不需要进入告警 fingerprint | 不成立，blocking | 仅 opt-in fingerprint 加本地 evaluationStatus、排序 triggered IDs 和合法来源；GENERAL 不变 |
| 非法 capability/mode 可归一化为更宽松配置 | 不成立，blocking | API 非法非空值/非法组合走现有 ApiResponse.fail；非法存量项隔离禁用且不覆写，禁止 fail-open |
| 复合条件删掉一个触发 conjunct 后可用剩余依据继续 | 不成立，blocking | opt-in 顶层触发依据任一被删即 triggerBasisRemoved=true，ADVISORY/REQUIRED 整条 fail closed |
| quote timestamp 缺失时可用 evaluationAt 作为历史上界 | 不成立，blocking | 增强 ledger unavailable/empty，coverage 标 upper_bound_unavailable，禁止声称两周复核完整 |
| feature off 时 REQUIRED 可自行选择回退或静默 | 不成立，blocking | 唯一策略为 REQUIRED fail closed；GENERAL/ADVISORY 才回退原行为，配置变更必须显式 |
| merge 后再扫描非法/历史 reason 能保留 removal | 不成立，blocking | 原始模型 snapshot 先过 historical/ref/wording guards，冻结 acceptedPreMerge 和 removal 后才 merge |
| 新增空 capability/mode 不会改变 GENERAL prompt | 不成立，blocking | GENERAL/feature-off ADVISORY 使用改造前旧字段投影，并用 prompt fixture 锁定无新字段 |
| 严格配置校验只覆盖 CRUD 即完整 | 不成立，blocking | persisted/transient 共用 validator；monitor run API 捕获非法 transient，整轮零行情/零评估/零模型调用 |
| immediate 只需第一条严格更晚 evidence | 不成立，blocking | evidence 还必须不晚于原 timeWindow；超窗 immediate 为 UNRESOLVED/EXPIRED_UNVERIFIED |
| quote 观测更晚即可验证观点 | 不成立，blocking | 增加 decisionAvailableAt=gmtCreate；evidence 必须晚于观点实际可用时刻，避免并发 manual 回看泄漏 |
| 可用已完成 K 预先算出未来 N 日 windowEnd | 不成立，blocking | 开放 N_DAY 只记录 elapsed/required 与 PENDING；第 N 根后继 completed K 出现后才 RESOLVED 固化日期 |

## 13. 当前方案已固定的产品边界

1. risk 邮件严格使用三态：ACTIVE 显示风险结构，EVALUATED_NONE 显示“已评估未触发”，SKIPPED_UNAVAILABLE 显示“未完成评估+原因”；未 opt-in/feature off 不显示 risk 段。
2. R10/R12 本阶段暂缓，避免伪装成已有 L1/L2 或行业广度数据。
3. 全局配置开关默认开启，但只有显式 `requiredCapabilities=[L0_RISK_PATTERN]` 的规则生成 L0 observations/guidance；opt-in 默认 ADVISORY，仅显式 REQUIRED 增加 risk 告警门；存量规则默认 GENERAL。关闭后原盯盘、原历史上下文、结构化告警和邮件链路保持可用。
4. 邮件只随实际触发并通过频控的 AI 告警发送，不为每轮未触发分析单独发信。
5. 本方案通过 reviewer 后仍停在“待用户审核、未编码”，不会自动进入开发。

## 14. 第八轮内部复审 claim graph（已被第 16 节跨方案自审降级）

该 claim graph 只审计了本文内部增量链路，没有把 v16 的统一 owner/dispatcher/test-ledger 方案作为反证输入。S1/S5/S8/S11 因此不能再支持“本文可独立编码”；后续仅保留为第八轮 reviewer 的历史审核记录。

```json
{
  "objective": {
    "primary": "用最近14天唯一slot观点账本和本轮L0可观察风险结构连续缩小主力意图假设，并把可信结果写入实际触发的AI预警邮件",
    "secondary": [
      "不增加DeepSeek调用次数",
      "不把L0解释成操纵事实",
      "不让风险解释置信度误伤任何现有告警",
      "控制上下文和外部行情请求压力"
    ]
  },
  "constraints": [
    "只使用quote、日K、技术指标、主力资金和历史技术分析",
    "历史观点不能成为本轮触发事实",
    "不接入L1/L2，不新增第二次模型复核",
    "复用现有盯盘、AI判断和单股邮件频控链路",
    "本轮只修改方案，不编码"
  ],
  "claims": [
    {
      "id": "S1",
      "statement": "现有buildAiContext、judge和sendAiTriggeredEmail提供了增量接入点，无需新建第二条AI或邮件流水线",
      "type": "fact",
      "dependsOn": ["E1", "E3", "E11", "E12", "E18"],
      "supportingEvidence": ["E1", "E3", "E11", "E12", "E18"],
      "hiddenAssumptions": [],
      "falsifiers": ["实际邮件由另一条未检查链路发送", "buildAiContext没有进入当前AI规则调用"],
      "alternativesConsidered": [],
      "confidence": "high"
    },
    {
      "id": "S2",
      "statement": "外层盯盘quote只有通过相对evaluationAt的新鲜度校验后才可供L0 risk使用，日K只提供排除当日后的completed baseline；freshness缺口必须与bundle requiredMissing隔离",
      "type": "recommendation",
      "dependsOn": ["E8", "E9", "E16", "E20", "E25"],
      "supportingEvidence": ["E8", "E9", "E16", "E20", "E25"],
      "hiddenAssumptions": ["外层quote包含R01-R04所需当日OHLC和成交字段"],
      "falsifiers": ["外层quote字段不完整且无法补齐", "quote timestamp无法可靠反映观测时点", "buildBundle的内部quote有必须保留的独有校验语义"],
      "alternativesConsidered": [{"id": "A1", "summary": "继续抓两份quote并做三方时间校验", "rejectedBecause": "增加请求并保留不必要的当前事实分叉"}],
      "confidence": "high"
    },
    {
      "id": "S3",
      "statement": "R01-R04只能由完全确定、区分INTRADAY和POST_CLOSE的公式生成，第一版不应有未定义score",
      "type": "recommendation",
      "dependsOn": ["E9", "E10", "S2"],
      "supportingEvidence": ["E9", "E10"],
      "hiddenAssumptions": ["volumeRatio是行情源提供的盘中时间归一化指标"],
      "falsifiers": ["行情源volumeRatio语义并非盘中可比", "真实样本显示固定阈值系统性不可用"],
      "alternativesConsidered": [{"id": "A2", "summary": "盘中按已交易分钟线性外推全天成交额", "rejectedBecause": "开收盘成交分布不均，线性外推会制造额外模型风险"}],
      "confidence": "medium"
    },
    {
      "id": "S4",
      "statement": "currentFacts只包含R01-R04全四个最小observations及ACTIVE/EVALUATED_NONE/SKIPPED_UNAVAILABLE三态，解释候选和policy cap放riskPatternGuidance",
      "type": "recommendation",
      "dependsOn": ["E6", "E7"],
      "supportingEvidence": ["E6", "E7"],
      "hiddenAssumptions": ["prompt继续限制triggered只能引用currentFacts"],
      "falsifiers": ["下游必须把guidance作为触发事实才能工作"],
      "alternativesConsidered": [{"id": "A3", "summary": "所有字段继续放currentFacts并靠文字约束", "rejectedBecause": "结构上仍允许模型把解释候选当硬事实"}],
      "confidence": "high"
    },
    {
      "id": "S5",
      "statement": "14天历史采用有quoteObservedAt上界的唯一slot紧凑ledger；连续复核的evidence必须晚于decisionAvailableAt且不超原窗口；N_DAY未来windowEnd只在第N根后继completed K出现后固化",
      "type": "recommendation",
      "dependsOn": ["E15", "E17", "E19", "E20", "E21", "E23", "E29"],
      "supportingEvidence": ["E15", "E17", "E19", "E20", "E21", "E23", "E29"],
      "hiddenAssumptions": ["正常定时分析每个交易日最多10个配置slot", "dataSnapshotJson含有真实quote timestamp和anchor facts", "gmtCreate代表观点实际写入可用时间", "紧凑claim足以让模型定位历史观点"],
      "falsifiers": ["quote timestamp缺失时回退evaluationAt构建ledger", "evidence早于decisionAvailableAt", "immediate evidence超过原timeWindow", "PENDING N_DAY预猜未来交易日期", "正常100 slot仍无法在预算内保留最小ledger", "异常省略行却仍标fullLedgerComplete", "同日验证仍使用累计值而非区间delta"],
      "alternativesConsidered": [
        {"id": "A4", "summary": "只取最近48条", "rejectedBecause": "与两周每轮目标不一致"},
        {"id": "A5", "summary": "把两周所有原始正文全部传入", "rejectedBecause": "上下文和封号压力过高，且重复信息多"}
      ],
      "confidence": "high"
    },
    {
      "id": "S6",
      "statement": "alertTriggerConfidence和riskReviewConfidence分离；GENERAL按旧顺序兼容；opt-in先在原始模型snapshot执行全部guard并冻结acceptedPreMerge/removal，之后才merge；任何顶层依据被删都fail closed",
      "type": "recommendation",
      "dependsOn": ["E14", "E22", "E24", "E26", "E27"],
      "supportingEvidence": ["E14", "E22", "E24", "E26", "E27"],
      "hiddenAssumptions": ["结构化规则、普通模型quote触发与风险解释可同时存在但语义不同", "规则JSON可以向后兼容增加requiredCapabilities和triggerMode"],
      "falsifiers": ["产品明确要求任何低置信风险解释都否决独立告警", "非法capability/mode静默变宽松", "requiredCapabilities缺失时无法稳定默认GENERAL", "GENERAL被新增finalize护栏修改", "structured merge在guard前覆盖非法reason", "历史guard后accepted trigger仍为true", "opt-in复合条件删掉一个conjunct后仍触发"],
      "alternativesConsidered": [{"id": "A6", "summary": "合并后统一cap", "rejectedBecause": "会把0.75告警阈值以下的合法结构化告警错误拦截"}],
      "confidence": "high"
    },
    {
      "id": "S7",
      "statement": "本地observations三态作为事实权威；程序化复核优先于模型叙事；模型引用必须allowlist校验且historicalView只能绑定自身recordId，模型解释与事实段隔离",
      "type": "recommendation",
      "dependsOn": ["E5", "E17", "E19", "S4", "S5"],
      "supportingEvidence": ["E5", "E17", "E19"],
      "hiddenAssumptions": ["beliefUpdate保持可解析Map", "本轮上下文可以构建完整allowlist", "规则可被稳定分类为GENERAL或RISK_PATTERN_REQUIRED"],
      "falsifiers": ["模型引用无法稳定映射到本地ID", "historicalView可引用另一个合法recordId", "删除非法引用后无法重新判定触发", "邮件必须把模型未验证自由文本混入事实段"],
      "alternativesConsidered": [{"id": "A7", "summary": "再调用一次模型复核", "rejectedBecause": "增加DeepSeek请求和封号风险"}],
      "confidence": "high"
    },
    {
      "id": "S8",
      "statement": "AiEmailContext加纯正文builder是最小可测邮件改造，发送仍复用现有alert gate和单股频控",
      "type": "recommendation",
      "dependsOn": ["E11", "E12", "E18", "S7"],
      "supportingEvidence": ["E11", "E12", "E18"],
      "hiddenAssumptions": ["用户要求是把分析附到实际预警邮件，而不是每轮都发信"],
      "falsifiers": ["用户明确要求未触发分析也必须逐轮邮件"],
      "alternativesConsidered": [{"id": "A8", "summary": "新增独立分析邮件队列", "rejectedBecause": "引入第二套频控、状态和噪音问题，超出本阶段目标"}],
      "confidence": "high"
    },
    {
      "id": "S9",
      "statement": "风险域通过requiredCapabilities显式opt-in并默认ADVISORY；存量规则默认GENERAL；非法配置fail closed；feature off时REQUIRED fail closed而GENERAL/ADVISORY回退旧行为",
      "type": "recommendation",
      "dependsOn": ["E4", "E24", "E26", "E27", "E28"],
      "supportingEvidence": ["E4", "E24", "E26", "E27", "E28"],
      "hiddenAssumptions": ["规则服务JSON持久化可向后兼容新增字符串数组和triggerMode", "调用方可显式配置capability"],
      "falsifiers": ["存量规则缺字段时未默认GENERAL", "GENERAL prompt出现新增字段", "API或transient入口未共用严格validator", "非法配置导致部分规则已执行", "非法配置被静默降级", "feature off时REQUIRED仍触发或被静默改为GENERAL", "业务要求无配置自动识别风险域"],
      "alternativesConsidered": [{"id": "A9", "summary": "继续使用风险关键词隐式推导domain", "rejectedBecause": "会改变包含重叠词的存量规则语义且无法可靠解析自由文本AND/OR"}],
      "confidence": "high"
    },
    {
      "id": "S10",
      "statement": "仅opt-in规则的告警fingerprint加入本地evaluationStatus、排序triggered pattern IDs和合法来源，使pattern切换可越过fingerprintUnchanged且不改变GENERAL去重语义",
      "type": "recommendation",
      "dependsOn": ["S4", "S6", "S7"],
      "supportingEvidence": [],
      "hiddenAssumptions": ["现有cooldown后的changed gate继续使用signalShapeFingerprint"],
      "falsifiers": ["pattern切换后fingerprint仍不变", "模型自由文本进入fingerprint", "GENERAL fingerprint发生变化"],
      "alternativesConsidered": [{"id": "A10", "summary": "对所有规则重做告警去重", "rejectedBecause": "会扩大存量行为回归范围，当前只需opt-in增量"}],
      "confidence": "high"
    },
    {
      "id": "S11",
      "statement": "修订后的总体链路在当前约束下成立：显式capability -> risk-only freshness与completed K -> 本地三态L0 observations/guidance -> 有界两周ledger与严格后继delta/timeWindow程序化复核 -> 单次模型解释 -> 本地allowlist/触发重算/语义护栏 -> 现有告警邮件",
      "type": "inference",
      "dependsOn": ["S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10"],
      "supportingEvidence": [],
      "hiddenAssumptions": ["新增跨链测试和真实002979.sz冒烟在编码后通过"],
      "falsifiers": ["任一blocking invariant无法通过测试", "真实数据无法形成新鲜canonical quote或truthful coverage", "邮件仍显示未校验模型事实", "新risk功能改变GENERAL规则既有告警语义"],
      "alternativesConsidered": [{"id": "A11", "summary": "新建独立模型审核和邮件流水线", "rejectedBecause": "请求、封号、状态管理和维护成本明显更高"}],
      "confidence": "medium"
    }
  ],
  "conclusion": {"dependsOn": ["S11"]}
}
```

## 15. 当前推理图与通过条件

1. E14 证明现有顶层 confidence 同时控制告警，因此先建立 S6 的置信度隔离，避免新风险分析改变旧告警语义。
2. E16/E20/E25 证明当前有双 quote、缺新鲜度/历史上界校验且全局 requiredMissing 会提前丢掉 currentFacts，S2 因此把 riskEligibilityMissing 与原 bundle 兼容行为隔离；S2 再支撑 S3 的 completed K 和盘中/收盘公式。
3. E15/E21 证明当前 48 条不够且 manual slot 可能突破 100；E19/E23/E29 证明当前复核既没有使用合法后继 observation，也把累计值误当区间变化，并存在并发生成时间泄漏。S5 因此建立 quoteObservedAt 上界、decisionAvailableAt、窗口内严格后继、truthful coverage、delta，以及不预猜未来交易日的 PENDING/RESOLVED maturity。
4. E6/E7/E17/E19 证明本轮事实、程序化复核和模型叙事必须分层，因此 S4/S7 固定 observations 三态的事实权威、verdict 的程序化边界、模型 allowlist 和事实段隔离。
5. E11/E12/E18 证明邮件正文需要扩展但投递链路无需重建，因此 S8 复用现有告警门和频控。
6. E4/E24/E26/E27/E28 证明 conditionText 关键词无法安全承载新 domain、新 DTO 会污染旧 prompt，且 transient 入口也需校验；S9 因此使用显式 capability/mode、旧字段 prompt 投影、persisted/transient 共用 fail-closed validator，存量默认 GENERAL。
7. S4/S6/S7 共同支撑 S10 的 opt-in fingerprint，确保风险结构变化不会被旧粗粒度 shape 误判为未变化，同时 GENERAL 去重不漂移。
8. S1-S10 同时成立，才能支持 S11；任何一个 blocking invariant 缺失都不能声称方案通过。

本文内部审核条件及结果：

1. 已满足：Challenge 确认 E1-E29 与 S2-S10 未遗留会改变告警、历史复核或邮件事实边界的 blocker。
2. 已满足：Optimize 确认当前方案在既定约束下没有明显更简单、更稳且收益足以改道的替代方案。
3. reviewer 的纯命名、排版、非关键字段建议可以忽略；涉及事实权威、置信度、数据时点、历史覆盖、公式可复现和邮件触发语义的意见必须修订后复审。

上述通过只覆盖本文内部 claim graph。它没有证明本文与其它已存在实施规范不存在所有权冲突；该遗漏已在下一节的主 agent 跨方案自审中推翻“可独立编码”的结论。

## 16. 主 agent 最终跨方案自审（非独立）

审核模式：严格本地自审，不调用子 agent。结论：**不通过，本文不得独立进入编码。**

### 16.1 Blocking findings

| ID | 结论 | 代码/文档证据 | 影响 |
|---|---|---|---|
| B1 | 本文和 v16 同时声明并设计“连续主力意图/历史验真”的所有权，形成双状态源 | 本文 6.2/6.3/7.3 以技术分析记录重建 ledger/verdict；`stock-watch-continuous-main-force-intent-plan-v16.md:58-116,192-201,275-328` 已把该语义交给 fact observation、test ledger、intent evaluation/head 和统一 `stock-analysis-v1` | 两套 verdict、成熟度和历史覆盖会相互漂移，邮件无法确定哪个是权威 |
| B2 | 本文没有持久化每次异动判断，因此不能满足“第二次验证第一次异动判断” | 当前 `StockWatchMonitorService.evaluateAiRule` 只把 decision 放入本轮 result/event；`lastAiAlerts` 只在成功发信后保存去重状态。本文仍只查询 `stock_watch_technical_analysis` | 未发邮件的异动分析不会成为下一轮可验证状态，所谓连续收敛只覆盖技术分析，不覆盖异动判断本身 |
| B3 | 本文保留逐规则同步模型调用，与账号限速目标和 v16 单股单 observation 一次请求相冲突 | 本文非目标 4、7.4-7.7 继续扩展 `buildAiContext -> judge`；v16 1-4、93-120、275-328 明确合并 technical/rules/intent，并由持久化 dispatcher 按账号预算消费 | 功能越丰富，单次 prompt 越长，但请求数不降；仍可能挤占 90 秒冷却并加剧账号风控 |
| B4 | 14 天重放无法保证 `TWENTY_DAY` 判断最终闭环 | 本文 6.2 只查询 14 个自然日，而 6.3 又要求 20 根后继 completed K 才终局；源判断通常会在成熟前离开 14 日集合 | 20 日 claim 可能永远不产生 FINAL；v16 的持久化 active test ledger 才能跨越冷启动窗口 |

### 16.2 Material contract defects

1. 7.6 示例中的 `riskSummary.evidenceRefs=["pattern:R01", ...]` 不符合本文同节定义的唯一合法形式 `pattern:<patternId>:<evidencePath>`。
2. 7.6 把 `expired_unverified` 同时列入 model `verdict`，而 6.3 已规定它只能属于 `maturity`；`outcome` 应保持 `SUPPORTED/WEAKENED/FALSIFIED/MIXED/UNRESOLVED`。
3. 7.4 声称 pre-model `AiEmailContext` 已包含 validated narrative，但 narrative 只能在模型返回并完成本地 finalize 后产生。事实 email context 应在模型前冻结，最终邮件由该 context 与 finalized decision 组合，不能把 raw belief 写回事实 context。
4. 文中 24KB/32KB 与实际 `max-chars` 混用。实现契约必须统一为 Java UTF-16 code units，并由生产 serializer fixture 验证；不能把字符预算表述成字节预算。

### 16.3 修订后的唯一可行方向

1. 本文只保留第 5 节 R01-R04 确定性公式、第 6.1 节 observations/guidance 事实分层、第 5.7 节双 confidence、L0 措辞边界和邮件展示规则。
2. 不实现本文自建的 14 天 ledger/current verdict；连续验证统一复用 v16 或其后继版本的 `fact observation -> active tests -> intent evaluation/head`。两周技术分析仅作第一次冷启动，之后由持久化 tests 跨窗口推进。
3. `StockWatchRiskPatternService` 在 sampler 接受 canonical fact 时每个 observation 只计算一次，结果写入 fact snapshot；不得在每条规则的 `buildAiContext` 中重复计算或重复抓行情。
4. R01-R04 作为 `stock-analysis-v1` 的当前事实扩展，同一次模型响应同时产出 technical/rules/intent；不增加请求，不保留本文的独立逐规则模型路径。
5. 邮件只能在统一 evaluation 业务提交后构建：本地 risk observations 与程序 test verdict 为事实段，finalized model narrative 为“模型解释（非事实）”；COMMITTED_LAGGED 不发实时异动邮件。
6. capability/mode 应进入 v16 冻结的 rule snapshot 和 request digest。`REQUIRED` 且本地没有 `status=triggered` pattern 时可在入队前确定性 fail closed，禁止浪费一次网页模型请求；`ADVISORY` 才允许随统一 DAY task解释未触发/不可用状态。

### 16.4 新的编码前门槛

1. 先决定并审核通过 v16 或其后继统一分析方案；若放弃 v16，必须显式撤销其“唯一实施规范”声明并重新证明逐规则请求在账号预算内可运行，不能两份方案同时有效。
2. 把上述 L0 addendum 合并进统一方案后，重新生成一份没有双 ledger、双 provider 路径和双邮件事实源的 authoritative claim graph。
3. 修正 16.2 的 schema/时序/预算矛盾，并补“同 observation 只计算一次 risk”“REQUIRED 无本地 pattern 零 reservation”“20 日 claim 跨 14 日冷启动仍可终局”的测试。

仍未证明：v16 当前也仍标记为待第十六轮双审核；因此本自审只证明“当前 L0 文档不应独立编码”，不证明 v16 已经可实现或已通过。
