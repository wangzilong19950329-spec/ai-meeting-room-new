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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StockWatchTechnicalAnalysisService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter SLOT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
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

    private final StockQuoteService stockQuoteService;
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

    public StockWatchTechnicalAnalysisService(StockQuoteService stockQuoteService,
                                              StockWatchListService watchListService,
                                              StockWatchTechnicalSessionMapper sessionMapper,
                                              StockWatchTechnicalAnalysisMapper analysisMapper) {
        this.stockQuoteService = stockQuoteService;
        this.watchListService = watchListService;
        this.sessionMapper = sessionMapper;
        this.analysisMapper = analysisMapper;
    }

    @Scheduled(
            fixedDelayString = "${stock-watch.technical-analysis.scan-interval-ms:1800000}",
            initialDelayString = "${stock-watch.technical-analysis.initial-delay-ms:90000}"
    )
    public void scheduledAnalyze() {
        if (!enabled || !running.compareAndSet(false, true)) {
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
        StockWatchTechnicalAnalysis record = runAnalysis(ref, name, "manual_api", forceNewSession, ignoreTradingHours, periodType);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("record", toRecordPayload(record));
        payload.put("session", toSessionPayload(sessionMapper.selectById(record.getSessionId()), ref, record.getPeriodType()));
        return payload;
    }

    private void runScheduledBatch() {
        ZonedDateTime now = ZonedDateTime.now(CHINA_ZONE);
        lastRunAt = now;
        boolean tradingSession = isTradingSession(now.toLocalTime());
        boolean weeklyDue = isWeeklyDue(now);
        boolean monthlyDue = isMonthlyDue(now);
        if (tradingHoursOnly && !tradingSession && !weeklyDue && !monthlyDue) {
            lastRunStatus = "skipped_non_trading_session";
            return;
        }
        List<StockRef> refs = scheduledRefs();
        for (StockRef ref : refs) {
            try {
                if (tradingSession) {
                    runPeriodIfMissing(ref, now, "DAY", "scheduled", false);
                }
                if (weeklyDue) {
                    runPeriodIfMissing(ref, now, "WEEK", "scheduled_weekly", true);
                }
                if (monthlyDue) {
                    runPeriodIfMissing(ref, now, "MONTH", "scheduled_monthly", true);
                }
            } catch (Exception e) {
                log.warn("stock-watch technical analysis skipped symbol={}.{} error={}",
                        ref.symbol, ref.market, e.getMessage());
            }
        }
        lastRunStatus = "success";
    }

    private void runPeriodIfMissing(StockRef ref,
                                    ZonedDateTime now,
                                    String periodType,
                                    String reason,
                                    boolean ignoreTradingHours) {
        PeriodWindow window = currentPeriodWindow(periodType, now.toLocalDate());
        String runSlot = "DAY".equals(periodType) ? currentRunSlot(now, ignoreTradingHours) : periodType;
        StockWatchTechnicalAnalysis existing = analysisMapper.selectLatestBySymbolDateSlot(
                ref.symbol,
                ref.market,
                Date.valueOf(now.toLocalDate()),
                runSlot,
                periodType,
                window.key
        );
        if (existing != null && "SUCCESS".equalsIgnoreCase(existing.getStatus())) {
            return;
        }
        runAnalysis(ref, ref.symbol, reason, false, ignoreTradingHours, periodType);
    }

    private StockWatchTechnicalAnalysis runAnalysis(StockRef ref,
                                                    String requestedName,
                                                    String reason,
                                                    boolean forceNewSession,
                                                    boolean ignoreTradingHours,
                                                    String rawPeriodType) {
        String periodType = normalizePeriodType(rawPeriodType);
        ZonedDateTime now = ZonedDateTime.now(CHINA_ZONE);
        if ("DAY".equals(periodType) && tradingHoursOnly && !ignoreTradingHours && !isTradingSession(now.toLocalTime())) {
            throw new IllegalStateException("非交易时段，已跳过自动技术面分析");
        }
        PeriodWindow window = currentPeriodWindow(periodType, now.toLocalDate());

        List<Map<String, Object>> quotes = stockQuoteService.fetchQuotes(ref.symbol + "." + ref.market);
        Map<String, Object> quote = quotes.isEmpty() ? Collections.emptyMap() : quotes.get(0);
        if (!bool(quote.get("ok"), false)) {
            throw new IllegalStateException("行情抓取失败：" + text(quote.get("reason"), "unknown"));
        }
        String stockName = text(quote.get("name"), requestedName);
        StockWatchTechnicalSession session = getOrCreateSession(ref, stockName, forceNewSession, periodType);
        List<StockWatchTechnicalAnalysis> sourceRecords = sourceRecordsForPeriod(ref, periodType, window);
        String prompt = buildPrompt(ref, stockName, quote, session, reason, periodType, window, sourceRecords);
        String promptHash = sha256(prompt).substring(0, 24);
        String runSlot = "DAY".equals(periodType) ? currentRunSlot(now, ignoreTradingHours) : periodType;

        BridgeResult bridgeResult;
        AnalysisPayload parsed;
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setSessionId(session.getId());
        record.setSymbol(ref.symbol);
        record.setMarket(ref.market);
        record.setStockName(stockName);
        record.setAnalysisDate(Date.valueOf(now.toLocalDate()));
        record.setRunSlot(runSlot);
        record.setPeriodType(periodType);
        record.setPeriodKey(window.key);
        record.setSourceRecordIds(sourceRecordIdsJson(sourceRecords));
        record.setProvider("deepseek_web_bridge");
        record.setPromptHash(promptHash);
        try {
            bridgeResult = callDeepSeekBridge(prompt, session, forceNewSession);
            parsed = parseAnalysisPayload(bridgeResult.answerText, stockName);
            record.setStatus("SUCCESS");
            record.setStance(parsed.stance);
            record.setTone(parsed.tone);
            record.setConfidence(parsed.confidence);
            record.setTitle(parsed.title);
            record.setConclusion(parsed.conclusion);
            record.setEvidenceJson(JSON.toJSONString(parsed.evidence));
            record.setRawResponse(bridgeResult.answerText);
            record.setBridgeTargetId(bridgeResult.targetId);
            record.setConversationUrl(bridgeResult.finalUrl);
            record.setContextCharsDelta(prompt.length() + text(bridgeResult.answerText, "").length());
            analysisMapper.insert(record);
            updateSessionAfterRun(session, stockName, bridgeResult, record, null);
            return record;
        } catch (Exception e) {
            record.setStatus("ERROR");
            record.setStance("分析失败");
            record.setTone("red");
            record.setConfidence(0.0);
            record.setTitle(stockName + " 技术面分析失败");
            record.setConclusion("DeepSeek 网页 provider 调用失败：" + e.getMessage());
            record.setEvidenceJson("[]");
            record.setRawResponse("");
            record.setErrorMessage(e.getMessage());
            record.setContextCharsDelta(prompt.length());
            analysisMapper.insert(record);
            updateSessionAfterRun(session, stockName, null, record, e.getMessage());
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
            if (!StringUtils.hasText(bridgeResult.answerText)) {
                throw new IllegalStateException("DeepSeek 网页未返回分析正文，状态=" + bridgeResult.contentStatus);
            }
            return bridgeResult;
        }
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
                               Map<String, Object> quote,
                               StockWatchTechnicalSession session,
                               String reason,
                               String periodType,
                               PeriodWindow window,
                               List<StockWatchTechnicalAnalysis> sourceRecords) {
        Map<String, Object> derived = buildDerivedTechnicalFields(quote);
        List<Map<String, Object>> recent = analysisMapper.selectRecentBySymbol(ref.symbol, ref.market, periodType, 5)
                .stream()
                .map(this::toCompactRecordPayload)
                .collect(Collectors.toList());

        JSONObject payload = new JSONObject(true);
        payload.put("task", "stock_watch_multi_period_technical_analysis");
        payload.put("reason", reason);
        payload.put("periodType", periodType);
        payload.put("periodWindow", window.toPayload());
        payload.put("stock", stockName + " " + ref.symbol + "." + ref.market);
        payload.put("timestamp", ZonedDateTime.now(CHINA_ZONE).toString());
        payload.put("quote", quote);
        payload.put("derivedTechnicalFields", derived);
        payload.put("sourceDailyAnalyses", sourceRecords.stream()
                .map(this::toSourceRecordPayload)
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

        return String.join("\n",
                periodInstruction,
                "允许结合你当前网页版能力自行判断是否需要补充搜索，但不要编造我没有提供的实时行情字段。",
                "如果周/月 sourceDailyAnalyses 不足，请明确写入 missingData，并降低 confidence；不要为了显得完整而编造K线、成交量或资金数据。",
                "输出必须是一个JSON对象，不要Markdown，不要代码块，不要前后解释。",
                "JSON字段固定为：stance(string), tone(string: green/blue/amber/red), confidence(number 0-1), title(string), conclusion(string), evidence(array string), missingData(array string)。",
                "结论要短，优先说明当前周期技术状态、下一周期需要验证的价量条件、风险边界。",
                "输入数据：",
                payload.toJSONString()
        );
    }

    private Map<String, Object> buildDerivedTechnicalFields(Map<String, Object> quote) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Double price = number(quote.get("price"), null);
        Double open = number(quote.get("open"), null);
        Double high = number(quote.get("high"), null);
        Double low = number(quote.get("low"), null);
        Double previousClose = number(quote.get("previousClose"), null);
        if (price != null && high != null && low != null && high > low) {
            fields.put("intradayPosition", (price - low) / (high - low));
        }
        if (price != null && open != null && open > 0) {
            fields.put("priceVsOpenPct", (price - open) / open);
        }
        if (high != null && low != null && previousClose != null && previousClose > 0) {
            fields.put("amplitudePct", (high - low) / previousClose);
        }
        fields.put("hasPositiveChange", number(quote.get("changePercent"), 0.0) >= 0);
        fields.put("hasPositiveMainInflow", number(quote.get("mainInflow"), 0.0) >= 0);
        fields.put("volumeRatio", quote.get("volumeRatio"));
        fields.put("turnover", quote.get("turnover"));
        return fields;
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
            return fallback;
        }
        AnalysisPayload payload = new AnalysisPayload();
        payload.stance = text(parsed.get("stance"), "待复核");
        payload.tone = normalizeTone(text(parsed.get("tone"), "amber"));
        payload.confidence = normalizeConfidence(parsed.get("confidence"));
        payload.title = text(parsed.get("title"), stockName + " 技术面分析");
        payload.conclusion = text(parsed.get("conclusion"), "");
        payload.evidence = toStringList(parsed.getJSONArray("evidence"));
        if (payload.evidence.isEmpty()) {
            payload.evidence.add("DeepSeek 网页技术面分析");
        }
        return payload;
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
        return payload;
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
                .map(ref -> ref.symbol + "." + ref.market)
                .collect(Collectors.toList());
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

    private static class BridgeResult {
        private String answerText;
        private String targetId;
        private String finalUrl;
        private String contentStatus;
    }

    private static class AnalysisPayload {
        private String stance;
        private String tone;
        private double confidence;
        private String title;
        private String conclusion;
        private List<String> evidence = new ArrayList<>();
    }
}
