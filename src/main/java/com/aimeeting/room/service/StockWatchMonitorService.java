package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchDeliveryConfigMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchDeliveryConfig;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.service.StockWatchAiJudgeService.AiDecision;
import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
import lombok.extern.slf4j.Slf4j;
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

    private final StockQuoteService stockQuoteService;
    private final NotificationService notificationService;
    private final StockWatchAiRuleService aiRuleService;
    private final StockWatchAiJudgeService aiJudgeService;
    private final StockWatchDeliveryConfigMapper deliveryConfigMapper;
    private final EastmoneyMarketDataService marketDataService;
    private final StockWatchTechnicalAnalysisMapper technicalAnalysisMapper;
    private final Map<String, RuleState> ruleStates = new LinkedHashMap<>();
    private final Set<String> sentSignalKeys = new LinkedHashSet<>();
    private final Map<String, String> lastAlertAtByStock = new LinkedHashMap<>();
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

    @Value("${stock-watch.monitor.ai-context-max-age-minutes:360}")
    private int aiContextMaxAgeMinutes;

    public StockWatchMonitorService(StockQuoteService stockQuoteService,
                                    NotificationService notificationService,
                                    StockWatchAiRuleService aiRuleService,
                                    StockWatchAiJudgeService aiJudgeService,
                                    StockWatchDeliveryConfigMapper deliveryConfigMapper,
                                    EastmoneyMarketDataService marketDataService,
                                    StockWatchTechnicalAnalysisMapper technicalAnalysisMapper) {
        this.stockQuoteService = stockQuoteService;
        this.notificationService = notificationService;
        this.aiRuleService = aiRuleService;
        this.aiJudgeService = aiJudgeService;
        this.deliveryConfigMapper = deliveryConfigMapper;
        this.marketDataService = marketDataService;
        this.technicalAnalysisMapper = technicalAnalysisMapper;
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
        if (tradingHoursOnly && !ignoreTradingHours && !isTradingSession(now)) {
            lastRunStatus = "skipped_non_trading_session";
            return buildRunResult(now, reason, true, "非交易时段，跳过盯盘扫描", Collections.emptyList());
        }

        List<SupportBreakRule> rules = resolveRules(request.get("rules"));
        List<AiWatchRule> aiRules = resolveAiRules(request.get("aiRules"));
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
        for (SupportBreakRule rule : rules) {
            Map<String, Object> quote = quoteBySymbol.get(rule.symbol);
            Map<String, Object> evaluation = evaluateRule(rule, quote, now, notify);
            evaluations.add(evaluation);
        }
        for (AiWatchRule rule : aiRules) {
            Map<String, Object> quote = quoteBySymbol.get(normalizeSymbol(rule.getSymbol()));
            Map<String, Object> evaluation = evaluateAiRule(rule, quote, now, notify);
            evaluations.add(evaluation);
        }

        saveStateQuietly();
        lastRunStatus = "success";
        return buildRunResult(now, reason, false, "盯盘扫描完成", evaluations);
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
        status.put("lastRunAt", lastRunAt == null ? null : lastRunAt.format(EVENT_TIME_FORMATTER));
        status.put("lastRunStatus", lastRunStatus);
        status.put("ruleStates", stateSnapshot());
        status.put("sentSignalCount", sentSignalKeys.size());
        status.put("lastAlertAtByStock", new LinkedHashMap<>(lastAlertAtByStock));
        status.put("aiRuleCount", aiRuleService.listEnabled().size());
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

        Map<String, Object> aiContext = buildAiContext(rule, quote, now);
        AiDecision decision = aiJudgeService.judge(rule, quote, now, aiContext);
        boolean confidenceMet = decision.getConfidence() >= rule.getMinConfidence();
        boolean triggered = decision.isTriggered() && confidenceMet;
        LocalDate today = now.toLocalDate();
        boolean duplicate = isAiDuplicate(today, rule);
        boolean notificationAttempted = triggered && notify && rule.isNotify() && decision.isShouldNotify() && !duplicate;
        boolean emailSent = false;
        AlertSendResult alertResult = AlertSendResult.notAttempted();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", "ai_watch_" + rule.getSymbol() + "_" + rule.getId() + "_" + today);
        event.put("timestamp", now.format(EVENT_TIME_FORMATTER));
        event.put("symbol", rule.getSymbol());
        event.put("market", rule.getMarket());
        event.put("name", rule.getName());
        event.put("trigger_type", "AI_RULE");
        event.put("rule_id", rule.getId());
        event.put("severity", decision.getSeverity());
        event.put("condition_text", rule.getConditionText());
        event.put("triggered", triggered);
        event.put("confidence", decision.getConfidence());
        event.put("min_confidence", rule.getMinConfidence());
        event.put("reason", decision.getReason());
        event.put("matched_signals", decision.getMatchedSignals());
        event.put("missing_data", decision.getMissingData());
        event.put("quote", quote);
        event.put("ai_context_status", aiContextStatus(aiContext));
        event.put("ai_context_missing_data", aiContext == null ? Collections.emptyList() : aiContext.get("missingData"));

        if (notificationAttempted) {
            alertResult = sendAiTriggeredEmail(rule, decision, event, now);
            emailSent = alertResult.sent;
            if (emailSent) {
                sentSignalKeys.add(aiDedupeKey(today, rule));
                trimSentSignalKeys();
            }
        }

        result.put("triggered", triggered);
        result.put("confidenceMet", confidenceMet);
        result.put("decision", decision);
        result.put("reason", decision.getReason());
        result.put("event", event);
        result.put("aiContextStatus", aiContextStatus(aiContext));
        result.put("duplicate", duplicate);
        result.put("notificationAttempted", alertResult.attempted);
        result.put("notificationSkippedReason", alertResult.skippedReason);
        result.put("notificationError", alertResult.errorMessage);
        result.put("emailSent", emailSent);
        return result;
    }

    private Map<String, Object> buildAiContext(AiWatchRule rule, Map<String, Object> quote, ZonedDateTime now) {
        if (!requiresEnhancedAiContext(rule)) {
            return Collections.emptyMap();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("status", "requested");
        context.put("reason", "condition_requires_enhanced_context");
        context.put("requestedAt", now.format(EVENT_TIME_FORMATTER));
        try {
            EastmoneyMarketDataService.MarketDataBundle bundle = marketDataService.buildBundle(rule.getSymbol(), rule.getMarket(), "DAY");
            if (bundle == null) {
                context.put("status", "unavailable");
                context.put("error", "market_data_bundle_empty");
                return context;
            }
            List<String> requiredMissing = bundle.getRequiredMissing();
            if (requiredMissing != null && !requiredMissing.isEmpty()) {
                context.put("status", "unavailable");
                context.put("missingData", requiredMissing);
                context.put("error", "required_market_data_missing");
                return context;
            }
            Map<String, Object> payload = bundle.getPayload();
            context.put("status", "available");
            context.put("source", "EastmoneyMarketDataService.buildBundle compact summary");
            context.put("asOf", payload.get("asOf"));

            Map<String, Object> currentFacts = new LinkedHashMap<>();
            copyIfPresent(payload, currentFacts, "anchorFacts");
            copyIfPresent(payload, currentFacts, "technicalIndicators");
            copyIfPresent(payload, currentFacts, "valuation");
            copyIfPresent(payload, currentFacts, "marketBenchmark");
            copyIfPresent(payload, currentFacts, "dataQuality");
            if (!currentFacts.isEmpty()) {
                context.put("currentFacts", currentFacts);
            }

            Map<String, Object> recentSeries = new LinkedHashMap<>();
            putTailIfPresent(payload, recentSeries, "minuteTrends5d", 6);
            putTailIfPresent(payload, recentSeries, "intradayFundFlow", 6);
            putHeadIfPresent(payload, recentSeries, "latestNews", 3);
            if (!recentSeries.isEmpty()) {
                context.put("recentSeries", recentSeries);
            }

            StockWatchTechnicalAnalysis recentAnalysis = latestSuccessfulTechnicalAnalysis(rule, now);
            if (recentAnalysis != null) {
                context.put("recentTechnicalAnalysis", toTechnicalContext(recentAnalysis));
            }
            return context;
        } catch (Exception e) {
            context.put("status", "unavailable");
            context.put("error", firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
            return context;
        }
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

    private StockWatchTechnicalAnalysis latestSuccessfulTechnicalAnalysis(AiWatchRule rule, ZonedDateTime now) {
        if (technicalAnalysisMapper == null || rule == null) {
            return null;
        }
        int maxAge = Math.max(30, Math.min(24 * 60, aiContextMaxAgeMinutes));
        Date since = Date.from(now.minusMinutes(maxAge).toInstant());
        try {
            return technicalAnalysisMapper.selectLatestSuccessfulBySymbolSince(
                    rule.getSymbol(),
                    rule.getMarket(),
                    "DAY",
                    since
            );
        } catch (Exception e) {
            log.warn("stock-watch AI context recent technical analysis unavailable symbol={}.{} error={}",
                    rule.getSymbol(), rule.getMarket(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toTechnicalContext(StockWatchTechnicalAnalysis analysis) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", analysis.getId());
        item.put("analysisDate", String.valueOf(analysis.getAnalysisDate()));
        item.put("runSlot", analysis.getRunSlot());
        item.put("periodType", analysis.getPeriodType());
        item.put("status", analysis.getStatus());
        item.put("confidence", analysis.getConfidence());
        item.put("gmtCreate", String.valueOf(analysis.getGmtCreate()));
        item.put("usage", "metadata_only_do_not_use_as_trigger_fact");
        item.put("excludedFields", Arrays.asList("title", "conclusion", "evidence", "mainForceIntent"));
        return item;
    }

    private String aiContextStatus(Map<String, Object> aiContext) {
        if (aiContext == null || aiContext.isEmpty()) {
            return "not_required";
        }
        return firstNonBlank(String.valueOf(aiContext.get("status")), "available");
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
        appendTitle(content, "Stock Watch AI 盯盘提醒");
        appendSection(content, "一、触发摘要");
        appendLine(content, "标的", rule.getName() + "（" + rule.getSymbol() + "." + rule.getMarket() + "）");
        appendLine(content, "结果", "DeepSeek 判断观察条件已触发");
        appendLine(content, "级别", firstNonBlank(decision.getSeverity(), rule.getSeverity(), "--"));
        appendLine(content, "置信度", format(decision.getConfidence()) + " / 阈值 " + format(rule.getMinConfidence()));
        appendLine(content, "时间", text(event, "timestamp", "--"));

        appendSection(content, "二、关键行情");
        appendQuoteLine(content, quote, "最新价", "price");
        appendQuoteLine(content, quote, "涨跌幅", "changePercent", "%");
        appendQuoteLine(content, quote, "量比", "volumeRatio");
        appendQuoteLine(content, quote, "换手率", "turnover", "%");
        appendQuoteLine(content, quote, "主力净流入", "mainInflow");

        appendSection(content, "三、DeepSeek 分析建议");
        appendParagraph(content, firstNonBlank(decision.getAnalysis(), "本轮 DeepSeek 未返回明确分析建议。"), "  ");
        appendLine(content, "分析模型", firstNonBlank(decision.getModel(), "--"));

        appendSection(content, "四、触发依据");
        appendParagraph(content, firstNonBlank(decision.getReason(), "未返回触发依据。"), "  ");
        if (decision.getMissingData() != null && !decision.getMissingData().isEmpty()) {
            content.append("\n  数据缺失：\n");
            for (String missing : decision.getMissingData()) {
                appendParagraph(content, missing, "    - ");
            }
        }

        appendSection(content, "五、观察条件");
        appendParagraph(content, compactCondition(rule.getConditionText()), "  ");
        return sendAlertWithFrequencyGate(rule.getSymbol(), rule.getMarket(), rule.getName(), subject, content.toString(), now);
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
        Set<String> activeStocks = new LinkedHashSet<>();
        rules.forEach(rule -> activeStocks.add(stockKey(rule.symbol, rule.market)));
        aiRules.forEach(rule -> activeStocks.add(stockKey(rule.getSymbol(), rule.getMarket())));

        ruleStates.keySet().removeIf(key -> !activeSupportRules.contains(key));
        sentSignalKeys.removeIf(key -> !isActiveSentSignal(key, activeSupportRules, activeAiRules));
        lastAlertAtByStock.keySet().removeIf(key -> !activeStocks.contains(key));
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
                return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30)))
                        || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0)));
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

    private int effectiveDefaultAlertIntervalMinutes() {
        return Math.max(5, Math.min(24 * 60, defaultAlertIntervalMinutes));
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
                + firstNonBlank(market, inferMarket(firstNonBlank(symbol, ""))).toLowerCase(Locale.ROOT);
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
        return symbol.matches("\\d{6}") ? stockKey(symbol, inferMarket(symbol)) : "";
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
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        java.util.regex.Matcher prefixed = java.util.regex.Pattern
                .compile("^(sh|sz|bj)[._-]?(\\d{6})$")
                .matcher(value);
        if (prefixed.matches()) {
            return new StockRef(prefixed.group(2), prefixed.group(1));
        }
        java.util.regex.Matcher suffixed = java.util.regex.Pattern
                .compile("^(\\d{6})(?:[._-]?(sh|sz|bj))?$")
                .matcher(value);
        if (!suffixed.matches()) {
            return null;
        }
        String symbol = suffixed.group(1);
        String market = StringUtils.hasText(suffixed.group(2)) ? suffixed.group(2) : inferMarket(symbol);
        return new StockRef(symbol, market);
    }

    private String inferMarket(String symbol) {
        if (symbol.startsWith("6") || symbol.startsWith("9")) return "sh";
        if (symbol.startsWith("4") || symbol.startsWith("8")) return "bj";
        return "sz";
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

    private static class StockRef {
        private final String symbol;
        private final String market;

        private StockRef(String symbol, String market) {
            this.symbol = symbol;
            this.market = market;
        }
    }
}
