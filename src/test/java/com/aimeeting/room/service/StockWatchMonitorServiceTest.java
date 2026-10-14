package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchDeliveryConfigMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchAnalysisDemand;
import com.aimeeting.room.entity.StockWatchDeliveryConfig;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.entity.StockWatchItem;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.service.StockWatchAiJudgeService.AiDecision;
import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
import com.aimeeting.room.service.StockWatchAnalysisDemandService.DemandCreationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.sql.Date;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockWatchMonitorServiceTest {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DEFAULT_SIGNAL_SHAPE = "changeDirection=down|mainFlowDirection=down|volumeSpikeWithMove";

    @Mock private StockQuoteService stockQuoteService;
    @Mock private NotificationService notificationService;
    @Mock private StockWatchAiRuleService aiRuleService;
    @Mock private StockWatchAiJudgeService aiJudgeService;
    @Mock private StockWatchDeliveryConfigMapper deliveryConfigMapper;
    @Mock private EastmoneyMarketDataService marketDataService;
    @Mock private StockWatchTechnicalAnalysisMapper technicalAnalysisMapper;
    @Mock private StockWatchRiskPatternService riskPatternService;
    @Mock private StockWatchFactPersistenceService factPersistenceService;
    @Mock private StockWatchTechnicalContextService technicalContextService;
    @Mock private StockWatchTechnicalEmailRenderer technicalEmailRenderer;
    @Mock private StockWatchAlertDeliveryService alertDeliveryService;

    @TempDir private Path tempDir;

    private StockWatchMonitorService service;

    @BeforeEach
    void setUp() {
        service = new StockWatchMonitorService(
                stockQuoteService,
                notificationService,
                aiRuleService,
                aiJudgeService,
                deliveryConfigMapper,
                marketDataService,
                technicalAnalysisMapper,
                riskPatternService,
                factPersistenceService,
                technicalContextService,
                technicalEmailRenderer,
                alertDeliveryService
        );
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "notifyEnabled", true);
        ReflectionTestUtils.setField(service, "tradingHoursOnly", false);
        ReflectionTestUtils.setField(service, "configuredSymbols", "");
        ReflectionTestUtils.setField(service, "configuredRules", "");
        ReflectionTestUtils.setField(service, "configuredStateFile", tempDir.resolve("monitor-state.json").toString());
        ReflectionTestUtils.setField(service, "scanIntervalMs", 600_000L);
        ReflectionTestUtils.setField(service, "defaultAlertIntervalMinutes", 120);
        ReflectionTestUtils.setField(service, "aiAlertCooldownMinutes", 30);
        ReflectionTestUtils.setField(service, "aiContextTechnicalLookbackDays", 14);
        ReflectionTestUtils.setField(service, "aiContextTechnicalLimit", 14);
        ReflectionTestUtils.setField(service, "aiContextTechnicalMaxChars", 12_000);
    }

    @Test
    void emptyConfiguredRulesProduceNoEvaluationOrEmail() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("quotes", Collections.emptyList());
        request.put("aiRules", Collections.emptyList());
        request.put("ignoreTradingHours", true);

        Map<String, Object> result = service.runOnce(request, "test");

        assertEquals(0L, result.get("triggeredCount"));
        assertEquals(0L, result.get("emailSentCount"));
        assertTrue(((List<?>) result.get("evaluations")).isEmpty());
        verify(notificationService, never()).sendStockWatchAlert(anyString(), anyString());
    }

    @Test
    void l0RulePreservesDotStockKeyAndDoesNotBuildEnhancedBundle() {
        AiWatchRule rule = aiRule("l0-rule", "002979", "sz", "雷赛智能", "涨跌幅 changePercent 绝对值 >= 5");
        rule.setRequiredCapabilities(Collections.singletonList(StockWatchAiRuleService.CAPABILITY_L0_ADVISORY));
        rule.setRiskPatternTriggerMode(StockWatchAiRuleService.TRIGGER_MODE_ADVISORY);
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("evaluationStatus", "EVALUATED");
        observations.put("patterns", Collections.emptyList());
        StockWatchRiskPatternService.RiskPatternResult riskPattern =
                new StockWatchRiskPatternService.RiskPatternResult(observations, Collections.emptyMap(),
                        ZonedDateTime.now(CHINA_ZONE), false);
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(marketDataService.fetchDailyBars("002979", "sz", 80)).thenReturn(Collections.emptyList());
        when(riskPatternService.analyze(eq("002979"), eq("sz"), eq(quote), anyList(), any(ZonedDateTime.class)))
                .thenReturn(riskPattern);
        when(factPersistenceService.observe(eq("002979"), eq("sz"), eq("雷赛智能"), eq(quote),
                eq(riskPattern), any(ZonedDateTime.class)))
                .thenReturn(StockWatchFactPersistenceService.ObservationReceipt.skipped("test"));
        when(aiJudgeService.judgeDeterministic(rule, quote)).thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        Map<String, Object> result = service.runOnce(request, "test");
        Map<?, ?> evaluation = (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);

        assertEquals("002979", evaluation.get("symbol"));
        assertEquals("sz", evaluation.get("market"));
        assertNotNull(evaluation.get("riskPatternSummary"));
        assertEquals(false, evaluation.get("triggered"));
        verify(riskPatternService).analyze(eq("002979"), eq("sz"), eq(quote), anyList(), any(ZonedDateTime.class));
        verify(factPersistenceService).observe(eq("002979"), eq("sz"), eq("雷赛智能"), eq(quote),
                eq(riskPattern), any(ZonedDateTime.class));
        verify(marketDataService, never()).buildBundle(anyString(), anyString(), anyString(), anyMap());
        verify(aiJudgeService, never()).judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap());
    }

    @Test
    void configuredHongKongSymbolsReachQuoteMonitoring() {
        ReflectionTestUtils.setField(service, "configuredSymbols", "H09988,H03441");
        when(aiRuleService.listEnabled()).thenReturn(Collections.emptyList());
        when(stockQuoteService.fetchQuotes("H09988,H03441")).thenReturn(Collections.emptyList());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ignoreTradingHours", true);
        request.put("notify", false);
        Map<String, Object> result = service.runOnce(request, "test");

        assertFalse(Boolean.TRUE.equals(result.get("skipped")));
        verify(stockQuoteService).fetchQuotes("H09988,H03441");
    }

    @Test
    void aiRuleWithoutEnhancedKeywordsKeepsQuoteOnlyFastPath() {
        AiWatchRule rule = aiRule("plain-rule", "002979", "sz", "雷赛智能", "涨跌幅 changePercent 绝对值 >= 5 且 量比 volumeRatio >= 2");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        Map<String, Object> result = service.runOnce(request, "test");
        Map<?, ?> evaluation = (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);

        assertEquals("not_required", evaluation.get("aiContextStatus"));
        verify(marketDataService, never()).buildBundle(anyString(), anyString(), anyString(), anyMap());
        verify(technicalAnalysisMapper, never()).selectLatestSuccessfulBySymbolSince(anyString(), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiRuleWithoutEnhancedKeywordsCanUseTechnicalHistoryWithoutEastmoneyBundle() {
        AiWatchRule rule = aiRule("plain-history-rule", "002979", "sz", "雷赛智能", "涨跌幅 changePercent 绝对值 >= 5");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        StockWatchTechnicalAnalysis history = technicalAnalysis(101L, "2026-07-07", "10:00", "主力意图转弱");
        history.setMainForceIntentJson("{\"intent\":\"DISTRIBUTE\",\"confidence\":0.72}");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(technicalAnalysisMapper.selectRecentSuccessfulSlotsByDateRange(
                eq("002979"), eq("sz"), any(), any(), eq("DAY"), eq(14)))
                .thenReturn(Collections.singletonList(history));
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        Map<String, Object> result = service.runOnce(request, "test");
        Map<?, ?> evaluation = (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(aiJudgeService).judge(eq(rule), eq(quote), any(ZonedDateTime.class), contextCaptor.capture());

        assertEquals("available", evaluation.get("aiContextStatus"));
        verify(marketDataService, never()).buildBundle(anyString(), anyString(), anyString(), anyMap());
        Map<String, Object> recent = (Map<String, Object>) contextCaptor.getValue().get("recentTechnicalAnalysis");
        assertEquals(1, recent.get("recordCount"));
        List<Map<String, Object>> records = (List<Map<String, Object>>) recent.get("records");
        assertEquals("主力意图转弱", records.get(0).get("title"));
        assertEquals("DISTRIBUTE", ((Map<String, Object>) records.get(0).get("mainForceIntent")).get("intent"));
        assertEquals("SUPPORTED", ((Map<String, Object>) records.get(0).get("priorValidation")).get("verdict"));
    }

    @Test
    void aiRuleEnhancedContextFailureFallsBackToQuoteOnlyDecision() {
        AiWatchRule rule = aiRule("enhanced-rule", "002979", "sz", "雷赛智能", "技术面跌破MA20且主力资金流恶化");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(marketDataService.buildBundle(eq("002979"), eq("sz"), eq("DAY"), eq(quote)))
                .thenThrow(new IllegalStateException("eastmoney timeout"));
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        Map<String, Object> result = service.runOnce(request, "test");
        Map<?, ?> evaluation = (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(aiJudgeService).judge(eq(rule), eq(quote), any(ZonedDateTime.class), contextCaptor.capture());

        assertEquals("unavailable", evaluation.get("aiContextStatus"));
        assertEquals("unavailable", contextCaptor.getValue().get("status"));
        assertTrue(String.valueOf(contextCaptor.getValue().get("error")).contains("eastmoney timeout"));
        verify(technicalAnalysisMapper, never()).selectLatestSuccessfulBySymbolSince(anyString(), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiRuleEnhancedContextKeepsTechnicalHistoryWhenEastmoneyFails() {
        AiWatchRule rule = aiRule("enhanced-history-rule", "002979", "sz", "雷赛智能", "技术面跌破MA20且主力资金流恶化");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        StockWatchTechnicalAnalysis history = technicalAnalysis(102L, "2026-07-06", "13:00", "高位放量回落");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(technicalAnalysisMapper.selectRecentSuccessfulSlotsByDateRange(
                eq("002979"), eq("sz"), any(), any(), eq("DAY"), eq(14)))
                .thenReturn(Collections.singletonList(history));
        when(marketDataService.buildBundle(eq("002979"), eq("sz"), eq("DAY"), eq(quote)))
                .thenThrow(new IllegalStateException("eastmoney timeout"));
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        Map<String, Object> result = service.runOnce(request, "test");
        Map<?, ?> evaluation = (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(aiJudgeService).judge(eq(rule), eq(quote), any(ZonedDateTime.class), contextCaptor.capture());

        assertEquals("available", evaluation.get("aiContextStatus"));
        Map<String, Object> context = contextCaptor.getValue();
        assertEquals("available", context.get("status"));
        assertEquals("unavailable", context.get("marketDataStatus"));
        assertTrue(String.valueOf(context.get("error")).contains("eastmoney timeout"));
        Map<String, Object> recent = (Map<String, Object>) context.get("recentTechnicalAnalysis");
        assertEquals(1, recent.get("recordCount"));
        assertEquals("高位放量回落", ((List<Map<String, Object>>) recent.get("records")).get(0).get("title"));
    }

    @Test
    void aiRuleEnhancedContextRequiredMissingFallsBackWhenNoTechnicalHistory() {
        AiWatchRule rule = aiRule("missing-rule", "002979", "sz", "雷赛智能", "技术面跌破MA20且主力资金流恶化");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("asOf", "2026-07-07T10:00:00+08:00");
        EastmoneyMarketDataService.MarketDataBundle bundle = new EastmoneyMarketDataService.MarketDataBundle(
                payload,
                Collections.singletonList("dailyKline:need_at_least_30_bars"),
                Collections.emptyList()
        );
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(marketDataService.buildBundle(eq("002979"), eq("sz"), eq("DAY"), eq(quote))).thenReturn(bundle);
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        Map<String, Object> result = service.runOnce(request, "test");
        Map<?, ?> evaluation = (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(aiJudgeService).judge(eq(rule), eq(quote), any(ZonedDateTime.class), contextCaptor.capture());

        assertEquals("unavailable", evaluation.get("aiContextStatus"));
        assertEquals("unavailable", contextCaptor.getValue().get("status"));
        assertEquals("required_market_data_missing", contextCaptor.getValue().get("error"));
        assertEquals(Collections.singletonList("dailyKline:need_at_least_30_bars"), contextCaptor.getValue().get("missingData"));
        verify(technicalAnalysisMapper, never()).selectLatestSuccessfulBySymbolSince(anyString(), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiRuleEnhancedContextIncludesTwoWeekTechnicalHistoryForAnalysis() {
        AiWatchRule rule = aiRule("metadata-rule", "002979", "sz", "雷赛智能", "技术面跌破MA20且主力资金流恶化");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("asOf", "2026-07-07T10:00:00+08:00");
        payload.put("anchorFacts", Map.of("priceYuan", 70.99, "mainInflowYuan", -71_786_715.0));
        EastmoneyMarketDataService.MarketDataBundle bundle = new EastmoneyMarketDataService.MarketDataBundle(
                payload,
                Collections.emptyList(),
                Collections.emptyList()
        );
        StockWatchTechnicalAnalysis analysis = technicalAnalysis(99L, "2026-07-07", "14:30", "主力意图：派发");
        analysis.setTitle("主力意图：派发");
        analysis.setConclusion("主力意图判断为DISTRIBUTE，可能触发风险。");
        analysis.setEvidenceJson("[\"主力资金连续净流出\",\"放量回落\"]");
        analysis.setMissingDataJson("[\"marketBenchmark\"]");
        analysis.setMainForceIntentJson("{\"intent\":\"DISTRIBUTE\",\"timeWindow\":\"FIVE_DAY\",\"confidence\":0.73}");
        analysis.setMainForceIntentStatus("VALID");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(marketDataService.buildBundle(eq("002979"), eq("sz"), eq("DAY"), eq(quote))).thenReturn(bundle);
        when(technicalAnalysisMapper.selectRecentSuccessfulSlotsByDateRange(
                eq("002979"), eq("sz"), any(), any(), eq("DAY"), eq(14)))
                .thenReturn(Collections.singletonList(analysis));
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        service.runOnce(request, "test");

        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(aiJudgeService).judge(eq(rule), eq(quote), any(ZonedDateTime.class), contextCaptor.capture());
        Map<String, Object> context = contextCaptor.getValue();
        Map<String, Object> recent = (Map<String, Object>) context.get("recentTechnicalAnalysis");
        assertEquals("historical_analysis_only_for_analysis_continuity_not_trigger_fact", recent.get("usage"));
        assertEquals(14, recent.get("lookbackDays"));
        assertEquals(1, recent.get("recordCount"));
        List<Map<String, Object>> records = (List<Map<String, Object>>) recent.get("records");
        Map<String, Object> record = records.get(0);
        assertEquals(99L, record.get("id"));
        assertEquals("2026-07-07", record.get("date"));
        assertEquals("主力意图：派发", record.get("title"));
        assertTrue(String.valueOf(record.get("conclusion")).contains("DISTRIBUTE"));
        assertTrue(((List<?>) record.get("evidence")).contains("主力资金连续净流出"));
        assertEquals("DISTRIBUTE", ((Map<String, Object>) record.get("mainForceIntent")).get("intent"));
        assertEquals("SUPPORTED", ((Map<String, Object>) record.get("priorValidation")).get("verdict"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiRuleTechnicalHistoryInfersPriorValidationFromLegacyTextWithoutIntentJson() {
        AiWatchRule rule = aiRule("legacy-history-rule", "002979", "sz", "雷赛智能", "技术面主力意图连续验证");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        StockWatchTechnicalAnalysis legacy = technicalAnalysis(188L, "2026-07-08", "08:24", "高位放量回落，主力出逃");
        legacy.setMainForceIntentJson(null);
        legacy.setMainForceIntentStatus(null);
        legacy.setConclusion("当前30分钟周期高位放量收阴，主力净流出-3.16亿元。下一周期需验证70元支撑有效性；若放量跌破68.32日内低点，将确认小时级调整加深。");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(technicalAnalysisMapper.selectRecentSuccessfulSlotsByDateRange(
                eq("002979"), eq("sz"), any(), any(), eq("DAY"), eq(14)))
                .thenReturn(Collections.singletonList(legacy));
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        service.runOnce(request, "test");

        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(aiJudgeService).judge(eq(rule), eq(quote), any(ZonedDateTime.class), contextCaptor.capture());
        Map<String, Object> recent = (Map<String, Object>) contextCaptor.getValue().get("recentTechnicalAnalysis");
        List<Map<String, Object>> records = (List<Map<String, Object>>) recent.get("records");
        Map<String, Object> record = records.get(0);
        Map<String, Object> intent = (Map<String, Object>) record.get("mainForceIntent");
        Map<String, Object> validation = (Map<String, Object>) record.get("priorValidation");
        List<Map<String, Object>> checks = (List<Map<String, Object>>) validation.get("checks");

        assertEquals("legacy_text_fallback", record.get("mainForceIntentSource"));
        assertEquals("DISTRIBUTE", intent.get("intent"));
        assertTrue(((List<?>) intent.get("watchPoints")).size() >= 2);
        assertTrue(checks.stream().anyMatch(check ->
                "intent_direction".equals(check.get("type")) && "SUPPORTED".equals(check.get("verdict"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiRuleTechnicalHistoryIncludesMultipleSlotsFromSameDayInTimeOrder() {
        AiWatchRule rule = aiRule("slot-history-rule", "002979", "sz", "雷赛智能", "涨跌幅 changePercent 绝对值 >= 5");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        StockWatchTechnicalAnalysis later = technicalAnalysis(202L, "2026-07-07", "14:30", "午后派发确认");
        StockWatchTechnicalAnalysis earlier = technicalAnalysis(201L, "2026-07-07", "10:00", "早盘拉升转弱");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(technicalAnalysisMapper.selectRecentSuccessfulSlotsByDateRange(
                eq("002979"), eq("sz"), any(), any(), eq("DAY"), eq(14)))
                .thenReturn(List.of(later, earlier));
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(false));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        service.runOnce(request, "test");

        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(aiJudgeService).judge(eq(rule), eq(quote), any(ZonedDateTime.class), contextCaptor.capture());
        Map<String, Object> recent = (Map<String, Object>) contextCaptor.getValue().get("recentTechnicalAnalysis");
        assertEquals("recent_successful_day_slots_limited_then_time_asc", recent.get("selectionPolicy"));
        assertEquals(2, recent.get("recordCount"));
        List<Map<String, Object>> records = (List<Map<String, Object>>) recent.get("records");
        assertEquals(201L, records.get(0).get("id"));
        assertEquals("10:00", records.get(0).get("time"));
        assertEquals(202L, records.get(1).get("id"));
        assertEquals("14:30", records.get(1).get("time"));
    }

    @Test
    void disabledEnhancedAiRuleDoesNotBuildContextOrCallJudge() {
        AiWatchRule rule = aiRule("disabled-rule", "002979", "sz", "雷赛智能", "技术面跌破MA20且主力资金流恶化");
        rule.setEnabled(false);
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        Map<String, Object> result = service.runOnce(request, "test");
        Map<?, ?> evaluation = (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);

        assertFalse(Boolean.TRUE.equals(evaluation.get("triggered")));
        assertEquals("AI观察条件未启用", evaluation.get("reason"));
        verify(marketDataService, never()).buildBundle(anyString(), anyString(), anyString(), anyMap());
        verify(aiJudgeService, never()).judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap());
    }

    @Test
    void invalidQuoteForEnhancedAiRuleDoesNotBuildContextOrCallJudge() {
        AiWatchRule rule = aiRule("invalid-quote-rule", "002979", "sz", "雷赛智能", "技术面跌破MA20且主力资金流恶化");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        quote.put("ok", false);
        quote.put("reason", "quote timeout");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);

        Map<String, Object> result = service.runOnce(request, "test");
        Map<?, ?> evaluation = (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);

        assertFalse(Boolean.TRUE.equals(evaluation.get("triggered")));
        assertEquals("行情缺失或抓取失败，未调用DeepSeek判断", evaluation.get("reason"));
        verify(marketDataService, never()).buildBundle(anyString(), anyString(), anyString(), anyMap());
        verify(aiJudgeService, never()).judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap());
    }

    @Test
    void scheduledRunPrunesStateForDeletedRules() throws Exception {
        Path stateFile = tempDir.resolve("monitor-state.json");
        java.nio.file.Files.write(stateFile, ("{\n"
                + "  \"ruleStates\": {\"002594:BREAK_205\": {\"breakDays\": 8}},\n"
                + "  \"sentSignalKeys\": [\"2026-06-17:002594:BREAK_205:8\"],\n"
                + "  \"lastAlertAtByStock\": {\"002594.sz\": \"2026-06-17T10:06:50+08:00\"}\n"
                + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("quotes", Collections.emptyList());
        request.put("ignoreTradingHours", true);

        service.runOnce(request, "scheduled-test");
        Map<String, Object> status = service.status();

        assertTrue(((Map<?, ?>) status.get("ruleStates")).isEmpty());
        assertEquals(0, status.get("sentSignalCount"));
        assertTrue(((Map<?, ?>) status.get("lastAlertAtByStock")).isEmpty());
    }

    @Test
    void triggeredRuleWithoutSingleStockMailConfigDoesNotFallbackToGlobalEmail() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("symbol", "002594");
        rule.put("market", "sz");
        rule.put("ruleId", "BREAK_205");
        rule.put("support", 205.0);
        rule.put("requiredBreakDays", 3);
        rule.put("seedBreakDays", 3);
        rule.put("severity", "risk");

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("symbol", "002594");
        quote.put("market", "sz");
        quote.put("name", "比亚迪");
        quote.put("price", 87.76);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.singletonList(rule));
        request.put("quotes", Collections.singletonList(quote));
        request.put("aiRules", Collections.emptyList());
        request.put("ignoreTradingHours", true);
        request.put("notify", true);

        Map<String, Object> result = service.runOnce(request, "test");
        List<?> evaluations = (List<?>) result.get("evaluations");
        Map<?, ?> evaluation = (Map<?, ?>) evaluations.get(0);

        assertEquals(1L, result.get("triggeredCount"));
        assertEquals(0L, result.get("emailSentCount"));
        assertTrue(Boolean.TRUE.equals(evaluation.get("triggered")));
        assertFalse(Boolean.TRUE.equals(evaluation.get("emailSent")));
        assertTrue(String.valueOf(evaluation.get("notificationSkippedReason")).contains("单股邮件"));
        verify(notificationService, never()).sendStockWatchAlert(anyString(), anyString());
    }

    @Test
    void legacyAiSentKeyInitializesStateAndSkipsFirstRun() throws Exception {
        AiWatchRule rule = aiRule("legacy-rule", "002979", "sz", "雷赛智能", "涨跌幅 changePercent 绝对值 >= 5");
        rule.setNotify(true);
        writeState("{\n"
                + "  \"sentSignalKeys\": [\"" + today() + ":AI:002979:legacy-rule\"],\n"
                + "  \"lastAlertAtByStock\": {\"002979.sz\": \"" + minutesAgo(80) + "\"}\n"
                + "}");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(true));

        Map<?, ?> evaluation = runSingleAiEvaluation(rule, quote);

        assertFalse(Boolean.TRUE.equals(evaluation.get("notificationAttempted")));
        assertEquals("legacyAiSentKeyInitialized", evaluation.get("notificationSkippedReason"));
        assertEquals(1, service.status().get("aiAlertStateCount"));
        verify(notificationService, never()).sendStockWatchAlertToRecipients(anyString(), anyString(), any(), any());
    }

    @Test
    void aiAlertCooldownSuppressesRepeatedSignalInsideCooldown() throws Exception {
        AiWatchRule rule = notifyingRule("cooldown-rule");
        writeStateWithAiAlert(rule, minutesAgo(10), DEFAULT_SIGNAL_SHAPE, 3, 6, 6, 7);
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(true));

        Map<?, ?> evaluation = runSingleAiEvaluation(rule, quote);

        assertFalse(Boolean.TRUE.equals(evaluation.get("notificationAttempted")));
        assertTrue(String.valueOf(evaluation.get("notificationSkippedReason")).contains("aiAlertCooldown"));
        verify(notificationService, never()).sendStockWatchAlertToRecipients(anyString(), anyString(), any(), any());
    }

    @Test
    void aiAlertAfterCooldownWithSameSignalDoesNotSend() throws Exception {
        AiWatchRule rule = notifyingRule("same-signal-rule");
        writeStateWithAiAlert(rule, minutesAgo(40), DEFAULT_SIGNAL_SHAPE, 3, 6, 6, 7);
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(true));

        Map<?, ?> evaluation = runSingleAiEvaluation(rule, quote);

        assertFalse(Boolean.TRUE.equals(evaluation.get("notificationAttempted")));
        assertEquals("fingerprintUnchanged", evaluation.get("notificationSkippedReason"));
        verify(notificationService, never()).sendStockWatchAlertToRecipients(anyString(), anyString(), any(), any());
    }

    @Test
    void aiAlertAfterCooldownWithOnlySmallFactBucketChangeDoesNotSend() throws Exception {
        AiWatchRule rule = notifyingRule("small-delta-rule");
        writeStateWithAiAlert(rule, minutesAgo(40), DEFAULT_SIGNAL_SHAPE, 3, 5, 5, 6);
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(true));

        Map<?, ?> evaluation = runSingleAiEvaluation(rule, quote);

        assertFalse(Boolean.TRUE.equals(evaluation.get("notificationAttempted")));
        assertEquals("fingerprintUnchanged", evaluation.get("notificationSkippedReason"));
        verify(notificationService, never()).sendStockWatchAlertToRecipients(anyString(), anyString(), any(), any());
    }

    @Test
    void aiAlertAfterCooldownWithMaterialRiskDeltaSends() throws Exception {
        AiWatchRule rule = notifyingRule("risk-delta-rule");
        writeStateWithAiAlert(rule, minutesAgo(40), DEFAULT_SIGNAL_SHAPE, 3, 4, 4, 5);
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(true));
        when(deliveryConfigMapper.selectBySymbolMarket("002979", "sz")).thenReturn(deliveryConfig(rule, "IMMEDIATE", 120));
        when(alertDeliveryService.createAndSend(any()))
                .thenReturn(StockWatchAlertDeliveryService.DeliveryResult.sent("msg-risk-delta", 1));

        Map<?, ?> evaluation = runSingleAiEvaluation(rule, quote);

        assertTrue(Boolean.TRUE.equals(evaluation.get("notificationAttempted")));
        assertTrue(Boolean.TRUE.equals(evaluation.get("emailSent")));
        assertTrue(Boolean.TRUE.equals(evaluation.get("aiMaterialRiskDelta")));
        verify(alertDeliveryService).createAndSend(any());
    }

    @Test
    void intervalAiAlertUsesAiLastSentAtNotDeliveryLastDispatchAt() throws Exception {
        AiWatchRule rule = notifyingRule("interval-rule");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision(true));
        StockWatchDeliveryConfig config = deliveryConfig(rule, "INTERVAL", 30);
        config.setLastDispatchAt(new java.util.Date());
        when(deliveryConfigMapper.selectBySymbolMarket("002979", "sz")).thenReturn(config);
        when(alertDeliveryService.createAndSend(any()))
                .thenReturn(StockWatchAlertDeliveryService.DeliveryResult.sent("msg-interval", 1));

        Map<?, ?> evaluation = runSingleAiEvaluation(rule, quote);

        assertTrue(Boolean.TRUE.equals(evaluation.get("notificationAttempted")));
        assertTrue(Boolean.TRUE.equals(evaluation.get("emailSent")));
        verify(alertDeliveryService).createAndSend(any());
    }

    @Test
    void aiAlertEmailInlinesOnlyExactFactTechnicalAnalysis() {
        AiWatchRule rule = notifyingRule("exact-inline-rule");
        AiDecision decision = decision(true);
        decision.setModel("deterministic-quote-anomaly-v1");
        long targetSourceTime = ZonedDateTime.of(2026, 7, 17, 10, 30, 0, 0, CHINA_ZONE)
                .toInstant().toEpochMilli();
        Map<String, Object> event = aiEvent(rule, targetSourceTime);
        StockWatchTechnicalAnalysis exact = technicalAnalysis(77L, "2026-07-17", "alert:fact-key-001", "同一事实分析");
        when(technicalContextService.exactTrustedForSource("002979", "sz", targetSourceTime)).thenReturn(exact);
        when(technicalEmailRenderer.render(eq(exact), eq("  同一行情事实可信技术分析")))
                .thenReturn("\n  同一行情事实可信技术分析\n    exact-body\n");
        when(deliveryConfigMapper.selectBySymbolMarket("002979", "sz"))
                .thenReturn(deliveryConfig(rule, "IMMEDIATE", 120));
        when(alertDeliveryService.createAndSend(any()))
                .thenReturn(StockWatchAlertDeliveryService.DeliveryResult.sent("msg-exact", 1));

        ReflectionTestUtils.invokeMethod(service, "sendAiTriggeredEmail", rule, decision, event,
                ZonedDateTime.of(2026, 7, 17, 10, 31, 0, 0, CHINA_ZONE));

        ArgumentCaptor<StockWatchAlertDeliveryService.AlertRequest> captor =
                ArgumentCaptor.forClass(StockWatchAlertDeliveryService.AlertRequest.class);
        verify(alertDeliveryService).createAndSend(captor.capture());
        StockWatchAlertDeliveryService.AlertRequest request = captor.getValue();
        assertEquals(77L, request.getInlineAnalysisId());
        assertEquals(9001L, request.getTargetFactObservationId());
        assertEquals("fact-key-001", request.getTargetFactObservationKey());
        assertEquals(targetSourceTime, request.getTargetSourceTimeEpochMs());
        assertTrue(request.getInitialBody().contains("同一行情事实可信技术分析"));
        assertFalse(request.getInitialBody().contains("正在生成"));
        verify(technicalContextService, never()).latestTrustedHistorical(anyString(), anyString(), anyLong());
    }

    @Test
    void aiAlertEmailKeepsHistoricalContextButStillCreatesExactDemand() {
        AiWatchRule rule = notifyingRule("history-context-rule");
        AiDecision decision = decision(true);
        decision.setModel("deterministic-quote-anomaly-v1");
        decision.setAnalysis("Read timed out");
        long targetSourceTime = ZonedDateTime.of(2026, 7, 17, 10, 30, 0, 0, CHINA_ZONE)
                .toInstant().toEpochMilli();
        Map<String, Object> event = aiEvent(rule, targetSourceTime);
        event.put("analysis_demand_id", 321L);
        StockWatchTechnicalAnalysis historical = technicalAnalysis(66L, "2026-07-17", "10:00", "上一轮分析");
        when(technicalContextService.exactTrustedForSource("002979", "sz", targetSourceTime)).thenReturn(null);
        when(technicalContextService.latestTrustedHistorical("002979", "sz", targetSourceTime)).thenReturn(historical);
        when(technicalEmailRenderer.render(eq(historical), eq("  最近一次可信技术分析（仅作上下文）")))
                .thenReturn("\n  最近一次可信技术分析（仅作上下文）\n    historical-body\n");
        when(deliveryConfigMapper.selectBySymbolMarket("002979", "sz"))
                .thenReturn(deliveryConfig(rule, "IMMEDIATE", 120));
        when(alertDeliveryService.createAndSend(any()))
                .thenReturn(StockWatchAlertDeliveryService.DeliveryResult.sent("msg-history", 1));

        ReflectionTestUtils.invokeMethod(service, "sendAiTriggeredEmail", rule, decision, event,
                ZonedDateTime.of(2026, 7, 17, 10, 31, 0, 0, CHINA_ZONE));

        ArgumentCaptor<StockWatchAlertDeliveryService.AlertRequest> captor =
                ArgumentCaptor.forClass(StockWatchAlertDeliveryService.AlertRequest.class);
        verify(alertDeliveryService).createAndSend(captor.capture());
        StockWatchAlertDeliveryService.AlertRequest request = captor.getValue();
        assertNull(request.getInlineAnalysisId());
        assertEquals(9001L, request.getTargetFactObservationId());
        assertEquals("fact-key-001", request.getTargetFactObservationKey());
        assertEquals(targetSourceTime, request.getTargetSourceTimeEpochMs());
        assertEquals(321L, request.getAnalysisDemandId());
        assertTrue(request.getInitialBody().contains("技术分析正在生成"));
        assertTrue(request.getInitialBody().contains("最近一次可信技术分析（仅作上下文）"));
        assertFalse(request.getInitialBody().contains("Read timed out"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unifiedAnalysisDemandQueuesEvenWhenMailGateIsNotAttempted() {
        AiWatchRule rule = notifyingRule("queued-demand-rule");
        rule.setRequiredCapabilities(Collections.singletonList(StockWatchAiRuleService.CAPABILITY_QUOTE_ANOMALY_V1));
        long targetSourceTime = ZonedDateTime.of(2026, 7, 17, 10, 30, 0, 0, CHINA_ZONE)
                .toInstant().toEpochMilli();
        Map<String, Object> event = aiEvent(rule, targetSourceTime);
        event.put("triggered", true);
        event.put("matched_signals", List.of("mainInflow=-7178万"));
        event.put("trigger_evidence_policy", "deterministic");
        event.put("trigger_evidence_gate_reason", "allowed");
        StockWatchItem item = stockItem(rule);
        StockWatchAnalysisDemand demand = new StockWatchAnalysisDemand();
        demand.setId(909L);
        demand.setDemandKey("demand-key-909");
        demand.setTriggerType("ANOMALY_IMMEDIATE");
        StockWatchAnalysisPolicyService policyService = org.mockito.Mockito.mock(StockWatchAnalysisPolicyService.class);
        StockWatchAnalysisDemandService demandService = org.mockito.Mockito.mock(StockWatchAnalysisDemandService.class);
        ReflectionTestUtils.setField(service, "analysisPolicyService", policyService);
        ReflectionTestUtils.setField(service, "analysisDemandService", demandService);
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 17, 10, 31, 0, 0, CHINA_ZONE);
        when(policyService.currentItem("002979", "sz")).thenReturn(item);
        when(policyService.allowsAutomaticDemand(item, "ANOMALY_IMMEDIATE")).thenReturn(true);
        when(demandService.createAnomalyDetailed(eq(item), eq(9001L), eq("fact-key-001"), eq(targetSourceTime),
                anyList(), anyMap(), eq(now))).thenReturn(DemandCreationResult.created(demand));

        ReflectionTestUtils.invokeMethod(service, "queueUnifiedAnalysisDemand", rule, event, now);

        assertEquals("QUEUED", event.get("analysis_demand_status"));
        assertEquals(909L, event.get("analysis_demand_id"));
        assertEquals("demand-key-909", event.get("analysis_demand_key"));
        ArgumentCaptor<List<String>> reasons = ArgumentCaptor.forClass(List.class);
        verify(demandService).createAnomalyDetailed(eq(item), eq(9001L), eq("fact-key-001"), eq(targetSourceTime),
                reasons.capture(), anyMap(), eq(now));
        assertTrue(reasons.getValue().contains("AI_RULE_TRIGGERED"));
        assertTrue(reasons.getValue().contains("mainInflow=-7178万"));
        verify(alertDeliveryService, never()).createAndSend(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void quoteAnomalyRunCreatesQuoteAlertFactBeforeUnifiedDemand() {
        AiWatchRule rule = notifyingRule("quote-alert-fact-rule");
        rule.setNotify(false);
        rule.setRequiredCapabilities(Collections.singletonList(StockWatchAiRuleService.CAPABILITY_QUOTE_ANOMALY_V1));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        quote.put("timestamp", ZonedDateTime.now(CHINA_ZONE).toEpochSecond());
        AiDecision decision = decision(true);
        decision.setReason("结构化行情规则命中：量价异动");
        StockWatchItem item = stockItem(rule);
        StockWatchFactObservation observation = factObservation(9002L, "quote-alert-fact-key",
                ((Number) quote.get("timestamp")).longValue() * 1000L,
                StockWatchFactPersistenceService.QUOTE_ALERT_SOURCE_TIME_QUALITY);
        StockWatchAnalysisDemand demand = new StockWatchAnalysisDemand();
        demand.setId(910L);
        demand.setDemandKey("demand-key-910");
        demand.setTriggerType("ANOMALY_IMMEDIATE");
        StockWatchAnalysisPolicyService policyService = org.mockito.Mockito.mock(StockWatchAnalysisPolicyService.class);
        StockWatchAnalysisDemandService demandService = org.mockito.Mockito.mock(StockWatchAnalysisDemandService.class);
        ReflectionTestUtils.setField(service, "analysisPolicyService", policyService);
        ReflectionTestUtils.setField(service, "analysisDemandService", demandService);
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(aiJudgeService.judgeDeterministic(rule, quote)).thenReturn(decision);
        when(policyService.currentItem("002979", "sz")).thenReturn(item);
        when(policyService.allowsAutomaticDemand(item, "ANOMALY_IMMEDIATE")).thenReturn(true);
        when(factPersistenceService.observeQuoteAlert(eq("002979"), eq("sz"), eq("雷赛智能"), eq(quote),
                any(ZonedDateTime.class))).thenReturn(
                StockWatchFactPersistenceService.ObservationReceipt.created(observation, Collections.emptyList(), 0));
        when(demandService.createAnomalyDetailed(eq(item), eq(9002L), eq("quote-alert-fact-key"),
                eq(observation.getSourceTimeEpochMs()), anyList(), anyMap(), any(ZonedDateTime.class)))
                .thenReturn(DemandCreationResult.created(demand));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", false);
        Map<String, Object> result = service.runOnce(request, "test");
        Map<String, Object> evaluation = (Map<String, Object>) ((List<?>) result.get("evaluations")).get(0);
        Map<String, Object> event = (Map<String, Object>) evaluation.get("event");
        Map<String, Object> receipt = (Map<String, Object>) evaluation.get("analysisFactObservation");

        assertEquals("QUEUED", evaluation.get("analysisDemandStatus"));
        assertEquals(910L, evaluation.get("analysisDemandId"));
        assertEquals(1, result.get("analysisFactObservationCount"));
        assertEquals("CREATED", receipt.get("status"));
        assertEquals(StockWatchFactPersistenceService.QUOTE_ALERT_SOURCE_TIME_QUALITY,
                receipt.get("sourceTimeQuality"));
        assertEquals(9002L, event.get("target_fact_observation_id"));
        assertEquals("quote-alert-fact-key", event.get("target_fact_observation_key"));
        assertEquals(observation.getSourceTimeEpochMs(), event.get("target_source_time_epoch_ms"));
    }

    @Test
    void quoteAnomalyPolicySkipDoesNotWriteQuoteAlertFact() {
        AiWatchRule rule = notifyingRule("quote-alert-policy-skip");
        rule.setRequiredCapabilities(Collections.singletonList(StockWatchAiRuleService.CAPABILITY_QUOTE_ANOMALY_V1));
        Map<String, Object> event = aiEvent(rule, ZonedDateTime.of(2026, 7, 17, 10, 30, 0, 0, CHINA_ZONE)
                .toInstant().toEpochMilli());
        event.remove("target_fact_observation_id");
        event.remove("target_fact_observation_key");
        event.remove("target_source_time_epoch_ms");
        event.put("triggered", true);
        StockWatchItem item = stockItem(rule);
        item.setAnomalyTriggerMode("ALERT_ONLY");
        StockWatchAnalysisPolicyService policyService = org.mockito.Mockito.mock(StockWatchAnalysisPolicyService.class);
        StockWatchAnalysisDemandService demandService = org.mockito.Mockito.mock(StockWatchAnalysisDemandService.class);
        ReflectionTestUtils.setField(service, "analysisPolicyService", policyService);
        ReflectionTestUtils.setField(service, "analysisDemandService", demandService);
        when(policyService.currentItem("002979", "sz")).thenReturn(item);
        when(policyService.allowsAutomaticDemand(item, "ANOMALY_IMMEDIATE")).thenReturn(false);

        ReflectionTestUtils.invokeMethod(service, "queueUnifiedAnalysisDemand", rule, event,
                ZonedDateTime.of(2026, 7, 17, 10, 31, 0, 0, CHINA_ZONE));

        assertEquals("SKIPPED_BY_POLICY", event.get("analysis_demand_status"));
        assertEquals("analysis_policy_disallowed:ANOMALY_IMMEDIATE", event.get("analysis_demand_reason"));
        verify(factPersistenceService, never()).observeQuoteAlert(anyString(), anyString(), anyString(), anyMap(), any());
        verify(demandService, never()).createAnomalyDetailed(any(), anyLong(), anyString(), anyLong(),
                anyList(), anyMap(), any());
    }

    @Test
    void aiAlertEmailRendersRiskPatternSummaryWhenComputed() {
        AiWatchRule rule = notifyingRule("risk-summary-rule");
        AiDecision decision = decision(true);
        decision.setModel("deterministic-quote-anomaly-v1");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", "2026-07-17T10:31:00+08:00");
        event.put("quote", quote(rule.getSymbol(), rule.getMarket(), rule.getName()));
        event.put("ai_signal_shape_fingerprint", DEFAULT_SIGNAL_SHAPE);
        event.put("riskPatternSummary", riskPatternSummary());
        when(deliveryConfigMapper.selectBySymbolMarket("002979", "sz"))
                .thenReturn(deliveryConfig(rule, "IMMEDIATE", 120));
        when(alertDeliveryService.createAndSend(any()))
                .thenReturn(StockWatchAlertDeliveryService.DeliveryResult.sent("msg-risk-summary", 1));

        ReflectionTestUtils.invokeMethod(service, "sendAiTriggeredEmail", rule, decision, event,
                ZonedDateTime.of(2026, 7, 17, 10, 31, 0, 0, CHINA_ZONE));

        ArgumentCaptor<StockWatchAlertDeliveryService.AlertRequest> captor =
                ArgumentCaptor.forClass(StockWatchAlertDeliveryService.AlertRequest.class);
        verify(alertDeliveryService).createAndSend(captor.capture());
        String body = captor.getValue().getInitialBody();
        assertTrue(body.contains("价量风险结构识别（套路识别）"));
        assertTrue(body.contains("R01 突破后未站稳风险"));
        assertTrue(body.contains("L0 只识别可观察价量结构"));
    }

    @Test
    void severityRankDownOnlyDoesNotSend() throws Exception {
        AiWatchRule rule = notifyingRule("severity-down-rule");
        writeStateWithAiAlert(rule, minutesAgo(40), DEFAULT_SIGNAL_SHAPE, 4, 8, 6, 7);
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        AiDecision decision = decision(true);
        decision.setSeverity("info");
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision);

        Map<?, ?> evaluation = runSingleAiEvaluation(rule, quote);

        assertFalse(Boolean.TRUE.equals(evaluation.get("notificationAttempted")));
        assertEquals("fingerprintUnchanged", evaluation.get("notificationSkippedReason"));
        assertFalse(Boolean.TRUE.equals(evaluation.get("aiSeverityRankUp")));
        verify(notificationService, never()).sendStockWatchAlertToRecipients(anyString(), anyString(), any(), any());
    }

    @Test
    void aiDecisionShouldNotifyFalseDoesNotSend() {
        AiWatchRule rule = notifyingRule("no-notify-rule");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        AiDecision decision = decision(true);
        decision.setShouldNotify(false);
        when(aiJudgeService.judge(eq(rule), eq(quote), any(ZonedDateTime.class), anyMap()))
                .thenReturn(decision);

        Map<?, ?> evaluation = runSingleAiEvaluation(rule, quote);

        assertFalse(Boolean.TRUE.equals(evaluation.get("notificationAttempted")));
        assertEquals("decisionShouldNotifyFalse", evaluation.get("notificationSkippedReason"));
        verify(notificationService, never()).sendStockWatchAlertToRecipients(anyString(), anyString(), any(), any());
    }

    private Map<?, ?> runSingleAiEvaluation(AiWatchRule rule, Map<String, Object> quote) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rules", Collections.emptyList());
        request.put("quotes", Collections.singletonList(quote));
        request.put("ignoreTradingHours", true);
        request.put("notify", true);

        Map<String, Object> result = service.runOnce(request, "test");
        return (Map<?, ?>) ((List<?>) result.get("evaluations")).get(0);
    }

    private Map<String, Object> aiEvent(AiWatchRule rule, long targetSourceTime) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", "2026-07-17T10:31:00+08:00");
        event.put("quote", quote(rule.getSymbol(), rule.getMarket(), rule.getName()));
        event.put("target_fact_observation_id", 9001L);
        event.put("target_fact_observation_key", "fact-key-001");
        event.put("target_source_time_epoch_ms", targetSourceTime);
        event.put("ai_signal_shape_fingerprint", DEFAULT_SIGNAL_SHAPE);
        return event;
    }

    private Map<String, Object> riskPatternSummary() {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("patternId", "R01");
        pattern.put("status", "triggered");
        pattern.put("statusLabel", "命中");
        pattern.put("riskStructureName", "突破后未站稳风险");
        pattern.put("invalidatesWhen", "后续放量站稳突破位");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("title", "价量风险结构识别（套路识别）");
        summary.put("status", "ACTIVE");
        summary.put("statusLabel", "有命中或关注结构");
        summary.put("semanticBoundary", "L0 只识别可观察价量结构，不能证明操纵或真实主力身份。");
        summary.put("patterns", Collections.singletonList(pattern));
        return summary;
    }

    private AiWatchRule notifyingRule(String id) {
        AiWatchRule rule = aiRule(id, "002979", "sz", "雷赛智能", "涨跌幅 changePercent 绝对值 >= 5 且 量比 volumeRatio >= 2");
        rule.setNotify(true);
        return rule;
    }

    private StockWatchDeliveryConfig deliveryConfig(AiWatchRule rule, String frequencyType, int intervalMinutes) {
        StockWatchDeliveryConfig config = new StockWatchDeliveryConfig();
        config.setId(42L);
        config.setSymbol(rule.getSymbol());
        config.setMarket(rule.getMarket());
        config.setStockName(rule.getName());
        config.setEnabled(true);
        config.setRecipientsJson("[\"watch@example.com\"]");
        config.setFrequencyType(frequencyType);
        config.setIntervalMinutes(intervalMinutes);
        return config;
    }

    private StockWatchItem stockItem(AiWatchRule rule) {
        StockWatchItem item = new StockWatchItem();
        item.setSymbol(rule.getSymbol());
        item.setMarket(rule.getMarket());
        item.setStockName(rule.getName());
        item.setEnabled(true);
        item.setAnalysisMode("INTERVAL");
        item.setAnalysisIntervalMinutes(30);
        item.setAnomalyTriggerMode("IMMEDIATE_ANALYSIS");
        item.setWeeklyReviewEnabled(true);
        item.setMonthlyReviewEnabled(true);
        item.setAnalysisPolicyVersion(7L);
        return item;
    }

    private StockWatchFactObservation factObservation(Long id,
                                                      String observationKey,
                                                      long sourceTimeEpochMs,
                                                      String sourceTimeQuality) {
        StockWatchFactObservation observation = new StockWatchFactObservation();
        observation.setId(id);
        observation.setObservationKey(observationKey);
        observation.setArrivalVersion(3L);
        observation.setSourceTimeEpochMs(sourceTimeEpochMs);
        observation.setSourceDate(ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(sourceTimeEpochMs), CHINA_ZONE).toLocalDate().toString());
        observation.setSourceTimeQuality(sourceTimeQuality);
        observation.setFactHash("fact-hash-" + id);
        return observation;
    }

    private void writeState(String json) throws Exception {
        java.nio.file.Files.write(tempDir.resolve("monitor-state.json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void writeStateWithAiAlert(AiWatchRule rule,
                                       String lastSentAt,
                                       String signalShape,
                                       int severityRank,
                                       int riskScore,
                                       int changeAbsBucket,
                                       int mainInflowAbsBucket) throws Exception {
        writeState("{\n"
                + "  \"lastAiAlerts\": {\n"
                + "    \"" + rule.getSymbol() + "." + rule.getMarket() + ":" + rule.getId() + "\": {\n"
                + "      \"lastSentAt\": \"" + lastSentAt + "\",\n"
                + "      \"signalShapeFingerprint\": \"" + signalShape + "\",\n"
                + "      \"severityRank\": " + severityRank + ",\n"
                + "      \"riskScore\": " + riskScore + ",\n"
                + "      \"riskTier\": \"medium\",\n"
                + "      \"normalizedFacts\": {\n"
                + "        \"changeAbsBucket\": " + changeAbsBucket + ",\n"
                + "        \"mainInflowAbsBucket\": " + mainInflowAbsBucket + "\n"
                + "      },\n"
                + "      \"source\": \"test\"\n"
                + "    }\n"
                + "  }\n"
                + "}");
    }

    private String today() {
        return ZonedDateTime.now(CHINA_ZONE).toLocalDate().toString();
    }

    private String minutesAgo(int minutes) {
        return ZonedDateTime.now(CHINA_ZONE).minusMinutes(minutes).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private AiWatchRule aiRule(String id, String symbol, String market, String name, String condition) {
        AiWatchRule rule = new AiWatchRule();
        rule.setId(id);
        rule.setSymbol(symbol);
        rule.setMarket(market);
        rule.setName(name);
        rule.setConditionText(condition);
        rule.setModel("deepseek-chat");
        rule.setSeverity("risk");
        rule.setMinConfidence(0.75);
        rule.setNotify(false);
        return rule;
    }

    private Map<String, Object> quote(String symbol, String market, String name) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("symbol", symbol);
        quote.put("market", market);
        quote.put("name", name);
        quote.put("price", 70.99);
        quote.put("changePercent", -3.11);
        quote.put("volumeRatio", 4.03);
        quote.put("mainInflow", -71_786_715.0);
        return quote;
    }

    private StockWatchTechnicalAnalysis technicalAnalysis(Long id, String date, String runSlot, String title) {
        StockWatchTechnicalAnalysis analysis = new StockWatchTechnicalAnalysis();
        analysis.setId(id);
        analysis.setAnalysisDate(Date.valueOf(date));
        analysis.setRunSlot(runSlot);
        analysis.setPeriodType("DAY");
        analysis.setStatus("SUCCESS");
        analysis.setStance("risk");
        analysis.setTone("amber");
        analysis.setConfidence(0.72);
        analysis.setTitle(title);
        analysis.setConclusion(title + "，需结合本轮行情复核。");
        analysis.setEvidenceJson("[\"价量结构转弱\"]");
        analysis.setMissingDataJson("[]");
        analysis.setMainForceIntentJson("{\"intent\":\"UNKNOWN\",\"confidence\":0.45}");
        analysis.setMainForceIntentStatus("VALID");
        analysis.setGmtCreate(new java.util.Date());
        return analysis;
    }

    private AiDecision decision(boolean triggered) {
        AiDecision decision = new AiDecision();
        decision.setOk(true);
        decision.setTriggered(triggered);
        decision.setConfidence(triggered ? 0.9 : 0.2);
        decision.setSeverity("risk");
        decision.setShouldNotify(triggered);
        decision.setReason(triggered ? "触发" : "未触发");
        decision.setAnalysis(triggered ? "触发" : "未触发");
        return decision;
    }
}
