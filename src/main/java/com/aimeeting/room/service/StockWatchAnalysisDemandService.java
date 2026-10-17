package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.dao.StockWatchAlertDeliveryObligationMapper;
import com.aimeeting.room.dao.StockWatchAnalysisDemandMapper;
import com.aimeeting.room.dao.StockWatchAnalysisTaskMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchAnalysisDemand;
import com.aimeeting.room.entity.StockWatchAnalysisTask;
import com.aimeeting.room.entity.StockWatchItem;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockWatchAnalysisDemandService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long NOT_BEFORE_MILLIS = 5L * 60L * 1000L;
    private static final long DEADLINE_MILLIS = 6L * 60L * 60L * 1000L;
    private static final String ANOMALY_RUN_SLOT_PREFIX = "al:";
    private static final int ANALYSIS_RUN_SLOT_MAX_LENGTH = 16;

    private final StockWatchAnalysisDemandMapper demandMapper;
    private final StockWatchAlertDeliveryObligationMapper obligationMapper;
    private final StockWatchAnalysisTaskMapper taskMapper;
    private final StockWatchTechnicalAnalysisMapper analysisMapper;
    private final StockWatchTechnicalAnalysisService technicalAnalysisService;
    private final StockWatchTechnicalEmailRenderer emailRenderer;
    private final StockWatchAnalysisPolicyService policyService;
    private final StockWatchAnalysisSchedulerOwnershipService ownershipService;
    private final StockWatchAnalysisSchedulePlanner planner;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Transactional
    public StockWatchAnalysisDemand createOrReuse(String symbol,
                                                  String market,
                                                  String stockName,
                                                  Long targetFactObservationId,
                                                  String targetFactObservationKey,
                                                  Long targetSourceTimeEpochMs) {
        if (targetFactObservationId == null
                || !StringUtils.hasText(targetFactObservationKey)
                || targetSourceTimeEpochMs == null) {
            return null;
        }
        String normalizedMarket = text(market).toLowerCase(Locale.ROOT);
        int generation = 1;
        String demandKey = sha256("stock-analysis-demand-v1|" + normalizedMarket + "|" + text(symbol)
                + "|DAY|" + targetFactObservationKey + "|" + generation);
        StockWatchAnalysisDemand existing = demandMapper.selectByDemandKey(demandKey);
        if (existing != null) return existing;

        long now = System.currentTimeMillis();
        StockWatchAnalysisDemand demand = new StockWatchAnalysisDemand();
        demand.setDemandKey(demandKey);
        demand.setSymbol(text(symbol));
        demand.setMarket(normalizedMarket);
        demand.setStockName(text(stockName));
        demand.setCapability(StockWatchAnalysisPolicyService.CAPABILITY);
        demand.setTriggerType("ANOMALY_IMMEDIATE");
        demand.setCoalesceKey(coalesceKey(normalizedMarket, text(symbol), "DAY"));
        demand.setActiveCoalesceKey(demand.getCoalesceKey());
        demand.setPeriodType("DAY");
        demand.setTargetFactObservationId(targetFactObservationId);
        demand.setTargetFactObservationKey(targetFactObservationKey);
        demand.setTargetSourceTimeEpochMs(targetSourceTimeEpochMs);
        LocalDate tradeDate = Instant.ofEpochMilli(targetSourceTimeEpochMs).atZone(CHINA_ZONE).toLocalDate();
        demand.setTradeDate(java.sql.Date.valueOf(tradeDate));
        demand.setRunSlot("alert:" + targetFactObservationKey);
        demand.setBusinessGeneration(generation);
        demand.setStatus("PENDING");
        demand.setPriority(400);
        demand.setNotBefore(new Date(now + NOT_BEFORE_MILLIS));
        demand.setDeadlineAt(new Date(now + DEADLINE_MILLIS));
        demand.setProviderSubmitCount(0);
        demand.setRowVersion(0L);
        try {
            demandMapper.insert(demand);
            return demand;
        } catch (DuplicateKeyException duplicate) {
            return demandMapper.selectByDemandKey(demandKey);
        }
    }

    @Transactional
    public DemandCreationResult createScheduled(StockWatchItem item,
                                                StockWatchAnalysisSchedulePlanner.DueDecision decision) {
        if (item == null || decision == null || !decision.isDue()) return DemandCreationResult.empty();
        policyService.applyDefaults(item);
        if (!policyService.allowsAutomaticDemand(item, decision.getTriggerType())) {
            return DemandCreationResult.empty();
        }
        String symbol = text(item.getSymbol());
        String market = text(item.getMarket()).toLowerCase(Locale.ROOT);
        String periodType = text(decision.getPeriodType()).toUpperCase(Locale.ROOT);
        long policyVersion = value(item.getAnalysisPolicyVersion());
        String demandKey = sha256("stock-analysis-demand-v2|" + market + "|" + symbol + "|"
                + StockWatchAnalysisPolicyService.CAPABILITY + "|" + periodType + "|"
                + text(decision.getScheduleSlotKey()) + "|" + policyVersion);
        StockWatchAnalysisDemand existing = demandMapper.selectByDemandKey(demandKey);
        if (existing != null) return DemandCreationResult.reused(existing);

        String coalesceKey = coalesceKey(market, symbol, periodType);
        StockWatchAnalysisDemand active = demandMapper.selectActiveByCoalesceKey(coalesceKey);
        Long supersededId = null;
        if (active != null && !demandKey.equals(active.getDemandKey())) {
            if (shouldReuseAnomalyForScheduled(active, policyVersion)) {
                return DemandCreationResult.reused(active);
            }
            if (demandMapper.markSupersededIfUnsubmitted(active.getId(), "superseded_by_new_scheduled_slot",
                    new Date()) == 1) {
                supersededId = active.getId();
            } else {
                return DemandCreationResult.reused(active);
            }
        }

        StockWatchAnalysisDemand demand = new StockWatchAnalysisDemand();
        demand.setDemandKey(demandKey);
        demand.setSymbol(symbol);
        demand.setMarket(market);
        demand.setStockName(text(item.getStockName()));
        demand.setCapability(StockWatchAnalysisPolicyService.CAPABILITY);
        demand.setTriggerType(text(decision.getTriggerType()));
        demand.setScheduleSlotKey(text(decision.getScheduleSlotKey()));
        demand.setRunSlot(text(decision.getRunSlot()));
        demand.setCoalesceKey(coalesceKey);
        demand.setActiveCoalesceKey(coalesceKey);
        demand.setSourcePolicyVersion(policyVersion);
        demand.setReasonCodesJson(JSON.toJSONString(List.of(text(decision.getReason()))));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mode", item.getAnalysisMode());
        context.put("intervalMinutes", item.getAnalysisIntervalMinutes());
        context.put("scheduleSlotKey", decision.getScheduleSlotKey());
        context.put("notBefore", decision.getNotBefore() == null ? null : decision.getNotBefore().toString());
        demand.setTriggerContextJson(JSON.toJSONString(context));
        demand.setPeriodType(periodType);
        demand.setTradeDate(java.sql.Date.valueOf(decision.getTradeDate()));
        demand.setBusinessGeneration(1);
        demand.setSupersededByDemandId(null);
        demand.setStatus("PENDING");
        demand.setPriority(priorityFor(decision.getTriggerType()));
        demand.setNotBefore(toDate(decision.getNotBefore()));
        demand.setDeadlineAt(toDate(decision.getDeadlineAt()));
        demand.setProviderSubmitCount(0);
        demand.setRowVersion(0L);
        return insertDemand(demand, supersededId);
    }

    @Transactional
    public StockWatchAnalysisDemand createAnomaly(StockWatchItem item,
                                                  Long targetFactObservationId,
                                                  String targetFactObservationKey,
                                                  Long targetSourceTimeEpochMs,
                                                  List<String> reasonCodes,
                                                  Map<String, Object> triggerContext,
                                                  ZonedDateTime now) {
        DemandCreationResult result = createAnomalyInternal(item, targetFactObservationId, targetFactObservationKey,
                targetSourceTimeEpochMs, reasonCodes, triggerContext, now);
        return result.getDemand();
    }

    @Transactional
    public DemandCreationResult createAnomalyDetailed(StockWatchItem item,
                                                      Long targetFactObservationId,
                                                      String targetFactObservationKey,
                                                      Long targetSourceTimeEpochMs,
                                                      List<String> reasonCodes,
                                                      Map<String, Object> triggerContext,
                                                      ZonedDateTime now) {
        return createAnomalyInternal(item, targetFactObservationId, targetFactObservationKey,
                targetSourceTimeEpochMs, reasonCodes, triggerContext, now);
    }

    private DemandCreationResult createAnomalyInternal(StockWatchItem item,
                                                       Long targetFactObservationId,
                                                       String targetFactObservationKey,
                                                       Long targetSourceTimeEpochMs,
                                                       List<String> reasonCodes,
                                                       Map<String, Object> triggerContext,
                                                       ZonedDateTime now) {
        if (item == null || targetFactObservationId == null || !StringUtils.hasText(targetFactObservationKey)
                || targetSourceTimeEpochMs == null) {
            return DemandCreationResult.empty();
        }
        policyService.applyDefaults(item);
        if (!policyService.allowsAutomaticDemand(item, "ANOMALY_IMMEDIATE")) {
            return DemandCreationResult.empty();
        }
        String anomalyMode = text(item.getAnomalyTriggerMode()).toUpperCase(Locale.ROOT);
        ZonedDateTime notBefore = now == null ? ZonedDateTime.now(CHINA_ZONE) : now;
        String triggerType = "ANOMALY_IMMEDIATE";
        int priority = 500;
        if ("DEFER_TO_NEXT_SCHEDULE".equals(anomalyMode)) {
            ZonedDateTime next = planner.nextScheduledDueAt(item, notBefore);
            if (next == null) return DemandCreationResult.empty();
            notBefore = next;
            triggerType = "ANOMALY_DEFERRED";
            priority = 350;
        }
        String symbol = text(item.getSymbol());
        String market = text(item.getMarket()).toLowerCase(Locale.ROOT);
        LocalDate tradeDate = Instant.ofEpochMilli(targetSourceTimeEpochMs).atZone(CHINA_ZONE).toLocalDate();
        long policyVersion = value(item.getAnalysisPolicyVersion());
        String demandKey = sha256("stock-analysis-demand-v2|" + market + "|" + symbol + "|"
                + StockWatchAnalysisPolicyService.CAPABILITY + "|DAY|alert|"
                + targetFactObservationKey + "|" + policyVersion + "|" + triggerType);
        StockWatchAnalysisDemand existing = demandMapper.selectByDemandKey(demandKey);
        if (existing != null) return DemandCreationResult.reused(existing);

        String coalesceKey = coalesceKey(market, symbol, "DAY");
        StockWatchAnalysisDemand active = demandMapper.selectActiveByCoalesceKey(coalesceKey);
        Long supersededId = null;
        if (active != null && !demandKey.equals(active.getDemandKey())) {
            if (demandMapper.markSupersededIfUnsubmitted(active.getId(), "superseded_by_new_anomaly_fact",
                    new Date()) == 1) {
                supersededId = active.getId();
            } else {
                return DemandCreationResult.reused(active);
            }
        }

        StockWatchAnalysisDemand demand = new StockWatchAnalysisDemand();
        demand.setDemandKey(demandKey);
        demand.setSymbol(symbol);
        demand.setMarket(market);
        demand.setStockName(text(item.getStockName()));
        demand.setCapability(StockWatchAnalysisPolicyService.CAPABILITY);
        demand.setTriggerType(triggerType);
        demand.setScheduleSlotKey(triggerType + ":" + targetFactObservationKey);
        demand.setRunSlot(anomalyRunSlot(targetFactObservationKey));
        demand.setCoalesceKey(coalesceKey);
        demand.setActiveCoalesceKey(coalesceKey);
        demand.setSourcePolicyVersion(policyVersion);
        demand.setReasonCodesJson(JSON.toJSONString(reasonCodes == null ? List.of("ANOMALY_TRIGGERED") : reasonCodes));
        demand.setTriggerContextJson(JSON.toJSONString(triggerContext == null ? Map.of() : triggerContext));
        demand.setPeriodType("DAY");
        demand.setTargetFactObservationId(targetFactObservationId);
        demand.setTargetFactObservationKey(targetFactObservationKey);
        demand.setTargetSourceTimeEpochMs(targetSourceTimeEpochMs);
        demand.setTradeDate(java.sql.Date.valueOf(tradeDate));
        demand.setBusinessGeneration(1);
        demand.setStatus("PENDING");
        demand.setPriority(priority);
        demand.setNotBefore(toDate(notBefore));
        demand.setDeadlineAt(new Date(toDate(notBefore).getTime() + DEADLINE_MILLIS));
        demand.setProviderSubmitCount(0);
        demand.setRowVersion(0L);
        return insertDemand(demand, supersededId);
    }

    public int supersedePolicyMismatch(StockWatchItem item) {
        if (item == null) return 0;
        policyService.applyDefaults(item);
        return demandMapper.supersedePolicyMismatch(
                text(item.getSymbol()),
                text(item.getMarket()).toLowerCase(Locale.ROOT),
                value(item.getAnalysisPolicyVersion()),
                "analysis_policy_version_changed");
    }

    @Scheduled(
            fixedDelayString = "${stock-watch.alert-analysis-demand.scan-interval-ms:60000}",
            initialDelayString = "${stock-watch.alert-analysis-demand.initial-delay-ms:60000}"
    )
    public void reconcile() {
        if (!running.compareAndSet(false, true)) return;
        try {
            dispatchOne();
            reconcileSubmitted();
        } catch (Exception e) {
            log.warn("stock-watch alert analysis demand reconcile failed error={}", diagnostic(e));
        } finally {
            running.set(false);
        }
    }

    private void dispatchOne() {
        Date now = new Date();
        List<StockWatchAnalysisDemand> candidates = demandMapper.selectDispatchable(now, 1);
        if (candidates == null || candidates.isEmpty()) return;
        StockWatchAnalysisDemand candidate = candidates.get(0);
        if (candidate.getDeadlineAt() != null && !candidate.getDeadlineAt().after(now)) {
            deadLetter(candidate, "analysis_demand_deadline_elapsed_before_submit");
            return;
        }

        String lease = UUID.randomUUID().toString();
        Date leaseUntil = new Date(now.getTime() + 15L * 60L * 1000L);
        if (demandMapper.claim(candidate.getId(), value(candidate.getRowVersion()), lease, leaseUntil, now) != 1) {
            return;
        }

        StockWatchAnalysisDemand claimed = demandMapper.selectById(candidate.getId());
        String blocker = dispatchBlocker(claimed);
        if (StringUtils.hasText(blocker)) {
            demandMapper.markClaimedSuperseded(claimed.getId(), lease, blocker);
            obligationMapper.markSupplementDeadLetterByDemand(claimed.getId(), blocker);
            return;
        }
        try {
            StockWatchTechnicalAnalysis record = technicalAnalysisService.runDemand(claimed);
            StockWatchAnalysisTask task = latestTask(claimed);
            Long taskId = task == null ? null : task.getId();
            if (record != null && "SUCCESS".equalsIgnoreCase(record.getStatus())) {
                satisfy(claimed, taskId, record);
                return;
            }
            if (task != null && inProgress(task.getStatus())) {
                demandMapper.markSubmitted(claimed.getId(), lease, task.getId());
                return;
            }
            demandMapper.markDeadLetter(claimed.getId(), "unified_analysis_terminal_without_success:"
                    + (record == null ? "no_record" : text(record.getStatus())));
            obligationMapper.markSupplementDeadLetterByDemand(claimed.getId(),
                    "技术分析未形成可发送的可信结果");
        } catch (Exception e) {
            StockWatchAnalysisTask task = latestTask(claimed);
            if (task != null && inProgress(task.getStatus())) {
                demandMapper.markSubmitted(claimed.getId(), lease, task.getId());
                log.info("stock-watch alert analysis remains pending demandId={} taskId={} status={} error={}",
                        claimed.getId(), task.getId(), task.getStatus(), diagnostic(e));
                return;
            }
            String error = diagnostic(e);
            if (terminalDispatchBlocker(error)) {
                demandMapper.markClaimedSuperseded(claimed.getId(), lease, error);
                obligationMapper.markSupplementDeadLetterByDemand(claimed.getId(), error);
                return;
            }
            Date retryAt = new Date(System.currentTimeMillis() + NOT_BEFORE_MILLIS);
            if (claimed.getDeadlineAt() != null && !retryAt.before(claimed.getDeadlineAt())) {
                demandMapper.markDeadLetter(claimed.getId(), "unified_analysis_submit_failed:" + error);
                obligationMapper.markSupplementDeadLetterByDemand(claimed.getId(),
                        "技术分析提交失败：" + error);
            } else {
                demandMapper.releaseForRetry(claimed.getId(), lease, retryAt,
                        "unified_analysis_submit_retry:" + error);
            }
        }
    }

    private void reconcileSubmitted() {
        List<StockWatchAnalysisDemand> submitted = demandMapper.selectSubmitted(20);
        if (submitted == null) return;
        Date now = new Date();
        for (StockWatchAnalysisDemand demand : submitted) {
            StockWatchTechnicalAnalysis record = demand.getAnalysisTaskId() == null
                    ? null : analysisMapper.selectByAnalysisTaskId(demand.getAnalysisTaskId());
            if (record != null && "SUCCESS".equalsIgnoreCase(record.getStatus())) {
                satisfy(demand, demand.getAnalysisTaskId(), record);
                continue;
            }
            StockWatchAnalysisTask task = demand.getAnalysisTaskId() == null
                    ? null : taskMapper.selectById(demand.getAnalysisTaskId());
            if (task != null && terminal(task.getStatus())) {
                deadLetter(demand, "targeted_analysis_terminal:" + task.getStatus());
            } else if (task == null && demand.getDeadlineAt() != null && !demand.getDeadlineAt().after(now)) {
                deadLetter(demand, "targeted_analysis_deadline_elapsed");
            }
        }
    }

    private void satisfy(StockWatchAnalysisDemand demand,
                         Long taskId,
                         StockWatchTechnicalAnalysis analysis) {
        if (analysis == null || analysis.getId() == null) return;
        if (demandMapper.markSatisfied(demand.getId(), taskId, analysis.getId()) != 1) {
            StockWatchAnalysisDemand current = demandMapper.selectById(demand.getId());
            if (current == null || !"SATISFIED".equals(current.getStatus())) return;
        }
        String subject = "Stock Watch 补充技术分析 · " + text(demand.getStockName());
        StringBuilder body = new StringBuilder();
        body.append("Stock Watch 技术分析补充\n\n");
        body.append("标的：").append(text(demand.getStockName())).append("（")
                .append(text(demand.getSymbol())).append(".").append(text(demand.getMarket())).append("）\n");
        body.append("关联行情事实：").append(text(demand.getTargetFactObservationKey())).append("\n");
        body.append("说明：本邮件补充此前异动提醒中等待生成的技术分析，分析输入与该提醒使用同一行情事实。\n");
        body.append(emailRenderer.render(analysis, "技术分析结果"));
        List<com.aimeeting.room.entity.StockWatchAlertDeliveryObligation> obligations =
                obligationMapper.selectWaitingByDemandId(demand.getId());
        if (obligations == null) return;
        for (com.aimeeting.room.entity.StockWatchAlertDeliveryObligation obligation : obligations) {
            obligationMapper.markSupplementReady(obligation.getId(), demand.getId(), analysis.getId(),
                    subject, body.toString());
        }
    }

    private void deadLetter(StockWatchAnalysisDemand demand, String error) {
        demandMapper.markDeadLetter(demand.getId(), error);
        obligationMapper.markSupplementDeadLetterByDemand(demand.getId(), error);
    }

    private StockWatchAnalysisTask latestTask(StockWatchAnalysisDemand demand) {
        List<StockWatchAnalysisTask> tasks = taskMapper.selectByObservation(
                demand.getSymbol(), demand.getMarket(), text(demand.getPeriodType()).toUpperCase(Locale.ROOT),
                observationKey(demand));
        if (tasks == null || tasks.isEmpty()) return null;
        return tasks.get(tasks.size() - 1);
    }

    public String observationKey(StockWatchAnalysisDemand demand) {
        String periodType = StringUtils.hasText(demand.getPeriodType())
                ? demand.getPeriodType().trim().toUpperCase(Locale.ROOT) : "DAY";
        if (demand.getTargetSourceTimeEpochMs() != null && StringUtils.hasText(demand.getTargetFactObservationKey())) {
            LocalDate sourceDate = Instant.ofEpochMilli(demand.getTargetSourceTimeEpochMs())
                    .atZone(CHINA_ZONE).toLocalDate();
            String runSlot = StringUtils.hasText(demand.getRunSlot())
                    ? demand.getRunSlot() : "alert:" + demand.getTargetFactObservationKey();
            return periodType + ":" + sourceDate + ":" + runSlot;
        }
        LocalDate tradeDate = demand.getTradeDate() == null
                ? LocalDate.now(CHINA_ZONE)
                : Instant.ofEpochMilli(demand.getTradeDate().getTime()).atZone(CHINA_ZONE).toLocalDate();
        String runSlot = StringUtils.hasText(demand.getRunSlot()) ? demand.getRunSlot() : text(demand.getScheduleSlotKey());
        return periodType + ":" + tradeDate + ":" + runSlot;
    }

    private String dispatchBlocker(StockWatchAnalysisDemand demand) {
        if (demand == null || !"CLAIMED".equals(demand.getStatus())) return "analysis_demand_claim_lost";
        StockWatchItem item = policyService.currentItem(demand.getSymbol(), demand.getMarket());
        if (item == null) return "analysis_policy_stock_missing";
        Long sourceVersion = demand.getSourcePolicyVersion();
        if (sourceVersion != null && !sourceVersion.equals(value(item.getAnalysisPolicyVersion()))) {
            return "analysis_policy_version_changed";
        }
        String triggerType = text(demand.getTriggerType()).toUpperCase(Locale.ROOT);
        if (!policyService.allowsAutomaticDemand(item, triggerType)) {
            return "analysis_policy_disallowed:" + triggerType;
        }
        if (!triggerType.startsWith("ANOMALY") && !ownershipService.isPolicyOwner()) {
            return "analysis_scheduler_ownership_not_policy";
        }
        return "";
    }

    private DemandCreationResult insertDemand(StockWatchAnalysisDemand demand, Long supersededId) {
        try {
            demandMapper.insert(demand);
            if (supersededId != null && demand.getId() != null) {
                demandMapper.linkSuperseded(supersededId, demand.getId());
            }
            return DemandCreationResult.created(demand);
        } catch (DuplicateKeyException duplicate) {
            StockWatchAnalysisDemand existing = demandMapper.selectByDemandKey(demand.getDemandKey());
            if (existing == null && StringUtils.hasText(demand.getActiveCoalesceKey())) {
                existing = demandMapper.selectActiveByCoalesceKey(demand.getActiveCoalesceKey());
            }
            return DemandCreationResult.reused(existing);
        }
    }

    private boolean shouldReuseAnomalyForScheduled(StockWatchAnalysisDemand active, long policyVersion) {
        if (active == null) return false;
        String triggerType = text(active.getTriggerType()).toUpperCase(Locale.ROOT);
        return triggerType.startsWith("ANOMALY")
                && value(active.getSourcePolicyVersion()) == policyVersion;
    }

    private Date toDate(ZonedDateTime value) {
        return value == null ? null : Date.from(value.toInstant());
    }

    private String coalesceKey(String market, String symbol, String periodType) {
        return sha256("stock-analysis-active-v1|" + text(market).toLowerCase(Locale.ROOT) + "|"
                + text(symbol) + "|" + StockWatchAnalysisPolicyService.CAPABILITY + "|"
                + text(periodType).toUpperCase(Locale.ROOT));
    }

    private String anomalyRunSlot(String targetFactObservationKey) {
        String digest = sha256("stock-analysis-alert-run-slot-v1|" + text(targetFactObservationKey));
        int suffixLength = ANALYSIS_RUN_SLOT_MAX_LENGTH - ANOMALY_RUN_SLOT_PREFIX.length();
        return ANOMALY_RUN_SLOT_PREFIX + digest.substring(0, suffixLength);
    }

    private int priorityFor(String triggerType) {
        String value = text(triggerType).toUpperCase(Locale.ROOT);
        if (value.startsWith("ANOMALY")) return 500;
        if ("DAILY_CLOSE".equals(value)) return 450;
        if (value.endsWith("LY_REVIEW")) return 300;
        return 400;
    }

    private boolean inProgress(String status) {
        return "RESERVED".equals(status) || "SUBMITTED".equals(status)
                || "UNKNOWN".equals(status) || "RESPONSE_RECEIVED".equals(status)
                || "PROJECTION_FAILED".equals(status);
    }

    private boolean terminal(String status) {
        return "INVALID_RESPONSE".equals(status) || "FAILED".equals(status)
                || "PROJECTION_INVALID".equals(status);
    }

    private boolean terminalDispatchBlocker(String error) {
        String value = text(error).toLowerCase(Locale.ROOT);
        return value.startsWith("analysis_policy_") || value.startsWith("analysis_scheduler_ownership_not_policy")
                || value.startsWith("analysis_demand_trade_date_mismatch")
                || value.startsWith("analysis_demand_run_slot_mismatch")
                || value.startsWith("analysis_demand_period_type_mismatch");
    }

    private long value(Long raw) {
        return raw == null ? 0L : raw;
    }

    private String text(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private String diagnostic(Exception e) {
        return e == null ? "unknown_error" : (StringUtils.hasText(e.getMessage())
                ? e.getMessage() : e.getClass().getSimpleName());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : hash) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }

    public static class DemandCreationResult {
        private final StockWatchAnalysisDemand demand;
        private final boolean created;

        private DemandCreationResult(StockWatchAnalysisDemand demand, boolean created) {
            this.demand = demand;
            this.created = created;
        }

        public static DemandCreationResult empty() {
            return new DemandCreationResult(null, false);
        }

        public static DemandCreationResult created(StockWatchAnalysisDemand demand) {
            return new DemandCreationResult(demand, true);
        }

        public static DemandCreationResult reused(StockWatchAnalysisDemand demand) {
            return new DemandCreationResult(demand, false);
        }

        public StockWatchAnalysisDemand getDemand() {
            return demand;
        }

        public boolean isCreated() {
            return created;
        }

        public boolean isReused() {
            return demand != null && !created;
        }
    }
}
