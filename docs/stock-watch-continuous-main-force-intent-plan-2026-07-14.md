# 股票异动判断：连续主力意图估计与证伪闭环技术方案

日期：2026-07-14

状态：第十三轮 challenge / optimize 均未通过；本文已被 `stock-watch-continuous-main-force-intent-plan-v16.md` 取代，仅保留前十三轮审核历史与 fixture 证据，不得作为实施规范

## 1. 背景与问题定义

当前异动判断已经把最近两周技术分析作为 `recentTechnicalAnalysis` 传给 DeepSeek，但模型仍可能把它退化成“两周行情摘要”，再用当天资金流直接给出主力意图。用户需要的不是历史行情复述，而是一个连续收敛过程：第 N 次判断必须验证第 N-1 次留下的判断和可证伪条件；无论上一轮正确与否，都要借助本轮事实缩小候选解释范围。

主力真实心理不可直接观测，且聚合资金流可能来自多个参与者。本方案的可验证目标因此定义为：

> 在不把模型推断伪装成事实的前提下，持续降低“当前占主导的资金行为策略”的不确定性，并完整记录每次状态更新、支持证据、反证、被淘汰假设和下一轮验证条件。

## 2. 当前代码/事实证据

### E1：异动触发已有 prompt 约束和不完备关键词防线，尚未形成严格引用边界

- `StockWatchAiJudgeService.buildPrompt` 规定 `triggered/reason/matchedSignals` 只能引用本轮 `quote/currentFacts`。
- `guardHistoricalTriggerSource` 只做关键词匹配，可拦截显式的 `recentTechnicalAnalysis/mainForceIntent/历史技术分析`，但“上一轮已经确认”等改写可能绕过。
- 因此当前只能称为弱防线。本次必须增加结构化引用校验：触发字段中的每个 `matchedSignals` 都要映射到本轮允许事实路径；历史路径只能出现在连续意图解释中。

证据：

- `src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java:301-315`
- `src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java:244-282`

### E2：历史日内链已经落库，但异动上下文按“每天最后一条”投影，遗漏相邻槽位

`selectLatestSuccessfulDailyByDateRange` 对每个 `analysis_date` 只保留最后一条成功记录。若 10:00、10:30、11:00 都有判断，数据库仍保存这些记录，但只有当天最后一次进入后续异动上下文，无法实现“第二次验证第一次”。现有 Mapper 已有全量日期范围查询，冷启动应复用它或在 Java 中取最新 48 条后改为时间升序，不新增重复 SQL。

证据：

- `src/main/resources/mapper/StockWatchTechnicalAnalysisMapper.xml:131-150`
- `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:688-725`

### E3：技术分析模型只显式收到最近两条同周期记录

`StockWatchTechnicalAnalysisService.buildPrompt` 使用 `selectRecentBySymbol(..., 2)`。DeepSeek 网页会话可能保留更早上下文，但它不是结构化、可审计、可在换会话后可靠恢复的事实链。

证据：

- `src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:642-668`

### E4：现有主力意图模型把“仓位目标”和“执行手法”混为单选枚举

当前 `intent` 同时包含 `ACCUMULATE/DISTRIBUTE` 和 `WASH/PULL_UP/SUPPORT/SUPPRESS`。现实中可能“压盘吸筹”或“拉升派发”，单选会制造伪冲突。

证据：

- `src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:695-698`
- `src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java:1236-1300`

### E5：现有预测模块已有可复用的客观验真能力

`StockWatchPredictionService` 已保存 T1/T5 等预测，并能计算方向命中、区间命中、失效价、绝对误差和 Brier 分数。意图闭环不应另造价格预测验真器，应引用这些结果作为客观证据之一。

证据：

- `src/main/java/com/aimeeting/room/service/StockWatchPredictionService.java:50-93`
- `src/main/java/com/aimeeting/room/service/StockWatchPredictionService.java:161-199`

### E6：异动模型的每次判断当前没有独立的数据库历史链

`StockWatchMonitorService.evaluateAiRule` 返回本轮 `AiDecision` 并写日志/运行响应；持久状态 `lastAiAlerts` 只在邮件成功后更新，用于通知去重，不等价于每轮主力意图分析历史。不能用邮件状态承载分析状态。

证据：

- `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:318-421`
- `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:583-597`

## 3. 目标、非目标与不变量

### 3.1 目标

1. 每个被系统接受的股票 observation 都能读取上一有效意图状态、全部未终结验证条件和本轮程序验真结果；状态不按规则调用次数推进。扫描期间出现并发回压时只保留最新待处理快照，并显式记录合并数量，不承诺逐 tick 消费。
2. 第一次运行可用最近 14 天内每个成功日内分析槽位做冷启动，不再只取每天最后一条。
3. 模型只输出仓位目标候选、执行手法、候选解释和下一轮可观测 claims；Java 根据账本生成历史复核、状态转移和可观测 claim 的验真结论。
4. 每轮结果不可变持久化，并可追溯到前一轮、技术分析记录、本轮事实快照、规则快照和模型会话元数据。
5. 不增加“独立意图分析”逻辑调用：连续意图更新搭载在现有某一次异动判断响应中；现有 bridge 恢复重试策略单独计数，不伪称每轮最多一次物理 HTTP 请求。
6. 模型结构化输出失败时，异动触发判断仍可完成；意图状态标记无效并继承上一有效状态，不把坏响应写成新结论。

### 3.2 非目标

1. 不宣称识别了唯一资金主体的真实心理。
2. 不在本次改造中训练 HMM、分类器或重新定义所有技术指标。
3. 不把自然语言规则的模型判断伪装成程序已经证明；现有结构化规则仍由 Java 决定，现有自然语言规则仍由模型判断并标注来源。
4. 不因为意图变化直接发邮件；通知仍由现有触发、置信度、冷却和单股投递配置控制。
5. 不用 DeepSeek 再调用一次“自我审核”；客观验真由程序执行。

### 3.3 核心不变量

- I1：只有规则显式携带且通过完整 schema 校验的 `structuredPredicate` AST 才能产生 `STRUCTURED_RULE`；其 triggered/operator/expected 由 Java AST evaluator 生成。`conditionText` 永远按自然语言处理，不做部分解析。自然语言规则的 triggered 明确标记为 `MODEL_INTERPRETATION`，模型只提交当前事实路径，Java 重取 actual 但不宣称证明了自然语言语义。`reason/matchedSignals` 是展示文本，不承担事实证明责任。
- I2：历史观点只能被本轮结构化事实或程序验真结果复核。`FALSIFIED` 只描述与 `claimRef/testId` 绑定、预先登记且期限语义完整的可观测 claim；它可以来自期限内决定性 match，或在数据覆盖完整时期限届满仍未出现预期 match。预测结果只能验证对应 forecast claim，不能直接证伪资金意图。
- I3：仓位目标与执行手法分轴表达。
- I4：程序维护该股票最多 6 条未终结 `activeTests`，达到容量时拒绝新增而不删除旧测试；模型只能提出测试结构，不能生成 canonical claim 文案、重置剩余观察次数或改写终态；没有新验真证据时 belief weight 不得显著上升。
- I5：每次状态更新都保留 `previousEvaluationId`、`observationKey`、来源记录 ID、规则快照、prompt hash、provider/session/attemptCount。
- I6：股票级 durable head/lease 保证同一时刻只有一个**可提交**的 owner leasedAttempt；lease 过期边界可能出现重叠物理请求，但只有有效 token/fence 可提交。leasedAttempt 只有在事务提交成功后才成为 acceptedObservation；同一 `symbol+market+observationKey` 最多提交一条评估记录。只有 acceptedObservation 消费旧测试，忙时新快照进入 latest-wins 单槽回压并计数，不能被描述成已消费 observation。
- I7：本功能不发起第二次意图专用请求，也不因 schema 无效发起修复请求；API 到专用 bridge fallback 保留，但 fresh-session 只允许明确 FAILED_PRE_SUBMIT。Bridge 先证明 stock continuity role、profile/browser ownership 与跨进程独占，再在不可逆点击前 durable reservation、提交后等待 DB ACK；在锁、journal、DB 未被人工删除或回滚的边界内，同 observation 至多一次 reservation 和一次不可逆浏览器调用。系统分别暴露 inference/control HTTP、reservation、confirmed invocation 与 possible upper bound，UNKNOWN 不冒充真实提交次数。账号级 circuit 在系统性风控、登录故障、browser target 未确认 teardown、所有权或实例握手失败时 fail-closed。

## 4. 总体架构

```text
StockWatchMonitorService
  ├─ 获取 quote / currentFacts / 两周技术分析
  ├─ 计算 observationKey（股票 + 上游源时间 + canonical facts hash；禁止本地时间）
  ├─ StockWatchIntentStateService.acquireObservationLease(...)
  │    ├─ durable head 使用数据库时钟 CAS 获取股票级 lease，网络调用不持有 DB 事务
  │    ├─ 获取成功只产生 leasedAttempt；最终事务成功才成为 acceptedObservation
  │    ├─ 同一 observationKey 已存在任意评估：本轮只判断触发
  │    ├─ 其它进程 lease 有效：不发起重复 owner 调用，把最新快照写入 pending 单槽并增加 coalescedCount
  │    └─ 下一次调度优先处理 pending 最新快照；被覆盖样本不消费 tests，也不伪称 evaluation
  ├─ StockWatchIntentStateService.prepareContext(...)
  │    ├─ 读取上一有效 intent evaluation
  │    ├─ 程序验收全部未终结 activeTests；重复 observation 不消耗次数
  │    ├─ 读取已到期 stock_watch_prediction 结果
  │    └─ 生成 continuityContext
  ├─ 为股票选择确定性 sticky owner rule，并先执行 owner
  │    ├─ 上一成功 owner 仍启用：继续使用
  │    ├─ 否则按规范化 ruleId 字典序选择一次并持久化
  │    └─ 非 owner rules 只判断各自触发，不输出或提交 intentUpdate
  ├─ StockWatchAiJudgeService.judge(..., includeIntentUpdate)
  │    ├─ 本轮规则触发判断
  │    └─ owner 在同一次响应输出精简 intentUpdate v2
  ├─ StockWatchIntentStateService.persist(...)
  │    ├─ 校验 intentUpdate schema / 引用路径 / belief weight
  │    ├─ 一个事务校验 lease token/fence/stateVersion、插入 evaluation、推进旧 tests
  │    ├─ SUCCESS：更新 intent head，程序派生状态转移并新增 claims/tests
  │    └─ INVALID/ERROR：不更新 intent head、不新增 tests，但旧 tests 已按有效 observation 推进
  └─ 原有通知门控（不受历史状态直接驱动）
```

### 4.1 Owner / cadence 方案比较与决策

候选 A：由 `stock_watch_technical_analysis` 拥有连续状态，异动判断只消费。优点是复用现有快照、raw response 和预测关联；缺点是本地运行可关闭技术分析调度，且异动发生在两个技术分析槽位之间时无法用本轮异动事实更新状态，不满足“异动判断产生的分析也参与下一轮验证”。

候选 B：由 AI 异动路径按规则分别拥有 `stock+rule` 状态。实现简单，但同一行情会因规则数量多次推进，主力意图被人为拆成多个互相不一致的链。

采用候选 C：**异动路径按 `symbol+market+observationKey` 拥有一条股票级连续状态；规则只是本 observation 的确定性 carrier，不是状态身份。** 技术分析记录作为冷启动和转折证据，异动状态独立持久化。该方案比 A 多一张评估表，但覆盖技术分析关闭和异动新事实；比 B 避免规则数量改变状态进度。

Owner 算法：读取上一成功状态的 `ownerRuleId`；该规则仍启用则 sticky 复用，否则把同股票启用规则按规范化 `ruleId` 字典序排序，选择第一条并持久化。进入规则循环前为每只股票构建一次**规则无关 canonical context**：owner 总是获取完整 `quote + currentFacts + intentContinuity`，不再由 `conditionText` 决定是否加载增强事实；intent 指令与 rule condition 分区，condition 只能影响该规则的触发判断。owner 在该股票规则组中优先执行，输出无效时本 observation 记为 INVALID，不允许后续规则接替，下一 accepted observation 再尝试。

`observationKey` 由程序从股票、上游行情源 `quote.timestamp/quote.updateTime` 和 quote/currentFacts 白名单字段的规范化 JSON 哈希生成。`sourceTradeDate` 只有在 `sourceTradeDateQuality.selectedSource` 明确为 `quote.timestamp` 或 `dailyKline.latest` 时才允许加入；`system_date_fallback` 必须排除。明确禁止使用 bundle `asOf`、`requestedAt`、抓取时间或本地 `now()`。源时间缺失时只使用规范化事实哈希。相同 key 的手工重跑、多规则调用、进程重试均不推进 activeTests。

术语边界：`sampledSnapshot` 是扫描抓到的行情；`leasedAttempt` 是取得 owner lease、允许发起模型请求但尚未提交的尝试；`acceptedObservation` 是 token/fence/stateVersion 校验与 evaluation/ledger/head 事务全部成功后的唯一观测；`coalescedSnapshot` 是 lease 忙时被 latest-wins 回压槽合并的快照。I2/I4 中“后续 observation”只指 acceptedObservation。超时、lease 过期或事务冲突的 leasedAttempt 不消费 tests。系统不声称逐 tick 分析；它保证在受控请求速率下按 acceptedObservation 连续收敛，并对回压合并提供 `pendingObservationKey/pendingSourceTime/pendingArrivalVersion/coalescedCount` 可观测性。该语义是账号安全优先的有意取舍。

## 5. 数据模型

新增股票级游标表 `stock_watch_intent_head`，负责 owner、线性链和网络调用 lease，不把数据库事务跨越模型请求。

| 字段 | 类型 | 含义 |
|---|---|---|
| symbol / market | VARCHAR PK | 股票状态唯一键 |
| latest_success_evaluation_id | BIGINT nullable | 上一有效 intent state |
| last_observation_key | VARCHAR nullable | 最近已提交的任意状态 acceptedObservation |
| last_accepted_source_time / last_accepted_source_date / last_accepted_source_quality | DATETIME / DATE / VARCHAR nullable | 最近已提交快照的上游时间水位；quality 只允许 INSTANT/TRADE_DATE/NONE |
| last_accepted_arrival_version | BIGINT nullable | 最近已提交快照的数据库登记序号；用于无可信源时间链的到达顺序水位 |
| owner_rule_id | VARCHAR nullable | sticky owner |
| session_meta_json | TEXT | owner session generation/turn/context 状态 |
| last_delivered_resolution_evaluation_id | BIGINT nullable | 最近一次已被 SUCCESS intent state 消费的终态 test 评估水位 |
| state_version | BIGINT | 只在 acceptedObservation 提交时递增，保护已提交状态链 |
| arrival_version | BIGINT | 每个 sampledSnapshot 在候选选择前由数据库原子递增，作为同股统一到达序号 |
| lease_fence / lease_token / lease_observation_key | BIGINT / VARCHAR nullable | 每次 acquire/takeover 原子递增 fence；只保护在途 owner 调用 |
| lease_source_time / lease_source_date / lease_source_quality / lease_arrival_version | DATETIME / DATE / VARCHAR / BIGINT nullable | 在途候选 freshness，busy 样本用同一比较表避免排队更旧快照 |
| lease_until | DATETIME nullable | 由数据库时钟计算的租约截止时间 |
| pending_observation_key | VARCHAR nullable | lease 忙时 latest-wins 单槽中的最新快照 key |
| pending_source_time / pending_source_date / pending_source_quality | DATETIME / DATE / VARCHAR nullable | pending 快照 freshness；quality=INSTANT/TRADE_DATE/NONE |
| pending_arrival_version | BIGINT nullable | pending 内容对应的统一 arrivalVersion，同时作为条件清槽的内容版本；纯计数不改变 |
| pending_fact_snapshot_json | MEDIUMTEXT nullable | 待下轮 owner 使用的规范化事实，不包含 Cookie/凭据 |
| coalesced_count | BIGINT | 因回压被合并的 sampledSnapshot 数量 |
| gmt_modify | DATETIME | 更新时间 |

每个 sampledSnapshot 在 quote 规范化完成后、构建昂贵上下文前，先通过同一个股票 head SQL 原子递增并取得 `arrival_version`。lease 空闲时注册与 acquire 可在同一短事务完成；lease 忙时注册与 pending 判断在同一行锁事务完成。该序号定义的是系统登记顺序，不冒充交易所时间，也不进入 observationKey。

调用模型前用短事务获取 lease：原子递增 `lease_fence`、写随机 token/候选 sourceTime/sourceDate/sourceQuality/arrivalVersion，并捕获 `state_version` 与 acquire 时 pending 的 `pending_observation_key/pending_arrival_version`；pending 更新绝不改变 stateVersion、leaseFence 或 leaseToken。所有 expiry 比较和 `lease_until` 计算使用数据库 `CURRENT_TIMESTAMP`，不混用 JVM 时钟。租期下限由真实最坏 provider 链计算：

`leaseSeconds >= apiCallTimeoutSeconds + bridgeMaxHttpAttempts * bridgeEndToEndCallTimeoutSeconds + commitMarginSeconds`

Bridge 端到端 timeout 不再由 Java 猜测 provider 子阶段，而以 Bridge `/health` 暴露且运行时强制执行的 HTTP outcome 边界计算：`bridgeServerUpper=queueTimeoutMs + requestHardTimeoutMs`，`bridgeClientCallTimeout>=bridgeServerUpper+transportMarginMs`。当前安全启动配置为 `300 + 360 + 30 = 690 秒`；lease 默认下限为 `150 + 2 * 690 + 60 = 1,590 秒`。`requestHardTimeoutMs=360s` 是从取得全局 slot 到 HTTP outcome 与 fail-closed 状态转换完成的绝对上界，不冒充浏览器/远端生成一定已经停止。Bridge 在 `requestHardTimeoutMs-abortGraceMs` 时触发统一 `AbortController`，cooldown、target preparation、CDP 调用、提交前延迟和 answer wait 都传播同一 signal。abort 时关闭当前 CDP client并等待 Node worker settled；marker 前可在 settled 后释放，post-marker 还必须完成浏览器 target 级恢复。

8791 升级为第二个 DeepSeek 账号的股票盯盘统一 broker，必须声明 `bridgeRole=stock-watch`，使用固定端口 8791、debug port 9344、canonical stock profile 和独立 journal。它不仅服务连续意图，而是对同一账号的全部股票请求实施一致的串行队列、最小间隔、幂等 journal、熔断和冷却。每个 inference 在进队前必须同时通过封闭校验：`bridgeRole=stock-watch`、`requestPurpose`、稳定 `submissionKey`、expected instance/fingerprint。`requestPurpose` 只允许：

- `intent-continuity`：`submissionKey=intent:{symbol.market}:{observationKey}`；
- `technical-analysis`：`submissionKey=technical:{symbol.market}:{periodType}:{analysisDate}:{runSlot}:{canonicalSourceHash}`；
- `rule-judgment`：`submissionKey=rule:{ruleId}:{observationKey}`，只为无法编译的自然语言规则保留，结构化规则不请求模型。

三类 key 均不得含 leaseFence、requestId 或本地时间，并由 Bridge 按 purpose 重算 key schema，不只信任调用者传值。未标记请求、未知 purpose、key 不匹配、通用 OpenAI adapter 和任何指向 8789/9333 的 fallback 都在进队和 reservation 前拒绝。连续意图在 evaluation 事务提交后 ACK，技术分析在分析记录事务提交后 ACK，自然语言规则在可持久的判断结果处理后 ACK。这样技术分析不会绕过相同账号的风控边界，也不需要回退到可能已被禁用的通用账号。

Broker 在读取 journal、创建 profile 目录、执行 recovery、连接 Chrome、创建 target 或 listen 之前，先按 canonical profile path 与 canonical journal path 的稳定顺序取得两把跨进程独占资源锁。启动 shell 不再预先 `mkdir $PROFILE_ROOT/Default`；profile 初始化必须在 Node 取得两把锁后完成。锁使用本地原子 lock directory + owner metadata；metadata 包含 Node PID、OS 进程启动时间、boot token 和 canonical resource path。遇到活 owner 时第二进程在任何 mutation 前退出；只有 PID 不存在或启动时间不匹配时，才可原子 rename stale lock 后竞争重建。每次 journal mutation 还执行锁内磁盘 revision 连续性检查；它是双重故障检测，不宣称为独立文件系统 CAS。异常变化立即 fail-closed。正常退出清锁，崩溃由 PID/start-time 安全回收；不得只凭陈旧 PID 数字删锁。

专用 Chrome 首次由该 Broker 启动时，client 在命令行携带随机 launch token。debug endpoint ready 后用 OS socket 查询解析 `9344` 的实际 LISTEN PID，再校验该 PID 的进程启动时间和命令行精确包含 canonical profile/debug-port/launch-token；只有实际 listener 验证成功后才原子持久化 `browserOwnership={listenerPid,listenerProcessStartTime,canonicalProfilePath,debugPort,launchToken,browserWebSocketIdentity}`。spawn PID 与 listener PID 不同时不做等价假设，只使用通过命令行/token 复验的 listener PID。首次持久、health、connect、target close 和 `Browser.close` 前都重新查询 socket owner，并要求 owner record、PID 未复用、进程命令行、当前 browser WebSocket identity 一致。任何一项不可验证则 `browserOwnershipVerified=false`、不创建/关闭 target、不执行 Browser.close，只保持 recovery fail-closed 等待处理。health 暴露 listener identity 验证结果，Java 仍要独立比较端口、debug port、canonical profile/journal、role 和 capabilities，不只信任 fingerprint。

存量环境迁移必须 fail-closed：先停旧 stock-watch Node；若 9344 仍存活且没有新格式 owner record，标记 `LEGACY_UNOWNED_BROWSER`，新 Broker 不自动接管、不自动关闭、不监听 8791。运行手册要求操作者用 socket PID + 进程启动时间 + 命令行 profile/debug-port 证明它是旧股票专用 Chrome，只终止该精确 PID，确认 `/json/version` 已不可达后再启动新 Broker，由新 Broker 产生 token/owner record/locks。禁止用宽泛 `pkill Chrome`。

post-marker abort 的 durable reservation 必须已包含 `targetId/browserOwnershipSnapshot/reservedAt`。Bridge 在关闭前再次复验当前 ownership 与 reservation snapshot；匹配后先调用专用 debug port 的 `/json/close/{targetId}`，轮询 `/json/list` 确认 targetId 消失；失败才通过已核验 browser WebSocket 执行 `Browser.close`，并确认 PID+processStartTime 和 endpoint 都消失。ownership 不匹配时绝不关闭任何 target/browser。只有 target/browser teardown 已确认，`browserTargetQuiescence` 才可从 UNCONFIRMED 变为 RECOVERING；账号 circuit 仍保持 OPEN_TRANSIENT，经过默认 15 分钟安全窗口后才进入 HALF_OPEN 单探针。到绝对 HTTP 上界仍无法完成 teardown 时先返回 UNKNOWN 并保持全局 fail-closed，后台只能继续 teardown，不能释放给第二 key。Node 重启不等于恢复：启动时只要 journal 存在未 ACK 的 SUBMITTING/SUBMITTED/UNKNOWN，就恢复 `OPEN_TRANSIENT_RECOVERY + browserTargetQuiescence=UNCONFIRMED`，按持久化 ownership snapshot 执行同一恢复流程，不能因本地没有旧 Promise 自动置 QUIESCED。watchdog 不得直接清锁。

Broker health 返回随机 boot `instanceId`、覆盖全部 deadline/cooldown/profile/debug-port/role/journal/capabilities 参数的 canonical `configFingerprint`、`bridgeRole=stock-watch`、`capabilities=[intent-continuity-v1,technical-analysis-v1,rule-judgment-v1]`、`browserOwnershipVerified/enforcedDeadline/browserTargetQuiescence`、listener PID/start-time 以及 queueTimeout/minInterval/postCooldown/maxInFlight/requestHardTimeout/abortGrace。Java 在取得 DB lease 前执行短超时 health 控制调用并保存 eligibility snapshot；只有 role/capability/purpose 精确匹配、ownershipVerified=true、enforcedDeadline=true、browserTargetQuiescence=QUIESCED 且本地 call timeout/lease 覆盖该 fingerprint 才允许后续 bridge。每个 inference POST 必须携带 `expectedInstanceId/expectedConfigFingerprint/expectedBridgeRole/requestPurpose/submissionKey`。Broker 在接收、排队获得 slot 后和 durable reservation 前重算 purpose/key 并比较所有握手值；此时 mismatch 返回 `INSTANCE_MISMATCH_PRE_SUBMIT/DEFERRED_PRE_SUBMIT`、reservation=0。reservation durable 后、invoke 前若任何握手或 ownership 检查异常，必须转 UNKNOWN、reservation=1、upperBound=1 并进入 teardown/recovery，绝不能回报 PRE_SUBMIT 或 fresh retry。health/ACK/reset 都计入独立 bridgeControlHttpCount，不占 provider inference attempt 上限。模型返回后用 `lease_token + lease_fence + captured state_version` 在单事务提交 evaluation/旧 tests/new claims/head，并递增 stateVersion。进程崩溃由 lease 到期恢复；过期旧请求的 token/fence 无法提交。

路由矩阵必须按获取 lease 前的同一快照执行，不在本轮中重做 health 后改路：

| 模式/快照 | 行为 | 是否取 lease | 失败处理 |
|---|---|---|---|
| BRIDGE_FIRST，Bridge eligible | 只调 Broker | 是 | reservation 前失败回 pending；reservation 后按 UNKNOWN/recovery |
| BRIDGE_FIRST，Bridge ineligible | 不调 API/Broker | 否 | latest-wins 归并 pending |
| API_FIRST，精确 API available，Bridge ineligible | `API_ONLY` | 是 | transport/无可用响应则不接受 observation，回 pending；可解析但 intent 无效可按 INVALID 提交 |
| API_FIRST，API available，Bridge eligible | API，失败后最多一次 Broker | 是 | API 失败后仅使用快照中已核验 Broker；pre-reservation mismatch 回 pending，本轮不重做 health |
| API_FIRST，API unavailable，Bridge eligible | 只调 Broker | 是 | 同 BRIDGE_FIRST eligible |
| API_FIRST，API unavailable，Bridge ineligible | 不调模型 | 否 | latest-wins 归并 pending |

`API_ONLY` 的连接超时、HTTP 无可用 body、截断或顶层无法解析都不消费 tests/不写 evaluation；只有可解析顶层 envelope 已形成本轮决策，但嵌套 intent 校验失败时，才允许提交 INVALID acceptedObservation。

候选进入 lease 前和 commit 时都必须与 durable freshness watermark 再比较。source quality 由程序生成：parseable `quote.timestamp/updateTime` 为 INSTANT；仅 `dailyKline.latest/sourceTradeDate` 为 TRADE_DATE；两者都没有为 NONE。比较表固定如下：

- 两边 sourceDate 已知时先比较日期，较早日期 stale、较晚日期 newer。
- 同日两边都是 INSTANT 时比较完整时间；较早 stale，相同 key duplicate，相同时间不同 facts hash 可按较大 arrivalVersion 作为后到修正。
- 同日 incoming=INSTANT、watermark=TRADE_DATE 时允许按较大 arrivalVersion 前进；incoming=TRADE_DATE、watermark=INSTANT 时因无法证明更新而拒绝 continuity。
- 同日两边都是 TRADE_DATE，或两边都是 NONE 时，只能声明“数据库到达顺序更新”，按 arrivalVersion 前进，不能在分析中声称交易所时间更新。
- watermark 有日期而 incoming=NONE 时拒绝 continuity；watermark=NONE 而 incoming 有日期时允许接管水位。

stale 或无法证明更新的快照只允许完成原规则触发判断，不发 continuity owner intentUpdate、不写 evaluation、不消费 tests。commit 必须重做同一比较，避免 lease 期间发生 takeover 后倒序写入。stale/duplicate pending 用 captured pendingArrivalVersion/key 条件清槽，不调用模型。这样 10:05 INSTANT 已提交后，缓存返回的不同 hash 10:00 快照不能倒序推进 tests；只有日期的同日样本只按到达顺序处理并明确降级证据强度。

已有有效 lease 时，不把该 sampledSnapshot 称为 observation，也不消费 tests；总是增加 `coalesced_count`。先把新样本与在途 lease 候选比较，再与 pending 比较，统一复用上面的 INSTANT/TRADE_DATE/NONE freshness comparator；只有 comparator 判定 newer 才可覆盖，相同精度无法比较时才以较大 arrivalVersion 决胜。若新样本不胜过 lease，纯计数且不建 pending；若胜过 lease 但不胜过已有 pending，仍只计数且不改变 pendingArrivalVersion；只有新样本成为最新候选时才替换槽，并把该样本的 arrivalVersion 写为新的 `pending_arrival_version`。这样“处理 P(v5) 时到达更早样本”不会改变槽的内容版本。

下一次 scheduled/manual run 给实时样本分配 arrivalVersion 后，与 pending 按同一排序及 committed freshness watermark 比较，只租用 winner。acquire 始终捕获当时 pendingArrivalVersion/key：若 pending 是 winner，提交后按捕获值条件清槽；若实时样本明确淘汰旧 pending，提交实时 winner 后也按捕获值条件清槽；K1 执行期间到达的 K3 会写入更大的 pendingArrivalVersion，条件清槽失败并保留 K3。若 pending key 已存在 evaluation，或已被 freshness watermark 判定为 stale，在发模型请求前执行“事实成立 + captured pendingArrivalVersion/key”条件清槽，然后重新选择候选或结束，不产生重复调用。提交不得用旧 head 对象整行覆盖 pending 字段。

pending 只保存规则无关 canonical facts、source metadata 和 capture 时规则 ID 列表，不保存可执行 `rule_snapshot`。提升 pending 时重新读取当前启用规则并解析 sticky owner；原 owner 被禁用/删除时按当前规则重选 owner、开启新 session generation 且 `forceNewSession=true`。历史 evaluation 仍保存真正执行时的不可变 owner rule snapshot。

这样不形成无界队列，也不会在前一请求结束后立即突发补跑，代价是瞬时中间快照可能被合并且 owner 的模型触发判断延后到下一轮。结构化规则仍可在采样当下由 Java 判断；自然语言 owner 不得在没有模型响应时发送通知。

新增表 `stock_watch_intent_evaluation`，一行代表一次 AI 异动判断中的意图更新尝试。状态身份按股票和 observation，不按规则。

| 字段 | 类型 | 含义 |
|---|---|---|
| id | BIGINT PK | 本轮评估 ID |
| symbol / market / stock_name | VARCHAR | 股票标识 |
| rule_id | VARCHAR | 承担本次意图 owner 的 AI 规则 |
| rule_snapshot_json | TEXT | owner 规则不可变快照，避免规则后改导致历史语义漂移 |
| evaluated_at | DATETIME | 本轮评估时间 |
| observation_key | VARCHAR(96) | 上游源时间 + 白名单事实哈希；相同观察不重复推进 |
| source_time / source_date / source_time_quality / arrival_version | DATETIME / DATE / VARCHAR / BIGINT | 本次 acceptedObservation 的 freshness 审计信息；quality=INSTANT/TRADE_DATE/NONE |
| previous_evaluation_id | BIGINT nullable | 上一有效意图状态 ID |
| source_analysis_ids | TEXT JSON | 冷启动或转折摘要引用的技术分析 ID |
| trigger_snapshot_json | MEDIUMTEXT | triggered/confidence/reason/matchedSignals；用于审计，不参与下一轮事实验真 |
| fact_snapshot_json | MEDIUMTEXT | 本轮允许引用的 quote/currentFacts 快照 |
| prior_validation_json | MEDIUMTEXT | 程序对全部 activeTests 和相关预测结果的本轮验收快照 |
| intent_state_json | MEDIUMTEXT | 通过校验的 `intentUpdate v2` |
| status | VARCHAR | SUCCESS / INVALID / ERROR |
| validation_warnings_json | TEXT | 非阻断告警 |
| prompt_hash | VARCHAR | 本轮 prompt 哈希 |
| model / provider | VARCHAR | 最终模型调用身份 |
| session_meta_json | TEXT | owner sessionKey、应用侧 generation/turn/contextChars、forceNew、conversationUrl |
| provider_inference_attempt_count | INT | API/bridge provider inference 请求总数，当前实现最大 3；不含 health/ACK/reset |
| attempt_trace_json | TEXT | 每次尝试的 ordinal/provider/outcome/reason/forceNew/sessionKey/conversationUrl |
| bridge_http_attempt_count / bridge_control_http_count | INT | Bridge inference HTTP 与 health/ACK/reset 控制面 HTTP 分开计数 |
| submit_reservation_count / confirmed_submit_invocation_count / possible_web_submit_upper_bound | INT | durable 提交预留数、浏览器 click/enter 调用已确认返回数、真实网页提交可能上界；UNKNOWN 时不得把上界冒充真实次数 |
| review_delivery_high_water | BIGINT nullable | 本次 prompt 已携带的终态 test 最大 resolvedEvaluationId；仅 SUCCESS 后推进 head 游标 |
| raw_response | MEDIUMTEXT | 模型原始响应 |
| error_message | TEXT | 失败原因 |
| gmt_create | DATETIME | 创建时间 |

索引：

- `(symbol, market, status, evaluated_at, id)`：取上一有效状态；
- `(previous_evaluation_id)`：追踪状态链；
- `(rule_id, evaluated_at)`：规则维度审计。
- 唯一索引 `(symbol, market, observation_key)`：同一 observation 只允许一个 owner 评估记录，不区分 SUCCESS/INVALID，避免失败后被其它规则重写。

新增程序拥有的测试账本 `stock_watch_intent_test`。模型只提出 `newClaims`，程序分配稳定 claim/test ID 并维护生命周期。

| 字段 | 类型 | 含义 |
|---|---|---|
| id | BIGINT PK | 稳定 test ID |
| origin_evaluation_id | BIGINT | 创建该测试的成功状态 |
| resolved_evaluation_id | BIGINT nullable | 进入终态时对应的 observation evaluation，可为 SUCCESS/INVALID/ERROR |
| symbol / market | VARCHAR | 股票键 |
| claim_ref | VARCHAR | 稳定格式 `{originEvaluationId}:{claimKey}` |
| claim_text | VARCHAR | 预先登记的可观测命题原文 |
| target_axis / target_value | VARCHAR | 该 claim 关联的候选轴和值；只用于 belief 解释 |
| test_key | VARCHAR | 模型在本轮内给出的短键；与 origin 组合唯一 |
| expectation_type | VARCHAR | MATCH_BEFORE_EXPIRY / NO_MATCH_BEFORE_EXPIRY；语义由程序固定 |
| metric_path / operator | VARCHAR | 白名单数值路径与运算符 |
| threshold / lower_bound / upper_bound | DECIMAL(24,8) | 比较参数，拒绝超出范围或非有限数值 |
| max_age_observations / age_count / observed_count | INT | 截止年龄、不同 observation 年龄和字段实际可见次数 |
| last_observation_key | VARCHAR | 防止同一快照重复消费 |
| status | VARCHAR | PENDING / SUPPORTED / FALSIFIED / INSUFFICIENT_DATA / INVALID |
| result_json | TEXT | 命中事实、终态与程序派生 review；模型不能改写 |
| version | INT | 乐观锁版本，跨进程并发提交保护 |
| created_at / resolved_at | DATETIME | 生命周期 |

所有 `PENDING` 测试在后续每个新 observation 都被读取；它们不会因为模型生成了下一状态而丢失。每个不同 observation 都增加 `age_count`；字段存在时才增加 `observed_count`。达到 `max_age_observations` 时，若可见次数不足则进入 `INSUFFICIENT_DATA` 并释放容量，绝不永久占用；重复 observation 不增加任何计数。

不复用 `lastAiAlerts`：它只代表“上次成功发信状态”，分析未发信也必须进入连续链。

## 6. `intentUpdate v2` 输出协议

现有顶层 `matchedSignals` 为展示字符串，不能再作为真实性证明。触发审计分为两种不可混淆的来源：

1. `STRUCTURED_RULE`：`AiWatchRule.structuredPredicate` 必须是显式、完整且通过 schema 校验的 JSON AST；Java evaluator 只执行该 AST 并生成 `{factPath, operator, expected, actual, matched}`。`conditionText` 不参与 AST 生成或补全。structuredPredicate 缺失/非法时整条规则为 `MODEL_INTERPRETATION`，不得保留任何 deterministic 部分。模型不能提供或覆盖 operator/expected；最终 structured triggered 由 Java AST 决定。
2. `MODEL_INTERPRETATION`：无法编译成程序 predicate 的自然语言规则仍由模型判断。模型最多提交 5 个 `triggerFactPaths`，Java 只校验路径属于本轮 canonical facts 并重取 actual，形成 `{factPath, actual}` 审计快照。这里的 `triggered` 是模型解释，不宣称 Java 已证明阈值或语义；若没有至少一个合法当前事实路径则强制 false。

顶层写入 `triggerSource`。`reason/matchedSignals` 只用于展示。模型不再提交 operator/expected，因而不存在由模型降低阈值后伪造“程序已证明”的路径。

AST v1 是 sealed 判别联合，任何未知字段、缺字段或 `null` children 都拒绝：

- `ANY/ALL` 节点只允许 `{type, children}`，children 数量 `1..16`；禁止空集合的 vacuous truth。
- `NOT` 节点只允许 `{type, child}`，且必须恰好一个 child。
- `PREDICATE` 节点只允许 `{type, factPath, operator, threshold, lower, upper}`；operator 使用与 newClaims 相同的 `GT/GTE/LT/LTE/BETWEEN/ABS_GTE` 判别联合，factPath 必须是当前数值事实白名单。`ABS_GTE` 取 actual 绝对值后比较，threshold 必须非负。
- 整棵树深度 `<=5`、节点数 `<=32`；schema 拒绝未知 type/operator，以及 AST 中非有限或不能无损写入 DECIMAL(24,8) 的 threshold/lower/upper 字面量。本轮 actual 缺失、非数值或不能安全转为 BigDecimal 时 evaluator 返回 UNKNOWN，并记录缺失/类型原因，不把数据质量问题伪装成非法规则。

evaluator 使用 `TRUE/FALSE/UNKNOWN` 三值逻辑：缺失或非数值 predicate 为 UNKNOWN；`NOT UNKNOWN=UNKNOWN`；ALL 遇 FALSE 为 FALSE，否则有 UNKNOWN 为 UNKNOWN，否则 TRUE；ANY 遇 TRUE 为 TRUE，否则有 UNKNOWN 为 UNKNOWN，否则 FALSE。只有根 TRUE 才能触发；根 UNKNOWN 强制 `triggered=false` 并把缺失路径写入审计，绝不能把 `NOT(missing)` 反转为 TRUE。创建/更新规则时 structuredPredicate 非法直接拒绝请求；加载旧文件遇到非法字段时记录告警并按 MODEL_INTERPRETATION，不修复猜测。现有 `judgeStructuredQuoteRule` 的“关键词命中 + 硬编码阈值”删除，不能作为新 deterministic 路径。

当前 20 条存量规则可做一次确定性迁移，但只识别两个完整模板：先把开头股票名规范化为 `{stock}`，完整文本 SHA-256 必须等于 `12e2dee1a77113f1c474de4a06fd92142b4d7c360a89d89ec791b3cbb1265a17` 或 `b7aabd23223b049cc69235a9f8d7e0e873d3b49f5a3278a77ef8ce5dc03102ed`，才写入固定 `STOCK_ANOMALY_V1` AST；任何字符变化、未知模板或已有 structuredPredicate 均不自动覆盖。迁移记录 presetVersion/sourceTemplateHash，规则保存后持久化显式 AST。

编辑生命周期必须 fail-safe：请求改变 `conditionText` 且未同时显式提交 `structuredPredicate` 时，原子清除已有 AST/presetVersion/sourceTemplateHash 并降为 MODEL_INTERPRETATION；请求显式提交 `structuredPredicate=null` 也表示主动降级；只有同时提交并通过完整校验的新 AST 才继续 STRUCTURED_RULE。若 conditionText 未变化，普通 patch 可保留原 AST。这样旧前端无需理解 AST 也不会出现“文字已改、隐藏旧阈值仍触发”。迁移生成的 AST 还会在加载时重算 sourceTemplateHash；文本不再匹配即清除并告警。

`STOCK_ANOMALY_V1` AST 为根 ANY：`ABS_GTE(changePercent,5)`；`GTE(changePercent,9.5)`；`LTE(changePercent,-9.5)`；`ALL(GTE(volumeRatio,2),ABS_GTE(changePercent,2))`；`ABS_GTE(mainInflow,100000000)`；`ALL(GTE(turnover,5),ABS_GTE(changePercent,3))`。这保持当前模板的既有阈值，但证据来源变成显式规则数据，而不是自然语言猜测。

模型不输出 `previousReview/observedBehavior/stateTransition/invalidatedClaims`。Java 已拥有 test ledger 和本轮事实，负责生成这些确定性派生结果，并把本轮 `priorValidation.generatedReview` 提供给模型写入自然语言 `analysis`。模型只输出候选 belief、解释、新的可观测 claims 和不确定性：

```json
{
  "schemaVersion": 2,
  "positioningCandidates": [
    {
      "intent": "ACCUMULATE|HOLD|DISTRIBUTE|UNKNOWN",
      "beliefWeight": 0.45,
      "evidencePaths": ["quote.mainInflow"],
      "counterEvidencePaths": ["quote.volumeRatio"],
      "reason": "string"
    }
  ],
  "executionTactics": [
    {
      "tactic": "PULL_UP|WASH|SUPPORT|SUPPRESS|ROTATE|NONE|UNKNOWN",
      "beliefWeight": 0.55,
      "evidencePaths": ["quote.changePercent"],
      "counterEvidencePaths": [],
      "reason": "string"
    }
  ],
  "newClaims": [
    {
      "claimKey": "flow_follow_through",
      "expectationType": "MATCH_BEFORE_EXPIRY|NO_MATCH_BEFORE_EXPIRY",
      "targetAxis": "POSITIONING",
      "targetValue": "ACCUMULATE",
      "metricPath": "quote.mainInflow",
      "operator": "GTE",
      "threshold": 100000000,
      "lower": null,
      "upper": null,
      "maxAgeObservations": 2
    }
  ],
  "uncertainty": ["聚合资金流无法区分资金主体"]
}
```

程序从白名单事实生成 `observedBehavior`，从上一状态与本轮最高 belief 派生 `stateTransition`，从 test ledger 派生 `generatedReview` 和被证伪的**可观测 claim**；不把“资金真实心理”标为 FALSIFIED。新状态不写入 `stock_watch_technical_analysis.main_force_intent_json`，因此不会进入旧前端/API 路径；本次不修改技术分析 v1 存量协议。若未来需要暴露兼容对象，必须完整投影 `intent/timeWindow/confidence/risk/watchPoints/evidence`，不能只投影一个 intent 字符串。

### 6.1 校验规则

1. `schemaVersion=2`。
2. `positioningCandidates` 为 2..3 项；`intent` 只允许 `ACCUMULATE/HOLD/DISTRIBUTE/UNKNOWN` 且去重；`beliefWeight` 接受数值/字符串后转 BigDecimal，范围 `[0,1]`、scale<=4，总和在 `[0.98,1.02]`；compact DTO 固定输出 4 位 canonical decimal string。该值表示模型当前信念权重，不宣称统计后验概率。
3. `executionTactics` 为 1..2 项，`tactic` 只允许 `PULL_UP/WASH/SUPPORT/SUPPRESS/ROTATE/NONE/UNKNOWN` 且必须去重；具体 tactic 可以并存，不要求权重和为 1；`NONE/UNKNOWN` 不得与任何具体 tactic 同时作为高权重候选；每项权重在 `[0,1]`、scale<=4，compact DTO 固定 4 位 decimal string。
4. 所有 `evidencePaths/counterEvidencePaths` 必须存在于本轮可重取的当前事实路径集合：`quote.*`、`aiContext.currentFacts.*`，每条最长 80 字符。每个 candidate/tactic 内部先规范化去重，再要求 evidencePaths 与 counterEvidencePaths 交集为空；同一路径不得同时充当正反证。后续 compact DTO 没有 `priorValidation.*` 命名空间，模型不得伪造该引用；历史记录只能帮助解释候选，不得成为程序 review 或候选 evidencePath。
5. `newClaims` 是两层 sealed 判别联合。target 联合只允许 `POSITIONING -> ACCUMULATE/HOLD/DISTRIBUTE/UNKNOWN` 或 `TACTIC -> PULL_UP/WASH/SUPPORT/SUPPRESS/ROTATE/NONE/UNKNOWN`，拒绝其它轴值组合；operator 联合中 `BETWEEN` 必须 `threshold=null`、lower/upper 非空且 `lower<=upper`，`GT/GTE/LT/LTE` 必须 threshold 非空且 lower/upper 均为 null，`ABS_GTE` 也必须 threshold 非空、非负且 bounds 为 null。所有数值接受数值/字符串后转 BigDecimal，必须能无损写入 DECIMAL(24,8)。metricPath 只允许数值型白名单路径且最长 80 字符；`maxAgeObservations` 范围 `1..8`；每轮最多 2 项；`claimKey` 必须匹配 `^[a-z0-9_]{1,40}$`。模型不得输出 claimText；程序根据判别联合和期限用固定模板生成 canonical claimText，再分配 `claimRef={originEvaluationId}:{claimKey}` 与 testId。`generatedReview` 和 FALSIFIED 展示只能使用 Java 生成文本。协议 cardinality、enum、DTO validator、prompt 和 budget fixture 必须引用同一组 Java 常量，禁止各写一份数字。
6. `MATCH_BEFORE_EXPIRY`：期限内命中为 SUPPORTED；期限结束且所有所需 observation 数据均可见仍未命中，才 FALSIFIED；存在缺口则 INSUFFICIENT_DATA。`NO_MATCH_BEFORE_EXPIRY`：期限内命中为 FALSIFIED；完整期限未命中为 SUPPORTED。该 verdict 只针对 claimText 中预先登记的可观测命题，对关联 intent candidate 最多作为 belief 调整证据。
7. 校验失败：本轮 observation 记录 `INVALID`，不成为下一轮有效 intent state，也不创建 newClaims；若 canonical fact snapshot 有效，旧 tests 仍按本 observation 推进。触发判断和通知门控按顶层解析结果或既有 deterministic fallback 处理。其它规则不得接替 owner。
8. 所有模型文本先 NFC 规范化并拒绝 Unicode `\p{C}` 控制/格式字符、`\p{Zl}` 或 `\p{Zp}`。所有“字符长度”统一指 Java UTF-16 code units（`String.length()`），禁止混用 Unicode code point；只有会进入后续 compact continuity 的 candidate/tactic reason 与 uncertainty 额外匹配 COMPACT_SAFE_TEXT，禁止 ASCII 双引号和反斜杠，避免合法 fixture 出现不可界定的转义膨胀；顶层 reason/analysis/matchedSignals/missingData 允许正常引号和反斜杠，由序列化后总长度门控制，不因普通标点把整轮 intent 判 INVALID。完整顶层 envelope 的唯一硬上限：reason<=160、analysis<=450、matchedSignals<=5 项且每项<=100、missingData<=8 项且每项<=80、triggerFactPaths<=5 项且每项<=120；intent 内 candidate 2..3、tactic 1..2、newClaims 0..2、uncertainty 0..3；candidate/tactic 每项 evidence/counter paths 各<=2；candidate/tactic reason<=80、claimKey<=40、uncertainty<=80。接受后的完整 DTO 还执行 Fastjson 实际序列化后 `String.length()<=6000`。

### 6.2 输出预算与模型身份

Continuity owner 不复用持久化规则里的 `rule.model`，而使用专用配置 `stock-watch.ai-rules.continuity.api-model-selector=deepseek-v4-flash`。这样现有规则的显式 `deepseek-chat` 不需要被静默迁移，非 owner 触发判断仍保持原配置；owner 的同一次“触发 + intentUpdate”调用走精确 continuity 路由。API 路径固定明确模型 ID `deepseek-v4-flash`，不再依赖将于 2026-07-24 弃用的 `deepseek-chat` 别名。

`ModelRegistryStore.init` 在加载磁盘 registry 后执行幂等 `ensureBuiltinDeepSeekV4Flash`：仅当同 modelId 不存在时，用现有 DeepSeek API URL/key 配置新增 `deepseek-v4-flash`，不覆盖用户已有模型。`AiModelRouter.chatExactWithTrace(selector, ...)` 只允许 selector 精确匹配且 enabled 的 `GenericAiClient`，禁止现有“找不到则 first client” fallback。只有 selector 在发请求前不存在/禁用才返回 `httpAttempted=false/routeStatus=unavailable`；一旦创建并执行 OkHttp Call，超时、HTTP 非 2xx、解析失败、response model mismatch、usage 缺失或 finishReason 非 stop 均返回 `httpAttempted=true/outcome=rejected|failed`，API providerInferenceAttemptCount 必须 +1。后续是否转 Broker 只按第 5 节路由快照矩阵决定：Bridge ineligible 的 `API_ONLY` 失败不会临时转用其它 bridge。若 bridge-first=true，则不检查/调用 API；若 bridge-first=false 且 API unavailable，attempt trace 先记录零物理请求的 unavailable，只有快照中 Broker eligible 才转入 Broker，不能冒充 V4 API 成功。

官方当前 token 文档链接的离线包名为 `deepseek_tokenizer.zip`，其 tokenizer 说明仍不能作为 V4 Flash 的精确 token 证明。DeepSeek 官方也明确实际 token 数以响应 `usage` 为准。因此发布门采用可复现且不夸大的分路约束：

- API：请求显式设置 `thinking.type=disabled`、`response_format.type=json_object`、`maxTokens=4096`，并在 prompt 中再次要求单一 JSON。`chatExactWithTrace` 记录响应 `model/system_fingerprint/usage.completion_tokens/finish_reason`；只有精确 model、usage 存在、`finish_reason=stop` 且完整顶层 JSON 通过 schema 时成功。`length/content_filter/tool_calls/insufficient_system_resource` 全部记 ERROR，不修复重试。4096 是防截断余量，不代表模型应生成长文；DTO 仍受第 6.1 节字段和 6,000 字符上限约束。
- Bridge：网页不提供可信 model ID/token usage，不能声称通过离线 tokenizer 精确计数；每次运行都要求 bridge 响应 `jsonComplete=true`、`contentStatus=success`，再解析完整顶层 JSON 和 schema。记录 bridge conversation/attempt/response chars/completionReason/jsonComplete；发布前真实 bridge 冒烟是额外证据，不代替运行时门。
- Broker submission 协议：每个请求使用第 5 节按 `requestPurpose` 定义的跨 lease/takeover 稳定 `submissionKey`；所有 fresh-session HTTP 重试与同业务输入的 takeover 都复用同一 key。Broker journal 状态只允许 `QUEUED/SUBMITTING/SUBMITTED/COMPLETED/FAILED_PRE_SUBMIT/UNKNOWN/ACKED`；同 key active/UNKNOWN 时返回 `in_progress|unknown` 而不再次 submit，COMPLETED 返回缓存结果，只有明确 FAILED_PRE_SUBMIT 才允许 `forceNewSession` 重新入队。三种 purpose 共用一个全局 maxInFlight/cooldown/circuit，不允许按 purpose 创建并行通道。
- `deepseek-web-client.js` 把当前单个 DOM eval 拆成 `prepareAndValidateComposer` 与 `invokePreparedSubmit`。prepare 只填充输入、检查按钮/Enter 路径、返回待提交内容指纹与 target/browser ownership snapshot，不触发 click/enter；随后调用 Bridge 注入的 `onSubmitStarting`。Bridge 先把 `SUBMITTING + submitReservationCount=1 + possibleWebSubmitUpperBound=1 + targetId/browserOwnershipSnapshot/reservedAt` 持久化，成功后 client 再检查 abort、ownership、instance/fingerprint、内容指纹和按钮状态，才执行 click/enter。marker 后任一检查异常都转 UNKNOWN/recovery；调用明确返回后写 `SUBMITTED + confirmedSubmitInvocationCount=1`。marker 前失败为 FAILED_PRE_SUBMIT；marker 后、click 返回前崩溃时真实提交次数不可知，只能记录 UNKNOWN、confirmed=0、upperBound=1；click 返回后状态落盘前崩溃同样恢复为 UNKNOWN。submissionKey/state/counters 与 COMPLETED 的 answer/验收元数据持久化，不写 prompt、Cookie 或凭据；Bridge 重启后 SUBMITTING/SUBMITTED 恢复为 UNKNOWN，COMPLETED 返回缓存。
- Journal 不是“每个 handler 自己读改写文件”。reservation、SUBMITTED、COMPLETED、ACK、circuit、cleanup、reset 和 recovery 的**检查+状态变更+持久化**全部进入同一个 `mutateJournal` 单写者队列/mutex，并在锁内基于最新 committed state 计算；每次 mutation 单调递增 `journalRevision`，按 revision 顺序执行“同目录临时文件写入并 fsync -> rename -> 父目录 fsync”，成功后才发布新的内存快照。pre-click 必须 await 包含本 reservation 的 revision 完整落盘；任何检查或持久化失败立即 fail-closed，不允许 invoke。health 只读最后 committed immutable snapshot。这样 ACK/circuit/cleanup 不能用旧快照覆盖新 reservation。
- 整条 post-acquire 链使用第 5 节统一 deadline/AbortSignal。signal 必须进入 cooldown sleep、shared target wait、client open/CDP send、pre-submit sleep、prepare、marker 后二次 abort 检查和 answer wait；超时后 Bridge 主动关闭 CDP client并等待 Node worker settled，不能靠 `Promise.race` 返回后直接释放 slot。marker 前 settled timeout 为 FAILED_PRE_SUBMIT；marker 后为 UNKNOWN，并按第 5 节验证 target/browser teardown。若到绝对 hard deadline 浏览器侧仍 UNCONFIRMED，则 circuit 立即 OPEN_TRANSIENT_RECOVERY 并保持全局 fail-closed，不等待第二个 key；只有确认 target teardown 的普通 provider timeout 才使用“连续两个不同 key”阈值。
- acceptedObservation 的 evaluation/ledger/head 事务提交后，Java 对 Bridge 发送带 `disposition=ACCEPTED` 的幂等 ACK；Bridge 才把该 key 标为 ACKED 并可删除 answer。若 durable freshness watermark 已明确证明 COMPLETED observation stale，Java 可在不获取 lease、不消费 tests 后发送 `disposition=STALE_DISCARDED` 的幂等 ACK；后续同快照仍被 DB watermark 拒绝。ACKED/FAILED_PRE_SUBMIT 可按 24 小时清理。DB commit 失败或 ACK 丢失时，COMPLETED/UNKNOWN tombstone 不过期、不自动淘汰；下次同 key 只读缓存或 fail-closed。journal 最多 5,000 个未 ACK 安全状态，容量满时返回 `DEFERRED_PRE_SUBMIT`，绝不淘汰未 ACK/UNKNOWN/SUBMITTED。重复 evaluation 路径会补发 ACK。该边界保证“journal 文件与数据库未被人工删除/回滚时，同 observation 至多一次提交预留和一次不可逆浏览器调用”；真实网页提交在崩溃窗口内允许 UNKNOWN，不宣称不可观测的精确次数。
- HTTP 断开时，尚未 onSubmitStarting 的排队/准备任务 abort 并记 FAILED_PRE_SUBMIT；marker 后只能 UNKNOWN。Bridge 响应/错误固定返回 `submissionState, failurePhase, submitReservationCount, confirmedSubmitInvocationCount, possibleWebSubmitUpperBound, queueWaitMs, cooldownMs, requestId`。Java 仅在 `submissionState=FAILED_PRE_SUBMIT && failurePhase=PRE_SUBMIT` 时允许一次 fresh-session HTTP 重试；timeout、连接断开、UNKNOWN、QUEUED、SUBMITTING、SUBMITTED、DEFERRED_PRE_SUBMIT 都禁止重试。trace 分开记录 bridgeInferenceHttpCount、bridgeControlHttpCount、submitReservationCount、confirmedSubmitInvocationCount 与 possibleWebSubmitUpperBound。
- 账号级 circuit breaker 持久化在同一 journal，状态为 `CLOSED/OPEN_AUTH/OPEN_TRANSIENT/OPEN_TRANSIENT_RECOVERY/HALF_OPEN`。login_required、账号禁用/风险验证页直接进入 OPEN_AUTH，只能本地受保护管理接口人工复位；连续 2 个不同 submissionKey 的**已确认 target teardown** post-marker UNKNOWN/provider timeout 进入 OPEN_TRANSIENT；browserTargetQuiescence 未确认时第 1 个即进入 OPEN_TRANSIENT_RECOVERY。恢复流程确认旧 target/browser identity 消失后仍等待默认 15 分钟安全窗口，再 HALF_OPEN 且全局只允许 1 个探测提交。探测成功关闭 circuit，失败重新打开。circuit 打开、browser target 未确认停止、instance/fingerprint mismatch 或 journal 容量满时 Broker 在任何 submit marker 前返回 `DEFERRED_PRE_SUBMIT + retryAfter`，submitReservationCount 不增加。Java 不写 evaluation、不消费 tests，使用 token/fence 短事务把 lease winner 保留/归并回 pending 后释放 lease；Bridge-first 在 health 已显示 open 时先通过 head 行锁把当前 sample 与已有 pending 做 latest-wins 合并，再跳过 acquire；API-first 只按第 5 节快照矩阵执行 API_ONLY 或归并 pending，不在本轮重新健康检查。结构化 trigger 可按既有 deterministic fallback，MODEL_INTERPRETATION 不通知。
- 官方 tokenizer 包只作为开发期估算工具并记录 artifact hash，不作为 V4 精确正确性证明；未来官方提供与目标模型绑定的 tokenizer 后再增加硬 token fixture 门。

官方依据：`https://api-docs.deepseek.com/quick_start/token_usage/`、`https://api-docs.deepseek.com/quick_start/pricing`、`https://api-docs.deepseek.com/guides/thinking_mode/`、`https://api-docs.deepseek.com/api/create-chat-completion/`。

## 7. 连续验证算法

### 7.1 冷启动

无上一有效意图评估时：

1. 新增与现有日期范围查询同过滤条件、但不做每日 latest 聚合和 SQL LIMIT 的 `selectAllSuccessfulDailyByDateRange`，读取最近 14 个自然日内**完整**成功 `DAY` 技术分析集合；查询结果按 `analysis_date/run_slot/gmt_create/id` 时间升序归一化。
2. 在完整 14 天集合上先标记首条、末条、主力意图变化点、置信度显著变化点、证伪/风险文字记录，再用剩余额度补最新普通记录，最终限制 48 条和 16,000 字符。这样不会因每天多个槽位而把窗口意外缩成约 5 个交易日。
3. 把记录的 `title/conclusion/evidence/mainForceIntent` 提供给模型，明确它们是“待验证历史观点”，不是事实。

### 7.2 Observation owner 与幂等

每轮 `runOnce` 先为每只股票构建 sampledSnapshot、observationKey、规则无关 canonical context 和 sticky owner。先从 pending 单槽与当前实时样本中选择更新候选；取得 lease 后只成为 leasedAttempt，owner 模型与最终事务成功后才成为 acceptedObservation。owner 在该股票规则组内优先执行；同股票其它规则仍完成各自触发判断，但 `includeIntentUpdate=false`。owner 输出无效时可作为 INVALID acceptedObservation 提交并推进旧 tests，后续规则不得接替，避免内容随规则顺序变化。

手工重跑或同一行情重复抓取时，若 observationKey 已存在任何评估记录，则不创建新状态、不消费 activeTests，并在运行响应标记 `intentUpdateSkippedReason=duplicateObservation`。lease 忙时标记 `intentUpdateSkippedReason=coalescedBackpressure` 并更新 pending 单槽，不把它计入 evaluation。服务方法同步只防单 JVM；股票 head 的 arrivalVersion/stateVersion/leaseFence/pendingArrivalVersion、evaluation 唯一键和 test version 共同负责跨进程线性保护。

### 7.3 后续更新与 activeTests 生命周期

有上一有效状态时：

1. 查询该股票全部 `PENDING` 测试，不限于上一 evaluation 创建的测试；最多 6 个 active tests，达到上限时拒绝本轮新增并记录告警，绝不静默删除旧测试。同时读取 `resolved_evaluation_id > head.last_delivered_resolution_evaluation_id` 的全部未交付终态，形成 terminal review backlog，并捕获本次 `reviewDeliveryHighWater=max(resolved_evaluation_id)`。
2. 对每个测试检查 `lastObservationKey`。相同 observation 不执行、不增加 observedCount。
3. 从本轮 `quote/currentFacts` 按路径取得数值。每个不同 observation 增加 ageCount；字段存在才增加 observedCount。终态判定顺序固定：先判断本轮是否出现决定性 match，出现则立即按 expectationType 进入 SUPPORTED/FALSIFIED；未出现决定性 match 且尚未到期则保持 PENDING；到期后再判断 observedCount 是否覆盖全部 ageCount，覆盖不完整则 INSUFFICIENT_DATA，覆盖完整才按 expectationType 的“到期未 match”语义进入 FALSIFIED/SUPPORTED。
4. Java 生成 `priorValidation.generatedReview`，持久层保留 claimRef/testId/canonicalClaimText/verdict/完整 actual evidence。传给模型的 compact `tests` 合并本轮事实验收后的 PENDING/terminal draft 与所有未交付 terminal backlog，每项保留 target/expectation/metric/operator/参数/currentVerdict/currentActual/age/observed/matched，使 fresh session 不依赖旧会话也能理解“哪条命题被支持或证伪”；不发送 claimText、resultJson 或历史 actual 数组。模型不得生成或覆盖 review。用于 belief 的唯一证据集合 `beliefEvidenceBatch` 与 prompt 展示集合保持一致：`captured 未交付 terminal backlog ∪ 本轮已展示 terminal draft`，先按 testId 去重，再按 target 聚合。只有本轮 intent SUCCESS 的提交事务才推进 `last_delivered_resolution_evaluation_id`：已有 backlog 推进到 prepare 捕获的 high water，本轮 draft 中已在 prompt 展示且成功写终态的 tests 推进到 currentEvaluationId；INVALID/ERROR/事务回滚均不推进，因此终态会在下一次尝试继续重放并在下一次 SUCCESS 恰好生效一次。新 claims 在上述交付之后创建，不会与已交付 terminal 叠加占用下一轮 compact tests 容量。
5. 合并上一状态关联技术分析的已评估预测，但只输出 `predictionId/analysisId/forecast claim/actual outcome`。预测未命中只能削弱对应预测，不直接改变意图测试终态。
6. 将程序验收结果传给模型；模型负责调整候选 belief、解释历史观点，并提出最多 2 个新 claims，不得重写 activeTests。
7. 获取股票级 lease 后，`prepareObservation` 只读并生成不可变 validation draft，不在网络调用期间持有 DB 事务。owner 返回后，`commitObservation` 在一个 `@Transactional` 中校验 lease token/fence/captured stateVersion、durable freshness watermark，插入唯一 evaluation，并对所有事实快照有效的 SUCCESS/INVALID/ERROR acceptedObservation 以 test version/CAS 应用旧测试 draft、写入终态；只有 intent SUCCESS 才更新 `latest_success_evaluation_id`、推进 captured review delivery high water、派生新状态并插入 newClaims。任何一步失败整体回滚，该 leasedAttempt 不成为 observation、不消费 tests。事务成功后更新 `last_observation_key/lastAcceptedSource*/lastAcceptedArrivalVersion/state_version` 并清除 lease；pending 只按 acquire 时捕获的 pendingArrivalVersion+key 条件清槽，禁止覆盖并发新 pending。
8. 新 claims 在本 acceptedObservation 提交后创建，不能用创建它的同一 observation 验收；第一个 eligible observation 必须是不同 observationKey。coalescedSnapshot 不是 eligible observation。

### 7.4 belief 与防锚定

- `INSUFFICIENT_DATA` 与 `FORECAST_MISSED` 不解除 belief 增幅门；没有与目标轴和值直接相关的新 `SUPPORTED/FALSIFIED` 时，最高候选 beliefWeight 不得比上一轮增加超过 0.05。
- Java 先对 `beliefEvidenceBatch` 按 `(targetAxis,targetValue)` 聚合 terminal verdict：仅有 SUPPORTED 为 `SUPPORTED_ONLY`，仅有 FALSIFIED 为 `FALSIFIED_ONLY`，两者同时存在为 `CONFLICTED`，没有方向终态为 `NONE`；其它终态不进入方向聚合。
- 增幅门按 candidate 独立计算，禁止用一个 target 的 blanket 规则覆盖另一 target 的直接证据：`canExceedCap(t) = aggregate(t)==SUPPORTED_ONLY || (aggregate(t)==NONE && sameAxisExists(FALSIFIED_ONLY))`。`FALSIFIED_ONLY` 或 `CONFLICTED` 的 target 自身不得高于上一状态；`CONFLICTED` 必须新增 uncertainty，但不会压制同轴另一 target 自身的 SUPPORTED_ONLY。`NONE` 仅在同轴其它 target 被 FALSIFIED_ONLY 时可因重新归一化超过 +0.05。跨轴 verdict 永远不能解锁另一轴。
- 上一状态不存在的候选基线按 0 计算，并套用同一 `canExceedCap` 公式；不再另写例外规则。
- Java 以集中、版本化的 path->family 表把事实映射为 `PRICE_TREND/VOLUME_LIQUIDITY/FUND_FLOW/RELATIVE_MARKET/EVENT/VALUATION` 去相关维度；MA/RSI/MACD 等同源派生指标同属 PRICE_TREND，不能伪装成三份支持。对 target `t`：先取 `families(candidate[t].evidencePaths)`；仅当 `aggregate(t)==SUPPORTED_ONLY` 时，再合并 beliefEvidenceBatch 中 `target=t && verdict=SUPPORTED` 的 test.metricPath family，否则不从 tests 增加 family。candidate.counterEvidencePaths、FALSIFIED_ONLY、CONFLICTED 以及同轴其它 target 的 FALSIFIED_ONLY 都不计正向 supporting family。无法映射的路径 fail-closed；测试 verdict 直接回溯其 metricPath，不存在独立 PRIOR_VALIDATION 族。最高候选 beliefWeight 超过 0.80 时 supportingFamilies 至少 2 个，否则 schema 校验失败。policyVersion 写入 priorValidation/validation snapshot，历史评估可审计。
- `UNKNOWN` 必须始终是允许候选；数据覆盖不足时其概率不得低于 0.25。
- 模型必须解释最高候选为何优于第二候选。
- 历史技术分析按时间升序输入，程序验收在历史文字之后单独分区，避免把旧结论伪装成本轮事实。

上述约束由 Java 校验器执行；违反时整份 `intentUpdate` 标记无效，不在程序里偷偷修正模型权重。历史技术分析只能帮助模型提出候选和待验证 claim，不能作为本轮 verdict 的证据。

## 8. 请求量与上下文优化

1. 不新增意图专用模型调用：owner 的 `intentUpdate` 加入现有 `StockWatchAiJudgeService` JSON 响应；同股票其它规则不输出 intentUpdate。
2. 有上一状态后，不再每轮重复发送两周全文，只发送上一状态、验收结果和少量转折记录。
3. 冷启动历史最多 48 条、默认 16,000 字符；后续连续上下文硬上限 8,000 字符。
4. 不做第二次结构修复请求。结构失败时记录 `INVALID` 并继承上一状态。默认 API 路径最多为 `API HTTP(1) + bridge inference HTTP(1) + 明确 PRE_SUBMIT fresh-session bridge inference HTTP retry(1) = 3` 次 provider inference HTTP；bridge-first 路径最多 2 次。health/ACK/reset 是独立 control HTTP，不混入推理次数。在 journal/DB 正常边界内，稳定 submissionKey + pre-click durable reservation + ACK 保证同 observation 最多一个提交预留和一次不可逆浏览器调用；崩溃窗口只给 actual submit 上界，不伪造精确次数。记录 total/api/bridge inference/control HTTP、submit reservation、confirmed invocation、possible upper bound 和逐次状态 trace。任何 timeout/UNKNOWN/已提交/DEFERRED 状态都不重试。
5. API 路径 `maxTokens` 默认 4,096；模型数组和文本采用第 6 节唯一硬上限。API 记录真实 usage/finishReason，bridge 用真实可解析响应验收；两条路径遇到截断 JSON 均不得触发 schema 修复请求。
6. 顶层 JSON 不可解析时不能声称保留模型 trigger：若程序结构化规则已经命中，沿用现有 deterministic fallback；否则本轮 AI 决策失败且不通知。只有顶层 JSON 可解析、嵌套 intentUpdate 无效时，才能保留模型 trigger 并把连续状态记为 INVALID。
7. 日志记录 observationKey、owner rule、context/response 字符数、历史条数、active test 数、上一状态 ID、验收数、schema 状态、provider/session、物理尝试数和耗时，不记录邮箱、Cookie 或完整敏感快照。
8. 后续轮次只发送 compact continuity DTO：`previousState` 和最多 6 个 `tests`；`tests` 同时承载 PENDING 与尚未交付的 terminal verdict，避免 activeTests/reviewDelta 重复和 fresh session 语义丢失。不发送 test `result_json`、canonicalClaimText、历史 actual 数组或重复 generatedReview。tests 不允许裁剪；容量已满时拒绝 newClaims。
9. 已按第 8.1 节精确 schema、COMPACT_SAFE_TEXT、canonical decimal string、tactic 去重、正反证不相交和封闭 enum 构造可复现的 6-test 最坏 fixture：`tests=3,061`、`previousState=2,880`，完整 JSON 共 `5,968` Java UTF-16 units / `7,248` UTF-8 bytes，SHA-256=`06eb0905a744e60f95301c6997782c84bde2b8957b3b9171f9dedacaeb84121c`，小于 8,000 units 硬上限。fixture 使用 4 条不同的 80-unit 路径和 6 个不同的 60-unit claimRef；其 test 是可达语义：`NO_MATCH_BEFORE_EXPIRY + BETWEEN[min,min] + currentActual=max + age=8 + observed=7 + matched=false` 会因覆盖不完整得到 `INSUFFICIENT_DATA`。`POSITIONING` 是最长合法 targetAxis，`ACCUMULATE/DISTRIBUTE` 是最长合法 targetValue，最长两个不同 tactic 为 `SUPPRESS/PULL_UP`。生产 compact serializer 与测试固定使用 Fastjson 1.2.83 `SerializerFeature.WriteMapNullValue`，禁止对该 DTO 增加 `BrowserCompatible`；默认省略 null 会得到不同 schema，BrowserCompatible 会导致非 ASCII 转义并超过预算。实现测试必须先用生产 validator 验证其四条合成路径与 test 生命周期可达，再用生产同一 serializer helper 和协议常量动态生成 fixture，断言 schema 无额外字段、完整保留 null 字段和 6 项且 `String.length()<=8,000`；任何 serializer feature 或常量变化导致 hash/长度变化时测试失败，不在运行时裁剪。
10. 冷启动历史使用独立 16,000 字符预算并优先裁剪非转折历史；进入第二轮后不再携带两周全文。
11. 不在 Java 再叠加第二个请求频率锁。股票专用 bridge 已提供串行队列、默认 90 秒最小请求间隔、30 秒 post-request cooldown 和 300 秒 queue timeout；continuity 复用它，并在 attempt trace 记录 queue/cooldown/submission/timeout 结果。Java bridge 端到端 call timeout 默认 690 秒，必须不小于 health 的 `queueTimeoutMs+requestHardTimeoutMs+30s`；pending 单槽负责后端回压，bridge 负责账号级节奏、circuit breaker 与 submissionKey 幂等，两层职责不同且不重复限速。

### 8.1 Compact continuity DTO 精确 schema

后续轮次传给模型的 `intentContinuity` 只允许以下字段，validator 对未知字段 fail-closed：

- `previousState.positioningCandidates[]`：`intent, beliefWeight, evidencePaths, counterEvidencePaths, reason`。
- `previousState.executionTactics[]`：`tactic, beliefWeight, evidencePaths, counterEvidencePaths, reason`。
- `previousState.uncertainty[]`：字符串。
- `tests[]`：`claimRef, targetAxis, targetValue, expectationType, metricPath, operator, threshold, lower, upper, maxAge, age, observed, currentVerdict, currentActual, matched`。它包含本轮 PENDING 和尚未交付的 terminal tests，按 originEvaluationId/testId 稳定排序。

不得出现 evaluation 元数据、canonicalClaimText、resultJson、历史 actual 数组或 session 信息。targetAxis/targetValue 使用第 6.1 节封闭联合。compact DTO 中 beliefWeight 固定 4 位 decimal string，DECIMAL(24,8) 使用 canonical decimal string，避免 JavaScript/JSON 浮点精度丢失；模型输出仍可接受数值或字符串，Java 统一转 BigDecimal 后持久化/投影。最大 fixture 使用真正 80 UTF-16-unit fact path、60-unit 安全 claimRef、80-unit COMPACT_SAFE_TEXT、负 DECIMAL(24,8) 边界 currentActual、3 个最长合法且去重的候选组合、2 个最长合法且去重的 tactic 和 6 tests。未交付 terminal + 当前 draft tests 的总数不会超过 6：它们都来自上一个 SUCCESS 后最多 6 条未终结测试；INVALID/ERROR 只终结该集合且不创建新项；SUCCESS prompt 已看到的 draft terminal 在同一 commit 标为 delivered，再创建不超过剩余容量的新 tests。因为 compact 文本的反斜杠/双引号/控制字符不合法，不存在合法的双倍 JSON 转义膨胀。编码前的 5,968 UTF-16-unit 计数由文档附录 A 的可执行生成器产生；编码后以 Fastjson 测试结果为最终发布门，二者不一致时更新文档并重新审核，不能选择较小值。

### 8.2 DeepSeek 会话边界

- 当前 bridge key 虽名为 `stock-watch-rule-{market}-{symbol}`，实际已经是股票级且不含 ruleId。修订后 owner 使用 `stock-watch-intent-{market}-{symbol}`；非 owner 判断使用 `stock-watch-rule-{market}-{symbol}-{ruleId}`，隔离规则对 owner 连续状态的隐式污染。
- 会话历史只作传输层上下文优化，不列入允许 evidence path；每次请求仍显式发送 previous state、activeTests validation 和必要历史转折。
- 应用维护 owner session 的 generation、bridge turnCount 和近似 contextChars。任一条件达到 `turnCount>=20` 或 `contextChars>=80000`，下一次 owner 调用设置 `forceNewSession=true`；轮换按真实 bridge turn/字符而非成功 observation 计数，且不增加请求。
- sticky owner 因规则禁用/删除而切换时必须开启新 generation 并 `forceNewSession=true`，禁止把旧 owner condition 的隐式会话继续给新 owner。
- 只有 Bridge 明确返回 FAILED_PRE_SUBMIT 的失败才允许一次 fresh-session 恢复并开启新 generation；UNKNOWN/timeout/已提交/DEFERRED 一律不重试。重试复用 observation submissionKey，Bridge pre-click durable reservation 与 journal 防止重复不可逆调用。`session_meta_json` 与 `attempt_trace_json` 记录每次 sessionKey、generation、forceNewSession、submissionKey/submissionState/submitReservationCount/confirmedSubmitInvocationCount/possibleWebSubmitUpperBound/circuitState、conversationUrl、turn/contextChars 和结果。generation 是应用侧状态，不伪称 bridge 原生字段。
- 即使旧会话影响了模型候选偏好，结构化规则仍由 Java predicate 决定，自然语言规则仍明确标记 `MODEL_INTERPRETATION`；SUPPORTED/FALSIFIED/INSUFFICIENT_DATA review 只能由 Java ledger 派生。无法消除的模型锚定记录为 `uncertainty`，不宣称会话完全无影响。

### 8.3 额外优化评估

- **首版纳入：证据族去相关。** 这是对现有“单一路径不得高置信”的更严格实现，不增加模型调用或 DTO 字段，能防止 MA/RSI/MACD 等同源指标重复抬高 belief。只统计 candidate 正向 evidence 与同 target SUPPORTED_ONLY test 的原 metric family，反证不能凑数。`marketBenchmark` 已在现有 currentFacts 中，归入 RELATIVE_MARKET；缺失时不能虚构板块/大盘中性结论。
- **暂缓：同股自然语言规则批处理。** 当前真实配置是 20 条启用规则、20 个不同股票，每股恰好 1 条，批处理现阶段不会减少请求，却会扩大顶层 schema 和失败半径。未来同股出现多条 MODEL_INTERPRETATION 规则时，再把它们合并为一次 `{ruleDecisions[], intentUpdate}`；STRUCTURED_RULE 始终由 Java 本地执行。
- **暂缓：把 beliefWeight 改成统计后验概率。** 当前样本不足，模型权重只表示候选相对信念。先积累 SUPPORTED/FALSIFIED/INSUFFICIENT_DATA 账本；达到可审计样本量后，再按证据族、时间窗口和数据质量做离线 calibration，并与现有预测 Brier 分数分开，避免伪精确。
- **暂缓：行业中性化。** 当前只有 marketBenchmark，没有可靠行业指数时不能假装已剔除板块 beta。首版记录该 uncertainty；后续若 Eastmoney 行业指数来源稳定，再增加 sector-relative 事实族，不从股票自身涨跌反推行业效应。

## 9. 代码改动清单

### 9.1 新增

- `entity/StockWatchIntentEvaluation.java`
- `entity/StockWatchIntentHead.java`
- `entity/StockWatchIntentTest.java`
- `dao/StockWatchIntentEvaluationMapper.java`
- `dao/StockWatchIntentHeadMapper.java`
- `dao/StockWatchIntentTestMapper.java`
- `resources/mapper/StockWatchIntentEvaluationMapper.xml`
- `resources/mapper/StockWatchIntentHeadMapper.xml`
- `resources/mapper/StockWatchIntentTestMapper.xml`
- `service/StockWatchIntentStateService.java`
- `test/.../StockWatchIntentStateServiceTest.java`

### 9.2 修改

- `StockWatchMonitorService`
  - 构建 observationKey 和股票级 owner；
  - 构建连续上下文并管理同快照去重；
  - 在模型返回后持久化评估；
  - 运行响应和日志暴露 evaluation ID/status/previous ID；
  - 历史查询改为全部日内槽位并按连续上下文策略压缩。
- `StockWatchAiJudgeService`
  - 扩展 JSON 协议与 `AiDecision.intentUpdate`；
  - 删除 conditionText 关键词 deterministic parser；新增显式 structuredPredicate AST validator/evaluator；
  - 限定引用路径并由 Java 生成 canonical claimText；
  - intent schema 无效不新增重试调用；记录 provider/API HTTP/bridge inference/control HTTP、submit reservation/confirmed invocation/possible upper bound 和 sessionKey/submissionKey；
  - API 固定明确 model ID，maxTokens 改为可配置默认 4,096。
- `StockWatchTechnicalAnalysisService`
  - 技术分析改用统一 stock-watch broker 的 `technical-analysis` purpose 和稳定 key；只有分析记录事务提交后发 ACCEPTED ACK，明确 PRE_SUBMIT 以外不重试。
- `StockWatchAiJudgeService` / `StockWatchAiRuleService`
  - owner continuity 使用 `intent-continuity`；必须调模型的非 owner 自然语言判断使用 `rule-judgment`；两者不得走旧未标记 `/search` 或 OpenAI adapter。
- `StockWatchAiRuleService.AiWatchRule`
  - 新增 `structuredPredicate/presetVersion/sourceTemplateHash`；创建/更新时按 sealed AST + 三值语义完整校验；加载存量文件时只对两个 allowlisted 完整模板 hash 做一次 STOCK_ANOMALY_V1 迁移并持久化；文本变更未显式携带新 AST 时原子清除旧 AST。
- `AiModelRouter` / `GenericAiClient`
  - 保留现有 `chat(...)` 兼容接口；新增本链路使用的 `chatExactWithTrace(...)`，返回 answer、httpAttempted、requestedModel/responseModel/systemFingerprint/usage/finishReason/provider/modelStatus/reason；精确 selector 不存在时 fail-closed，禁止 first-client fallback。
  - bridge 调用同样返回结构化 attempt，最终汇总 total/api/bridge inference HTTP、bridge control HTTP、submission 三段计数与逐次 trace。
- `scripts/deepseek-web-bridge.js` / `scripts/deepseek-web-client.js` / `scripts/start-stock-watch-deepseek-bridge-safe.sh`
  - 新增跨 lease 稳定的 observation submissionKey；把 composer prepare 与 click/enter 分离，在两者之间持久化带 target/browser identity 的 durable reservation；DB commit 后 ACK；同 key active/重启后 UNKNOWN 不重复提交、completed 可返回缓存。
  - 启动第一步对 canonical journal/profile 分别取得跨进程独占 lock directory，取锁后才创建 profile 目录；校验 PID+进程启动时间后才可安全回收 stale lock；锁冲突在任何 journal/browser mutation 前退出。所有 journal check/mutation 再使用进程内单写者 revision queue和锁内磁盘 revision 连续性检查，临时文件/文件/父目录完整 fsync；pre-click 等待自身 revision durable，持久化失败 fail-closed。
  - 启动专用 Chrome 时写 browser owner record 并在命令行携带 launch token；通过 OS socket 查询实际 listener PID/start-time，重连、health、target close、Browser.close 前校验 listener/profile/debug-port/token/WebSocket identity。无法证明所有权时只 fail-closed，不操作 endpoint。
  - 用统一 AbortSignal 和 CDP client close 停止 Node 链；post-marker 还要关闭并确认 target 消失，失败且 ownership verified 才关闭 Browser。Node 重启恢复 UNCONFIRMED/recovery circuit，不能直接放行第二 key。
  - 结构化返回 submissionState/failurePhase/submitReservationCount/confirmedSubmitInvocationCount/possibleWebSubmitUpperBound/queueWaitMs/cooldownMs。
  - health 暴露 bridgeRole/capabilities/listener identity/browserOwnershipVerified/instanceId/configFingerprint/enforcedDeadline/browserTargetQuiescence、queue/request hard/abort grace timeout 和账号 circuit；inference 强制携带 expected role/instance/fingerprint/purpose/key。pre-reservation mismatch 可 DEFERRED，post-reservation mismatch 只能 UNKNOWN/recovery。
  - 运行模式固定 `stock-watch` 角色和 8791/9344/canonical profile/journal，关闭 OpenAI adapter和旧未标记 `/search`；三种 purpose 共用同一队列/journal/circuit。
- `scripts/start-stock-watch-stack.sh` / `scripts/start-local-backend.sh` / `scripts/stop-stock-watch-stack.sh`
  - 不再把“技术 bridge”和“AI rules bridge”当成两条无协议的 8791 通道；改为唯一 `STOCK_WATCH_BROKER_URL/ROLE`，只在 Java 调用者已提供合法 purpose/key 后启动后端。
  - stop 先停 Node，只对 owner record + socket listener + token/profile 全部匹配的 Chrome 精确 PID 做可选关闭；旧无 owner record 环境进入 `LEGACY_UNOWNED_BROWSER` 手工迁移流程。
- `ModelRegistryStore`
  - 无论 registry 来自内建还是磁盘，幂等补充 `deepseek-v4-flash`；存在同 modelId 时不覆盖用户配置。
- `StockWatchTechnicalAnalysisMapper(.xml)`
  - 新增 `selectAllSuccessfulDailyByDateRange` 读取完整 14 天成功 DAY 槽位，不做每日 latest 聚合和 SQL LIMIT；不删除旧方法，保证兼容。
- `StockWatchPredictionMapper/Service`
  - 新增按 analysis ID 或股票读取已评估结果的只读接口；不改变现有评估算法。
- `StockWatchTechnicalSchemaInitializer`、`sql/init.sql`、`sql/schema-local.sql`
  - 创建新表和索引，启动时幂等迁移。
- `application.yml`
  - `intent-continuity-enabled=true`
  - `intent-history-lookback-days=14`
  - `intent-history-limit=48`
  - `intent-history-max-chars=16000`
  - `intent-state-max-chars=8000`
  - `intent-max-active-tests=6`
  - `intent-lease-seconds=1590`
  - `intent-lease-commit-margin-seconds=60`
  - `intent-pending-mode=LATEST_WINS`
  - `ai-rules.continuity.api-model-selector=deepseek-v4-flash`
  - `ai-rules.continuity.api-thinking-enabled=false`
  - `stock-watch.broker.url=http://127.0.0.1:8791/search`（技术分析/连续意图/自然语言规则共用，无通用 bridge fallback）
  - `stock-watch.broker.role=stock-watch`
  - `stock-watch.broker.debug-port=9344`
  - `stock-watch.broker.canonical-profile=<stock profile>`
  - `stock-watch.broker.canonical-journal=<stock journal>`
  - `stock-watch.broker.end-to-end-timeout-seconds=690`
  - `stock-watch.broker.health-timeout-seconds=5`
  - `stock-watch.broker.abort-grace-seconds=10`
  - `stock-watch.broker.instance-check-required=true`
  - `stock-watch.broker.acked-retention-seconds=86400`
  - `stock-watch.broker.max-unacked-entries=5000`
  - `stock-watch.broker.circuit-transient-threshold=2`
  - `stock-watch.broker.circuit-open-seconds=900`
  - `ai-rules.continuity.evidence-family-policy-version=1`
  - `stock-watch.broker.submission-journal=${user.home}/.aimeeting-room/stock-watch-bridge-submissions.json`
  - `stock-watch.broker.profile-root=${user.home}/.aimeeting-room/deepseek-web-stock-profile`
  - `ai-rules.max-output-tokens=4096`

## 10. 兼容与降级策略

1. 开关关闭：保持现有 prompt/响应，不读写新表。
2. 旧 `mainForceIntent` 只有单一 `intent`：冷启动时映射为“历史待验证观点”，不直接转换成 v2 belief 真值。新状态对旧技术分析 v1 零写入、零投影；旧前端/API 完整对象保持原样。
3. 新表不存在或 DB 读失败：`intentContinuity.status=unavailable`，不让模型猜上一状态；异动规则判断继续。DB 写失败：本轮触发和通知结果保持，状态不提交。
4. 上一状态 JSON 损坏：跳过该状态并读取更早的最近有效状态，同时记录坏记录 ID；若无有效状态则冷启动。
5. 顶层模型 JSON 可解析但不返回 `intentUpdate` 或嵌套 schema 无效：记录 `INVALID`；邮件正文继续显示原 `analysis`，并提示“连续意图状态未通过结构校验”；若 canonical fact snapshot 有效，旧 activeTests 仍按本 acceptedObservation 推进，但不创建 newClaims。顶层 JSON 不可解析则仅在 STRUCTURED_RULE 已有 deterministic 结果时保留该结果；自然语言规则失败且不通知。评估记 ERROR，旧 tests 仍按有效 fact snapshot 推进。
6. 本轮测试字段部分缺失：ageCount 仍按新 observation 增长，observedCount 不增长；到期信息不完整进入 INSUFFICIENT_DATA，不能当作证伪并释放容量。
7. 同一 observation 的重复调用：若已有 SUCCESS/INVALID/ERROR 任一评估记录，跳过意图更新且 activeTests 不重复消费；其它规则不得接替 owner。
8. 规则被修改/删除：历史 evaluation 使用 `rule_snapshot_json`，状态链仍按股票连续，不重解释旧规则文本；pending 不保存可执行规则快照，提升时按当前规则重选 owner。conditionText 变化且请求未显式提供合法新 AST 时清除旧 AST，避免隐藏阈值继续触发。
9. head lease token/fence/captured stateVersion、evaluation、旧测试 CAS 更新和新测试插入必须同事务校验/提交；唯一键冲突、CAS 失败或部分写失败整体回滚。pendingArrivalVersion 只作为 pending 内容 CAS，不参与 owner stateVersion，也不能被 owner 的旧对象整行覆盖。
10. INVALID/ERROR 只要发生过真实 bridge/API attempt，也必须更新 `session_meta_json` 的 generation/turn/context 计数，避免连续失败时漏计轮换阈值。
11. lease 忙时不生成 evaluation，而是更新 latest-wins pending 单槽；下一轮处理最新快照，被合并样本只进入计数/日志，不消费 tests。
12. 不回填历史数据库。首次新版本运行以现有两周技术分析冷启动，之后形成新的连续链。

## 11. 测试方案

### 11.1 单元测试

1. 首次运行包含最近 14 天全部日内成功槽位，顺序为时间升序。
2. 字符预算裁剪仍保留首条、末条和意图转折点。
3. 第二轮读取上一 evaluation 并验证全部 `activeTests`。
4. MATCH_BEFORE_EXPIRY / NO_MATCH_BEFORE_EXPIRY 的 SUPPORTED/FALSIFIED/INSUFFICIENT_DATA 生命周期和边界。
5. 仓位候选概率和、重复枚举、非法 tactic、非法路径被拒绝。
6. Java 生成的 review 必须绑定 `claimRef + testId`；模型没有 previousReview 字段。只有完整期限内可机器判定的可观测 claim 才能 FALSIFIED，不能证伪资金心理。
7. 无新证据却置信度跳升超过 0.05 时被拒绝。
8. intent schema 无效不影响 `triggered` 和结构化规则合并。
9. 历史内容不能进入 STRUCTURED_RULE evidence 或自然语言规则 accepted `triggerFactPaths`；后者的 triggered 必须标记 `MODEL_INTERPRETATION`，不能显示成程序证明。
10. DB 写失败时监控本轮仍返回触发结果且不发重复请求。
11. 同一模型响应同时得到触发判断和 intentUpdate，验证 schema 有效/无效均没有“意图专用或 schema 修复”调用；只有明确 FAILED_PRE_SUBMIT 可 fresh-session 重试，空响应/截断/timeout/UNKNOWN/已提交均不重试。
12. 旧版历史 `mainForceIntent` 可以冷启动但不会被当作已证实事实。
13. 同股票两条规则采用 sticky owner 和 canonical context；同 observation 只提交一条 owner 评估并只消费一次测试；owner INVALID 时其它规则不接替。
14. 手工重复运行同一 observation 不推进 activeTests，也不创建第二条评估；observationKey 不得包含 bundle asOf/now。
15. 三个不同 observation 中 maxAgeObservations=3 的 claim 在第三次正确到期，不被中间状态覆盖。
16. 数据缺失到达年龄上限后进入 INSUFFICIENT_DATA 并释放容量；即使中间 owner INVALID/ERROR，事实有效的新 observation 仍推进年龄；预测未命中只能证伪 forecast claim，不能直接证伪意图。
17. API 测试验证 continuity 专用 selector、thinking disabled、json_object、maxTokens=4096、精确 response model、usage/finishReason trace；仅 `finish_reason=stop` 成功，其余 finish reason 和顶层截断 JSON 仅允许完整 STRUCTURED_RULE fallback，否则失败。bridge 必须运行时 `jsonComplete=true/contentStatus=success`；嵌套 intent 无效可保留顶层自然语言 trigger，三者均不发 schema 修复请求。
18. 新状态绝不写入旧技术分析 v1 字段，旧前端/API `mainForceIntent` 完整对象保持不变。
19. head lease token/fence/stateVersion/freshness watermark + evaluation + active test CAS + newClaims 插入任一步失败时事务整体回滚；零 activeTests 和不同 observation 并发也不能形成分叉，pendingArrivalVersion 不会误杀在途 owner。
20. 默认 API→Broker→明确 PRE_SUBMIT fresh retry 的 provider inference HTTP attempt trace 最多 3 条；bridge-first 最多 2 条；health/ACK/reset 单列 bridgeControlHttpCount。每条 provider/outcome/session/submission/circuit 元数据正确。在 journal/DB 正常边界内，同一 submissionKey `submitReservationCount<=1`、`confirmedSubmitInvocationCount<=1`、`possibleWebSubmitUpperBound<=1`；timeout/UNKNOWN/SUBMITTED/DEFERRED 不重试，UNKNOWN 不得显示成真实 submit=1。
21. 两个进程竞争同股票 lease 时只有一个可提交 owner；使用数据库时钟验证默认 lease 至少 1,590 秒，完整覆盖 API timeout + 两次 health-derived Broker client timeout + commit margin。允许 Broker fallback 时在 lease 前形成 eligibility snapshot；health 必须 role=stock-watch、包含所需 capability、listener/ownershipVerified 且独立配置字段与预期 8791/9344/profile/journal 全匹配。inference 带 expected role/instance/fingerprint/purpose/key，Broker 在接收/出队/reservation前逐次比较。覆盖 health 后 POST 前重启或改 hard timeout时返回 INSTANCE_MISMATCH_PRE_SUBMIT、reservation=0并回 pending；reservation 后 invoke 前注入 mismatch 时必须 UNKNOWN、reservation=1、upperBound=1且进入 recovery，不得 fresh retry。BRIDGE_FIRST 在 enforcedDeadline=false、ownership=false 或 browserTargetQuiescence!=QUIESCED 时不获取 lease；API_FIRST 只在精确 API available 时可取 API_ONLY lease。模拟 lease 到期 takeover 时旧 token/fence 不能提交；失败 leasedAttempt 不消费 tests。
22. 用真实 Mapper/H2-MySQL 模式执行并发轨迹：每个 sampledSnapshot 先分配统一 arrivalVersion；K1 持 lease；较新 K2/K3 只有实际替换槽才写入新的 pendingArrivalVersion，较早样本只增加 coalescedCount；K1 提交不能清除更新后的 K3。覆盖“处理 P(v5) 时到达较早样本不改内容版本”“实时 winner 条件清除被淘汰 pending”“pending 已有 evaluation 时无模型调用清槽”“实时 K4 与并发 pending K5 同/缺失源时间按 arrivalVersion 排序”。另逐项覆盖 freshness 比较表：10:05 INSTANT accepted 后不同 hash 的 10:00 INSTANT 被 acquire/commit 双门拒绝；INSTANT watermark 后同日 TRADE_DATE/NONE 不消费 tests；TRADE_DATE 后同日 INSTANT 接管；同质量无精确时间按 arrivalVersion；stale pending 条件清槽。全程不改错误的 stateVersion/leaseFence。
23. 由同一协议常量和生产 Fastjson serializer helper 生成第 8.1 节精确 schema：固定 `WriteMapNullValue`、禁止 `BrowserCompatible`，先让生产 validator 确认六个 `NO_MATCH_BEFORE_EXPIRY + BETWEEN[min,min] + currentActual=max + age=8 + observed=7 + matched=false + INSUFFICIENT_DATA` 可达，再用 6 个不同 claimRef 的最坏 tests + 使用4条不同路径且正反证不相交的 previousState 序列化。完整 JSON 必须保留 null 字段和 6 项、得到 5,968 UTF-16 units / 7,248 bytes / SHA-256 `06eb0905...eb84121c` 且 Java `String.length()<=8,000`。另断言默认省略 null 与 BrowserCompatible 都不能被误用。验证 POSITIONING/DISTRIBUTE 和 SUPPRESS/PULL_UP 确为去重联合最长组合；未知 axis/value、错配/重复 enum 及 40/80 个反斜杠、双引号、控制字符不能进入 compact 序列化边界。顶层普通引号/反斜杠在总长门内可接受；第 7 个 PENDING test 被拒绝创建。
24. STRUCTURED_RULE 只来自显式、完整且合法的 structuredPredicate AST；conditionText 即使含可识别数字也不能自动进入。覆盖空 ANY/ALL、未知字段/operator、NOT(missing)、ALL/ANY 的 TRUE/FALSE/UNKNOWN 真值表，以及 ABS_GTE 的非负 threshold/绝对值语义。AST 非法创建/更新被拒绝，旧文件非法字段降为 MODEL_INTERPRETATION；两个允许的完整模板 hash 才迁移 STOCK_ANOMALY_V1，字符变化不迁移，且迁移生成的 preset 必须调用同一个 production validator 成功。conditionText 变化且未显式提交新 AST 时清除旧 AST；pending 提升时重读当前 owner，禁用/删除 owner 后新 generation。operator/expected 由 Java evaluator 执行，模型无法覆盖。
25. Java 按固定模板生成 canonical claimText；模型响应中出现 claimText 字段被拒绝，review/FALSIFIED 文本与 expectation/metric/operator/期限结构一致。
26. 磁盘 registry 缺 V4 Flash 时幂等补充且不覆盖已有配置；`chatExactWithTrace` 找不到/禁用时 `httpAttempted=false`，response model mismatch/usage 缺失/非 stop/HTTP失败时 `httpAttempted=true` 且 API providerInferenceAttemptCount+1，均禁止 first-client fallback并转 bridge。覆盖“API 错误 model 后 bridge 成功，provider inference count=2”。持久化 rule.model=deepseek-chat 不影响 owner continuity selector，非 owner 行为保持。
27. claim 判别联合覆盖 BETWEEN 必填/互斥/lower<=upper，GT/GTE/LT/LTE threshold 必填且 bounds 必须 null，以及 ABS_GTE threshold 必填非负且 bounds 必须 null；非法组合不得生成 canonical claimText 或测试记录。
28. `SUCCESS O1 -> ERROR/INVALID O2 resolves T -> SUCCESS O3` 时，O3 的 compact tests 必须包含 T 的完整结构和 terminal verdict；O2 与失败 O3 都不推进 delivery cursor，只有成功提交的 O3 推进到 captured high water。若 O3 当前事实又使 T2 终结，T2 的 terminal draft 必须已出现在 O3 prompt。`beliefEvidenceBatch=backlog(T)+draft(T2)` 按 testId 去重后统一聚合并在 SUCCESS 恰好生效一次；新 claims 随后创建，下一轮未交付 terminal + PENDING 总数始终<=6。
29. Broker submissionKey 集成测试覆盖同 observation 不同 leaseFence 仍得到同 key、queued client disconnect 无 reservation、composer prepare 不点击、durable reservation 完成后才允许 invoke、同 key active 返回 in_progress、COMPLETED 返回缓存、FAILED_PRE_SUBMIT 只允许一次 fresh retry、timeout/UNKNOWN/SUBMITTED 禁止 retry。专门注入“marker 后 click 前崩溃”“Runtime.evaluate 已接收但响应丢失”“click 返回后 SUBMITTED 落盘前崩溃”，恢复后均 UNKNOWN、不重提。post-marker abort 必须确认 targetId 消失；close target 失败时只有 ownership recheck 通过才 Browser.close。反例覆盖通用 8789/9333、9344 被错误 profile占用、PID复用、launch token/identity变化和“Chrome 启动后另一进程抢占 9344”，均不得获取 lease或关闭 endpoint。每次以 OS socket 查询到的实际 listener PID/start-time 为准，不以 spawn PID 替代。Node 重启但 detached Chrome 仍存活、或 journal 有 unsafe 状态时恢复 OPEN_TRANSIENT_RECOVERY+UNCONFIRMED，只有 teardown+15分钟安全窗后 HALF_OPEN。通过真实 safe launcher 启动两个独立 Node 进程竞争同 journal/profile，第二进程必须在 profile mkdir/mutation/listen/browser connect 前退出；分别覆盖 crash stale-lock 的 PID/start-time 安全回收。进程内 journal 测试交错 ACK/reservation、circuit/COMPLETED、cleanup/reset，revision 单调；每次 mutation 执行锁内磁盘 revision 连续性检查，异常 fail-closed。每个 revision 验证 temp fsync/rename/parent fsync，失败不 invoke。evaluation commit 后 ACCEPTED ACK 才清理 answer，durable stale watermark 可发 STALE_DISCARDED ACK；DB commit/ACK 失败继续返回缓存；5,000 个未 ACK 状态满时 fail-closed。覆盖 OPEN_AUTH、已确认 teardown的连续两次 post-submit UNKNOWN、HALF_OPEN 单探针和 DEFERRED 回 pending。分别断言 bridgeInferenceHttpCount、bridgeControlHttpCount、submitReservationCount、confirmedSubmitInvocationCount、possibleWebSubmitUpperBound。
30. belief 聚合覆盖 `beliefEvidenceBatch` 中同 target SUPPORTED_ONLY/FALSIFIED_ONLY/CONFLICTED、同轴不同 target 和跨轴轨迹。必须包含 `O2 ERROR 产生 A=SUPPORTED backlog + O3 draft A=FALSIFIED => A=CONFLICTED`，以及 `A=CONFLICTED + 同轴 B=SUPPORTED_ONLY` 时 B 可按自身证据越过 cap、A 不得上升。逐 candidate `canExceedCap` 唯一公式通过组合矩阵；CONFLICTED 写 uncertainty，缺失于上一状态的候选以 0 为基线。证据族测试验证 MA/RSI/MACD 只算一个 PRICE_TREND；规范化后 evidence/counter 交集非空被拒绝；一个正向 family+一个 counter family、FALSIFIED/CONFLICTED、同轴其它 target 的 FALSIFIED 都不能通过 0.80 门。只有 `aggregate(t)==SUPPORTED_ONLY` 才把该 target 中 verdict=SUPPORTED 的原 metric family 合并到 candidate 正向 family；达到两个维度才允许。不可达 priorValidation evidence path 被拒绝，policyVersion 持久化。
31. 五行路由矩阵全覆盖：BRIDGE_FIRST ineligible 不取 lease；API_FIRST + API available + Broker ineligible 只取 API_ONLY lease；API_ONLY transport/无可用响应回 pending 且 tests 不消费；可解析顶层+无效 intent 可提交 INVALID；API 失败只在原 eligibility snapshot 为 true 时尝试 Broker，本轮绝不重做 health、不改走 8789。
32. 账号 broker 隔离测试：未带 role/purpose/key、未知 purpose、purpose 与 key 结构不匹配、通用 OpenAI adapter、旧技术分析裸 `/search` 均在进队前拒绝且 reservation=0；合法技术分析与 intent-continuity 串行共用同一 cooldown/circuit，两者分别在自身 DB commit 后 ACK。
33. 迁移测试：停旧 Node 后 9344 仍活但无合法 owner record 时产生 `LEGACY_UNOWNED_BROWSER`，新 Broker 不 connect/close/listen；只有精确校验 socket listener PID/start-time/cmdline profile+port 并确认 endpoint 下线后才可新启。测试禁止宽泛 pkill，并验证其它 Chrome 完全不受影响。

### 11.2 集成测试

- 三张新表 CRUD、head arrivalVersion/stateVersion/leaseFence/pendingArrivalVersion/freshness watermark/review delivery cursor、任意状态 observation 幂等、取上一有效状态、activeTests 全生命周期和链路排序。
- Schema initializer 在空库和已有库重复执行均成功。
- 预测结果可按历史 analysis ID 注入 `priorValidation`。

### 11.3 真实数据冒烟

选择已有单股配置且历史记录充足的真实股票，优先 `002594.sz` 比亚迪；若当前库历史不足则使用 `002979.sz` 雷赛智能。

步骤：

1. 执行存量迁移 preflight：停旧 stock Node，检查 9344 的实际 listener。无 owner record 时必须显示 `LEGACY_UNOWNED_BROWSER`；只在 PID/start-time/cmdline 精确证明为旧 stock profile 后终止该 PID，确认 endpoint 下线，不影响其它 Chrome。
2. 重启股票专用 Broker、后端，确认 health 的 role=stock-watch、三项 capabilities、listener PID/start-time、ownershipVerified=true、instance/fingerprint、browserTargetQuiescence=QUIESCED；Broker URL 必须为 8791，不接受通用 8789。
3. 使用 `notify=false` 强制执行第一次真实 AI 规则判断，确认：真实 Eastmoney 数据、真实 DeepSeek Broker、evaluation SUCCESS、sourceAnalysisIds 非空、无邮件。
4. 立即重跑相同行情，确认 observationKey 相同、没有第二条 evaluation、activeTests 不消费，避免用重复快照伪造“第二轮验证”。
5. 若实时行情产生了新的上游 `quote.timestamp/updateTime` 或 canonical fact hash，再执行新 observation 并确认 `previousEvaluationId` 和 priorValidation；若已收盘没有新快照，则使用库中两个真实历史 dataSnapshot 做只读 replay 冒烟，验证第二 observation 的程序验收，不把回放当实时通知。
6. 另强制一次真实技术分析，确认 `requestPurpose=technical-analysis`、稳定 key、与 intent 共用同一队列/cooldown，分析记录 commit 后 ACK；任何旧裸请求都被 reservation=0 拒绝。
7. 检查 Broker 请求计数与 journal：正常 owner 不因 intent schema 追加修复请求；仅明确 FAILED_PRE_SUBMIT 可出现一次 fresh-session inference HTTP 恢复；同一 key 的 submitReservation/confirmedInvocation/possibleUpperBound 均<=1，UNKNOWN 不伪装成确认提交；evaluation/technical analysis commit 后各自 ACCEPTED ACK 生效。health/ACK 只增加 control count。另用 mock/受控错误验证 abort quiescence、circuit DEFERRED 不产生提交预留，真实账号冒烟不故意制造风控页。
8. 查询数据库确认链条、activeTests、规则快照、prompt hash、provider/session/attemptCount、原始响应和事实快照可追溯。
9. 若测试需要邮件，只在最后使用一次明确的测试规则并确认单股配置；默认不发送，避免真实收件人收到多封冒烟邮件。

通过标准：实时请求 HTTP 200；至少一轮 intent SUCCESS；重复快照不推进；新快照或真实历史 replay 能形成 previous 链和程序验收；真实行情与模型响应可追踪；无意图专用/schema 修复请求；日志无未处理异常。

## 12. 实施顺序与回滚

1. 先加表、实体、Mapper、状态服务及单测。
2. 实现 stock-watch broker 协议、所有权/跨进程锁/journal/circuit 和迁移 preflight，先用 mock Chrome 通过隔离与崩溃测试，不立即连真实账号。
3. 把技术分析、连续意图和自然语言规则调用者迁移到封闭 purpose/key 协议；扩展 AiDecision 协议和校验，不接入通知。
4. 接入 Monitor，默认开关开启；全部单测、集成测试和编译通过。
5. 按第 11.3 节处理 legacy endpoint，本地重启、notify=false 执行真实 intent + duplicate + replay/new observation + technical 冒烟。
6. 若连续意图新链路异常，可通过 `intent-continuity-enabled=false` 关闭该功能；但不得因此把技术分析退回未限速的旧 8791/8789 路由。新表为旁路审计数据，不影响旧表。

## 13. Claim Graph（审核包）

### Objective

- Primary：让每次异动判断通过验证前次判断，持续缩小占主导资金行为策略的候选空间。
- Secondary：不增加模型请求；保持触发事实边界；结果可审计；兼容现有运行链。

### Constraints

- DeepSeek 网页账号请求频率受限，不能增加二次分析/审核请求。
- 真实意图不可直接观测，只能验证可观测后果。
- 当前工作树已有相关未提交修改，实施必须保留并增量适配。
- 现有结构化触发和邮件冷却语义不得回退。

### Claims

#### S1（fact，high）

技术分析表已经保留 30 分钟日内槽位，但当前异动上下文只查询“每天最后一条”并截断数量，且没有独立 acceptedObservation/test 状态链，不能表达相邻异动判断之间的验证关系。

- dependsOn：E2、E3
- hiddenAssumptions：用户要求每个执行槽位都能参与后续验证。
- falsifiers：代码存在另一条可靠持久化日内意图链并已输入模型。
- alternatives：继续只取每日最后一条；拒绝原因是无法验证相邻槽位判断。

#### S2（inference，high）

自由文本让模型自评历史观点会产生循环验证风险；只有 Java 对预先登记、与 claimRef/testId 绑定且期限语义完整的可观测 claim 进行验收，才能生成 SUPPORTED/FALSIFIED。FALSIFIED 可以来自 NO_MATCH claim 的决定性 match，也可以来自 MATCH claim 在数据覆盖完整时到期未命中。

- dependsOn：E1、E5、E6、S1
- hiddenAssumptions：结构化事实路径和程序测试结果比模型文字更适合充当验真依据；普通预测结果只验证其可观测后果。
- falsifiers：能够证明模型自评具有独立外部事实源且不会引用自身结论。
- alternatives：纯 prompt 方案；拒绝原因是不可机器审计且易锚定。

#### S3（recommendation，high）

仓位目标与执行手法必须分轴，否则单一 intent 枚举会制造错误互斥。

- dependsOn：E4
- hiddenAssumptions：两类概念允许同时成立。
- falsifiers：业务定义明确要求它们互斥，或现有数据无法支持任何手法判断。
- alternatives：保留单枚举并增加组合值；拒绝原因是组合爆炸、校验与统计困难。

#### S4（recommendation，medium）

在“被接受的异动新事实必须参与下一轮、技术分析调度可关闭、状态按 observation 而非规则推进”的前提下，股票 head/lease/pending 单槽、独立 evaluation 和程序 test ledger 是满足线性状态、请求回压、完整链路、审计和失败降级的最小可靠持久化边界。

- dependsOn：E6、S1、S2
- hiddenAssumptions：不能把分析状态等同于邮件状态；技术分析表不应混入规则评估记录；异动路径可能先于下一技术分析槽位；账号安全优先时允许把并发中间 sampledSnapshot 合并为最新 pending。
- falsifiers：现有表已有语义一致、每轮必写且可表达 previous/validation/state 的结构。
- alternatives：状态 JSON 文件；拒绝原因是只保留可变快照、难查询和审计。复用技术分析表；其快照/预测关联可复用，但当技术分析调度关闭或异动发生在槽位之间时无法拥有本次异动状态，且会污染周期分析语义。按 rule 建表；拒绝原因是同一 observation 多次推进。

#### S5（recommendation，high）

连续意图更新应搭载当前 observation 中某次现有 AI 判断响应，不新增意图专用或 schema 修复逻辑调用；provider fallback 保留，fresh-session 仅限明确 PRE_SUBMIT 失败。Bridge 以稳定 submissionKey、单写者 durable reservation、expected instance/fingerprint 握手、可执行 HTTP deadline、browser target teardown、acceptedObservation 后 ACK 和账号级 circuit，在 journal/DB 正常边界内把同一 observation 限制为最多一次 reservation 和一次不可逆浏览器调用，并对不可观测崩溃窗口只报告 possible upper bound。

- dependsOn：请求频率约束、E1
- hiddenAssumptions：精简 schema、4,096 maxTokens、6,000 字符 DTO 上限足以容纳新增结构；API usage 和真实 bridge 截断测试仍需编码后验证。
- falsifiers：journal/DB 正常边界内同一 observation 的 reservation 或 confirmed invocation 大于 1，UNKNOWN 被展示为已确认真实提交，browser target 未 teardown、instance mismatch 或 circuit 打开后另一 key 仍进入不可逆调用，旧 journal revision 覆盖新 reservation，或 continuity 产生意图专用调用。
- alternatives：独立第二次意图调用；拒绝原因是请求翻倍并增加账号风险。

#### S6（recommendation，medium）

程序必须有界追踪该股票全部未终结 activeTests，并按唯一新 observation 消费；模型只提出新的可观测 claims，不拥有测试生命周期或 review verdict。

- dependsOn：S1、S2、S4
- hiddenAssumptions：最多 6 个 activeTests 足以覆盖当前业务；超过上限可拒绝新增而不是删除旧测试。
- falsifiers：真实运行长期达到 6 个有效测试而持续拒绝高价值新测试，或测试账本无法在重复 observation/多规则下保持幂等。
- alternatives：只携带上一状态测试；拒绝原因是 maxAgeObservations>1 会被中间状态覆盖。无限事件流；拒绝原因是上下文和查询不可控。

### Conclusion

若 S1-S6 均通过，按“股票 head/lease + observation evaluation + active test ledger + Java 验真/review + 双轴 belief + 无新增意图专用调用”实施；任一审核发现 I1-I7 无法保持，则编码前修改方案。

## 14. 编码前审核门

Challenge 必须验证：

1. E1-E6 是否与当前代码一致；
2. 是否遗漏会导致循环论证、历史污染触发或错误覆盖上一状态的路径；
3. `FALSIFIED` 的证据边界是否足够严格；
4. 新表是否真是必要边界。

Optimize 必须验证：

1. 是否有同等可靠但更小的持久化方案；
2. 双轴模型是否比单枚举组合更适合当前业务；
3. 单请求输出大小、上下文压缩和降级是否兼顾账号风险；
4. 有界追踪全部 activeTests 是否覆盖跨三轮到期且不会被重复 observation 消费。

只有两个审核均返回“通过”后才能编码；部分通过必须修改文档并复审。

## 15. 首轮审核 finding 处置记录

第 15-17 节仅保存审核历史，其中旧常量、旧枚举和旧 token 结论均已被第 3-11 节及第 18 节覆盖，不能作为实施规范复制。

Challenge：Carver，agent `019f612a-c876-77e2-986f-93eb78740cbf`，结论不通过。

Optimize：Galileo，agent `019f612a-c933-7cc3-b17b-b3bfe1911ba2`，结论不通过。

| Finding | 判定 | 修订 |
|---|---|---|
| E1 被描述为严格事实边界，但实际只有 prompt + 关键词拦截 | 属实 | E1 降级；新增 matchedSignals 与 verdict 的结构化路径校验 |
| E2 把“上下文投影遗漏”写成“数据库历史链丢失” | 属实 | 改写 E2，明确记录仍在库中并复用现有范围查询 |
| `NOT_MET` 被允许推导 `FALSIFIED` | 属实 | 拆分 SUPPORT_IF_MATCH / CONTRADICT_IF_MATCH；只有显式矛盾命中允许 FALSIFY |
| 预测未命中可直接证伪资金意图 | 属实 | 限定预测只验证可观测 forecast claim，最多间接削弱意图 |
| 只读取上一状态会丢失跨多轮测试 | 属实 | 新增程序拥有的 active test ledger，追踪全部未终结测试 |
| 同股多规则按调用推进会顺序依赖 | 属实 | 状态身份改为 symbol+market+observationKey，每个新事实快照只成功提交一次 |
| 重复行情/手工重跑会错误消费测试期限 | 属实 | 首轮改为 observationKey 幂等；当前版本进一步使用股票 head/lease + 唯一 observation 约束 |
| 新 evaluation 表是最小边界尚未证明 | 属实 | 增加三种 owner/cadence 比较；技术分析可关闭且异动需即时更新，因此选择 observation 级独立表；测试生命周期单独 ledger |
| “最多一次物理请求”与现有 fallback/retry 冲突 | 属实 | 改为不新增意图专用/schema 修复调用；保留并计数既有物理重试 |
| 900 token 可能容不下原大 schema | 属实 | 删除程序可派生字段，限制数组/文本/总字符；API 默认 1,600 tokens；增加截断测试 |
| 固定 DeepSeek session 是未审计隐式输入 | 属实 | session 统一股票级、20 observation 轮换、记录 generation/url/attempt；会话历史不得作为证据路径 |
| `continuity.*` 与 `aiContext.intentContinuity.*` 路径不一致 | 属实 | 协议统一为 `aiContext.intentContinuity.*` |
| 双轴会破坏旧前端读取单一 intent | 属实 | 当前最终修订为新状态对技术分析 v1 零写入、零投影，旧完整对象不变 |

第二轮复审必须重点验证：owner/cadence 选择是否真正满足用户目标；activeTests 是否能跨三轮且幂等；强证伪语义是否闭合；请求与会话边界是否自洽。

## 16. 第二轮审核 finding 处置记录

两名原审核者第二轮均判定不通过。以下是主 agent 对第二轮 blocker 的处置，仍需第三轮确认：

| Finding | 判定 | 第三版修订 |
|---|---|---|
| matchedSignals 仍是字符串，无法证明触发来源 | 属实 | 新增结构化 triggerEvidence；模型触发无合法当前事实路径时强制 false；reason/matchedSignals 降为展示 |
| owner 内容仍受规则顺序、conditionText 和增强上下文影响 | 属实 | sticky owner；冷启动 ruleId 字典序；owner 优先执行；canonical context 永远完整且与 condition 分区；INVALID 不接力 |
| observationKey 可能误用本地 bundle asOf | 属实 | 明确只允许上游 quote 源时间/sourceTradeDate + canonical facts hash，禁止 asOf/requestedAt/now |
| evaluation 与 ledger 缺少原子边界 | 属实 | 第二版先做单事务；当前最终修订进一步增加 head lease/version，且有效 INVALID/ERROR observation 也推进旧 ledger |
| 模型 previousReview 缺 claimRef/testId 且可自定义 FALSIFY | 属实 | 删除模型 previousReview；Java 根据预登记可观测 claim 和 ledger 生成 review；FALSIFIED 只描述精确可观测 claim，不描述资金心理 |
| MISSING 可永久占满 32 个 active tests | 属实 | ageCount 对每个新 observation 增长；到期且数据不完整进入 INSUFFICIENT_DATA 并释放容量 |
| 物理请求最大值写成 2，实际默认可达 3 | 属实 | 明确 API+bridge+fresh retry 最大 3、bridge-first 最大 2；保存 total/api/bridge 与 attemptTrace |
| 顶层 JSON 截断无法只丢 intentUpdate 保留模型 trigger | 属实 | 区分顶层不可解析与嵌套 intent 无效；前者只保留已有 deterministic fallback，否则失败；后者保留可解析 trigger |
| session 字段和轮换与真实物理 turn 不闭合 | 属实 | owner/non-owner session 隔离；按 bridge turn/contextChars 轮换；session_meta_json 与 attempt_trace_json；generation 明确为应用侧 |
| schema 仍过大、缺逐项硬上限 | 属实 | 当时版本删除模型派生字段并补全顶层/嵌套硬上限，采用 tokenizer 门；该门已被第 6.2 与第 18 节替代 |
| legacy projection 可能不完整 | 属实 | 新状态不写入技术分析 v1，也不进入旧接口；未来若投影必须完整对象，本次不做单值投影 |
| 新测试可能用创建 observation 自证 | 属实 | newClaims 仅在事务末尾创建，第一个 eligible observation 必须是不同 observationKey |

第三轮通过条件：上述修订在文档内部无矛盾，challenge 与 optimize 均明确返回“通过”；否则继续修订，不编码。

## 17. 第三轮审核 finding 处置记录

第三轮两名审核者仍判定不通过。以下修订等待第四轮核销：

| Finding | 判定 | 第四版修订 |
|---|---|---|
| INVALID/ERROR observation 不推进 ledger，和每个新 observation 增龄矛盾 | 属实 | canonical facts 有效时所有状态都推进旧 tests；只有 SUCCESS 更新 intent head 和创建 newClaims |
| test CAS 不能防零 activeTests 或不同 observation 并发分叉 | 属实 | 新增股票级 durable head/lease/version；请求前短事务抢 lease，请求后 token+version 单事务提交 |
| triggerEvidence 可伪造 actual，未执行谓词 | 属实 | 模型不得输出 actual；Java 重取 actual、强类型校验 operator/expected 并执行谓词，false evidence 不接受 |
| FALSIFIED 与 INSUFFICIENT_DATA 终态优先级冲突 | 属实 | 固定为决定性 match 优先；未 match 到期后才检查数据覆盖完整性 |
| sourceTradeDate 可能来自 system_date_fallback | 属实 | 仅 selectedSource=quote.timestamp/dailyKline.latest 时纳入；system fallback 排除 |
| 4,000 字符不能证明 1,600 token | 属实 | 完整 envelope 全字段硬上限；目标 DeepSeek tokenizer 最坏 fixture <1,400 tokens，字符数只记录 |
| 模型 previousReview 已删，但 test 表缺 claim_text/axis/value | 属实 | test 表补齐稳定 claimRef、claimText、targetAxis/targetValue |
| 当前 API 返回 String，无法区分未发 HTTP 和真实失败 | 属实 | AiModelRouter/GenericAiClient 增加兼容的 chatWithTrace 结构化结果；bridge 也返回 attempt trace |
| 32 activeTests + review 输入预算未证明 | 属实 | 上限降为 12；最坏 activeTests+generatedReview+previousState 必须 <=8,000 字符且不可裁剪 tests |
| legacyIntent 指令与零投影冲突 | 属实 | 删除派生 legacyIntent；新状态对旧 v1 零写入、零投影 |
| owner 切换仍可能继承旧 session | 属实 | owner 变化强制新 generation/forceNewSession |
| 新 claim 可能创建轮自证 | 属实 | 继续保持下一个不同 observation 才可验收 |

第四轮通过条件：head/lease 生命周期、所有状态推进旧 tests、Java trigger/review、token/input 预算、request trace 和零 legacy 投影内部一致，两个审核均返回“通过”。

## 18. 第四轮审核 finding 处置记录

第四轮两名原审核者仍判定不通过。主 agent 对全部 material findings 的判定与第五版修订如下，等待第五轮核销：

| Finding | 判定 | 第五版修订 |
|---|---|---|
| lease 只按单次 bridge timeout 设计，未覆盖约 590 秒完整链 | 属实 | 使用数据库时钟；租期公式固定为 API timeout + 2 次 bridge timeout + commit margin，当前默认 650 秒；启动校验和 takeover 测试 |
| lease 不能保证绝无重复物理请求 | 属实 | 不变量降为“只有一个可提交 owner”；明确过期边界可重叠但旧 token 不能提交 |
| lease 忙时 K2 可能静默丢失 | 属实 | 区分 sampled/accepted/coalesced；增加 latest-wins pending 单槽、上游时间比较和 coalescedCount；只 acceptedObservation 消费 tests |
| latest-wins 不能声称逐快照消费 | 属实 | 目标与 claim 改为受控采样连续收敛；明确中间 sampledSnapshot 可能合并，这是账号请求安全优先的边界 |
| 模型仍能伪造 expected/operator | 属实 | 结构化规则完全由 Java 生成 predicate/evidence；自然语言规则只提交事实路径并标记 MODEL_INTERPRETATION，不包装成程序证明 |
| candidate/tactic/newClaims 上限前后冲突 | 属实 | 唯一协议常量统一为 candidate 2..3、tactic 1..2、newClaims 0..2 |
| 模型 claimText 可能与结构字段矛盾 | 属实 | 删除模型 claimText；Java 固定模板生成 canonicalClaimText，review 只使用该文本 |
| 12 tests 已有 9,233 字符反例 | 属实 | active 上限降为 6；只发送 compact DTO；最坏 fixture 实测 6,287 字符 / 7,567 bytes，小于 8,000 |
| 官方 tokenizer 未绑定目标模型 | 属实 | API 固定 deepseek-v4-flash 并记录真实 usage/model/fingerprint；官方当前 v3 tokenizer 只作带 SHA 的估算，不伪称 V4 精确证明；bridge 用真实非截断 JSON 验收 |
| INVALID/ERROR 可能漏计 bridge session turn | 属实 | 只要发生物理 attempt 就更新 session meta，不以 intent SUCCESS 为条件 |

第五轮通过条件：两名原审核者确认上述边界内部一致，尤其是 acceptedObservation 语义、650 秒 lease、双轨 trigger、Java canonical claim、6-test fixture 和 API/bridge 分路预算；任一 material blocker 未关闭则继续修订，不编码。

## 19. 第五轮审核 finding 处置记录

第五轮两名原审核者仍判定不通过。全部 material findings 已进入第六版：

| Finding | 判定 | 第六版修订 |
|---|---|---|
| pending CAS 与 owner 共用 head version，会误杀 K1 或覆盖 K3 | 属实 | 拆分 stateVersion/leaseFence/pendingVersion；pending 不改 owner fence；owner 只按 captured pendingVersion+key 条件清槽 |
| 取得 lease 就称 accepted，过期失败后语义矛盾 | 属实 | 新增 leasedAttempt；只有 evaluation/ledger/head 事务成功才成为 acceptedObservation，失败尝试不消费 tests |
| 同源时间/缺失时间的 pending 无顺序 | 属实 | pendingVersion 作为数据库原子到达序号；可信较早时间不覆盖，相同/缺失时间后到者 latest-wins；序号不进 observationKey |
| 当前 structured parser 可部分识别后冒充整条规则证明 | 属实 | 新建完整 AST compiler；必须消费全部 token/子句/单位/逻辑，否则整条降为 MODEL_INTERPRETATION；加入混合反例测试 |
| compact DTO 没有精确字段 schema 与可复算 fixture | 属实 | 第 8.1 节固定逐字段 schema；附录 A 提供生成器、6,962 字符和 SHA；编码后 Fastjson serializer 为最终门 |
| continuity V4 model 在现有 registry/router 不可执行 | 属实 | continuity 专用 selector；registry 幂等补 V4 Flash；chatExactWithTrace 禁止 first-client fallback；API unavailable 明确转 bridge |
| V4 默认 thinking 且 finishReason 门不完整 | 属实 | API 显式 thinking disabled/json_object；只接受精确 model+usage+finishReason stop+完整 schema，其它均 ERROR；bridge 运行时要求 jsonComplete/contentStatus |

第六轮通过条件：原 challenge 与 optimize 审核者确认三版本并发轨迹、完整规则编译、精确 DTO fixture 和 exact model route 均闭合；不通过则继续修订，不编码。

## 20. 第六轮审核 finding 处置记录

第六轮两名原审核者仍判定不通过。全部 material findings 已进入第七版：

| Finding | 判定 | 第七版修订 |
|---|---|---|
| 实时 sampledSnapshot 没有与 pending 同域到达序号 | 属实 | 每个样本在候选选择前由 DB head 分配 arrivalVersion；lease/pending 都保存该序号，可信源时间优先、同/缺失时间按 arrival 排序 |
| 较早 busy 样本递增 pendingVersion 导致永不清槽 | 属实 | pendingVersion 只在槽内容实际插入/替换时递增；纯计数只增加 coalescedCount |
| 实时 winner 不能清除被淘汰旧 pending，duplicate pending 可残留 | 属实 | acquire 始终捕获 pending；实时/pending winner 提交都可按 captured version+key 条件清槽；已有 evaluation 的 pending 无模型调用清槽 |
| 全量自然语言 compiler 会让现有混合说明规则降级 | 属实 | 放弃自然语言编译；规则新增显式 structuredPredicate AST；现有 20 条只按两个完整模板 hash 确定迁移 |
| model mismatch 已发 HTTP 却记 httpAttempted=false | 属实 | 仅 preflight unavailable 为 false；一旦执行 OkHttp Call，所有失败/拒绝都为 true 并计物理请求 |
| claim operator 的 threshold/bounds 联合未定义 | 属实 | 定义 BETWEEN 与非 BETWEEN 判别联合、必填互斥和 lower<=upper；canonical claim 只从合法联合生成 |
| fixture path 实际 79 字符且未防 JSON 转义膨胀 | 属实 | path 修正 80；claimKey 安全 ASCII；所有自由文本 SAFE_TEXT 拒绝引号/反斜杠/控制与行分隔字符；belief/decimal canonical string；重算最长枚举组合为 7,022 chars/hash |

第七轮通过条件：两名原审核者确认统一 arrivalVersion、pending 三条清理轨迹、显式 predicate AST、真实 physical count、claim 判别联合和 SAFE_TEXT 最大 fixture 均闭合；不通过则继续修订，不编码。

## 21. 第七轮审核 finding 处置记录

第七轮两名原审核者仍判定不通过。主 agent 已逐项独立核对，以下 material findings 均属实并进入第八版；Optimize 提出的“删除全部 arrivalVersion 字段”不采纳，因为 freshness watermark 和 lease/pending commit 审计需要稳定的到达序号，删除后无法关闭 Challenge M1。

| Finding | 判定 | 第八版修订 |
|---|---|---|
| 已提交 10:05 后不同 hash 的 10:00 旧快照仍可倒序消费 tests | 属实 | head 增加 lastAccepted source time/date/quality/arrival watermark；INSTANT/TRADE_DATE/NONE 比较表；acquire 与 commit 双门拒绝 stale/不可证明更新 |
| AST 缺 children 基数、未知字段和缺失值逻辑 | 属实 | sealed AST schema；ANY/ALL 非空；UNKNOWN 三值传播；NOT UNKNOWN 仍 UNKNOWN；根只有 TRUE 可触发 |
| conditionText 编辑会遗留隐藏旧 AST，pending owner snapshot 会执行过期规则 | 属实 | 文本变更未显式带合法新 AST 时原子清除；加载时复核迁移 hash；pending 删除可执行规则快照并在提升时重选当前 owner |
| ERROR/INVALID 中形成的终态 review 可能永远不送达模型 | 属实 | head 增加 terminal resolution delivery cursor；prepare 捕获未交付 high water；只有 SUCCESS commit 推进，失败继续重放 |
| INSUFFICIENT_DATA 被错误用于解除 belief 增幅限制 | 属实 | 数据不足和 forecast miss 不解锁；SUPPORTED/FALSIFIED 只按目标轴和值定向解锁/抑制 |
| Bridge timeout 后服务端仍可能提交，fresh retry 造成重复网页请求 | 属实 | 稳定 submissionKey 状态机、queued abort、active/cache 幂等；仅明确 FAILED_PRE_SUBMIT 可重试；HTTP 与 web submit 分开计数；lease/timeout 扩到真实端到端上限 |
| targetAxis/targetValue 值域未封闭，7,022 不是最坏合法输入 | 属实 | 定义 POSITIONING/TACTIC 判别联合及精确 value enum；合并 tests/reviewDelta 后 fixture 使用最长 POSITIONING/DISTRIBUTE，重算为 5,968 chars，未知或错配组合拒绝 |
| 迁移 AST 编辑保护 | 属实 | 与上面第 3 项合并，旧前端改文本自动安全降级，无需同步支持 AST |
| 冷启动先截 48 条会遗漏 14 天较早转折 | 属实 | 完整读取 14 天后先标转折，再补最新普通记录并压到 48/16k |
| 所有顶层自由文本都禁引号/反斜杠会产生无意义 INVALID | 属实（minor） | COMPACT_SAFE_TEXT 只用于后续重复携带文本；顶层展示文本允许正常转义并受 Fastjson 序列化后总长门控制 |
| 删除 arrivalVersion 可简化协议 | 不采纳 | 行锁顺序可替代候选 tie-break，但不能替代 durable accepted freshness watermark 和 lease/pending 的 commit 审计序号 |

第八轮通过条件：两名原审核者确认 committed freshness、AST 三值语义、规则编辑/owner 生命周期、terminal review delivery cursor、定向 belief 门、Bridge submission 幂等与真实 webSubmitCount、target 判别联合和完整 14 天压缩均闭合；任何 material finding 未关闭则继续修订，不编码。

## 22. 第八轮审核 finding 处置记录

第八轮两名原审核者仍判定不通过。以下 correctness/material findings 均经源码核对后判定属实并进入第九版：

| Finding | 判定 | 第九版修订 |
|---|---|---|
| STOCK_ANOMALY_V1 使用 ABS_GTE，但 sealed operator 未允许 | 属实 | operator 联合加入 ABS_GTE，threshold 必填非负、bounds 为空；preset 必须经过同一 production validator |
| 570/1,350 秒低估真实 Bridge 最坏链 | 属实 | 以 health 的 queueTimeout+requestHardTimeout 为服务器边界，再加 transport margin；当前 client=690s、lease=1,590s，拒绝复制 provider 子阶段猜测 |
| 24h/5000 TTL 与绝对 at-most-once claim 冲突 | 属实 | 不可逆点击前 durable marker；COMPLETED/UNKNOWN 未 ACK 不过期不淘汰；acceptedObservation 后 ACK 才清理；容量满 fail-closed；明确 journal/DB 人工清除不在保证边界 |
| 同 target 同轮 SUPPORTED/FALSIFIED 时 belief 规则冲突 | 属实 | Java 聚合 SUPPORTED_ONLY/FALSIFIED_ONLY/CONFLICTED；CONFLICTED 不解锁且写 uncertainty；跨轴隔离 |
| 系统性登录/风控失败时不同 key 仍每 90 秒撞账号 | 属实 | 新增持久化账号 circuit：OPEN_AUTH 人工恢复，OPEN_TRANSIENT 15 分钟、HALF_OPEN 单探针，DEFERRED 不提交并保留 pending |
| tactic 未去重、长度单位不统一，5,968 不是严格最大 | 属实（minor） | tactic 去重；长度统一 Java UTF-16 String.length；fixture 使用最长不同 tactic SUPPRESS/PULL_UP，Fastjson 为最终门 |
| pendingVersion 与 pendingArrivalVersion 重复 | 属实（minor） | 删除 pendingVersion，只用 pendingArrivalVersion+observationKey 做内容 CAS |
| tokenizer 包名过时 | 属实（minor） | 改为当前 deepseek_tokenizer.zip；继续只把 API usage 视为真实 token 证据 |
| “逼近真实心理”超出证据边界 | 属实（minor） | 结论限定为持续缩小与可观测事实一致的资金行为候选，不声称识别唯一主体心理 |

第九轮通过条件：两名原审核者确认 ABS_GTE/preset validator、health-derived timeout/lease、pre-click marker+ACK journal、账号 circuit、belief 冲突聚合、pendingArrivalVersion CAS 和 UTF-16/Fastjson fixture 均闭合；任何 material finding 未关闭则继续修订，不编码。

## 23. 第九轮审核 finding 处置记录

第九轮 Challenge 与 Optimize 均判定不通过。两名审核者独立命中了 Bridge 伪 hard-timeout；其余 finding 经当前源码与方案逐项核对后均判定属实并进入第十版：

| Finding | 判定 | 第十版修订 |
|---|---|---|
| pre-click marker 写 `webSubmitCount=1` 冒充真实点击 | 属实 | composer prepare 与 invoke 拆分；marker 记录 reservation/possible upper bound，invoke 返回后才记录 confirmed；崩溃窗口为 UNKNOWN，不报告不可观测精确值 |
| 当前 watchdog/Promise.race 只释放 inFlight，不取消旧 CDP worker | 属实 | 统一 AbortSignal、关闭 CDP client、等待 worker quiesced；未确认停止立即打开 circuit 并保持 fail-closed，不能让第二 key 进入页面 |
| health 只首次校验，bridge 重启/环境变化后 690 秒预算可能失真 | 属实 | health 增加 instanceId/configFingerprint/enforcedDeadline/workerQuiescence；每次 bridge inference 在 DB lease 前探测，实例/指纹变化重验 |
| 同轴 A=CONFLICTED、B=SUPPORTED_ONLY 时旧规则互相冲突 | 属实 | 改成逐 candidate `canExceedCap` 唯一公式；A 冲突不覆盖 B 自身直接支持 |
| ERROR/INVALID backlog 与本轮 terminal draft 未统一聚合 | 属实 | 定义 beliefEvidenceBatch=未交付 backlog∪本轮已展示 draft，按 testId 去重；只在 SUCCESS 推进 cursor，整批恰好生效一次 |
| AST 同时写“非数值 actual 拒绝”和“actual 非数值为 UNKNOWN” | 属实（minor） | schema 只拒绝非法 AST 数值字面量；运行 actual 缺失/非数值统一返回 UNKNOWN |
| Fastjson 默认省略 null，BrowserCompatible 又会把 fixture 扩到 8k 以上 | 属实（minor） | 生产与测试统一 `WriteMapNullValue` 且禁止 BrowserCompatible；锁定 5,968/7,248/hash |
| health/ACK 混入最多三次 inference HTTP 口径 | 属实（minor） | 新增 bridgeControlHttpCount，provider inference attempt 上限不含控制面 |
| stale COMPLETED 会长期占未 ACK 容量 | 属实（minor） | durable watermark 明确判 stale 后可发送 STALE_DISCARDED ACK，不获取 lease、不消费 tests |
| MA/RSI/MACD 可被当成多份独立证据 | 主 agent 新增优化 | 首版加入证据族去相关，高于 0.80 至少两个独立证据族；不新增 DTO 或调用 |
| 同股规则批处理是否更省请求 | 当前无收益 | 真实配置 20 条规则对应 20 个不同股票；首版暂缓，出现同股多自然语言规则后再启用批处理 |

第十轮通过条件：两名原审核者确认可执行 deadline/worker quiescence、reservation 与 confirmed/upper-bound 语义、health 实例重验、beliefEvidenceBatch、逐 candidate belief 公式、Fastjson serializer feature、账号 circuit 和证据族去相关均闭合；任何 material finding 未关闭则继续修订，不编码。

## 24. 第十轮审核 finding 处置记录

第十轮 Challenge 与 Optimize 均判定不通过。以下 findings 经源码核对均属实并进入第十一版：

| Finding | 判定 | 第十一版修订 |
|---|---|---|
| Node worker settled 不能证明 detached Chrome 页面停止 | 属实 | post-marker reservation 持久化 target/browser identity；关闭 target并确认消失，失败则关闭股票专用 Browser identity；未确认时持续 fail-closed |
| Node 重启会丢失旧 worker，但 detached Chrome 仍可能生成 | 属实 | 未 ACK SUBMITTING/SUBMITTED/UNKNOWN 启动恢复为 OPEN_TRANSIENT_RECOVERY+UNCONFIRMED；重走 target/browser teardown 与 15 分钟安全窗，重启不自动放行 |
| health 与 inference 之间 instance/fingerprint 存在 TOCTOU | 属实 | inference 携带 expected instance/fingerprint；Bridge 在接收、出队、reservation、invoke 前比较，mismatch pre-submit 回 pending且本轮不重试 |
| fsync+rename 不能防多个异步 handler 的旧快照覆盖 | 属实 | 所有 journal 检查与 mutation 进入单写者 revision queue；pre-click await 自身 revision，落盘失败 fail-closed；增加 ACK/reservation 等交错测试 |
| evidencePaths 允许不可达的 priorValidation 命名空间 | 属实 | candidate 只引用 quote/currentFacts；SUPPORTED test 由 Java 直接按 metricPath贡献，不新增 DTO testRef |
| counterEvidence/FALSIFIED 可能被误计为第二支持证据族 | 属实 | 定义 supportingFamilies 唯一公式，只统计 candidate evidence 与同 target SUPPORTED_ONLY test；反证/冲突/其它 target 不计 |
| “独立证据族”措辞过强且映射变更不可审计 | 属实（minor） | 改称去相关维度；集中 path->family 表并把 policyVersion 写入 validation snapshot |
| physicalAttemptCount 与 control HTTP 容易混淆 | 属实（minor） | 数据字段改名 providerInferenceAttemptCount，control 仍单列 |

第十一轮通过条件：两名原审核者确认 browser target/browser identity teardown、重启 recovery、expected instance/fingerprint 多阶段握手、journal 单写者 revision、supportingFamilies 命名空间与正反证边界均闭合；任何 material finding 未关闭则继续修订，不编码。

## 25. 第十一轮审核 finding 处置记录

第十一轮 Challenge 与 Optimize 均判定不通过。以下 findings 经源码核对属实并进入第十二版：

| Finding | 判定 | 第十二版修订 |
|---|---|---|
| `Browser.close` 只有配置约定，没有股票专用 Chrome 所有权证明 | 属实 | continuity 固定 role/8791/9344/profile；owner record 校验 PID、启动时间、命令行 profile/port/launch token 和 browser identity；不可证明时绝不关闭 |
| 当前配置可回退通用 8789 bridge | 属实 | 新增 continuity 专用 URL/role，禁止回落 ai-rules/technical/general bridge；health role/ownership 不匹配不获取 lease |
| 进程内 revision queue 防不了两个 Node 进程共享 journal/profile | 属实 | 启动第一步对 canonical journal/profile 取得跨进程资源锁；PID/start-time 安全回收 stale lock；磁盘 baseRevision 作为第二防线 |
| reservation 后 fingerprint mismatch 被错误归为 PRE_SUBMIT | 属实 | pre-reservation mismatch 才 reservation=0/DEFERRED；reservation 后任何握手异常均 UNKNOWN、reservation=1、upperBound=1、teardown/recovery |
| evidence 与 counterEvidence 可用同一路径 | 属实（minor） | 规范化去重后要求正反证集合不相交 |
| CONFLICTED target 中单条 SUPPORTED 可能被计入 supportingFamilies | 属实（minor） | 只有 aggregate(t)==SUPPORTED_ONLY 才合并该 target 的 SUPPORTED test metric family |
| 新 disjoint 约束使旧最大 fixture 失效 | 主 agent 一致性发现 | 改为4条不同80-unit路径、6个不同60-unit claimRef；长度仍5968/7248，新 SHA-256=`56fab7...4728c6` |

第十二轮通过条件：两名原审核者确认 browser ownership 证明、continuity role/URL 隔离、journal/profile 跨进程锁与 revision CAS、reservation 前后 mismatch 分相、正反证不相交和新合法 fixture 均闭合；任何 material finding 未关闭则继续修订，不编码。

## 26. 第十二轮审核 finding 处置记录

第十二轮 Challenge 与 Optimize 均判定不通过。以下 findings 已经源码、启动脚本和 fixture 独立核对，进入第十三版：

| Finding | 判定 | 第十三版修订 |
|---|---|---|
| 最坏 fixture 的 `actual=min` 本应 match BETWEEN，且 observed=age，`INSUFFICIENT_DATA` 不可达 | 属实 | 改为 `BETWEEN[min,min] + actual=max + age=8 + observed=7`；生产 validator 先验证可达；新 SHA=`06eb0905...eb84121c` |
| 存量 detached 9344 Chrome 没有 owner record，新实现无安全接管路径 | 属实 | 增加 `LEGACY_UNOWNED_BROWSER` preflight；不自动接管/关闭；用精确 listener PID/start-time/cmdline 人工迁移 |
| API-first 在 Bridge ineligible 时是否取 lease、是否 fallback 前后矛盾 | 属实 | 固定五行路由矩阵；只有精确 API available 才可 API_ONLY lease；运输/无可用响应回 pending，不消费 tests |
| 当前 stack 同时把技术分析和 AI rules 指向 8791，仅限 continuity role 会导致技术链断或 legacy 绕过风控 | 属实 | 8791 升级为账号级 `stock-watch` broker；三个封闭 purpose 共用队列/journal/circuit；未标记请求和 OpenAI adapter 进队前拒绝 |
| owner record 只记录 spawn PID，没有证明它是 9344 实际 listener | 属实 | 首次持久及每次操作前用 OS socket 查询 listener PID/start-time，再校验 cmdline/token/profile/identity |
| safe launcher 在 Node 取锁前创建 profile 目录 | 属实 | shell 只传 canonical path，profile mkdir/setup 移到 Node 取得 profile+journal 锁之后；使用真实 launcher 竞争测试 |
| “磁盘 baseRevision CAS”过度声称为独立 CAS | 属实（minor） | 改为“锁内磁盘 revision 连续性检查”，只作为跨进程锁后的第二故障检测 |
| 用户的第二账号需要服务全部盯盘请求，而不是只服务连续意图 | 主 agent 优化 finding | 采用账号级 broker，使技术分析与意图分析共享同一实际账号限速和熔断，避免“单链路安全、整体仍超速” |

第十三轮通过条件：两名原审核者均明确确认账号级 broker 反向隔离、实际 listener 所有权、legacy 迁移、路由矩阵、取锁前零 profile mutation 和语义可达 fixture 已闭合；任何 material blocker 未关闭则继续修订，不编码。

## 附录 A：Compact DTO 最大 fixture 生成器

以下脚本是编码前的独立可复算证据；实现后必须由 Java/Fastjson 测试取代其发布门地位。

```javascript
const crypto = require('crypto');
const rep = (c, n) => c.repeat(n);
const paths = ['w', 'x', 'y', 'z'].map(c => 'aiContext.currentFacts.' + rep(c, 57)); // each 80 chars
const claimRef = i => '9223372036854775807:' + rep('k', 39) + String(i + 1); // each 60 chars
const max = '9999999999999999.99999999';
const min = '-9999999999999999.99999999';
const tests = Array.from({ length: 6 }, (_, i) => ({
  claimRef: claimRef(i), targetAxis: 'POSITIONING', targetValue: 'DISTRIBUTE',
  expectationType: 'NO_MATCH_BEFORE_EXPIRY', metricPath: paths[0],
  operator: 'BETWEEN', threshold: null, lower: min,
  upper: min, maxAge: 8, age: 8, observed: 7,
  currentVerdict: 'INSUFFICIENT_DATA', currentActual: max,
  matched: false
}));
const previousState = {
  positioningCandidates: Array.from({ length: 3 }, (_, i) => ({
    intent: ['ACCUMULATE', 'DISTRIBUTE', 'UNKNOWN'][i], beliefWeight: '0.3333',
    evidencePaths: [paths[0], paths[1]], counterEvidencePaths: [paths[2], paths[3]], reason: rep('理', 80)
  })),
  executionTactics: Array.from({ length: 2 }, (_, i) => ({
    tactic: ['SUPPRESS', 'PULL_UP'][i], beliefWeight: '1.0000',
    evidencePaths: [paths[0], paths[1]], counterEvidencePaths: [paths[2], paths[3]], reason: rep('因', 80)
  })),
  uncertainty: Array.from({ length: 3 }, () => rep('疑', 80))
};
const json = JSON.stringify({ previousState, tests });
const utf16Units = json.length;
const sha256 = crypto.createHash('sha256').update(json).digest('hex');
if (utf16Units !== 5968 || sha256 !== '06eb0905a744e60f95301c6997782c84bde2b8957b3b9171f9dedacaeb84121c') {
  throw new Error(JSON.stringify({ utf16Units, sha256 }));
}
console.log({ utf16Units, bytes: Buffer.byteLength(json), sha256 });
```
