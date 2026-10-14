# 股票分析系统可用性与优化优先级

日期：2026-10-05。范围是诊断与建议；未修改业务源码、业务数据库，未重启或部署服务。结论审核与路径比较为非独立自审，本轮未新启动 Challenge 或 Optimize 审核子代理；另按 research skill 委派了一名原始领域资料研究员。10 月 4 日统计报告此前经过独立 Challenge，不代表本轮整个优化计划也经过独立审核。

## Goal Contract v0

来源：用户本轮问题“你觉得现有系统存在哪些问题？如果想真正能用，应该做哪些优化？”，以及本会话已明确的短线交易需求、保留主力意图并行研究的偏好。

目标：区分已确认的工程缺陷、历史效果限制和待验证的产品假设，提出可分阶段验收、优先级明确的优化建议。

- G1：重要问题有当前源码或已核验研究工件支持，不把相关性当因果、不把研究标签当实盘真值。
- G2：刷新本库数据覆盖和独立账本状态，区分“未有运行证据”和“已证明唯一故障原因”。
- G3：建议同时覆盖短线风险辅助和并行意图研究，区分工程验收、模型增量效果和可执行交易效果。
- 非目标：本轮不实施修复，不作个股买卖或仓位建议，不承诺盈利或未来命中率。
- 未确认：具体持仓期限、是否已持仓、可接受的误报频率与错过上涨的代价。因此下述验收条件是建议，不是已经接受的交易合同。

## 当前证据

本轮 raw SQL 再次得到预测 2,999 条、EVALUATED 2,540 条、PENDING 459 条；成功分析 1,625 条，最新业务日期和创建时间仍为 2026-08-14。priceFailure assessment / ledger 均为 0，bootstrap_observation_id = last_observation_id = 908；事实记录 1,935 条。这些结果属于本地 ai_meeting 库，不外推到其他实例。

收盘前写入 EVALUATED 的时间标记仍为 1,578 / 2,540 = 62.1%。这不是“62.1% 的结果数值全部错误”。prediction 964 在目标日 09:29:53 结算，988 的存储源价 3.631 / 结果价 1.197 形成 -67.03% 收益，仍可从本库查到。

主要源码指针：

- [evaluateOne](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchPredictionService.java:269)：有足够未来日线就结算，没有完成日线门槛；直接将结果价格除以源报价。
- [selectPending](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/resources/mapper/StockWatchPredictionMapper.xml:82)：只选 PENDING，自动任务不复核已经 EVALUATED 的旧记录。
- [fetchDailyBars](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/EastmoneyMarketDataService.java:177)：评估读取 qfq 序列；没有事件报价锚点。日线校验在缺 quoteDate 时跳过报价一致性检查，见该文件第 343 行。
- [buildHistoryPromptContext](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:1777)：当前源码已有 eligible-only 历史过滤；不能继续把最初“不可评估历史全部进入提示词”描述成当前未修复事实。
- [已有研究结果](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/docs/research/stock-watch-existing-data-findings-2026-10-04.md) 与 [质量边界](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/docs/research/stock-watch-existing-data-quality-2026-10-04.md)：冻结快照历史重建，不是全量准确率或交易所成交核验。

CodeGraph status 为 up to date；使用 CodeGraph 导航，并对未展示的重要源码区域读取 raw 文件。本轮重跑研究脚本 7 个测试通过，只证明重算逻辑的这些测试，不证明生产系统已修复。

## 问题与收益排序

| 优先级 | 问题 | 证据与含义 | 建议验收 |
| --- | --- | --- | --- |
| P0 | 成绩单的结果真值不足 | 提前结算、价格尺度跨基准、没有旧评估自动复核 | 未完成目标窗口不终结；缺尺度锚点不评分；冻结并持久化评估输入、来源、版本；存量建立独立重评版本，不静默覆盖旧结果 |
| P0 | 连续运行尚未获证 | 本库数据截至 8 月 14 日，独立研究账本零结果 | 追踪事实到需求、任务、模型回复、分析、预测、成熟评估的实际业务链；复用已有调度和状态设施；核对部署源码版本，跨完整交易窗口检查新增记录与延迟 |
| P1 | 风险文字、方向和意图不是同一目标 | 通富 7 月 27 日文字派发但 T1 SIDEWAYS；没有账户级意图真值 | 独立冻结风险信号、时点、窗口与结果；意图单独作为可证伪假设，不把未触发证伪等同于意图确认 |
| P1 | 暂未显示稳定方向增量 | 可重建 T1 UP/DOWN 72/153=47.1%，同样本固定看跌 55.6%；T5 90/211=42.7% 对 55.9% | 在新样本对照预先固定的简单基线；保留方向研究但不把它当已验证的买卖引擎；基线不构成看跌交易建议 |
| P1 | 风险警报的历史高命中尚未转化为实用价值 | 强措辞历史研究存在迟到、事后挑选、反例，旧 8/9 不是 9 个可执行信号 | 报警必须早于待预测风险窗口；比较同时期同类波动和价格位置基线；报告精确率、召回率、覆盖率、延迟、误报和错过上涨成本 |
| P2 | probability 容易被当作兑现概率 | 均值 60.7%，LEGACY_1PCT 三分类 96/353=27.2%；>=70% 为 15/34=44.1% | 先标明模型自报、未经校准；修复标签后按固定事件、期限和版本做时间外校准，不能靠把筛选阈值提高到 70% 证明有效 |
| P2 | “震荡”和区间触达容易形成安全错觉 | SIDEWAYS 的 55/200 个样本次日最低价较判断价低至少 5%；range touch 是极值重叠，不是实际成交证明 | 分开显示方向、收盘区间与下行风险；交易评估不能以极值重叠代替止盈/止损先后或真实成交 |

收益排序指的是对可信评估和可验证辅助能力的预期贡献，不是已经测得的 DeepSeek 准确率提升。

## 建议路径

### 第一步：修复并验收评估链

围绕一个问题链完成成熟度、价格基准、数据完整性、缺失原因、结果版本和重复事件口径，避免只修其中一个字段就宣称成绩单可信。

实时源报价、最终日线、复权基准、生成时点、模型完成时点、评估时点分别保存。存在除权或价格尺度修订时，需要可核验的来源和事件尺度；不得默认对齐比例为 1，也不能把当前离线 previousClose 比例代理当成通用生产真值。

所有旧记录保留。新旧评分版本并行比较，缺证据的结果标记不可评分；分母公开包含多少到期、已完成、缺失、重评和不可评分事件。按事先定义的股票/时点/期限事件计分，不以同日多次回复扩充独立样本。

### 第二步：运行独立短线风险验证，意图继续并行

复用已有事实、R01-R04 特征、任务和 priceFailure 独立研究设施，不另建一套行情或将新研究字段混入主分析事实身份。先查明现有账本为什么没有前向结果并验收，不立即添加更多规则。

价格失效规则、DeepSeek 风险措辞、主力意图各保留来源，独立评分。价格失效不是派发真值，派发文字命中某次下跌也不等于稳定预警优势。

先冻结事件、基准、窗口、报警时点、阈值、去重和对照，再收集探索之后的新样本。旧数据只用于候选设计与修复验证，不能同时选规则和证明规则有效。现有冻结协议的解盲条件不因想尽快看到效果而事后改变。

区分三种指标：报警后的可观察价格风险、当时可执行的避险效果，以及实际交易净收益。后两种需要另外冻结信号可用后的可交易价格、持仓状态、执行顺序、不能成交情况、成本和机会损失；仅凭日线最低价无法得到。

### 第三步：对模型输入做有控制的增量实验

在合格新样本上比较“简洁的价格/资金/成交/位置事实”“加持续性与背离”“加合格历史或外部事件”等预先固定版本，使用同一事件事实快照、独立模型上下文和明确缺失标记。

检验每个增量是否提升方向或风险任务的时间外结果，并报告调用成本、延迟、覆盖率和失败率。资金价格同向比例高只能说明输出与当期状态一致，不能证明 DeepSeek 只看这两个因素，也不能保证删掉其他因素会更准。

公司事件、新闻、财务信息按任务相关性使用；不在没有消融证据前一概删除，也不以堆更多字段为目标。缺乏证据允许“不足以判断”，但拒答率必须纳入覆盖分母，不能靠只保留少数好样本制造高准确率。

## 路径比较与自审裁决

- A1：先统一修复评估与运行链，再独立验证风险辅助，保留意图并行。当前范围内优先，因它提供可归因、可复核的验收基础。
- A2：立即扩充历史、新闻和提示词，或将 probability 提高到 70% 才使用。不能修复结果标签、运行中断、报警迟到和成交边界；可能增加成本，效果待验证。
- A3：重做行情系统、模型和全部界面。切换与回归风险高，现有基础设施已能复用，未发现必须重建整个系统的证据。
- A4：不修成绩单，先单独做 risk ledger。可以并行做，但结果真值与运行验收仍是依赖；不能绕过 P0 后宣称风险产品已有效。

裁决：评估链问题与本库覆盖不足为“属实”；“DeepSeek 内部只由资金和价格决定”为“待补证”；“所有风险警报已经无效”为“不属实/证据不足”；“仅调整 probability 阈值就能变为实用系统”为“待补证”；把历史降噪当当前源码未修复项为“不属实”。

仍未证明：优化后准确率或净收益提高、风险预警优于简单基线、连续生产运行成功、真实意图识别正确。当前源码过滤存在也不自动证明当前进程加载了它，本轮未调用新 DeepSeek 任务验收。

历史表现、回测和目标价格不能替代未来或实际执行结果；成本、基准、市场环境和样本选择需公开，参考 [SEC Performance Claims](https://www.investor.gov/introduction-investing/general-resources/news-alerts/alerts-bulletins/investor-bulletins-47)。

## Goal Contract v1：加入专业领域与整体设计审查

来源：用户追加要求“除了具体的局部问题，整体设计上也分析存在哪些问题，是否符合专业领域知识，因为这个就是我凭自己的感觉临时搭建的系统”。

v1 保留 G1-G3，追加 G4：对照市场微观结构与量化研究的原始资料，解释任务、观测、假设、预测、风险辅助、结果验证之间的区别，核实当前实现已有的专业化设计，不把旧方案缺口直接当成当前事实。

变更理由是用户扩大范围，不是为了迎合某个方案；v0 的诊断只能回答局部优先级，不能单独宣称已完成 v1。下面的整体判断属于受限设计评估，不是专业认证、完整交易系统审计或盈利证明。

## 整体判断

当前更接近“有可追溯基础的行情分析与研究工具”，还不能凭现有证据当成已验证的短线决策系统。主要不足是领域任务和验收链未闭合，不是缺少几个技术指标，也不是 Java、Vue 或使用 DeepSeek 本身有问题。

保留已有基础：事实观察、canonical task、冻结 request envelope、结果投影、来源状态、原始回复、程序风险结构、模型失败时本地规则推进。当前 v6 已将模型意图放在原始审计回复中，服务端计算弱证据画像；这比将模型自然语言视作意图真值更符合可审计的研究方式。

但“数据与回复可追溯”不等于“具有预测优势”，“JSON 与算术通过验证”不等于“概率和目标价经过校准”，“通知资格通过”不等于“适合交易”。

### D1：潜在意图与可观察风险不是同一种研究对象

真实账户、库存变化、执行目的属于潜在对象；系统输入是报价、供应商资金字段、价量衍生量及公开资料。没有账户级真值时，不能把“资金净流入”直接解释为特定主体吸筹，也不能把后续下跌当成证明派发身份的标签。

建议意图研究保留互相竞争的解释、支持与反证、具体可观察预测和失效条件。单独统计这些预测是否兑现，不宣称统计真实主体心理。价格失效/下行风险则可以采用预先冻结、可观测的结果定义，独立验证。

订单流与同期价格联系有微观结构研究基础，但不是实名身份或事前交易收益的证明；大订单也可分拆执行。[Cont et al.](https://arxiv.org/abs/1011.6402)、[Lillo et al.](https://arxiv.org/pdf/cond-mat/0412708)。东方财富终端 FAQ 说明其特定产品资金流由软件计算，主力净流入是大单/超大单统计；它没有证明本系统接口的确切统计口径或真实主体身份。[供应商 FAQ](https://emdesk.eastmoney.com/pc_activity/pages/viptrade/pages/answer.html)。本轮 web 文本抽取漏掉 FAQ 隐藏答案，随后 raw curl 核验了第 33、34、36 问的 HTML；不将产品营销表述当成识别有效性。

资料研究员的市场机制与代理变量边界记录见 [专业原始资料核查](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/docs/research/stock-watch-professional-domain-sources-2026-10-05.md)。该子任务没有代码或全系统合格性结论。

当前代码已经限制真实身份和操纵推断；应保持该边界，而不是恢复强主力故事。程序测试成熟且没有命中失效条件会标为 SUPPORTED，见 [evaluateExistingTests](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchFactPersistenceService.java:262)。这里的命名不能被产品或统计升级为“意图证实”。

### D2：分析频率、输入尺度与预测持有期没有形成一套明确合同

DAY 提示词要求“30分钟级别技术面分析”，见 [buildPrompt](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:1676)。

实际这条链输入日线，提示词 DAY 只取最近 20 根日线、15 条分钟趋势/资金记录，见 [toPromptMarketDataPayload](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:2163)。buildBundle 的 DAY 不生成显式 periodKline；分钟资金接口 klt=1，见 [buildBundle](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/EastmoneyMarketDataService.java:108) 与该文件第 410 行。不能将“每 30 分钟调用一次”当成“拥有完整 30 分钟 OHLCV/资金窗口”。

结果却按源日期之后第 1/5 根日线的收盘评分，源日剩余盘中路径不在 T1 中。这并非禁止跨尺度预测，而是必须明确输入尺度、形成时点、可用时点和结果窗口；当前不能仅凭“30分钟分析”文字验收盘中风险。

最终 bar 的完成状态还需按标的交易日历、交易机制和来源确认，不能将固定钟点当成所有市场的充分条件。港股适用证券有连续交易之后的收市竞价阶段；本轮历史重建使用的 16:00 只是代理边界，不应原样升级为通用生产完成门。[HKEX Trading Mechanism](https://www.hkex.com.hk/Services/Trading/Securities/Overview/Trading-Mechanism?sc_lang=en)

建议先确定少量任务，例如“信号可用后至下一交易日收盘的价格风险”与“未来五个交易日的方向”，再决定需要哪些完成或未完成 bar、分钟事实以及各自数据质量条件。跨尺度字段保留但带语义，不假装已经重采样完成。

### D3：看当前市场状态、预测未来与形成决策应分别验收

价格和资金同向可用于描述当前状态，不能直接产生未来收益或尾部风险优势；更不能由“派发”自动推出某个卖出时点。

当前 v6 普通界面的 title/stance/conclusion/confidence 由 TrustedTechnicalRendererV1 覆盖，模型 forecasts 仍保存。见 [applyTrustedV6Payload](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:3433) 和 [persistPredictions](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchPredictionService.java:153)。因此一条“分析结论”实际可能有服务端画像、模型原文和模型预测三个来源；不能都用 DeepSeek 准确率概括。

建议明确每个结果的来源、任务、版本与验收目标。意图、方向、风险三路并行共享事实，但任何一路不作为另一路已经正确的前提。Qlib 的官方工作流也区分数据、模型、信号评估与回测；这里只借鉴分层，不建议将它直接嵌入生产或推定本系统必须采用其模型。[Qlib Workflow](https://qlib.readthedocs.io/en/latest/component/workflow.html)

### D4：强制给方向/目标价，并不能产生合格的预测产品

DAY 合同要求 T1/T5，缺项则整个投影被判 invalid；合法方向限 UP/DOWN/SIDEWAYS，见 [projectPreparedTask](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:851) 和 [validatePredictionsStrict](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchPredictionService.java:93)。

这保证结构完整，但不表达“当前没有可验证的增量判断”。SIDEWAYS 是未来收益的具体类别，不是“不知道”，也不是低风险。

probability、预测区间必须有固定事件和覆盖语义，例如所选方向发生概率，或某期限收盘价的固定覆盖预测区间。不能把代码夹紧到 0-1、规则 confidence 或模型语言自信当成校准。预测区间的覆盖与宽度都应评分，而不只看是否触达。[Gneiting and Raftery, 2007](https://sites.stat.washington.edu/raftery/Research/PDF/Gneiting2007jasa.pdf)

建议允许明确的不足以判断状态、分任务的可用性和覆盖率；只保留少数预测时必须公开拒答与缺失，不以筛掉难例制造高准确率。

### D5：规则确定性不等于专业阈值或统计概率

结构化 quote 规则分支存在“接近涨跌停”9.5%、“主力净额绝对值”1 亿、固定量比和换手阈值，见 [judgeStructuredQuoteRule](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java:235)。该分支 confidence 为 0.82 或 0.88 加信号条数，上限 0.98；风险结构命中还会置 1.0，随后与 minConfidence 比较，见 [evaluateAiRule](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:404)。这是命中资格/启发式分数，不是未来正确率。

沪深不同板块、风险警示、ETF 与港股不能默认共享“涨跌停”语义；同样 1 亿资金对不同成交额、流通规模与波动环境意义不同。应把标的交易机制、字段单位和来源统计口径纳入规则资格，并优先比较标准化和分环境基线。绝对额可以保留为流动性/重要性条件，但不能单独认定意图或优势。

当前 buildPrompt 的 DAY/WEEK/MONTH 分支均使用 A 股角色描述，而数据路径允许港股。应复用现有 market 字段明确角色和适用规则，不能让同一人设隐含覆盖不同市场；这不证明每个港股回复因此出错。

这是该已检查分支的语义风险，不代表所有线上规则都经过它，也不证明其所有提醒无效；本轮未按真实配置逐条执行告警。

### D6：研究应先固定可失败的实验，再追求更好表现

2,999 条预测记录不是 2,999 个独立交易事件；同股同日、重叠 T5 窗口、相同市场环境与长期模型会话均造成相关性。时间外验证需处理这些依赖，不能随机按预测行拆分后宣称稳健。

看见几次典型出货命中后再选择措辞、阈值、标的和窗口，是探索，不是独立验收。反复在同一组历史记录中找最好结果存在选择偏差，原始研究明确讨论过该问题。[Bailey et al., 2015](https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf)

建议记录试过哪些规则和模型版本，冻结验收后使用后续新样本，按时间/股票事件处理依赖；基线也不能事后择优。对高位、高波动警报，必须与同类条件基线比较，而不是与整个股票池平均风险比较。

模型网页路由、会话与 FULL/DELTA 也是实验输入的一部分。冻结 prompt 哈希不自动冻结远程模型或完整隐含会话；不可固定的部分应作为可复现性限制记录。当前会话与投影已有记录，实验应复用它们，不另造第二套 task owner。

### D7：辅助交易也需要执行语义，但不必现在建设自动交易系统

当前预测保存 source_price，但它是在模型调用前截取，不是收到信号之后的可成交价格。先跌 8% 再收到预警，与提前收到预警，是不同产品结果。

同一信号对“准备新买”和“已有可卖持仓”意义不同。没有委托/成交也可以先做纸面政策评估，但需冻结可用时点、持仓情景、退出规则、不能成交的情况、成本和机会损失。缺少这些，最多能报告价格风险关联，不能报告实际避免损失。

这不是要求立即接券商或扩大到组合管理；现有系统的交易质量/通知门与未来订单放行应继续区分。7 月 16 日 [TradeGate 影响分析](/Users/wangzilong/IdeaProjects/ai-meeting-room-new/docs/stock-watch-tradegate-report-impact-analysis-2026-07-16.md:15) 已作这种设计区分，但该旧文不被当成当前实施证明。

## 建议的领域分工

共享事实与交易机制 -> 可观察特征 -> 三条独立研究线（意图假设 / 价格预测 / 下行风险）-> 各自冻结结果与基线 -> 人工决策辅助 / 执行情景评估。

主力意图研究不需要停止，也不应成为下行风险预警的前置条件。实际优先级仍是修成绩单和运行证据、统一任务/时间/概率语义、独立验证短线风险，然后以消融实验决定新增哪些因素。

目前低优先级：继续堆未经评估的技术指标和历史原文；给 LLM 更强的专家人设；事后提高 confidence 筛选阈值；单纯增加模型辩论轮数；为了更专业而全量重构或立即购买更高频数据。它们不是永远无价值，而是没有证据表明能先于上述缺口解决本轮目标。
