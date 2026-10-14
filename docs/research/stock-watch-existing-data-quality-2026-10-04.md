# 现有股票分析与预测的数据质量边界

日期：2026-10-04。角色：独立只读数据质量核查，不重复总体准确率、因素或收益分组。只新增本文件，未写入业务数据库、修改既有文件、重启服务或运行 Maven 测试。

## 核心结论

现有数据可用于审计系统曾经输出什么、描述候选风险形态及提出下一轮验证假设。**持久化的 EVALUATED、actual_return_pct、direction_hit 不能直接作为已完成收盘、尺度一致、可交易的结果真值。** 主要限制是：

1. 冻结快照的 2,540 条 EVALUATED 中，1,578 条在目标日收盘前已评估。按源码市场时段，沪深使用 15:00、港股使用 16:00；分别为 595、915、68 条。只是时间质量标记，不等于已经逐条证明 1,578 条数值全部错误。
2. 生成基准是实时 quote.price，评估取前复权日线后直接计算 close/source_price；评估路径没有报价尺度对齐、复权基准冻结或完成日线校验。历史报告已有同尺度问题的实证，本轮不重复主代理的对齐收益重算。
3. 原始 2,999 条并非 2,999 个独立交易事件：归一市场后只有 439 个股票日；506 个股票日/期限组有多条预测，涉及 2,469 条记录。
4. mainForceIntent 不是主体真实意图标签；v6 的持久化结构主要是服务端确定性弱证据画像，旧意图验真表也主要是规则失效测试，不是交易盈利或真实派发的监督真值。

## 证据与口径

主数据使用冻结文件 `/private/tmp/stock-watch-existing-data-2026-10-04.jsonl`，只读解析，不把原始 JSON、模型原文、私密日志或连接凭据复制到本报告。

- SHA-256：`88ee7dfa091488837ebf8e199249ffcad648f736b3bbd11b0c48fcd9848046cb`。
- 4,624 行：2,999 条 prediction、1,625 条 SUCCESS analysis；prediction id 与 analysis id 在各自集合内无重复。
- 原始预测：2,540 EVALUATED、459 PENDING；预测源日期覆盖 2026-06-19 至 2026-08-14。不能据此描述 10 月实时风险。
- 统一键：`trim(symbol) + '.' + lower(trim(market))`。只有 prediction 2276/2277 的 market 是 `SZ`，应合入 `300750.sz`，归一后为 25 个标的。它们不是同一 id 的重复行；归一标的键不授权删除不同预测。
- 补充证据来自 `mysql -h 127.0.0.1 -u root ai_meeting` 的 SELECT / SHOW。首个记录时点为 2026-10-04 23:29:28，数据库本地与 UTC 偏移为 +08:00；源码默认 JDBC 时区也为 Asia/Shanghai，见 [application.yml](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/resources/application.yml:6)。补充查询不作为同一事务快照。
- 已先用 CodeGraph 定位预测评估、summary、RETURN_SIGN、生成投影与 Mapper；`codegraph status` 两次报告索引最新。随后用 raw `nl -ba` 核验相关源码。当前工作树有其他进行中改动；当前源码事实不等于已证明全部历史记录当时运行的是这一版本。

| 指针 | 本轮核验范围 |
| --- | --- |
| [PredictionService](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchPredictionService.java:36) | L36-90、117-212、215-405、408-572：版本、保存、评估、分母、回顾指标、日期格式 |
| [PredictionMapper](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/resources/mapper/StockWatchPredictionMapper.xml:42) | L42-103：入库、API 可见性、待评估与更新 SQL |
| [TechnicalAnalysisService](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:538) | L538-598、631-699、780-910、1254-1274：源日期、报价基准、冻结投影、版本 |
| [AnalysisProjectionService](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchAnalysisProjectionService.java:47) | L47-95：事务内分析和预测投影、意图测试资格 |
| [EastmoneyMarketDataService](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/EastmoneyMarketDataService.java:82) | L82-100、177-178、222-359、765-775：生成/评估序列、前复权、校验与来源元数据 |
| [StockQuoteService](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockQuoteService.java:222) | L222-255：实时报价字段与源时间 |
| [FactPersistenceService](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchFactPersistenceService.java:177) | L177-241、262-357：模型意图与规则测试、成熟和证伪语义 |
| [IntentTestMapper](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/resources/mapper/StockWatchIntentTestMapper.xml:39) | L39-60：测试激活边界、进度与终止 |

## 1. status 与有效分母

业务 SQL 的可见性不是“全表全部预测”：它要求 v6 migration READY，并且分析属于 TECHNICAL_RUNTIME 的 canonical task，或无 task 且不超过 legacy cutoff。见 PredictionMapper L57-65、82-92。

补充只读查询得到：migration generation=6、READY、legacy cutoff=796；全表 2,999 条中 API 可见 2,980 条（2,539 EVALUATED、441 PENDING）。其余 19 条关联 9 个非 canonical 的 v5 task 分析。不能把全表统计直接叫作 UI 当前 summary；也不能把“当前不可见”叫作测试污染。全表与 API 群体均没有孤立 analysis_id，可见预测关联的 analysis 全是 SUCCESS。

- summary 只按 `status == EVALUATED` 判定已评估，其余状态全部算 pending；不是“未来交易日尚未到”的判定。`directionHitRate`、`rangeCloseHitRate` 的分母是当前传入列表全部 EVALUATED。当前原始 EVALUATED 没有缺失 actual_return_pct、close/high/low、target date、direction_hit、brier 或非正 source_price，但完整字段不证明标签正确。见 PredictionService L338-394。
- RETURN_SIGN 分母只取 EVALUATED、方向 UP/DOWN、有效收益。原始快照有 1,363 条符合，1,177 条 SIDEWAYS 不计入；4 条零收益仍在分母中并且不背离。它不是全部预测的胜率。见 L455-472。
- `history`/`historyBefore` 每次只查询 10-500 条，summary 汇总的是本页 records，并非所有历史。多页 summary 不可直接平均。见 L184-202、Mapper L68-80。
- 原始 PENDING 最早：T1=07-17、T5=07-16、W1=07-17、M1=07-20。459 条不能全称为“尚未成熟”。获取失败、可见性排除、未运行、序列覆盖不足或其他原因都可能保留 PENDING；本轮没有通过日志或重启判定唯一原因。
- `selectPending` 只取 PENDING，批量按最早 forecast_date/id 取 limit；失败只跳过，不保存独立待评估原因，已评估记录不在下一轮重评范围内。见 L215-266、Mapper L82-102。这会造成结果缺失的选择边界，不能把 missing 当零收益、方向错或自然右删失。

## 2. horizon、持有期与窗口极值

见 PredictionService L269-309、496-521。

| horizon | 源码评估窗口 | 不应解释为 |
| --- | --- | --- |
| T1 | forecast_date 之后第 1 根可用日线 | 生成后精确 24 小时；当天剩余盘中走势 |
| T5 | 之后前 5 根日线 | 5 个日历日；固定周五退出 |
| W1 | 同样为之后前 5 根日线 | 独立于 T5 的另一段持有期 |
| M1 | 之后前 20 根日线 | 精确下一自然月月末 |

实际交易日由返回日线计数，没有逐日验证所有应有行情。排序由行情服务严格递增检查，但行情缺失、停牌或供应商漏行不能仅凭现有 outcome 字段区分。forecast 当天整根日线被排除，即使预测在上午生成，也不覆盖该日之后的风险。

- actual_close 是窗口最后一根 bar 的 close；actual_high/actual_low 是整个窗口最大 high / 最小 low，不是最后一天 high/low。
- range_close_hit 是最后 close 落在 target 区间；range_touch_hit 仅是窗口总极值与 target 区间有重叠。跨日缺口也可能让这种极值重叠成立，不能证明某笔成交价格确实进入目标区间。
- invalidated 只判断窗口极值是否触过失效价，没有触达目标与失效价的顺序；不能据此推导止盈先于止损、真实止损成交或策略净利润。
- 没有收盘完成门槛：只要 future 的行数够，就立即取最后 bar 并写 EVALUATED，自动待评估任务不再重评它。行情服务只拒绝“未来日期”，不拒绝今天尚未完成的日线。

## 3. 目标收盘尚未完成便评估

冻结快照按对应市场收盘阈值比较 `evaluated_at < evaluated_trade_date + closeTime`，得到 1,578 条，分期限为 T1=612、T5=824、W1=88、M1=54。市场时段来自 [TechnicalAnalysisService L4373-4381](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:4373)。

例如 prediction 1：forecast 06-19、目标 06-22、evaluated_at=06-22 10:39:08。prediction 2276：目标 08-10、evaluated_at=09:47:55；2277：目标 08-14、evaluated_at=09:51:39。这些时间无法支持“目标日最终收盘结果”语义。原始快照还有 208 个股票/目标日存在多个 stored actual_close；这也只证明存储结果不唯一，不能把未对齐的价格差异全部归因于盘中变动。

源码因果边界：evaluateOne 不检查 finalBar 对应日是否收盘，updateEvaluation 将状态置为 EVALUATED，而之后 selectPending 不再选择它。**“已评估”是一次程序写入成功状态，不是“成熟真实结果”证书。** 尚未持久化当次完整评估输入，故不能逐条重现历史 provider 返回的 bar 或执行版本；最终错误数量应以主代理的完成日线且同尺度重算为准。

## 4. 前复权日线与实时报价不是自动同尺度

可验证的代码链：

1. StockQuoteService L222-255 从 Eastmoney 实时报价 f2 取 price，未进行复权换算。
2. TechnicalAnalysisService L598 将 quote.price 作为 sourcePrice；L813、906-909 使用被冻结的 sourcePrice 投影；PredictionService L160-171 原样持久化该基准与 snapshot。
3. EastmoneyMarketDataService L222-267 明确使用 `fqt=1`、`adjustment=qfq`；Tencent fallback L286-314 明确请求 qfqday。生成 snapshot 的 buildBundle L90-92 传入 quote 进行最新日 bar 与报价的一致性检查。
4. 但评估调用 L177-178 传入的 canonicalQuote 是 null；validateCanonicalDailyKlines L343-344 因缺少 quoteDate 提前返回，跳过 quote/date/price 的一致性验证。evaluateOne L287 随即计算 `(actualClose / sourcePrice - 1) * 100`，失效价、目标价和 high/low 比较也没有尺度变换。
5. 评估器仅持久化窗口数值、评估日期与指标，没有持久化此次评估序列的 provider、adjustment、复权基准或 bar 来源快照。原有生成 input_snapshot 不代表后来的评估输入。

冻结 SUCCESS 分析的生成来源元数据中，1,034 条明确记为 qfq（Tencent qfqday=524、Eastmoney klines=510），591 条没有该 adjustment 元数据。缺少字段不能直接认定 raw 或 qfq，也不能用当前源码的校验回填历史保证。

既有 [07-29 背离报告 L49-74](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/docs/research/stock-watch-divergence-samples-2026-07-29.md:49) 明确记录：其当时样本中有 7 个股票日的对齐因子偏离 1% 以上，最大约 3；不对齐会产生 ETF 约 -60% 的伪收益。这是历史报告既有结果，不是本轮重算的新数字。

因此只能说“代码允许跨尺度直接除法，历史数据已存在相关反例”，不能断言所有前复权结果必错。若目标日/源日价格恰好同尺度，原公式可能正确；公司行为、provider 复权基准与历史再抓取会改变这一条件。主代理以已完成日线，并用事件 quote.previousClose / 事件日前一根同序列 close 对齐重算；缺失对齐锚点、日线缺口或无法核验时应标注不可评分，不能默认比例=1。即使完成这种对齐，仍是报价基准的研究收益，不是成交回报。

## 5. source_price 不等于可交易入场

sourcePrice 的冻结发生在 DeepSeek 请求及响应之前，实际预测可用时刻是响应/投影完成时刻；它不是 gmt_create 当刻的必然可成交价，更不是委托回报。生成后交易、可成交量、费用、滑点、不可成交行情、退出时间与持仓限制均不在 prediction outcome 合同中。

原始 567 条预测在 forecast 日 15:00 之后生成；这里只是统一时间切分，港股还需使用 16:00，不能把这 567 条都叫盘后不可交易。除正常盘后生成，还应逐条检查行情源时间与生成延迟。收益风险回顾可用 sourcePrice 作“当时快照报价”基准；要支持短线策略效果，需要冻结“信号可用后下一可交易入场”合同，并另行验证执行。

## 6. gmt_create、forecast_date 与回填边界

forecast_date 是行情源交易日期，不是服务器生成日期：TechnicalAnalysisService L546-548、4429-4442 优先 sourceTradeDate/anchorFacts/最新日线日期；L783、828、908 同时用于 analysis_date 与预测源日。冻结投影在重放时保留旧源日和旧基准。Mapper insert 没有显式 gmt_create，默认 CURRENT_TIMESTAMP，见 [schema L399](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/resources/sql/init.sql:399)。

原始快照有 4 条跨日生成，分两个分析事件：

| prediction | analysis | forecast_date | gmt_create | 目标日边界 |
| --- | --- | --- | --- | --- |
| 844/845 | 770 | 07-16 | 07-17 06:42:02 | T1 在目标日开盘前生成；T5 仍 PENDING |
| 940/941 | 833 | 07-17 | 07-18 00:21:26 | 周末生成，目标分别 07-20、07-24 |

只有 844 与目标日同日生成，且在开盘前；没有 EVALUATED 预测在目标日收盘后才生成。API 可见范围不含 844/845，所以可见范围只有 940/941 这 2 条跨日。这不支持“广泛结果出来后补预测”的结论，但也不是全部历史 point-in-time 无泄漏证明：需要源时间、当时 prompt、provider 完成时间和重放链的逐条核验，不能只检查日期。

冻结 analysis snapshot 的后续日线可用作离线结果标签；必须禁止回流到事件时特征。预测日期、生成时点、源时间和评估时点要保留为四个不同字段。

## 7. 相关预测不是独立证据

归一市场后，原始预测覆盖 439 个股票日，其中 254 个股票日有多个 analysis，最多 30 个；506 个股票日/期限组重复，涉及 2,469 条预测。API 可见群体则是 436 股票日、250 多分析股票日、499 重复组、2,451 条重复组内记录。不可混用两套分母。

唯一键仅为 analysis_id/horizon_code，不是 symbol/market/day/horizon，见 [schema L402](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/resources/sql/init.sql:402)。同一天盘中重复预测共享未来价格路径；邻日 T5/M1 窗口还相互重叠。T5/W1 天数相同也不产生独立持有期。不能按行数做独立伯努利样本的置信度或随机行拆分训练/验证。

事件单位、采样时点与去重规则应在看结果前确定，例如每股票/每固定时点只取当时已经可用的一个预测；按时间验证并防止同股重叠窗口穿越分割边界。选“当天最新一条”可做描述性复盘，但不能代表当天较早时刻已知的信号。

## 8. 方向阈值、RETURN_SIGN 与 Brier

见 PredictionService L36-46、288-301、375-394、445-493。

- LEGACY_1PCT：收益 >= +1% 为 UP、<= -1% 为 DOWN，其余 SIDEWAYS；边界包含 UP/DOWN。
- HORIZON_V2：T1 的 SIDEWAYS 为 [-1.5%,+1.5%]，T5/W1 为 [-3%,+3%]，M1 为 [-5%,+5%]；超过边界才 UP/DOWN。当前新请求默认传入该版本，重放使用冻结版本。
- 冻结 2,999 条全部为 LEGACY_1PCT；可见 1,025 个 v6 task 分析的 envelope.responseContractVersion=6，但顶层 directionPolicyVersion 未写明。responseContractVersion、task purpose 与方向口径不是同一个版本维度。不能把旧样本的 v2 回顾得分称作“当时模型按 v2 生成的历史准确率”。
- RETURN_SIGN：UP 且收益 <0、DOWN 且收益 >0 才背离；SIDEWAYS 不计分，零收益不背离。不背离不意味着超过波动阈值、赚取成本后利润或达到目标区间。
- directionalReviewReturnPct 优先使用有限 stored actual_return_pct（包括 0），仅在不可用时由 close/sourcePrice 推导；因此现有不可靠 stored return 会继续传入回顾指标。阈值重评分也不能修复尺度与未完成 bar。
- Brier 是 `(probability - binary(directionHit))^2`，不是三类别概率分布的多分类 Brier。混合版本时 API 抑制平均 Brier；currentPolicyDirectionHit 只是重判标签，不改旧概率原本对应的事件。即使全部一个版本，概率校准也仍需合格、独立且同语义的标签样本。

## 9. fixture/test 的可识别边界

未发现可以据此确认的当前预测 fixture 污染：只读查询没有 `009990`-`009999` 冒烟符号或已知方向测试 analysis_id=987654321/987654322。可见 snapshot 的 source 值为 Eastmoney quote=2,972 条、缺失 source=8 条；这只是来源字段，不是外部真值认证。

- 英文子串 `test` 会命中 `latestNews`，不能用于全 JSON 污染筛除；2,802 条可见 snapshot 的宽泛命中均属于这种候选级证据，不应计为测试数据。
- 唯一独立词 test 文本候选 prediction 850 实为自然语言“test 1.0 support”，关联真实 provider 标记且当前不可见，不能凭这个词认定 fixture。
- 2276/2277 关联 analysis 1624、task 929、SUCCESS、provider=deepseek_web_bridge；source=387.69。测试源码中的 300750 T5 样例 source=388.31。即使 actual_close=398.1 数字相似，也不构成 fixture 身份证据。大小写和看起来整齐的数值都不能单独证明污染。
- [方向持久化测试](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/test/java/com/aimeeting/room/StockWatchDirectionPolicyPersistenceTest.java:17) 使用 smoketest 和事务；[smoketest 配置 L5-13](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/test/resources/application-smoketest.yml:5) 指向 H2 内存库，而非默认 MySQL。它能证明这些测试的默认隔离设计，不能证明过去没有配置覆盖或其他人工写入。
- prediction schema 没有不可伪造的 fixture/provenance 隔离字段。本轮未逐条核验 provider ledger；不能声称“全部绝无测试污染”，也不能把可疑行自动剔除后报告所谓真实收益。

## 10. mainForceIntent 有验真结构，但不是实盘真值

v6 validateMainForceIntentV6 直接从 positioningProfile/atomCatalog 形成 intent，标记 contractVersion=6、evidenceQuality=SERVER_PROFILE_V1；原模型 mainForce 字段只留 raw 审计，见 [TechnicalAnalysisService L3386-3418](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:3386)。同一版本还通过可信 renderer 覆盖 title/stance/conclusion/confidence/evidence，见 L3433-3448。因此 provider=DeepSeek 不代表这些持久化字段都在测“模型原始判断”。

可见 1,416 个预测分析中，1,025 个是 SERVER_PROFILE_V1/contract 6，391 个缺此结构版本。以结构化 intent 统计派发特征，应叫服务端弱证据画像分层；以 DeepSeek 明确派发措辞统计，应从 raw 解析模型字段、去重并保留语境，不能互相替代。

旧表确实有结果，但语义有限：

- stock_watch_intent_test 共 286 个 test，仅 12 个 test 的 claim_ref 属于 `analysisTask:*:mainForceIntent`，覆盖 6 个独立模型 claim；其余 274 个为 R01-R04 规则测试，不能全部计为模型意图已验真样本。
- 模型意图测试为 FALSIFIED=9、SUPPORTED=3 个 test，不是 12 个独立交易；同一 claim 的多指标测试还可能给出不同 verdict。未在本轮计算其准确率。
- DISTRIBUTE/SUPPRESS 的预设证伪条件包括 dailyReturnPct >= 1% 或 mainInflow > 1 亿。某个条件触发即 FALSIFIED；SUPPORT/ACCUMULATE 等使用相反风险条件。它测的是预设价格/资金行为约束是否被推翻，不是“真实主力到底在派发”。
- SUPPORTED 表示达到观察年龄、具备所需观测数量且未触发该证伪条件；不是独立正向确认。年龄按不同已到达 sourceDate 累加，同日可多次测试但不重复计年龄，不保证连续交易日或完整盘中覆盖。某天第一次观测缺失、随后补值，还不能凭最终 observed_count 断言该天被充分覆盖。见 FactPersistenceService L270-280。
- VALID / VALID_WITH_WARNINGS 是解析/合同/弱证据画像状态，不是事后标签。价格资金行为无法证实身份、操纵或违法动机，当前 prompt 也明确这一限制，见 [L1688-1695](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:1688)。

另外只读复核 priceFailure state：bootstrap=last=908，started_at=2026-08-01 17:54:13，assessment=0。见 [ResearchService L185-201](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchPriceFailureResearchService.java:185)：初始化从当前 max observation 起步且 noBackfill；L87-105 只消费 cursor 之后的事实。本轮不能从这个状态主张已有前向规则验证证据，也未诊断其未推进原因。

## 可支持与不可支持

| 问题 | 现有证据可支持 | 尚不支持 |
| --- | --- | --- |
| 系统曾判断什么 | 分时点、版本、来源的输出审计 | 全部持久化描述等于 DeepSeek 原始结论 |
| 哪些形态值得研究 | 完成且同尺度结果上的描述性关联与候选假设 | 未完成/跨尺度 stored outcome 推导交易规律 |
| 看涨/看跌是否与后续方向矛盾 | 明确 RETURN_SIGN 和三分类分母后的历史回顾 | 同义地称为全预测胜率、未来概率或可实现利润 |
| 派发提示有无价值 | 预先定义的风险事件与后续回撤关联 | 真实主体意图；提示的独立增益或因果收益 |
| 能否做短线动作 | 明确存在时间、入场、缺失与相关性验证需求 | 原始 source_price 回报等于可交易入场结果 |

主代理的最终数值应明确：原始/API 可见群体、完成行情覆盖分母、尺度对齐失败数、有效生成时点、独立事件数、方向版本、缺失/旧 PENDING 的处理，以及重算标签不是 stored 标签。这里不另给总体准确率或策略建议。

## 查询复现摘要

只读命令：`shasum -a 256 /private/tmp/stock-watch-existing-data-2026-10-04.jsonl`；Node JSON.parse 逐行解析并按 `_kind` 分类，市场 lower/trim 后以 id 检查重复，按上述键与时间比较计数。没有导出 JSON 原文。

以下补充 SQL 展示决定边界的谓词；直接在 SELECT 内使用，未创建视图、临时表或更新状态。

```sql
-- 业务 API 可见群体，区别于冻结的原始全表。
SELECT COUNT(*) AS visible_rows,
       SUM(p.status='EVALUATED') AS evaluated,
       SUM(p.status='PENDING') AS pending
FROM stock_watch_prediction p
JOIN stock_watch_technical_analysis a ON a.id=p.analysis_id
LEFT JOIN stock_watch_analysis_observation_identity i
       ON i.canonical_task_id=a.analysis_task_id
JOIN stock_watch_analysis_migration_state ms
     ON ms.migration_key='stock-watch-v6'
    AND ms.generation=6 AND ms.status='READY'
WHERE i.analysis_scope='TECHNICAL_RUNTIME'
   OR (a.analysis_task_id IS NULL AND a.id<=ms.legacy_analysis_cutoff_id);
-- 结果：2980 / 2539 / 441。

SELECT IF(claim_ref LIKE 'analysisTask:%:mainForceIntent',
          'MODEL_INTENT','RULE_PATTERN') AS origin,
       test_verdict,COUNT(*) AS tests,COUNT(DISTINCT claim_ref) AS claims
FROM stock_watch_intent_test
GROUP BY origin,test_verdict;
-- MODEL_INTENT：FALSIFIED 9 tests/6 claims，SUPPORTED 3 tests/3 claims。
-- claims 跨 verdict 不可相加；整个模型部分总共只有 6 个不同 claim_ref。

SELECT rule_version,bootstrap_observation_id,last_observation_id,started_at
FROM stock_watch_price_failure_state;
SELECT COUNT(*) AS assessments FROM stock_watch_price_failure_assessment;
-- bootstrap=last=908；2026-08-01 17:54:13；assessments=0。
```

本次仅研究边界，没有实施修复；不宣称存量标签已修正、完成独立收益复算或完成实盘验证。最后的时间与市场计数、文件哈希均用 raw 命令复核，未依赖 RTK 摘要。
