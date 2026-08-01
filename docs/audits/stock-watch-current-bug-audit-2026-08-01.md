# 盯盘系统当前 Bug 审计与代码级修复方案

审计日期：2026-08-01  
审计对象：`StockWatch` 盯盘、行情、策略调度、统一技术分析 demand、DeepSeek 网页 bridge 与前端运行态展示主链路。  
审计方式：当前工作树源码、CodeGraph 导航、运行中本地服务、MySQL 当前状态、已有设计文档和审计文档交叉验证。

## 1. 结论摘要

当前盯盘系统的主要问题不是单点空指针或页面展示瑕疵，而是几个跨边界不变量没有被系统化封装：

1. **需求队列生命周期未闭环**：过期 `PENDING` demand 不会被 `dispatchOne()` 选中，也不会被 `reconcileSubmitted()` 终态化；runtime 却仍返回 `VALID`。当前本地运行态已经复现：`PENDING=21`，最早 `not_before=2026-07-31 14:50:00`，最大 `deadline_at=2026-07-31 23:00:00`，而审计时间为 `2026-08-01 15:19 CST`。
2. **DeepSeek bridge 内容可信边界过宽**：JS bridge 明确区分 `weak_signal`、`possible_history_pollution`、`link_only`、`empty`、`incomplete`；Java 技术分析和 AI 规则调用只拒绝 `incomplete` 与空正文，存在把弱信号或历史污染正文当作成功分析的路径。
3. **交易日历和市场会话不是统一深模块**：后端 Planner、技术分析 freshness、盯盘扫描、前端自动刷新各自使用 weekday 或固定时段。港股后端 DAILY_CLOSE 已按 16:00 设计，但前端自动刷新仍在 15:00 后停止。
4. **多市场股票身份仍然存在裸 `symbol` 主路径**：后端盯盘评估、行情服务和前端报价回填都有按 `symbol` 建 map 或查找 watchlist 的代码，绕开已有的 `symbol.market` 规范化能力，后续一旦出现跨市场同码或 HK/A 股输入混用，会串线。
5. **容量和公平性被 UI 字段掩盖**：策略接口固定返回 `capacityStatus=UNBOUNDED`，但实际 bridge 是 single worker，dispatch 每次扫描只取 1 条，且按 `priority DESC, id ASC`，不能表达积压、吞吐和公平性风险。
6. **前端验证仍是字符串扫描，不覆盖关键行为**：`verify:stock-watch-realness` 能防 demo/fake Kline 回归，但没有 Vitest 或同类行为测试覆盖港股会话、市场身份、runtime degraded、source error 等当前关键风险。

优先级建议：

- **P0**：修 demand 过期终态与 runtime 健康；修 DeepSeek bridge fail-closed。
- **P0/P1**：修前端港股会话与 quote/watchlist 股票身份匹配。
- **P1**：抽 `MarketCalendarService`，统一后端和前端交易日/会话语义。
- **P1**：建立 demand 容量、批量 dispatch、公平性和 SLA 指标。
- **P2**：拆出前端可测试模块并补行为测试。

## 2. 当前运行态证据

本地服务状态：

- 后端：`127.0.0.1:8127` 可访问，需要登录态。
- 前端：`127.0.0.1:3009` 有进程监听。
- DeepSeek bridge：`127.0.0.1:8791/health` 返回 `ok=true`、`debugPort=9344`、`busy=false`、`single_worker`、请求账本 `entryCount=908`。

运行态接口证据：

```json
{
  "status": "VALID",
  "analysisMode": "POLICY",
  "pendingJobs": 21,
  "retryJobs": 1,
  "nextDueAt": "2026-07-31T14:50",
  "warnings": [],
  "retryPolicy": {
    "nextRetryAt": "2026-07-31T22:59:46",
    "deadlineAt": "2026-07-31T23:00",
    "lastErrorCode": "unified_analysis_submit_retry:daily_close_fact_not_ready:source_trade_date_not_fresh",
    "status": "VALID"
  }
}
```

运行态 SQL 证据：

```text
status       count  min_not_before        max_deadline_at
DEAD_LETTER  276    2026-07-23 09:30:00   2026-07-31 23:00:00
PENDING      21     2026-07-31 14:50:00   2026-07-31 23:00:00
SATISFIED    514    2026-07-23 09:30:00   2026-07-31 23:00:00
SUPERSEDED   569    2026-07-23 09:30:00   2026-07-31 15:50:00

expired_active(PENDING/CLAIMED deadline_at <= NOW()) = 21
```

DeepSeek analysis task 当前状态分布未观测到 `weak_signal` 或 `possible_history_pollution` 成功样本：

```text
PROJECTED_SUCCESS success complete_json_network PROJECTED      684
SUCCESS           complete parsed                PARSED_VALID    39
INVALID_RESPONSE  success complete_json_network INVALID          3
```

因此 DeepSeek 内容门禁问题在当前 DB 中是**源码可达风险**，不是已观测污染样本。

## 3. 架构层 Bug

### A1. Demand 生命周期没有一个拥有 SLA 的队列边界

证据：

- `StockWatchAnalysisDemandMapper.xml:66-71` 的 `selectDispatchable` 只选择 `deadline_at > now` 的 `PENDING/CLAIMED`。
- `StockWatchAnalysisDemandService.java:286-294` 的 deadline 过期分支在 `selectDispatchable` 之后，普通过期 `PENDING` 不会进入这个分支。
- `StockWatchAnalysisDemandService.java:351-368` 只 reconcile `SUBMITTED`，不处理普通过期 `PENDING/CLAIMED`。
- `StockWatchRuntimeStatusService.java:127-154` 把所有 `PENDING/CLAIMED/SUBMITTED` 计入 `pendingJobs`，`nextDueAt` 直接取 `PENDING MIN(not_before)`，不排除过期行。
- 当前 DB 有 21 条过期 `PENDING`，runtime 仍返回 `VALID`。

影响：

- 调度器停止兑现某些需求后，系统仍显示健康。
- `active_coalesce_key` 仍占用，可能阻止同股票同 slot 新需求正确重建。
- UI 和告警无法区分“稍后执行”和“已经错过 SLA”。

根因：

队列表既承担 coalesce、lease、submit、retry、终态记录，又缺少一个“所有 active demand 必须最终进入终态”的队列服务不变量。当前代码把过期处理放在 dispatch 分支里，但 mapper 又把过期数据过滤掉。

### A2. 交易日历/市场会话被复制在多个层级

证据：

- 设计文档 `docs/stock-watch-per-stock-analysis-cadence-plan-v18.md:173-177` 要求 SH/SZ/BJ 到 15:00，HK 到 16:00，且交易日不能只靠周一到周五。
- `StockWatchAnalysisSchedulePlanner.java:215-227` 用 `isWeekday/nextWeekday/lastWeekdayOfMonth` 近似交易日。
- `StockWatchAnalysisSchedulePlannerTest.java:60-78` 已确认后端 DAILY_CLOSE 需要支持 HK 16:00。
- `frontend/src/views/StockWatch.vue:3376-3394` 的 `isTradingSessionNow()` 只支持 09:30-11:30、13:00-15:00。
- `frontend/src/views/StockWatch.vue:3360-3367` 自动刷新只在 `isTradingSessionNow()` 为 true 时运行。
- 旧审计文档 `docs/audits/stock-watch-cadence-implementation-v2.audit.json:512-519` 已把“DAILY_CLOSE 使用 weekday 近似，不是权威交易所日历”列为剩余边界。

影响：

- 港股 15:00-16:00 期间前端不自动刷新，用户看到的实时价格和资金流可能停滞。
- 法定节假日、半日市、停牌、不同市场收盘时间会在不同服务里表现不一致。
- close-ready、runtime、UI、scheduler 没有同一个解释来源，后续很难证明“今天该不该跑模型”。

根因：

“市场状态”不是一个可注入、可测试、可替换的模块，而是分散的日期时间判断。

### A3. Provider 内容可信边界没有集中封装

证据：

- `scripts/deepseek-web-client.js:1493-1529` 明确把回答来源标成 `completion_stream`、`new_assistant_block`、`body_delta`、`page_fallback`、`link_only`、`empty`，并把后几类降级为 `weak_signal`、`possible_history_pollution` 等。
- `StockWatchTechnicalAnalysisService.java:1418-1450` 只拒绝 `contentStatus=incomplete` 和空正文。
- `StockWatchAiJudgeService.java:710-731` 同样只拒绝 `incomplete` 和空正文。
- `scripts/deepseek-openai-adapter.js:415-423` 也只拒绝 `incomplete`。

影响：

- 一旦 bridge 在页面渲染不稳定时返回 `body_delta` 或 `page_fallback`，Java 侧可能把风险文本当成成功结果，后续投影、技术分析、邮件提醒都会建立在不可信上下文上。
- 内容可信策略散落在 JS 和 Java，多入口不一致，不能一处收紧。

根因：

系统现在把“有正文”近似为“可用分析”，没有把 provider envelope 的 provenance 当成一等契约。

### A4. 股票身份模型没有成为系统边界

证据：

- `StockSymbol.java:9-15` 已定义 canonical stock symbol 概念，并支持 HK/A 股归一化。
- `StockSymbol.java:111-120` 已有 `eastmoneySecid` 和 `marketFromEastmoney`。
- `StockWatchMonitorService.java:195-203` 用裸 `normalizeSymbol(symbol)` 建 `quoteBySymbol`。
- `StockWatchMonitorService.java:222-228` AI rule 后续又用 `stockKey(rule.getSymbol(), rule.getMarket())` 查风险和事实，说明代码内部已经混用两套 identity。
- `StockQuoteService.java:97-112` 从 Eastmoney diff 按 `symbol` 建 `byCode`，然后强行把 market 覆盖成请求 ref 的 market。
- `frontend/src/views/StockWatch.vue:3433-3441` 用 `item.symbol === quote.symbol` 找 watchlist，再把 `stock.market` 改成 quote market。

影响：

- 同一数字代码跨市场或用户输入格式混合时，quote、规则、事实观测、UI 行情回填可能串线。
- 风险事实已按 `stockKey` 记录，但 quote 源头仍按 `symbol` 匹配，导致事实与行情的身份边界不一致。

根因：

`StockSymbol` 是工具类，不是主链路的数据模型。服务接口、map key、前端状态都没有强制使用 `symbol.market`。

## 4. 代码层 Bug

### C1. 过期 PENDING 永远不会被当前 dispatch 分支清理

当前代码：

```xml
<!-- StockWatchAnalysisDemandMapper.xml -->
WHERE status IN ('PENDING','CLAIMED')
  AND not_before <= #{now} AND deadline_at > #{now}
```

```java
// StockWatchAnalysisDemandService.dispatchOne()
List<StockWatchAnalysisDemand> candidates = demandMapper.selectDispatchable(now, 1);
if (candidate.getDeadlineAt() != null && !candidate.getDeadlineAt().after(now)) {
    deadLetter(candidate, "analysis_demand_deadline_elapsed_before_submit");
    return;
}
```

Bug 点：mapper 已经排除 `deadline_at <= now`，所以 Java 里的 deadline 过期保护对普通过期 `PENDING` 不生效。

### C2. Runtime 把过期积压展示为 VALID

当前代码：

```java
result.put("pendingJobs", pendingJobs(...));
result.put("nextDueAt", nextDueAt(...));
result.put("warnings", warnings);
result.put("status", warnings.isEmpty() ? "VALID" : "DEGRADED");
```

Bug 点：`warnings` 只来自查询失败或服务异常，不来自业务健康。过期 job、过去的 nextDueAt、retry deadline 已过，都不会让 status 降级。

### C3. DeepSeek 弱信号/历史污染未 fail-closed

当前代码：

```java
bridgeResult.contentStatus = text(result.get("contentStatus"), "");
if ("incomplete".equalsIgnoreCase(bridgeResult.contentStatus)) {
    throw new IllegalStateException(...);
}
if (!StringUtils.hasText(bridgeResult.answerText)) {
    throw new IllegalStateException(...);
}
return bridgeResult;
```

Bug 点：`weak_signal`、`possible_history_pollution`、`link_only` 不应进入成功路径。尤其 stock-watch 的输出会驱动预测、提醒和业务决策，不能沿用通用聊天“尽量返回正文”的策略。

### C4. 港股交易时段前端少一个小时

当前代码：

```js
return (
  (minutes >= 9 * 60 + 30 && minutes <= 11 * 60 + 30)
  || (minutes >= 13 * 60 && minutes <= 15 * 60)
)
```

Bug 点：watchlist 可能包含 HK，后端 scheduler 已按 HK 16:00 close 设计，但前端自动刷新在 15:00 后停止。

### C5. Quote/watchlist 按 symbol 匹配会改错 market

当前代码：

```js
const stock = watchlist.find((item) => item.symbol === quote.symbol)
stock.market = quote.market || stock.market
```

Bug 点：如果 watchlist 中同 symbol 但不同 market，或者后端响应 market 与请求不一致，第一条匹配项会被更新，甚至 market 会被覆盖。

### C6. `capacityStatus=UNBOUNDED` 与实际 single worker 矛盾

当前代码：

```java
payload.put("capacityStatus", "UNBOUNDED");
```

当前 bridge health 显示 `mode=single_worker`。Demand reconcile 每次扫描只 `dispatchOne()`，mapper `LIMIT 1`，排序 `priority DESC, id ASC`。因此 capacity 不是 unbounded，至少应能表达 `OK/AT_RISK/OVERDUE/BLOCKED/UNKNOWN`。

### C7. 前端缺少能证明当前风险的行为测试

当前 `frontend/package.json` 只有：

```json
"verify:stock-watch-realness": "node scripts/verify-stock-watch-realness.mjs"
```

`frontend/scripts/verify-stock-watch-realness.mjs` 是字符串扫描，不能证明：

- HK 15:30 自动刷新。
- `symbol.market` 匹配。
- runtime `DEGRADED` 展示。
- intraday bars `SOURCE_ERROR` 不被当作有效图表。

## 5. 设计理念层 Bug

### D1. 以“生成任务”为中心，而不是以“兑现 SLA”为中心

现在的设计会创建 demand、coalesce demand、提交 provider，但没有让“每个 active demand 必须在 deadline 前进入成功、跳过、过期或死信终态”成为第一不变量。

应改为：

- Demand table 是 obligation queue。
- Runtime status 是 SLA 视图，不是查询可用性视图。
- 过期、跳过、死信都应是正常业务状态，不是异常日志。

### D2. 以系统时间 weekday 为中心，而不是以市场日历状态为中心

现在后端和前端有多个 `weekday + time range` 判断。正确模型应是：

- 给定 `market + symbol + at`，得到一个 `MarketSession`。
- Scheduler、quote refresh、close-ready、freshness gate、UI 都只消费这个状态。
- 日历来源和质量要可见，例如 `STATIC_RULE`、`HOLIDAY_CSV`、`EXCHANGE_API`、`STALE/UNKNOWN`。

### D3. 以“LLM 返回可解析”为成功，而不是以“数据和内容可信”为成功

股票盯盘里的模型结果不是聊天回复，它会影响提醒、预测和复盘。成功条件至少要同时满足：

- 数据快照 tradeDate/freshness 可信。
- Provider 内容来源可信。
- JSON 完整且 schema 合法。
- 输出投影通过质量门禁。

只要其中一层不可信，就应终止或进入 retry/skip，不应进入 `PROJECTED_SUCCESS`。

### D4. 以展示字段拼接身份，而不是以 canonical identity 贯穿系统

多市场支持不应该靠每个调用点记得带 market。正确模型是所有服务和前端状态都使用 `StockKey(symbol, market)`，展示层才把它格式化为 `09988.hk`、`688017.sh`。

## 6. 代码级修复方案

### P0-1. Demand 生命周期闭环与 runtime 健康降级

改动文件：

- `src/main/java/com/aimeeting/room/mapper/StockWatchAnalysisDemandMapper.java`
- `src/main/resources/mapper/StockWatchAnalysisDemandMapper.xml`
- `src/main/java/com/aimeeting/room/service/StockWatchAnalysisDemandService.java`
- `src/main/java/com/aimeeting/room/service/StockWatchRuntimeStatusService.java`
- `src/main/resources/sql/init.sql`
- `src/test/java/com/aimeeting/room/service/StockWatchAnalysisDemandServiceTest.java`
- `src/test/java/com/aimeeting/room/service/StockWatchRuntimeStatusServiceTest.java`

#### 6.1.1 增加过期终态

推荐新增两个业务终态：

- `EXPIRED`：deadline 前未提交 provider 的 demand。
- `SKIPPED_DATA_UNAVAILABLE`：`DAILY_CLOSE/REVIEW` 因 close-ready/freshness 到 deadline 仍不满足而安全跳过，不能调用模型。

如果短期不想新增状态，也可以先统一落 `DEAD_LETTER`，但建议至少用 `error_message` 区分：

- `analysis_demand_deadline_elapsed_before_submit`
- `daily_close_fact_not_ready_until_deadline`

#### 6.1.2 Mapper 增加批量过期清理

```java
int markExpiredActive(@Param("now") Date now, @Param("reason") String reason);

int markSkippedDataUnavailable(@Param("now") Date now,
                               @Param("reason") String reason);

int countExpiredActive(@Param("now") Date now,
                       @Param("symbol") String symbol,
                       @Param("market") String market);

Date oldestExpiredDeadlineAt(@Param("now") Date now,
                             @Param("symbol") String symbol,
                             @Param("market") String market);
```

```xml
<update id="markExpiredActive">
  UPDATE stock_watch_analysis_demand
  SET status='EXPIRED',
      error_message=#{reason},
      active_coalesce_key=NULL,
      lease_token=NULL,
      lease_until=NULL,
      row_version=row_version+1
  WHERE status IN ('PENDING','CLAIMED')
    AND deadline_at <= #{now}
    AND analysis_task_id IS NULL
</update>

<update id="markSkippedDataUnavailable">
  UPDATE stock_watch_analysis_demand
  SET status='SKIPPED_DATA_UNAVAILABLE',
      error_message=#{reason},
      active_coalesce_key=NULL,
      lease_token=NULL,
      lease_until=NULL,
      row_version=row_version+1
  WHERE status IN ('PENDING','CLAIMED')
    AND trigger_type IN ('DAILY_CLOSE','WEEKLY_REVIEW','MONTHLY_REVIEW')
    AND deadline_at <= #{now}
    AND analysis_task_id IS NULL
    AND error_message LIKE 'unified_analysis_submit_retry:daily_close_fact_not_ready:%'
</update>
```

注意事项：

- 必须清 `active_coalesce_key`，否则唯一索引 `uk_sw_analysis_demand_active_coalesce` 会继续阻止新 active demand。
- `SUBMITTED` 不在这个批量 update 里处理，继续由 `reconcileSubmitted()` 根据 task 终态或 deadline 进入 dead letter。
- `EXPIRED/SKIPPED_DATA_UNAVAILABLE` 不应被 `selectActiveByCoalesceKey` 当作 active 行。

#### 6.1.3 Reconcile 先清理再批量 dispatch

```java
@Scheduled(...)
public void reconcile() {
    if (!running.compareAndSet(false, true)) return;
    try {
        Date now = new Date();
        expireActiveDemands(now);
        dispatchReadyBatch(now);
        reconcileSubmitted(now);
    } catch (Exception e) {
        log.warn("stock-watch alert analysis demand reconcile failed error={}", diagnostic(e));
    } finally {
        running.set(false);
    }
}

private void expireActiveDemands(Date now) {
    int skipped = demandMapper.markSkippedDataUnavailable(now, "daily_close_fact_not_ready_until_deadline");
    int expired = demandMapper.markExpiredActive(now, "analysis_demand_deadline_elapsed_before_submit");
    if (skipped > 0 || expired > 0) {
        log.warn("stock-watch demand expired skippedDataUnavailable={} expired={}", skipped, expired);
    }
}

private void dispatchReadyBatch(Date now) {
    int batchSize = Math.max(1, demandDispatchBatchSize);
    for (int i = 0; i < batchSize; i++) {
        if (!dispatchOne(now)) break;
    }
}
```

把 `dispatchOne()` 改成返回 boolean，并接收同一个 `now`，避免每条 demand 的判断时间漂移。

#### 6.1.4 Runtime status 以 SLA 降级

新增返回字段：

```json
{
  "activePendingJobs": 0,
  "expiredJobs": 0,
  "skippedDataUnavailableJobs": 0,
  "deadLetterJobs": 0,
  "supersededJobs": 0,
  "oldestPendingAgeSeconds": 0,
  "capacityStatus": "OK"
}
```

降级规则建议：

```java
if (expiredJobs > 0) warnings.add("analysis_demand_expired");
if (nextDueAt != null && nextDueAt.isBefore(now.minusMinutes(5))) warnings.add("analysis_demand_overdue");
if (retryPolicyDeadlinePassed) warnings.add("analysis_retry_deadline_passed");
if (capacityStatus == OVERDUE || capacityStatus == BLOCKED) warnings.add("analysis_capacity_" + capacityStatus);
status = warnings.isEmpty() ? "VALID" : "DEGRADED";
```

验收：

```bash
mvn -q -Dtest=StockWatchAnalysisDemandServiceTest,StockWatchRuntimeStatusServiceTest test
```

运行态验收：

```sql
SELECT COUNT(*)
FROM stock_watch_analysis_demand
WHERE status IN ('PENDING','CLAIMED') AND deadline_at <= NOW();
```

预期：reconcile 跑完后为 `0`；如果测试夹具刻意插入过期行，`/api/stock-watch/runtime-status` 必须返回 `DEGRADED` 和 `analysis_demand_expired`。

### P0-2. DeepSeek bridge 内容门禁 fail-closed

改动文件：

- 新增 `src/main/java/com/aimeeting/room/service/DeepSeekBridgeContentGate.java`
- `src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java`
- `src/main/java/com/aimeeting/room/service/StockWatchAiJudgeService.java`
- `scripts/deepseek-openai-adapter.js`
- `src/test/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisServiceTest.java`
- `src/test/java/com/aimeeting/room/service/StockWatchAiJudgeServiceTest.java`

#### 6.2.1 Java 统一门禁

```java
public final class DeepSeekBridgeContentGate {
    private static final Set<String> TRUSTED_CONTENT_STATUS = Set.of("success");
    private static final Set<String> TRUSTED_ANSWER_SOURCE = Set.of("completion_stream", "new_assistant_block");

    public static void assertTrusted(BridgeEnvelope envelope) {
        String status = normalize(envelope.contentStatus());
        String source = normalize(envelope.answerSource());
        if (!TRUSTED_CONTENT_STATUS.contains(status)) {
            throw new IllegalStateException("DeepSeek bridge content not trusted: contentStatus=" + status
                    + ", answerSource=" + source + ", contentNote=" + envelope.contentNote());
        }
        if (StringUtils.hasText(source) && !TRUSTED_ANSWER_SOURCE.contains(source)) {
            throw new IllegalStateException("DeepSeek bridge answer source not trusted: answerSource=" + source);
        }
        if (!StringUtils.hasText(envelope.answerText())) {
            throw new IllegalStateException("DeepSeek bridge returned empty answer");
        }
    }
}
```

兼容策略：

- 新 bridge 请求只接受 `contentStatus=success` 且 `answerSource` 为 `completion_stream/new_assistant_block`。
- 历史库里的 `contentStatus=complete, completionReason=parsed, providerStatus=PARSED_VALID` 只作为历史记录展示兼容，不应作为新 bridge 响应的成功判定。
- `weak_signal`、`possible_history_pollution`、`link_only`、`empty`、未知 status 一律异常，进入 retry 或终态失败。

#### 6.2.2 扩展 BridgeResult 字段

在 `StockWatchTechnicalAnalysisService.BridgeResult` 加：

```java
String answerSource;
String contentNote;
Integer searchCount;
Integer bodyDeltaLength;
```

`executeBridgeRequest()` 解析：

```java
bridgeResult.answerSource = text(result.get("answerSource"), "");
bridgeResult.contentNote = text(result.get("contentNote"), "");
DeepSeekBridgeContentGate.assertTrusted(BridgeEnvelope.from(bridgeResult));
return bridgeResult;
```

`StockWatchAiJudgeService` 不要只返回 `String answer`，改为复用同一个 gate，至少在返回 answer 前调用同一校验。

#### 6.2.3 JS adapter 同步收紧

```js
const trustedStatuses = new Set(['success'])
const trustedSources = new Set(['completion_stream', 'new_assistant_block'])
const status = String(bridgeResult.contentStatus || '').toLowerCase()
const source = String(bridgeResult.answerSource || '').toLowerCase()
if (!trustedStatuses.has(status) || (source && !trustedSources.has(source))) {
  context.sendJson(res, 502, makeOpenAiError(
    `DeepSeek web bridge returned untrusted content: ${status}/${source}`,
    'untrusted_bridge_content',
    'server_error'
  ))
  return true
}
```

验收：

```bash
mvn -q -Dtest=StockWatchTechnicalAnalysisServiceTest,StockWatchAiJudgeServiceTest test
node --test scripts/deepseek-openai-adapter.test.js
```

如果没有现成 JS test harness，先补最小 `node:test`，构造 `weak_signal`、`possible_history_pollution`、`link_only`、`empty`、`success/completion_stream` 五组样本。

### P0/P1-3. 股票身份统一为 `StockKey`

改动文件：

- 新增 `src/main/java/com/aimeeting/room/service/StockKey.java`
- `src/main/java/com/aimeeting/room/service/StockQuoteService.java`
- `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java`
- `frontend/src/modules/stock-watch/stockKey.js`
- `frontend/src/views/StockWatch.vue`
- 对应 Java 和前端测试

#### 6.3.1 Java value object

```java
public record StockKey(String symbol, String market) {
    public StockKey {
        StockSymbol normalized = StockSymbol.parse(symbol, market);
        symbol = normalized.getSymbol();
        market = normalized.getMarket();
    }

    public static StockKey of(String symbol, String market) {
        return new StockKey(symbol, market);
    }

    public String label() {
        return symbol + "." + market;
    }

    public String eastmoneySecid() {
        return StockSymbol.eastmoneySecid(symbol, market);
    }
}
```

#### 6.3.2 `StockQuoteService` 改为按 secid/label 回填

当前 `byCode` 应替换为：

```java
Map<String, Map<String, Object>> byStockKey = new LinkedHashMap<>();
for (int i = 0; i < diff.size(); i++) {
    JSONObject item = diff.getJSONObject(i);
    Map<String, Object> quote = toQuote(item);
    StockKey key = StockKey.of(text(quote.get("symbol")), text(quote.get("market")));
    byStockKey.put(key.label(), quote);
}

return refs.stream()
        .map(ref -> {
            StockKey requested = StockKey.of(ref.getSymbol(), ref.getMarket());
            Map<String, Object> quote = byStockKey.get(requested.label());
            if (quote == null) return failedQuote(ref, "eastmoney_missing");
            return new LinkedHashMap<>(quote);
        })
        .collect(Collectors.toList());
```

不要再用请求 ref 覆盖返回 quote 的 market；如果 Eastmoney 返回 market 与请求不一致，应作为 `market_mismatch` 失败或 degraded source。

#### 6.3.3 `StockWatchMonitorService` 所有 quote map 改为 stock key

```java
Map<String, Map<String, Object>> quoteByStockKey = quotes.stream()
        .filter(quote -> text(quote, "symbol", null) != null)
        .filter(quote -> text(quote, "market", null) != null)
        .collect(Collectors.toMap(
                quote -> stockKey(text(quote, "symbol", ""), text(quote, "market", "")),
                quote -> quote,
                (left, ignored) -> left,
                LinkedHashMap::new
        ));

Map<String, Object> quote = quoteByStockKey.get(stockKey(rule.getSymbol(), rule.getMarket()));
```

`buildRiskPatterns(aiRules, quoteBySymbol, now)` 也应改签名为 `quoteByStockKey`。

#### 6.3.4 前端 quote 回填

```js
export function stockKey(symbol, market) {
  return `${String(symbol || '').trim().toLowerCase()}.${String(market || '').trim().toLowerCase()}`
}

function applyQuoteToStock(quote) {
  const quoteKey = stockKey(quote.symbol, quote.market)
  const stock = watchlist.find((item) => stockKey(item.symbol, item.market) === quoteKey)
  if (!stock || quote.price == null) return false
  if (stockKey(stock.symbol, stock.market) !== quoteKey) return false
  ...
}
```

验收：

- Java：同一数字 symbol 构造不同 market 的 quote fixture，断言 AI rule 和 support rule 都命中正确 quote。
- 前端：watchlist 同时放入两个同 symbol 不同 market 的对象，`applyQuoteToStock()` 只更新匹配 market 的对象。

### P1-4. 统一 `MarketCalendarService`

改动文件：

- 新增 `src/main/java/com/aimeeting/room/service/MarketCalendarService.java`
- 新增 `src/main/java/com/aimeeting/room/service/StaticMarketCalendarService.java`
- `StockWatchAnalysisSchedulePlanner.java`
- `StockWatchTechnicalAnalysisService.java`
- `StockWatchMonitorService.java`
- `StockWatchRuntimeStatusService.java`
- 新增前端 API：`StockWatchController` 暴露 market session
- `frontend/src/modules/stock-watch/marketSession.js`
- `frontend/src/views/StockWatch.vue`

接口建议：

```java
public interface MarketCalendarService {
    MarketSession session(String market, ZonedDateTime at);
    boolean isTradingDay(String market, LocalDate date);
    LocalDate nextTradingDay(String market, LocalDate date);
    List<ZonedDateTime> intervalSlots(String market, LocalDate date, int intervalMinutes);
    ZonedDateTime closeReadyAt(String market, LocalDate date);
}

public record MarketSession(
        String market,
        LocalDate tradingDate,
        boolean tradingDay,
        String phase,
        ZonedDateTime sessionOpenAt,
        ZonedDateTime sessionCloseAt,
        ZonedDateTime nextOpenAt,
        ZonedDateTime closeReadyAt,
        String calendarSource,
        String qualityStatus
) {}
```

第一阶段实现：

- A 股：09:30-11:30、13:00-15:00，closeReady 15:10。
- HK：09:30-12:00、13:00-16:00，closeReady 16:10。
- 节假日：先支持本地 CSV 或配置表，不能确定时 `qualityStatus=UNKNOWN`，close-ready 必须延迟或 skip，不能伪造交易日。

替换点：

- `StockWatchAnalysisSchedulePlanner.isWeekday/nextWeekday/lastWeekdayOfMonth`
- `StockWatchTechnicalAnalysisService` 中所有 close-ready、weekend、sourceTradeDate fallback 判断
- `StockWatchMonitorService` 非交易时段跳过逻辑
- `StockWatchRuntimeStatusService.nextDueAt` 解释和 UI 文案
- `frontend/src/views/StockWatch.vue:isTradingSessionNow`

前端应改为按 watchlist 市场判断：

```js
function isTradingSessionNow(now = new Date(), items = watchlist) {
  const markets = new Set(items.map((item) => normalizeMarket(item.market)))
  return [...markets].some((market) => marketSessionNow(market, now).isOpen)
}
```

更好的版本是直接消费后端 `/api/stock-watch/market-session?markets=sh,sz,hk`，避免前后端重复日历。

验收：

```bash
mvn -q -Dtest=StockWatchAnalysisSchedulePlannerTest,MarketCalendarServiceTest,StockWatchTechnicalAnalysisServiceTest test
```

测试用例：

- HK 15:30 open，SH 15:30 closed。
- HK DAILY_CLOSE 16:10 due，15:12 not due。
- 周末 closed。
- 配置节假日 closed。
- close-ready freshness 失败超过 23:00 进入 `SKIPPED_DATA_UNAVAILABLE`，不调用 DeepSeek。

### P1-5. Demand 容量、公平性和可观测性

改动文件：

- `StockWatchAnalysisDemandService.java`
- `StockWatchAnalysisDemandMapper.xml`
- `StockWatchAnalysisPolicyService.java`
- `StockWatchRuntimeStatusService.java`
- 相关配置和测试

配置建议：

```yaml
stock-watch:
  alert-analysis-demand:
    scan-interval-ms: 60000
    dispatch-batch-size: 3
    max-concurrent-provider-submissions: 1
    overdue-warning-minutes: 5
```

公平性修复方向：

1. 短期：`selectDispatchable(now, limit)` 一次取多条，服务层按 `symbol.market` 去重，每轮同股最多 1 条。
2. 中期：增加 queue cursor 或 per-stock last claimed time。
3. 长期：按 trigger type 和 deadline 做 admission control，避免 INTERVAL 挤掉 DAILY_CLOSE。

示例 SQL：

```sql
SELECT *
FROM stock_watch_analysis_demand d
WHERE d.status IN ('PENDING','CLAIMED')
  AND d.not_before <= #{now}
  AND d.deadline_at > #{now}
  AND (d.status != 'CLAIMED' OR d.lease_until <= #{now})
ORDER BY d.priority DESC, d.deadline_at ASC, d.id ASC
LIMIT #{candidateLimit}
```

服务层：

```java
Set<String> seenStocks = new HashSet<>();
for (StockWatchAnalysisDemand candidate : candidates) {
    String key = stockKey(candidate.getSymbol(), candidate.getMarket());
    if (!seenStocks.add(key)) continue;
    if (dispatchOneCandidate(candidate, now)) dispatched++;
    if (dispatched >= dispatchBatchSize) break;
}
```

Runtime capacity：

```java
capacityStatus = switch {
    case bridgeUnavailable -> "BLOCKED";
    case expiredJobs > 0 -> "OVERDUE";
    case pendingJobs > estimatedOneHourThroughput -> "AT_RISK";
    default -> "OK";
};
```

验收：

- 插入同优先级多股票 demand，断言一个 reconcile batch 不会只处理同一股票。
- bridge unavailable 时 runtime `capacityStatus=BLOCKED`。
- backlog 大于估算吞吐时 runtime `capacityStatus=AT_RISK`。
- 删除 `capacityStatus=UNBOUNDED` 的硬编码。

### P2-6. 前端行为测试补齐

改动文件：

- `frontend/package.json`
- `frontend/src/modules/stock-watch/stockKey.js`
- `frontend/src/modules/stock-watch/marketSession.js`
- `frontend/src/modules/stock-watch/intradayEnvelope.js`
- `frontend/src/views/StockWatch.vue`
- `frontend/src/modules/stock-watch/*.test.js`

依赖：

```json
{
  "devDependencies": {
    "vitest": "^2.0.0"
  },
  "scripts": {
    "test:stock-watch": "vitest run src/modules/stock-watch/**/*.test.js"
  }
}
```

应抽出的纯函数：

- `stockKey(symbol, market)`
- `normalizeMarket(market)`
- `marketSessionNow(market, now)`
- `shouldAutoRefreshQuotes(watchlist, now)`
- `normalizeIntradayEnvelope(envelope)`
- `runtimeStatusSeverity(runtimeStatus)`

测试用例：

```js
import { describe, expect, it } from 'vitest'
import { shouldAutoRefreshQuotes } from './marketSession'
import { stockKey } from './stockKey'

it('keeps HK quote refresh open at 15:30 China time', () => {
  const now = new Date('2026-07-22T07:30:00.000Z') // 15:30 Asia/Shanghai
  expect(shouldAutoRefreshQuotes([{ symbol: '09988', market: 'hk' }], now)).toBe(true)
  expect(shouldAutoRefreshQuotes([{ symbol: '688017', market: 'sh' }], now)).toBe(false)
})

it('uses symbol and market as one identity', () => {
  expect(stockKey('09988', 'HK')).toBe('09988.hk')
  expect(stockKey('688017', 'sh')).toBe('688017.sh')
})
```

验收：

```bash
cd frontend
npm run verify:stock-watch-realness
npm run test:stock-watch
npm run build
```

## 7. 推荐实施顺序

1. **PR 1：Demand lifecycle P0**  
   新增终态、过期清理、runtime degraded、单元测试和一次 live smoke。完成后当前 `expired_active=21` 应收敛为 `0` 或明确展示 `DEGRADED`。

2. **PR 2：Bridge fail-closed P0**  
   统一 `DeepSeekBridgeContentGate`，两个 Java 调用点和 JS adapter 同步拒绝弱信号。完成后 mock `weak_signal/page_fallback/link_only` 不得产生 `PROJECTED_SUCCESS`。

3. **PR 3：StockKey P0/P1**  
   后端 quote map、monitor map、前端 watchlist 回填都改为 `symbol.market`。完成后重复 symbol 不同 market 的 fixture 不串线。

4. **PR 4：MarketCalendar P1**  
   后端先统一日历服务，再让前端消费 API 或共享纯函数。完成后 HK 15:30 自动刷新、HK 16:10 daily close、节假日 closed 都有测试。

5. **PR 5：Capacity/fairness P1**  
   批量 dispatch、公平性、capacity status、runtime 指标。完成后不能再返回 `UNBOUNDED`。

6. **PR 6：Frontend behavior tests P2**  
   把关键纯函数从大 Vue 文件拆出，建立稳定测试入口。

## 8. 最小验收清单

代码测试：

```bash
mvn -q -Dtest=StockWatchAnalysisDemandServiceTest,StockWatchRuntimeStatusServiceTest test
mvn -q -Dtest=StockWatchTechnicalAnalysisServiceTest,StockWatchAiJudgeServiceTest test
mvn -q -Dtest=StockWatchAnalysisSchedulePlannerTest,MarketCalendarServiceTest test
cd frontend && npm run verify:stock-watch-realness && npm run test:stock-watch && npm run build
```

运行态验收：

```bash
curl -H "X-Token: <token>" http://127.0.0.1:8127/api/stock-watch/runtime-status
```

必须证明：

- 没有过期 active demand，或 runtime 明确 `DEGRADED` 并给出 `analysis_demand_expired`。
- `nextDueAt` 不再指向已过期的昨日时间。
- bridge mock `weak_signal`、`possible_history_pollution`、`link_only`、`empty` 被拒绝。
- HK watchlist 在 15:30 Asia/Shanghai 仍会自动刷新。
- 同 symbol 不同 market 的 quote 不会互相覆盖。
- `capacityStatus` 不再硬编码 `UNBOUNDED`。

SQL 验收：

```sql
SELECT COUNT(*)
FROM stock_watch_analysis_demand
WHERE status IN ('PENDING','CLAIMED') AND deadline_at <= NOW();
```

预期：正常运行后为 `0`。

## 9. 本次审计边界

- 本报告没有修改业务代码，只给出当前 bug 证据和可执行修复方案。
- DeepSeek bridge 已确认当前源码存在弱信号放行路径，但当前 DB 未观测到 `weak_signal/possible_history_pollution` 成功样本。
- 当前运行态已确认 demand 过期积压与 runtime `VALID` 矛盾。
- 未对线上真实用户行为做 destructive 写入测试；所有 live 证据来自本地服务、只读接口和只读 SQL。
- 当前工作树已有若干与本报告无关的未提交改动，本报告未覆盖也未修改这些改动。
