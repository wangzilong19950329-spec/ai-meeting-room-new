package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.entity.StockWatchTechnicalSession;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StockWatchTechnicalAnalysisService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter SLOT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter BATCH_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MONTH_DAY_FORMATTER = DateTimeFormatter.ofPattern("MM/dd");
    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final List<LocalTime> RUN_SLOTS = Arrays.asList(
            LocalTime.of(9, 30),
            LocalTime.of(10, 0),
            LocalTime.of(10, 30),
            LocalTime.of(11, 0),
            LocalTime.of(11, 30),
            LocalTime.of(13, 0),
            LocalTime.of(13, 30),
            LocalTime.of(14, 0),
            LocalTime.of(14, 30),
            LocalTime.of(15, 0)
    );
    private static final List<String> MAIN_FORCE_INTENTS = Arrays.asList(
            "ACCUMULATE", "DISTRIBUTE", "WASH", "PULL_UP", "SUPPORT", "SUPPRESS", "ROTATE", "NEUTRAL", "UNKNOWN"
    );
    private static final List<String> MAIN_FORCE_WINDOWS = Arrays.asList("INTRADAY", "THREE_DAY", "FIVE_DAY", "TWENTY_DAY");
    private static final Set<String> STRONG_MAIN_FORCE_INTENTS = new LinkedHashSet<>(Arrays.asList(
            "ACCUMULATE", "DISTRIBUTE", "WASH", "PULL_UP", "SUPPORT", "SUPPRESS", "ROTATE"
    ));
    private static final java.util.regex.Pattern EVIDENCE_PATH_PATTERN = java.util.regex.Pattern.compile(
            "(?:evidenceSignals|marketData)\\.[A-Za-z0-9_.$\\[\\]-]+"
    );

    private final EastmoneyMarketDataService marketDataService;
    private final StockWatchPredictionService predictionService;
    private final StockWatchListService watchListService;
    private final StockWatchTechnicalSessionMapper sessionMapper;
    private final StockWatchTechnicalAnalysisMapper analysisMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${stock-watch.technical-analysis.enabled:true}")
    private boolean enabled;

    @Value("${stock-watch.technical-analysis.trading-hours-only:true}")
    private boolean tradingHoursOnly;

    @Value("${stock-watch.technical-analysis.symbols:${stock-watch.monitor.symbols:688017.sh,300760.sz,002594.sz}}")
    private String configuredSymbols;

    @Value("${stock-watch.technical-analysis.context-limit-chars:80000}")
    private int contextLimitChars;

    @Value("${stock-watch.technical-analysis.context-rotate-ratio:0.85}")
    private double contextRotateRatio;

    @Value("${stock-watch.technical-analysis.bridge-url:${ai.search.deepseek-web.bridge-url:http://127.0.0.1:8789/search}}")
    private String bridgeUrl;

    @Value("${stock-watch.technical-analysis.debug-port:${DEEPSEEK_WEB_DEBUG_PORT:9333}}")
    private int debugPort;

    @Value("${stock-watch.technical-analysis.connect-timeout-seconds:${ai.search.deepseek-web.connect-timeout-seconds:10}}")
    private int connectTimeoutSeconds;

    @Value("${stock-watch.technical-analysis.read-timeout-seconds:${ai.search.deepseek-web.read-timeout-seconds:320}}")
    private int readTimeoutSeconds;

    private volatile ZonedDateTime lastRunAt;
    private volatile String lastRunStatus = "never";

    public StockWatchTechnicalAnalysisService(EastmoneyMarketDataService marketDataService,
                                              StockWatchPredictionService predictionService,
                                              StockWatchListService watchListService,
                                              StockWatchTechnicalSessionMapper sessionMapper,
                                              StockWatchTechnicalAnalysisMapper analysisMapper) {
        this.marketDataService = marketDataService;
        this.predictionService = predictionService;
        this.watchListService = watchListService;
        this.sessionMapper = sessionMapper;
        this.analysisMapper = analysisMapper;
    }

    @Scheduled(
            fixedDelayString = "${stock-watch.technical-analysis.scan-interval-ms:1800000}",
            initialDelayString = "${stock-watch.technical-analysis.initial-delay-ms:90000}"
    )
    public void scheduledAnalyze() {
        if (!enabled) {
            log.info("stock-watch technical analysis scheduled skip reason=disabled");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("stock-watch technical analysis scheduled skip reason=already_running");
            return;
        }
        try {
            runScheduledBatch();
        } catch (Exception e) {
            lastRunStatus = "error: " + e.getMessage();
            log.warn("stock-watch technical analysis scheduled batch failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", enabled);
        payload.put("tradingHoursOnly", tradingHoursOnly);
        payload.put("symbols", configuredSymbols);
        payload.put("scheduledSymbols", scheduledSymbolLabels());
        payload.put("bridgeUrl", bridgeUrl);
        payload.put("contextLimitChars", contextLimitChars);
        payload.put("contextRotateRatio", contextRotateRatio);
        payload.put("lastRunAt", lastRunAt == null ? null : lastRunAt.toString());
        payload.put("lastRunStatus", lastRunStatus);
        payload.put("running", running.get());
        return payload;
    }

    public Map<String, Object> history(String rawSymbol,
                                       String rawMarket,
                                       LocalDate selectedDate,
                                       int days,
                                       String rawPeriodType,
                                       String selectedPeriodKey) {
        StockRef ref = normalizeStockRef(rawSymbol, rawMarket);
        String periodType = normalizePeriodType(rawPeriodType);
        int safePeriods = Math.max(1, Math.min(days, "DAY".equals(periodType) ? 30 : 12));
        LocalDate endDate = LocalDate.now(CHINA_ZONE);
        LocalDate startDate = "DAY".equals(periodType) ? endDate.minusDays(safePeriods - 1L) : oldestPeriodStart(periodType, endDate, safePeriods);
        LocalDate effectiveSelectedDate = selectedDate != null ? selectedDate : endDate;
        String effectivePeriodKey = StringUtils.hasText(selectedPeriodKey)
                ? selectedPeriodKey.trim()
                : periodKey(periodType, effectiveSelectedDate);

        List<StockWatchTechnicalAnalysis> records = analysisMapper.selectBySymbolDateRange(
                ref.symbol,
                ref.market,
                Date.valueOf(startDate),
                Date.valueOf(endDate),
                periodType
        );
        List<Map<String, Object>> periodsPayload = buildPeriodsPayload(periodType, endDate, safePeriods, records);

        List<Map<String, Object>> selectedRecords = records.stream()
                .filter(record -> effectivePeriodKey.equals(firstNonBlank(record.getPeriodKey(), periodKey(periodType, toLocalDate(record.getAnalysisDate())))))
                .map(this::toRecordPayload)
                .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", ref.symbol);
        payload.put("market", ref.market);
        payload.put("periodType", periodType);
        payload.put("selectedPeriodKey", effectivePeriodKey);
        payload.put("selectedDate", effectiveSelectedDate.toString());
        payload.put("days", periodsPayload);
        payload.put("periods", periodsPayload);
        payload.put("records", selectedRecords);
        payload.put("session", toSessionPayload(sessionMapper.selectActive(ref.symbol, ref.market, periodType), ref, periodType));
        return payload;
    }

    public Map<String, Object> runManual(Map<String, Object> request) {
        StockRef ref = normalizeStockRef(text(request.get("symbol"), ""), text(request.get("market"), ""));
        String name = text(request.get("name"), ref.symbol);
        String periodType = normalizePeriodType(text(request.get("periodType"), "DAY"));
        boolean forceNewSession = bool(request.get("forceNewSession"), false);
        boolean ignoreTradingHours = bool(request.get("ignoreTradingHours"), true);
        StockWatchTechnicalAnalysis record = runAnalysis(ref, name, "manual_api", forceNewSession, ignoreTradingHours, periodType, false);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("record", toRecordPayload(record));
        payload.put("session", toSessionPayload(sessionMapper.selectById(record.getSessionId()), ref, record.getPeriodType()));
        return payload;
    }

    private void runScheduledBatch() {
        long batchStartedAt = System.nanoTime();
        ZonedDateTime now = ZonedDateTime.now(CHINA_ZONE);
        String batchId = now.format(BATCH_ID_FORMATTER);
        lastRunAt = now;
        boolean tradingSession = isTradingSession(now.toLocalTime());
        boolean weeklyDue = isWeeklyDue(now);
        boolean monthlyDue = isMonthlyDue(now);
        String daySlot = tradingSession ? currentRunSlot(now, false) : "";
        List<StockRef> refs = scheduledRefs();
        BatchStats stats = new BatchStats();
        log.info("stock-watch technical analysis batch start batchId={} now={} refs={} tradingSession={} daySlot={} weeklyDue={} monthlyDue={}",
                batchId, now, refs.size(), tradingSession, daySlot, weeklyDue, monthlyDue);
        if (tradingHoursOnly && !tradingSession && !weeklyDue && !monthlyDue) {
            lastRunStatus = "skipped_non_trading_session";
            log.info("stock-watch technical analysis batch end batchId={} status=skipped reason=non_trading_session refs={} elapsedMs={}",
                    batchId, refs.size(), elapsedMillis(batchStartedAt));
            return;
        }
        for (StockRef ref : refs) {
            try {
                if (tradingSession) {
                    stats.record(runPeriodIfMissing(ref, now, "DAY", "scheduled", false, batchId));
                }
                if (weeklyDue) {
                    stats.record(runPeriodIfMissing(ref, now, "WEEK", "scheduled_weekly", true, batchId));
                }
                if (monthlyDue) {
                    stats.record(runPeriodIfMissing(ref, now, "MONTH", "scheduled_monthly", true, batchId));
                }
            } catch (Exception e) {
                stats.recordFailure();
                log.warn("stock-watch technical analysis symbol aborted batchId={} symbol={} reason={} error={}",
                        batchId, stockLabel(ref), classifySkipReason(e), diagnosticMessage(e));
            }
        }
        lastRunStatus = "success";
        log.info("stock-watch technical analysis batch end batchId={} status=success refs={} attempted={} completed={} skipped={} failed={} elapsedMs={}",
                batchId, refs.size(), stats.attempted, stats.completed, stats.skipped, stats.failed,
                elapsedMillis(batchStartedAt));
    }

    private PeriodRunResult runPeriodIfMissing(StockRef ref,
                                               ZonedDateTime now,
                                               String periodType,
                                               String reason,
                                               boolean ignoreTradingHours,
                                               String batchId) {
        long stockStartedAt = System.nanoTime();
        PeriodWindow window = currentPeriodWindow(periodType, now.toLocalDate());
        String runSlot = "DAY".equals(periodType) ? currentRunSlot(now, ignoreTradingHours) : periodType;
        String symbol = stockLabel(ref);
        log.info("stock-watch technical analysis stock start batchId={} symbol={} period={} slot={} periodKey={} reason={}",
                batchId, symbol, periodType, runSlot, window.key, reason);
        try {
            StockWatchTechnicalAnalysis record = runAnalysis(ref, ref.symbol, reason, false, ignoreTradingHours, periodType, true);
            String recordStatus = text(record.getStatus(), "UNKNOWN");
            String outcome = "SUCCESS".equalsIgnoreCase(recordStatus) ? "completed" : "failed";
            log.info("stock-watch technical analysis stock end batchId={} symbol={} period={} slot={} recordSlot={} periodKey={} outcome={} recordStatus={} recordId={} elapsedMs={}",
                    batchId, symbol, periodType, runSlot, record.getRunSlot(), window.key, outcome,
                    recordStatus, record.getId(), elapsedMillis(stockStartedAt));
            return PeriodRunResult.completed(recordStatus);
        } catch (ExistingAnalysisSkippedException e) {
            StockWatchTechnicalAnalysis existing = e.existing;
            log.info("stock-watch technical analysis stock skip batchId={} symbol={} period={} slot={} periodKey={} sourceDate={} reason=existing_success recordId={} elapsedMs={}",
                    batchId, symbol, periodType, existing.getRunSlot(), existing.getPeriodKey(), existing.getAnalysisDate(),
                    existing.getId(), elapsedMillis(stockStartedAt));
            return PeriodRunResult.skipped();
        } catch (Exception e) {
            log.warn("stock-watch technical analysis stock skip batchId={} symbol={} period={} slot={} periodKey={} reason={} error={} elapsedMs={}",
                    batchId, symbol, periodType, runSlot, window.key, classifySkipReason(e),
                    diagnosticMessage(e), elapsedMillis(stockStartedAt));
            throw e;
        }
    }

    private StockWatchTechnicalAnalysis runAnalysis(StockRef ref,
                                                    String requestedName,
                                                    String reason,
                                                    boolean forceNewSession,
                                                    boolean ignoreTradingHours,
                                                    String rawPeriodType,
                                                    boolean skipExistingSuccess) {
        String periodType = normalizePeriodType(rawPeriodType);
        ZonedDateTime now = ZonedDateTime.now(CHINA_ZONE);
        if ("DAY".equals(periodType) && tradingHoursOnly && !ignoreTradingHours && !isTradingSession(now.toLocalTime())) {
            throw new IllegalStateException("非交易时段，已跳过自动技术面分析");
        }

        EastmoneyMarketDataService.MarketDataBundle marketData = marketDataService.buildBundle(ref.symbol, ref.market, periodType);
        if (!marketData.canPredict()) {
            throw new IllegalStateException("预测必需数据未就绪：" + String.join("；", marketData.getRequiredMissing()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> quote = (Map<String, Object>) marketData.getPayload().getOrDefault("quote", Collections.emptyMap());
        String stockName = text(quote.get("name"), requestedName);
        LocalDate sourceTradeDate = sourceTradeDate(marketData.getPayload(), now.toLocalDate());
        PeriodWindow window = currentPeriodWindow(periodType, sourceTradeDate);
        String runSlot = "DAY".equals(periodType) ? currentRunSlot(now, ignoreTradingHours) : periodType;
        if (skipExistingSuccess) {
            StockWatchTechnicalAnalysis existing = analysisMapper.selectLatestBySymbolDateSlot(
                    ref.symbol,
                    ref.market,
                    Date.valueOf(sourceTradeDate),
                    runSlot,
                    periodType,
                    window.key
            );
            if (existing != null && "SUCCESS".equalsIgnoreCase(existing.getStatus())) {
                throw new ExistingAnalysisSkippedException(existing);
            }
        }
        Map<String, Object> evidenceSignals = buildEvidenceSignals(marketData.getPayload(), periodType);
        StockWatchTechnicalSession session = getOrCreateSession(ref, stockName, forceNewSession, periodType);
        List<StockWatchTechnicalAnalysis> sourceRecords = sourceRecordsForPeriod(ref, periodType, window);
        String prompt = buildPrompt(ref, stockName, marketData, evidenceSignals, session, reason, periodType, window, sourceRecords);
        String promptHash = sha256(prompt).substring(0, 24);

        BridgeResult bridgeResult = null;
        AnalysisPayload parsed;
        IntentValidationResult intentValidation = IntentValidationResult.invalid("mainForceIntent_not_checked");
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setSessionId(session.getId());
        record.setSymbol(ref.symbol);
        record.setMarket(ref.market);
        record.setStockName(stockName);
        record.setAnalysisDate(Date.valueOf(sourceTradeDate));
        record.setRunSlot(runSlot);
        record.setPeriodType(periodType);
        record.setPeriodKey(window.key);
        record.setSourceRecordIds(sourceRecordIdsJson(sourceRecords));
        record.setEvidenceSignalJson(JSON.toJSONString(evidenceSignals));
        record.setProvider("deepseek_web_bridge");
        record.setPromptHash(promptHash);
        try {
            bridgeResult = callDeepSeekBridgeWithRetry(prompt, session, forceNewSession, periodType);
            parsed = parseAnalysisPayload(bridgeResult.answerText, stockName);
            intentValidation = validateMainForceIntent(parsed.mainForceIntent, evidenceSignals);
            if (!hasRequiredForecasts(parsed.forecasts, periodType) || !intentValidation.valid) {
                bridgeResult = callDeepSeekBridge(structuredRetryPrompt(periodType, intentValidation.problems), session, false);
                parsed = parseAnalysisPayload(bridgeResult.answerText, stockName);
                intentValidation = validateMainForceIntent(parsed.mainForceIntent, evidenceSignals);
            }
            if (!hasRequiredForecasts(parsed.forecasts, periodType)) {
                throw new ModelResponseSchemaException("DeepSeek 返回JSON缺少当前周期要求的完整 forecasts，未保存不完整预测"
                        + schemaWarningSuffix(parsed));
            }
            if (!intentValidation.valid) {
                throw new ModelResponseSchemaException("DeepSeek 返回JSON缺少可校验的 mainForceIntent，未保存不完整主力意图："
                        + String.join("；", intentValidation.problems) + schemaWarningSuffix(parsed));
            }
            parsed.missingData = new ArrayList<>();
            parsed.missingData.addAll(marketData.getRequiredMissing());
            parsed.missingData.addAll(marketData.getOptionalMissing());
            record.setStatus("SUCCESS");
            record.setStance(parsed.stance);
            record.setTone(parsed.tone);
            record.setConfidence(parsed.confidence);
            record.setTitle(parsed.title);
            record.setConclusion(parsed.conclusion);
            record.setEvidenceJson(JSON.toJSONString(parsed.evidence));
            record.setMissingDataJson(JSON.toJSONString(parsed.missingData));
            record.setMainForceIntentJson(intentValidation.intent.toJSONString());
            record.setMainForceIntentStatus(intentValidation.status);
            record.setMainForceIntentWarningsJson(JSON.toJSONString(intentValidation.warnings));
            record.setDataSnapshotJson(marketData.toJson());
            record.setRawResponse(bridgeResult.answerText);
            record.setBridgeTargetId(bridgeResult.targetId);
            record.setConversationUrl(bridgeResult.finalUrl);
            record.setErrorMessage(schemaWarningSummary(parsed));
            record.setContextCharsDelta(prompt.length() + text(bridgeResult.answerText, "").length());
            analysisMapper.insert(record);
            predictionService.savePredictions(record.getId(), ref.symbol, ref.market, stockName, sourceTradeDate,
                    number(quote.get("price"), null), parsed.forecasts, marketData.toJson());
            updateSessionAfterRun(session, stockName, bridgeResult, record, null);
            return record;
        } catch (Exception e) {
            record.setStatus("ERROR");
            record.setStance("分析失败");
            record.setTone("red");
            record.setConfidence(0.0);
            record.setTitle(stockName + " 技术面分析失败");
            record.setConclusion(failureConclusion(e));
            record.setEvidenceJson("[]");
            record.setMissingDataJson(JSON.toJSONString(marketData.getRequiredMissing()));
            record.setMainForceIntentJson(intentValidation.intent == null ? null : intentValidation.intent.toJSONString());
            record.setMainForceIntentStatus(intentValidation.status);
            record.setMainForceIntentWarningsJson(JSON.toJSONString(intentValidation.warnings));
            record.setDataSnapshotJson(marketData.toJson());
            record.setRawResponse(bridgeResult == null ? "" : text(bridgeResult.answerText, ""));
            record.setBridgeTargetId(bridgeResult == null ? null : bridgeResult.targetId);
            record.setConversationUrl(bridgeResult == null ? null : bridgeResult.finalUrl);
            record.setErrorMessage(diagnosticMessage(e));
            record.setContextCharsDelta(prompt.length() + (bridgeResult == null ? 0 : text(bridgeResult.answerText, "").length()));
            analysisMapper.insert(record);
            updateSessionAfterRun(session, stockName, bridgeResult, record, diagnosticMessage(e));
            return record;
        }
    }

    private List<StockWatchTechnicalAnalysis> sourceRecordsForPeriod(StockRef ref, String periodType, PeriodWindow window) {
        if ("DAY".equals(periodType)) {
            return Collections.emptyList();
        }
        int limit = "MONTH".equals(periodType) ? 260 : 80;
        return analysisMapper.selectSuccessfulByDateRange(
                ref.symbol,
                ref.market,
                Date.valueOf(window.start),
                Date.valueOf(window.end),
                "DAY",
                limit
        );
    }

    private String sourceRecordIdsJson(List<StockWatchTechnicalAnalysis> records) {
        if (records == null || records.isEmpty()) {
            return "[]";
        }
        return JSON.toJSONString(records.stream()
                .map(StockWatchTechnicalAnalysis::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    private StockWatchTechnicalSession getOrCreateSession(StockRef ref,
                                                          String stockName,
                                                          boolean forceNewSession,
                                                          String periodType) {
        StockWatchTechnicalSession active = sessionMapper.selectActive(ref.symbol, ref.market, periodType);
        boolean shouldRotate = active != null && shouldRotate(active);
        if (active == null || forceNewSession || shouldRotate) {
            if (active != null) {
                sessionMapper.deactivateActive(
                        ref.symbol,
                        ref.market,
                        periodType,
                        forceNewSession ? "MANUAL_ROTATED" : "CONTEXT_ROTATED",
                        forceNewSession ? "手动要求新开会话" : "累计上下文接近阈值，自动新开会话"
                );
            }
            StockWatchTechnicalSession latest = sessionMapper.selectLatest(ref.symbol, ref.market, periodType);
            int generation = latest == null || latest.getGeneration() == null ? 1 : latest.getGeneration() + 1;
            StockWatchTechnicalSession created = new StockWatchTechnicalSession();
            created.setSymbol(ref.symbol);
            created.setMarket(ref.market);
            created.setStockName(stockName);
            created.setPeriodType(periodType);
            created.setSessionKey("stock-watch-" + periodType.toLowerCase(Locale.ROOT) + "-" + ref.market + "-" + ref.symbol + "-g" + generation);
            created.setStatus("ACTIVE");
            created.setGeneration(generation);
            created.setContextChars(0);
            created.setContextLimitChars(contextLimitChars);
            created.setLastRunStatus("new");
            sessionMapper.insert(created);
            return created;
        }
        return active;
    }

    private boolean shouldRotate(StockWatchTechnicalSession session) {
        int limit = session.getContextLimitChars() == null || session.getContextLimitChars() <= 0
                ? contextLimitChars
                : session.getContextLimitChars();
        int used = session.getContextChars() == null ? 0 : session.getContextChars();
        double ratio = contextRotateRatio <= 0 || contextRotateRatio > 1 ? 0.85 : contextRotateRatio;
        return used >= Math.round(limit * ratio);
    }

    private void updateSessionAfterRun(StockWatchTechnicalSession session,
                                       String stockName,
                                       BridgeResult bridgeResult,
                                       StockWatchTechnicalAnalysis record,
                                       String error) {
        int used = session.getContextChars() == null ? 0 : session.getContextChars();
        int delta = record.getContextCharsDelta() == null ? 0 : record.getContextCharsDelta();
        sessionMapper.updateAfterRun(
                session.getId(),
                bridgeResult != null ? bridgeResult.targetId : session.getBridgeTargetId(),
                bridgeResult != null ? bridgeResult.finalUrl : session.getConversationUrl(),
                used + Math.max(0, delta),
                new java.util.Date(),
                error == null ? "SUCCESS" : "ERROR",
                error
        );
    }

    private BridgeResult callDeepSeekBridge(String prompt,
                                            StockWatchTechnicalSession session,
                                            boolean forceNewSession) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", prompt);
        body.put("sessionKey", session.getSessionKey());
        body.put("forceNewSession", forceNewSession);
        body.put("closeTargetAfter", false);
        body.put("expectedJson", true);
        body.put("mode", "expert");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.max(5, connectTimeoutSeconds), TimeUnit.SECONDS)
                .readTimeout(Math.max(30, readTimeoutSeconds), TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(bridgeUrl)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException("bridge HTTP " + response.code() + " " + truncate(responseBody, 300));
            }
            JSONObject payload = JSON.parseObject(responseBody);
            if (payload == null || !payload.getBooleanValue("ok")) {
                throw new IllegalStateException("bridge 返回无效结果：" + truncate(responseBody, 300));
            }
            JSONObject result = payload.getJSONObject("result");
            if (result == null) {
                throw new IllegalStateException("bridge 未返回 result");
            }
            BridgeResult bridgeResult = new BridgeResult();
            bridgeResult.answerText = text(result.get("answerText"), "");
            bridgeResult.targetId = text(result.get("targetId"), "");
            bridgeResult.finalUrl = text(
                    result.get("finalUrl"),
                    text(result.get("conversationUrl"), text(result.get("url"), ""))
            );
            if (!StringUtils.hasText(bridgeResult.finalUrl) && StringUtils.hasText(bridgeResult.targetId)) {
                bridgeResult.finalUrl = lookupChromeTargetUrl(bridgeResult.targetId);
            }
            bridgeResult.contentStatus = text(result.get("contentStatus"), "");
            bridgeResult.completionReason = text(result.get("completionReason"), "");
            if ("incomplete".equalsIgnoreCase(bridgeResult.contentStatus)) {
                throw new IllegalStateException("DeepSeek 网页回答未完整生成，completionReason=" + bridgeResult.completionReason);
            }
            if (!StringUtils.hasText(bridgeResult.answerText)) {
                throw new IllegalStateException("DeepSeek 网页未返回分析正文，状态=" + bridgeResult.contentStatus);
            }
            return bridgeResult;
        }
    }

    private BridgeResult callDeepSeekBridgeWithRetry(String prompt,
                                                     StockWatchTechnicalSession session,
                                                     boolean forceNewSession,
                                                     String periodType) throws Exception {
        try {
            return callDeepSeekBridge(prompt, session, forceNewSession);
        } catch (Exception e) {
            String message = text(e.getMessage(), "").toLowerCase(Locale.ROOT);
            if (!message.contains("未完整生成") && !message.contains("incomplete_json")) {
                throw e;
            }
            log.warn("DeepSeek structured response incomplete; retrying in same session key={}", session.getSessionKey());
            return callDeepSeekBridge(structuredRetryPrompt(periodType, Collections.singletonList("response_incomplete_json")), session, false);
        }
    }

    private String structuredRetryPrompt(String periodType, List<String> schemaProblems) {
        String horizons = "WEEK".equals(periodType) ? "W1"
                : ("MONTH".equals(periodType) ? "M1" : "T1和T5");
        String problems = schemaProblems == null || schemaProblems.isEmpty()
                ? "未通过结构化校验"
                : String.join("；", schemaProblems);
        return "上一条结构化回答不完整。不要重新搜索，不要解释，只根据上一条任务输入重新输出一个完整、可解析的JSON对象；"
                + "必须包含stance、tone、confidence、title、conclusion、evidence、missingData、mainForceIntent、forecasts，forecasts必须包含" + horizons + "。"
                + "mainForceIntent必须包含intent/timeWindow/confidence/evidence/counterEvidence/assumptions/risk/watchPoints；"
                + "mainForceIntent.evidence和counterEvidence必须引用上一条输入中evidenceSignals.citationPaths里的完整路径。"
                + "本轮未通过项：" + problems + "。";
    }

    private boolean hasRequiredForecasts(JSONArray forecasts, String periodType) {
        if (forecasts == null || forecasts.isEmpty()) return false;
        List<String> required = "WEEK".equals(periodType) ? Collections.singletonList("W1")
                : ("MONTH".equals(periodType) ? Collections.singletonList("M1") : Arrays.asList("T1", "T5"));
        List<String> found = new ArrayList<>();
        for (int i = 0; i < forecasts.size(); i++) {
            JSONObject forecast = asJsonObject(forecasts.get(i));
            if (forecast == null) continue;
            String horizon = text(forecast.get("horizon"), "").toUpperCase(Locale.ROOT).replace("+", "");
            Double targetLow = number(forecast.get("targetLow"), null);
            Double targetHigh = number(forecast.get("targetHigh"), null);
            if (targetLow != null && targetHigh != null && targetLow > 0 && targetLow <= targetHigh) found.add(horizon);
        }
        return found.containsAll(required);
    }

    private String lookupChromeTargetUrl(String targetId) {
        if (!StringUtils.hasText(targetId) || debugPort <= 0) {
            return "";
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.max(2, Math.min(connectTimeoutSeconds, 5)), TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + debugPort + "/json")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful() || !StringUtils.hasText(body)) {
                return "";
            }
            JSONArray targets = JSON.parseArray(body);
            for (int i = 0; i < targets.size(); i++) {
                JSONObject item = targets.getJSONObject(i);
                if (item != null && targetId.equals(text(item.get("id"), ""))) {
                    return text(item.get("url"), "");
                }
            }
        } catch (Exception e) {
            log.debug("failed to lookup DeepSeek Chrome target url: {}", e.getMessage());
        }
        return "";
    }

    private String buildPrompt(StockRef ref,
                               String stockName,
                               EastmoneyMarketDataService.MarketDataBundle marketData,
                               Map<String, Object> evidenceSignals,
                               StockWatchTechnicalSession session,
                               String reason,
                               String periodType,
                               PeriodWindow window,
                               List<StockWatchTechnicalAnalysis> sourceRecords) {
        List<Map<String, Object>> recent = analysisMapper.selectRecentBySymbol(ref.symbol, ref.market, periodType, 2)
                .stream()
                .map(this::toPromptRecordPayload)
                .collect(Collectors.toList());

        JSONObject payload = new JSONObject(true);
        payload.put("task", "stock_watch_multi_period_technical_analysis");
        payload.put("reason", reason);
        payload.put("periodType", periodType);
        payload.put("periodWindow", window.toPayload());
        payload.put("stock", stockName + " " + ref.symbol + "." + ref.market);
        payload.put("timestamp", ZonedDateTime.now(CHINA_ZONE).toString());
        payload.put("marketData", toPromptMarketDataPayload(marketData.getPayload(), periodType));
        payload.put("evidenceSignals", evidenceSignals);
        payload.put("sourceDailyAnalyses", sourceRecords.stream()
                .map(this::toPromptSourceRecordPayload)
                .collect(Collectors.toList()));
        payload.put("recentSamePeriodAnalysis", recent);
        Map<String, Object> sessionPayload = new LinkedHashMap<>();
        sessionPayload.put("sessionKey", session.getSessionKey());
        sessionPayload.put("generation", session.getGeneration());
        sessionPayload.put("contextChars", session.getContextChars());
        sessionPayload.put("contextLimitChars", session.getContextLimitChars());
        payload.put("session", sessionPayload);

        String periodInstruction;
        if ("WEEK".equals(periodType)) {
            periodInstruction = "你是A股周度技术面复盘员。按固定自然周复盘，周结论默认在周五收盘后生成；不要把最近7天滚动窗口当成周线。";
        } else if ("MONTH".equals(periodType)) {
            periodInstruction = "你是A股月度技术面复盘员。按自然月/月末复盘，关注月内结构、资金方向和下月需要验证的关键条件。";
        } else {
            periodInstruction = "你是A股盘中技术面分析员。请基于我提供的行情快照和派生字段做30分钟级别技术面分析。";
        }

        String forecastInstruction = "DAY".equals(periodType)
                ? "forecasts必须包含T1和T5两项。"
                : ("WEEK".equals(periodType) ? "forecasts必须包含W1一项。" : "forecasts必须包含M1一项。");
        return String.join("\n",
                periodInstruction,
                "本轮行情、K线摘要、资金流、估值、财务摘要、公告、最近新闻、股东户数和本地技术指标都在marketData中；不得用会话历史覆盖本轮结构化数据。",
                "本轮股票分析以marketData为唯一事实源，不需要额外联网搜索；如网页专业模式自动检索，也只能作为辅助线索，禁止覆盖marketData事实。",
                "marketData.anchorFacts是程序校验后的事实锚点。涉及现价、涨跌幅、成交额、资金净额、资金占比和技术指标时必须原样引用anchorFacts，禁止自行换算单位或重算比例。",
                "marketData.latestNews是必须参考的最近新闻；marketData.shareholderCounts是股东户数变化线索。若相关字段为空，只能在missingData复制dataQuality.requiredMissing或optionalMissing里的对应项。",
                "marketData.dataPacking说明本轮为节省上下文已截取最近序列；完整原始行情快照由系统另存，不要抱怨未提供更早原始明细。",
                "evidenceSignals是程序从marketData派生的资金流、价量、位置和证据覆盖信号。你必须强制分析mainForceIntent，但只能解释这些信号，不能把模型猜测当成事实。",
                "mainForceIntent是“主力意图的模型推断”，不是事实标签；intent只能取ACCUMULATE/DISTRIBUTE/WASH/PULL_UP/SUPPORT/SUPPRESS/ROTATE/NEUTRAL/UNKNOWN。",
                "mainForceIntent字段固定为：intent(string), timeWindow(string: INTRADAY/THREE_DAY/FIVE_DAY/TWENTY_DAY), confidence(number 0-1), evidence(array string), counterEvidence(array string), assumptions(array string), risk(string), watchPoints(array string)。",
                "mainForceIntent.evidence和counterEvidence每条都必须引用evidenceSignals.citationPaths里的至少一个完整路径，例如evidenceSignals.fundFlowEvidence.anchorMainInflowYuan；证据不足时intent必须为NEUTRAL或UNKNOWN，confidence不得高于0.55。",
                "dataQuality.sourceStatus是采集器真实状态；missingData只能复制dataQuality.requiredMissing与optionalMissing，禁止自行臆测已提供字段仍然缺失。",
                "输出必须是一个JSON对象，不要Markdown，不要代码块，不要前后解释。",
                "JSON字段固定为：stance(string), tone(string: green/blue/amber/red), confidence(number 0-1), title(string), conclusion(string), evidence(array string), missingData(array string), mainForceIntent(object), forecasts(array)。",
                "每个forecast字段固定为：horizon(T1/T5/W1/M1), direction(UP/DOWN/SIDEWAYS), probability(0-1), targetLow, targetHigh, expectedReturnLow, expectedReturnHigh, invalidationPrice, rationale。",
                forecastInstruction,
                "direction定义：到期收益>=1%为UP，<=-1%为DOWN，其余为SIDEWAYS；targetLow不得大于targetHigh，价格与收益率必须和source price算术一致。",
                "结论要短，优先说明当前周期技术状态、下一周期需要验证的价量条件、风险边界。",
                "输入数据：",
                payload.toJSONString()
        );
    }

    private Map<String, Object> toPromptMarketDataPayload(Map<String, Object> raw, String periodType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return payload;
        }
        copyIfPresent(raw, payload, "asOf");
        copyIfPresent(raw, payload, "sourceTradeDate");
        copyIfPresent(raw, payload, "sourceTradeDateQuality");
        copyIfPresent(raw, payload, "symbol");
        copyIfPresent(raw, payload, "market");
        copyIfPresent(raw, payload, "periodType");
        copyIfPresent(raw, payload, "quote");
        copyIfPresent(raw, payload, "stockDetail");
        copyIfPresent(raw, payload, "anchorFacts");
        copyIfPresent(raw, payload, "technicalIndicators");
        copyIfPresent(raw, payload, "valuation");
        copyIfPresent(raw, payload, "marketBenchmark");

        String normalizedPeriod = normalizePeriodType(periodType);
        int dailyCount = "DAY".equals(normalizedPeriod) ? 20 : 50;
        int intradayCount = "DAY".equals(normalizedPeriod) ? 15 : 10;
        payload.put("dailyKline", tailObjects(raw.get("dailyKline"), dailyCount));
        if (raw.containsKey("periodKline")) {
            payload.put("periodKline", tailObjects(raw.get("periodKline"), 30));
        }
        payload.put("minuteTrends5d", tailObjects(raw.get("minuteTrends5d"), intradayCount));
        payload.put("intradayFundFlow", tailObjects(raw.get("intradayFundFlow"), intradayCount));
        payload.put("financials", headObjects(raw.get("financials"), 3));
        payload.put("announcements", headObjects(raw.get("announcements"), 5));
        payload.put("latestNews", compactNews(raw.get("latestNews"), 6));
        payload.put("shareholderCounts", headObjects(raw.get("shareholderCounts"), 3));
        copyIfPresent(raw, payload, "dataQuality");

        Map<String, Object> packing = new LinkedHashMap<>();
        packing.put("mode", "compact_for_deepseek_prompt");
        packing.put("fullSnapshotSavedBySystem", true);
        packing.put("dailyKlineTailCount", dailyCount);
        packing.put("minuteTrendsTailCount", intradayCount);
        packing.put("intradayFundFlowTailCount", intradayCount);
        packing.put("financialsLatestCount", 3);
        packing.put("announcementsLatestCount", 5);
        packing.put("latestNewsCount", 6);
        packing.put("shareholderCountsLatestCount", 3);
        payload.put("dataPacking", packing);
        return payload;
    }

    private Map<String, Object> buildEvidenceSignals(Map<String, Object> raw, String periodType) {
        Map<String, Object> signals = new LinkedHashMap<>();
        List<String> citationPaths = new ArrayList<>();
        Map<String, Object> fundFlow = buildFundFlowEvidence(raw, citationPaths);
        Map<String, Object> priceVolume = buildPriceVolumeSignal(raw, citationPaths);
        Map<String, Object> position = buildPositionSignal(raw, citationPaths);
        Map<String, Object> coverage = buildEvidenceCoverage(fundFlow, priceVolume, position);
        signals.put("schema", "stock_watch_evidence_signals_v1");
        signals.put("periodType", normalizePeriodType(periodType));
        signals.put("fundFlowEvidence", fundFlow);
        signals.put("priceVolumeSignal", priceVolume);
        signals.put("positionSignal", position);
        signals.put("evidenceCoverage", coverage);
        signals.put("citationPaths", citationPaths);
        signals.put("usagePolicy", "DeepSeek必须解释这些程序派生信号；不得把mainForceIntent当作事实标签或触发规则。");
        return signals;
    }

    private Map<String, Object> buildFundFlowEvidence(Map<String, Object> raw, List<String> citationPaths) {
        JSONObject quote = asJsonObject(raw == null ? null : raw.get("quote"));
        JSONObject anchorFacts = asJsonObject(raw == null ? null : raw.get("anchorFacts"));
        List<Object> intradayFlow = toObjectList(raw == null ? null : raw.get("intradayFundFlow"));
        JSONObject latestFlow = lastObject(intradayFlow);
        Map<String, Object> signal = new LinkedHashMap<>();
        List<String> limitations = new ArrayList<>();
        Double anchorMain = number(anchorFacts == null ? null : anchorFacts.get("mainInflowYuan"), null);
        Double anchorRatio = number(anchorFacts == null ? null : anchorFacts.get("mainInflowRatioPercent"), null);
        Double amount = number(anchorFacts == null ? null : anchorFacts.get("amountYuan"), number(quote == null ? null : quote.get("amount"), null));
        Double latestMain = number(latestFlow == null ? null : latestFlow.get("main"), null);
        Double latestSuperLarge = number(latestFlow == null ? null : latestFlow.get("superLarge"), null);
        Double latestLarge = number(latestFlow == null ? null : latestFlow.get("large"), null);
        Double latestSmall = number(latestFlow == null ? null : latestFlow.get("small"), null);
        Double tailMainSum = sumTail(intradayFlow, "main", Math.min(15, intradayFlow.size()));
        Double tailSuperLargeSum = sumTail(intradayFlow, "superLarge", Math.min(15, intradayFlow.size()));
        Double tailLargeSum = sumTail(intradayFlow, "large", Math.min(15, intradayFlow.size()));
        Double selectedMain = firstNumber(anchorMain, latestMain, tailMainSum);
        String direction = fundFlowDirection(selectedMain, latestSuperLarge, latestLarge, latestSmall);
        String strength = fundFlowStrength(selectedMain, anchorRatio, amount);
        String pattern = fundFlowPattern(direction, strength, latestSuperLarge, latestLarge, latestSmall);
        putSignal(signal, "fundFlowEvidence", "direction", direction, citationPaths);
        putSignal(signal, "fundFlowEvidence", "strength", strength, citationPaths);
        putSignal(signal, "fundFlowEvidence", "pattern", pattern, citationPaths);
        putSignal(signal, "fundFlowEvidence", "anchorMainInflowYuan", roundNullable(anchorMain), citationPaths);
        putSignal(signal, "fundFlowEvidence", "anchorMainInflowRatioPercent", roundNullable(anchorRatio), citationPaths);
        putSignal(signal, "fundFlowEvidence", "amountYuan", roundNullable(amount), citationPaths);
        putSignal(signal, "fundFlowEvidence", "latestMainInflowYuan", roundNullable(latestMain), citationPaths);
        putSignal(signal, "fundFlowEvidence", "latestSuperLargeInflowYuan", roundNullable(latestSuperLarge), citationPaths);
        putSignal(signal, "fundFlowEvidence", "latestLargeInflowYuan", roundNullable(latestLarge), citationPaths);
        putSignal(signal, "fundFlowEvidence", "latestSmallInflowYuan", roundNullable(latestSmall), citationPaths);
        putSignal(signal, "fundFlowEvidence", "tail15MainSumYuan", roundNullable(tailMainSum), citationPaths);
        putSignal(signal, "fundFlowEvidence", "tail15SuperLargeSumYuan", roundNullable(tailSuperLargeSum), citationPaths);
        putSignal(signal, "fundFlowEvidence", "tail15LargeSumYuan", roundNullable(tailLargeSum), citationPaths);
        signal.put("window", "intraday");
        if (anchorMain == null && latestMain == null && tailMainSum == null) {
            limitations.add("资金流主力净额不可用，不能支撑强主力意图。");
        }
        if (intradayFlow.isEmpty()) {
            limitations.add("intradayFundFlow为空，无法观察盘中资金流形态。");
        }
        signal.put("limitations", limitations);
        return signal;
    }

    private Map<String, Object> buildPriceVolumeSignal(Map<String, Object> raw, List<String> citationPaths) {
        JSONObject quote = asJsonObject(raw == null ? null : raw.get("quote"));
        JSONObject anchorFacts = asJsonObject(raw == null ? null : raw.get("anchorFacts"));
        Map<String, Object> signal = new LinkedHashMap<>();
        List<String> limitations = new ArrayList<>();
        Double price = number(anchorFacts == null ? null : anchorFacts.get("priceYuan"), number(quote == null ? null : quote.get("price"), null));
        Double changePct = number(anchorFacts == null ? null : anchorFacts.get("changePercent"), number(quote == null ? null : quote.get("changePercent"), null));
        Double amount = number(anchorFacts == null ? null : anchorFacts.get("amountYuan"), number(quote == null ? null : quote.get("amount"), null));
        Double turnover = number(anchorFacts == null ? null : anchorFacts.get("turnoverPercent"), number(quote == null ? null : quote.get("turnover"), null));
        Double volumeRatio = number(anchorFacts == null ? null : anchorFacts.get("volumeRatio"), number(quote == null ? null : quote.get("volumeRatio"), null));
        Double high = number(quote == null ? null : quote.get("high"), null);
        Double low = number(quote == null ? null : quote.get("low"), null);
        Double dayRangePositionPct = rangePosition(price, low, high);
        String priceDirection = priceDirection(changePct);
        String activity = activityLevel(changePct, volumeRatio, turnover);
        putSignal(signal, "priceVolumeSignal", "priceDirection", priceDirection, citationPaths);
        putSignal(signal, "priceVolumeSignal", "activity", activity, citationPaths);
        putSignal(signal, "priceVolumeSignal", "priceYuan", roundNullable(price), citationPaths);
        putSignal(signal, "priceVolumeSignal", "changePercent", roundNullable(changePct), citationPaths);
        putSignal(signal, "priceVolumeSignal", "amountYuan", roundNullable(amount), citationPaths);
        putSignal(signal, "priceVolumeSignal", "turnoverPercent", roundNullable(turnover), citationPaths);
        putSignal(signal, "priceVolumeSignal", "volumeRatio", roundNullable(volumeRatio), citationPaths);
        putSignal(signal, "priceVolumeSignal", "dayRangePositionPct", roundNullable(dayRangePositionPct), citationPaths);
        if (price == null || changePct == null) {
            limitations.add("实时价或涨跌幅不可用，价量信号不完整。");
        }
        if (volumeRatio == null && turnover == null && amount == null) {
            limitations.add("成交额、换手率和量比均不可用，不能判断量能。");
        }
        signal.put("limitations", limitations);
        return signal;
    }

    private Map<String, Object> buildPositionSignal(Map<String, Object> raw, List<String> citationPaths) {
        JSONObject indicators = asJsonObject(raw == null ? null : raw.get("technicalIndicators"));
        List<Object> dailyBars = toObjectList(raw == null ? null : raw.get("dailyKline"));
        Map<String, Object> signal = new LinkedHashMap<>();
        List<String> limitations = new ArrayList<>();
        JSONObject latestBar = lastObject(dailyBars);
        Double close = number(latestBar == null ? null : latestBar.get("close"), null);
        Double high20 = maxTail(dailyBars, "high", Math.min(20, dailyBars.size()));
        Double low20 = minTail(dailyBars, "low", Math.min(20, dailyBars.size()));
        Double return5Pct = returnFromTail(dailyBars, 5);
        Double return20Pct = returnFromTail(dailyBars, 20);
        Double ma5 = number(indicators == null ? null : indicators.get("ma5"), null);
        Double ma20 = number(indicators == null ? null : indicators.get("ma20"), null);
        Double position20 = rangePosition(close, low20, high20);
        String location = positionLocation(position20);
        String trend = positionTrend(return5Pct, return20Pct, close, ma5, ma20);
        putSignal(signal, "positionSignal", "location", location, citationPaths);
        putSignal(signal, "positionSignal", "trend", trend, citationPaths);
        putSignal(signal, "positionSignal", "closeYuan", roundNullable(close), citationPaths);
        putSignal(signal, "positionSignal", "high20Yuan", roundNullable(high20), citationPaths);
        putSignal(signal, "positionSignal", "low20Yuan", roundNullable(low20), citationPaths);
        putSignal(signal, "positionSignal", "closePosition20dPct", roundNullable(position20), citationPaths);
        putSignal(signal, "positionSignal", "return5Pct", roundNullable(return5Pct), citationPaths);
        putSignal(signal, "positionSignal", "return20Pct", roundNullable(return20Pct), citationPaths);
        putSignal(signal, "positionSignal", "ma5", roundNullable(ma5), citationPaths);
        putSignal(signal, "positionSignal", "ma20", roundNullable(ma20), citationPaths);
        if (dailyBars.size() < 20) {
            limitations.add("dailyKline不足20根，位置和趋势只能低置信参考。");
        }
        if (close == null) {
            limitations.add("最新日K收盘价不可用。");
        }
        signal.put("limitations", limitations);
        return signal;
    }

    private Map<String, Object> buildEvidenceCoverage(Map<String, Object> fundFlow,
                                                      Map<String, Object> priceVolume,
                                                      Map<String, Object> position) {
        boolean hasFundFlow = hasAnyValue(fundFlow, "anchorMainInflowYuan", "latestMainInflowYuan", "tail15MainSumYuan");
        boolean hasPriceVolume = hasAnyValue(priceVolume, "priceYuan", "changePercent")
                && hasAnyValue(priceVolume, "amountYuan", "turnoverPercent", "volumeRatio");
        boolean hasPosition = hasAnyValue(position, "closePosition20dPct", "return5Pct", "return20Pct");
        int score = (hasFundFlow ? 1 : 0) + (hasPriceVolume ? 1 : 0) + (hasPosition ? 1 : 0);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("fundFlow", hasFundFlow);
        coverage.put("priceVolume", hasPriceVolume);
        coverage.put("position", hasPosition);
        coverage.put("score", score);
        coverage.put("maxScore", 3);
        coverage.put("strongIntentAllowed", hasFundFlow && hasPriceVolume);
        coverage.put("highConfidenceAllowed", hasFundFlow && hasPriceVolume && hasPosition);
        return coverage;
    }

    private void putSignal(Map<String, Object> target,
                           String section,
                           String key,
                           Object value,
                           List<String> citationPaths) {
        if (value == null) {
            return;
        }
        target.put(key, value);
        citationPaths.add("evidenceSignals." + section + "." + key);
    }

    private JSONObject lastObject(List<Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return asJsonObject(values.get(values.size() - 1));
    }

    private Double firstNumber(Double... values) {
        if (values == null) {
            return null;
        }
        for (Double value : values) {
            if (value != null && Double.isFinite(value)) {
                return value;
            }
        }
        return null;
    }

    private Double sumTail(List<Object> rows, String key, int count) {
        if (rows == null || rows.isEmpty() || count <= 0) {
            return null;
        }
        int start = Math.max(0, rows.size() - count);
        double sum = 0;
        boolean found = false;
        for (int i = start; i < rows.size(); i++) {
            JSONObject row = asJsonObject(rows.get(i));
            Double value = number(row == null ? null : row.get(key), null);
            if (value != null && Double.isFinite(value)) {
                sum += value;
                found = true;
            }
        }
        return found ? sum : null;
    }

    private Double maxTail(List<Object> rows, String key, int count) {
        Double value = null;
        if (rows == null || rows.isEmpty() || count <= 0) {
            return null;
        }
        int start = Math.max(0, rows.size() - count);
        for (int i = start; i < rows.size(); i++) {
            JSONObject row = asJsonObject(rows.get(i));
            Double next = number(row == null ? null : row.get(key), null);
            if (next != null && Double.isFinite(next)) {
                value = value == null ? next : Math.max(value, next);
            }
        }
        return value;
    }

    private Double minTail(List<Object> rows, String key, int count) {
        Double value = null;
        if (rows == null || rows.isEmpty() || count <= 0) {
            return null;
        }
        int start = Math.max(0, rows.size() - count);
        for (int i = start; i < rows.size(); i++) {
            JSONObject row = asJsonObject(rows.get(i));
            Double next = number(row == null ? null : row.get(key), null);
            if (next != null && Double.isFinite(next)) {
                value = value == null ? next : Math.min(value, next);
            }
        }
        return value;
    }

    private Double returnFromTail(List<Object> rows, int periods) {
        if (rows == null || rows.size() <= periods) {
            return null;
        }
        JSONObject latest = asJsonObject(rows.get(rows.size() - 1));
        JSONObject base = asJsonObject(rows.get(rows.size() - 1 - periods));
        Double latestClose = number(latest == null ? null : latest.get("close"), null);
        Double baseClose = number(base == null ? null : base.get("close"), null);
        if (latestClose == null || baseClose == null || baseClose == 0) {
            return null;
        }
        return (latestClose / baseClose - 1.0) * 100.0;
    }

    private Double rangePosition(Double value, Double low, Double high) {
        if (value == null || low == null || high == null || high <= low) {
            return null;
        }
        return Math.max(0.0, Math.min(100.0, (value - low) * 100.0 / (high - low)));
    }

    private String fundFlowDirection(Double main, Double superLarge, Double large, Double small) {
        if (main == null || Math.abs(main) < 1.0) {
            return "UNKNOWN";
        }
        double institutional = safe(superLarge) + safe(large);
        if (main > 0 && institutional >= 0) {
            return "NET_INFLOW";
        }
        if (main < 0 && institutional <= 0) {
            return "NET_OUTFLOW";
        }
        if (main > 0 && small != null && small < 0) {
            return "NET_INFLOW";
        }
        if (main < 0 && small != null && small > 0) {
            return "NET_OUTFLOW";
        }
        return "MIXED";
    }

    private String fundFlowStrength(Double main, Double ratioPct, Double amount) {
        if (main == null) {
            return "UNKNOWN";
        }
        double abs = Math.abs(main);
        double ratio = ratioPct != null ? Math.abs(ratioPct)
                : (amount == null || amount == 0 ? 0 : Math.abs(main / amount * 100.0));
        if (abs >= 100_000_000.0 || ratio >= 5.0) {
            return "STRONG";
        }
        if (abs >= 30_000_000.0 || ratio >= 2.0) {
            return "MEDIUM";
        }
        return "WEAK";
    }

    private String fundFlowPattern(String direction, String strength, Double superLarge, Double large, Double small) {
        if ("UNKNOWN".equals(direction)) {
            return "UNKNOWN";
        }
        double big = safe(superLarge) + safe(large);
        if ("NET_INFLOW".equals(direction) && big > 0 && "STRONG".equals(strength)) {
            return "ACCUMULATION_LIKE";
        }
        if ("NET_OUTFLOW".equals(direction) && big < 0 && "STRONG".equals(strength)) {
            return "DISTRIBUTION_LIKE";
        }
        if ("NET_INFLOW".equals(direction) && small != null && small < 0) {
            return "PULL_UP_LIKE";
        }
        if ("NET_OUTFLOW".equals(direction) && small != null && small > 0) {
            return "WASH_LIKE";
        }
        return "NEUTRAL";
    }

    private String priceDirection(Double changePct) {
        if (changePct == null) {
            return "UNKNOWN";
        }
        if (changePct >= 1.0) {
            return "UP";
        }
        if (changePct <= -1.0) {
            return "DOWN";
        }
        return "SIDEWAYS";
    }

    private String activityLevel(Double changePct, Double volumeRatio, Double turnover) {
        double absChange = changePct == null ? 0.0 : Math.abs(changePct);
        double ratio = volumeRatio == null ? 0.0 : volumeRatio;
        double turn = turnover == null ? 0.0 : turnover;
        if (absChange >= 5.0 || ratio >= 2.0 || turn >= 8.0) {
            return "HIGH";
        }
        if (absChange >= 2.0 || ratio >= 1.2 || turn >= 3.0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String positionLocation(Double positionPct) {
        if (positionPct == null) {
            return "UNKNOWN";
        }
        if (positionPct >= 75.0) {
            return "NEAR_HIGH";
        }
        if (positionPct <= 25.0) {
            return "NEAR_LOW";
        }
        return "MID_RANGE";
    }

    private String positionTrend(Double return5Pct, Double return20Pct, Double close, Double ma5, Double ma20) {
        if (return5Pct != null && return20Pct != null) {
            if (return5Pct > 3.0 && return20Pct > 5.0) {
                return "UP";
            }
            if (return5Pct < -3.0 && return20Pct < -5.0) {
                return "DOWN";
            }
        }
        if (close != null && ma5 != null && ma20 != null) {
            if (close > ma5 && ma5 > ma20) {
                return "UP";
            }
            if (close < ma5 && ma5 < ma20) {
                return "DOWN";
            }
            return "SIDEWAYS";
        }
        return "UNKNOWN";
    }

    private boolean hasAnyValue(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (source.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    private double safe(Double value) {
        return value == null || !Double.isFinite(value) ? 0.0 : value;
    }

    private Double roundNullable(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private List<Object> tailObjects(Object raw, int count) {
        List<Object> values = toObjectList(raw);
        if (values.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(values.subList(Math.max(0, values.size() - count), values.size()));
    }

    private List<Object> headObjects(Object raw, int count) {
        List<Object> values = toObjectList(raw);
        if (values.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(values.subList(0, Math.min(count, values.size())));
    }

    private List<Object> compactNews(Object raw, int count) {
        List<Object> values = headObjects(raw, count);
        List<Object> compact = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map)) {
                compact.add(value);
                continue;
            }
            Map<?, ?> item = (Map<?, ?>) value;
            Map<String, Object> news = new LinkedHashMap<>();
            news.put("title", item.get("title"));
            news.put("showTime", item.get("showTime"));
            news.put("source", item.get("source"));
            news.put("summary", truncate(text(item.get("summary"), ""), 180));
            news.put("url", item.get("url"));
            compact.add(news);
        }
        return compact;
    }

    private List<Object> toObjectList(Object raw) {
        if (raw instanceof List) {
            return new ArrayList<>((List<?>) raw);
        }
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                values.add(array.get(i));
            }
            return values;
        }
        return Collections.emptyList();
    }

    private AnalysisPayload parseAnalysisPayload(String raw, String stockName) {
        JSONObject parsed = extractJsonObject(raw);
        if (parsed == null) {
            AnalysisPayload fallback = new AnalysisPayload();
            fallback.stance = "待复核";
            fallback.tone = "amber";
            fallback.confidence = 0.5;
            fallback.title = stockName + " 技术面分析";
            fallback.conclusion = truncate(text(raw, "DeepSeek 网页返回内容不可解析。"), 1200);
            fallback.evidence = Collections.singletonList("raw_response_unparsed");
            fallback.missingData = Collections.singletonList("model_response_invalid_json");
            fallback.forecasts = new JSONArray();
            return fallback;
        }
        AnalysisPayload payload = new AnalysisPayload();
        payload.stance = text(parsed.get("stance"), "待复核");
        payload.tone = normalizeTone(text(parsed.get("tone"), "amber"));
        payload.confidence = normalizeConfidence(parsed.get("confidence"));
        payload.title = text(parsed.get("title"), stockName + " 技术面分析");
        payload.conclusion = text(parsed.get("conclusion"), "");
        payload.evidence = toStringListField(parsed.get("evidence"), "evidence", payload.schemaWarnings);
        payload.missingData = toStringListField(parsed.get("missingData"), "missingData", payload.schemaWarnings);
        payload.mainForceIntent = asJsonObject(parsed.get("mainForceIntent"));
        if (parsed.containsKey("mainForceIntent") && payload.mainForceIntent == null) {
            payload.schemaWarnings.add("mainForceIntent expected JSONObject but got " + jsonTypeName(parsed.get("mainForceIntent")));
        }
        payload.forecasts = toForecastArray(parsed.get("forecasts"), payload.schemaWarnings);
        if (payload.evidence.isEmpty()) {
            payload.evidence.add("DeepSeek 网页技术面分析");
        }
        return payload;
    }

    private IntentValidationResult validateMainForceIntent(JSONObject raw, Map<String, Object> evidenceSignals) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JSONObject normalized = new JSONObject(true);
        if (raw == null || raw.isEmpty()) {
            problems.add("mainForceIntent_missing");
            return IntentValidationResult.invalid(normalized, problems, warnings);
        }
        String intent = normalizeEnum(text(raw.get("intent"), ""), MAIN_FORCE_INTENTS, "UNKNOWN");
        String rawIntent = text(raw.get("intent"), "");
        if (!MAIN_FORCE_INTENTS.contains(rawIntent.toUpperCase(Locale.ROOT))) {
            problems.add("mainForceIntent.intent_invalid:" + rawIntent);
        }
        String timeWindow = normalizeEnum(text(raw.get("timeWindow"), ""), MAIN_FORCE_WINDOWS, "INTRADAY");
        String rawWindow = text(raw.get("timeWindow"), "");
        if (!MAIN_FORCE_WINDOWS.contains(rawWindow.toUpperCase(Locale.ROOT))) {
            problems.add("mainForceIntent.timeWindow_invalid:" + rawWindow);
        }
        boolean confidencePresent = raw.containsKey("confidence");
        double confidence = normalizeConfidence(raw.get("confidence"));
        if (!confidencePresent) {
            problems.add("mainForceIntent.confidence_missing");
        }
        List<String> evidence = strictStringList(raw, "evidence", true, problems);
        List<String> counterEvidence = strictStringList(raw, "counterEvidence", false, problems);
        List<String> assumptions = strictStringList(raw, "assumptions", false, problems);
        List<String> watchPoints = strictStringList(raw, "watchPoints", false, problems);
        String risk = text(raw.get("risk"), "");
        if (!raw.containsKey("risk")) {
            problems.add("mainForceIntent.risk_missing");
        }

        Set<String> allowedPaths = citationPathSet(evidenceSignals);
        Set<String> citedPaths = validEvidencePaths(evidence, allowedPaths, warnings, problems, "evidence");
        citedPaths.addAll(validEvidencePaths(counterEvidence, allowedPaths, warnings, problems, "counterEvidence"));
        boolean strongIntent = STRONG_MAIN_FORCE_INTENTS.contains(intent);
        boolean strongAllowed = coverageFlag(evidenceSignals, "strongIntentAllowed");
        boolean highConfidenceAllowed = coverageFlag(evidenceSignals, "highConfidenceAllowed");
        if (strongIntent && !strongAllowed) {
            problems.add("strong_intent_requires_fund_flow_and_price_volume_coverage");
        }
        if (strongIntent && citedPaths.isEmpty()) {
            problems.add("strong_intent_requires_valid_evidence_path");
        }
        if (confidence > 0.55 && !highConfidenceAllowed) {
            problems.add("confidence_above_0.55_requires_fund_flow_price_volume_position_coverage");
        }
        if ((evidence.isEmpty() || citedPaths.isEmpty()) && ("NEUTRAL".equals(intent) || "UNKNOWN".equals(intent)) && confidence > 0.55) {
            problems.add("neutral_or_unknown_without_valid_evidence_confidence_must_not_exceed_0.55");
        }

        normalized.put("intent", intent);
        normalized.put("timeWindow", timeWindow);
        normalized.put("confidence", roundNullable(confidence));
        normalized.put("evidence", toJsonArray(evidence));
        normalized.put("counterEvidence", toJsonArray(counterEvidence));
        normalized.put("assumptions", toJsonArray(assumptions));
        normalized.put("risk", risk);
        normalized.put("watchPoints", toJsonArray(watchPoints));
        normalized.put("validatedEvidencePaths", toJsonArray(new ArrayList<>(citedPaths)));
        normalized.put("disclaimer", "模型推断，非事实标签，不参与异动触发。");
        if (!problems.isEmpty()) {
            return IntentValidationResult.invalid(normalized, problems, warnings);
        }
        return IntentValidationResult.valid(normalized, warnings);
    }

    private List<String> strictStringList(JSONObject object, String field, boolean requireNonEmpty, List<String> problems) {
        if (object == null || !object.containsKey(field)) {
            problems.add("mainForceIntent." + field + "_missing");
            return new ArrayList<>();
        }
        Object raw = object.get(field);
        List<String> values = new ArrayList<>();
        if (raw instanceof JSONArray) {
            values = toStringList((JSONArray) raw);
        } else if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                String value = text(item, "");
                if (StringUtils.hasText(value)) {
                    values.add(value);
                }
            }
        } else {
            problems.add("mainForceIntent." + field + "_must_be_array");
        }
        if (requireNonEmpty && values.isEmpty()) {
            problems.add("mainForceIntent." + field + "_empty");
        }
        return values;
    }

    private String normalizeEnum(String raw, List<String> allowed, String fallback) {
        String value = text(raw, "").trim().toUpperCase(Locale.ROOT);
        return allowed.contains(value) ? value : fallback;
    }

    private Set<String> citationPathSet(Map<String, Object> evidenceSignals) {
        Set<String> paths = new LinkedHashSet<>();
        if (evidenceSignals == null) {
            return paths;
        }
        for (Object value : toObjectList(evidenceSignals.get("citationPaths"))) {
            String path = text(value, "");
            if (StringUtils.hasText(path)) {
                paths.add(path);
            }
        }
        return paths;
    }

    private Set<String> validEvidencePaths(List<String> evidence,
                                           Set<String> allowedPaths,
                                           List<String> warnings,
                                           List<String> problems,
                                           String fieldName) {
        Set<String> valid = new LinkedHashSet<>();
        if (evidence == null || evidence.isEmpty()) {
            return valid;
        }
        int index = 0;
        for (String item : evidence) {
            java.util.regex.Matcher matcher = EVIDENCE_PATH_PATTERN.matcher(item);
            boolean foundPath = false;
            boolean foundValid = false;
            while (matcher.find()) {
                foundPath = true;
                String path = matcher.group();
                if (allowedPaths.contains(path)) {
                    valid.add(path);
                    foundValid = true;
                }
            }
            if (!foundPath) {
                String message = "mainForceIntent." + fieldName + "[" + index + "] has no parseable path: " + truncate(item, 80);
                warnings.add(message);
                problems.add("mainForceIntent." + fieldName + "[" + index + "]_missing_valid_evidence_path");
            } else if (!foundValid) {
                String message = "mainForceIntent." + fieldName + "[" + index + "] path not in citationPaths: " + truncate(item, 80);
                warnings.add(message);
                problems.add("mainForceIntent." + fieldName + "[" + index + "]_invalid_evidence_path");
            }
            index++;
        }
        return valid;
    }

    private boolean coverageFlag(Map<String, Object> evidenceSignals, String key) {
        JSONObject coverage = asJsonObject(evidenceSignals == null ? null : evidenceSignals.get("evidenceCoverage"));
        Object value = coverage == null ? null : coverage.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private JSONArray toJsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            array.addAll(values);
        }
        return array;
    }

    private JSONObject extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        JSONObject direct = parseObject(text);
        if (direct != null) {
            return direct;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return parseObject(text.substring(start, end + 1));
        }
        return null;
    }

    private JSONObject parseObject(String raw) {
        try {
            return JSON.parseObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> toStringListField(Object raw, String fieldName, List<String> schemaWarnings) {
        if (raw == null) {
            return new ArrayList<>();
        }
        if (raw instanceof JSONArray) {
            return toStringList((JSONArray) raw);
        }
        if (schemaWarnings != null) {
            schemaWarnings.add(fieldName + " expected JSONArray but got " + jsonTypeName(raw));
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                String value = text(item, "");
                if (StringUtils.hasText(value)) {
                    values.add(value);
                }
            }
            return values;
        }
        String value = raw instanceof JSONObject || raw instanceof Map ? JSON.toJSONString(raw) : text(raw, "");
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
        return values;
    }

    private JSONArray toForecastArray(Object raw, List<String> schemaWarnings) {
        JSONArray result = new JSONArray();
        if (raw == null) {
            return result;
        }
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.size(); i++) {
                addForecastItem(result, array.get(i), "", schemaWarnings);
            }
            return result;
        }
        if (schemaWarnings != null) {
            schemaWarnings.add("forecasts expected JSONArray but got " + jsonTypeName(raw));
        }
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            if (looksLikeForecast(object)) {
                addForecastItem(result, object, "", schemaWarnings);
                return result;
            }
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                addForecastItem(result, entry.getValue(), normalizeForecastHorizon(entry.getKey()), schemaWarnings);
            }
            return result;
        }
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                addForecastItem(result, item, "", schemaWarnings);
            }
            return result;
        }
        addForecastItem(result, raw, "", schemaWarnings);
        return result;
    }

    private void addForecastItem(JSONArray result, Object raw, String fallbackHorizon, List<String> schemaWarnings) {
        if (raw == null) {
            return;
        }
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.size(); i++) {
                addForecastItem(result, array.get(i), fallbackHorizon, schemaWarnings);
            }
            return;
        }
        JSONObject item = asJsonObject(raw);
        if (item == null) {
            if (schemaWarnings != null) {
                schemaWarnings.add("forecasts item ignored: " + jsonTypeName(raw));
            }
            return;
        }
        if (!StringUtils.hasText(text(item.get("horizon"), "")) && StringUtils.hasText(fallbackHorizon)) {
            item.put("horizon", fallbackHorizon);
        }
        result.add(item);
    }

    private JSONObject asJsonObject(Object raw) {
        if (raw instanceof JSONObject) {
            return (JSONObject) raw;
        }
        if (raw instanceof Map) {
            JSONObject object = new JSONObject(true);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                object.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return object;
        }
        if (raw instanceof String) {
            return parseObject((String) raw);
        }
        return null;
    }

    private boolean looksLikeForecast(JSONObject object) {
        return object.containsKey("horizon")
                || object.containsKey("direction")
                || object.containsKey("probability")
                || object.containsKey("targetLow")
                || object.containsKey("targetHigh");
    }

    private String normalizeForecastHorizon(String raw) {
        return text(raw, "").toUpperCase(Locale.ROOT).replace("+", "").replaceAll("\\s+", "");
    }

    private String jsonTypeName(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof JSONArray) {
            return "JSONArray";
        }
        if (raw instanceof JSONObject) {
            return "JSONObject";
        }
        return raw.getClass().getSimpleName();
    }

    private String schemaWarningSummary(AnalysisPayload payload) {
        if (payload == null || payload.schemaWarnings == null || payload.schemaWarnings.isEmpty()) {
            return null;
        }
        return "DeepSeek JSON schema coerced: " + String.join("; ", payload.schemaWarnings);
    }

    private String schemaWarningSuffix(AnalysisPayload payload) {
        String summary = schemaWarningSummary(payload);
        return StringUtils.hasText(summary) ? "；" + summary : "";
    }

    private String failureConclusion(Exception e) {
        String message = diagnosticMessage(e);
        if (e instanceof ModelResponseSchemaException) {
            return "DeepSeek 网页返回结构解析失败：" + message;
        }
        return "DeepSeek 网页 provider 调用失败：" + message;
    }

    private String diagnosticMessage(Exception e) {
        if (e == null) {
            return "unknown_error";
        }
        return text(e.getMessage(), e.getClass().getName());
    }

    private Map<String, Object> toSessionPayload(StockWatchTechnicalSession session, StockRef fallbackRef) {
        return toSessionPayload(session, fallbackRef, session == null ? "DAY" : session.getPeriodType());
    }

    private Map<String, Object> toSessionPayload(StockWatchTechnicalSession session, StockRef fallbackRef, String periodType) {
        String normalizedPeriodType = normalizePeriodType(periodType);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (session == null) {
            payload.put("title", fallbackRef.symbol + " · " + fallbackRef.market);
            payload.put("periodType", normalizedPeriodType);
            payload.put("sessionId", "计划绑定 stock-watch-" + normalizedPeriodType.toLowerCase(Locale.ROOT) + "-" + fallbackRef.market + "-" + fallbackRef.symbol);
            payload.put("status", "待创建");
            payload.put("contextUsage", 0);
            payload.put("rotatePolicy", "超过 " + Math.round(contextRotateRatio * 100) + "% 自动新开");
            payload.put("traceable", false);
            payload.put("url", "");
            payload.put("lastRun", "--");
            payload.put("nextRun", nextRunLabel(ZonedDateTime.now(CHINA_ZONE)));
            return payload;
        }
        int limit = session.getContextLimitChars() == null || session.getContextLimitChars() <= 0
                ? contextLimitChars
                : session.getContextLimitChars();
        int used = session.getContextChars() == null ? 0 : session.getContextChars();
        String url = text(session.getConversationUrl(), "");
        payload.put("id", session.getId());
        payload.put("title", text(session.getStockName(), session.getSymbol()) + " · " + session.getSymbol());
        payload.put("periodType", firstNonBlank(session.getPeriodType(), normalizedPeriodType));
        payload.put("sessionId", session.getSessionKey());
        payload.put("status", sessionStatusLabel(session));
        payload.put("contextUsage", limit <= 0 ? 0 : Math.min(100, Math.round((used * 100.0f) / limit)));
        payload.put("rotatePolicy", "超过 " + Math.round(contextRotateRatio * 100) + "% 自动新开");
        payload.put("traceable", isTraceableUrl(url));
        payload.put("url", url);
        payload.put("bridgeTargetId", session.getBridgeTargetId());
        payload.put("lastRun", session.getLastRunAt() == null ? "--" : SLOT_FORMATTER.format(session.getLastRunAt().toInstant().atZone(CHINA_ZONE)));
        payload.put("nextRun", nextRunLabel(ZonedDateTime.now(CHINA_ZONE)));
        payload.put("lastRunStatus", session.getLastRunStatus());
        payload.put("lastError", session.getLastError());
        return payload;
    }

    private Map<String, Object> toRecordPayload(StockWatchTechnicalAnalysis record) {
        Map<String, Object> payload = toCompactRecordPayload(record);
        payload.put("rawResponse", record.getRawResponse());
        payload.put("errorMessage", record.getErrorMessage());
        payload.put("conversationUrl", record.getConversationUrl());
        payload.put("bridgeTargetId", record.getBridgeTargetId());
        payload.put("traceable", isTraceableUrl(record.getConversationUrl()));
        payload.put("evidenceSignals", parseObject(record.getEvidenceSignalJson()));
        return payload;
    }

    private Map<String, Object> toCompactRecordPayload(StockWatchTechnicalAnalysis record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", record.getId());
        payload.put("date", toLocalDate(record.getAnalysisDate()).toString());
        payload.put("time", record.getRunSlot());
        payload.put("periodType", firstNonBlank(record.getPeriodType(), "DAY"));
        payload.put("periodKey", firstNonBlank(record.getPeriodKey(), periodKey(firstNonBlank(record.getPeriodType(), "DAY"), toLocalDate(record.getAnalysisDate()))));
        payload.put("sourceRecordIds", parseEvidence(record.getSourceRecordIds()));
        payload.put("provider", "DeepSeek 网页");
        payload.put("status", record.getStatus());
        payload.put("stance", record.getStance());
        payload.put("tone", record.getTone());
        payload.put("confidence", formatConfidence(record.getConfidence()));
        payload.put("title", record.getTitle());
        payload.put("conclusion", record.getConclusion());
        payload.put("evidence", parseEvidence(record.getEvidenceJson()));
        payload.put("missingData", parseEvidence(record.getMissingDataJson()));
        payload.put("mainForceIntent", parseObject(record.getMainForceIntentJson()));
        payload.put("mainForceIntentStatus", record.getMainForceIntentStatus());
        payload.put("mainForceIntentWarnings", parseEvidence(record.getMainForceIntentWarningsJson()));
        payload.put("promptHash", record.getPromptHash());
        return payload;
    }

    private Map<String, Object> toSourceRecordPayload(StockWatchTechnicalAnalysis record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", record.getId());
        payload.put("date", toLocalDate(record.getAnalysisDate()).toString());
        payload.put("time", record.getRunSlot());
        payload.put("stance", record.getStance());
        payload.put("tone", record.getTone());
        payload.put("confidence", record.getConfidence());
        payload.put("title", record.getTitle());
        payload.put("conclusion", truncate(record.getConclusion(), 360));
        payload.put("evidence", parseEvidence(record.getEvidenceJson()));
        payload.put("mainForceIntent", parseObject(record.getMainForceIntentJson()));
        return payload;
    }

    private Map<String, Object> toPromptRecordPayload(StockWatchTechnicalAnalysis record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", record.getId());
        payload.put("date", toLocalDate(record.getAnalysisDate()).toString());
        payload.put("time", record.getRunSlot());
        payload.put("status", record.getStatus());
        payload.put("stance", record.getStance());
        payload.put("tone", record.getTone());
        payload.put("confidence", formatConfidence(record.getConfidence()));
        payload.put("title", truncate(record.getTitle(), 80));
        payload.put("conclusion", truncate(record.getConclusion(), 240));
        payload.put("evidence", compactEvidence(record.getEvidenceJson(), 3, 120));
        payload.put("missingData", compactEvidence(record.getMissingDataJson(), 3, 120));
        payload.put("mainForceIntent", parseObject(record.getMainForceIntentJson()));
        return payload;
    }

    private Map<String, Object> toPromptSourceRecordPayload(StockWatchTechnicalAnalysis record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", record.getId());
        payload.put("date", toLocalDate(record.getAnalysisDate()).toString());
        payload.put("time", record.getRunSlot());
        payload.put("stance", record.getStance());
        payload.put("tone", record.getTone());
        payload.put("confidence", record.getConfidence());
        payload.put("title", truncate(record.getTitle(), 80));
        payload.put("conclusion", truncate(record.getConclusion(), 240));
        payload.put("evidence", compactEvidence(record.getEvidenceJson(), 3, 120));
        payload.put("mainForceIntent", parseObject(record.getMainForceIntentJson()));
        return payload;
    }

    private List<String> compactEvidence(String evidenceJson, int maxItems, int maxChars) {
        return parseEvidence(evidenceJson).stream()
                .limit(Math.max(0, maxItems))
                .map(value -> truncate(value, maxChars))
                .collect(Collectors.toList());
    }

    private List<String> parseEvidence(String evidenceJson) {
        if (!StringUtils.hasText(evidenceJson)) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(evidenceJson, String.class);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private String sessionStatusLabel(StockWatchTechnicalSession session) {
        if (!"ACTIVE".equalsIgnoreCase(session.getStatus())) {
            return "已轮换";
        }
        return shouldRotate(session) ? "接近换会话" : "复用中";
    }

    private boolean isTraceableUrl(String url) {
        return StringUtils.hasText(url)
                && url.startsWith("https://chat.deepseek.com")
                && !"https://chat.deepseek.com/".equals(url)
                && !"https://chat.deepseek.com".equals(url);
    }

    private String currentRunSlot(ZonedDateTime now, boolean allowOutsideTrading) {
        LocalTime time = now.toLocalTime();
        if (!isTradingSession(time) && allowOutsideTrading) {
            return time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).format(SLOT_FORMATTER);
        }
        return RUN_SLOTS.stream()
                .filter(slot -> !slot.isAfter(time))
                .max(Comparator.naturalOrder())
                .orElse(RUN_SLOTS.get(0))
                .format(SLOT_FORMATTER);
    }

    private String nextRunLabel(ZonedDateTime now) {
        LocalTime time = now.toLocalTime();
        return RUN_SLOTS.stream()
                .filter(slot -> slot.isAfter(time))
                .findFirst()
                .map(slot -> slot.format(SLOT_FORMATTER))
                .orElse("下个交易日 09:30");
    }

    private boolean isTradingSession(LocalTime time) {
        return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0)));
    }

    private boolean isWeeklyDue(ZonedDateTime now) {
        return now.getDayOfWeek() == DayOfWeek.FRIDAY && !now.toLocalTime().isBefore(LocalTime.of(15, 0));
    }

    private boolean isMonthlyDue(ZonedDateTime now) {
        return now.toLocalDate().equals(lastWeekdayOfMonth(now.toLocalDate()))
                && !now.toLocalTime().isBefore(LocalTime.of(15, 0));
    }

    private LocalDate lastWeekdayOfMonth(LocalDate date) {
        LocalDate cursor = YearMonth.from(date).atEndOfMonth();
        while (cursor.getDayOfWeek() == DayOfWeek.SATURDAY || cursor.getDayOfWeek() == DayOfWeek.SUNDAY) {
            cursor = cursor.minusDays(1);
        }
        return cursor;
    }

    private String normalizePeriodType(String raw) {
        String value = text(raw, "DAY").trim().toUpperCase(Locale.ROOT);
        if ("W".equals(value) || "WEEKLY".equals(value)) {
            return "WEEK";
        }
        if ("M".equals(value) || "MONTHLY".equals(value)) {
            return "MONTH";
        }
        if ("WEEK".equals(value) || "MONTH".equals(value)) {
            return value;
        }
        return "DAY";
    }

    private LocalDate oldestPeriodStart(String periodType, LocalDate endDate, int periods) {
        if ("WEEK".equals(periodType)) {
            return currentPeriodWindow(periodType, endDate.minusWeeks(periods - 1L)).start;
        }
        if ("MONTH".equals(periodType)) {
            return currentPeriodWindow(periodType, endDate.minusMonths(periods - 1L)).start;
        }
        return endDate.minusDays(periods - 1L);
    }

    private LocalDate sourceTradeDate(Map<String, Object> payload, LocalDate fallback) {
        LocalDate source = parseLocalDate(payload == null ? null : payload.get("sourceTradeDate"));
        if (source != null) {
            return source;
        }
        JSONObject anchorFacts = asJsonObject(payload == null ? null : payload.get("anchorFacts"));
        source = parseLocalDate(anchorFacts == null ? null : anchorFacts.get("sourceTradeDate"));
        if (source != null) {
            return source;
        }
        List<Object> dailyBars = toObjectList(payload == null ? null : payload.get("dailyKline"));
        JSONObject latest = lastObject(dailyBars);
        source = parseLocalDate(latest == null ? null : latest.get("date"));
        return source == null ? fallback : source;
    }

    private LocalDate parseLocalDate(Object raw) {
        String value = text(raw, "");
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
        } catch (Exception ignored) {
            return null;
        }
    }

    private PeriodWindow currentPeriodWindow(String periodType, LocalDate date) {
        String normalized = normalizePeriodType(periodType);
        if ("WEEK".equals(normalized)) {
            LocalDate anchor = weekAnchor(date);
            LocalDate start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate end = start.plusDays(4);
            int weekYear = start.get(WeekFields.ISO.weekBasedYear());
            int week = start.get(WeekFields.ISO.weekOfWeekBasedYear());
            String key = String.format(Locale.ROOT, "%04d-W%02d", weekYear, week);
            return new PeriodWindow(normalized, key, start, end);
        }
        if ("MONTH".equals(normalized)) {
            YearMonth month = YearMonth.from(date);
            return new PeriodWindow(normalized, month.format(YEAR_MONTH_FORMATTER), month.atDay(1), month.atEndOfMonth());
        }
        return new PeriodWindow("DAY", date.format(DATE_FORMATTER), date, date);
    }

    private LocalDate weekAnchor(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return date.minusDays(1);
        }
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return date.minusDays(2);
        }
        return date;
    }

    private String periodKey(String periodType, LocalDate date) {
        return currentPeriodWindow(periodType, date).key;
    }

    private List<Map<String, Object>> buildPeriodsPayload(String periodType,
                                                          LocalDate endDate,
                                                          int count,
                                                          List<StockWatchTechnicalAnalysis> records) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (StockWatchTechnicalAnalysis record : records) {
            LocalDate recordDate = toLocalDate(record.getAnalysisDate());
            String key = firstNonBlank(record.getPeriodKey(), periodKey(periodType, recordDate));
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }

        List<Map<String, Object>> payload = new ArrayList<>();
        LocalDate cursor = endDate;
        for (int index = 0; index < count; index++) {
            PeriodWindow window = currentPeriodWindow(periodType, cursor);
            Map<String, Object> item = window.toPayload();
            item.put("date", window.end.toString());
            item.put("label", periodLabel(window, endDate));
            item.put("sublabel", periodSublabel(window));
            item.put("count", counts.getOrDefault(window.key, 0));
            payload.add(item);
            cursor = window.start.minusDays(1);
        }
        return payload;
    }

    private String periodLabel(PeriodWindow window, LocalDate today) {
        if ("DAY".equals(window.type)) {
            if (window.start.equals(today)) {
                return "今天";
            }
            if (window.start.equals(today.minusDays(1))) {
                return "昨天";
            }
            return window.start.format(MONTH_DAY_FORMATTER);
        }
        if ("WEEK".equals(window.type)) {
            return window.key.replace("-W", " 第") + "周";
        }
        return window.key;
    }

    private String periodSublabel(PeriodWindow window) {
        if ("DAY".equals(window.type)) {
            return "日内30m";
        }
        if ("WEEK".equals(window.type)) {
            return window.start.format(MONTH_DAY_FORMATTER) + " - " + window.end.format(MONTH_DAY_FORMATTER);
        }
        return window.start.format(MONTH_DAY_FORMATTER) + " - " + window.end.format(MONTH_DAY_FORMATTER);
    }

    private List<StockRef> parseConfiguredSymbols(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Collections.emptyList();
        }
        List<StockRef> refs = new ArrayList<>();
        for (String token : raw.split("[,;\\s]+")) {
            try {
                refs.add(normalizeStockRef(token, ""));
            } catch (Exception ignored) {
            }
        }
        return refs;
    }

    private List<StockRef> scheduledRefs() {
        Map<String, StockRef> refs = new LinkedHashMap<>();
        for (StockRef ref : parseConfiguredSymbols(configuredSymbols)) {
            refs.put(ref.symbol + "." + ref.market, ref);
        }
        for (String symbol : watchListService.listEnabledSymbols()) {
            try {
                StockRef ref = normalizeStockRef(symbol, "");
                refs.put(ref.symbol + "." + ref.market, ref);
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(refs.values());
    }

    private List<String> scheduledSymbolLabels() {
        return scheduledRefs().stream()
                .map(this::stockLabel)
                .collect(Collectors.toList());
    }

    private String stockLabel(StockRef ref) {
        return ref.symbol + "." + ref.market;
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private String classifySkipReason(Exception e) {
        String message = diagnosticMessage(e);
        if (message.contains("非交易时段")) {
            return "non_trading_session";
        }
        if (message.contains("预测必需数据未就绪")) {
            return "required_market_data_missing";
        }
        if (message.contains("行情抓取失败")) {
            return "quote_fetch_failed";
        }
        return "exception";
    }

    private StockRef normalizeStockRef(String rawSymbol, String rawMarket) {
        String value = text(rawSymbol, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String market = text(rawMarket, "").trim().toLowerCase(Locale.ROOT);
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
            throw new IllegalArgumentException("股票编码格式不正确");
        }
        String symbol = suffixed.group(1);
        String effectiveMarket = StringUtils.hasText(market)
                ? market
                : (StringUtils.hasText(suffixed.group(2)) ? suffixed.group(2) : inferMarket(symbol));
        return new StockRef(symbol, effectiveMarket);
    }

    private String inferMarket(String symbol) {
        if (symbol.startsWith("6") || symbol.startsWith("9")) return "sh";
        if (symbol.startsWith("4") || symbol.startsWith("8")) return "bj";
        return "sz";
    }

    private LocalDate toLocalDate(java.util.Date date) {
        if (date instanceof Date) {
            return ((Date) date).toLocalDate();
        }
        return date.toInstant().atZone(CHINA_ZONE).toLocalDate();
    }

    private List<String> toStringList(JSONArray array) {
        if (array == null) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String value = text(array.get(i), "");
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String normalizeTone(String tone) {
        String value = text(tone, "amber").toLowerCase(Locale.ROOT);
        if (Arrays.asList("green", "blue", "amber", "red").contains(value)) {
            return value;
        }
        return "amber";
    }

    private double normalizeConfidence(Object raw) {
        double value = number(raw, 0.5);
        if (value > 1.0) {
            value = value / 100.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String formatConfidence(Double confidence) {
        double value = confidence == null ? 0.0 : confidence;
        return Math.round(value * 100) + "%";
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value)) return false;
        return fallback;
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

    private String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(Objects.hashCode(text)));
        }
    }

    private static class PeriodWindow {
        private final String type;
        private final String key;
        private final LocalDate start;
        private final LocalDate end;

        private PeriodWindow(String type, String key, LocalDate start, LocalDate end) {
            this.type = type;
            this.key = key;
            this.start = start;
            this.end = end;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("key", key);
            payload.put("startDate", start.toString());
            payload.put("endDate", end.toString());
            return payload;
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

    private static class BatchStats {
        private int attempted;
        private int completed;
        private int skipped;
        private int failed;

        private void record(PeriodRunResult result) {
            attempted++;
            if (result.skipped) {
                skipped++;
            } else if ("SUCCESS".equalsIgnoreCase(result.recordStatus)) {
                completed++;
            } else {
                failed++;
            }
        }

        private void recordFailure() {
            attempted++;
            failed++;
        }
    }

    private static class PeriodRunResult {
        private final boolean skipped;
        private final String recordStatus;

        private PeriodRunResult(boolean skipped, String recordStatus) {
            this.skipped = skipped;
            this.recordStatus = recordStatus;
        }

        private static PeriodRunResult skipped() {
            return new PeriodRunResult(true, null);
        }

        private static PeriodRunResult completed(String recordStatus) {
            return new PeriodRunResult(false, recordStatus);
        }
    }

    private static class BridgeResult {
        private String answerText;
        private String targetId;
        private String finalUrl;
        private String contentStatus;
        private String completionReason;
    }

    private static class AnalysisPayload {
        private String stance;
        private String tone;
        private double confidence;
        private String title;
        private String conclusion;
        private List<String> evidence = new ArrayList<>();
        private List<String> missingData = new ArrayList<>();
        private JSONObject mainForceIntent;
        private JSONArray forecasts = new JSONArray();
        private List<String> schemaWarnings = new ArrayList<>();
    }

    private static class IntentValidationResult {
        private final boolean valid;
        private final String status;
        private final JSONObject intent;
        private final List<String> problems;
        private final List<String> warnings;

        private IntentValidationResult(boolean valid,
                                       String status,
                                       JSONObject intent,
                                       List<String> problems,
                                       List<String> warnings) {
            this.valid = valid;
            this.status = status;
            this.intent = intent;
            this.problems = problems == null ? new ArrayList<>() : new ArrayList<>(problems);
            this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
        }

        private static IntentValidationResult valid(JSONObject intent, List<String> warnings) {
            return new IntentValidationResult(
                    true,
                    warnings == null || warnings.isEmpty() ? "VALID" : "VALID_WITH_WARNINGS",
                    intent,
                    Collections.emptyList(),
                    warnings
            );
        }

        private static IntentValidationResult invalid(String problem) {
            return invalid(new JSONObject(true), Collections.singletonList(problem), Collections.emptyList());
        }

        private static IntentValidationResult invalid(JSONObject intent, List<String> problems, List<String> warnings) {
            return new IntentValidationResult(false, "INVALID", intent, problems, warnings);
        }
    }

    private static class ExistingAnalysisSkippedException extends RuntimeException {
        private final StockWatchTechnicalAnalysis existing;

        private ExistingAnalysisSkippedException(StockWatchTechnicalAnalysis existing) {
            super("existing_success:" + (existing == null ? "" : existing.getId()));
            this.existing = existing;
        }
    }

    private static class ModelResponseSchemaException extends IllegalStateException {
        private ModelResponseSchemaException(String message) {
            super(message);
        }
    }
}
