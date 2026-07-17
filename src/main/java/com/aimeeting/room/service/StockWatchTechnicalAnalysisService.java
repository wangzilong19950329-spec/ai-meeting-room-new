package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchIntentTest;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.entity.StockWatchTechnicalSession;
import com.aimeeting.room.service.StockWatchAnalysisTaskService.Reservation;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
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
import java.time.LocalDateTime;
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
import java.util.TreeMap;
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
    private static final List<LocalTime> HK_RUN_SLOTS = Arrays.asList(
            LocalTime.of(9, 30),
            LocalTime.of(10, 0),
            LocalTime.of(10, 30),
            LocalTime.of(11, 0),
            LocalTime.of(11, 30),
            LocalTime.of(12, 0),
            LocalTime.of(13, 0),
            LocalTime.of(13, 30),
            LocalTime.of(14, 0),
            LocalTime.of(14, 30),
            LocalTime.of(15, 0),
            LocalTime.of(15, 30),
            LocalTime.of(16, 0)
    );
    private static final List<String> MAIN_FORCE_INTENTS = Arrays.asList(
            "ACCUMULATE", "DISTRIBUTE", "WASH", "PULL_UP", "SUPPORT", "SUPPRESS", "ROTATE", "NEUTRAL", "UNKNOWN"
    );
    private static final List<String> MAIN_FORCE_WINDOWS = Arrays.asList("INTRADAY", "THREE_DAY", "FIVE_DAY", "TWENTY_DAY");
    private static final int RESPONSE_CONTRACT_VERSION = 6;
    private static final String RESPONSE_CONTRACT_NAME = "stock-analysis-response-v6";
    private static final Set<Integer> SUPPORTED_RESPONSE_CONTRACTS = new LinkedHashSet<>(Arrays.asList(5, 6));
    private static final Set<String> STRONG_MAIN_FORCE_INTENTS = new LinkedHashSet<>(Arrays.asList(
            "ACCUMULATE", "DISTRIBUTE", "WASH", "PULL_UP", "SUPPORT", "SUPPRESS", "ROTATE"
    ));
    private static final java.util.regex.Pattern EVIDENCE_PATH_PATTERN = java.util.regex.Pattern.compile(
            "(?:evidenceSignals|externalEvidenceCatalog)\\.[A-Za-z0-9_.$\\[\\]-]+"
    );

    private final EastmoneyMarketDataService marketDataService;
    private final StockWatchPredictionService predictionService;
    private final StockWatchListService watchListService;
    private final StockWatchTechnicalSessionMapper sessionMapper;
    private final StockWatchTechnicalAnalysisMapper analysisMapper;
    private final StockWatchAnalysisTaskService analysisTaskService;
    private final StockWatchAnalysisProjectionService analysisProjectionService;
    private final StockWatchRiskPatternService riskPatternService;
    private final StockWatchFactPersistenceService factPersistenceService;
    private final StockWatchV6RuntimeGate runtimeGate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconciling = new AtomicBoolean(false);

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

    @Value("${stock-watch.technical-analysis.bridge-url:http://127.0.0.1:8791/search}")
    private String bridgeUrl;

    @Value("${stock-watch.technical-analysis.debug-port:9344}")
    private int debugPort;

    @Value("${stock-watch.technical-analysis.connect-timeout-seconds:${ai.search.deepseek-web.connect-timeout-seconds:10}}")
    private int connectTimeoutSeconds;

    @Value("${stock-watch.technical-analysis.read-timeout-seconds:${ai.search.deepseek-web.read-timeout-seconds:320}}")
    private int readTimeoutSeconds;

    @Value("${stock-watch.technical-analysis.task-stale-after-seconds:420}")
    private int taskStaleAfterSeconds;

    @Value("${stock-watch.technical-analysis.reconcile-batch-size:20}")
    private int reconcileBatchSize;

    private volatile ZonedDateTime lastRunAt;
    private volatile String lastRunStatus = "never";

    public StockWatchTechnicalAnalysisService(EastmoneyMarketDataService marketDataService,
                                              StockWatchPredictionService predictionService,
                                              StockWatchListService watchListService,
                                              StockWatchTechnicalSessionMapper sessionMapper,
                                              StockWatchTechnicalAnalysisMapper analysisMapper,
                                              StockWatchAnalysisTaskService analysisTaskService,
                                              StockWatchAnalysisProjectionService analysisProjectionService,
                                              StockWatchRiskPatternService riskPatternService,
                                              StockWatchFactPersistenceService factPersistenceService,
                                              StockWatchV6RuntimeGate runtimeGate) {
        this.marketDataService = marketDataService;
        this.predictionService = predictionService;
        this.watchListService = watchListService;
        this.sessionMapper = sessionMapper;
        this.analysisMapper = analysisMapper;
        this.analysisTaskService = analysisTaskService;
        this.analysisProjectionService = analysisProjectionService;
        this.riskPatternService = riskPatternService;
        this.factPersistenceService = factPersistenceService;
        this.runtimeGate = runtimeGate;
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
        runtimeGate.assertReady();
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

    @Scheduled(
            fixedDelayString = "${stock-watch.technical-analysis.reconcile-interval-ms:60000}",
            initialDelayString = "${stock-watch.technical-analysis.reconcile-initial-delay-ms:120000}"
    )
    public void scheduledReconcileTasks() {
        runtimeGate.assertReady();
        if (!enabled || !reconciling.compareAndSet(false, true)) return;
        long startedAt = System.nanoTime();
        int recovered = 0;
        int terminalized = 0;
        int failed = 0;
        try {
            long staleMillis = Math.max(60, taskStaleAfterSeconds) * 1000L;
            List<Reservation> tasks = analysisTaskService.findRecoverableBefore(
                    new java.util.Date(System.currentTimeMillis() - staleMillis), reconcileBatchSize);
            if (!tasks.isEmpty()) {
                log.info("stock-watch analysis task reconcile start candidates={}", tasks.size());
            }
            for (Reservation reservation : tasks) {
                try {
                    String status = text(reservation.getTask().getStatus(), "").toUpperCase(Locale.ROOT);
                    if ("RESERVED".equals(status)) {
                        persistAbandonedTaskFromIdentity(reservation, "FAILED",
                                "stale_reserved_reconciled_without_provider_submission");
                        terminalized++;
                    } else if ("SUBMITTED".equals(status)) {
                        BridgeLedgerLookup lookup = lookupBridgeLedger(reservation);
                        if (lookup.result != null) {
                            analysisTaskService.markResponseReceived(reservation, lookup.result.answerText,
                                    lookup.result.contentStatus, lookup.result.completionReason,
                                    "RECOVERED_FROM_BRIDGE_LEDGER", lookup.result.targetId, lookup.result.finalUrl);
                            recoverPreparedTaskWithTerminalFallback(reservation);
                            recovered++;
                        } else {
                            persistAbandonedTaskFromIdentity(reservation, "UNKNOWN",
                                    "stale_submitted_reconciled_without_replayable_result:" + lookup.status
                                            + (StringUtils.hasText(lookup.error) ? ":" + lookup.error : ""));
                            terminalized++;
                        }
                    } else if ("RESPONSE_RECEIVED".equals(status) || "PROJECTION_FAILED".equals(status)) {
                        recoverPreparedTaskWithTerminalFallback(reservation);
                        recovered++;
                    }
                } catch (Exception taskError) {
                    failed++;
                    log.warn("stock-watch analysis task reconcile failed taskId={} status={} error={}",
                            reservation.getTask().getId(), reservation.getTask().getStatus(),
                            diagnosticMessage(taskError));
                }
            }
            if (!tasks.isEmpty()) {
                log.info("stock-watch analysis task reconcile end candidates={} recovered={} terminalized={} failed={} elapsedMs={}",
                        tasks.size(), recovered, terminalized, failed, elapsedMillis(startedAt));
            }
        } finally {
            reconciling.set(false);
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
        runtimeGate.assertReady();
        StockRef ref = normalizeStockRef(text(request.get("symbol"), ""), text(request.get("market"), ""));
        String name = text(request.get("name"), ref.symbol);
        String periodType = normalizePeriodType(text(request.get("periodType"), "DAY"));
        boolean forceNewSession = bool(request.get("forceNewSession"), false);
        boolean ignoreTradingHours = bool(request.get("ignoreTradingHours"), true);
        boolean reuseOnly = bool(request.get("reuseOnly"), false);
        StockWatchTechnicalAnalysis record = runAnalysis(ref, name, "manual_api", forceNewSession,
                ignoreTradingHours, periodType, false, reuseOnly);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("record", toRecordPayload(record));
        StockWatchTechnicalSession recordSession = record.getSessionId() == null
                ? null
                : sessionMapper.selectById(record.getSessionId());
        payload.put("session", toSessionPayload(recordSession, ref, record.getPeriodType()));
        return payload;
    }

    private void runScheduledBatch() {
        long batchStartedAt = System.nanoTime();
        ZonedDateTime now = ZonedDateTime.now(CHINA_ZONE);
        String batchId = now.format(BATCH_ID_FORMATTER);
        lastRunAt = now;
        List<StockRef> refs = scheduledRefs();
        boolean tradingSession = refs.stream().anyMatch(ref -> isTradingSession(now, ref.market));
        boolean weeklyDue = isWeeklyDue(now);
        boolean monthlyDue = isMonthlyDue(now);
        String daySlot = tradingSession ? now.toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).format(SLOT_FORMATTER) : "";
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
                if (isTradingSession(now, ref.market)) {
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
        String runSlot = "DAY".equals(periodType) ? currentRunSlot(now, ignoreTradingHours, ref.market) : periodType;
        String symbol = stockLabel(ref);
        log.info("stock-watch technical analysis stock start batchId={} symbol={} period={} slot={} periodKey={} reason={}",
                batchId, symbol, periodType, runSlot, window.key, reason);
        try {
            StockWatchTechnicalAnalysis record = runAnalysis(ref, ref.symbol, reason, false,
                    ignoreTradingHours, periodType, true, false);
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
                                                    boolean skipExistingSuccess,
                                                    boolean reuseOnly) {
        String periodType = normalizePeriodType(rawPeriodType);
        ZonedDateTime now = ZonedDateTime.now(CHINA_ZONE);
        if ("DAY".equals(periodType) && tradingHoursOnly && !ignoreTradingHours && !isTradingSession(now, ref.market)) {
            throw new IllegalStateException("非交易时段，已跳过自动技术面分析");
        }

        EastmoneyMarketDataService.MarketDataBundle marketData = marketDataService.buildBundle(ref.symbol, ref.market, periodType);
        @SuppressWarnings("unchecked")
        Map<String, Object> quote = (Map<String, Object>) marketData.getPayload().getOrDefault("quote", Collections.emptyMap());
        String stockName = text(quote.get("name"), requestedName);
        LocalDate sourceTradeDate = sourceTradeDate(marketData.getPayload(), now.toLocalDate());
        PeriodWindow window = currentPeriodWindow(periodType, sourceTradeDate);
        String runSlot = "DAY".equals(periodType)
                ? currentRunSlot(now, ignoreTradingHours, ref.market, sourceTradeDate) : periodType;
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
        String observationKey = analysisObservationKey(periodType, sourceTradeDate, runSlot);
        String submissionKey = analysisSubmissionKey(ref, observationKey);
        Reservation taskReservation = analysisTaskService.findCanonical(
                ref.symbol, ref.market, periodType, observationKey);
        if (taskReservation == null && reuseOnly) {
            throw new AnalysisReuseOnlyMissException(observationKey);
        }
        if (taskReservation != null
                && (taskReservation.isProjectedSuccess() || taskReservation.isTerminalFailure())) {
            StockWatchTechnicalAnalysis taskResult = resolveExistingTaskResult(taskReservation);
            if (taskResult != null) return taskResult;
        }

        Map<String, Object> factReceipt = Collections.emptyMap();
        Map<String, Object> promptMarketData = Collections.emptyMap();
        Map<String, Object> evidenceSignals = Collections.emptyMap();
        JSONObject externalEvidenceCatalog = null;
        List<StockWatchTechnicalAnalysis> sourceRecords = Collections.emptyList();
        List<Map<String, Object>> recentTwoWeekLedger = Collections.emptyList();
        JSONObject historyCatalog = null;
        String sourceRecordIds = "[]";
        String evidenceSignalJson = "{}";
        String externalEvidenceCatalogJson = "{}";
        String historyCatalogJson = "{}";
        String dataSnapshotJson = "{}";
        String missingDataJson = "[]";
        Double originObservationIdValue = null;
        Double sourcePrice = number(quote.get("price"), null);
        if (taskReservation == null) {
            List<Map<String, Object>> dailyBars = toMapList(marketData.getPayload().get("dailyKline"));
            StockWatchRiskPatternService.RiskPatternResult riskPattern = riskPatternService.analyze(
                    ref.symbol, ref.market, quote, dailyBars, now);
            marketData.getPayload().put("riskPatternObservations", riskPattern.getObservations());
            marketData.getPayload().put("riskPatternGuidance", riskPattern.getGuidance());
            factReceipt = factPersistenceService.observe(
                    ref.symbol, ref.market, stockName, quote, riskPattern, now).toMap();
            marketData.getPayload().put("factObservationReceipt", factReceipt);
            promptMarketData = toPromptMarketDataPayload(marketData.getPayload(), periodType);
            externalEvidenceCatalog = buildExternalEvidenceCatalog(marketData.getPayload());
            if (!verifyExternalEvidenceCatalogDigest(externalEvidenceCatalog)) {
                throw new IllegalStateException("external_evidence_catalog_generation_digest_invalid");
            }
            evidenceSignals = buildEvidenceSignals(marketData.getPayload(), periodType, ref.market, externalEvidenceCatalog);
            if (!marketData.canPredict()) {
                return persistDataUnavailable(ref, stockName, sourceTradeDate, runSlot, periodType, window,
                        marketData, evidenceSignals);
            }
            sourceRecords = sourceRecordsForPeriod(ref, periodType, window);
            recentTwoWeekLedger = recentTwoWeekLedger(ref, window.end);
            historyCatalog = buildHistoryEvidenceCatalog(ref, window.end, recentTwoWeekLedger);
            if (!verifyHistoryCatalogDigest(historyCatalog)) {
                throw new IllegalStateException("history_catalog_generation_digest_invalid");
            }
            sourceRecordIds = sourceRecordIdsJson(sourceRecords);
            evidenceSignalJson = JSON.toJSONString(evidenceSignals);
            externalEvidenceCatalogJson = externalEvidenceCatalog.toJSONString();
            historyCatalogJson = historyCatalog.toJSONString();
            dataSnapshotJson = marketData.toJson();
            List<String> projectionMissingData = new ArrayList<>();
            projectionMissingData.addAll(marketData.getRequiredMissing());
            projectionMissingData.addAll(marketData.getOptionalMissing());
            missingDataJson = JSON.toJSONString(projectionMissingData);
            originObservationIdValue = number(factReceipt.get("observationId"), null);
            taskReservation = analysisTaskService.claimCanonical(submissionKey, RESPONSE_CONTRACT_NAME, ref.symbol,
                    ref.market, periodType, observationKey, "deepseek_web_bridge");
        }
        StockWatchTechnicalSession session = null;
        String prompt = null;
        if (taskReservation.isNewSubmission()) {
            session = getOrCreateSession(ref, stockName, forceNewSession, periodType);
            prompt = buildPrompt(ref, stockName, promptMarketData, evidenceSignals, externalEvidenceCatalog, historyCatalog,
                    recentTwoWeekLedger, session, reason, periodType, window, sourceRecords);
            String promptHash = sha256(prompt);
            int generation = session.getGeneration() == null ? 1 : session.getGeneration();
            String projectionJson = frozenProjectionJson(session, stockName, sourceTradeDate, runSlot, periodType,
                    window.key, sourceRecordIds, evidenceSignalJson, externalEvidenceCatalogJson, dataSnapshotJson,
                    originObservationIdValue == null ? null : originObservationIdValue.longValue(),
                    sourcePrice, promptHash, prompt.length(), missingDataJson, historyCatalogJson);
            Map<String, Object> requestEnvelope = new LinkedHashMap<>();
            requestEnvelope.put("schema", "stock-analysis-v3");
            requestEnvelope.put("responseContractVersion", RESPONSE_CONTRACT_VERSION);
            requestEnvelope.put("symbol", ref.symbol);
            requestEnvelope.put("market", ref.market);
            requestEnvelope.put("periodType", periodType);
            requestEnvelope.put("observationKey", observationKey);
            requestEnvelope.put("sourceTradeDate", sourceTradeDate.toString());
            requestEnvelope.put("runSlot", runSlot);
            requestEnvelope.put("generation", generation);
            requestEnvelope.put("promptChars", prompt.length());
            requestEnvelope.put("projectionJson", projectionJson);
            requestEnvelope.put("projectionDigest", sha256(projectionJson));
            analysisTaskService.prepare(taskReservation, promptHash, generation, requestEnvelope);
        }
        if (!taskReservation.isNewSubmission() && "RESERVED".equals(taskReservation.getTask().getStatus())
                && taskResponseContractVersion(taskReservation) < 6) {
            return persistAbandonedTask(taskReservation, ref, stockName, sourceTradeDate, runSlot,
                    periodType, window.key, "FAILED", "legacy_pre_submit_not_resumable");
        }
        if (!taskReservation.isNewSubmission() && "RESERVED".equals(taskReservation.getTask().getStatus())
                && isTaskStale(taskReservation)) {
            return persistAbandonedTask(taskReservation, ref, stockName, sourceTradeDate, runSlot,
                    periodType, window.key, "FAILED", "stale_reserved_before_provider_submission");
        }
        if (!taskReservation.isNewSubmission() && "SUBMITTED".equals(taskReservation.getTask().getStatus())) {
            BridgeLedgerLookup lookup = lookupBridgeLedger(taskReservation);
            if (lookup.result != null) {
                analysisTaskService.markResponseReceived(taskReservation, lookup.result.answerText,
                        lookup.result.contentStatus, lookup.result.completionReason, "RECOVERED_FROM_BRIDGE_LEDGER",
                        lookup.result.targetId, lookup.result.finalUrl);
            } else if (lookup.isTerminalWithoutResult() || isTaskStale(taskReservation)) {
                return persistAbandonedTask(taskReservation, ref, stockName, sourceTradeDate, runSlot,
                        periodType, window.key, "UNKNOWN", "provider_outcome_not_retriable:" + lookup.status
                                + (StringUtils.hasText(lookup.error) ? ":" + lookup.error : ""));
            }
        }
        StockWatchTechnicalAnalysis existingTaskResult = resolveExistingTaskResult(taskReservation);
        if (existingTaskResult != null) return existingTaskResult;

        return projectPreparedTask(taskReservation, ref, stockName, sourceTradeDate, runSlot, periodType,
                window, session, prompt, forceNewSession, sourceRecordIds, evidenceSignalJson,
                externalEvidenceCatalogJson, historyCatalogJson, dataSnapshotJson, missingDataJson,
                originObservationIdValue, sourcePrice);
    }

    private StockWatchTechnicalAnalysis projectPreparedTask(Reservation taskReservation,
                                                             StockRef ref,
                                                             String stockName,
                                                             LocalDate sourceTradeDate,
                                                             String runSlot,
                                                             String periodType,
                                                             PeriodWindow window,
                                                             StockWatchTechnicalSession session,
                                                             String prompt,
                                                             boolean forceNewSession,
                                                             String sourceRecordIds,
                                                             String evidenceSignalJson,
                                                             String externalEvidenceCatalogJson,
                                                             String historyCatalogJson,
                                                             String dataSnapshotJson,
                                                             String missingDataJson,
                                                             Double originObservationIdValue,
                                                             Double sourcePrice) {
        FrozenProjection frozen = null;
        Map<String, Object> responseReceipt = null;
        if (!taskReservation.isNewSubmission()) {
            try {
                responseReceipt = analysisTaskService.responseReceipt(taskReservation);
                frozen = restoreFrozenProjection(taskReservation);
            } catch (Exception replayError) {
                return persistInvalidReplay(taskReservation, ref, stockName, sourceTradeDate, runSlot,
                        periodType, window.key, null, replayError);
            }
        }
        if (frozen != null && !taskReservation.isRequestDigestMatch()) {
            log.info("stock-watch projection replay uses frozen request taskId={} currentPromptDigestChanged=true",
                    taskReservation.getTask().getId());
        }
        StockWatchTechnicalSession projectionSession = frozen == null
                ? session
                : sessionMapper.selectById(frozen.sessionId);
        if (projectionSession == null) {
            IllegalStateException missingSession = new IllegalStateException("analysis_projection_session_missing:"
                    + (frozen == null || frozen.sessionId == null ? "null" : frozen.sessionId));
            if (!taskReservation.isNewSubmission()) {
                return persistInvalidReplay(taskReservation, ref, stockName, sourceTradeDate, runSlot,
                        periodType, window.key, frozen, missingSession);
            }
            throw missingSession;
        }
        String projectionStockName = frozen == null ? stockName : frozen.stockName;
        String projectionSymbol = frozen == null ? ref.symbol : taskReservation.getTask().getSymbol();
        String projectionMarket = frozen == null ? ref.market : taskReservation.getTask().getMarket();
        LocalDate projectionSourceDate = frozen == null ? sourceTradeDate : LocalDate.parse(frozen.sourceTradeDate);
        String projectionRunSlot = frozen == null ? runSlot : frozen.runSlot;
        String projectionPeriodType = frozen == null ? periodType : frozen.periodType;
        String projectionPeriodKey = frozen == null ? window.key : frozen.periodKey;
        String projectionSourceRecordIds = frozen == null ? sourceRecordIds : frozen.sourceRecordIds;
        String projectionEvidenceSignalJson = frozen == null ? evidenceSignalJson : frozen.evidenceSignalJson;
        String projectionExternalEvidenceCatalogJson = frozen == null
                ? externalEvidenceCatalogJson : frozen.externalEvidenceCatalogJson;
        String projectionDataSnapshotJson = frozen == null ? dataSnapshotJson : frozen.dataSnapshotJson;
        String projectionMissingDataJson = frozen == null ? missingDataJson : frozen.missingDataJson;
        String projectionHistoryCatalogJson = frozen == null ? historyCatalogJson : frozen.historyCatalogJson;
        JSONObject projectionEvidenceSignals = parseObject(projectionEvidenceSignalJson);
        if (projectionEvidenceSignals == null) {
            throw new IllegalStateException("analysis_projection_evidence_invalid");
        }
        JSONObject projectionExternalEvidenceCatalog = parseObject(projectionExternalEvidenceCatalogJson);
        if (projectionExternalEvidenceCatalog == null
                || !verifyExternalEvidenceCatalogDigest(projectionExternalEvidenceCatalog)
                || !externalCatalogMatchesCitationPaths(projectionExternalEvidenceCatalog, projectionEvidenceSignals)) {
            throw new IllegalStateException("analysis_projection_external_evidence_catalog_invalid");
        }
        JSONObject projectionHistoryCatalog = parseObject(projectionHistoryCatalogJson);
        if (projectionHistoryCatalog == null || !verifyHistoryCatalogDigest(projectionHistoryCatalog)) {
            throw new IllegalStateException("analysis_projection_history_catalog_invalid");
        }
        Long projectionOriginObservationId = frozen == null
                ? (originObservationIdValue == null ? null : originObservationIdValue.longValue())
                : frozen.originObservationId;
        Double projectionSourcePrice = frozen == null ? sourcePrice : frozen.sourcePrice;
        String projectionPromptHash = frozen == null ? taskReservation.getTask().getRequestDigest() : frozen.promptHash;
        int projectionPromptChars = frozen == null ? prompt.length() : frozen.promptChars;
        int responseContractVersion = frozen == null ? RESPONSE_CONTRACT_VERSION : frozen.responseContractVersion;

        BridgeResult bridgeResult = null;
        AnalysisPayload parsed;
        IntentValidationResult intentValidation = IntentValidationResult.invalid("mainForceIntent_not_checked");
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setSessionId(projectionSession.getId());
        record.setSymbol(projectionSymbol);
        record.setMarket(projectionMarket);
        record.setStockName(projectionStockName);
        record.setAnalysisDate(Date.valueOf(projectionSourceDate));
        record.setRunSlot(projectionRunSlot);
        record.setPeriodType(projectionPeriodType);
        record.setPeriodKey(projectionPeriodKey);
        record.setSourceRecordIds(projectionSourceRecordIds);
        record.setEvidenceSignalJson(projectionEvidenceSignalJson);
        record.setAnalysisTaskId(taskReservation.getTask().getId());
        record.setResponseContractVersion(responseContractVersion);
        record.setProvider("deepseek_web_bridge");
        record.setPromptHash(projectionPromptHash);
        boolean projectionPhase = false;
        try {
            if (taskReservation.hasReusableSuccess() || taskReservation.hasReusableResponse()) {
                bridgeResult = BridgeResult.cached(taskReservation.getTask().getRawResponse(),
                        taskReservation.getTask().getContentStatus(), taskReservation.getTask().getCompletionReason(),
                        text(responseReceipt == null ? null : responseReceipt.get("bridgeTargetId"), ""),
                        text(responseReceipt == null ? null : responseReceipt.get("conversationUrl"), ""));
            } else {
                if (!taskReservation.isNewSubmission()) {
                    throw new IllegalStateException(taskReservation.refusalReason());
                }
                analysisTaskService.markSubmitted(taskReservation, "deepseek_web_bridge");
                bridgeResult = callDeepSeekBridge(prompt, session, forceNewSession, taskReservation);
            }
            runtimeGate.assertReady();
            parsed = parseAnalysisPayload(bridgeResult.answerText, projectionStockName);
            intentValidation = validateResponseContract(responseContractVersion, parsed.mainForceIntent,
                    projectionEvidenceSignals, projectionExternalEvidenceCatalog, projectionHistoryCatalog);
            if (responseContractVersion == 5) {
                validateHistoricalNarrative(parsed, projectionHistoryCatalog);
            } else {
                applyTrustedV6Payload(parsed, projectionEvidenceSignals,
                        projectionPeriodType, projectionSourceDate, projectionRunSlot);
            }
            if (!hasRequiredForecasts(parsed.forecasts, projectionPeriodType)) {
                throw new ModelResponseSchemaException("DeepSeek 返回JSON缺少当前周期要求的完整 forecasts，未保存不完整预测"
                        + schemaWarningSuffix(parsed));
            }
            if (!intentValidation.valid) {
                throw new ModelResponseSchemaException("DeepSeek 返回JSON缺少可校验的 mainForceIntent，未保存不完整主力意图："
                        + String.join("；", intentValidation.problems) + schemaWarningSuffix(parsed));
            }
            try {
                predictionService.validatePredictionsStrict(projectionSourcePrice, parsed.forecasts);
            } catch (IllegalArgumentException invalidForecast) {
                throw new ModelResponseSchemaException("DeepSeek 返回 forecasts 无法安全投影："
                        + diagnosticMessage(invalidForecast));
            }
            if (taskReservation.isNewSubmission()) {
                analysisTaskService.markResponseReceived(taskReservation, bridgeResult.answerText,
                        bridgeResult.contentStatus, bridgeResult.completionReason, "PARSED_VALID",
                        bridgeResult.targetId, bridgeResult.finalUrl);
            }
            JSONArray frozenMissingData = JSON.parseArray(projectionMissingDataJson);
            parsed.missingData = frozenMissingData == null
                    ? new ArrayList<>()
                    : frozenMissingData.stream().map(String::valueOf).collect(Collectors.toList());
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
            record.setDataSnapshotJson(projectionDataSnapshotJson);
            record.setRawResponse(bridgeResult.answerText);
            String projectionBridgeTargetId = firstNonBlank(bridgeResult.targetId, projectionSession.getBridgeTargetId());
            String projectionConversationUrl = firstNonBlank(bridgeResult.finalUrl, projectionSession.getConversationUrl());
            record.setBridgeTargetId(projectionBridgeTargetId);
            record.setConversationUrl(projectionConversationUrl);
            record.setErrorMessage(schemaWarningSummary(parsed));
            record.setContextCharsDelta(projectionPromptChars + text(bridgeResult.answerText, "").length());
            projectionPhase = true;
            return analysisProjectionService.project(taskReservation,
                    projectionOriginObservationId,
                    intentValidation.intent, record, parsed.forecasts, projectionSourceDate,
                    projectionSourcePrice, projectionDataSnapshotJson, projectionSession,
                    projectionBridgeTargetId, projectionConversationUrl);
        } catch (Exception e) {
            if (projectionPhase) {
                try {
                    analysisTaskService.markProjectionFailed(taskReservation, diagnosticMessage(e));
                } catch (Exception transitionError) {
                    log.error("stock-watch analysis projection failure state update failed taskId={} error={}",
                            taskReservation.getTask().getId(), diagnosticMessage(transitionError));
                }
                throw new IllegalStateException("analysis_projection_failed:" + diagnosticMessage(e), e);
            }
            if (!taskReservation.isNewSubmission()) {
                return persistInvalidReplay(taskReservation, ref, stockName, sourceTradeDate, runSlot,
                        periodType, window.key, frozen, e);
            }
            record.setStatus("ERROR");
            record.setStance("分析失败");
            record.setTone("red");
            record.setConfidence(0.0);
            record.setTitle(projectionStockName + " 技术面分析失败");
            record.setConclusion(failureConclusion(e));
            record.setEvidenceJson("[]");
            record.setMissingDataJson(projectionMissingDataJson);
            record.setMainForceIntentJson(intentValidation.intent == null ? null : intentValidation.intent.toJSONString());
            record.setMainForceIntentStatus(intentValidation.status);
            record.setMainForceIntentWarningsJson(JSON.toJSONString(intentValidation.warnings));
            record.setDataSnapshotJson(projectionDataSnapshotJson);
            record.setRawResponse(bridgeResult == null ? "" : text(bridgeResult.answerText, ""));
            record.setBridgeTargetId(bridgeResult == null ? null : bridgeResult.targetId);
            record.setConversationUrl(bridgeResult == null ? null : bridgeResult.finalUrl);
            record.setErrorMessage(diagnosticMessage(e));
            record.setContextCharsDelta(projectionPromptChars + (bridgeResult == null ? 0 : text(bridgeResult.answerText, "").length()));
            String taskFailureStatus = analysisTaskService.shouldMarkInvalid(e) ? "INVALID_RESPONSE"
                    : (analysisTaskService.shouldMarkUnknown(e) ? "UNKNOWN" : "FAILED");
            StockWatchTechnicalAnalysis failedRecord = analysisProjectionService.projectFailure(
                    taskReservation, record, projectionSession,
                    taskFailureStatus,
                    bridgeResult == null ? null : bridgeResult.answerText,
                    bridgeResult == null ? null : bridgeResult.contentStatus,
                    bridgeResult == null ? null : bridgeResult.completionReason,
                    diagnosticMessage(e),
                    bridgeResult == null ? projectionSession.getBridgeTargetId() : bridgeResult.targetId,
                    bridgeResult == null ? projectionSession.getConversationUrl() : bridgeResult.finalUrl);
            analysisProjectionService.updateFailureTelemetryBestEffort(
                    projectionSession, failedRecord,
                    bridgeResult == null ? projectionSession.getBridgeTargetId() : bridgeResult.targetId,
                    bridgeResult == null ? projectionSession.getConversationUrl() : bridgeResult.finalUrl,
                    diagnosticMessage(e));
            return failedRecord;
        }
    }

    private StockWatchTechnicalAnalysis recoverPreparedTask(Reservation reservation) {
        StockWatchAnalysisTaskIdentity identity = taskIdentity(reservation);
        return projectPreparedTask(reservation, identity.ref, identity.stockName, identity.sourceDate,
                identity.runSlot, identity.periodType, identity.window, null, null, false,
                "[]", "{}", "{}", "{}", "{}", "[]", null, null);
    }

    private StockWatchTechnicalAnalysis recoverPreparedTaskWithTerminalFallback(Reservation reservation) {
        try {
            return recoverPreparedTask(reservation);
        } catch (Exception recoveryError) {
            Reservation latest = analysisTaskService.find(reservation.getTask().getSubmissionKey());
            if (latest == null || !"PROJECTION_FAILED".equals(latest.getTask().getStatus())) throw recoveryError;
            StockWatchAnalysisTaskIdentity identity = taskIdentity(latest);
            FrozenProjection frozen = null;
            try {
                frozen = restoreFrozenProjection(latest);
            } catch (Exception ignored) {
            }
            return persistInvalidReplay(latest, identity.ref, identity.stockName, identity.sourceDate,
                    identity.runSlot, identity.periodType, identity.window.key, frozen,
                    new IllegalStateException("reconcile_projection_retry_failed:" + diagnosticMessage(recoveryError),
                            recoveryError));
        }
    }

    private StockWatchTechnicalAnalysis persistAbandonedTaskFromIdentity(Reservation reservation,
                                                                         String terminalStatus,
                                                                         String error) {
        StockWatchAnalysisTaskIdentity identity = taskIdentity(reservation);
        return persistAbandonedTask(reservation, identity.ref, identity.stockName, identity.sourceDate,
                identity.runSlot, identity.periodType, identity.window.key, terminalStatus, error);
    }

    private StockWatchAnalysisTaskIdentity taskIdentity(Reservation reservation) {
        String symbol = firstNonBlank(reservation.getTask().getSymbol(), "UNKNOWN");
        String market = firstNonBlank(reservation.getTask().getMarket(), "sz");
        String periodType = normalizePeriodType(reservation.getTask().getPeriodType());
        String observationKey = text(reservation.getTask().getObservationKey(), "");
        String[] parts = observationKey.split(":", 3);
        LocalDate sourceDate = parts.length > 1 ? parseLocalDate(parts[1]) : null;
        if (sourceDate == null) sourceDate = LocalDate.now(CHINA_ZONE);
        String runSlot = parts.length > 2 ? parts[2] : periodType;
        StockRef ref = new StockRef(symbol, market);
        return new StockWatchAnalysisTaskIdentity(ref, symbol, sourceDate, runSlot, periodType,
                currentPeriodWindow(periodType, sourceDate));
    }

    private StockWatchTechnicalAnalysis resolveExistingTaskResult(Reservation reservation) {
        if (reservation == null || reservation.isNewSubmission()
                || reservation.hasReusableResponse()
                || (reservation.hasReusableSuccess() && !reservation.isProjectedSuccess())) {
            return null;
        }
        Long taskId = reservation.getTask().getId();
        if (reservation.isProjectedSuccess() || reservation.isTerminalFailure()) {
            StockWatchTechnicalAnalysis existing = analysisMapper.selectByAnalysisTaskId(taskId);
            if (existing != null) return existing;
            throw new IllegalStateException("analysis_task_terminal_without_projection:"
                    + reservation.getTask().getStatus() + ":" + taskId);
        }
        if (reservation.isInProgress()) {
            throw new IllegalStateException("analysis_submission_in_progress:"
                    + reservation.getTask().getStatus() + ":" + taskId);
        }
        throw new IllegalStateException(reservation.refusalReason());
    }

    private StockWatchTechnicalAnalysis persistInvalidReplay(Reservation reservation,
                                                              StockRef ref,
                                                              String currentStockName,
                                                              LocalDate currentSourceDate,
                                                              String currentRunSlot,
                                                              String currentPeriodType,
                                                              String currentPeriodKey,
                                                              FrozenProjection frozen,
                                                              Exception error) {
        Long taskId = reservation.getTask().getId();
        StockWatchTechnicalAnalysis existing = analysisMapper.selectByAnalysisTaskId(taskId);
        if (existing != null) return existing;
        LocalDate sourceDate = currentSourceDate;
        if (frozen != null && StringUtils.hasText(frozen.sourceTradeDate)) {
            try {
                sourceDate = LocalDate.parse(frozen.sourceTradeDate);
            } catch (Exception ignored) {
                // The diagnostic record intentionally falls back to the current canonical source date.
            }
        }
        String diagnostic = diagnosticMessage(error);
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setAnalysisTaskId(taskId);
        record.setSessionId(null);
        record.setSymbol(firstNonBlank(reservation.getTask().getSymbol(), ref.symbol));
        record.setMarket(firstNonBlank(reservation.getTask().getMarket(), ref.market));
        record.setStockName(frozen == null ? currentStockName : firstNonBlank(frozen.stockName, currentStockName));
        record.setAnalysisDate(Date.valueOf(sourceDate));
        record.setRunSlot(frozen == null ? currentRunSlot : firstNonBlank(frozen.runSlot, currentRunSlot));
        record.setPeriodType(frozen == null ? currentPeriodType : firstNonBlank(frozen.periodType, currentPeriodType));
        record.setPeriodKey(frozen == null ? currentPeriodKey : firstNonBlank(frozen.periodKey, currentPeriodKey));
        record.setSourceRecordIds(frozen == null ? "[]" : firstNonBlank(frozen.sourceRecordIds, "[]"));
        record.setEvidenceSignalJson(frozen == null ? "{}" : firstNonBlank(frozen.evidenceSignalJson, "{}"));
        record.setStatus("ERROR");
        record.setStance("重放失败");
        record.setTone("red");
        record.setConfidence(0.0);
        record.setTitle(record.getStockName() + " 技术面响应重放失败");
        record.setConclusion("已阻止损坏或不可恢复的模型响应继续重放，本轮未再次调用模型。");
        record.setEvidenceJson("[]");
        record.setMissingDataJson(frozen == null ? "[]" : firstNonBlank(frozen.missingDataJson, "[]"));
        record.setMainForceIntentStatus("PROJECTION_INVALID");
        record.setMainForceIntentWarningsJson(JSON.toJSONString(Collections.singletonList(diagnostic)));
        record.setDataSnapshotJson(frozen == null ? "{}" : firstNonBlank(frozen.dataSnapshotJson, "{}"));
        record.setRawResponse(text(reservation.getTask().getRawResponse(), ""));
        record.setPromptHash(reservation.getTask().getRequestDigest());
        record.setProvider("deepseek_web_bridge");
        record.setContextCharsDelta(record.getRawResponse().length());
        record.setErrorMessage(diagnostic);
        return analysisProjectionService.projectReplayFailure(reservation, record, diagnostic);
    }

    private StockWatchTechnicalAnalysis persistAbandonedTask(Reservation reservation,
                                                              StockRef ref,
                                                              String stockName,
                                                              LocalDate sourceTradeDate,
                                                              String runSlot,
                                                              String periodType,
                                                              String periodKey,
                                                              String terminalStatus,
                                                              String error) {
        StockWatchTechnicalAnalysis existing = analysisMapper.selectByAnalysisTaskId(reservation.getTask().getId());
        if (existing != null) return existing;
        FrozenProjection frozen = null;
        try {
            frozen = restoreFrozenProjection(reservation);
        } catch (Exception ignored) {
            // A pre-prepare reservation has no frozen projection; the task identity remains sufficient for a diagnostic.
        }
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setAnalysisTaskId(reservation.getTask().getId());
        record.setSessionId(frozen == null ? null : frozen.sessionId);
        record.setSymbol(firstNonBlank(reservation.getTask().getSymbol(), ref.symbol));
        record.setMarket(firstNonBlank(reservation.getTask().getMarket(), ref.market));
        record.setStockName(frozen == null ? stockName : frozen.stockName);
        LocalDate recordDate = sourceTradeDate;
        if (frozen != null) {
            try {
                recordDate = LocalDate.parse(frozen.sourceTradeDate);
            } catch (Exception ignored) {
            }
        }
        record.setAnalysisDate(Date.valueOf(recordDate));
        record.setRunSlot(frozen == null ? runSlot : frozen.runSlot);
        record.setPeriodType(frozen == null ? periodType : frozen.periodType);
        record.setPeriodKey(frozen == null ? periodKey : frozen.periodKey);
        record.setSourceRecordIds(frozen == null ? "[]" : frozen.sourceRecordIds);
        record.setEvidenceSignalJson(frozen == null ? "{}" : frozen.evidenceSignalJson);
        record.setStatus("ERROR");
        record.setStance("任务已安全终止");
        record.setTone("red");
        record.setConfidence(0.0);
        record.setTitle(record.getStockName() + " 技术面任务未完成");
        record.setConclusion("模型提交结果无法安全确认，任务已终止且不会自动再次调用模型。");
        record.setEvidenceJson("[]");
        record.setMissingDataJson(frozen == null ? "[]" : frozen.missingDataJson);
        record.setMainForceIntentStatus("TASK_" + terminalStatus);
        record.setMainForceIntentWarningsJson(JSON.toJSONString(Collections.singletonList(error)));
        record.setDataSnapshotJson(frozen == null ? "{}" : frozen.dataSnapshotJson);
        record.setRawResponse(text(reservation.getTask().getRawResponse(), ""));
        record.setPromptHash(reservation.getTask().getRequestDigest());
        record.setProvider("SUBMITTED".equals(reservation.getTask().getStatus())
                ? "deepseek_web_bridge" : "local_task_guard");
        record.setErrorMessage(error);
        record.setContextCharsDelta(record.getRawResponse().length());
        return analysisProjectionService.projectAbandoned(reservation, record, terminalStatus, error);
    }

    private boolean isTaskStale(Reservation reservation) {
        if (reservation == null || reservation.getTask() == null) return true;
        java.util.Date anchor = reservation.getTask().getSubmittedAt();
        if (anchor == null) anchor = reservation.getTask().getGmtModify();
        if (anchor == null) anchor = reservation.getTask().getGmtCreate();
        if (anchor == null) return true;
        long staleMillis = Math.max(60, taskStaleAfterSeconds) * 1000L;
        return System.currentTimeMillis() - anchor.getTime() >= staleMillis;
    }

    private int taskResponseContractVersion(Reservation reservation) {
        if (reservation == null || reservation.getTask() == null) return 0;
        JSONObject envelope = parseObject(reservation.getTask().getRequestEnvelopeJson());
        if (envelope == null) return 0;
        int direct = envelope.getIntValue("responseContractVersion");
        if (direct > 0) return direct;
        JSONObject projection = parseObject(text(envelope.get("projectionJson"), ""));
        return projection == null ? 0 : projection.getIntValue("responseContractVersion");
    }

    private String frozenProjectionJson(StockWatchTechnicalSession session,
                                        String stockName,
                                        LocalDate sourceTradeDate,
                                        String runSlot,
                                        String periodType,
                                        String periodKey,
                                        String sourceRecordIds,
                                        String evidenceSignalJson,
                                        String externalEvidenceCatalogJson,
                                        String dataSnapshotJson,
                                        Long originObservationId,
                                        Double sourcePrice,
                                        String promptHash,
                                        int promptChars,
                                        String missingDataJson,
                                        String historyCatalogJson) {
        JSONObject projection = new JSONObject(true);
        projection.put("schema", "stock-analysis-projection-v3");
        projection.put("responseContractVersion", RESPONSE_CONTRACT_VERSION);
        projection.put("sessionId", session == null ? null : session.getId());
        projection.put("stockName", stockName);
        projection.put("sourceTradeDate", sourceTradeDate == null ? null : sourceTradeDate.toString());
        projection.put("runSlot", runSlot);
        projection.put("periodType", periodType);
        projection.put("periodKey", periodKey);
        projection.put("sourceRecordIds", sourceRecordIds);
        projection.put("evidenceSignalJson", evidenceSignalJson);
        projection.put("externalEvidenceCatalogJson", externalEvidenceCatalogJson);
        projection.put("externalEvidenceCatalogDigest", sha256(externalEvidenceCatalogJson));
        projection.put("dataSnapshotJson", dataSnapshotJson);
        projection.put("originObservationId", originObservationId);
        projection.put("sourcePrice", sourcePrice);
        projection.put("promptHash", promptHash);
        projection.put("promptChars", promptChars);
        projection.put("missingDataJson", missingDataJson);
        projection.put("historyCatalogJson", historyCatalogJson);
        projection.put("historyCatalogDigest", sha256(historyCatalogJson));
        return projection.toJSONString();
    }

    private FrozenProjection restoreFrozenProjection(Reservation reservation) {
        if (reservation == null || reservation.getTask() == null) {
            throw new IllegalStateException("analysis_projection_task_required");
        }
        JSONObject envelope = parseObject(reservation.getTask().getRequestEnvelopeJson());
        if (envelope == null) {
            throw new IllegalStateException("analysis_projection_envelope_invalid");
        }
        String projectionJson = text(envelope.get("projectionJson"), "");
        String expectedProjectionDigest = text(envelope.get("projectionDigest"), "");
        if (!StringUtils.hasText(projectionJson) || !StringUtils.hasText(expectedProjectionDigest)
                || !sha256(projectionJson).equals(expectedProjectionDigest)) {
            throw new IllegalStateException("analysis_projection_envelope_digest_mismatch");
        }
        JSONObject projection = parseObject(projectionJson);
        if (projection == null || !"stock-analysis-projection-v3".equals(projection.getString("schema"))) {
            throw new IllegalStateException("analysis_projection_envelope_schema_invalid");
        }
        FrozenProjection frozen = new FrozenProjection();
        frozen.responseContractVersion = projection.getIntValue("responseContractVersion");
        if (!SUPPORTED_RESPONSE_CONTRACTS.contains(frozen.responseContractVersion)) {
            throw new IllegalStateException("analysis_projection_response_contract_unsupported:"
                    + frozen.responseContractVersion);
        }
        frozen.sessionId = projection.getLong("sessionId");
        frozen.stockName = requiredProjectionText(projection, "stockName");
        frozen.sourceTradeDate = requiredProjectionText(projection, "sourceTradeDate");
        frozen.runSlot = requiredProjectionText(projection, "runSlot");
        frozen.periodType = requiredProjectionText(projection, "periodType");
        frozen.periodKey = requiredProjectionText(projection, "periodKey");
        frozen.sourceRecordIds = requiredProjectionText(projection, "sourceRecordIds");
        frozen.evidenceSignalJson = requiredProjectionText(projection, "evidenceSignalJson");
        frozen.externalEvidenceCatalogJson = requiredProjectionText(projection, "externalEvidenceCatalogJson");
        String expectedExternalEvidenceCatalogDigest = requiredProjectionText(
                projection, "externalEvidenceCatalogDigest");
        if (!sha256(frozen.externalEvidenceCatalogJson).equals(expectedExternalEvidenceCatalogDigest)) {
            throw new IllegalStateException("analysis_projection_external_evidence_catalog_digest_mismatch");
        }
        frozen.dataSnapshotJson = requiredProjectionText(projection, "dataSnapshotJson");
        frozen.originObservationId = projection.getLong("originObservationId");
        frozen.sourcePrice = projection.getDouble("sourcePrice");
        frozen.promptHash = requiredProjectionText(projection, "promptHash");
        frozen.promptChars = projection.getIntValue("promptChars");
        frozen.missingDataJson = requiredProjectionText(projection, "missingDataJson");
        frozen.historyCatalogJson = requiredProjectionText(projection, "historyCatalogJson");
        String expectedHistoryCatalogDigest = requiredProjectionText(projection, "historyCatalogDigest");
        if (!sha256(frozen.historyCatalogJson).equals(expectedHistoryCatalogDigest)) {
            throw new IllegalStateException("analysis_projection_history_catalog_digest_mismatch");
        }
        if (frozen.sessionId == null || frozen.promptChars <= 0 || frozen.sourcePrice == null
                || parseObject(frozen.evidenceSignalJson) == null
                || parseObject(frozen.externalEvidenceCatalogJson) == null
                || parseObject(frozen.dataSnapshotJson) == null
                || parseObject(frozen.historyCatalogJson) == null
                || !isJsonArray(frozen.sourceRecordIds)
                || !isJsonArray(frozen.missingDataJson)) {
            throw new IllegalStateException("analysis_projection_envelope_fields_invalid");
        }
        try {
            LocalDate.parse(frozen.sourceTradeDate);
        } catch (Exception invalidDate) {
            throw new IllegalStateException("analysis_projection_source_date_invalid", invalidDate);
        }
        if (!frozen.promptHash.equals(reservation.getTask().getRequestDigest())) {
            throw new IllegalStateException("analysis_projection_prompt_digest_mismatch");
        }
        if (StringUtils.hasText(reservation.getTask().getPeriodType())
                && !frozen.periodType.equalsIgnoreCase(reservation.getTask().getPeriodType())) {
            throw new IllegalStateException("analysis_projection_period_mismatch");
        }
        return frozen;
    }

    private String requiredProjectionText(JSONObject projection, String field) {
        String value = projection == null ? "" : projection.getString(field);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("analysis_projection_field_missing:" + field);
        }
        return value;
    }

    private boolean isJsonArray(String raw) {
        try {
            return JSON.parseArray(raw) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private StockWatchTechnicalAnalysis persistDataUnavailable(StockRef ref,
                                                               String stockName,
                                                               LocalDate sourceTradeDate,
                                                               String runSlot,
                                                               String periodType,
                                                               PeriodWindow window,
                                                               EastmoneyMarketDataService.MarketDataBundle marketData,
                                                               Map<String, Object> evidenceSignals) {
        List<String> missing = new ArrayList<>();
        missing.addAll(marketData.getRequiredMissing());
        missing.addAll(marketData.getOptionalMissing());
        String reason = "预测必需数据未就绪：" + String.join("；", marketData.getRequiredMissing());
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setSessionId(null);
        record.setSymbol(ref.symbol);
        record.setMarket(ref.market);
        record.setStockName(stockName);
        record.setAnalysisDate(Date.valueOf(sourceTradeDate));
        record.setRunSlot(runSlot);
        record.setPeriodType(periodType);
        record.setPeriodKey(window.key);
        record.setSourceRecordIds("[]");
        record.setEvidenceSignalJson(JSON.toJSONString(evidenceSignals));
        record.setStatus("DATA_UNAVAILABLE");
        record.setStance("数据未就绪");
        record.setTone("amber");
        record.setConfidence(0.0);
        record.setTitle(stockName + " 技术面数据未就绪");
        record.setConclusion("本轮未调用模型：" + reason);
        record.setEvidenceJson("[]");
        record.setMissingDataJson(JSON.toJSONString(missing));
        record.setMainForceIntentStatus("NOT_EVALUATED");
        record.setMainForceIntentWarningsJson(JSON.toJSONString(Collections.singletonList("model_not_called")));
        record.setDataSnapshotJson(marketData.toJson());
        record.setRawResponse("");
        record.setPromptHash(sha256(marketData.toJson()));
        record.setProvider("local_data_gate");
        record.setContextCharsDelta(0);
        record.setErrorMessage(reason);
        log.info("stock-watch technical analysis data unavailable symbol={}.{} sourceDate={} slot={} "
                        + "missing={} modelCalled=false persisted=false",
                ref.symbol, ref.market, sourceTradeDate, runSlot, marketData.getRequiredMissing());
        return record;
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

    private BridgeResult callDeepSeekBridge(String prompt,
                                            StockWatchTechnicalSession session,
                                            boolean forceNewSession,
                                            Reservation reservation) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", prompt);
        body.put("sessionKey", session.getSessionKey());
        body.put("forceNewSession", forceNewSession);
        body.put("closeTargetAfter", false);
        body.put("expectedJson", true);
        body.put("mode", "expert");
        body.put("purpose", "stock-analysis-v1");
        body.put("requestDigest", reservation.getTask().getRequestDigest());
        body.put("submissionKey", reservation.getTask().getSubmissionKey());

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

    private BridgeLedgerLookup lookupBridgeLedger(Reservation reservation) {
        HttpUrl base = HttpUrl.parse(bridgeUrl);
        if (base == null || reservation == null || reservation.getTask() == null) {
            return BridgeLedgerLookup.status("UNAVAILABLE", "bridge_url_or_task_missing");
        }
        HttpUrl statusUrl = base.newBuilder()
                .encodedPath("/request-status")
                .query(null)
                .addQueryParameter("submissionKey", reservation.getTask().getSubmissionKey())
                .addQueryParameter("requestDigest", reservation.getTask().getRequestDigest())
                .build();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.max(5, connectTimeoutSeconds), TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(statusUrl).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (response.code() == 404) return BridgeLedgerLookup.status("NOT_FOUND", null);
            JSONObject payload = JSON.parseObject(body);
            String ledgerStatus = text(payload == null ? null : payload.get("ledgerStatus"), "UNAVAILABLE")
                    .toUpperCase(Locale.ROOT);
            if (!response.isSuccessful() || payload == null || !payload.getBooleanValue("ok")) {
                return BridgeLedgerLookup.status(ledgerStatus,
                        text(payload == null ? null : payload.get("message"), ""));
            }
            JSONObject result = payload.getJSONObject("result");
            String answerText = text(result == null ? null : result.get("answerText"), "");
            if (!StringUtils.hasText(answerText)) {
                return BridgeLedgerLookup.status("SUCCESS_WITHOUT_RESPONSE", null);
            }
            return BridgeLedgerLookup.success(BridgeResult.cached(answerText,
                    text(result.get("contentStatus"), ""),
                    text(result.get("completionReason"), ""),
                    text(result.get("targetId"), ""),
                    text(result.get("finalUrl"), text(result.get("conversationUrl"), ""))));
        } catch (Exception transport) {
            return BridgeLedgerLookup.status("UNREACHABLE", diagnosticMessage(transport));
        }
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
                               Map<String, Object> promptMarketData,
                               Map<String, Object> evidenceSignals,
                               JSONObject externalEvidenceCatalog,
                               JSONObject historyCatalog,
                               List<Map<String, Object>> recentTwoWeekLedger,
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
        payload.put("responseContractVersion", RESPONSE_CONTRACT_VERSION);
        payload.put("marketData", promptMarketData);
        payload.put("evidenceSignals", evidenceSignals);
        payload.put("externalEvidenceCatalog", externalEvidenceCatalog);
        payload.put("sourceDailyAnalyses", sourceRecords.stream()
                .map(this::toPromptSourceRecordPayload)
                .collect(Collectors.toList()));
        payload.put("recentSamePeriodAnalysis", recent);
        payload.put("recentTwoWeekAnalysisLedger", recentTwoWeekLedger);
        payload.put("historyEvidenceCatalog", historyCatalog);
        payload.put("recentTwoWeekCoverage", Map.of(
                "lookbackDays", 14,
                "includedCount", recentTwoWeekLedger.size(),
                "selectionPolicy", "latest_success_per_date_slot_then_chronological"
        ));
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
                "本轮行情、K线摘要、资金流、估值、财务摘要、公告、股东户数和本地技术指标在marketData中；最近新闻在externalEvidenceCatalog中；不得用会话历史覆盖本轮结构化数据。",
                "本轮股票分析以marketData为唯一事实源，不需要额外联网搜索；如网页专业模式自动检索，也只能作为辅助线索，禁止覆盖marketData事实。",
                "marketData.anchorFacts是程序校验后的事实锚点。涉及现价、涨跌幅、成交额、资金净额、资金占比和技术指标时必须原样引用anchorFacts，禁止自行换算单位或重算比例。",
                "externalEvidenceCatalog是本轮实际发送给你的紧凑外部证据目录，目录外新闻不得引用；marketData.shareholderCounts是股东户数变化线索。若相关字段为空，只能在missingData复制dataQuality.requiredMissing或optionalMissing里的对应项。",
                "marketData.dataPacking说明本轮为节省上下文已截取最近序列；完整原始行情快照由系统另存，不要抱怨未提供更早原始明细。",
                "evidenceSignals是程序从marketData派生的资金流、价量、位置和完整证据原子；服务器会独立计算可信画像，你的主力判断只作为审计假设，不能覆盖服务器画像。",
                "marketData.riskPatternObservations是程序计算的R01-R04事实；historyEvidenceCatalog是服务端冻结的历史归因真值。不得把marketData.factObservationReceipt.testVerdicts当成完整历史账本。",
                "R01-R04只证明可观察的价量风险结构，不能证明操纵、坐庄、对倒、账户组或任何主体的真实身份与违法意图。",
                "recentTwoWeekAnalysisLedger包含最近两周逐slot分析；请比较每次假设与后续事实如何支持、削弱或证伪，并据此缩小本轮待验证范围，但旧记录的evidenceQuality和narrowingEligible必须原样尊重。",
                "responseContractVersion固定为6。mainForceIntent只是审计假设，字段为positioningIntent, executionTactic, confidence, assumptions, risk, watchPoints, historyAssessment。",
                "positioningIntent只能取ACCUMULATE/DISTRIBUTE/NEUTRAL/UNKNOWN；executionTactic只能取PULL_UP/WASH/SUPPORT/SUPPRESS/ROTATE/NONE/UNKNOWN。",
                "新闻只能作为背景，不得把新闻与压价、建仓、出货等动机写成已证实事实；不要输出服务器证据原子之外的新数值。",
                "主力假设的自由文本不会进入普通界面、邮件或后续可信历史；服务器只保留它用于审计。",
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

    private List<Map<String, Object>> recentTwoWeekLedger(StockRef ref,
                                                           LocalDate endDate) {
        if (endDate == null) return Collections.emptyList();
        List<StockWatchTechnicalAnalysis> rows = analysisMapper.selectRecentSuccessfulSlotsByDateRange(
                ref.symbol, ref.market, Date.valueOf(endDate.minusDays(13)), Date.valueOf(endDate), "DAY", 100);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        Map<String, StockWatchTechnicalAnalysis> unique = new LinkedHashMap<>();
        for (StockWatchTechnicalAnalysis row : rows) {
            String key = toLocalDate(row.getAnalysisDate()) + ":" + text(row.getRunSlot(), "") + ":" + text(row.getPeriodType(), "DAY");
            unique.putIfAbsent(key, row);
        }
        List<StockWatchTechnicalAnalysis> chronological = new ArrayList<>(unique.values());
        Collections.reverse(chronological);
        return chronological.stream().map(this::toPromptRecordPayload).collect(Collectors.toList());
    }

    private JSONObject buildHistoryEvidenceCatalog(StockRef ref,
                                                    LocalDate endDate,
                                                    List<Map<String, Object>> ledger) {
        Map<String, List<StockWatchIntentTest>> testsByClaim = factPersistenceService
                .historyTests(ref.symbol, ref.market).stream()
                .filter(test -> StringUtils.hasText(test.getClaimRef()))
                .collect(Collectors.groupingBy(StockWatchIntentTest::getClaimRef,
                        LinkedHashMap::new, Collectors.toList()));
        JSONArray entries = new JSONArray();
        for (Map<String, Object> historical : ledger == null ? Collections.<Map<String, Object>>emptyList() : ledger) {
            Long analysisId = longNumber(historical.get("id"));
            StockWatchTechnicalAnalysis source = analysisId == null ? null : analysisMapper.selectById(analysisId);
            Long taskId = source == null ? null : source.getAnalysisTaskId();
            JSONObject claim = source == null ? null : parseObject(source.getMainForceIntentJson());
            String claimRef = taskId == null ? "" : "analysisTask:" + taskId + ":mainForceIntent";
            List<StockWatchIntentTest> tests = StringUtils.hasText(claimRef)
                    ? testsByClaim.getOrDefault(claimRef, Collections.emptyList())
                    : Collections.emptyList();
            JSONObject entry = new JSONObject(true);
            entry.put("historyRef", "analysis:" + analysisId);
            entry.put("analysisId", analysisId);
            entry.put("analysisTaskId", taskId);
            entry.put("claimRef", StringUtils.hasText(claimRef) ? claimRef : null);
            entry.put("claimDigest", historyClaimDigest(historical, claim));
            entry.put("claim", compactIntentClaim(claim));
            String evidenceQuality = claim != null && claim.getIntValue("contractVersion") == 6
                    ? "SERVER_PROFILE_V1"
                    : (taskId == null ? "LEGACY_UNVERIFIED" : "LEGACY_PATH_MEMBERSHIP_ONLY");
            entry.put("evidenceQuality", evidenceQuality);
            entry.put("narrowingEligible", false);
            String attributionStatus = taskId == null || claim == null || claim.isEmpty()
                    ? "LEGACY_UNBOUND"
                    : (tests.isEmpty() ? "NO_TEST_DEFINITION" : "ATTRIBUTABLE");
            entry.put("attributionStatus", attributionStatus);
            JSONArray testPayloads = new JSONArray();
            for (StockWatchIntentTest test : tests) {
                JSONObject testPayload = new JSONObject(true);
                testPayload.put("testRef", "intentTest:" + test.getId());
                testPayload.put("metricPath", test.getMetricPath());
                testPayload.put("operator", test.getComparisonOperator());
                testPayload.put("target", parseJsonValue(test.getTargetValueJson()));
                testPayload.put("verdict", normalizeHistoryVerdict(test.getTestVerdict()));
                testPayload.put("ageCount", value(test.getAgeCount(), 0));
                testPayload.put("observedCount", value(test.getObservedCount(), 0));
                testPayload.put("requiredObservedCount", value(test.getRequiredObservedCount(), 0));
                testPayload.put("lastEvaluatedSourceDate", test.getLastEvaluatedSourceDate());
                testPayloads.add(testPayload);
            }
            entry.put("tests", testPayloads);
            entry.put("aggregateVerdict", aggregateHistoryVerdict(attributionStatus, tests));
            entries.add(entry);
        }
        JSONObject catalog = new JSONObject(true);
        catalog.put("schema", "stock-watch-history-catalog-v1");
        catalog.put("lookbackStart", endDate == null ? null : endDate.minusDays(13).toString());
        catalog.put("lookbackEnd", endDate == null ? null : endDate.toString());
        catalog.put("selectionPolicy", "latest_success_per_date_slot_then_chronological");
        catalog.put("entries", entries);
        catalog.put("catalogDigest", historyCatalogDigest(catalog));
        return catalog;
    }

    private JSONObject compactIntentClaim(JSONObject claim) {
        if (claim == null || claim.isEmpty()) return null;
        JSONObject compact = new JSONObject(true);
        compact.put("intent", text(claim.get("intent"), "UNKNOWN"));
        compact.put("timeWindow", text(claim.get("timeWindow"), "INTRADAY"));
        compact.put("confidence", number(claim.get("confidence"), 0.0));
        return compact;
    }

    private String historyClaimDigest(Map<String, Object> historical, JSONObject claim) {
        JSONObject digestInput = new JSONObject(true);
        digestInput.put("id", historical == null ? null : historical.get("id"));
        digestInput.put("date", historical == null ? null : historical.get("date"));
        digestInput.put("time", historical == null ? null : historical.get("time"));
        digestInput.put("title", historical == null ? null : historical.get("title"));
        digestInput.put("conclusion", historical == null ? null : historical.get("conclusion"));
        digestInput.put("mainForceIntent", compactIntentClaim(claim));
        return sha256(digestInput.toJSONString());
    }

    private Object parseJsonValue(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try {
            return JSON.parse(raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String aggregateHistoryVerdict(String attributionStatus, List<StockWatchIntentTest> tests) {
        if (!"ATTRIBUTABLE".equals(attributionStatus) || tests == null || tests.isEmpty()) {
            return "NOT_EVALUABLE";
        }
        List<String> verdicts = tests.stream()
                .map(test -> normalizeHistoryVerdict(test.getTestVerdict()))
                .collect(Collectors.toList());
        if (verdicts.contains("FALSIFIED")) return "FALSIFIED";
        if (verdicts.stream().allMatch("SUPPORTED"::equals)) return "SUPPORTED";
        if (verdicts.contains("INSUFFICIENT_DATA")) return "INSUFFICIENT_DATA";
        return "UNRESOLVED";
    }

    private String normalizeHistoryVerdict(String raw) {
        String verdict = text(raw, "UNRESOLVED").toUpperCase(Locale.ROOT);
        return Arrays.asList("SUPPORTED", "FALSIFIED", "UNRESOLVED", "INSUFFICIENT_DATA")
                .contains(verdict) ? verdict : "UNRESOLVED";
    }

    private boolean verifyHistoryCatalogDigest(JSONObject catalog) {
        if (catalog == null || !"stock-watch-history-catalog-v1".equals(catalog.getString("schema"))) return false;
        String expected = text(catalog.get("catalogDigest"), "");
        return StringUtils.hasText(expected) && expected.equals(historyCatalogDigest(catalog));
    }

    private String historyCatalogDigest(JSONObject catalog) {
        JSONObject unsigned = new JSONObject(true);
        if (catalog != null) unsigned.putAll(catalog);
        unsigned.remove("catalogDigest");
        return sha256(JSON.toJSONString(canonicalJsonValue(unsigned)));
    }

    private Object canonicalJsonValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getValue() != null) {
                    sorted.put(String.valueOf(entry.getKey()), canonicalJsonValue(entry.getValue()));
                }
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : (List<?>) value) normalized.add(canonicalJsonValue(item));
            return normalized;
        }
        return value;
    }

    private JSONObject buildExternalEvidenceCatalog(Map<String, Object> raw) {
        JSONObject catalog = new JSONObject(true);
        catalog.put("schema", "stock-watch-external-evidence-v1");
        catalog.put("responseContractVersion", RESPONSE_CONTRACT_VERSION);
        JSONObject entriesByRef = new JSONObject(true);
        for (Object value : compactNews(raw == null ? null : raw.get("latestNews"), 6)) {
            JSONObject source = asJsonObject(value);
            if (source == null || !StringUtils.hasText(text(source.get("title"), ""))) continue;
            JSONObject entry = new JSONObject(true);
            entry.put("kind", "NEWS");
            putCatalogScalar(entry, "title", source.get("title"));
            putCatalogScalar(entry, "showTime", source.get("showTime"));
            putCatalogScalar(entry, "source", source.get("source"));
            putCatalogScalar(entry, "summary", source.get("summary"));
            putCatalogScalar(entry, "url", source.get("url"));
            String ref = "news_" + sha256(JSON.toJSONString(canonicalJsonValue(entry))).substring(0, 16);
            entry.put("ref", ref);
            entriesByRef.put(ref, entry);
        }
        catalog.put("entriesByRef", entriesByRef);
        catalog.put("catalogDigest", externalEvidenceCatalogDigest(catalog));
        return catalog;
    }

    private void putCatalogScalar(JSONObject target, String field, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (StringUtils.hasText(text)) target.put(field, text);
    }

    private String externalEvidenceCatalogDigest(JSONObject catalog) {
        if (catalog == null) return "";
        JSONObject unsigned = parseObject(catalog.toJSONString());
        if (unsigned == null) return "";
        unsigned.remove("catalogDigest");
        return sha256(JSON.toJSONString(canonicalJsonValue(unsigned)));
    }

    private boolean verifyExternalEvidenceCatalogDigest(JSONObject catalog) {
        if (catalog == null || !"stock-watch-external-evidence-v1".equals(catalog.getString("schema"))
                || !SUPPORTED_RESPONSE_CONTRACTS.contains(catalog.getIntValue("responseContractVersion"))) {
            return false;
        }
        String expected = text(catalog.get("catalogDigest"), "");
        return StringUtils.hasText(expected) && expected.equals(externalEvidenceCatalogDigest(catalog));
    }

    private Set<String> externalEvidenceCitationPaths(JSONObject catalog) {
        Set<String> paths = new LinkedHashSet<>();
        if (!verifyExternalEvidenceCatalogDigest(catalog)) return paths;
        JSONObject entries = catalog.getJSONObject("entriesByRef");
        if (entries == null) return paths;
        for (String ref : entries.keySet()) {
            JSONObject entry = entries.getJSONObject(ref);
            if (entry == null || !ref.equals(text(entry.get("ref"), ""))) continue;
            for (String field : Arrays.asList("title", "showTime", "source", "summary", "url")) {
                if (StringUtils.hasText(text(entry.get(field), ""))) {
                    paths.add("externalEvidenceCatalog.entriesByRef." + ref + "." + field);
                }
            }
        }
        return paths;
    }

    private boolean externalCatalogMatchesCitationPaths(JSONObject catalog, Map<String, Object> evidenceSignals) {
        Set<String> expected = externalEvidenceCitationPaths(catalog);
        Set<String> actual = citationPathSet(evidenceSignals).stream()
                .filter(path -> path.startsWith("externalEvidenceCatalog."))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return expected.equals(actual);
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
        copyIfPresent(raw, payload, "riskPatternObservations");
        copyIfPresent(raw, payload, "riskPatternGuidance");
        copyIfPresent(raw, payload, "factObservationReceipt");

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
        packing.put("latestNewsLocation", "externalEvidenceCatalog");
        packing.put("shareholderCountsLatestCount", 3);
        payload.put("dataPacking", packing);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object raw) {
        if (!(raw instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (item instanceof Map) result.add((Map<String, Object>) item);
        }
        return result;
    }

    private Map<String, Object> buildEvidenceSignals(Map<String, Object> raw, String periodType) {
        JSONObject externalEvidenceCatalog = buildExternalEvidenceCatalog(raw);
        return buildEvidenceSignals(raw, periodType, "SH", externalEvidenceCatalog);
    }

    private Map<String, Object> buildEvidenceSignals(Map<String, Object> raw,
                                                     String periodType,
                                                     JSONObject externalEvidenceCatalog) {
        return buildEvidenceSignals(raw, periodType, "SH", externalEvidenceCatalog);
    }

    private Map<String, Object> buildEvidenceSignals(Map<String, Object> raw,
                                                     String periodType,
                                                     String market,
                                                     JSONObject externalEvidenceCatalog) {
        Map<String, Object> signals = new LinkedHashMap<>();
        List<String> citationPaths = new ArrayList<>();
        Map<String, Object> fundFlow = buildFundFlowEvidence(raw, market, citationPaths);
        Map<String, Object> priceVolume = buildPriceVolumeSignal(raw, citationPaths);
        Map<String, Object> position = buildPositionSignal(raw, citationPaths);
        citationPaths.addAll(externalEvidenceCitationPaths(externalEvidenceCatalog));
        Map<String, Object> coverage = buildEvidenceCoverage(fundFlow, priceVolume, position);
        signals.put("schema", "stock_watch_evidence_signals_v2");
        signals.put("atomCatalogVersion", 2);
        signals.put("roleProfileVersion", 1);
        signals.put("periodType", normalizePeriodType(periodType));
        JSONObject quote = asJsonObject(raw == null ? null : raw.get("quote"));
        if (quote != null && quote.get("timestamp") != null) {
            signals.put("sourceTimestamp", quote.get("timestamp"));
        }
        signals.put("fundFlowEvidence", fundFlow);
        signals.put("priceVolumeSignal", priceVolume);
        signals.put("positionSignal", position);
        signals.put("evidenceCoverage", coverage);
        Map<String, Object> atomCatalog = buildEvidenceAtomCatalog(fundFlow, priceVolume, position);
        signals.put("atomCatalog", atomCatalog);
        signals.put("positioningProfile", evaluatePositioningProfile(atomCatalog));
        signals.put("citationPaths", citationPaths);
        signals.put("usagePolicy", "可信主力画像由服务器完整原子目录确定；模型主力文本仅进入审计，不参与可信输出。");
        return signals;
    }

    private Map<String, Object> buildFundFlowEvidence(Map<String, Object> raw,
                                                      String market,
                                                      List<String> citationPaths) {
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
        Map<String, Object> closeDelta = exactCloseFlowDelta(intradayFlow, market);
        Double tailMainDelta = number(closeDelta.get("mainDeltaYuan"), null);
        Double tailSuperLargeDelta = number(closeDelta.get("superLargeDeltaYuan"), null);
        Double tailLargeDelta = number(closeDelta.get("largeDeltaYuan"), null);
        Double selectedMain = firstNumber(anchorMain, latestMain, tailMainDelta);
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
        putSignal(signal, "fundFlowEvidence", "tail15MainDeltaYuan", roundNullable(tailMainDelta), citationPaths);
        putSignal(signal, "fundFlowEvidence", "tail15SuperLargeDeltaYuan", roundNullable(tailSuperLargeDelta), citationPaths);
        putSignal(signal, "fundFlowEvidence", "tail15LargeDeltaYuan", roundNullable(tailLargeDelta), citationPaths);
        for (String key : Arrays.asList("startTime", "endTime", "mainStartYuan", "mainEndYuan",
                "superLargeStartYuan", "superLargeEndYuan", "largeStartYuan", "largeEndYuan")) {
            putSignal(signal, "fundFlowEvidence", "tail15" + Character.toUpperCase(key.charAt(0)) + key.substring(1),
                    closeDelta.get(key), citationPaths);
        }
        signal.put("window", "intraday");
        if (anchorMain == null && latestMain == null && tailMainDelta == null) {
            limitations.add("资金流主力净额不可用，不能支撑强主力意图。");
        }
        if (intradayFlow.isEmpty()) {
            limitations.add("intradayFundFlow为空，无法观察盘中资金流形态。");
        }
        String closeDeltaLimitation = text(closeDelta.get("limitation"), "");
        if (StringUtils.hasText(closeDeltaLimitation)) limitations.add(closeDeltaLimitation);
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
        Double ma60 = number(indicators == null ? null : indicators.get("ma60"), null);
        Double macdDif = number(indicators == null ? null : indicators.get("macdDif"), null);
        Double macdDea = number(indicators == null ? null : indicators.get("macdDea"), null);
        Double macdHistogram = number(indicators == null ? null : indicators.get("macdHistogram"), null);
        Double rsi14 = number(indicators == null ? null : indicators.get("rsi14"), null);
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
        putSignal(signal, "positionSignal", "ma60", roundNullable(ma60), citationPaths);
        putSignal(signal, "positionSignal", "macdDif", roundNullable(macdDif), citationPaths);
        putSignal(signal, "positionSignal", "macdDea", roundNullable(macdDea), citationPaths);
        putSignal(signal, "positionSignal", "macdHistogram", roundNullable(macdHistogram), citationPaths);
        putSignal(signal, "positionSignal", "rsi14", roundNullable(rsi14), citationPaths);
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
        boolean hasFundFlow = hasAnyValue(fundFlow, "anchorMainInflowYuan", "latestMainInflowYuan", "tail15MainDeltaYuan");
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

    private Map<String, Object> exactCloseFlowDelta(List<Object> rows, String market) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalizedMarket = text(market, "").toUpperCase(Locale.ROOT);
        LocalTime startTime;
        LocalTime endTime;
        if (Arrays.asList("SH", "SZ", "BJ").contains(normalizedMarket)) {
            startTime = LocalTime.of(14, 45);
            endTime = LocalTime.of(15, 0);
        } else if (Arrays.asList("HK", "HKG").contains(normalizedMarket)) {
            startTime = LocalTime.of(15, 45);
            endTime = LocalTime.of(16, 0);
        } else {
            result.put("limitation", "tail15_close_unsupported_market:" + normalizedMarket);
            return result;
        }
        if (rows == null || rows.isEmpty()) {
            result.put("limitation", "tail15_close_samples_missing");
            return result;
        }
        Map<LocalDateTime, JSONObject> samples = new LinkedHashMap<>();
        LocalDateTime previous = null;
        for (Object value : rows) {
            JSONObject row = asJsonObject(value);
            LocalDateTime timestamp = parseFlowTimestamp(row == null ? null : row.get("time"));
            if (timestamp == null) {
                result.put("limitation", "tail15_close_timestamp_invalid");
                return result;
            }
            if (previous != null && !timestamp.isAfter(previous)) {
                result.put("limitation", timestamp.equals(previous)
                        ? "tail15_close_duplicate_timestamp" : "tail15_close_out_of_order");
                return result;
            }
            previous = timestamp;
            samples.put(timestamp, row);
        }
        LocalDate tradeDate = previous.toLocalDate();
        LocalDateTime start = LocalDateTime.of(tradeDate, startTime);
        LocalDateTime end = LocalDateTime.of(tradeDate, endTime);
        JSONObject startRow = samples.get(start);
        JSONObject endRow = samples.get(end);
        if (startRow == null || endRow == null) {
            result.put("limitation", "tail15_close_exact_endpoint_missing:" + startTime + "->" + endTime);
            return result;
        }
        String startSource = text(startRow.get("sourceRequestId"), "");
        String endSource = text(endRow.get("sourceRequestId"), "");
        if (StringUtils.hasText(startSource) && StringUtils.hasText(endSource) && !startSource.equals(endSource)) {
            result.put("limitation", "tail15_close_source_request_mismatch");
            return result;
        }
        result.put("startTime", start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        result.put("endTime", end.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        for (String key : Arrays.asList("main", "superLarge", "large")) {
            Double startValue = number(startRow.get(key), null);
            Double endValue = number(endRow.get(key), null);
            if (startValue == null || endValue == null || !Double.isFinite(startValue) || !Double.isFinite(endValue)) {
                result.clear();
                result.put("limitation", "tail15_close_non_finite_value:" + key);
                return result;
            }
            result.put(key + "StartYuan", roundNullable(startValue));
            result.put(key + "EndYuan", roundNullable(endValue));
            result.put(key + "DeltaYuan", roundNullable(endValue - startValue));
        }
        return result;
    }

    private LocalDateTime parseFlowTimestamp(Object raw) {
        String value = text(raw, "").trim();
        if (!StringUtils.hasText(value)) return null;
        for (DateTimeFormatter formatter : Arrays.asList(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Map<String, Object> buildEvidenceAtomCatalog(Map<String, Object> fundFlow,
                                                          Map<String, Object> priceVolume,
                                                          Map<String, Object> position) {
        Map<String, Object> entries = new LinkedHashMap<>();
        Double main = number(fundFlow.get("anchorMainInflowYuan"),
                number(fundFlow.get("latestMainInflowYuan"), null));
        Double ratio = number(fundFlow.get("anchorMainInflowRatioPercent"), null);
        Double change = number(priceVolume.get("changePercent"), null);
        Double close = number(position.get("closeYuan"), null);
        Double ma20 = number(position.get("ma20"), null);
        Double dif = number(position.get("macdDif"), null);
        Double dea = number(position.get("macdDea"), null);
        Double histogram = number(position.get("macdHistogram"), null);
        if (main != null && ratio != null) {
            if (main >= 100_000_000d && ratio >= 2d) putAtom(entries, "FF_MAIN_STRONG_IN", "FUND_FLOW", main, "yuan");
            if (main <= -100_000_000d && ratio <= -2d) putAtom(entries, "FF_MAIN_STRONG_OUT", "FUND_FLOW", main, "yuan");
            if (Math.abs(main) < 100_000_000d && Math.abs(ratio) < 1d) putAtom(entries, "FF_MAIN_BALANCED", "FUND_FLOW", main, "yuan");
        }
        if (change != null) {
            if (change >= 1d) putAtom(entries, "PV_RETURN_UP", "PRICE_VOLUME", change, "percent");
            if (change <= -1d) putAtom(entries, "PV_RETURN_DOWN", "PRICE_VOLUME", change, "percent");
            if (Math.abs(change) < 1d) putAtom(entries, "PV_RETURN_BALANCED", "PRICE_VOLUME", change, "percent");
        }
        if (close != null && ma20 != null) {
            if (close > ma20) putAtom(entries, "POS_ABOVE_MA20", "POSITION", close - ma20, "yuan");
            if (close < ma20) putAtom(entries, "POS_BELOW_MA20", "POSITION", close - ma20, "yuan");
        }
        if (dif != null && dea != null && histogram != null) {
            if (dif > dea && histogram > 0) putAtom(entries, "TECH_MACD_BULL", "TECHNICAL", histogram, "value");
            if (dif < dea && histogram < 0) putAtom(entries, "TECH_MACD_BEAR", "TECHNICAL", histogram, "value");
        }
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("schema", "stock-watch-evidence-atoms-v2");
        catalog.put("entriesById", entries);
        catalog.put("catalogDigest", sha256(JSON.toJSONString(canonicalJsonValue(entries))));
        return catalog;
    }

    private void putAtom(Map<String, Object> entries, String id, String family, Double value, String unit) {
        Map<String, Object> atom = new LinkedHashMap<>();
        atom.put("atomId", id);
        atom.put("family", family);
        atom.put("familyDedupKey", "same-day|" + family + "|" + id.replaceAll("_(IN|OUT|UP|DOWN|BALANCED)$", ""));
        atom.put("value", roundNullable(value));
        atom.put("unit", unit);
        entries.put(id, atom);
    }

    private Map<String, Object> evaluatePositioningProfile(Map<String, Object> atomCatalog) {
        JSONObject catalog = asJsonObject(atomCatalog);
        JSONObject entries = catalog == null ? null : asJsonObject(catalog.get("entriesById"));
        Set<String> ids = entries == null ? Collections.emptySet() : new LinkedHashSet<>(entries.keySet());
        List<String> matched = new ArrayList<>();
        if (ids.contains("FF_MAIN_STRONG_IN") && ids.contains("PV_RETURN_UP")) matched.add("ACCUMULATE");
        if (ids.contains("FF_MAIN_STRONG_OUT") && ids.contains("PV_RETURN_DOWN")) matched.add("DISTRIBUTE");
        if (ids.contains("FF_MAIN_BALANCED") && ids.contains("PV_RETURN_BALANCED")) matched.add("NEUTRAL");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileVersion", 1);
        profile.put("evidenceQuality", "SERVER_PROFILE_V1");
        profile.put("tier", matched.size() == 1 ? "WEAK" : "UNSUPPORTED");
        profile.put("normalizedValue", matched.size() == 1 ? matched.get(0) : "UNKNOWN");
        List<String> support = new ArrayList<>();
        List<String> counters = new ArrayList<>();
        double cap = 0d;
        if (matched.size() == 1) {
            String value = matched.get(0);
            if ("ACCUMULATE".equals(value)) {
                support.addAll(Arrays.asList("FF_MAIN_STRONG_IN", "PV_RETURN_UP"));
                if (ids.contains("POS_BELOW_MA20")) counters.add("POS_BELOW_MA20");
                if (ids.contains("TECH_MACD_BEAR")) counters.add("TECH_MACD_BEAR");
                cap = counters.isEmpty() ? 0.55d : 0.45d;
            } else if ("DISTRIBUTE".equals(value)) {
                support.addAll(Arrays.asList("FF_MAIN_STRONG_OUT", "PV_RETURN_DOWN"));
                if (ids.contains("POS_ABOVE_MA20")) counters.add("POS_ABOVE_MA20");
                if (ids.contains("TECH_MACD_BULL")) counters.add("TECH_MACD_BULL");
                cap = counters.isEmpty() ? 0.55d : 0.45d;
            } else {
                support.addAll(Arrays.asList("FF_MAIN_BALANCED", "PV_RETURN_BALANCED"));
                cap = 0.35d;
            }
        }
        profile.put("confidence", cap);
        profile.put("supportAtomIds", support);
        profile.put("counterAtomIds", counters);
        profile.put("reasonCode", matched.size() == 1 ? "PROFILE_MATCHED_NON_ACTIONABLE" : "NO_UNIQUE_ELIGIBLE_PROFILE");
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("narrowingEligible", false);
        permissions.put("intentTestEligible", false);
        permissions.put("mainForceAlertEligible", false);
        permissions.put("mainForceDeliveryEligible", false);
        profile.put("permissions", permissions);
        return profile;
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

    private IntentValidationResult validateResponseContract(int responseContractVersion,
                                                            JSONObject raw,
                                                            Map<String, Object> evidenceSignals,
                                                            JSONObject externalEvidenceCatalog,
                                                            JSONObject historyCatalog) {
        if (!SUPPORTED_RESPONSE_CONTRACTS.contains(responseContractVersion)) {
            throw new ModelResponseSchemaException("unsupported_response_contract_version:"
                    + responseContractVersion);
        }
        if (!verifyExternalEvidenceCatalogDigest(externalEvidenceCatalog)
                || !externalCatalogMatchesCitationPaths(externalEvidenceCatalog, evidenceSignals)) {
            throw new ModelResponseSchemaException("external_evidence_catalog_invalid");
        }
        return responseContractVersion == 5
                ? validateMainForceIntentV5(raw, evidenceSignals, historyCatalog)
                : validateMainForceIntentV6(raw, evidenceSignals);
    }

    private IntentValidationResult validateMainForceIntent(JSONObject raw,
                                                           Map<String, Object> evidenceSignals,
                                                           JSONObject historyCatalog) {
        return validateMainForceIntentV5(raw, evidenceSignals, historyCatalog);
    }

    private IntentValidationResult validateMainForceIntentV6(JSONObject raw,
                                                             Map<String, Object> evidenceSignals) {
        List<String> warnings = new ArrayList<>();
        JSONObject profile = asJsonObject(evidenceSignals == null ? null : evidenceSignals.get("positioningProfile"));
        JSONObject atomCatalog = asJsonObject(evidenceSignals == null ? null : evidenceSignals.get("atomCatalog"));
        if (profile == null || atomCatalog == null
                || evidenceSignals == null
                || number(evidenceSignals.get("atomCatalogVersion"), 0d).intValue() != 2
                || number(evidenceSignals.get("roleProfileVersion"), 0d).intValue() != 1) {
            return IntentValidationResult.invalid("v6_server_profile_missing_or_invalid");
        }
        JSONObject intent = new JSONObject(true);
        intent.put("contractVersion", 6);
        intent.put("evidenceQuality", "SERVER_PROFILE_V1");
        intent.put("intent", text(profile.get("normalizedValue"), "UNKNOWN"));
        intent.put("timeWindow", "INTRADAY");
        intent.put("confidence", number(profile.get("confidence"), 0d));
        intent.put("positioningEvaluation", profile);
        JSONObject tactic = new JSONObject(true);
        tactic.put("normalizedValue", "UNKNOWN");
        tactic.put("tier", "UNSUPPORTED");
        tactic.put("confidence", 0d);
        tactic.put("reasonCode", "NO_LAUNCH_TACTIC_PROFILE");
        tactic.put("permissions", profile.get("permissions"));
        intent.put("executionTacticEvaluation", tactic);
        intent.put("evidence", toJsonArray(renderProfileAtoms(profile, atomCatalog, "supportAtomIds")));
        intent.put("counterEvidence", toJsonArray(renderProfileAtoms(profile, atomCatalog, "counterAtomIds")));
        intent.put("assumptions", new JSONArray());
        intent.put("risk", "该画像仅为服务器确定性弱证据分类，不构成行动信号。");
        intent.put("watchPoints", new JSONArray());
        intent.put("narrowingEligible", false);
        if (raw != null && !raw.isEmpty()) warnings.add("model_main_force_fields_stored_in_raw_audit_only");
        return IntentValidationResult.valid(intent, warnings);
    }

    private List<String> renderProfileAtoms(JSONObject profile, JSONObject atomCatalog, String field) {
        List<String> rendered = new ArrayList<>();
        JSONObject entries = atomCatalog == null ? null : atomCatalog.getJSONObject("entriesById");
        for (String id : stringValues(profile == null ? null : profile.get(field))) {
            JSONObject atom = entries == null ? null : entries.getJSONObject(id);
            if (atom != null) {
                rendered.add(id + "=" + text(atom.get("value"), "") + " " + text(atom.get("unit"), ""));
            }
        }
        return rendered;
    }

    private void applyTrustedV6Payload(AnalysisPayload payload,
                                       Map<String, Object> evidenceSignals,
                                       String periodType,
                                       LocalDate analysisDate,
                                       String observationSlot) {
        TrustedTechnicalRendererV1.RenderedTechnical rendered = TrustedTechnicalRendererV1.render(
                evidenceSignals, periodType, analysisDate, observationSlot);
        payload.title = rendered.getTitle();
        payload.stance = rendered.getStance();
        payload.tone = rendered.getTone();
        payload.conclusion = rendered.getConclusion();
        payload.confidence = rendered.getConfidence();
        payload.evidence = new ArrayList<>(rendered.getEvidence());
        for (Object value : payload.forecasts) {
            JSONObject forecast = asJsonObject(value);
            if (forecast != null) forecast.put("rationale", "模型方向预测；自由文本理由未通过语义验证，普通界面不展示。");
        }
    }

    private IntentValidationResult validateMainForceIntentV5(JSONObject raw,
                                                             Map<String, Object> evidenceSignals,
                                                             JSONObject historyCatalog) {
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
        String rawWindow = text(raw.get("timeWindow"), "").trim().toUpperCase(Locale.ROOT);
        String timeWindow;
        if ("ONE_WEEK".equals(rawWindow)) {
            timeWindow = "FIVE_DAY";
            warnings.add("mainForceIntent.timeWindow_alias:ONE_WEEK->FIVE_DAY");
        } else {
            timeWindow = normalizeEnum(rawWindow, MAIN_FORCE_WINDOWS, "INTRADAY");
        }
        if (!MAIN_FORCE_WINDOWS.contains(rawWindow) && !"ONE_WEEK".equals(rawWindow)) {
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
        JSONObject historyAttribution = validateHistoryAttribution(
                asJsonObject(raw.get("historyAttribution")), historyCatalog, allowedPaths, problems, warnings);

        normalized.put("intent", intent);
        normalized.put("rawTimeWindow", rawWindow);
        normalized.put("timeWindow", timeWindow);
        normalized.put("confidence", roundNullable(confidence));
        normalized.put("evidence", toJsonArray(evidence));
        normalized.put("counterEvidence", toJsonArray(counterEvidence));
        normalized.put("assumptions", toJsonArray(assumptions));
        normalized.put("risk", risk);
        normalized.put("watchPoints", toJsonArray(watchPoints));
        normalized.put("validatedEvidencePaths", toJsonArray(new ArrayList<>(citedPaths)));
        normalized.put("historyAttribution", historyAttribution);
        normalized.put("disclaimer", "模型推断，非事实标签，不参与异动触发。");
        if (!problems.isEmpty()) {
            return IntentValidationResult.invalid(normalized, problems, warnings);
        }
        return IntentValidationResult.valid(normalized, warnings);
    }

    private JSONObject validateHistoryAttribution(JSONObject raw,
                                                  JSONObject catalog,
                                                  Set<String> allowedCurrentPaths,
                                                  List<String> problems,
                                                  List<String> warnings) {
        JSONObject normalized = new JSONObject(true);
        if (raw == null || raw.isEmpty()) {
            problems.add("mainForceIntent.historyAttribution_missing");
            return normalized;
        }
        if (!verifyHistoryCatalogDigest(catalog)) {
            problems.add("history_catalog_digest_invalid");
            return normalized;
        }
        int contractVersion = raw.getIntValue("contractVersion");
        if (contractVersion != 1) problems.add("historyAttribution.contractVersion_must_be_1");
        String catalogDigest = text(raw.get("catalogDigest"), "");
        String expectedDigest = text(catalog.get("catalogDigest"), "");
        if (!expectedDigest.equals(catalogDigest)) problems.add("historyAttribution.catalogDigest_mismatch");

        Map<String, JSONObject> expectedEntries = new LinkedHashMap<>();
        for (Object value : toObjectList(catalog.get("entries"))) {
            JSONObject entry = asJsonObject(value);
            String historyRef = text(entry == null ? null : entry.get("historyRef"), "");
            if (entry != null && StringUtils.hasText(historyRef)) expectedEntries.put(historyRef, entry);
        }
        List<Object> rawAssessments = toObjectList(raw.get("assessments"));
        if (!(raw.get("assessments") instanceof List)) {
            problems.add("historyAttribution.assessments_must_be_array");
        }
        Map<String, JSONObject> normalizedAssessments = new LinkedHashMap<>();
        Set<String> narrowingEligibleRefs = new LinkedHashSet<>();
        for (int index = 0; index < rawAssessments.size(); index++) {
            JSONObject assessment = asJsonObject(rawAssessments.get(index));
            if (assessment == null) {
                problems.add("historyAttribution.assessments[" + index + "]_invalid");
                continue;
            }
            String historyRef = text(assessment.get("historyRef"), "");
            JSONObject expected = expectedEntries.get(historyRef);
            if (expected == null) {
                problems.add("historyAttribution.assessments[" + index + "]_unknown_historyRef:" + historyRef);
                continue;
            }
            if (normalizedAssessments.containsKey(historyRef)) {
                problems.add("historyAttribution.assessments_duplicate_historyRef:" + historyRef);
                continue;
            }
            String verdict = text(assessment.get("verdict"), "").toUpperCase(Locale.ROOT);
            String expectedVerdict = text(expected.get("aggregateVerdict"), "NOT_EVALUABLE");
            if (!expectedVerdict.equals(verdict)) {
                problems.add("historyAttribution.verdict_mismatch:" + historyRef);
            }
            List<String> testRefs = stringValues(assessment.get("testRefs"));
            List<String> expectedTestRefs = new ArrayList<>();
            for (Object testValue : toObjectList(expected.get("tests"))) {
                JSONObject test = asJsonObject(testValue);
                String testRef = text(test == null ? null : test.get("testRef"), "");
                if (StringUtils.hasText(testRef)) expectedTestRefs.add(testRef);
            }
            if (!expectedTestRefs.equals(testRefs)) {
                problems.add("historyAttribution.testRefs_mismatch:" + historyRef);
            }
            boolean usedForNarrowing = bool(assessment.get("usedForNarrowing"), false);
            boolean eligible = "SUPPORTED".equals(expectedVerdict) || "FALSIFIED".equals(expectedVerdict);
            if (usedForNarrowing && !eligible) {
                problems.add("historyAttribution.ineligible_narrowing:" + historyRef + ":" + expectedVerdict);
            }
            if (usedForNarrowing && eligible) narrowingEligibleRefs.add(historyRef);
            JSONObject normalizedAssessment = new JSONObject(true);
            normalizedAssessment.put("historyRef", historyRef);
            normalizedAssessment.put("verdict", expectedVerdict);
            normalizedAssessment.put("testRefs", toJsonArray(expectedTestRefs));
            normalizedAssessment.put("usedForNarrowing", usedForNarrowing);
            normalizedAssessment.put("implication", text(assessment.get("implication"), ""));
            normalizedAssessments.put(historyRef, normalizedAssessment);
        }
        for (String expectedRef : expectedEntries.keySet()) {
            if (!normalizedAssessments.containsKey(expectedRef)) {
                problems.add("historyAttribution.assessment_missing:" + expectedRef);
            }
        }

        JSONObject rawNarrowing = asJsonObject(raw.get("narrowing"));
        JSONObject normalizedNarrowing = new JSONObject(true);
        if (rawNarrowing == null) {
            problems.add("historyAttribution.narrowing_missing");
        } else {
            requireArrayField(rawNarrowing, "basisHistoryRefs", problems);
            List<String> basisHistoryRefs = stringValues(rawNarrowing.get("basisHistoryRefs"));
            for (String ref : basisHistoryRefs) {
                if (!narrowingEligibleRefs.contains(ref)) {
                    problems.add("historyAttribution.narrowing_invalid_basis:" + ref);
                }
            }
            requireArrayField(rawNarrowing, "currentEvidencePaths", problems);
            List<String> currentPaths = stringValues(rawNarrowing.get("currentEvidencePaths"));
            for (String path : currentPaths) {
                if (!allowedCurrentPaths.contains(path)) {
                    problems.add("historyAttribution.narrowing_invalid_current_path:" + path);
                }
            }
            requireArrayField(rawNarrowing, "retainedIntents", problems);
            requireArrayField(rawNarrowing, "rejectedIntents", problems);
            List<String> retained = normalizeIntentList(rawNarrowing.get("retainedIntents"), problems,
                    "historyAttribution.narrowing.retainedIntents");
            List<String> rejected = normalizeIntentList(rawNarrowing.get("rejectedIntents"), problems,
                    "historyAttribution.narrowing.rejectedIntents");
            Set<String> overlap = new LinkedHashSet<>(retained);
            overlap.retainAll(rejected);
            if (!overlap.isEmpty()) problems.add("historyAttribution.narrowing_intent_overlap:" + overlap);
            String summary = text(rawNarrowing.get("summary"), "");
            if (!StringUtils.hasText(summary)) warnings.add("historyAttribution.narrowing.summary_empty");
            normalizedNarrowing.put("basisHistoryRefs", toJsonArray(basisHistoryRefs));
            normalizedNarrowing.put("currentEvidencePaths", toJsonArray(currentPaths));
            normalizedNarrowing.put("retainedIntents", toJsonArray(retained));
            normalizedNarrowing.put("rejectedIntents", toJsonArray(rejected));
            normalizedNarrowing.put("summary", summary);
        }
        JSONArray normalizedAssessmentArray = new JSONArray();
        normalizedAssessmentArray.addAll(normalizedAssessments.values());
        normalized.put("contractVersion", 1);
        normalized.put("catalogDigest", expectedDigest);
        normalized.put("assessments", normalizedAssessmentArray);
        normalized.put("narrowing", normalizedNarrowing);
        return normalized;
    }

    private List<String> normalizeIntentList(Object raw, List<String> problems, String field) {
        List<String> values = stringValues(raw).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toList());
        if (values.size() != new LinkedHashSet<>(values).size()) problems.add(field + "_duplicates");
        for (String value : values) {
            if (!MAIN_FORCE_INTENTS.contains(value)) problems.add(field + "_invalid:" + value);
        }
        return values;
    }

    private void requireArrayField(JSONObject object, String field, List<String> problems) {
        if (object == null || !(object.get(field) instanceof List)) {
            problems.add("historyAttribution.narrowing." + field + "_must_be_array");
        }
    }

    private List<String> stringValues(Object raw) {
        if (!(raw instanceof List)) return new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Object value : (List<?>) raw) {
            String item = text(value, "");
            if (StringUtils.hasText(item)) values.add(item);
        }
        return values;
    }

    private void validateHistoricalNarrative(AnalysisPayload payload, JSONObject historyCatalog) {
        List<String> narrative = new ArrayList<>();
        narrative.add(text(payload == null ? null : payload.stance, ""));
        narrative.add(text(payload == null ? null : payload.title, ""));
        narrative.add(text(payload == null ? null : payload.conclusion, ""));
        if (payload != null && payload.evidence != null) narrative.addAll(payload.evidence);
        if (payload != null && payload.mainForceIntent != null) {
            narrative.addAll(stringValues(payload.mainForceIntent.get("evidence")));
            narrative.addAll(stringValues(payload.mainForceIntent.get("counterEvidence")));
        }
        java.util.regex.Pattern forbidden = java.util.regex.Pattern.compile(
                "(?i)\\b(SUPPORTED|FALSIFIED|WEAKENED|UNRESOLVED|NOT_EVALUABLE|INSUFFICIENT_DATA)\\b|历史[^，。；]{0,40}(?:支持|证伪|削弱|未决|不可评估)");
        for (String text : narrative) {
            if (forbidden.matcher(text == null ? "" : text).find()) {
                throw new ModelResponseSchemaException("历史裁决只能出现在结构化historyAttribution中");
            }
        }
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
            boolean foundInvalid = false;
            while (matcher.find()) {
                foundPath = true;
                String path = matcher.group();
                if (allowedPaths.contains(path)) {
                    valid.add(path);
                    foundValid = true;
                } else {
                    foundInvalid = true;
                }
            }
            if (!foundPath) {
                String message = "mainForceIntent." + fieldName + "[" + index + "] has no parseable path: " + truncate(item, 80);
                warnings.add(message);
                problems.add("mainForceIntent." + fieldName + "[" + index + "]_missing_valid_evidence_path");
            } else if (!foundValid || foundInvalid) {
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
            return JSON.parseObject(raw, Feature.OrderedField);
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
            payload.put("nextRun", nextRunLabel(ZonedDateTime.now(CHINA_ZONE), fallbackRef.market));
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
        payload.put("nextRun", nextRunLabel(ZonedDateTime.now(CHINA_ZONE), firstNonBlank(session.getMarket(), fallbackRef.market)));
        payload.put("lastRunStatus", session.getLastRunStatus());
        payload.put("lastError", session.getLastError());
        return payload;
    }

    private Map<String, Object> toRecordPayload(StockWatchTechnicalAnalysis record) {
        Map<String, Object> payload = toCompactRecordPayload(record);
        boolean v6Trusted = TrustedTechnicalRendererV1.isTrustedV6(record);
        if (!v6Trusted) payload.put("rawResponse", record.getRawResponse());
        payload.put("rawResponseAuditOnly", v6Trusted);
        payload.put("errorMessage", record.getErrorMessage());
        payload.put("conversationUrl", record.getConversationUrl());
        payload.put("bridgeTargetId", record.getBridgeTargetId());
        JSONObject sourceTrace = sourceTrace(record.getDataSnapshotJson());
        boolean modelTraceable = isTraceableUrl(record.getConversationUrl());
        boolean sourceTraceable = sourceTrace != null && !sourceTrace.isEmpty();
        payload.put("traceable", modelTraceable || sourceTraceable);
        payload.put("modelTraceable", modelTraceable);
        payload.put("sourceTraceable", sourceTraceable);
        payload.put("sourceTrace", sourceTrace);
        payload.put("evidenceSignals", parseObject(record.getEvidenceSignalJson()));
        return payload;
    }

    private JSONObject sourceTrace(String dataSnapshotJson) {
        JSONObject snapshot = parseObject(dataSnapshotJson);
        JSONObject dataQuality = snapshot == null ? null : snapshot.getJSONObject("dataQuality");
        return dataQuality == null ? null : dataQuality.getJSONObject("sourceStatus");
    }

    private Map<String, Object> toCompactRecordPayload(StockWatchTechnicalAnalysis record) {
        TrustedTechnicalRendererV1.RenderedTechnical trustedTechnical = TrustedTechnicalRendererV1.isTrustedV6(record)
                ? TrustedTechnicalRendererV1.render(record) : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", record.getId());
        payload.put("analysisTaskId", record.getAnalysisTaskId());
        payload.put("date", toLocalDate(record.getAnalysisDate()).toString());
        payload.put("time", record.getRunSlot());
        payload.put("periodType", firstNonBlank(record.getPeriodType(), "DAY"));
        payload.put("periodKey", firstNonBlank(record.getPeriodKey(), periodKey(firstNonBlank(record.getPeriodType(), "DAY"), toLocalDate(record.getAnalysisDate()))));
        payload.put("sourceRecordIds", parseEvidence(record.getSourceRecordIds()));
        boolean modelCalled = "deepseek_web_bridge".equals(record.getProvider());
        payload.put("provider", modelCalled ? "DeepSeek 网页" : "本地数据闸门");
        payload.put("providerCode", record.getProvider());
        payload.put("modelCalled", modelCalled);
        payload.put("status", record.getStatus());
        payload.put("stance", trustedTechnical == null ? record.getStance() : trustedTechnical.getStance());
        payload.put("tone", trustedTechnical == null ? record.getTone() : trustedTechnical.getTone());
        payload.put("confidence", formatConfidence(trustedTechnical == null
                ? record.getConfidence() : trustedTechnical.getConfidence()));
        payload.put("title", trustedTechnical == null ? record.getTitle() : trustedTechnical.getTitle());
        payload.put("conclusion", trustedTechnical == null ? record.getConclusion() : trustedTechnical.getConclusion());
        payload.put("evidence", trustedTechnical == null
                ? parseEvidence(record.getEvidenceJson()) : trustedTechnical.getEvidence());
        payload.put("missingData", parseEvidence(record.getMissingDataJson()));
        payload.put("mainForceIntent", parseObject(record.getMainForceIntentJson()));
        JSONObject mainForce = parseObject(record.getMainForceIntentJson());
        payload.put("evidenceQuality", mainForce == null ? "LEGACY_UNVERIFIED"
                : firstNonBlank(mainForce.getString("evidenceQuality"), "LEGACY_PATH_MEMBERSHIP_ONLY"));
        payload.put("mainForceIntentStatus", record.getMainForceIntentStatus());
        payload.put("mainForceIntentWarnings", parseEvidence(record.getMainForceIntentWarningsJson()));
        payload.put("promptHash", record.getPromptHash());
        if (trustedTechnical != null) payload.putAll(trustedTechnical.metadata());
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
        JSONObject mainForce = parseObject(record.getMainForceIntentJson());
        payload.put("mainForceIntent", mainForce);
        payload.put("evidenceQuality", mainForce != null && mainForce.getIntValue("contractVersion") == 6
                ? "SERVER_PROFILE_V1"
                : (record.getAnalysisTaskId() == null ? "LEGACY_UNVERIFIED" : "LEGACY_PATH_MEMBERSHIP_ONLY"));
        payload.put("narrowingEligible", false);
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

    private String currentRunSlot(ZonedDateTime now, boolean allowOutsideTrading, String market) {
        return currentRunSlot(now, allowOutsideTrading, market, null);
    }

    private String currentRunSlot(ZonedDateTime now,
                                  boolean allowOutsideTrading,
                                  String market,
                                  LocalDate sourceTradeDate) {
        LocalTime time = now.toLocalTime();
        List<LocalTime> slots = runSlots(market);
        if (!isTradingSession(now, market) && allowOutsideTrading
                && (sourceTradeDate != null && sourceTradeDate.isBefore(now.toLocalDate())
                || now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY
                || time.isBefore(slots.get(0)))) {
            return slots.get(slots.size() - 1).format(SLOT_FORMATTER);
        }
        return slots.stream()
                .filter(slot -> !slot.isAfter(time))
                .max(Comparator.naturalOrder())
                .orElse(slots.get(0))
                .format(SLOT_FORMATTER);
    }

    private String analysisObservationKey(String periodType, LocalDate sourceTradeDate, String runSlot) {
        return periodType + ":" + sourceTradeDate + ":" + runSlot;
    }

    private String analysisSubmissionKey(StockRef ref, String observationKey) {
        return "stock-analysis-slot-v1:" + ref.market + ":" + ref.symbol + ":" + observationKey;
    }

    private String nextRunLabel(ZonedDateTime now, String market) {
        LocalTime time = now.toLocalTime();
        return runSlots(market).stream()
                .filter(slot -> slot.isAfter(time))
                .findFirst()
                .map(slot -> slot.format(SLOT_FORMATTER))
                .orElse("下个交易日 09:30");
    }

    private boolean isTradingSession(ZonedDateTime now, String market) {
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        LocalTime morningClose = "hk".equalsIgnoreCase(market) ? LocalTime.of(12, 0) : LocalTime.of(11, 30);
        LocalTime afternoonClose = "hk".equalsIgnoreCase(market) ? LocalTime.of(16, 0) : LocalTime.of(15, 0);
        return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(morningClose))
                || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(afternoonClose));
    }

    private List<LocalTime> runSlots(String market) {
        return "hk".equalsIgnoreCase(market) ? HK_RUN_SLOTS : RUN_SLOTS;
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
        StockSymbol normalized = StockSymbol.parse(rawSymbol, rawMarket);
        return new StockRef(normalized.getSymbol(), normalized.getMarket());
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

    private Long longNumber(Object raw) {
        Double value = number(raw, null);
        return value == null ? null : value.longValue();
    }

    private int value(Integer raw, int fallback) {
        return raw == null ? fallback : raw;
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

    private static class StockWatchAnalysisTaskIdentity {
        private final StockRef ref;
        private final String stockName;
        private final LocalDate sourceDate;
        private final String runSlot;
        private final String periodType;
        private final PeriodWindow window;

        private StockWatchAnalysisTaskIdentity(StockRef ref,
                                               String stockName,
                                               LocalDate sourceDate,
                                               String runSlot,
                                               String periodType,
                                               PeriodWindow window) {
            this.ref = ref;
            this.stockName = stockName;
            this.sourceDate = sourceDate;
            this.runSlot = runSlot;
            this.periodType = periodType;
            this.window = window;
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

        private static BridgeResult cached(String answerText,
                                           String contentStatus,
                                           String completionReason,
                                           String targetId,
                                           String finalUrl) {
            BridgeResult result = new BridgeResult();
            result.answerText = answerText;
            result.contentStatus = contentStatus;
            result.completionReason = completionReason;
            result.targetId = targetId;
            result.finalUrl = finalUrl;
            return result;
        }
    }

    private static class BridgeLedgerLookup {
        private final String status;
        private final String error;
        private final BridgeResult result;

        private BridgeLedgerLookup(String status, String error, BridgeResult result) {
            this.status = status;
            this.error = error;
            this.result = result;
        }

        static BridgeLedgerLookup success(BridgeResult result) {
            return new BridgeLedgerLookup("SUCCESS", null, result);
        }

        static BridgeLedgerLookup status(String status, String error) {
            return new BridgeLedgerLookup(status == null ? "UNAVAILABLE" : status, error, null);
        }

        boolean isTerminalWithoutResult() {
            return Arrays.asList("UNKNOWN", "EVICTED_SUCCESS", "DIGEST_MISMATCH", "SUCCESS_WITHOUT_RESPONSE")
                    .contains(status);
        }
    }

    private static class FrozenProjection {
        private int responseContractVersion;
        private Long sessionId;
        private String stockName;
        private String sourceTradeDate;
        private String runSlot;
        private String periodType;
        private String periodKey;
        private String sourceRecordIds;
        private String evidenceSignalJson;
        private String externalEvidenceCatalogJson;
        private String dataSnapshotJson;
        private Long originObservationId;
        private Double sourcePrice;
        private String promptHash;
        private int promptChars;
        private String missingDataJson;
        private String historyCatalogJson;
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
