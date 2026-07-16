# 股票盯盘统一分析与连续主力意图方案（第十六版）

日期：2026-07-15

状态：第十五轮 Challenge / Optimize 均未通过；本版等待第十六轮双审核。双通过前不得修改业务代码。

> 本文是第十六版唯一实施规范。`stock-watch-continuous-main-force-intent-plan-2026-07-14.md` 仅作为前十三轮审核历史和 intent DTO fixture 证据；第十五版已被本文覆盖，不得据其编码。

## 1. 结论先行

本期不再为“技术分析”、“异动规则判断”和“连续主力意图”发三次模型请求。对同一股票的同一待分析事实快照，它们合并为一次 `stock-analysis-v1` 响应：

1. `technicalAnalysis`：当前技术面与风险边界；
2. `ruleDecisions[]`：该股全部需要模型解释的自然语言规则判断；
3. `intentUpdate`：结合两周历史分析、上次意图状态和本轮程序验真结果，更新主力仓位目标、执行手法、反证和下次可观测 claims。

模型链路发布模式固定为 `BROKER_ONLY`：全部股票盯盘模型请求只使用第二个 DeepSeek 网页账号对应的 8791 Broker。不初始化、不探测、不调用 DeepSeek API，不回退 8789，不开放 OpenAI adapter。Broker 不可用时任务留在数据库，不换账号补请求。

## 2. 已核对的现状与容量约束

- `StockWatchMonitorService.runOnce` 当前是 `synchronized` 且按规则顺序同步执行；AI 请求会阻塞后续股票采样。
- monitor 默认 10 分钟扫描，technical analysis 默认 30 分钟扫描。
- `start-stock-watch-stack.sh` 当前把 technical 和 AI rules 都指向 8791，但 `application.yml` 两条链都仍可回落 8789。
- stock bridge 现有最小间隔 90 秒，且是在上一请求完成后再开始冷却。理论极限小于 40 次/小时，真实容量还要减去页面生成耗时。
- 以 20 只股票计，即使每只每 30 分钟只发一次，也需要 40 次/小时，已达无生成耗时的理论上限。因此在保守账号策略下，不能承诺所有 20 只股票每 30 分钟都产出一条模型分析。
- 现有产品并非只有 DAY：`StockWatchTechnicalAnalysisService.runScheduledBatch` 会按 `weeklyDue/monthlyDue` 执行 WEEK/MONTH，`runManual` 接受 `periodType`，forecast validator 要求 DAY=`T1/T5`、WEEK=`W1`、MONTH=`M1`；前端也展示日内/周度/月度 tabs。因此迁移必须保留三周期，不能用一次 DAY 合并把周/月功能删掉。

正确优化目标不是让更多 Java 线程同时等 Bridge，而是减少重复模型请求、先完成全部股票的事实采样，再在明确容量预算下公平消费。

## 3. 目标、非目标与不变量

### 3.1 目标

1. 每个唯一事实 observation 入库时，不依赖模型成功与否，都对全部 active tests 恰好执行一次程序验真。
2. 每次模型分析使用最新待处理 observation，并携带未交付的历史 test verdict，使主力意图候选随事实收窄。
3. 同股同快照的 technical/rules/intent 只产生一次模型请求和一个可审计业务提交。
4. 采样不被模型请求阻塞；消费使用持久化公平调度，保证低优先级股票不被长期饥饿。
5. 请求的不可逆提交、业务事务和 ACK 能够在崩溃后恢复，不因重试重复提交网页。

### 3.2 非目标

- 不宣称识别唯一资金主体的真实心理；只维护可被后续事实支持、削弱或证伪的行为候选。
- 不为满足“30 分钟每股一条”而缩短网页账号安全间隔。
- 不在本期实现 API fallback、多账号轮询、多股模型微批或第二次 schema 修复请求。
- 不用模型自己审核自己；claim verdict 始终由 Java 对白名单事实路径计算。

### 3.3 不变量

- I1：`observationKey=SHA-256(symbol,market,sourceTimeQuality,sourceTimeOrDate,canonicalFactSnapshot)`；禁止本地 `now`、requestId、lease fence 和随机数。唯一键保证重复采样不重复消费 tests。
- I2：事实 observation 与模型 evaluation 是两条独立时间线。网页超时、熔断、INVALID 或预算用尽不影响事实验真；模型成功才能产生新 intent state 和 new claims。
- I3：只有显式、完整且通过 sealed AST validator 的规则由 Java 直接判定。自然语言规则只在 `stock-analysis-v1` 中解释，不伪装成程序证明。
- I4：模型只能提出封闭的仓位目标、执行手法和最多 2 个可观测 claims。Java 拥有 canonical claim text、age/observed/matched/verdict 和 belief 增幅门。
- I5：本期只有 `requestPurpose=stock-analysis`，但 identity 显式区分 `DAY_CONTINUITY` 与 `PERIOD_REVIEW`；未带 role/purpose/identity/digest、未知字段、旧裸 `/search` 和 OpenAI adapter 都在进队前拒绝，reservation=0。
- I6：只有成功 durable reservation 后才可点击网页；同 `submissionKey+requestDigest` 在 journal 正常边界内最多一次不可逆提交。UNKNOWN 不重试、不伪装成已确认提交。
- I7：业务结果与 ACK outbox 必须同事务提交；首次 ACK 后还必须以 `ACK_CONFIRMED` 完成 Java/Broker 双向确认。未确认 tombstone 不因年龄自动删除，容量满时 fail-closed。
- I8：`DAY/WEEK/MONTH` 是三个独立分析身份；同一事实跨周期不得合并或复用 receipt。只有 DAY 推进连续意图和自然语言规则，WEEK/MONTH 只做相应预测周期的技术复盘。

## 4. 总体架构

```text
StockWatchSamplingService (10m，不调模型)
  ├─ 一次批量取得全部股票 quote
  ├─ 每股只构建一次 canonical currentFacts（有界数据线程池 + 单股 timeout）
  ├─ 插入唯一 fact observation
  │    ├─ Java 验收全部 active tests，写 terminal backlog
  │    └─ Java 计算 structured rule decisions
  └─ 若命中任一调度条件，按 periodType latest-wins upsert PENDING task
       ├─ 结构化异动已命中
       ├─ 存在需模型解释的自然语言规则
       └─ 该股 30m technical slot 到期

StockWatchAnalysisDispatcher (dedicated single worker)
  ├─ 取得账号级 DB dispatch lease，从 Broker health 读取 durable 预算快照
  ├─ 用防饥饿规则选一个 PENDING task，冻结 identity/prompt/digest
  ├─ 健康检查 8791 stock-watch Broker
  ├─ 以相同 key/digest 发一次 stock-analysis-v1
  ├─ 业务事务
  │    ├─ 写 technical analysis 成功/失败记录
  │    ├─ 写全部 rule decisions
  │    ├─ 写 intent evaluation；仅 SUCCESS 更新 intent head/新 claims
  │    ├─ task receipt -> BUSINESS_COMMITTED
  │    └─ ACK outbox -> PENDING
  └─ ACK worker 幂等发送 ACCEPTED / STALE_DISCARDED
       └─ DB 标记 ACKED 后发送 ACK_CONFIRMED，完成 tombstone 生命周期

8791 Stock-watch Broker
  ├─ role/capability/ownership/identity/digest 前置校验
  ├─ 账号串行队列 + cooldown + circuit
  ├─ submission journal + pre-click durable reservation
  └─ 验证过的 browser-level CDP target teardown
```

### 4.1 采样与模型消费解耦

`scheduledScan` 不得再调 `StockWatchAiJudgeService` 或等待 Bridge。它先完成全部 unique stock 的 quote/facts 采样与持久化，才返回扫描结果。市场数据增强使用有界线程池，每股超时时仍以明确 missing-data snapshot 参与 freshness 检查，不让第一只股票占住全局采样。只有越过该股 durable fact watermark 的 snapshot 才成为 accepted fact observation 并消费 tests；旧缓存快照只记录 `stale_sample` 日志/计数。

现有 30 分钟 DAY technical scheduler 不再直接遍历模型；由统一 sampler 按交易时间计算 technical slot，并把 `TECHNICAL_DUE` 合并进该股的 DAY PENDING task。异动触发和 DAY technical 到期同时发生时仍只保留一个任务。现有 WEEK/MONTH 调度语义保留，但只各自创建独立 `PERIOD_REVIEW` task；它们不能与 DAY task 合并，也不能推进连续意图。

通知不等待不必要的模型：Java structured rule 在 fact 事务成功后继续使用现有置信度/冷却/单股邮件门控；自然语言 rule 只能在对应 model evaluation 业务事务成功且不是 COMMITTED_LAGGED 时触发通知。通知去重键携带 ruleId+observationId，不会因 task/ACK 恢复重发。

### 4.2 事实先行的 test ledger

插入 `stock_watch_fact_observation` 的单事务内：

1. 以唯一 observationKey 去重；重复 key 直接返回，不改 test age。
2. 读取该股全部 PENDING tests，对白名单 metricPath 重取 actual。
3. 每个新 observation 使 age+1；字段存在使 observed+1。先判定性 match，再判断到期与数据覆盖。
4. 写入不可变 observation-test result，并以 test version CAS 更新 test。
5. 终态 verdict 进入未交付 backlog。只有后续 SUCCESS model evaluation 在 prompt 中完整看到它并事务提交后，才推进 delivery high-water。
6. 在同一 fact 事务内读取并规范化完整 natural-rule snapshot，按 ruleId 排序，冻结 `ruleSnapshotJson/sortedRuleIds/ruleSetHash` 到 fact；task 只能复制这份快照，lease 时不得重读规则。规则并发编辑只影响下一个 accepted fact，不能形成“旧 key + 新 prompt”。
7. 对每个应调度周期生成 `analysisIntentKey=SHA-256(canonical {observationId,periodType,periodKey,requiredForecastHorizons,intentUpdateMode,ruleSetHash,technicalSlot,normalizedReasonCodes})`，并按 periodType latest-wins upsert task。fact/head/test-result/test-CAS/structured decisions/冻结规则/task upsert 在同一事务；任何一步失败全部回滚。
8. 如果该股已有 `LEASED` 或 `PROVIDER_COMPLETED` DAY task，任何 comparator=NEWER 的 fact 都必须额外 upsert 一条 P2 DAY 后继任务，reason=`INFLIGHT_SUCCESSOR`；即使没有规则命中且 technical slot 未到期也不能省略。该后继任务承接最新 fact 和全部未交付 terminal backlog。

duplicate 分支不只是返回：它在行锁内检查既有 fact 的 `analysisIntentKey/analysisDisposition/linkedTaskId`。如果应产生 task 但 disposition 仍为 `MISSING`或 linked task 不存在，在同事务修复 upsert；已经 `PENDING/LEASED/COMMITTED/COALESCED` 时不重建。PENDING 被新 observation 替换时，旧 fact 的 disposition 事务化改为 `COALESCED` 并保留 linked task，避免重复采样把被有意合并的旧快照重新入队。

因此模型运输失败不会重复消费事实，也不会阻止历史 claim 被新事实验证。新 claims 从 SUCCESS evaluation 创建，`originObservationId` 等于当前 observation，最早只能从下一个不同 observation 开始验收，不允许创建轮自证。

### 4.3 公平 dispatcher 与可达 SLA

Java 只有一个 stock-watch model worker，它使用专用 executor，不占用 Spring 其它 `@Scheduled` 任务的执行线程。多后端进程下，先用 DB singleton dispatch lease 确保只有一个工作者可向 Broker 发请求；数据库事务不跨越 HTTP。

任务选择规则固定：

1. `priorityClass` 不由调用方自由填写，而由 sealed `reasonCodes` 唯一派生：含 `ANOMALY_TRIGGERED` 为 P0；否则含 `TECHNICAL_DUE/NATURAL_RULE_DUE/MANUAL_REQUESTED/PERIOD_REVIEW_DUE` 任一项为 P1；只有 `INFLIGHT_SUCCESSOR` 为 P2。派生值进入 identity，Broker 独立重算，不一致时 reservation=0。除在途后继外，本期不为 backlog 单独创建 P2。
2. 如果任何任务等待超过 `starvation-escalation-minutes=45`，它进入全局 oldest-first 候选。这不是“45 分钟必定完成”的 SLA；它只保证在 budget/circuit/cooldown 允许的下一个名额中，选取所有 escalated 任务中每股 durable `last_model_reserved_at` 最旧的一只。
3. 没有 escalated 任务时，允许最多 `max-urgent-burst=3` 个 P0 连发；第 4 个必须从全局按 `fact_head.last_model_reserved_at NULLS FIRST, task.first_pending_at, symbol` 取最旧任务。
4. 其它时候按 `priorityClass, fact_head.last_model_reserved_at NULLS FIRST, task.first_pending_at, symbol` 选择。候选 SQL/内存筛选必须先应用 Broker health 返回的当前滚动预算 `eligiblePriorityClasses/nextEligibleAt`，不能反复把不合格 P1 送给 Broker而堵住 P0。若 reservation 因 health 后预算已变化返回 `BUDGET_SNAPSHOT_STALE`，dispatcher 在同一 tick 刷新 health、排除当前不合格类别并选择另一个合格候选；每类每 tick 最多尝试一次，全部不合格才休眠到最早 nextEligibleAt，不 busy-loop。task 从不承担跨任务公平性记忆；Broker 返回 durable reservation=1 后，Java 以 task token/fence 在一个短事务中更新 `fact_head.last_model_reserved_at`、task reservation trace 和 dispatch urgentBurstCount。响应丢失时由 Broker journal 中的 reservation timestamp 恢复并回填，不把该股当成“从未服务”。

账号预算默认为 `max-reservations-per-rolling-60m=20`、`max-reservations-per-rolling-24h=80`，其中任一滚动 60 分钟为 P0 保留 4 个名额，非 P0 最多使用 16 个。Broker journal 保留最近 24 小时每个 durable reservation 的 `{submissionKey,reservedAtEpochMs,priorityClass}`，每次在同一 `mutateJournal` 中剪枝窗口外记录、计算滚动 60m/24h 余额、增加 reservation 并写 SUBMITTING marker；其中任一持久化失败都不点击。因此 10:59 + 11:00 不能绕过 60 分钟上限。

Broker 同时持久化 `lastBudgetWallClockMs`。若当前 wall clock 小于该值，进入 `OPEN_TIME_SKEW`并 fail-closed，直到时钟追平或本地受保护管理接口人工确认；不用“取较大值”伪造时间前进。这使“已 reservation/点击、Java 未收到响应就崩溃”仍被计入预算。UNKNOWN 不退回额度，FAILED_PRE_SUBMIT 不消费。Java DB 只保存 Broker 返回的预算快照用于审计，不是权威计数器。这是可配置安全上限，不是吞吐承诺；Broker 的 90 秒冷却和真实生成耗时仍可使实际吞吐更低。

状态接口必须暴露：`pendingUniqueStocks`、各优先级数量、`oldestPendingAge`、滚动 60m/24h reservation 时间戳摘要、各优先级余额/nextEligibleAt、近 20 次真实 provider elapsed p50/p95，以及 `nextEligibleCoverageEstimate`。该估算不是 SLO，也不使用“当前小时预算”除法；它从 Broker journal 的 reservation timestamps 起，按滚动 60m/24h 上限、P0 保留、当前 cooldown/circuit、p95 provider duration 和 pending unique stocks 做离散事件模拟，输出 `earliestCompletionAt/latestEstimatedCompletionAt`。若 circuit 非 CLOSED、未来预算窗口不可推导、容量为 0 或待处理集合持续增长导致上界不可计算，返回 `UNBOUNDED` 和原因。样本少于 20 次时以 request hard timeout 作为保守 p95。页面和日志不得把 30 分钟配置显示成“所有股票每 30 分钟必定产出”；`starvation-escalation-minutes` 只是选队提升阈值。

## 5. 数据模型

### 5.1 `stock_watch_fact_observation`

一行代表一个程序已接受的事实快照，不表示模型已分析。

| 字段 | 含义 |
|---|---|
| id, symbol, market, stock_name | 主键与股票 |
| observation_key UNIQUE(symbol,market,key) | 规范事实幂等键 |
| source_time/date/quality, fact_hash | 上游时间与事实哈希 |
| arrival_version | 该股 fact head 分配的单调 observationVersion，用于 model evaluation 提交顺序 |
| fact_snapshot_json, data_quality_json | 白名单事实与缺口 |
| structured_rule_decisions_json, rule_snapshot_json, sorted_rule_ids_json, rule_set_hash | fact 事务内冻结的 Java 决定与完整规范规则集；后续 lease 不重读配置 |
| technical_slot, scheduling_reasons_json | 任务合并原因 |
| analysis_links_json | 按 periodType 保存 analysisIntentKey/disposition/linkedTaskId；disposition=`NONE/MISSING/PENDING/LEASED/COMMITTED/COALESCED` |
| sampled_at, created_at | 采样审计时间，不进 observationKey |

### 5.2 `stock_watch_fact_head`

每股一行，保存 `arrival_version/latest_fact_observation_id/last_source_time/last_source_date/last_source_quality/last_fact_hash/last_model_reserved_at`。`last_model_reserved_at` 是跨 task 的永久公平性水位，不随 receipt 完成/清理丢失。sampler 先原子增加 arrivalVersion，再在行锁事务中按以下固定表判断 freshness：

- 日期可比时，较早日期 stale，较晚日期 newer。
- 同日均为 INSTANT 时比较完整时间；相同时间同 hash 是 duplicate，相同时间异 hash 按更大 arrivalVersion 作为后到修正。
- 同日 INSTANT 可接管 TRADE_DATE；TRADE_DATE 不能倒退覆盖 INSTANT。
- 同日均为 TRADE_DATE 或均为 NONE 时只按 arrivalVersion 表示“数据库到达顺序”，不宣称交易所时间更新。
- 已有日期 watermark 时 NONE 不能接管；NONE watermark 可被有日期 snapshot 接管。

只有 comparator=NEWER 才在同事务插入 fact observation、推进 tests 和 watermark。DUPLICATE/STALE/UNPROVABLE 不插入 accepted fact、不消费 tests、不创建模型 task。

### 5.3 `stock_watch_analysis_task_receipt`

该表同时是有界 latest-wins 队列、不可变 dispatch task 和 Broker 业务 receipt。每只股每个 `periodType` 最多同时一条 `LEASED` 和一条 `PENDING`；新 observation 只替换同 periodType 的 PENDING observationId，合并最高优先级与 reasons，保留最旧 firstPendingAt 和 coalescedCount。账号级 dispatcher 仍只允许一个 provider in-flight。

| 字段 | 含义 |
|---|---|
| id, symbol, market, observation_id | 业务 task |
| period_type, period_key | `DAY/WEEK/MONTH` 与既有 period window key；跨周期不合并 |
| required_forecast_horizons_json, intent_update_mode | DAY=`[T1,T5] + CONTINUITY_UPDATE`；WEEK=`[W1] + TECHNICAL_ONLY`；MONTH=`[M1] + TECHNICAL_ONLY` |
| active_slot | `PENDING/LEASED/null`；唯一索引 `(symbol,market,period_type,active_slot)` |
| priority_class, reasons_json, first_pending_at, coalesced_count | priority 由 reasons 唯一派生；跨 task 公平性使用 fact_head.last_model_reserved_at |
| analysis_intent_key UNIQUE | fact 与 task 的幂等交接键 |
| rule_snapshot_json, sorted_rule_ids_json, rule_set_hash | 从 fact 原样复制的冻结规则，禁止 lease 时刷新 |
| lease_token/fence/until | 不跨 HTTP 事务的任务租约 |
| contract_version | 固定 `stock-analysis-v1` |
| submission_identity_json, submission_key | 冻结的 sealed identity 与派生 key |
| request_payload_json, request_digest | 冻结 prompt 与完整请求哈希；不含 Cookie/凭据 |
| broker_state, reservation/confirmed/upper_bound | Broker 请求追踪 |
| business_state | `PENDING/LEASED/PROVIDER_COMPLETED/BUSINESS_COMMITTED/COMMITTED_LAGGED/INVALID_RESPONSE/STALE_DISCARDED/WAITING_RECOVERY` |
| technical_analysis_id, intent_evaluation_id, result_digest | 业务提交回执 |
| ack_state, ack_disposition, ack_attempts, acked_at, ack_confirm_state, ack_confirmed_at | 同表 durable ACK 与 ACK_CONFIRMED outbox |
| next_retry_at, last_error_code | 只允许 PRE_SUBMIT 的有界重试/退避 |

### 5.4 `stock_watch_broker_dispatch_state`

单例行保存跨进程 dispatch token/fence/leaseUntil、urgentBurstCount，以及生产 `maintenance_mode/token/fence/lease_until`，所有 lease 判断使用 DB 时钟。maintenance active 时 sampler/dispatcher 在启动、取 task 和 reserve 前全部 fail-closed，不能靠已有 dispatch lease 穿透。账号预算的权威状态在 Broker journal，不在此行；此行可镜像最近 health 预算快照供运行查询。dispatch lease 下限根据当前 health 的 queue timeout + hard timeout + commit margin 计算，过期 worker的 token/fence 无法提交 task。

### 5.5 意图表

- `stock_watch_intent_head`：每股最新 SUCCESS evaluation、stateVersion、terminal-delivery high-water 和 session generation；不再存 ownerRuleId，不承担 fact freshness watermark。
- `stock_watch_intent_evaluation`：observationId/observationVersion、previousEvaluationId、technicalAnalysisId、全部 rule snapshots/decisions、priorValidation、intent result、prompt/request/response hash、provider trace、status。
- `stock_watch_intent_test`：claimRef、封闭 target/operator/metric、age/observed/verdict/version、originObservationId、activationAfterObservationId、lastEvaluatedObservationId。
- `stock_watch_intent_test_result`：每个 observation-test 的 actual/matched/verdict 不可变明细，唯一 `(observation_id,test_id)`。

### 5.6 Broker journal 控制状态

Broker journal 除 reservation/receipt/circuit 外，持久化唯一 `exclusiveSmokeLease={executionScope,tokenHash,fence,expiresAt,lastHeartbeatAt}`。生产 request 在 lease active 时一律 reservation=0；smoke request 必须携带原 token 和 fence，Broker 只存 token hash。TTL 到期但存在该 scope 的 `SUBMITTING/SUBMITTED/UNKNOWN` 时不删除 lease，而转为 `EXPIRED_UNSAFE` 并保持账号 fail-closed。只有无 unsafe in-flight 且全部 receipt 已 ACK_CONFIRMED 时，持 fence 的 release 才生效。

## 6. Broker 协议、幂等与 ACK

### 6.1 Sealed submission identity

Java 在取得 task lease 后冻结以下唯一 identity，未知/缺失字段 fail-closed：

```json
{
  "contractVersion": "stock-analysis-v1",
  "purpose": "stock-analysis",
  "executionScope": "production",
  "symbol": "002594",
  "market": "sz",
  "observationKey": "<64 lowercase hex>",
  "factSnapshotHash": "<64 lowercase hex>",
  "ruleSetHash": "<64 lowercase hex>",
  "periodType": "DAY",
  "periodKey": "2026-07-15",
  "requiredForecastHorizons": ["T1", "T5"],
  "intentUpdateMode": "CONTINUITY_UPDATE",
  "technicalSlot": "2026-07-15T10:30+08:00",
  "priorityClass": "P0",
  "sessionNamespace": "production:sz:002594",
  "sessionGeneration": 0,
  "reasonCodes": ["ANOMALY_TRIGGERED", "NATURAL_RULE_DUE", "TECHNICAL_DUE"]
}
```

`technicalSlot` 可为 JSON null，但不得省略。`reasonCodes` 规范去重并按 enum 字典序排列；`priorityClass` 必须由它们按 4.3 的封闭表派生，Java 和 Broker 各自计算。`periodType/periodKey/requiredForecastHorizons/intentUpdateMode` 必须精确匹配：DAY 使用交易日 key、`[T1,T5]`、`CONTINUITY_UPDATE`；WEEK 使用现有 `currentPeriodWindow(...).key`、`[W1]`、`TECHNICAL_ONLY`；MONTH 使用 `yyyy-MM`、`[M1]`、`TECHNICAL_ONLY`。DAY 使用 fact 中冻结的完整 rule snapshot/hash；WEEK/MONTH 的 rule snapshot 固定为空数组，ruleSetHash 固定为 canonical empty-set hash。`executionScope` 生产固定 `production`；隔离冒烟使用先在 smoke DB 持久化的 `smoke:<runId>`。`sessionNamespace={executionScope}:{market}:{symbol}:{periodType}`，generation 只在持久化 session 轮换时增加。task lease 只能复制并验证这些冻结字段，不能重读规则。

不使用变长字段裸拼接。Java 和 Node 共用一个封闭 canonical request envelope：

```json
{
  "contractVersion": "stock-analysis-v1",
  "submissionIdentity": { "...": "上述完整对象" },
  "request": {
    "query": "<exact prompt>",
    "forceNewSession": false,
    "expectedJson": true,
    "mode": "stock-analysis"
  }
}
```

本协议只包含字符串、布尔、非负十进制整数、null、封闭对象和字符串数组。序列化规则固定：所有字符串先 NFC，`query` 把 CRLF/CR 统一为 LF；对象 key 按 Unicode code unit 升序；null 必须保留；reasonCodes 和 rule snapshots 在进入 envelope 前按协议 key 排序；JSON 使用 RFC 8259 最短必要转义，不转义 `/` 或非 ASCII；无空白；最终取 UTF-8 bytes。`submissionKey=stock-analysis-v1:SHA-256(canonical identity bytes)`，`requestDigest=SHA-256(canonical request envelope bytes)`。Java 把 identity/envelope/key/digest 先持久化；Broker 使用同一规则重算，不只检查字符串格式。共享 golden vectors 覆盖 CRLF/LF、Unicode 组合字符、null、字段顺序、引号/反斜杠、分隔符碰撞和整数边界。

第一次对 key 做 durable reservation 时把 requestDigest 与 key 绑定。相同 key 但 digest 不同返回 `IDENTITY_DIGEST_CONFLICT`，不提交、不新建 session。代码发布后对已冻结 task 只能复用原 payload，不能用新 prompt 改写它。

### 6.2 Receipt / ACK 状态表

| 时点 | DB task/receipt | Broker journal | 恢复行为 |
|---|---|---|---|
| HTTP 前 | LEASED，identity/payload/digest durable | 无或 FAILED_PRE_SUBMIT | 可用同 key/digest 尝试；总 PRE_SUBMIT 重试最多 1 次 |
| reservation 后、响应前 | LEASED/WAITING_RECOVERY | SUBMITTING/SUBMITTED/UNKNOWN | 同 key 只查缓存/恢复，不再点击 |
| Broker COMPLETED、DB 前 | PROVIDER_COMPLETED 或崩溃前仍 LEASED | COMPLETED 缓存 answer | 重启后同 key 取缓存并完成业务事务 |
| 业务 commit | BUSINESS_COMMITTED/INVALID_RESPONSE/STALE_DISCARDED + ack=PENDING | COMPLETED | 业务记录和 ACK outbox 在同一事务 |
| 首次 ACK HTTP 后、DB 标记前 | ack=PENDING | ACKED_UNCONFIRMED + canonical tombstone | ACK 幂等重发，Broker 返回相同 receipt/hash；tombstone 不按年龄删除 |
| DB 已标记 ACKED、confirm 前 | ack=ACKED, confirm=PENDING | ACKED_UNCONFIRMED | confirmation worker 发送 ACK_CONFIRMED |
| 最终 | ack=ACKED, confirm=CONFIRMED | ACK_CONFIRMED | 最短 24h 后才可清理 answer；compact tombstone 仍按审计 TTL 保留 |

ACK 必须携带 `submissionKey, taskReceiptId, disposition, businessState, resultDigest, committedRecordIds`，其中 record IDs 按 `{recordType,id}` 排序。Broker 对封闭 ACK envelope 使用与请求相同的 canonical JSON 规则计算 `ackPayloadHash`；首次 ACK 把 hash 与 key 持久绑定，并返回不可伪造随机 `ackReceipt`。后续只有 envelope 完全一致才是幂等 ACK；同 key 不同 hash 返回 `ACK_PAYLOAD_CONFLICT`并 fail-closed。Java 收到首次 ACK 后事务化标记 `ack=ACKED, confirm=PENDING`，confirmation worker 再发送 `{submissionKey,taskReceiptId,ackReceipt,ackPayloadHash}`。Broker 仅在四项精确匹配时写 `ACK_CONFIRMED`。未 confirmed 的 compact tombstone 永不因年龄自动删除，journal 达容量上限时拒绝新 reservation；confirmed 后至少 24 小时才可清 answer，compact key/hash/receipt tombstone 按更长审计 TTL 保留。`ACCEPTED` 包含已持久化的 `INVALID_RESPONSE`，不代表模型结论有效；`STALE_DISCARDED` 必须先落 DB。UNKNOWN 和未持久结果绝不 ACK/confirm。

### 6.3 网页所有权与恢复

- Broker role 固定 `stock-watch`，端口 8791，debug port 9344，canonical profile/journal 固定。health 暴露 `capability=stock-analysis-v1`、instance/fingerprint/deadline/circuit/ownership。Java 独立比较所有字段。每个 inference 在 canonical envelope 外单独携带 `expectedInstanceId/expectedConfigFingerprint/expectedRole/expectedCapability`；它们不进业务 requestDigest，使 reservation 前的安全重启可以明确返回 mismatch。Broker 在 HTTP 接收后、排队出队后、durable reservation 前三次比较 tuple；任一 mismatch 返回 `INSTANCE_MISMATCH_PRE_SUBMIT`、reservation=0。reservation 后所有权/实例异常只能 UNKNOWN + recovery，不得回报 PRE_SUBMIT。
- Node 在任何 profile mkdir、journal read/mutation、Chrome connect/listen 前，先按稳定顺序取 canonical profile/journal 两把跨进程 lock directory。锁内对磁盘 revision 做连续性检查，不夸大为独立文件 CAS。
- Chrome 启动携带 launch token。debug ready 后在 browser WebSocket 握手前后各用 OS socket 查询一次 9344 实际 LISTEN PID/start-time，两次必须相同；再验证 cmdline 精确包含 profile/port/token，并通过该 WebSocket 调用 `Browser.getVersion` 取 identity，才持久化 owner record。health/connect/teardown 前都重验。
- 存量 9344 endpoint 活着但没有合法 owner record 时进入 `LEGACY_UNOWNED_BROWSER`：不接管、不关闭、不监听 8791。运行手册只允许在 socket PID/start-time/cmdline 精确证明为旧 stock profile 后终止该 PID，禁止宽泛 `pkill Chrome`。
- target 的创建、枚举、attach 和关闭全部使用同一条已验证 browser-level CDP WebSocket：`Target.createTarget`、`Target.getTargets`、`Target.attachToTarget`、`Target.closeTarget`；任何 ownership 建立后都禁止调用 debug-port HTTP `/json/new`、`/json/list`、`/json/close`。每次 create 前重验该连接的 browser identity 与 listener PID/start-time；重验失败 reservation=0。post-marker abort 在同一 WebSocket close 并确认 target 消失；只有 browser identity、listener PID/start-time 与 reservation ownership snapshot 仍匹配且 target close 失败时，才允许同连接调用 `Browser.close`。关闭后确认 PID/start-time 消失且 9344 不可达。
- 未确认 teardown 的第一个 post-marker 超时立即进入 `OPEN_TRANSIENT_RECOVERY`；确认 target/browser 消失后仍等待 15 分钟，才 HALF_OPEN 单探针。login/risk/ban 进 `OPEN_AUTH`，只能人工复位。

## 7. 统一模型协议与连续意图

### 7.1 输入与周期模式

- 本轮 `quote/currentFacts/dataQuality`；
- sealed `periodType/periodKey/requiredForecastHorizons/intentUpdateMode`；模型只能输出该周期要求的 forecasts；
- `DAY + CONTINUITY_UPDATE`：输入 fact 中冻结的全部规则，结构化规则携带 Java decision，自然语言规则待模型判断；无上一 SUCCESS intent 时输入最近 14 天全部成功 DAY 日内分析槽位，时间升序，最多 48 条/16,000 UTF-16 units；有上一状态后只输入 compact previousState、最多 6 个 active/terminal tests 和必要转折记录；Java 由全部 observation-test result 生成 `priorValidation.generatedReview`，模型不得改写；
- `WEEK/MONTH + TECHNICAL_ONLY`：只输入事实、该周期既有技术历史与 required horizons，不输入/推进 intent state，不判断 natural rules。这样同一 fact 不会因三个周期被重复算作三次意图观察。

### 7.2 输出

```json
{
  "technicalAnalysis": {
    "title": "...",
    "conclusion": "...",
    "evidence": [],
    "risks": [],
    "watchPoints": [],
    "forecasts": []
  },
  "ruleDecisions": [
    {
      "ruleId": "...",
      "triggered": false,
      "confidence": 0.0,
      "severity": "risk",
      "shouldNotify": false,
      "reason": "...",
      "triggerFactPaths": []
    }
  ],
  "intentUpdate": {
    "positioningCandidates": [],
    "executionTactics": [],
    "newClaims": [],
    "uncertainty": []
  }
}
```

DAY 模式要求 `ruleDecisions` 与 fact 冻结的自然语言 ruleId 集合精确相等，每个 triggerFactPath 都在当前白名单事实中重取 actual；历史文字和 priorValidation 不能成为触发 fact path。WEEK/MONTH 模式则强制 `ruleDecisions=[]`、`intentUpdate=null`，否则该子模块 INVALID；其 technical forecasts 必须分别精确覆盖 W1 或 M1。DAY technical forecasts 必须精确覆盖 T1/T5。`technicalAnalysis` 复用并收紧现有技术分析 v1 的校验；持久化时补充 `analysis_task_id/fact_observation_id/period_type/period_key/trigger_source`，使异动时产生的技术分析与 DAY intent/rule decisions 追溯到同一 task。

intent DTO 继续使用前案第 6.1/7/8.1 节的封闭双轴、claim union、evidence/counter disjoint、UTF-16 字段上限、Fastjson `WriteMapNullValue`、最多 6 tests 和 belief 增幅门。可执行最坏 fixture 必须继续得到 `5968 UTF-16 / 7248 bytes / SHA-256 06eb0905a744e60f95301c6997782c84bde2b8957b3b9171f9dedacaeb84121c`，且先通过生产 validator 证明其 `INSUFFICIENT_DATA` 语义可达。

模型响应只解析一次，不发 schema repair。处理边界：

- Broker 未 reservation 即失败：task 有界退避，只允许一次同 key/digest PRE_SUBMIT 重试；事实 tests 早已验真。
- reservation 后 UNKNOWN：不重试，task WAITING_RECOVERY，账号熔断；事实 tests 早已验真。
- COMPLETED 但顶层不可解析：持久化 raw hash/trace 和 `INVALID_RESPONSE`，不更新 intent/不创建 claims，以 ACCEPTED ACK 清理 Broker 缓存。
- 顶层可解析但子模块无效：每个子模块独立校验并持久化 status；只有 intent SUCCESS 更新 intent head/创建 claims。无论有效与否都不二次请求。
- DAY 模型返回期间只是有更新 fact observation：仍把冻结 observation 的 technical/rules/intent 作为历史 evaluation 持久化，标记 `COMMITTED_LAGGED`。只要 task token/fence 仍有效且尚无更高 observationVersion 的 SUCCESS model evaluation 提交，它可作为“最新已分析状态”更新 intent head；但当前展示和通知门控必须对比 fact head，lagged rule decision 不发实时通知。4.2 保证任何新 fact 已原子创建 `INFLIGHT_SUCCESSOR` P2 task，所以 commit 不能假设“稍后自然有规则/slot”来延续链条；若后继缺失则当前 commit fail-closed 并修复 task，不能完成为无后继 lagged。
- 新 claims 的 `activationAfterObservationId` 固定为业务 commit 事务内读到的 `fact_head.latest_fact_observation_id`，而不是冻结的旧 observationId。因此模型未见过的已到达 facts 不会被追溯用于新 claim，新 claim 从其后第一个不同 fact 开始验收。
- task token/fence 已失效且新 owner 已接管同 task 时，旧 worker 只丢弃自己的提交尝试，不写 DB、不 ACK；新 owner 以同 key 取 Broker 缓存后提交。只有 DB 已存在更高 observationVersion 的 model evaluation 成功提交时，才把旧结果持久化为 `STALE_DISCARDED` 并同事务写 stale ACK outbox。

## 8. 配置和代码改动边界

### 8.1 配置

```yaml
stock-watch:
  analysis:
    mode: BROKER_ONLY
    sampling-interval-ms: 600000
    technical-slot-minutes: 30
    starvation-escalation-minutes: 45
    max-urgent-burst: 3
    max-reservations-per-rolling-60m: 20
    urgent-reservations-per-rolling-60m: 4
    max-reservations-per-rolling-24h: 80
  broker:
    url: http://127.0.0.1:8791/search
    role: stock-watch
    capability: stock-analysis-v1
    debug-port: 9344
    profile-root: ${user.home}/.aimeeting-room/deepseek-web-stock-profile
    submission-journal: ${user.home}/.aimeeting-room/stock-watch-bridge-submissions.json
```

`STOCK_WATCH_AI_RULES_BRIDGE_FIRST`、`STOCK_WATCH_AI_RULES_BRIDGE_URL`、`STOCK_WATCH_TECH_BRIDGE_URL` 不再决定股票请求路由。启动脚本只传 `STOCK_WATCH_BROKER_URL/ROLE/CAPABILITY/DEBUG_PORT/PROFILE/JOURNAL`。若发现旧环境变量尝试把 stock-watch 指向 8789/9333，启动失败而不是静默兼容。

### 8.2 新增/修改

- 新增 `StockWatchSamplingService`、`StockWatchAnalysisQueueService`、`StockWatchAnalysisDispatcher`、`StockWatchAnalysisCommitService`、`StockWatchBrokerClient`、`StockWatchBrokerAckWorker`、`StockWatchBrokerAckConfirmationWorker`。
- 新增第 5 节八类表的 entity/mapper/schema initializer/SQL，其中 task receipt 同时承担 ACK outbox。技术分析表增加 task/fact/trigger source 可追溯字段。
- `StockWatchMonitorService`：移除同步逐规则模型调用；改为触发 sampler 并返回 task/evaluation 状态。
- `StockWatchTechnicalAnalysisController.runManual`：不再同步调 service/Bridge；继续接受现有 `DAY/WEEK/MONTH`，读取当前 accepted fact 的冻结规则并按所选 period 生成独立 intent key，enqueue `MANUAL_REQUESTED`，返回 HTTP 202 + taskReceiptId/status URL。重复手工请求只复用同 periodType/periodKey 的 task，跨周期不得复用，不绕过预算。
- `StockWatchTechnicalAnalysisService`：保留数据组装、DAY/WEEK/MONTH period window、解析和查询能力；现有周/月 cadence 改为 enqueue `PERIOD_REVIEW_DUE`，删除独立定时模型遍历、直接 HTTP Bridge 调用和 schema retry，由统一 commit service 写入分析与 T1/T5/W1/M1 forecast。
- `StockWatchAiJudgeService`：改为构建/校验一个 `stock-analysis-v1`；同股多规则合并一次输入；不调 AiModelRouter/API。
- `StockWatchAiRuleService`：增加 sealed structuredPredicate AST；只对完整合法 AST 做 Java 判断。
- `scripts/deepseek-web-bridge.js`、`deepseek-web-client.js`、safe start/stop/stack scripts：实现 stock-watch role、单 purpose、journal/circuit/ownership/legacy preflight，关闭 adapter。
- `scripts/stock-watch-ai-rule-expert-smoke.js` 与所有 stock-watch smoke：删除 8789 fallback 和裸 `/search`；通过后端只 enqueue/poll receipt，并断言 Broker reservation 是新增而非命中旧缓存。
- `frontend/src/views/StockWatch.vue`：手动分析改为处理 202 receipt 并轮询状态；不保持一个可同步直连技术 service 的入口。
- 新增架构约束测试：`service/controller/scripts/frontend` 中只允许 `StockWatchBrokerClient` 封装 8791 模型 HTTP；禁止 stock-watch 代码出现 8789/9333、旧 Bridge URL 回退、OpenAI adapter 或第二 schema request。hostile 环境变量把旧 URL/port 设为 8789/9333 时，应用启动失败且 Broker reservation=0。
- 不修改 `ModelRegistryStore`、`AiModelRouter`、`GenericAiClient` 的 V4 API 路由；前案这部分从本期删除。

## 9. 测试方案

### 9.1 事实与队列

1. 同 observationKey 多次采样只插入一条 fact，每个 active test 只增加一次 age/observed。
2. 无 Broker、OPEN_AUTH、超预算、provider timeout 和 INVALID response 下，新 fact 仍正确生成 test result/terminal backlog，不产生新 intent state。
3. `NO_MATCH_BEFORE_EXPIRY/MATCH_BEFORE_EXPIRY` 的决定性 match、到期完整覆盖和 INSUFFICIENT_DATA 边界全覆盖。
4. 模型成功后创建的 claim 不得在 origin observation 验收，下一不同 fact 才可。
5. freshness 比较表全覆盖：10:05 INSTANT 后到的 10:00 不消费 tests；INSTANT 不被同日 TRADE_DATE/NONE 覆盖；同时间事实修正按 arrivalVersion 接管；多进程行锁下只有一个 NEWER 提交。
6. 采样先完成全部 20 只股票入库，模型工作者仍阻塞时不影响后续股票采样。
7. 同股 DAY technical + structured anomaly + 多个 natural rules 同时到期只有一个 DAY PENDING task 和一次 Broker submission；同 fact 的 WEEK/MONTH 分别是独立 task/key，不能与 DAY 或彼此 merge，forecast horizons 精确为 DAY T1/T5、WEEK W1、MONTH M1。
8. fact/head/test-result/test-CAS/structured decisions/frozen rule snapshot/task upsert 任一故障均整体回滚；注入“fact 逻辑完成后 task upsert 前崩溃”不能留下孤立 fact。规则在事务提交前后并发编辑时，旧 fact/key/prompt 必须全用旧 snapshot，新规则只进入下一 accepted fact。duplicate 分支可修复 MISSING linked task，但不会重建已 COALESCED 的旧 intent。
9. 同一 analysisIntentKey 只能对应一个 active/历史 receipt；同 periodType LEASED 期间新 fact 只创建/替换一条 PENDING next，保留最高优先级、最旧 firstPendingAt 与 coalescedCount。精确覆盖：无规则命中、slot 未到期但 LEASED/PROVIDER_COMPLETED 时到达新 fact，仍创建 reason=`INFLIGHT_SUCCESSOR` 的 P2 DAY 后继。
10. task A 完成并清理后同股重新入队 B，仍使用 fact_head.last_model_reserved_at 排序，不回到 NULL。持续容量存在时，escalated 全局 oldest 必须占用下一可用名额；不断言 45 分钟内完成。多进程只有一个 dispatch lease。
11. 滚动 60 分钟非紧急 16、紧急预留 4、总量 20 与滚动 24 小时总量 80 在 Broker journal reservation mutation 中原子验证/扣减。覆盖 10:59/11:00 跨整点、Broker 重启、wall clock 回拨进 OPEN_TIME_SKEW、marker durable 后 Java 崩溃仍扣额度、UNKNOWN 不退回和 FAILED_PRE_SUBMIT 不消费。精确构造“16 个 P1 已消费 + 最老 escalated P1 + 等待 P0”：dispatcher 必须过滤 P1 并成功 reserve P0；priorityClass 伪造必须被 Broker 拒绝。health 后预算变化时同 tick 刷新并选其它 eligible 类，不 busy-loop。
12. `nextEligibleCoverageEstimate` 用 reservation timestamps 模拟滚动 60m/24h、P0 reserve、cooldown 和 p95；24h 额度接近耗尽时不得仍报 60 分钟完成，应给出接近真实释放时间的范围或 UNBOUNDED。

### 9.2 Broker / receipt / 所有权

13. Broker 对无 role/purpose/identity/digest、未知 identity 字段、key/digest 不匹配、裸 `/search`、OpenAI adapter 和 8789/9333 全部在进队前拒绝，reservation=0。
14. Java 与 Node 共享 canonical vectors，覆盖 CRLF/LF、NFC、null、字段顺序、转义、分隔符碰撞、periodType/periodKey/horizons/mode/priority 和 generation，重算 identity key/requestDigest；同 key 不同 digest fail-closed。
15. health 后 Broker 重启/变更 fingerprint 时，接收、出队、reservation 前任一 expected tuple mismatch 都是 reservation=0；reservation 后 mismatch 只能 UNKNOWN/recovery。
16. 覆盖 PREPARED，reservation 后崩溃，COMPLETED 后 DB 前崩溃，DB commit 后 ACK 前崩溃，首次 ACK 后 Java DB 标记前崩溃、DB ACKED 后 confirm 前崩溃。同 key 从不二次点击，业务记录与 outbox 不分裂。模拟停机超过 24h，未 confirmed tombstone 仍可返回相同 ACK receipt 并完成确认，不能被 age cleanup 删除。
17. 同 key 同 canonical ACK/CONFIRM 幂等；任一 disposition/resultDigest/receiptId/businessState/排序 record IDs/ackReceipt/hash 不同时返回冲突，不清理 answer。
18. 顶层不可解析也要持久 INVALID_RESPONSE + result digest 后 ACK/confirm；UNKNOWN 绝不 ACK。
19. 模型期间仅有新 fact 到达时，旧结果作为 COMMITTED_LAGGED 历史评估提交，lagged decision 不实时通知；事务必须看到 P2 successor，缺失时 fail-closed 修复。新 claim 从 commit-time latest fact 之后激活。只有已有更高 model evaluation 时才 STALE_DISCARDED。
20. safe launcher 两进程竞争时，第二进程在 profile mkdir/journal read/browser connect/listen 前退出；stale lock 只按 PID+start-time 回收。
21. Chrome 启动后其它进程抢占 9344、PID 复用、token/profile/identity 变化时 ownership=false，不创建/关闭 target/browser。
22. `Target.createTarget/getTargets/attachToTarget/closeTarget` 与 `Browser.close` 只经已验证的同一 browser CDP WebSocket；源码/网络测试断言无 `/json/new`、`/json/list`、`/json/close`。在 ownership 验证后、create 前注入端口重绑，alternate Chrome 的 target count 保持 0。
23. 旧 9344 无 owner record 时 `LEGACY_UNOWNED_BROWSER`，不自动操作；精确终止旧 stock Chrome 不影响其它 Chrome。

### 9.3 协议与意图

24. `stock-analysis-v1` 每个 task 只请求一次；DAY 要求 technical/rules/intent 三子模块，WEEK/MONTH 要求 technical 且 `rules=[]/intent=null`；没有意图专用、rule repair 或 technical repair 请求。
25. DAY ruleId 集合必须精确等于 fact 冻结集合；triggerFactPath 只能是当前事实；历史文字不能触发。WEEK/MONTH 不得返回 rule decision 或更新 intent head。
26. intent 双轴、claim union、正反证 disjoint、active test 容量、belief 增幅门、terminal delivery 恰好一次和不可达路径拒绝全覆盖。
27. 生产 validator + serializer 生成 compact fixture，必须得到 `5968/7248/06eb0905...eb84121c`。
28. DAY 冷启动携带两周全部日内成功槽位；第二次起只携 previousState + tests + 转折，不再总结两周行情替代历史观点验证。

## 10. 实施顺序、迁移与真实冒烟

1. 先实现 fact/test ledger、task receipt/dispatcher 表和单测，不连 Broker。
2. 实现 Broker identity/journal/ownership/circuit/ACK，只用 fake CDP 完成故障注入。
3. 实现统一 `stock-analysis-v1` 协议、sampler、dispatcher、commit/outbox，保持 `notify=false`。
4. 运行单元、Mapper/H2-MySQL、多进程、Node journal、fake CDP 和全量回归测试。
5. 迁移 preflight：停旧 stock Node，检查 9344。若为 legacy unowned，用 socket PID/start-time/cmdline 证明后只终止该 stock Chrome，确认 9344 下线；启动新 Broker 生成 owner record。
6. 重启后检查 health：8791/9344、role/capability、listener/ownership、instance/fingerprint、deadline、circuit CLOSED、adapter disabled。
7. 使用真实股票 `002594.sz` 比亚迪（历史不足则 `002979.sz` 雷赛智能）、真实 Eastmoney 数据和真实 8791 DeepSeek 网页账号执行 `notify=false` 冒烟。临时 schema 只能隔离业务数据，不能隔离同一个网页账号；因此无论交易时段还是收盘后，都必须执行以下账号级互斥编排：

   - 从生产库只读导出两个时间递增的真实 historical fact snapshots 和所需两周技术历史，不携带凭据/邮箱；记录生产 fact/head/task/analysis/intent/notification 行数与关键 high-water，以及 Broker production scope reservation 序号；
   - 停止生产 backend 和自动重启 supervisor，等待 sampler/dispatcher/ACK worker idle、生产 DB dispatch lease 释放。Broker health/journal 必须不存在 production scope 的 `QUEUED/SUBMITTING/SUBMITTED/UNKNOWN`；任一 unsafe 状态存在则中止冒烟，不靠等待或删 journal 强行继续；
   - 由 smoke orchestrator 在**生产 schema**取得 `maintenance_mode=SMOKE` 租约 `{token,fence,expiresAt}`。所有生产 sampler/dispatcher 启动和 reserve 前都检查该租约，因此生产进程即使意外被拉起也只能只读健康检查，不能采样/dispatch；
   - 再经本机受保护 control endpoint 在 Broker journal 取得账号级 exclusive smoke lease `{executionScope=smoke:<runId>,token,fence,expiresAt}`。lease active 时 Broker 拒绝所有 production reservation；smoke 每个 health/request/ACK/CONFIRM 都携带期望 token/fence，Broker 在接收、出队、reservation 前重验。orchestrator 同时 heartbeat 两把 lease，TTL 必须大于 health 推导的单次 hard timeout + commit/confirm margin；
   - 创建与生产同 DB engine 的临时 schema `ai_meeting_room_smoke_<runId>`，启动独立 port 的 `stock-watch-smoke` profile，使用空 fact/head/task/intent 表、上述 executionScope/lease token/fence、`notify=false`。它使用真实 8791、真实已登录第二账号和共享预算/journal，但不能获取 production scope；
   - runner 通过生产 `acceptSample` 事务方法按时间顺序导入第一 snapshot，确认 fact/test/DAY task 入 smoke schema；强制 dispatcher 消费，且发送前断言 key 不在 journal，确保新增真实 reservation。重复第一 snapshot，确认 fact/test/task/网页都不重复；
   - 导入第二 snapshot 并真实消费，确认第二轮 prompt 带上一 intent、逐条 priorValidation 和收窄后的 claims；不能退化为两周行情摘要。两轮各一次 reservation 同时产生 DAY technical/rules/intent，无 API/8789/修复请求；receipt 必须完成 business commit、ACKED、ACK_CONFIRMED，每 key reservation/confirmed/upperBound 都不超过 1；
   - smoke 期间持续断言 production reservation 增量为 0，生产表/high-water/通知完全不变。WEEK/MONTH、规则并发、预算和 crash 边界用 mock/fake CDP 测试，不额外消耗真实账号；
   - 正常退出顺序固定：停 smoke backend -> 等所有 smoke receipt ACK_CONFIRMED 且 Broker 无 smoke unsafe in-flight -> 删除临时 schema/无敏感中间文件 -> 释放 Broker smoke lease -> 释放生产 maintenance lease -> 启动 production supervisor/backend -> 检查生产 health/调度恢复。Broker 中已 confirmed 的 smoke 审计 tombstone 不伪删；
   - orchestrator 异常退出时 lease 只可在 TTL 后 safe-expire；若 Broker 仍有 `SUBMITTING/SUBMITTED/UNKNOWN`，账号 circuit 和 production 拒绝必须保持 fail-closed，不能因 TTL 自动放行。恢复工具先完成 teardown/ACK reconciliation，再按相同逆序释放。

8. 冒烟发现问题后先记录证据，修复、重跑回归和真实冒烟，最后重启并留下可用生产进程。

真实冒烟不故意制造风控页、post-marker 超时或多封邮件。破坏性边界只在 fake CDP/mock provider 中测试。

## 11. 回滚与剩余边界

- `stock-watch.analysis.enabled=false` 可暂停 sampler/dispatcher，不删除 fact/test/task 历史。
- 连续意图子模块可单独关闭，但 technical/rules 仍必须经 8791 Broker，不能退回 8789/API。
- 预算、公平队列和有界 latest-wins 意味着模型可能跳过中间快照；但每个唯一 fact observation 仍会验收历史 tests，被跳过的模型分析不会被伪装成已执行。
- 默认滚动 60 分钟 20 次、滚动 24 小时 80 次是保守初值，上线后只能根据真实封号/超时率、p95 耗时和积压调整；不能为清空队列自动提高。
- 多股微批可能进一步减少请求，但它会放大上下文、截断和单次失败影响面；本期先用单股统一请求积累容量数据，不把微批当成未验证的答案。

## 12. 前两轮审核处置

| Finding | 处置 |
|---|---|
| 20 股票的到达率超过账号容量，同步顺序扫描饥饿 | 拆为全股快速 sampler + dedicated fair dispatcher；加滚动预算、P0 保留、urgent burst 与 starvation escalation |
| technical/intent/rule 三 purpose 仍可重复请求 | 删除三 purpose；只保留一个 `stock-analysis-v1` purpose 和一次业务 commit |
| API_FIRST 违背股票使用第二网页账号 | 本期固定 BROKER_ONLY；删除 ModelRegistry/AiModelRouter/GenericAiClient 改动和全部 API fallback |
| submissionKey 没有 sealed identity/requestDigest/contractVersion | 定义 Java/Node 共同 canonical identity、key 与全请求 digest；同 key 异 digest fail-closed |
| rule/technical 没有 durable ACK sink | 合并为 task receipt；业务记录与 ACK outbox 同事务，每个 ACK 携带 task/record IDs/result digest |
| provider 失败是否消费 tests 矛盾 | 事实 observation 入库时独立验真 tests；provider 只决定是否产生新 intent state，不再控制事实验真 |
| `/json/close` 有端口重绑竞态 | 删除该路径；target/browser destructive close 只通过已验证的同一 browser-level CDP WebSocket |

| 第十四轮 Finding | 第十五版处置 |
|---|---|
| 公平性存在可清理 task 上，45 分钟 SLA 数学不可达 | 永久 `fact_head.last_model_reserved_at`；45 分钟改为 escalation；容量只报告模拟得到的 estimate，不宣称 SLO |
| 自然小时可跨整点突发 40 次 | Broker journal 改为滚动 60m/24h reservation timestamps，同 marker 原子持久；clock rollback fail-closed |
| fact commit 到 task upsert 之间崩溃可永久丢任务 | fact/head/test/task 同事务；analysisIntentKey + disposition/link；duplicate 分支可修复 MISSING，不重建 COALESCED |
| 新 fact 到达就丢弃已付费结果会破坏活性 | 冻结结果持久为 COMMITTED_LAGGED；只有更高 model evaluation 已提交才 stale；new claims 从 commit-time fact head 后激活 |
| digest 裸拼接、health TOCTOU、ACK 内容冲突 | 定义 NFC/LF/fixed canonical JSON envelope 和共享 vectors；expected tuple 三阶段校验；ACK payload hash 首次绑定 |
| 手动 controller/service/前端/旧 smoke 仍可绕过 Broker | 手动只 enqueue+202，service 删直连/retry，前端 poll receipt，smoke 删 8789，加唯一 BrokerClient 架构测试 |
| 收盘后 historical replay 会被生产 freshness 拒绝 | 真实 8791 + 独立 Spring profile/临时同引擎 schema + smoke scope，按时间导入两快照，冒烟后恢复生产 |

| 第十五轮 Finding | 第十六版处置 |
|---|---|
| fact key 与 lease 时规则快照可能错配 | rule snapshot/ruleIds/hash 在 fact 事务冻结，task 只复制；规则编辑只影响下一 fact |
| 不合格 escalated P1 可挡住 P0 reserve | priority 从 reasons 派生并入 identity；dispatcher 按 Broker eligible classes 过滤，stale 时同 tick 换类 |
| coverage 忽略滚动 24h | 改为基于 journal timestamps/cooldown/p95/pending 的离散事件 estimate，可返回 UNBOUNDED，不称 SLO |
| 首次 ACK 后停机超过 24h 会丢恢复依据 | 增加 ACK_CONFIRMED 二阶段；未确认 tombstone 不按年龄清理，容量满 fail-closed |
| 普通新 fact 在模型在途时不保证后继 | 任意 NEWER fact 对 LEASED/PROVIDER_COMPLETED DAY task 原子创建 INFLIGHT_SUCCESSOR P2 |
| 创建 target 仍走 `/json/new` | create/list/attach/close/browser close 全部收口到同一已验证 browser CDP WebSocket |
| smoke schema 隔离不了共享账号 | 停生产/supervisor + 生产 maintenance lease + Broker exclusive smoke lease + token/fence/heartbeat + fail-closed 恢复 |
| DAY/WEEK/MONTH identity 冲突、forecast 功能退化 | period/horizon/mode 进入 fact/task/key/prompt/result；DAY 更新连续意图，WEEK/MONTH technical-only 且跨周期不合并 |

第十六轮必须由独立 Challenge 与 Optimize 分别给出明确 PASS：上述八个 finding 均可由文中测试复现并关闭，同时确认没有引入新的 material correctness/operability blocker。任一 REJECT 都继续修订，不编码。
