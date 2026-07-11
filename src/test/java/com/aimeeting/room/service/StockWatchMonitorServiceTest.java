package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchDeliveryConfigMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.service.StockWatchAiJudgeService.AiDecision;
import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
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
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockWatchMonitorServiceTest {

    @Mock private StockQuoteService stockQuoteService;
    @Mock private NotificationService notificationService;
    @Mock private StockWatchAiRuleService aiRuleService;
    @Mock private StockWatchAiJudgeService aiJudgeService;
    @Mock private StockWatchDeliveryConfigMapper deliveryConfigMapper;
    @Mock private EastmoneyMarketDataService marketDataService;
    @Mock private StockWatchTechnicalAnalysisMapper technicalAnalysisMapper;

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
                technicalAnalysisMapper
        );
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "notifyEnabled", true);
        ReflectionTestUtils.setField(service, "tradingHoursOnly", false);
        ReflectionTestUtils.setField(service, "configuredSymbols", "");
        ReflectionTestUtils.setField(service, "configuredRules", "");
        ReflectionTestUtils.setField(service, "configuredStateFile", tempDir.resolve("monitor-state.json").toString());
        ReflectionTestUtils.setField(service, "scanIntervalMs", 600_000L);
        ReflectionTestUtils.setField(service, "defaultAlertIntervalMinutes", 120);
        ReflectionTestUtils.setField(service, "aiContextMaxAgeMinutes", 360);
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
        verify(marketDataService, never()).buildBundle(anyString(), anyString(), anyString());
        verify(technicalAnalysisMapper, never()).selectLatestSuccessfulBySymbolSince(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aiRuleEnhancedContextFailureFallsBackToQuoteOnlyDecision() {
        AiWatchRule rule = aiRule("enhanced-rule", "002979", "sz", "雷赛智能", "技术面跌破MA20且主力资金流恶化");
        Map<String, Object> quote = quote("002979", "sz", "雷赛智能");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(marketDataService.buildBundle("002979", "sz", "DAY")).thenThrow(new IllegalStateException("eastmoney timeout"));
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
    void aiRuleEnhancedContextRequiredMissingFallsBackWithoutRecentAnalysisLookup() {
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
        when(marketDataService.buildBundle("002979", "sz", "DAY")).thenReturn(bundle);
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
    void aiRuleEnhancedContextStripsTechnicalConclusionAndMainForceIntentFromTriggerInput() {
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
        StockWatchTechnicalAnalysis analysis = new StockWatchTechnicalAnalysis();
        analysis.setId(99L);
        analysis.setAnalysisDate(Date.valueOf("2026-07-07"));
        analysis.setRunSlot("14:30");
        analysis.setPeriodType("DAY");
        analysis.setStatus("SUCCESS");
        analysis.setTitle("主力意图：派发");
        analysis.setConclusion("主力意图判断为DISTRIBUTE，可能触发风险。");
        analysis.setMainForceIntentJson("{\"intent\":\"DISTRIBUTE\"}");
        analysis.setMainForceIntentStatus("VALID");
        when(aiRuleService.listEnabled()).thenReturn(Collections.singletonList(rule));
        when(marketDataService.buildBundle("002979", "sz", "DAY")).thenReturn(bundle);
        when(technicalAnalysisMapper.selectLatestSuccessfulBySymbolSince(eq("002979"), eq("sz"), eq("DAY"), any()))
                .thenReturn(analysis);
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
        assertEquals(99L, recent.get("id"));
        assertEquals("metadata_only_do_not_use_as_trigger_fact", recent.get("usage"));
        assertFalse(recent.containsKey("title"));
        assertFalse(recent.containsKey("conclusion"));
        assertFalse(recent.containsKey("evidence"));
        assertFalse(recent.containsKey("mainForceIntent"));
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
        verify(marketDataService, never()).buildBundle(anyString(), anyString(), anyString());
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
        verify(marketDataService, never()).buildBundle(anyString(), anyString(), anyString());
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
