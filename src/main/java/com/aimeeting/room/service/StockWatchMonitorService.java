package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchDeliveryConfigMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchAnalysisDemand;
import com.aimeeting.room.entity.StockWatchDeliveryConfig;
import com.aimeeting.room.entity.StockWatchItem;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.service.StockWatchAiJudgeService.AiDecision;
import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
import com.aimeeting.room.service.StockWatchAnalysisDemandService.DemandCreationResult;
import com.aimeeting.room.service.StockWatchRiskPatternService.RiskPatternResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StockWatchMonitorService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter EVENT_TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PRICE_LEVEL_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元");

    private final StockQuoteService stockQuoteService;
    private final NotificationService notificationService;
    private final StockWatchAiRuleService aiRuleService;
    private final StockWatchAiJudgeService aiJudgeService;
    private final StockWatchDeliveryConfigMapper deliveryConfigMapper;
    private final EastmoneyMarketDataService marketDataService;
    private final StockWatchTechnicalAnalysisMapper technicalAnalysisMapper;
    private final StockWatchRiskPatternService riskPatternService;
    private final StockWatchFactPersistenceService factPersistenceService;
    private final StockWatchTechnicalContextService technicalContextService;
    private final StockWatchTechnicalEmailRenderer technicalEmailRenderer;
    private final StockWatchAlertDeliveryService alertDeliveryService;
    private final StockWatchRiskPatternSummaryRenderer riskPatternSummaryRenderer = new StockWatchRiskPatternSummaryRenderer();
    @Autowired(required = false)
    private StockWatchAnalysisPolicyService analysisPolicyService;
    @Autowired(required = false)
    private StockWatchAnalysisDemandService analysisDemandService;
    private final Map<String, RuleState> ruleStates = new LinkedHashMap<>();
    private final Set<String> sentSignalKeys = new LinkedHashSet<>();
    private final Map<String, String> lastAlertAtByStock = new LinkedHashMap<>();
    private final Map<String, AiAlertState> lastAiAlerts = new LinkedHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private boolean stateLoaded = false;
    private ZonedDateTime lastRunAt;
    private String lastRunStatus = "never";

    @Value("${stock-watch.monitor.enabled:true}")
    private boolean enabled;

    @Value("${stock-watch.monitor.notify-enabled:true}")
    private boolean notifyEnabled;

    @Value("${stock-watch.monitor.trading-hours-only:true}")
    private boolean tradingHoursOnly;

    @Value("${stock-watch.monitor.symbols:688017.sh,300760.sz,002594.sz}")
    private String configuredSymbols;

    @Value("${stock-watch.monitor.rules:}")
    private String configuredRules;

    @Value("${stock-watch.monitor.state-file:}")
    private String configuredStateFile;

    @Value("${stock-watch.monitor.scan-interval-ms:600000}")
    private long scanIntervalMs;

    @Value("${stock-watch.monitor.default-alert-interval-minutes:120}")
    private int defaultAlertIntervalMinutes;

    @Value("${stock-watch.monitor.ai-alert-cooldown-minutes:30}")
    private int aiAlertCooldownMinutes;

    @Value("${stock-watch.monitor.ai-context-technical-lookback-days:14}")
    private int aiContextTechnicalLookbackDays;

    @Value("${stock-watch.monitor.ai-context-technical-limit:14}")
    private int aiContextTechnicalLimit;

    @Value("${stock-watch.monitor.ai-context-technical-max-chars:12000}")
    private int aiContextTechnicalMaxChars;

    public StockWatchMonitorService(StockQuoteService stockQuoteService,
                                    NotificationService notificationService,
                                    StockWatchAiRuleService aiRuleService,
                                    StockWatchAiJudgeService aiJudgeService,
                                    StockWatchDeliveryConfigMapper deliveryConfigMapper,
                                    EastmoneyMarketDataService marketDataService,
                                    StockWatchTechnicalAnalysisMapper technicalAnalysisMapper,
                                    StockWatchRiskPatternService riskPatternService,
                                    StockWatchFactPersistenceService factPersistenceService,
                                    StockWatchTechnicalContextService technicalContextService,
                                    StockWatchTechnicalEmailRenderer technicalEmailRenderer,
                                    StockWatchAlertDeliveryService alertDeliveryService) {
        this.stockQuoteService = stockQuoteService;
        this.notificationService = notificationService;
        this.aiRuleService = aiRuleService;
        this.aiJudgeService = aiJudgeService;
        this.deliveryConfigMapper = deliveryConfigMapper;
        this.marketDataService = marketDataService;
        this.technicalAnalysisMapper = technicalAnalysisMapper;
        this.riskPatternService = riskPatternService;
        this.factPersistenceService = factPersistenceService;
        this.technicalContextService = technicalContextService;
        this.technicalEmailRenderer = technicalEmailRenderer;
        this.alertDeliveryService = alertDeliveryService;
    }

    @Scheduled(
            fixedDelayString = "${stock-watch.monitor.scan-interval-ms:600000}",
            initialDelayString = "${stock-watch.monitor.initial-delay-ms:30000}"
    )
    public void scheduledScan() {
        if (!enabled) {
            lastRunStatus = "skipped_disabled";
            log.info("stock-watch monitor scheduled scan skip reason=disabled intervalMs={}", scanIntervalMs);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            lastRunStatus = "skipped_already_running";
            log.info("stock-watch monitor scheduled scan skip reason=already_running intervalMs={}", scanIntervalMs);
            return;
        }
        long startedAt = System.currentTimeMillis();
        log.info("stock-watch monitor scheduled scan start intervalMs={} notifyEnabled={} tradingHoursOnly={}",
                scanIntervalMs, notifyEnabled, tradingHoursOnly);
        try {
            Map<String, Object> result = runOnce(Collections.emptyMap(), "scheduled");
            logScheduledRunResult(result, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            lastRunStatus = "error: " + e.getMessage();
            log.warn("stock-watch monitor scheduled scan failed elapsedMs={} error={}",
                    System.currentTimeMillis() - startedAt, e.getMessage());
        } finally {
            running.set(false);
        }
    }

    public synchronized Map<String, Object> runOnce(Map<String, Object> request, String reason) {
        ensureStateLoaded();
        ZonedDateTime now = ZonedDateTime.now(CHINA_ZONE);
        lastRunAt = now;

        boolean ignoreTradingHours = bool(request.get("ignoreTradingHours"), false);
        Boolean notifyOverride = nullableBool(request.get("notify"));
        boolean notify = notifyOverride != null ? notifyOverride : notifyEnabled;
        List<SupportBreakRule> rules = resolveRules(request.get("rules"));
        List<AiWatchRule> aiRules = resolveAiRules(request.get("aiRules"));
        if (tradingHoursOnly && !ignoreTradingHours && !isTradingSession(now)) {
            lastRunStatus = "skipped_non_trading_session";
            return buildRunResult(now, reason, true, "非交易时段，跳过盯盘扫描", Collections.emptyList());
        }

        if (!request.containsKey("rules") && !request.containsKey("aiRules")) {
            pruneInactiveRuleState(rules, aiRules);
        }
        List<Map<String, Object>> quotes = resolveQuotes(request.get("quotes"), rules, aiRules);
        Map<String, Map<String, Object>> quoteBySymbol = quotes.stream()
                .filter(quote -> text(quote, "symbol", null) != null)
                .collect(Collectors.toMap(
                        quote -> normalizeSymbol(text(quote, "symbol", "")),
                        quote -> quote,
                        (left, ignored) -> left,
                        LinkedHashMap::new
                ));

        List<Map<String, Object>> evaluations = new ArrayList<>();
        Map<String, RiskPatternResult> riskPatternsByStock = buildRiskPatterns(aiRules, quoteBySymbol, now);
        List<Map<String, Object>> factObservations = persistRiskFacts(riskPatternsByStock, quoteBySymbol, now);
        Map<String, Map<String, Object>> factReceiptByStock = factObservations.stream()
                .filter(item -> StringUtils.hasText(text(item.get("symbol"), ""))
                        && StringUtils.hasText(text(item.get("market"), "")))
                .collect(Collectors.toMap(
                        item -> stockKey(text(item.get("symbol"), ""), text(item.get("market"), "")),
                        item -> item,
                        (left, ignored) -> left,
                        LinkedHashMap::new
                ));
        for (SupportBreakRule rule : rules) {
            Map<String, Object> quote = quoteBySymbol.get(rule.symbol);
            Map<String, Object> evaluation = evaluateRule(rule, quote, now, notify);
            evaluations.add(evaluation);
        }
        for (AiWatchRule rule : aiRules) {
            Map<String, Object> quote = quoteBySymbol.get(normalizeSymbol(rule.getSymbol()));
            Map<String, Object> evaluation = evaluateAiRule(
                    rule,
                    quote,
                    riskPatternsByStock.get(stockKey(rule.getSymbol(), rule.getMarket())),
                    factReceiptByStock.get(stockKey(rule.getSymbol(), rule.getMarket())),
                    now,
                    notify
            );
            evaluations.add(evaluation);
        }

        saveStateQuietly();
        lastRunStatus = "success";
        List<Map<String, Object>> analysisFactObservations = analysisFactObservations(evaluations);
        Map<String, Object> result = buildRunResult(now, reason, false, "盯盘扫描完成", evaluations);
        result.put("factObservations", factObservations);
        result.put("factObservationCount", factObservations.size());
        result.put("analysisFactObservations", analysisFactObservations);
        result.put("analysisFactObservationCount", analysisFactObservations.size());
        return result;
    }

    public synchronized Map<String, Object> status() {
        ensureStateLoaded();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("notifyEnabled", notifyEnabled);
        status.put("tradingHoursOnly", tradingHoursOnly);
        status.put("scanIntervalMs", scanIntervalMs);
        status.put("symbols", configuredSymbols);
        status.put("rules", configuredRules);
        status.put("stateFile", stateFile().toString());
        status.put("defaultAlertIntervalMinutes", effectiveDefaultAlertIntervalMinutes());
        status.put("aiAlertCooldownMinutes", effectiveAiAlertCooldownMinutes());
        status.put("lastRunAt", lastRunAt == null ? null : lastRunAt.format(EVENT_TIME_FORMATTER));
        status.put("lastRunStatus", lastRunStatus);
        status.put("ruleStates", stateSnapshot());
        status.put("sentSignalCount", sentSignalKeys.size());
        status.put("lastAlertAtByStock", new LinkedHashMap<>(lastAlertAtByStock));
        status.put("aiAlertStateCount", lastAiAlerts.size());
        List<AiWatchRule> aiRules = aiRuleService.listEnabled();
        status.put("aiRuleCount", aiRules.size());
        status.put("riskPatternEnabledRuleCount", aiRules.stream().filter(AiWatchRule::usesRiskPattern).count());
        return status;
    }

    private Map<String, Object> evaluateRule(SupportBreakRule rule, Map<String, Object> quote, ZonedDateTime now, boolean notify) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", rule.symbol);
        result.put("market", rule.market);
        result.put("ruleId", rule.ruleId);
        result.put("support", rule.support);
        result.put("requiredBreakDays", rule.requiredBreakDays);
        result.put("severity", rule.severity);

        if (quote == null || !bool(quote.get("ok"), true)) {
            result.put("triggered", false);
            result.put("reason", "行情缺失或抓取失败");
            result.put("notificationAttempted", false);
            result.put("emailSent", false);
            return result;
        }

        Double price = number(quote.get("price"), null);
        String name = text(quote, "name", rule.symbol);
        result.put("name", name);
        result.put("price", price);
        if (price == null) {
            result.put("triggered", false);
            result.put("reason", "行情未返回最新价");
            result.put("notificationAttempted", false);
            result.put("emailSent", false);
            return result;
        }

        LocalDate today = now.toLocalDate();
        String stateKey = rule.stateKey();
        RuleState state = ruleStates.computeIfAbsent(stateKey, ignored -> new RuleState());
        if (rule.seedBreakDays != null && !Objects.equals(state.lastSeedSource, rule.ruleId)) {
            state.breakDays = Math.max(state.breakDays, rule.seedBreakDays);
            state.lastSeedSource = rule.ruleId;
        }

        boolean belowSupport = price < rule.support;
        if (belowSupport) {
            if (!today.equals(state.lastBelowDate)) {
                state.breakDays += 1;
                state.lastBelowDate = today;
            }
        } else {
            state.breakDays = 0;
            state.lastBelowDate = null;
        }
        state.lastPrice = price;
        state.lastEvaluatedAt = now.format(EVENT_TIME_FORMATTER);

        boolean triggered = belowSupport && state.breakDays >= rule.requiredBreakDays;
        String reason = triggered
                ? "price=" + format(price) + " 低于 support=" + format(rule.support)
                + "，break_days=" + state.breakDays + "/" + rule.requiredBreakDays + "，触发 " + rule.ruleId
                : "未触发：price=" + format(price) + "，support=" + format(rule.support)
                + "，break_days=" + state.breakDays + "/" + rule.requiredBreakDays;
        String eventId = "watch_" + rule.symbol + "_" + rule.ruleId + "_" + today;
        String dedupeKey = today + ":" + rule.symbol + ":" + rule.ruleId + ":" + state.breakDays;
        boolean duplicate = sentSignalKeys.contains(dedupeKey);
        boolean notificationAttempted = triggered && notify && !duplicate;
        boolean emailSent = false;
        AlertSendResult alertResult = AlertSendResult.notAttempted();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", eventId);
        event.put("timestamp", now.format(EVENT_TIME_FORMATTER));
        event.put("symbol", rule.symbol);
        event.put("market", rule.market);
        event.put("name", name);
        event.put("trigger_type", rule.ruleId);
        event.put("severity", rule.severity);
        event.put("price", price);
        event.put("support", rule.support);
        event.put("break_days", state.breakDays);
        event.put("required_break_days", rule.requiredBreakDays);
        event.put("triggered", triggered);
        event.put("reason", reason);

        if (notificationAttempted) {
            alertResult = sendTriggeredEmail(rule, name, reason, event, now);
            emailSent = alertResult.sent;
            if (emailSent) {
                sentSignalKeys.add(dedupeKey);
                trimSentSignalKeys();
            }
        }

        result.put("breakDays", state.breakDays);
        result.put("triggered", triggered);
        result.put("reason", reason);
        result.put("event", event);
        result.put("duplicate", duplicate);
        result.put("notificationAttempted", alertResult.attempted);
        result.put("notificationSkippedReason", alertResult.skippedReason);
        result.put("notificationError", alertResult.errorMessage);
        result.put("emailSent", emailSent);
        return result;
    }

    private Map<String, Object> evaluateAiRule(AiWatchRule rule,
                                               Map<String, Object> quote,
                                               RiskPatternResult riskPattern,
                                               Map<String, Object> factReceipt,
                                               ZonedDateTime now,
                                               boolean notify) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "AI_RULE");
        result.put("symbol", rule.getSymbol());
        result.put("market", rule.getMarket());
        result.put("ruleId", rule.getId());
        result.put("name", rule.getName());
        result.put("conditionText", rule.getConditionText());
        result.put("minConfidence", rule.getMinConfidence());
        result.put("severity", rule.getSeverity());
        result.put("requiredCapabilities", rule.getRequiredCapabilities());
        result.put("riskPatternTriggerMode", rule.getRiskPatternTriggerMode());

        if (!rule.isEnabled()) {
            result.put("triggered", false);
            result.put("reason", "AI观察条件未启用");
            result.put("notificationAttempted", false);
            result.put("emailSent", false);
            return result;
        }
        if (quote == null || !bool(quote.get("ok"), true)) {
            result.put("triggered", false);
            result.put("reason", "行情缺失或抓取失败，未调用DeepSeek判断");
            result.put("notificationAttempted", false);
            result.put("emailSent", false);
            return result;
        }

        Map<String, Object> aiContext = buildAiContext(rule, quote, riskPattern, now, rule.isGeneralLegacy());
        AiDecision decision = rule.isQuoteAnomalyV1()
                ? aiJudgeService.judgeDeterministic(rule, quote)
                : (rule.isGeneralLegacy()
                ? aiJudgeService.judge(rule, quote, now, aiContext)
                : aiJudgeService.judgeDeterministic(rule, quote));
        if (rule.requiresRiskPattern() && riskPattern != null && riskPattern.hasTriggeredPattern()) {
            decision.setTriggered(true);
            decision.setShouldNotify(true);
            decision.setConfidence(1.0);
            decision.setSeverity(rule.getSeverity());
            decision.setReason(triggeredRiskPatternReason(riskPattern));
            decision.setAnalysis("本地L0风险结构已命中；连续主力意图解释由同一 observation 的技术分析任务统一生成。");
            decision.setMatchedSignals(triggeredRiskPatternIds(riskPattern));
            decision.setModel("deterministic-risk-pattern");
        }
        boolean confidenceMet = decision.getConfidence() >= rule.getMinConfidence();
        boolean modelReportedTriggered = decision.isTriggered() && confidenceMet;
        RuleCapabilityGate capabilityGate = evaluateRuleCapabilityGate(rule, decision, riskPattern, modelReportedTriggered);
        boolean triggered = modelReportedTriggered && capabilityGate.allowed;
        LocalDate today = now.toLocalDate();
        AiSignalSnapshot signalSnapshot = buildAiSignalSnapshot(rule, quote, decision);
        AiAlertGate alertGate = evaluateAiAlertGate(rule, quote, decision, signalSnapshot, now, notify, confidenceMet, triggered, today);
        boolean notificationAttempted = alertGate.allowed;
        boolean emailSent = false;
        AlertSendResult alertResult = alertGate.allowed
                ? AlertSendResult.notAttempted()
                : AlertSendResult.skipped(alertGate.reason);

        Map<String, Object> riskPatternSummary = riskPatternSummaryRenderer.summarize(riskPattern, factReceipt);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", "ai_watch_" + rule.getSymbol() + "_" + rule.getMarket() + "_" + rule.getId() + "_" + today);
        event.put("timestamp", now.format(EVENT_TIME_FORMATTER));
        event.put("symbol", rule.getSymbol());
        event.put("market", rule.getMarket());
        event.put("name", rule.getName());
        event.put("trigger_type", "AI_RULE");
        event.put("rule_id", rule.getId());
        event.put("severity", decision.getSeverity());
        event.put("condition_text", rule.getConditionText());
        event.put("triggered", triggered);
        event.put("model_reported_triggered", modelReportedTriggered);
        event.put("trigger_evidence_policy", capabilityGate.policy);
        event.put("trigger_evidence_allowed", capabilityGate.allowed);
        event.put("trigger_evidence_gate_reason", capabilityGate.reason);
        event.put("confidence", decision.getConfidence());
        event.put("min_confidence", rule.getMinConfidence());
        event.put("reason", decision.getReason());
        event.put("matched_signals", decision.getMatchedSignals());
        event.put("missing_data", decision.getMissingData());
        event.put("belief_update", decision.getBeliefUpdate());
        event.put("quote", quote);
        if (validFactReceipt(factReceipt)) {
            applyFactReceipt(event, factReceipt);
        }
        event.put("ai_context_status", aiContextStatus(aiContext));
        event.put("ai_context_missing_data", aiContext == null ? Collections.emptyList() : aiContext.get("missingData"));
        if (riskPattern != null) {
            event.put("risk_pattern_observations", riskPattern.getObservations());
            if (riskPatternSummary != null) {
                event.put("riskPatternSummary", riskPatternSummary);
            }
        }
        appendAiContextHistoryMetadata(event, aiContext);
        event.put("ai_alert_state_key", aiAlertStateKey(rule));
        event.put("ai_signal_shape_fingerprint", signalSnapshot.signalShapeFingerprint);
        event.put("ai_risk_score", signalSnapshot.riskScore);
        event.put("ai_risk_tier", signalSnapshot.riskTier);
        event.put("ai_normalized_facts", signalSnapshot.normalizedFacts);
        event.put("ai_alert_decision_reason", alertGate.reason);
        event.put("ai_alert_signal_shape_changed", alertGate.signalShapeChanged);
        event.put("ai_alert_severity_rank_up", alertGate.severityRankUp);
        event.put("ai_alert_material_risk_delta", alertGate.materialRiskDelta);
        queueUnifiedAnalysisDemand(rule, event, quote, factReceipt, now);

        if (notificationAttempted) {
            alertResult = sendAiTriggeredEmail(rule, decision, event, now);
            emailSent = alertResult.sent;
            if (emailSent) {
                markAiAlertSent(rule, signalSnapshot, decision, now);
            }
        }

        result.put("triggered", triggered);
        result.put("modelReportedTriggered", modelReportedTriggered);
        result.put("triggerEvidencePolicy", capabilityGate.policy);
        result.put("triggerEvidenceAllowed", capabilityGate.allowed);
        result.put("triggerEvidenceGateReason", capabilityGate.reason);
        result.put("alertTriggerConfidence", decision.getConfidence());
        result.put("alertTriggerConfidenceSemantics", decision.getModel() != null && decision.getModel().startsWith("deterministic")
                ? "deterministic_match_indicator_not_probability"
                : "model_reported_rule_match_confidence_not_calibrated_price_probability");
        result.put("riskPatternObservations", riskPattern == null ? null : riskPattern.getObservations());
        if (riskPatternSummary != null) {
            result.put("riskPatternSummary", riskPatternSummary);
        }
        result.put("confidenceMet", confidenceMet);
        result.put("decision", decision);
        result.put("reason", decision.getReason());
        result.put("event", event);
        result.put("analysisFactObservation", event.get("analysis_fact_observation"));
        result.put("analysisDemandStatus", event.get("analysis_demand_status"));
        result.put("analysisDemandReason", event.get("analysis_demand_reason"));
        result.put("analysisDemandId", event.get("analysis_demand_id"));
        result.put("analysisDemandKey", event.get("analysis_demand_key"));
        result.put("analysisDemandTriggerType", event.get("analysis_demand_trigger_type"));
        result.put("analysisDemandError", event.get("analysis_demand_error"));
        result.put("aiContextStatus", aiContextStatus(aiContext));
        result.put("duplicate", alertGate.duplicate);
        result.put("aiAlertStateKey", aiAlertStateKey(rule));
        result.put("aiAlertDecisionReason", alertGate.reason);
        result.put("aiSignalShapeChanged", alertGate.signalShapeChanged);
        result.put("aiSeverityRankUp", alertGate.severityRankUp);
        result.put("aiMaterialRiskDelta", alertGate.materialRiskDelta);
        result.put("aiSignalShapeFingerprint", signalSnapshot.signalShapeFingerprint);
        result.put("aiRiskScore", signalSnapshot.riskScore);
        result.put("aiRiskTier", signalSnapshot.riskTier);
        result.put("notificationAttempted", alertResult.attempted);
        result.put("notificationSkippedReason", alertResult.skippedReason);
        result.put("notificationError", alertResult.errorMessage);
        result.put("emailSent", emailSent);
        log.info("stock-watch AI alert evaluated symbol={}.{} ruleId={} triggered={} confidenceMet={} shouldNotify={} skipReason={} attempted={} sent={} signalShapeChanged={} severityRankUp={} materialRiskDelta={}",
                rule.getSymbol(), rule.getMarket(), rule.getId(), triggered, confidenceMet, decision.isShouldNotify(),
                firstNonBlank(alertResult.skippedReason, alertGate.reason, "--"),
                alertResult.attempted, emailSent, alertGate.signalShapeChanged, alertGate.severityRankUp, alertGate.materialRiskDelta);
        return result;
    }

    private AiAlertGate evaluateAiAlertGate(AiWatchRule rule,
                                            Map<String, Object> quote,
                                            AiDecision decision,
                                            AiSignalSnapshot snapshot,
                                            ZonedDateTime now,
                                            boolean notify,
                                            boolean confidenceMet,
                                            boolean triggered,
                                            LocalDate today) {
        if (!decision.isTriggered()) {
            return AiAlertGate.blocked("notTriggered", false);
        }
        if (!confidenceMet) {
            return AiAlertGate.blocked("confidenceBelowMin", false);
        }
        if (!notify) {
            return AiAlertGate.blocked("notifyDisabled", false);
        }
        if (!rule.isNotify()) {
            return AiAlertGate.blocked("ruleNotifyFalse", false);
        }
        if (!decision.isShouldNotify()) {
            return AiAlertGate.blocked("decisionShouldNotifyFalse", false);
        }
        if (alertDeliveryService.hasDurableCooldown(rule.getId(), rule.getSymbol(), rule.getMarket(),
                snapshot.signalShapeFingerprint, now)) {
            return AiAlertGate.blocked("durableDeliveryCooldown", true);
        }

        String stateKey = aiAlertStateKey(rule);
        AiAlertState previous = lastAiAlerts.get(stateKey);
        if (previous == null && sentSignalKeys.contains(aiDedupeKey(today, rule))) {
            AiAlertState legacy = new AiAlertState();
            legacy.lastSentAt = firstNonBlank(lastAlertAtByStock.get(stockKey(rule.getSymbol(), rule.getMarket())),
                    now.format(EVENT_TIME_FORMATTER));
            legacy.signalShapeFingerprint = snapshot.signalShapeFingerprint;
            legacy.severityRank = snapshot.severityRank;
            legacy.riskScore = snapshot.riskScore;
            legacy.riskTier = snapshot.riskTier;
            legacy.normalizedFacts = new LinkedHashMap<>(snapshot.normalizedFacts);
            legacy.reason = firstNonBlank(decision.getReason(), "legacy_ai_sent_key_initialized");
            legacy.source = "legacy_sent_signal_key";
            lastAiAlerts.put(stateKey, legacy);
            trimAiAlertState();
            return AiAlertGate.blocked("legacyAiSentKeyInitialized", true);
        }

        if (previous == null) {
            return AiAlertGate.allowed("firstAiAlert");
        }

        ZonedDateTime lastSentAt = parseEventTime(previous.lastSentAt);
        if (lastSentAt != null) {
            long elapsed = Duration.between(lastSentAt, now).toMinutes();
            int cooldown = effectiveAiAlertCooldownMinutes();
            if (elapsed < cooldown) {
                return AiAlertGate.blocked("aiAlertCooldown:" + Math.max(0, elapsed) + "/" + cooldown + "m", true);
            }
        }

        boolean signalShapeChanged = !Objects.equals(firstNonBlank(previous.signalShapeFingerprint, ""),
                firstNonBlank(snapshot.signalShapeFingerprint, ""));
        boolean severityRankUp = snapshot.severityRank > previous.severityRank;
        boolean materialRiskDelta = materialRiskDelta(previous, snapshot);
        if (signalShapeChanged || severityRankUp || materialRiskDelta) {
            return AiAlertGate.allowed("aiAlertChanged", signalShapeChanged, severityRankUp, materialRiskDelta);
        }
        return AiAlertGate.blocked("fingerprintUnchanged", true, signalShapeChanged, severityRankUp, materialRiskDelta);
    }

    private AiSignalSnapshot buildAiSignalSnapshot(AiWatchRule rule, Map<String, Object> quote, AiDecision decision) {
        Map<String, Object> facts = new LinkedHashMap<>();
        List<String> shape = new ArrayList<>();

        Double changePercent = number(quote.get("changePercent"), null);
        String changeDirection = directionalBucket(changePercent, 0.3);
        int changeAbsBucket = absBucket(changePercent, 0.5);
        facts.put("changeDirection", changeDirection);
        facts.put("changeAbsBucket", changeAbsBucket);
        facts.put("changePercent", changePercent);
        shape.add("changeDirection=" + changeDirection);

        Double volumeRatio = number(quote.get("volumeRatio"), null);
        int volumeRatioBucket = absBucket(volumeRatio, 0.5);
        facts.put("volumeRatioBucket", volumeRatioBucket);
        facts.put("volumeRatio", volumeRatio);

        Double turnover = number(quote.get("turnover"), null);
        int turnoverBucket = absBucket(turnover, 0.5);
        facts.put("turnoverBucket", turnoverBucket);
        facts.put("turnover", turnover);

        Double mainInflow = number(quote.get("mainInflow"), null);
        String flowDirection = directionalBucket(mainInflow, 10_000_000.0);
        int mainInflowAbsBucket = absBucket(mainInflow, 10_000_000.0);
        facts.put("mainFlowDirection", flowDirection);
        facts.put("mainInflowAbsBucket", mainInflowAbsBucket);
        facts.put("mainInflow", mainInflow);
        shape.add("mainFlowDirection=" + flowDirection);

        double absChange = changePercent == null ? 0.0 : Math.abs(changePercent);
        double absMainInflow = mainInflow == null ? 0.0 : Math.abs(mainInflow);
        boolean absChangeSignal = absChange >= 5.0;
        boolean nearLimit = absChange >= 9.5;
        boolean limitMove = absChange >= 9.8;
        boolean volumeSpikeWithMove = volumeRatio != null && volumeRatio >= 2.0 && absChange >= 2.0;
        boolean mainFlowExtreme = absMainInflow >= 100_000_000.0;
        boolean turnoverWithMove = turnover != null && turnover >= 5.0 && absChange >= 3.0;
        addSignalShape(shape, facts, "absChange>=5", absChangeSignal);
        addSignalShape(shape, facts, "nearLimit", nearLimit);
        addSignalShape(shape, facts, "limitMove", limitMove);
        addSignalShape(shape, facts, "volumeSpikeWithMove", volumeSpikeWithMove);
        addSignalShape(shape, facts, "mainFlowExtreme", mainFlowExtreme);
        addSignalShape(shape, facts, "turnoverWithMove", turnoverWithMove);

        int severityRank = severityRank(firstNonBlank(decision.getSeverity(), rule.getSeverity()));
        int riskScore = severityRank
                + (absChangeSignal ? 2 : 0)
                + (nearLimit ? 2 : 0)
                + (limitMove ? 1 : 0)
                + (volumeSpikeWithMove ? 1 : 0)
                + (volumeRatio != null && volumeRatio >= 4.0 ? 1 : 0)
                + (mainFlowExtreme ? 2 : 0)
                + (turnoverWithMove ? 1 : 0)
                + Math.min(3, changeAbsBucket / 4)
                + Math.min(3, mainInflowAbsBucket / 10);
        facts.put("severityRank", severityRank);
        facts.put("riskScore", riskScore);

        Collections.sort(shape);
        AiSignalSnapshot snapshot = new AiSignalSnapshot();
        snapshot.signalShapeFingerprint = String.join("|", shape);
        snapshot.normalizedFacts = facts;
        snapshot.severityRank = severityRank;
        snapshot.riskScore = riskScore;
        snapshot.riskTier = riskTier(riskScore);
        return snapshot;
    }

    private void addSignalShape(List<String> shape, Map<String, Object> facts, String key, boolean matched) {
        facts.put(key, matched);
        if (matched) {
            shape.add(key);
        }
    }

    private boolean materialRiskDelta(AiAlertState previous, AiSignalSnapshot snapshot) {
        if (previous == null || snapshot == null) {
            return false;
        }
        if (snapshot.riskScore - previous.riskScore >= 2) {
            return true;
        }
        int previousFlowBucket = integerFromMap(previous.normalizedFacts, "mainInflowAbsBucket", 0);
        int currentFlowBucket = integerFromMap(snapshot.normalizedFacts, "mainInflowAbsBucket", 0);
        if (currentFlowBucket - previousFlowBucket >= 2) {
            return true;
        }
        int previousChangeBucket = integerFromMap(previous.normalizedFacts, "changeAbsBucket", 0);
        int currentChangeBucket = integerFromMap(snapshot.normalizedFacts, "changeAbsBucket", 0);
        return currentChangeBucket - previousChangeBucket >= 2;
    }

    private void markAiAlertSent(AiWatchRule rule,
                                 AiSignalSnapshot snapshot,
                                 AiDecision decision,
                                 ZonedDateTime now) {
        AiAlertState state = new AiAlertState();
        state.lastSentAt = now.format(EVENT_TIME_FORMATTER);
        state.signalShapeFingerprint = snapshot.signalShapeFingerprint;
        state.severityRank = snapshot.severityRank;
        state.riskScore = snapshot.riskScore;
        state.riskTier = snapshot.riskTier;
        state.normalizedFacts = new LinkedHashMap<>(snapshot.normalizedFacts);
        state.reason = firstNonBlank(decision.getReason(), "");
        state.source = "ai_alert_sent";
        lastAiAlerts.put(aiAlertStateKey(rule), state);
        trimAiAlertState();
    }

    private Map<String, Object> buildAiContext(AiWatchRule rule,
                                               Map<String, Object> quote,
                                               RiskPatternResult riskPattern,
                                               ZonedDateTime now,
                                               boolean allowExternalMarketData) {
        boolean enhanced = requiresEnhancedAiContext(rule);
        Map<String, Object> recentTechnical = recentTechnicalAnalysisContext(rule, now, quote);
        if (!enhanced && recentTechnical.isEmpty() && riskPattern == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("status", recentTechnical.isEmpty() ? "requested" : "available");
        context.put("reason", enhanced ? "condition_requires_enhanced_context" : "technical_history_for_ai_rule");
        context.put("requestedAt", now.format(EVENT_TIME_FORMATTER));
        if (!recentTechnical.isEmpty()) {
            context.put("recentTechnicalAnalysis", recentTechnical);
        }
        if (riskPattern != null) {
            Map<String, Object> currentFacts = new LinkedHashMap<>();
            currentFacts.put("riskPatternObservations", riskPattern.getObservations());
            context.put("currentFacts", currentFacts);
            context.put("riskPatternGuidance", riskPattern.getGuidance());
        }

        if (!enhanced || !allowExternalMarketData) {
            return context;
        }

        try {
            EastmoneyMarketDataService.MarketDataBundle bundle = marketDataService.buildBundle(
                    rule.getSymbol(), rule.getMarket(), "DAY", quote);
            if (bundle == null) {
                markMarketDataUnavailable(context, !recentTechnical.isEmpty(), "market_data_bundle_empty");
                return context;
            }
            List<String> requiredMissing = bundle.getRequiredMissing();
            if (requiredMissing != null && !requiredMissing.isEmpty()) {
                markMarketDataUnavailable(context, !recentTechnical.isEmpty(), "required_market_data_missing");
                context.put("missingData", requiredMissing);
                return context;
            }
            Map<String, Object> payload = bundle.getPayload();
            context.put("status", "available");
            context.put("marketDataStatus", "available");
            context.put("source", "EastmoneyMarketDataService.buildBundle compact summary");
            context.put("asOf", payload.get("asOf"));

            Map<String, Object> currentFacts = context.get("currentFacts") instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) context.get("currentFacts"))
                    : new LinkedHashMap<>();
            copyIfPresent(payload, currentFacts, "anchorFacts");
            copyIfPresent(payload, currentFacts, "technicalIndicators");
            copyIfPresent(payload, currentFacts, "valuation");
            copyIfPresent(payload, currentFacts, "marketBenchmark");
            copyIfPresent(payload, currentFacts, "dataQuality");
            if (!currentFacts.isEmpty()) {
                context.put("currentFacts", currentFacts);
                refreshPriorValidations(recentTechnical, quote, currentFacts, now);
                trimTechnicalContextToMaxChars(recentTechnical);
            }

            Map<String, Object> recentSeries = new LinkedHashMap<>();
            putTailIfPresent(payload, recentSeries, "minuteTrends5d", 6);
            putTailIfPresent(payload, recentSeries, "intradayFundFlow", 6);
            putHeadIfPresent(payload, recentSeries, "latestNews", 3);
            if (!recentSeries.isEmpty()) {
                context.put("recentSeries", recentSeries);
            }
            return context;
        } catch (Exception e) {
            markMarketDataUnavailable(context, !recentTechnical.isEmpty(),
                    firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
            return context;
        }
    }

    private Map<String, RiskPatternResult> buildRiskPatterns(List<AiWatchRule> aiRules,
                                                              Map<String, Map<String, Object>> quoteBySymbol,
                                                              ZonedDateTime now) {
        Map<String, RiskPatternResult> results = new LinkedHashMap<>();
        Set<String> stocks = aiRules.stream()
                .filter(AiWatchRule::usesRiskPattern)
                .map(rule -> stockKey(rule.getSymbol(), rule.getMarket()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String stock : stocks) {
            StockRef ref = parseStockRef(stock);
            if (ref == null) {
                log.warn("stock-watch L0 stock key invalid stockKey={}", stock);
                continue;
            }
            String symbol = ref.symbol;
            String market = ref.market;
            Map<String, Object> quote = quoteBySymbol.get(normalizeSymbol(symbol));
            List<Map<String, Object>> dailyBars;
            try {
                dailyBars = marketDataService.fetchDailyBars(symbol, market, 80);
            } catch (Exception e) {
                log.warn("stock-watch L0 daily bars unavailable symbol={}.{} error={}", symbol, market, e.getMessage());
                dailyBars = Collections.emptyList();
            }
            RiskPatternResult result = riskPatternService.analyze(symbol, market, quote, dailyBars, now);
            results.put(stock, result);
            log.info("stock-watch L0 evaluated symbol={}.{} status={} active={} triggered={}",
                    symbol, market, result.getObservations().get("evaluationStatus"),
                    result.isActive(), result.hasTriggeredPattern());
        }
        return results;
    }

    private RuleCapabilityGate evaluateRuleCapabilityGate(AiWatchRule rule,
                                                            AiDecision decision,
                                                            RiskPatternResult riskPattern,
                                                            boolean modelReportedTriggered) {
        if (rule == null || rule.isGeneralLegacy()) {
            return RuleCapabilityGate.allowed("LEGACY_MODEL_RULE", "general_legacy_compatibility");
        }
        if (!modelReportedTriggered) {
            return RuleCapabilityGate.allowed(rule.getRiskPatternTriggerMode(), "model_or_structured_rule_not_triggered");
        }
        if (rule.requiresRiskPattern()) {
            return riskPattern != null && riskPattern.hasTriggeredPattern()
                    ? RuleCapabilityGate.allowed("RISK_PATTERN_REQUIRED", "local_risk_pattern_triggered")
                    : RuleCapabilityGate.blocked("RISK_PATTERN_REQUIRED", "local_risk_pattern_not_triggered");
        }
        boolean structuredQuoteTriggered = decision != null
                && StringUtils.hasText(decision.getReason())
                && decision.getReason().startsWith("结构化行情规则命中");
        return structuredQuoteTriggered
                ? RuleCapabilityGate.allowed("L0_ADVISORY", "structured_quote_rule_triggered")
                : RuleCapabilityGate.blocked("L0_ADVISORY", "model_finding_cannot_open_notification");
    }

    private String triggeredRiskPatternReason(RiskPatternResult riskPattern) {
        List<String> ids = triggeredRiskPatternIds(riskPattern);
        return ids.isEmpty() ? "本地L0风险结构未命中" : "本地L0风险结构命中：" + String.join("、", ids);
    }

    private List<String> triggeredRiskPatternIds(RiskPatternResult riskPattern) {
        if (riskPattern == null || !(riskPattern.getObservations().get("patterns") instanceof List)) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (Object raw : (List<?>) riskPattern.getObservations().get("patterns")) {
            if (!(raw instanceof Map)) continue;
            Map<?, ?> item = (Map<?, ?>) raw;
            if ("triggered".equals(item.get("status")) && item.get("patternId") != null) {
                ids.add(String.valueOf(item.get("patternId")));
            }
        }
        return ids;
    }

    private List<Map<String, Object>> persistRiskFacts(Map<String, RiskPatternResult> riskPatternsByStock,
                                                       Map<String, Map<String, Object>> quoteBySymbol,
                                                       ZonedDateTime now) {
        if (riskPatternsByStock == null || riskPatternsByStock.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> receipts = new ArrayList<>();
        for (Map.Entry<String, RiskPatternResult> entry : riskPatternsByStock.entrySet()) {
            StockRef ref = parseStockRef(entry.getKey());
            if (ref == null) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("symbol", entry.getKey());
                failed.put("market", "");
                failed.put("status", "ERROR");
                failed.put("reason", "invalid_stock_key");
                receipts.add(failed);
                continue;
            }
            String symbol = ref.symbol;
            String market = ref.market;
            Map<String, Object> quote = quoteBySymbol.get(normalizeSymbol(symbol));
            try {
                Map<String, Object> receipt = factPersistenceService.observe(
                        symbol,
                        market,
                        quote == null ? symbol : text(quote, "name", symbol),
                        quote,
                        entry.getValue(),
                        now
                ).toMap();
                receipt.put("symbol", symbol);
                receipt.put("market", market);
                receipts.add(receipt);
            } catch (Exception e) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("symbol", symbol);
                failed.put("market", market);
                failed.put("status", "ERROR");
                failed.put("reason", firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
                receipts.add(failed);
                log.warn("stock-watch fact observation persist failed symbol={}.{} error={}", symbol, market, e.getMessage());
            }
        }
        return receipts;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> analysisFactObservations(List<Map<String, Object>> evaluations) {
        if (evaluations == null || evaluations.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> receipts = new ArrayList<>();
        for (Map<String, Object> evaluation : evaluations) {
            Object receipt = evaluation == null ? null : evaluation.get("analysisFactObservation");
            if (receipt instanceof Map) {
                receipts.add(new LinkedHashMap<>((Map<String, Object>) receipt));
            }
        }
        return receipts;
    }

    private boolean requiresEnhancedAiContext(AiWatchRule rule) {
        String condition = firstNonBlank(rule == null ? null : rule.getConditionText(), "").toLowerCase(Locale.ROOT);
        return containsAny(condition,
                "技术面", "k线", "kline", "均线", "ma5", "ma10", "ma20", "ma60",
                "macd", "rsi", "boll", "布林", "资金流", "主力", "估值", "pe_ttm", "pb分位", "市盈率", "市净率",
                "新闻", "公告", "股东户数", "支撑", "压力");
    }

    private boolean containsAny(String value, String... tokens) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String token : tokens) {
            if (value.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void markMarketDataUnavailable(Map<String, Object> context, boolean hasHistoricalContext, String error) {
        context.put("marketDataStatus", "unavailable");
        context.put("status", hasHistoricalContext ? "available" : "unavailable");
        context.put("error", error);
    }

    private Map<String, Object> recentTechnicalAnalysisContext(AiWatchRule rule,
                                                               ZonedDateTime now,
                                                               Map<String, Object> quote) {
        if (technicalAnalysisMapper == null || rule == null) {
            return Collections.emptyMap();
        }
        int lookbackDays = effectiveTechnicalLookbackDays();
        int limit = effectiveTechnicalLimit();
        LocalDate end = now.toLocalDate();
        LocalDate start = end.minusDays(Math.max(0, lookbackDays - 1L));
        try {
            List<StockWatchTechnicalAnalysis> records = technicalAnalysisMapper.selectRecentSuccessfulSlotsByDateRange(
                    rule.getSymbol(),
                    rule.getMarket(),
                    java.sql.Date.valueOf(start),
                    java.sql.Date.valueOf(end),
                    "DAY",
                    limit
            );
            if (records == null || records.isEmpty()) {
                return Collections.emptyMap();
            }
            records = new ArrayList<>(records);
            Collections.reverse(records);
            List<Map<String, Object>> compactRecords = records.stream()
                    .filter(Objects::nonNull)
                    .map(this::toTechnicalHistoryRecord)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (compactRecords.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("status", "available");
            context.put("lookbackDays", lookbackDays);
            context.put("windowStart", start.toString());
            context.put("windowEnd", end.toString());
            context.put("recordCount", compactRecords.size());
            context.put("maxRecords", limit);
            context.put("selectionPolicy", "recent_successful_day_slots_limited_then_time_asc");
            context.put("usage", "historical_analysis_only_for_analysis_continuity_not_trigger_fact");
            context.put("records", compactRecords);
            refreshPriorValidations(context, quote, Collections.emptyMap(), now);
            trimTechnicalContextToMaxChars(context);
            return context;
        } catch (Exception e) {
            log.warn("stock-watch AI context technical history unavailable symbol={}.{} error={}",
                    rule.getSymbol(), rule.getMarket(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    private Map<String, Object> toTechnicalHistoryRecord(StockWatchTechnicalAnalysis analysis) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", analysis.getId());
        item.put("date", analysisDateString(analysis.getAnalysisDate()));
        item.put("time", analysis.getRunSlot());
        item.put("periodType", analysis.getPeriodType());
        item.put("status", analysis.getStatus());
        item.put("stance", analysis.getStance());
        item.put("tone", analysis.getTone());
        item.put("confidence", analysis.getConfidence());
        item.put("gmtCreate", dateTimeString(analysis.getGmtCreate()));
        putTextIfPresent(item, "title", analysis.getTitle(), 90);
        putTextIfPresent(item, "conclusion", analysis.getConclusion(), 280);
        List<String> evidence = compactJsonStringArray(analysis.getEvidenceJson(), 3, 140);
        if (!evidence.isEmpty()) {
            item.put("evidence", evidence);
        }
        List<String> missingData = compactJsonStringArray(analysis.getMissingDataJson(), 3, 120);
        if (!missingData.isEmpty()) {
            item.put("missingData", missingData);
        }
        JSONObject mainForceIntent = parseJsonObject(analysis.getMainForceIntentJson());
        boolean explicitEvidenceQuality = mainForceIntent != null
                && (mainForceIntent.containsKey("evidenceQuality") || mainForceIntent.containsKey("contractVersion"));
        Map<String, Object> inferredIntent = explicitEvidenceQuality
                ? Collections.emptyMap() : inferLegacyMainForceIntent(analysis);
        if (mainForceIntent != null && !mainForceIntent.isEmpty() && !isUnknownMainForceIntent(mainForceIntent)) {
            item.put("mainForceIntent", mainForceIntent);
        } else if (!inferredIntent.isEmpty()) {
            item.put("mainForceIntent", inferredIntent);
            item.put("mainForceIntentSource", "legacy_text_fallback");
        } else if (mainForceIntent != null && !mainForceIntent.isEmpty()) {
            item.put("mainForceIntent", mainForceIntent);
        }
        putTextIfPresent(item, "mainForceIntentStatus", analysis.getMainForceIntentStatus(), 40);
        return item;
    }

    private boolean isUnknownMainForceIntent(Map<?, ?> mainForceIntent) {
        String intent = firstNonBlank(mapText(mainForceIntent, "intent"), "UNKNOWN");
        return "UNKNOWN".equalsIgnoreCase(intent) || "NEUTRAL".equalsIgnoreCase(intent);
    }

    private Map<String, Object> inferLegacyMainForceIntent(StockWatchTechnicalAnalysis analysis) {
        String title = firstNonBlank(analysis.getTitle(), "");
        String conclusion = firstNonBlank(analysis.getConclusion(), "");
        String text = (title + "。" + conclusion).trim();
        if (!StringUtils.hasText(text)) {
            return Collections.emptyMap();
        }
        String intent = inferLegacyIntent(text);
        List<String> watchPoints = inferLegacyWatchPoints(title, conclusion);
        if (!StringUtils.hasText(intent) && watchPoints.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> inferred = new LinkedHashMap<>();
        inferred.put("schemaVersion", "legacy-text-v1");
        inferred.put("source", "legacy_text_fallback");
        inferred.put("intent", StringUtils.hasText(intent) ? intent : "UNKNOWN");
        inferred.put("timeWindow", "NEXT_SLOT_OR_SHORT_TERM");
        inferred.put("confidence", StringUtils.hasText(intent) ? 0.45 : 0.3);
        if (!watchPoints.isEmpty()) {
            inferred.put("watchPoints", watchPoints);
        }
        inferred.put("evidenceText", truncateText(text, 260));
        return inferred;
    }

    private String inferLegacyIntent(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (containsAnyText(text,
                "主力出逃", "主力流出", "资金流出", "净流出", "派发", "出货", "抛压", "获利回吐")) {
            return "DISTRIBUTE";
        }
        if (containsAnyText(text, "洗盘", "震仓")) {
            return "WASH";
        }
        if (containsAnyText(text, "主力净流入", "主力流入", "资金流入", "净流入", "吸筹", "承接")) {
            return "ACCUMULATE";
        }
        if (containsAnyText(text, "拉升", "涨停", "封单稳固", "封死")) {
            return "PULL_UP";
        }
        if (containsAnyText(text, "支撑", "守住")) {
            return "SUPPORT";
        }
        return "";
    }

    private List<String> inferLegacyWatchPoints(String title, String conclusion) {
        List<String> points = new ArrayList<>();
        String text = firstNonBlank(conclusion, title, "");
        for (String part : text.split("[。；;\\n]")) {
            String clause = part == null ? "" : part.trim();
            if (!StringUtils.hasText(clause)) {
                continue;
            }
            if (containsAnyText(clause,
                    "下一周期", "需验证", "验证", "若", "关注", "风险边界",
                    "支撑", "跌破", "突破", "站稳", "回踩", "开板", "放量",
                    "主力", "净流入", "净流出", "资金流入", "资金流出")) {
                points.add(truncateText(clause, 180));
            }
            if (points.size() >= 6) {
                break;
            }
        }
        if (points.isEmpty() && StringUtils.hasText(title)
                && containsAnyText(title, "主力", "支撑", "跌破", "突破", "开板", "放量", "流入", "流出")) {
            points.add(truncateText(title, 180));
        }
        return points;
    }

    @SuppressWarnings("unchecked")
    private void refreshPriorValidations(Map<String, Object> recentTechnical,
                                         Map<String, Object> quote,
                                         Map<String, Object> currentFacts,
                                         ZonedDateTime now) {
        if (recentTechnical == null || recentTechnical.isEmpty()) {
            return;
        }
        Object rawRecords = recentTechnical.get("records");
        if (!(rawRecords instanceof List)) {
            return;
        }
        for (Object rawRecord : (List<?>) rawRecords) {
            if (!(rawRecord instanceof Map)) {
                continue;
            }
            Map<String, Object> record = (Map<String, Object>) rawRecord;
            Map<String, Object> validation = validatePriorIntentRecord(record, quote, currentFacts, now);
            if (!validation.isEmpty()) {
                record.put("priorValidation", validation);
            }
        }
    }

    private Map<String, Object> validatePriorIntentRecord(Map<String, Object> record,
                                                          Map<String, Object> quote,
                                                          Map<String, Object> currentFacts,
                                                          ZonedDateTime now) {
        Object rawIntent = record == null ? null : record.get("mainForceIntent");
        if (!(rawIntent instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<?, ?> intentMap = (Map<?, ?>) rawIntent;
        String intent = firstNonBlank(mapText(intentMap, "intent"), "UNKNOWN").toUpperCase(Locale.ROOT);
        List<Map<String, Object>> checks = new ArrayList<>();

        Map<String, Object> intentCheck = validateIntentDirection(intent, quote, currentFacts);
        if (!intentCheck.isEmpty()) {
            checks.add(intentCheck);
        }
        for (Object watchPoint : watchPointObjects(intentMap.get("watchPoints"))) {
            Map<String, Object> check = validateWatchPoint(watchPoint, quote, currentFacts);
            if (!check.isEmpty()) {
                checks.add(check);
            }
        }
        if (checks.isEmpty()) {
            Map<String, Object> unresolved = new LinkedHashMap<>();
            unresolved.put("type", "prior_intent");
            unresolved.put("verdict", "UNRESOLVED");
            unresolved.put("reason", "历史主力意图缺少可程序验证的方向或观察点");
            checks.add(unresolved);
        }

        String verdict = aggregatePriorVerdict(checks);
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("verdict", verdict);
        validation.put("validatedAt", now.format(EVENT_TIME_FORMATTER));
        validation.put("sourceAnalysisId", record.get("id"));
        validation.put("basedOn", currentFacts == null || currentFacts.isEmpty()
                ? List.of("quote")
                : List.of("quote", "currentFacts"));
        validation.put("summary", priorValidationSummary(verdict, checks));
        validation.put("checks", checks);
        return validation;
    }

    private Map<String, Object> validateIntentDirection(String intent,
                                                        Map<String, Object> quote,
                                                        Map<String, Object> currentFacts) {
        if (!StringUtils.hasText(intent) || "UNKNOWN".equals(intent) || "NEUTRAL".equals(intent)) {
            return Collections.emptyMap();
        }
        Double mainInflow = currentNumber(quote, currentFacts,
                "quote.mainInflow",
                "currentFacts.anchorFacts.mainInflowYuan",
                "anchorFacts.mainInflowYuan");
        Double changePct = currentNumber(quote, currentFacts,
                "quote.changePercent",
                "currentFacts.anchorFacts.changePercent",
                "anchorFacts.changePercent",
                "currentFacts.anchorFacts.changePct",
                "anchorFacts.changePct");
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("type", "intent_direction");
        check.put("intent", intent);
        if (mainInflow != null) {
            check.put("mainInflow", mainInflow);
        }
        if (changePct != null) {
            check.put("changePercent", changePct);
        }

        boolean positiveIntent = Arrays.asList("ACCUMULATE", "PULL_UP", "SUPPORT").contains(intent);
        boolean negativeIntent = Arrays.asList("DISTRIBUTE", "SUPPRESS").contains(intent);
        if (positiveIntent) {
            if ((mainInflow != null && mainInflow >= 30_000_000.0) || (changePct != null && changePct >= 1.0)) {
                check.put("verdict", "SUPPORTED");
                check.put("reason", "本轮资金或价格方向支持此前偏多主力意图");
            } else if (mainInflow != null && mainInflow <= -100_000_000.0 && changePct != null && changePct <= -1.0) {
                check.put("verdict", "FALSIFIED");
                check.put("reason", "本轮大额主力净流出且价格下跌，强烈反向于此前偏多主力意图");
            } else if ((mainInflow != null && mainInflow < 0) || (changePct != null && changePct < 0)) {
                check.put("verdict", "WEAKENED");
                check.put("reason", "本轮资金或价格方向削弱此前偏多主力意图");
            } else {
                check.put("verdict", "UNRESOLVED");
                check.put("reason", "本轮事实不足以验证此前偏多主力意图");
            }
            return check;
        }
        if (negativeIntent) {
            if ((mainInflow != null && mainInflow <= -30_000_000.0) || (changePct != null && changePct <= -1.0)) {
                check.put("verdict", "SUPPORTED");
                check.put("reason", "本轮资金或价格方向支持此前偏空/派发主力意图");
            } else if (mainInflow != null && mainInflow >= 100_000_000.0 && changePct != null && changePct >= 1.0) {
                check.put("verdict", "FALSIFIED");
                check.put("reason", "本轮大额主力净流入且价格上涨，强烈反向于此前偏空/派发主力意图");
            } else if ((mainInflow != null && mainInflow > 0) || (changePct != null && changePct > 0)) {
                check.put("verdict", "WEAKENED");
                check.put("reason", "本轮资金或价格方向削弱此前偏空/派发主力意图");
            } else {
                check.put("verdict", "UNRESOLVED");
                check.put("reason", "本轮事实不足以验证此前偏空/派发主力意图");
            }
            return check;
        }
        if ("WASH".equals(intent)) {
            if (changePct != null && changePct < 0 && (mainInflow == null || mainInflow > -100_000_000.0)) {
                check.put("verdict", "SUPPORTED");
                check.put("reason", "本轮回落但未见强主力净流出，支持洗盘假设");
            } else if (mainInflow != null && mainInflow <= -100_000_000.0) {
                check.put("verdict", "WEAKENED");
                check.put("reason", "本轮主力净流出过强，削弱洗盘假设");
            } else {
                check.put("verdict", "UNRESOLVED");
                check.put("reason", "本轮事实不足以验证洗盘假设");
            }
            return check;
        }
        return Collections.emptyMap();
    }

    private List<Object> watchPointObjects(Object raw) {
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                values.add(array.get(i));
            }
            return values;
        }
        if (raw instanceof Collection) {
            return new ArrayList<>((Collection<?>) raw);
        }
        if (raw == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(raw);
    }

    private Map<String, Object> validateWatchPoint(Object raw,
                                                   Map<String, Object> quote,
                                                   Map<String, Object> currentFacts) {
        if (raw instanceof Map) {
            return validateStructuredWatchPoint((Map<?, ?>) raw, quote, currentFacts);
        }
        return validateTextWatchPoint(raw == null ? "" : String.valueOf(raw), quote, currentFacts);
    }

    private Map<String, Object> validateStructuredWatchPoint(Map<?, ?> watchPoint,
                                                             Map<String, Object> quote,
                                                             Map<String, Object> currentFacts) {
        String metricPath = firstNonBlank(
                mapText(watchPoint, "metricPath"),
                mapText(watchPoint, "path"),
                mapText(watchPoint, "field"));
        String operator = firstNonBlank(
                mapText(watchPoint, "operator"),
                mapText(watchPoint, "op"),
                mapText(watchPoint, "compare"));
        Double threshold = number(firstNonNull(
                watchPoint.get("threshold"),
                watchPoint.get("value"),
                watchPoint.get("target")), null);
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("type", "watch_point");
        check.put("watchPoint", truncateText(String.valueOf(watchPoint), 220));
        if (StringUtils.hasText(metricPath)) {
            check.put("metricPath", metricPath);
        }
        if (StringUtils.hasText(operator)) {
            check.put("operator", operator);
        }
        if (threshold != null) {
            check.put("threshold", threshold);
        }
        if (!StringUtils.hasText(metricPath) || !StringUtils.hasText(operator) || threshold == null) {
            check.put("verdict", "UNRESOLVED");
            check.put("reason", "观察点缺少metricPath/operator/threshold，无法程序验证");
            return check;
        }
        Double actual = resolveMetric(metricPath, quote, currentFacts);
        if (actual == null) {
            check.put("verdict", "UNRESOLVED");
            check.put("reason", "本轮事实缺少观察点所需字段：" + metricPath);
            return check;
        }
        check.put("actualValue", actual);
        boolean matched = compare(actual, operator, threshold);
        check.put("verdict", matched ? "SUPPORTED" : (bool(watchPoint.get("falsifyOnMiss"), false) ? "FALSIFIED" : "WEAKENED"));
        check.put("reason", matched ? "本轮事实命中结构化观察点" : "本轮事实未命中结构化观察点");
        return check;
    }

    private Map<String, Object> validateTextWatchPoint(String watchPoint,
                                                       Map<String, Object> quote,
                                                       Map<String, Object> currentFacts) {
        Map<String, Object> check = new LinkedHashMap<>();
        String text = firstNonBlank(watchPoint, "");
        if (!StringUtils.hasText(text)) {
            return Collections.emptyMap();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        check.put("type", "watch_point");
        check.put("watchPoint", truncateText(text, 220));

        Double mainInflow = currentNumber(quote, currentFacts,
                "quote.mainInflow",
                "currentFacts.anchorFacts.mainInflowYuan",
                "anchorFacts.mainInflowYuan");
        if (text.contains("主力") && (text.contains("流入") || text.contains("净流入"))) {
            return verdictFromSignedValue(check, mainInflow, true, "主力净流入观察点");
        }
        if (text.contains("主力") && (text.contains("流出") || text.contains("净流出"))) {
            return verdictFromSignedValue(check, mainInflow, false, "主力净流出观察点");
        }

        Double volumeRatio = currentNumber(quote, currentFacts,
                "quote.volumeRatio",
                "currentFacts.anchorFacts.volumeRatio",
                "anchorFacts.volumeRatio");
        if (text.contains("放量") || text.contains("量能") || normalized.contains("volume")) {
            check.put("metricPath", "quote.volumeRatio");
            if (volumeRatio == null) {
                check.put("verdict", "UNRESOLVED");
                check.put("reason", "本轮缺少量比，无法验证量能观察点");
            } else if (volumeRatio >= 1.2) {
                check.put("actualValue", volumeRatio);
                check.put("verdict", "SUPPORTED");
                check.put("reason", "本轮量比支持量能观察点");
            } else if (volumeRatio < 1.0) {
                check.put("actualValue", volumeRatio);
                check.put("verdict", "WEAKENED");
                check.put("reason", "本轮量比不足，削弱量能观察点");
            } else {
                check.put("actualValue", volumeRatio);
                check.put("verdict", "UNRESOLVED");
                check.put("reason", "本轮量比中性，量能观察点未决");
            }
            return check;
        }

        Double threshold = priceThreshold(text);
        Double price = currentNumber(quote, currentFacts,
                "quote.price",
                "currentFacts.anchorFacts.priceYuan",
                "anchorFacts.priceYuan",
                "currentFacts.technicalIndicators.close",
                "technicalIndicators.close");
        if (threshold != null) {
            check.put("metricPath", "quote.price");
            check.put("threshold", threshold);
            if (price == null) {
                check.put("verdict", "UNRESOLVED");
                check.put("reason", "本轮缺少价格，无法验证价格观察点");
                return check;
            }
            check.put("actualValue", price);
            if (containsAnyText(text, "突破", "站稳", "收复", "上攻", "挑战", "守住", "支撑")) {
                check.put("operator", ">=");
                check.put("verdict", price >= threshold ? "SUPPORTED" : "WEAKENED");
                check.put("reason", price >= threshold ? "本轮价格达到/守住观察价位" : "本轮价格未达到/未守住观察价位");
                return check;
            }
            if (containsAnyText(text, "跌破", "失守", "破位", "回落至", "回踩")) {
                check.put("operator", "<=");
                check.put("verdict", price <= threshold ? "SUPPORTED" : "WEAKENED");
                check.put("reason", price <= threshold ? "本轮价格触及/跌破观察价位" : "本轮价格尚未触及观察价位");
                return check;
            }
        }

        check.put("verdict", "UNRESOLVED");
        check.put("reason", "文本观察点未能映射到稳定事实字段");
        return check;
    }

    private Map<String, Object> verdictFromSignedValue(Map<String, Object> check,
                                                       Double value,
                                                       boolean expectPositive,
                                                       String label) {
        check.put("metricPath", "quote.mainInflow");
        if (value == null) {
            check.put("verdict", "UNRESOLVED");
            check.put("reason", "本轮缺少主力净流入，无法验证" + label);
            return check;
        }
        check.put("actualValue", value);
        boolean supported = expectPositive ? value > 0 : value < 0;
        check.put("verdict", supported ? "SUPPORTED" : "WEAKENED");
        check.put("reason", supported ? "本轮资金方向支持" + label : "本轮资金方向削弱" + label);
        return check;
    }

    private String aggregatePriorVerdict(List<Map<String, Object>> checks) {
        int supported = 0;
        int weakened = 0;
        int falsified = 0;
        int unresolved = 0;
        for (Map<String, Object> check : checks) {
            String verdict = firstNonBlank(mapText(check, "verdict"), "UNRESOLVED");
            switch (verdict) {
                case "SUPPORTED":
                    supported++;
                    break;
                case "WEAKENED":
                    weakened++;
                    break;
                case "FALSIFIED":
                    falsified++;
                    break;
                default:
                    unresolved++;
                    break;
            }
        }
        if (falsified > 0) {
            return "FALSIFIED";
        }
        if (supported > 0 && weakened == 0) {
            return "SUPPORTED";
        }
        if (weakened > 0 && supported == 0) {
            return "WEAKENED";
        }
        if (supported > 0 && weakened > 0) {
            return "MIXED";
        }
        return unresolved > 0 ? "UNRESOLVED" : "UNRESOLVED";
    }

    private String priorValidationSummary(String verdict, List<Map<String, Object>> checks) {
        long supported = checks.stream().filter(item -> "SUPPORTED".equals(item.get("verdict"))).count();
        long weakened = checks.stream().filter(item -> "WEAKENED".equals(item.get("verdict"))).count();
        long falsified = checks.stream().filter(item -> "FALSIFIED".equals(item.get("verdict"))).count();
        long unresolved = checks.stream().filter(item -> "UNRESOLVED".equals(item.get("verdict"))).count();
        return "verdict=" + verdict
                + ", supported=" + supported
                + ", weakened=" + weakened
                + ", falsified=" + falsified
                + ", unresolved=" + unresolved;
    }

    private Double currentNumber(Map<String, Object> quote, Map<String, Object> currentFacts, String... paths) {
        for (String path : paths) {
            Double value = resolveMetric(path, quote, currentFacts);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Double resolveMetric(String metricPath, Map<String, Object> quote, Map<String, Object> currentFacts) {
        if (!StringUtils.hasText(metricPath)) {
            return null;
        }
        String path = metricPath.trim();
        if (path.startsWith("aiContext.")) {
            path = path.substring("aiContext.".length());
        }
        Object value = null;
        if (path.startsWith("quote.")) {
            value = nestedValue(quote, path.substring("quote.".length()));
        } else if (path.startsWith("currentFacts.")) {
            value = nestedValue(currentFacts, path.substring("currentFacts.".length()));
        } else {
            value = nestedValue(quote, path);
            if (value == null) {
                value = nestedValue(currentFacts, path);
            }
        }
        return number(value, null);
    }

    @SuppressWarnings("unchecked")
    private Object nestedValue(Map<String, Object> source, String path) {
        if (source == null || !StringUtils.hasText(path)) {
            return null;
        }
        Object cursor = source;
        for (String part : path.split("\\.")) {
            if (!(cursor instanceof Map)) {
                return null;
            }
            cursor = ((Map<String, Object>) cursor).get(part);
            if (cursor == null) {
                return null;
            }
        }
        return cursor;
    }

    private boolean compare(double actual, String operator, double threshold) {
        String op = firstNonBlank(operator, "").trim();
        switch (op) {
            case ">":
                return actual > threshold;
            case ">=":
            case "gte":
            case "GE":
                return actual >= threshold;
            case "<":
                return actual < threshold;
            case "<=":
            case "lte":
            case "LE":
                return actual <= threshold;
            case "==":
            case "=":
                return Math.abs(actual - threshold) < 0.000001;
            case "!=":
                return Math.abs(actual - threshold) >= 0.000001;
            default:
                return false;
        }
    }

    private Double priceThreshold(String text) {
        java.util.regex.Matcher matcher = PRICE_LEVEL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return number(matcher.group(1), null);
    }

    private boolean containsAnyText(String value, String... tokens) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String mapText(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int effectiveTechnicalLookbackDays() {
        return Math.max(1, Math.min(30, aiContextTechnicalLookbackDays));
    }

    private int effectiveTechnicalLimit() {
        return Math.max(1, Math.min(96, aiContextTechnicalLimit));
    }

    private int effectiveTechnicalMaxChars() {
        return Math.max(2_000, Math.min(30_000, aiContextTechnicalMaxChars));
    }

    private void trimTechnicalContextToMaxChars(Map<String, Object> context) {
        Object rawRecords = context.get("records");
        if (!(rawRecords instanceof List)) {
            return;
        }
        List<?> records = (List<?>) rawRecords;
        int maxChars = effectiveTechnicalMaxChars();
        while (records.size() > 1 && JSON.toJSONString(context).length() > maxChars) {
            records.remove(0);
        }
        context.put("recordCount", records.size());
        if (JSON.toJSONString(context).length() > maxChars) {
            context.put("truncatedByMaxChars", true);
        }
    }

    private String analysisDateString(Date date) {
        if (date == null) {
            return "";
        }
        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate().toString();
        }
        return date.toInstant().atZone(CHINA_ZONE).toLocalDate().toString();
    }

    private String dateTimeString(Date date) {
        if (date == null) {
            return "";
        }
        try {
            return date.toInstant().atZone(CHINA_ZONE).format(EVENT_TIME_FORMATTER);
        } catch (Exception ignored) {
            return String.valueOf(date);
        }
    }

    private void putTextIfPresent(Map<String, Object> target, String key, String value, int maxChars) {
        if (StringUtils.hasText(value)) {
            target.put(key, truncateText(value.trim(), maxChars));
        }
    }

    private List<String> compactJsonStringArray(String raw, int maxItems, int maxChars) {
        if (!StringUtils.hasText(raw) || maxItems <= 0) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        try {
            Object parsed = JSON.parse(raw);
            if (parsed instanceof JSONArray) {
                JSONArray array = (JSONArray) parsed;
                for (int i = 0; i < array.size() && values.size() < maxItems; i++) {
                    addCompactString(values, array.get(i), maxChars);
                }
            } else if (parsed instanceof Collection) {
                for (Object item : (Collection<?>) parsed) {
                    if (values.size() >= maxItems) break;
                    addCompactString(values, item, maxChars);
                }
            } else {
                addCompactString(values, parsed, maxChars);
            }
        } catch (Exception ignored) {
            addCompactString(values, raw, maxChars);
        }
        return values;
    }

    private void addCompactString(List<String> values, Object raw, int maxChars) {
        if (raw == null || values.size() >= 100) {
            return;
        }
        String value = String.valueOf(raw).trim();
        if (StringUtils.hasText(value)) {
            values.add(truncateText(value, maxChars));
        }
    }

    private JSONObject parseJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return JSON.parseObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String truncateText(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private String aiContextStatus(Map<String, Object> aiContext) {
        if (aiContext == null || aiContext.isEmpty()) {
            return "not_required";
        }
        return firstNonBlank(String.valueOf(aiContext.get("status")), "available");
    }

    private void appendAiContextHistoryMetadata(Map<String, Object> event, Map<String, Object> aiContext) {
        if (event == null || aiContext == null) {
            return;
        }
        Object raw = aiContext.get("recentTechnicalAnalysis");
        if (!(raw instanceof Map)) {
            return;
        }
        Map<?, ?> history = (Map<?, ?>) raw;
        event.put("ai_context_history_record_count", history.get("recordCount"));
        event.put("ai_context_history_window_start", history.get("windowStart"));
        event.put("ai_context_history_window_end", history.get("windowEnd"));
        event.put("ai_context_history_usage", history.get("usage"));
        event.put("ai_context_history_selection_policy", history.get("selectionPolicy"));
        Map<String, Integer> priorVerdicts = priorValidationVerdictCounts(history.get("records"));
        if (!priorVerdicts.isEmpty()) {
            event.put("ai_context_history_prior_verdicts", priorVerdicts);
        }
    }

    private Map<String, Integer> priorValidationVerdictCounts(Object rawRecords) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (!(rawRecords instanceof List)) {
            return counts;
        }
        for (Object rawRecord : (List<?>) rawRecords) {
            if (!(rawRecord instanceof Map)) {
                continue;
            }
            Object rawValidation = ((Map<?, ?>) rawRecord).get("priorValidation");
            if (!(rawValidation instanceof Map)) {
                continue;
            }
            Object rawVerdict = ((Map<?, ?>) rawValidation).get("verdict");
            String verdict = rawVerdict == null ? "" : String.valueOf(rawVerdict).trim();
            if (StringUtils.hasText(verdict)) {
                counts.put(verdict, counts.getOrDefault(verdict, 0) + 1);
            }
        }
        return counts;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private void putTailIfPresent(Map<String, Object> source, Map<String, Object> target, String key, int limit) {
        List<Object> values = sliceList(source == null ? null : source.get(key), limit, false);
        if (!values.isEmpty()) {
            target.put(key, values);
        }
    }

    private void putHeadIfPresent(Map<String, Object> source, Map<String, Object> target, String key, int limit) {
        List<Object> values = sliceList(source == null ? null : source.get(key), limit, true);
        if (!values.isEmpty()) {
            target.put(key, values);
        }
    }

    private List<Object> sliceList(Object raw, int limit, boolean head) {
        if (!(raw instanceof List) || limit <= 0) {
            return Collections.emptyList();
        }
        List<?> values = (List<?>) raw;
        int size = values.size();
        int from = head ? 0 : Math.max(0, size - limit);
        int to = head ? Math.min(size, limit) : size;
        return new ArrayList<>(values.subList(from, to));
    }

    private void queueUnifiedAnalysisDemand(AiWatchRule rule,
                                            Map<String, Object> event,
                                            ZonedDateTime now) {
        queueUnifiedAnalysisDemand(rule, event, eventQuote(event), null, now);
    }

    private void queueUnifiedAnalysisDemand(AiWatchRule rule,
                                            Map<String, Object> event,
                                            Map<String, Object> quote,
                                            Map<String, Object> factReceipt,
                                            ZonedDateTime now) {
        if (rule == null || event == null || !bool(event.get("triggered"), false)) {
            return;
        }
        try {
            AnalysisDemandGate gate = evaluateAnalysisDemandGate(rule);
            if (!gate.allowed) {
                event.put("analysis_demand_status", gate.status);
                event.put("analysis_demand_reason", gate.reason);
                return;
            }
            ensureAnalysisFactEvent(rule, event, quote, factReceipt, now);
            if (!validFactEvent(event)) {
                event.put("analysis_demand_status", "SKIPPED_NO_FACT");
                event.put("analysis_demand_reason", firstNonBlank(
                        text(event.get("analysis_fact_observation_reason"), ""),
                        "target_fact_missing"));
                return;
            }
            List<String> reasonCodes = new ArrayList<>();
            reasonCodes.add("AI_RULE_TRIGGERED");
            Object matchedSignals = event.get("matched_signals");
            if (matchedSignals instanceof Collection) {
                for (Object signal : (Collection<?>) matchedSignals) {
                    String value = text(signal, "");
                    if (StringUtils.hasText(value)) reasonCodes.add(value);
                }
            }
            Map<String, Object> triggerContext = new LinkedHashMap<>();
            triggerContext.put("eventId", event.get("event_id"));
            triggerContext.put("ruleId", rule.getId());
            triggerContext.put("signalFingerprint", event.get("ai_signal_shape_fingerprint"));
            triggerContext.put("riskTier", event.get("ai_risk_tier"));
            triggerContext.put("triggerEvidencePolicy", event.get("trigger_evidence_policy"));
            triggerContext.put("triggerEvidenceGateReason", event.get("trigger_evidence_gate_reason"));
            triggerContext.put("targetFactSourceTimeQuality", event.get("target_source_time_quality"));
            DemandCreationResult demandResult = analysisDemandService.createAnomalyDetailed(
                    gate.item,
                    longNumber(event.get("target_fact_observation_id")),
                    text(event.get("target_fact_observation_key"), ""),
                    longNumber(event.get("target_source_time_epoch_ms")),
                    reasonCodes,
                    triggerContext,
                    now
            );
            StockWatchAnalysisDemand demand = demandResult == null ? null : demandResult.getDemand();
            if (demand == null) {
                event.put("analysis_demand_status", "SKIPPED_BY_POLICY");
                event.put("analysis_demand_reason", "analysis_demand_not_created");
                return;
            }
            event.put("analysis_demand_status", demandResult.isCreated() ? "QUEUED" : "REUSED");
            event.put("analysis_demand_id", demand.getId());
            event.put("analysis_demand_key", demand.getDemandKey());
            event.put("analysis_demand_trigger_type", demand.getTriggerType());
        } catch (Exception e) {
            event.put("analysis_demand_status", "ERROR");
            event.put("analysis_demand_error", shortError(e));
            log.warn("stock-watch unified analysis demand queue failed symbol={}.{} ruleId={} error={}",
                    rule.getSymbol(), rule.getMarket(), rule.getId(), shortError(e));
        }
    }

    private AnalysisDemandGate evaluateAnalysisDemandGate(AiWatchRule rule) {
        if (rule == null) {
            return AnalysisDemandGate.blocked("SKIPPED_BY_POLICY", "rule_missing");
        }
        if (rule.isGeneralLegacy()) {
            return AnalysisDemandGate.blocked("SKIPPED_GENERAL_LEGACY", "general_legacy_rule");
        }
        if (analysisPolicyService == null || analysisDemandService == null) {
            return AnalysisDemandGate.blocked("SKIPPED_SERVICE_UNAVAILABLE", "analysis_services_unavailable");
        }
        StockWatchItem item = analysisPolicyService.currentItem(rule.getSymbol(), rule.getMarket());
        if (item == null) {
            return AnalysisDemandGate.blocked("SKIPPED_BY_POLICY", "analysis_policy_stock_missing");
        }
        if (!analysisPolicyService.allowsAutomaticDemand(item, "ANOMALY_IMMEDIATE")) {
            return AnalysisDemandGate.blocked("SKIPPED_BY_POLICY", "analysis_policy_disallowed:ANOMALY_IMMEDIATE");
        }
        return AnalysisDemandGate.allowed(item);
    }

    private void ensureAnalysisFactEvent(AiWatchRule rule,
                                         Map<String, Object> event,
                                         Map<String, Object> quote,
                                         Map<String, Object> factReceipt,
                                         ZonedDateTime now) {
        if (validFactEvent(event)) return;
        if (validFactReceipt(factReceipt)) {
            applyFactReceipt(event, factReceipt);
            return;
        }
        if (rule == null || !rule.isQuoteAnomalyV1()) {
            return;
        }
        StockWatchFactPersistenceService.ObservationReceipt receipt = factPersistenceService.observeQuoteAlert(
                rule.getSymbol(),
                rule.getMarket(),
                text(quote, "name", rule.getName()),
                quote,
                now
        );
        Map<String, Object> receiptMap = receipt == null
                ? StockWatchFactPersistenceService.ObservationReceipt.skipped("quote_alert_fact_receipt_missing").toMap()
                : receipt.toMap();
        receiptMap.put("symbol", rule.getSymbol());
        receiptMap.put("market", rule.getMarket());
        event.put("analysis_fact_observation", receiptMap);
        event.put("analysis_fact_observation_status", receiptMap.get("status"));
        event.put("analysis_fact_observation_reason", receiptMap.get("reason"));
        if (validFactReceipt(receiptMap)) {
            applyFactReceipt(event, receiptMap);
        }
    }

    private void applyFactReceipt(Map<String, Object> event, Map<String, Object> factReceipt) {
        event.put("target_fact_observation_id", longNumber(factReceipt.get("observationId")));
        event.put("target_fact_observation_key", text(factReceipt.get("observationKey"), ""));
        event.put("target_source_time_epoch_ms", longNumber(factReceipt.get("sourceTimeEpochMs")));
        event.put("target_source_time_quality", factReceipt.get("sourceTimeQuality"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventQuote(Map<String, Object> event) {
        Object quote = event == null ? null : event.get("quote");
        return quote instanceof Map ? new LinkedHashMap<>((Map<String, Object>) quote) : Collections.emptyMap();
    }

    private String shortError(Exception e) {
        if (e == null) return "unknown_error";
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
    }

    private AlertSendResult sendTriggeredEmail(SupportBreakRule rule,
                                               String name,
                                               String reason,
                                               Map<String, Object> event,
                                               ZonedDateTime now) {
        String subject = "Stock Watch 预警 · " + name + " · " + rule.ruleId;
        StringBuilder content = new StringBuilder();
        appendTitle(content, "Stock Watch 盯盘预警");
        appendSection(content, "一、触发摘要");
        appendLine(content, "标的", name + "（" + rule.symbol + "." + rule.market + "）");
        appendLine(content, "信号", rule.ruleId);
        appendLine(content, "级别", rule.severity);
        appendLine(content, "时间", text(event, "timestamp", "--"));
        appendSection(content, "二、关键行情");
        appendLine(content, "最新价", format(number(event.get("price"), 0.0)));
        appendLine(content, "支撑位", format(number(event.get("support"), 0.0)));
        appendLine(content, "跌破天数", integer(event.get("break_days"), 0) + "/" + integer(event.get("required_break_days"), 0));
        appendSection(content, "三、触发原因");
        appendParagraph(content, reason, "  - ");
        return sendAlertWithFrequencyGate(rule.symbol, rule.market, name, subject, content.toString(), now);
    }

    private AlertSendResult sendAiTriggeredEmail(AiWatchRule rule,
                                                AiDecision decision,
                                                Map<String, Object> event,
                                                ZonedDateTime now) {
        String subject = "Stock Watch AI预警 · " + rule.getName();
        Map<?, ?> quote = event.get("quote") instanceof Map ? (Map<?, ?>) event.get("quote") : Collections.emptyMap();
        StringBuilder content = new StringBuilder();
        boolean deterministic = decision.getModel() != null && decision.getModel().startsWith("deterministic");
        appendTitle(content, deterministic ? "Stock Watch 确定性盯盘提醒" : "Stock Watch AI 盯盘提醒");
        appendSection(content, "一、触发摘要");
        appendLine(content, "标的", rule.getName() + "（" + rule.getSymbol() + "." + rule.getMarket() + "）");
        appendLine(content, "结果", deterministic ? "本地确定性规则已触发" : "DeepSeek 判断观察条件已触发");
        appendLine(content, "级别", firstNonBlank(decision.getSeverity(), rule.getSeverity(), "--"));
        appendLine(content, "置信度", format(decision.getConfidence()) + " / 阈值 " + format(rule.getMinConfidence()));
        appendLine(content, "时间", text(event, "timestamp", "--"));

        appendSection(content, "二、关键行情");
        appendQuoteLine(content, quote, "最新价", "price");
        appendQuoteLine(content, quote, "涨跌幅", "changePercent", "%");
        appendQuoteLine(content, quote, "量比", "volumeRatio");
        appendQuoteLine(content, quote, "换手率", "turnover", "%");
        appendQuoteLine(content, quote, "主力净流入", "mainInflow");

        Long targetSourceTime = longNumber(event.get("target_source_time_epoch_ms"));
        StockWatchTechnicalAnalysis inlineAnalysis = targetSourceTime == null ? null
                : technicalContextService.exactTrustedForSource(
                rule.getSymbol(), rule.getMarket(), targetSourceTime);
        StockWatchTechnicalAnalysis recentContextAnalysis = targetSourceTime == null || inlineAnalysis != null ? null
                : technicalContextService.latestTrustedHistorical(
                rule.getSymbol(), rule.getMarket(), targetSourceTime);
        Object riskSummary = event.get("riskPatternSummary");
        if (riskSummary instanceof Map) {
            String renderedRisk = riskPatternSummaryRenderer.renderMailBlock((Map<String, Object>) riskSummary, "  ");
            if (StringUtils.hasText(renderedRisk)) {
                appendSection(content, "三、价量风险结构识别（套路识别）");
                content.append(renderedRisk);
            }
        }

        appendSection(content, "四、技术分析");
        if (inlineAnalysis != null) {
            content.append(technicalEmailRenderer.render(inlineAnalysis, "  同一行情事实可信技术分析"));
        } else if (validFactEvent(event)) {
            appendParagraph(content,
                    "同一行情事实的技术分析正在生成。初始提醒先发送，分析完成后将另发一封“补充技术分析”邮件。",
                    "  ");
            if (recentContextAnalysis != null) {
                content.append(technicalEmailRenderer.render(recentContextAnalysis, "  最近一次可信技术分析（仅作上下文）"));
            }
        } else {
            appendParagraph(content,
                    "本轮缺少可追溯的标准行情事实，未提交模型分析，也不会把模型异常文本当作技术分析。",
                    "  ");
        }
        appendLine(content, "触发判断来源", firstNonBlank(decision.getModel(), "--"));

        appendSection(content, "五、触发依据");
        appendParagraph(content, firstNonBlank(decision.getReason(), "未返回触发依据。"), "  ");
        if (decision.getMissingData() != null && !decision.getMissingData().isEmpty()) {
            content.append("\n  数据缺失：\n");
            for (String missing : decision.getMissingData()) {
                appendParagraph(content, missing, "    - ");
            }
        }

        appendSection(content, "六、观察条件");
        appendParagraph(content, compactCondition(rule.getConditionText()), "  ");
        return sendAiAlertWithFrequencyGate(rule, subject, content.toString(), event, inlineAnalysis, now);
    }

    private AlertSendResult sendAiAlertWithFrequencyGate(AiWatchRule rule,
                                                         String subject,
                                                         String content,
                                                         Map<String, Object> event,
                                                         StockWatchTechnicalAnalysis inlineAnalysis,
                                                         ZonedDateTime now) {
        StockWatchDeliveryConfig config = deliveryConfigMapper.selectBySymbolMarket(rule.getSymbol(), rule.getMarket());
        if (config == null) {
            return AlertSendResult.skipped("deliveryConfigMissing: 未配置单股邮件推送，不允许回退到全局邮箱");
        }
        String stateKey = aiAlertStateKey(rule);
        boolean configEnabled = Boolean.TRUE.equals(config.getEnabled());
        if (!configEnabled) {
            return AlertSendResult.skipped("deliveryConfigDisabled: 单股邮件推送未启用");
        }
        List<String> recipients = parseRecipients(config.getRecipientsJson());
        if (recipients.isEmpty()) {
            return AlertSendResult.skipped("deliveryRecipientsMissing: 单股邮件配置没有有效收件人");
        }
        CooldownGate gate = aiDeliveryCooldownGate(config, stateKey, now);
        if (!gate.due) {
            return AlertSendResult.skipped("deliveryFrequencyBlocked: " + gate.reason);
        }

        StockWatchAlertDeliveryService.AlertRequest request =
                new StockWatchAlertDeliveryService.AlertRequest();
        request.setConfigId(config.getId());
        request.setRuleId(rule.getId());
        request.setSymbol(rule.getSymbol());
        request.setMarket(rule.getMarket());
        request.setStockName(rule.getName());
        request.setRecipients(recipients);
        request.setTargetFactObservationId(longNumber(event.get("target_fact_observation_id")));
        request.setTargetFactObservationKey(text(event.get("target_fact_observation_key"), null));
        request.setTargetSourceTimeEpochMs(longNumber(event.get("target_source_time_epoch_ms")));
        request.setTradeDate(java.sql.Date.valueOf(now.toLocalDate()));
        request.setSignalFingerprint(text(event.get("ai_signal_shape_fingerprint"), ""));
        request.setInlineAnalysisId(inlineAnalysis == null ? null : inlineAnalysis.getId());
        request.setAnalysisDemandId(longNumber(event.get("analysis_demand_id")));
        request.setDeadlineAt(Date.from(now.plusHours(6).toInstant()));
        request.setInitialSubject(subject);
        request.setInitialBody(content);
        StockWatchAlertDeliveryService.DeliveryResult sendResult =
                alertDeliveryService.createAndSend(request);
        if (sendResult.isSuccess()) {
            markStockAlertSent(stockKey(config.getSymbol(), config.getMarket()), now);
            return AlertSendResult.sent(sendResult.getMessageId(), "使用单股邮件配置发送");
        }
        String error = firstNonBlank(sendResult.getError(), "SMTP 发送失败");
        return AlertSendResult.failed(error);
    }

    private CooldownGate aiDeliveryCooldownGate(StockWatchDeliveryConfig config, String stateKey, ZonedDateTime now) {
        String frequency = firstNonBlank(config.getFrequencyType(), "MANUAL").toUpperCase(Locale.ROOT);
        AiAlertState state = lastAiAlerts.get(stateKey);
        ZonedDateTime lastSentAt = state == null ? null : parseEventTime(state.lastSentAt);
        if ("MANUAL".equals(frequency)) {
            return CooldownGate.blocked("邮件频率为手动发送");
        }
        if ("IMMEDIATE".equals(frequency)) {
            return CooldownGate.allowed();
        }
        if ("INTERVAL".equals(frequency)) {
            if (lastSentAt == null) {
                return CooldownGate.allowed();
            }
            int interval = Math.max(effectiveIntervalMinutes(config.getIntervalMinutes()), effectiveAiAlertCooldownMinutes());
            long elapsed = Duration.between(lastSentAt, now).toMinutes();
            return elapsed >= interval
                    ? CooldownGate.allowed()
                    : CooldownGate.blocked("AI发送频率未到：已过 " + Math.max(0, elapsed) + " 分钟 / 间隔 " + interval + " 分钟");
        }
        if ("DAILY_CLOSE".equals(frequency)) {
            LocalTime closeDispatchTime = LocalTime.of(15, 10);
            if (now.toLocalTime().isBefore(closeDispatchTime)) {
                return CooldownGate.blocked("发送频率为收盘后发送，当前未到 15:10");
            }
            boolean notSentToday = lastSentAt == null || lastSentAt.toLocalDate().isBefore(now.toLocalDate());
            return notSentToday
                    ? CooldownGate.allowed()
                    : CooldownGate.blocked("今日已发送过该股票AI邮件");
        }
        return CooldownGate.blocked("未知邮件频率：" + frequency);
    }

    private AlertSendResult sendAlertWithFrequencyGate(String symbol,
                                                       String market,
                                                       String stockName,
                                                       String subject,
                                                       String content,
                                                       ZonedDateTime now) {
        StockWatchDeliveryConfig config = deliveryConfigMapper.selectBySymbolMarket(symbol, market);
        String stockKey = stockKey(symbol, market);
        if (config == null) {
            return AlertSendResult.skipped("未配置单股邮件推送，不允许回退到全局邮箱");
        }
        return sendConfiguredAlert(config, subject, content, now);
    }

    private AlertSendResult sendConfiguredAlert(StockWatchDeliveryConfig config,
                                                String subject,
                                                String content,
                                                ZonedDateTime now) {
        String stockKey = stockKey(config.getSymbol(), config.getMarket());
        boolean configEnabled = Boolean.TRUE.equals(config.getEnabled());
        if (!configEnabled) {
            return AlertSendResult.skipped("单股邮件推送未启用");
        }
        List<String> recipients = parseRecipients(config.getRecipientsJson());
        if (recipients.isEmpty()) {
            return AlertSendResult.skipped("单股邮件配置没有有效收件人");
        }
        CooldownGate gate = configuredCooldownGate(config, stockKey, now);
        if (!gate.due) {
            return AlertSendResult.skipped(gate.reason);
        }

        NotificationService.EmailSendResult sendResult = notificationService.sendStockWatchAlertToRecipients(
                subject,
                content,
                recipients,
                null
        );
        if (sendResult.isSuccess()) {
            deliveryConfigMapper.updateDispatchStatus(config.getId(), "SENT", "Stock Watch 预警已发送：" + subject, new Date());
            markStockAlertSent(stockKey, now);
            return AlertSendResult.sent(sendResult.getMessageId(), "使用单股邮件配置发送");
        }
        String error = firstNonBlank(sendResult.getErrorMessage(), "SMTP 发送失败");
        deliveryConfigMapper.updateDispatchStatus(config.getId(), "FAILED", "Stock Watch 预警发送失败：" + error, null);
        return AlertSendResult.failed(error);
    }

    private CooldownGate configuredCooldownGate(StockWatchDeliveryConfig config, String stockKey, ZonedDateTime now) {
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return globalCooldownGate(stockKey, now);
        }
        String frequency = firstNonBlank(config.getFrequencyType(), "MANUAL").toUpperCase(Locale.ROOT);
        if ("MANUAL".equals(frequency)) {
            return CooldownGate.blocked("邮件频率为手动发送");
        }
        if ("IMMEDIATE".equals(frequency)) {
            return globalCooldownGate(stockKey, now);
        }
        if ("INTERVAL".equals(frequency)) {
            Date last = config.getLastDispatchAt();
            int interval = effectiveIntervalMinutes(config.getIntervalMinutes());
            if (last == null) {
                return CooldownGate.allowed();
            }
            ZonedDateTime lastAt = ZonedDateTime.ofInstant(last.toInstant(), CHINA_ZONE);
            long elapsed = Duration.between(lastAt, now).toMinutes();
            return elapsed >= interval
                    ? CooldownGate.allowed()
                    : CooldownGate.blocked("发送频率未到：已过 " + Math.max(0, elapsed) + " 分钟 / 间隔 " + interval + " 分钟");
        }
        if ("DAILY_CLOSE".equals(frequency)) {
            LocalTime closeDispatchTime = LocalTime.of(15, 10);
            if (now.toLocalTime().isBefore(closeDispatchTime)) {
                return CooldownGate.blocked("发送频率为收盘后发送，当前未到 15:10");
            }
            Date last = config.getLastDispatchAt();
            boolean notSentToday = last == null || ZonedDateTime.ofInstant(last.toInstant(), CHINA_ZONE)
                    .toLocalDate()
                    .isBefore(now.toLocalDate());
            return notSentToday
                    ? CooldownGate.allowed()
                    : CooldownGate.blocked("今日已发送过该股票邮件");
        }
        return CooldownGate.blocked("未知邮件频率：" + frequency);
    }

    private CooldownGate globalCooldownGate(String stockKey, ZonedDateTime now) {
        int interval = effectiveDefaultAlertIntervalMinutes();
        ZonedDateTime lastAt = parseEventTime(lastAlertAtByStock.get(stockKey));
        if (lastAt == null) {
            return CooldownGate.allowed();
        }
        long elapsed = Duration.between(lastAt, now).toMinutes();
        return elapsed >= interval
                ? CooldownGate.allowed()
                : CooldownGate.blocked("全局预警冷却中：已过 " + Math.max(0, elapsed) + " 分钟 / 间隔 " + interval + " 分钟");
    }

    private void markStockAlertSent(String stockKey, ZonedDateTime now) {
        lastAlertAtByStock.put(stockKey, now.format(EVENT_TIME_FORMATTER));
        trimStockAlertState();
    }

    private void appendTitle(StringBuilder builder, String title) {
        builder.append(title).append("\n")
                .append(repeat("=", Math.max(8, title.length()))).append("\n");
    }

    private void appendSection(StringBuilder builder, String title) {
        builder.append("\n").append(title).append("\n");
    }

    private void appendLine(StringBuilder builder, String label, Object value) {
        builder.append("  ").append(label).append("：")
                .append(value == null ? "--" : String.valueOf(value))
                .append("\n");
    }

    private void appendQuoteLine(StringBuilder builder, Map<?, ?> quote, String label, String key) {
        appendQuoteLine(builder, quote, label, key, "");
    }

    private void appendQuoteLine(StringBuilder builder, Map<?, ?> quote, String label, String key, String suffix) {
        Object value = quote == null ? null : quote.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return;
        }
        String rendered = value instanceof Number ? format(((Number) value).doubleValue()) : String.valueOf(value).trim();
        appendLine(builder, label, rendered + firstNonBlank(suffix, ""));
    }

    private void appendParagraph(StringBuilder builder, String text, String prefix) {
        String value = firstNonBlank(text, "--")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        String safePrefix = prefix == null ? "" : prefix;
        for (String line : value.split("\\n+")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                builder.append(safePrefix).append(trimmed).append("\n");
            }
        }
    }

    private String compactCondition(String conditionText) {
        String value = firstNonBlank(conditionText, "未配置观察条件")
                .replaceAll("\\s+", " ")
                .trim();
        int limit = 180;
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...（完整条件请在系统配置中查看）";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private List<SupportBreakRule> resolveRules(Object override) {
        if (override instanceof JSONArray) {
            return parseRuleArray((JSONArray) override);
        }
        if (override instanceof List) {
            return parseRuleList((List<?>) override);
        }
        List<SupportBreakRule> configured = parseConfiguredRules(configuredRules);
        return configured.isEmpty() ? Collections.emptyList() : configured;
    }

    private List<AiWatchRule> resolveAiRules(Object override) {
        List<AiWatchRule> parsed;
        if (override instanceof JSONArray) {
            parsed = aiRuleService.parseTransientRules((JSONArray) override);
            return parsed == null ? Collections.emptyList() : parsed;
        }
        if (override instanceof List) {
            parsed = aiRuleService.parseTransientRules(override);
            return parsed == null ? Collections.emptyList() : parsed;
        }
        parsed = aiRuleService.listEnabled();
        return parsed == null ? Collections.emptyList() : parsed;
    }

    private void pruneInactiveRuleState(List<SupportBreakRule> rules, List<AiWatchRule> aiRules) {
        Set<String> activeSupportRules = rules.stream()
                .map(SupportBreakRule::stateKey)
                .collect(Collectors.toSet());
        Set<String> activeAiRules = aiRules.stream()
                .map(rule -> normalizeSymbol(rule.getSymbol()) + ":" + rule.getId())
                .collect(Collectors.toSet());
        Set<String> activeAiAlertKeys = aiRules.stream()
                .map(this::aiAlertStateKey)
                .collect(Collectors.toSet());
        Set<String> activeStocks = new LinkedHashSet<>();
        rules.forEach(rule -> activeStocks.add(stockKey(rule.symbol, rule.market)));
        aiRules.forEach(rule -> activeStocks.add(stockKey(rule.getSymbol(), rule.getMarket())));

        ruleStates.keySet().removeIf(key -> !activeSupportRules.contains(key));
        sentSignalKeys.removeIf(key -> !isActiveSentSignal(key, activeSupportRules, activeAiRules));
        lastAlertAtByStock.keySet().removeIf(key -> !activeStocks.contains(key));
        lastAiAlerts.keySet().removeIf(key -> !activeAiAlertKeys.contains(key));
    }

    private boolean isActiveSentSignal(String key,
                                       Set<String> activeSupportRules,
                                       Set<String> activeAiRules) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        String[] parts = key.split(":");
        if (parts.length >= 4 && "AI".equals(parts[1])) {
            return activeAiRules.contains(parts[2] + ":" + parts[3]);
        }
        return parts.length >= 4 && activeSupportRules.contains(parts[1] + ":" + parts[2]);
    }

    private List<Map<String, Object>> resolveQuotes(Object override, List<SupportBreakRule> rules, List<AiWatchRule> aiRules) {
        if (override instanceof JSONArray) {
            return parseQuoteArray((JSONArray) override);
        }
        if (override instanceof List) {
            return parseQuoteList((List<?>) override);
        }
        String symbols = configuredSymbols;
        List<String> extraSymbols = new ArrayList<>();
        extraSymbols.addAll(rules.stream().map(rule -> rule.symbol + "." + rule.market).collect(Collectors.toList()));
        extraSymbols.addAll(aiRules.stream().map(rule -> rule.getSymbol() + "." + rule.getMarket()).collect(Collectors.toList()));
        if (StringUtils.hasText(symbols)) {
            extraSymbols.addAll(Arrays.asList(symbols.split("[,;\\s]+")));
        }
        symbols = extraSymbols.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining(","));
        return stockQuoteService.fetchQuotes(symbols);
    }

    private List<SupportBreakRule> parseConfiguredRules(String rawRules) {
        if (!StringUtils.hasText(rawRules)) {
            return Collections.emptyList();
        }
        List<SupportBreakRule> rules = new ArrayList<>();
        for (String token : rawRules.split("[,;\\n]+")) {
            String item = token.trim();
            if (item.isEmpty()) continue;
            String[] parts = item.split(":");
            if (parts.length < 4) {
                log.warn("invalid stock-watch rule config: {}", item);
                continue;
            }
            StockRef ref = parseStockRef(parts[0]);
            if (ref == null) continue;
            SupportBreakRule rule = new SupportBreakRule();
            rule.symbol = ref.symbol;
            rule.market = ref.market;
            rule.ruleId = parts[1].trim();
            rule.support = number(parts[2], 0.0);
            rule.requiredBreakDays = integer(parts[3], 1);
            rule.severity = parts.length >= 5 && StringUtils.hasText(parts[4]) ? parts[4].trim() : "risk";
            rules.add(rule);
        }
        return rules;
    }

    private List<SupportBreakRule> parseRuleArray(JSONArray array) {
        return parseRuleList(array);
    }

    private List<SupportBreakRule> parseRuleList(List<?> rawRules) {
        List<SupportBreakRule> rules = new ArrayList<>();
        for (Object raw : rawRules) {
            if (!(raw instanceof Map)) continue;
            Map<?, ?> map = (Map<?, ?>) raw;
            String symbol = text(map, "symbol", null);
            if (!StringUtils.hasText(symbol)) continue;
            StockRef ref = parseStockRef(symbol + "." + text(map, "market", ""));
            if (ref == null) ref = parseStockRef(symbol);
            if (ref == null) continue;
            SupportBreakRule rule = new SupportBreakRule();
            rule.symbol = ref.symbol;
            rule.market = ref.market;
            rule.ruleId = text(map, "ruleId", "BREAK_SUPPORT");
            rule.support = number(map.get("support"), 0.0);
            rule.requiredBreakDays = integer(map.get("requiredBreakDays"), 1);
            rule.seedBreakDays = nullableInteger(map.get("seedBreakDays"));
            rule.severity = text(map, "severity", "risk");
            rules.add(rule);
        }
        return rules;
    }

    private List<Map<String, Object>> parseQuoteArray(JSONArray array) {
        return parseQuoteList(array);
    }

    private List<Map<String, Object>> parseQuoteList(List<?> rawQuotes) {
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (Object raw : rawQuotes) {
            if (!(raw instanceof Map)) continue;
            Map<?, ?> input = (Map<?, ?>) raw;
            Map<String, Object> quote = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                if (entry.getKey() != null) {
                    quote.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            quote.putIfAbsent("ok", true);
            quotes.add(quote);
        }
        return quotes;
    }

    private boolean isTradingSession(ZonedDateTime now) {
        switch (now.getDayOfWeek()) {
            case SATURDAY:
            case SUNDAY:
                return false;
            default:
                LocalTime time = now.toLocalTime();
                // The monitor serves both mainland and Hong Kong symbols. Use the union of
                // their continuous trading sessions so HK quotes remain monitored until 16:00.
                return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(12, 0)))
                        || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(16, 0)));
        }
    }

    private Map<String, Object> buildRunResult(ZonedDateTime now,
                                               String reason,
                                               boolean skipped,
                                               String message,
                                               List<Map<String, Object>> evaluations) {
        List<Map<String, Object>> safeEvaluations = evaluations == null ? Collections.emptyList() : evaluations;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ranAt", now.format(EVENT_TIME_FORMATTER));
        result.put("reason", reason);
        result.put("skipped", skipped);
        result.put("message", message);
        result.put("evaluations", safeEvaluations);
        result.put("evaluationCount", safeEvaluations.size());
        result.put("triggeredCount", countTruthy(safeEvaluations, "triggered"));
        result.put("aiJudgedCount", countAiJudged(safeEvaluations));
        result.put("notificationAttemptedCount", countTruthy(safeEvaluations, "notificationAttempted"));
        result.put("emailSentCount", countTruthy(safeEvaluations, "emailSent"));
        return result;
    }

    private void logScheduledRunResult(Map<String, Object> result, long elapsedMs) {
        log.info("stock-watch monitor scheduled scan end skipped={} message={} evaluationCount={} triggeredCount={} aiJudgedCount={} notificationAttemptedCount={} emailSentCount={} elapsedMs={}",
                result.get("skipped"),
                result.get("message"),
                integer(result.get("evaluationCount"), 0),
                integer(result.get("triggeredCount"), 0),
                integer(result.get("aiJudgedCount"), 0),
                integer(result.get("notificationAttemptedCount"), 0),
                integer(result.get("emailSentCount"), 0),
                elapsedMs);
    }

    private long countTruthy(List<Map<String, Object>> evaluations, String key) {
        return evaluations.stream().filter(item -> bool(item.get(key), false)).count();
    }

    private long countAiJudged(List<Map<String, Object>> evaluations) {
        return evaluations.stream()
                .filter(item -> "AI_RULE".equals(text(item, "type", "")) && item.containsKey("decision"))
                .count();
    }

    private void ensureStateLoaded() {
        if (stateLoaded) {
            return;
        }
        stateLoaded = true;
        Path file = stateFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JSONObject root = JSON.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            JSONObject states = root.getJSONObject("ruleStates");
            if (states != null) {
                for (String key : states.keySet()) {
                    JSONObject value = states.getJSONObject(key);
                    RuleState state = new RuleState();
                    state.breakDays = integer(value.get("breakDays"), 0);
                    String lastBelowDate = value.getString("lastBelowDate");
                    if (StringUtils.hasText(lastBelowDate)) {
                        state.lastBelowDate = LocalDate.parse(lastBelowDate);
                    }
                    state.lastPrice = number(value.get("lastPrice"), null);
                    state.lastEvaluatedAt = value.getString("lastEvaluatedAt");
                    state.lastSeedSource = value.getString("lastSeedSource");
                    ruleStates.put(key, state);
                }
            }
            JSONArray sent = root.getJSONArray("sentSignalKeys");
            if (sent != null) {
                for (int i = 0; i < sent.size(); i++) {
                    sentSignalKeys.add(sent.getString(i));
                }
            }
            JSONObject lastAlerts = root.getJSONObject("lastAlertAtByStock");
            if (lastAlerts != null) {
                for (String key : lastAlerts.keySet()) {
                    String value = lastAlerts.getString(key);
                    if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                        lastAlertAtByStock.put(key, value);
                    }
                }
            }
            JSONObject aiAlerts = root.getJSONObject("lastAiAlerts");
            if (aiAlerts != null) {
                for (String key : aiAlerts.keySet()) {
                    JSONObject value = aiAlerts.getJSONObject(key);
                    AiAlertState state = parseAiAlertState(value);
                    if (StringUtils.hasText(key) && state != null) {
                        lastAiAlerts.put(key, state);
                    }
                }
                trimAiAlertState();
            }
            if (lastAlertAtByStock.isEmpty() && !sentSignalKeys.isEmpty()) {
                String migratedAt = firstNonBlank(root.getString("savedAt"), ZonedDateTime.now(CHINA_ZONE).format(EVENT_TIME_FORMATTER));
                for (String signalKey : sentSignalKeys) {
                    String stockKey = stockKeyFromSentSignal(signalKey);
                    if (StringUtils.hasText(stockKey)) {
                        lastAlertAtByStock.putIfAbsent(stockKey, migratedAt);
                    }
                }
                trimStockAlertState();
            }
        } catch (Exception e) {
            log.warn("failed to load stock-watch monitor state: {}", e.getMessage());
        }
    }

    private void saveStateQuietly() {
        try {
            Path file = stateFile();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("ruleStates", stateSnapshot());
            root.put("sentSignalKeys", new ArrayList<>(sentSignalKeys));
            root.put("lastAlertAtByStock", new LinkedHashMap<>(lastAlertAtByStock));
            root.put("lastAiAlerts", aiAlertStateSnapshot());
            root.put("savedAt", ZonedDateTime.now(CHINA_ZONE).format(EVENT_TIME_FORMATTER));
            Files.write(file, JSON.toJSONString(root, true).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("failed to save stock-watch monitor state: {}", e.getMessage());
        }
    }

    private Map<String, Object> stateSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, RuleState> entry : ruleStates.entrySet()) {
            RuleState state = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("breakDays", state.breakDays);
            item.put("lastBelowDate", state.lastBelowDate == null ? null : state.lastBelowDate.toString());
            item.put("lastPrice", state.lastPrice);
            item.put("lastEvaluatedAt", state.lastEvaluatedAt);
            item.put("lastSeedSource", state.lastSeedSource);
            snapshot.put(entry.getKey(), item);
        }
        return snapshot;
    }

    private Map<String, Object> aiAlertStateSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, AiAlertState> entry : lastAiAlerts.entrySet()) {
            AiAlertState state = entry.getValue();
            if (state == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("lastSentAt", state.lastSentAt);
            item.put("signalShapeFingerprint", state.signalShapeFingerprint);
            item.put("severityRank", state.severityRank);
            item.put("riskScore", state.riskScore);
            item.put("riskTier", state.riskTier);
            item.put("normalizedFacts", new LinkedHashMap<>(state.normalizedFacts));
            item.put("reason", state.reason);
            item.put("source", state.source);
            snapshot.put(entry.getKey(), item);
        }
        return snapshot;
    }

    private AiAlertState parseAiAlertState(JSONObject value) {
        if (value == null) {
            return null;
        }
        AiAlertState state = new AiAlertState();
        state.lastSentAt = value.getString("lastSentAt");
        state.signalShapeFingerprint = value.getString("signalShapeFingerprint");
        state.severityRank = integer(value.get("severityRank"), 0);
        state.riskScore = integer(value.get("riskScore"), 0);
        state.riskTier = value.getString("riskTier");
        state.reason = value.getString("reason");
        state.source = value.getString("source");
        JSONObject facts = value.getJSONObject("normalizedFacts");
        if (facts != null) {
            for (String key : facts.keySet()) {
                state.normalizedFacts.put(key, facts.get(key));
            }
        }
        return state;
    }

    private Path stateFile() {
        if (StringUtils.hasText(configuredStateFile)) {
            return Paths.get(expandHome(configuredStateFile));
        }
        return Paths.get(System.getProperty("user.home"), ".aimeeting-room", "stock-watch-monitor-state.json");
    }

    private String expandHome(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private void trimSentSignalKeys() {
        int max = 500;
        while (sentSignalKeys.size() > max) {
            String first = sentSignalKeys.iterator().next();
            sentSignalKeys.remove(first);
        }
    }

    private void trimAiAlertState() {
        int max = 1000;
        while (lastAiAlerts.size() > max) {
            String first = lastAiAlerts.keySet().iterator().next();
            lastAiAlerts.remove(first);
        }
    }

    private boolean isAiDuplicate(LocalDate today, AiWatchRule rule) {
        int cooldownDays = rule.getCooldownDays() == null ? 1 : Math.max(1, rule.getCooldownDays());
        for (int i = 0; i < cooldownDays; i++) {
            if (sentSignalKeys.contains(aiDedupeKey(today.minusDays(i), rule))) {
                return true;
            }
        }
        return false;
    }

    private String aiDedupeKey(LocalDate date, AiWatchRule rule) {
        return date + ":AI:" + rule.getSymbol() + ":" + rule.getId();
    }

    private String aiAlertStateKey(AiWatchRule rule) {
        return stockKey(rule.getSymbol(), rule.getMarket()) + ":" + rule.getId();
    }

    private int effectiveDefaultAlertIntervalMinutes() {
        return Math.max(5, Math.min(24 * 60, defaultAlertIntervalMinutes));
    }

    private int effectiveAiAlertCooldownMinutes() {
        return Math.max(5, Math.min(240, aiAlertCooldownMinutes));
    }

    private int effectiveIntervalMinutes(Integer intervalMinutes) {
        return Math.max(5, Math.min(24 * 60, intervalMinutes == null ? 120 : intervalMinutes));
    }

    private ZonedDateTime parseEventTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return ZonedDateTime.parse(raw, EVENT_TIME_FORMATTER);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stockKey(String symbol, String market) {
        return firstNonBlank(symbol, "").toLowerCase(Locale.ROOT)
                + "."
                + firstNonBlank(market, StockSymbol.inferMarket(firstNonBlank(symbol, ""))).toLowerCase(Locale.ROOT);
    }

    private String stockKeyFromSentSignal(String signalKey) {
        if (!StringUtils.hasText(signalKey)) {
            return "";
        }
        String[] parts = signalKey.split(":");
        String symbol = "";
        if (parts.length >= 3 && "AI".equalsIgnoreCase(parts[1])) {
            symbol = parts[2];
        } else if (parts.length >= 2) {
            symbol = parts[1];
        }
        return symbol.matches("\\d{5,6}") ? stockKey(symbol, StockSymbol.inferMarket(symbol)) : "";
    }

    private List<String> parseRecipients(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.size(); i++) {
                values.add(array.getString(i));
            }
        } else if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                values.add(item == null ? "" : String.valueOf(item));
            }
        } else {
            String text = String.valueOf(raw).trim();
            if (text.startsWith("[") && text.endsWith("]")) {
                try {
                    return parseRecipients(JSON.parseArray(text));
                } catch (Exception ignored) {
                    // fallback to delimiter parsing
                }
            }
            values.addAll(Arrays.asList(text.split("[,;\\n\\s]+")));
        }
        return values.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(StringUtils::hasText)
                .filter(item -> EMAIL_PATTERN.matcher(item).matches())
                .distinct()
                .collect(Collectors.toList());
    }

    private void trimStockAlertState() {
        int max = 500;
        while (lastAlertAtByStock.size() > max) {
            String first = lastAlertAtByStock.keySet().iterator().next();
            lastAlertAtByStock.remove(first);
        }
    }

    private StockRef parseStockRef(String raw) {
        StockSymbol normalized = StockSymbol.tryParse(raw, "");
        return normalized == null ? null : new StockRef(normalized.getSymbol(), normalized.getMarket());
    }

    private String normalizeSymbol(String symbol) {
        StockRef ref = parseStockRef(symbol);
        return ref == null ? symbol : ref.symbol;
    }

    private String text(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }

    private Long longNumber(Object raw) {
        if (raw == null) return null;
        try {
            return raw instanceof Number ? ((Number) raw).longValue()
                    : Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean validFactReceipt(Map<String, Object> receipt) {
        if (receipt == null || receipt.isEmpty()) return false;
        String status = text(receipt.get("status"), "").toUpperCase(Locale.ROOT);
        return !"ERROR".equals(status) && !"SKIPPED".equals(status)
                && longNumber(receipt.get("observationId")) != null
                && StringUtils.hasText(text(receipt.get("observationKey"), ""))
                && longNumber(receipt.get("sourceTimeEpochMs")) != null;
    }

    private boolean validFactEvent(Map<String, Object> event) {
        return event != null
                && longNumber(event.get("target_fact_observation_id")) != null
                && StringUtils.hasText(text(event.get("target_fact_observation_key"), ""))
                && longNumber(event.get("target_source_time_epoch_ms")) != null;
    }

    private Double number(Object raw, Double fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Integer nullableInteger(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private int integer(Object raw, int fallback) {
        Integer value = nullableInteger(raw);
        return value == null ? fallback : value;
    }

    private Boolean nullableBool(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (Arrays.asList("true", "1", "yes", "y").contains(value)) return true;
        if (Arrays.asList("false", "0", "no", "n").contains(value)) return false;
        return null;
    }

    private boolean bool(Object raw, boolean fallback) {
        Boolean value = nullableBool(raw);
        return value == null ? fallback : value;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String directionalBucket(Double value, double deadZone) {
        if (value == null) {
            return "missing";
        }
        if (value > deadZone) {
            return "up";
        }
        if (value < -deadZone) {
            return "down";
        }
        return "flat";
    }

    private int absBucket(Double value, double bucketSize) {
        if (value == null || bucketSize <= 0) {
            return 0;
        }
        return (int) Math.floor(Math.abs(value) / bucketSize);
    }

    private int severityRank(String severity) {
        String normalized = firstNonBlank(severity, "").toLowerCase(Locale.ROOT);
        if (Arrays.asList("critical", "severe", "danger", "red", "high").contains(normalized)) {
            return 4;
        }
        if (Arrays.asList("risk", "warning", "warn", "amber", "medium").contains(normalized)) {
            return 3;
        }
        if (Arrays.asList("watch", "notice", "yellow", "low").contains(normalized)) {
            return 2;
        }
        if (Arrays.asList("info", "neutral", "green").contains(normalized)) {
            return 1;
        }
        return 0;
    }

    private String riskTier(int riskScore) {
        if (riskScore >= 8) {
            return "high";
        }
        if (riskScore >= 5) {
            return "medium";
        }
        return "low";
    }

    private int integerFromMap(Map<String, Object> values, String key, int fallback) {
        if (values == null) {
            return fallback;
        }
        return integer(values.get(key), fallback);
    }

    private static class SupportBreakRule {
        private String symbol;
        private String market;
        private String ruleId;
        private double support;
        private int requiredBreakDays;
        private Integer seedBreakDays;
        private String severity;

        private String stateKey() {
            return symbol + ":" + ruleId;
        }
    }

    private static class RuleState {
        private int breakDays = 0;
        private LocalDate lastBelowDate;
        private Double lastPrice;
        private String lastEvaluatedAt;
        private String lastSeedSource;
    }

    private static class AiAlertState {
        private String lastSentAt;
        private String signalShapeFingerprint;
        private int severityRank;
        private int riskScore;
        private String riskTier;
        private Map<String, Object> normalizedFacts = new LinkedHashMap<>();
        private String reason;
        private String source;
    }

    private static class AiSignalSnapshot {
        private String signalShapeFingerprint;
        private Map<String, Object> normalizedFacts = new LinkedHashMap<>();
        private int severityRank;
        private int riskScore;
        private String riskTier;
    }

    private static class AiAlertGate {
        private final boolean allowed;
        private final boolean duplicate;
        private final String reason;
        private final boolean signalShapeChanged;
        private final boolean severityRankUp;
        private final boolean materialRiskDelta;

        private AiAlertGate(boolean allowed,
                            boolean duplicate,
                            String reason,
                            boolean signalShapeChanged,
                            boolean severityRankUp,
                            boolean materialRiskDelta) {
            this.allowed = allowed;
            this.duplicate = duplicate;
            this.reason = reason;
            this.signalShapeChanged = signalShapeChanged;
            this.severityRankUp = severityRankUp;
            this.materialRiskDelta = materialRiskDelta;
        }

        private static AiAlertGate allowed(String reason) {
            return allowed(reason, false, false, false);
        }

        private static AiAlertGate allowed(String reason,
                                           boolean signalShapeChanged,
                                           boolean severityRankUp,
                                           boolean materialRiskDelta) {
            return new AiAlertGate(true, false, reason, signalShapeChanged, severityRankUp, materialRiskDelta);
        }

        private static AiAlertGate blocked(String reason, boolean duplicate) {
            return blocked(reason, duplicate, false, false, false);
        }

        private static AiAlertGate blocked(String reason,
                                           boolean duplicate,
                                           boolean signalShapeChanged,
                                           boolean severityRankUp,
                                           boolean materialRiskDelta) {
            return new AiAlertGate(false, duplicate, reason, signalShapeChanged, severityRankUp, materialRiskDelta);
        }
    }

    private static class CooldownGate {
        private final boolean due;
        private final String reason;

        private CooldownGate(boolean due, String reason) {
            this.due = due;
            this.reason = reason;
        }

        private static CooldownGate allowed() {
            return new CooldownGate(true, "");
        }

        private static CooldownGate blocked(String reason) {
            return new CooldownGate(false, reason);
        }
    }

    private static class AlertSendResult {
        private final boolean attempted;
        private final boolean sent;
        private final String messageId;
        private final String skippedReason;
        private final String errorMessage;

        private AlertSendResult(boolean attempted,
                                boolean sent,
                                String messageId,
                                String skippedReason,
                                String errorMessage) {
            this.attempted = attempted;
            this.sent = sent;
            this.messageId = messageId;
            this.skippedReason = skippedReason;
            this.errorMessage = errorMessage;
        }

        private static AlertSendResult notAttempted() {
            return new AlertSendResult(false, false, null, null, null);
        }

        private static AlertSendResult skipped(String reason) {
            return new AlertSendResult(false, false, null, reason, null);
        }

        private static AlertSendResult sent(String messageId, String message) {
            return new AlertSendResult(true, true, messageId, null, null);
        }

        private static AlertSendResult failed(String errorMessage) {
            return new AlertSendResult(true, false, null, null, errorMessage);
        }
    }

    private static class RuleCapabilityGate {
        private final boolean allowed;
        private final String policy;
        private final String reason;

        private RuleCapabilityGate(boolean allowed, String policy, String reason) {
            this.allowed = allowed;
            this.policy = policy;
            this.reason = reason;
        }

        private static RuleCapabilityGate allowed(String policy, String reason) {
            return new RuleCapabilityGate(true, policy, reason);
        }

        private static RuleCapabilityGate blocked(String policy, String reason) {
            return new RuleCapabilityGate(false, policy, reason);
        }
    }

    private static class AnalysisDemandGate {
        private final boolean allowed;
        private final StockWatchItem item;
        private final String status;
        private final String reason;

        private AnalysisDemandGate(boolean allowed, StockWatchItem item, String status, String reason) {
            this.allowed = allowed;
            this.item = item;
            this.status = status;
            this.reason = reason;
        }

        private static AnalysisDemandGate allowed(StockWatchItem item) {
            return new AnalysisDemandGate(true, item, null, null);
        }

        private static AnalysisDemandGate blocked(String status, String reason) {
            return new AnalysisDemandGate(false, null, status, reason);
        }
    }

    private static class StockRef {
        private final String symbol;
        private final String market;

        private StockRef(String symbol, String market) {
            this.symbol = symbol;
            this.market = market;
        }
    }
}
