package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchAnalysisDemand;
import com.aimeeting.room.entity.StockWatchAnalysisTask;
import com.aimeeting.room.entity.StockWatchItem;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.entity.StockWatchTechnicalSession;
import com.aimeeting.room.service.StockWatchAnalysisTaskService.Reservation;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockWatchTechnicalAnalysisServiceTest {

    private final StockWatchTechnicalAnalysisService service = new StockWatchTechnicalAnalysisService(
            mock(EastmoneyMarketDataService.class),
            mock(StockWatchPredictionService.class),
            mock(StockWatchListService.class),
            mock(StockWatchTechnicalSessionMapper.class),
            mock(StockWatchTechnicalAnalysisMapper.class),
            mock(StockWatchAnalysisTaskService.class),
            mock(StockWatchAnalysisProjectionService.class),
            mock(StockWatchRiskPatternService.class),
            mock(StockWatchFactPersistenceService.class),
            mock(StockWatchFactObservationMapper.class),
            mock(StockWatchV6RuntimeGate.class),
            mock(StockWatchBridgeAdmissionService.class)
    );

    @Test
    void normalizesHongKongSymbolForScheduledAnalysis() {
        Object ref = ReflectionTestUtils.invokeMethod(service, "normalizeStockRef", "H03441", "");

        assertNotNull(ref);
        assertEquals("03441", ReflectionTestUtils.getField(ref, "symbol"));
        assertEquals("hk", ReflectionTestUtils.getField(ref, "market"));
    }

    @Test
    void hongKongTradingWindowContinuesAfterMainlandClose() {
        ZonedDateTime at1530 = ZonedDateTime.of(2026, 7, 16, 15, 30, 0, 0, ZoneId.of("Asia/Shanghai"));

        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "isTradingSession", at1530, "hk"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "isTradingSession", at1530, "sh"));
        assertEquals("15:30", ReflectionTestUtils.invokeMethod(service, "currentRunSlot", at1530, false, "hk"));
    }

    @Test
    void scheduledAnalyzeRechecksLegacyOwnershipAfterClaimingRunner() {
        StockWatchAnalysisSchedulerOwnershipService ownershipService =
                mock(StockWatchAnalysisSchedulerOwnershipService.class);
        when(ownershipService.isLegacyOwner()).thenReturn(true, false);
        ReflectionTestUtils.setField(service, "schedulerOwnershipService", ownershipService);
        ReflectionTestUtils.setField(service, "enabled", true);

        service.scheduledAnalyze();

        assertEquals("skipped_ownership_not_legacy", service.status().get("lastRunStatus"));
        assertFalse(service.isRunning());
    }

    @Test
    void demandPromptPayloadCarriesTriggerContextIntoUnifiedAnalysisPrompt() {
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchTechnicalAnalysisService localService = new StockWatchTechnicalAnalysisService(
                mock(EastmoneyMarketDataService.class),
                mock(StockWatchPredictionService.class),
                mock(StockWatchListService.class),
                mock(StockWatchTechnicalSessionMapper.class),
                analysisMapper,
                mock(StockWatchAnalysisTaskService.class),
                mock(StockWatchAnalysisProjectionService.class),
                mock(StockWatchRiskPatternService.class),
                mock(StockWatchFactPersistenceService.class),
                mock(StockWatchFactObservationMapper.class),
                mock(StockWatchV6RuntimeGate.class),
                mock(StockWatchBridgeAdmissionService.class)
        );
        when(analysisMapper.selectRecentBySymbol(
                org.mockito.ArgumentMatchers.eq("688017"),
                org.mockito.ArgumentMatchers.eq("sh"),
                org.mockito.ArgumentMatchers.eq("DAY"),
                org.mockito.ArgumentMatchers.eq(2))).thenReturn(Collections.emptyList());
        Object ref = ReflectionTestUtils.invokeMethod(localService, "normalizeStockRef", "688017", "sh");
        Object window = ReflectionTestUtils.invokeMethod(localService, "currentPeriodWindow",
                "DAY", LocalDate.of(2026, 7, 22));
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setSessionKey("688017-sh-day");
        session.setGeneration(3);
        session.setContextChars(1024);
        session.setContextLimitChars(80_000);
        StockWatchAnalysisDemand demand = new StockWatchAnalysisDemand();
        demand.setId(901L);
        demand.setDemandKey("demand-901");
        demand.setTriggerType("ANOMALY_DEFERRED");
        demand.setScheduleSlotKey("DAY:2026-07-22:14:30");
        demand.setRunSlot("14:30");
        demand.setSourcePolicyVersion(7L);
        demand.setReasonCodesJson("[\"quote_anomaly\"]");
        demand.setTriggerContextJson("{\"source\":\"monitor\",\"mainInflow\":120000000}");

        String prompt = ReflectionTestUtils.invokeMethod(localService, "buildPrompt",
                ref, "迈瑞医疗", Collections.emptyMap(), Collections.emptyMap(),
                new JSONObject(true), new JSONObject(true), Collections.emptyList(),
                session, "anomaly_unified_demand", "DAY", window,
                Collections.<StockWatchTechnicalAnalysis>emptyList(), demand);

        assertNotNull(prompt);
        assertTrue(prompt.contains("\"analysisDemand\""));
        assertTrue(prompt.contains("\"triggerType\":\"ANOMALY_DEFERRED\""));
        assertTrue(prompt.contains("\"quote_anomaly\""));
        assertTrue(prompt.contains("\"mainInflow\":120000000"));
    }

    @Test
    void buildPromptOmitsNonEvaluableHistoryTextFromModelInput() {
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchTechnicalAnalysisService localService = serviceWithAnalysisMapper(analysisMapper);
        StockWatchTechnicalAnalysis recent = analysisRecord(
                1001L, 501L, "看涨", "历史方向锚定-最近同周期", "旧结论不应进入Prompt");
        when(analysisMapper.selectRecentBySymbol(
                org.mockito.ArgumentMatchers.eq("09988"),
                org.mockito.ArgumentMatchers.eq("hk"),
                org.mockito.ArgumentMatchers.eq("DAY"),
                org.mockito.ArgumentMatchers.eq(2))).thenReturn(Collections.singletonList(recent));
        Map<String, Object> ledgerItem = promptRecord(
                1002L, "看涨", "历史方向锚定-两周账本", "两周旧结论不应进入Prompt");
        JSONObject historyCatalog = historyCatalog(entry(
                1002L, 502L, "NO_TEST_DEFINITION", "NOT_EVALUABLE", false));

        String prompt = ReflectionTestUtils.invokeMethod(localService, "buildPrompt",
                stockRef(localService, "09988", "hk"), "阿里巴巴-W",
                Collections.emptyMap(), Collections.emptyMap(),
                new JSONObject(true), historyCatalog, Collections.singletonList(ledgerItem),
                session(), "manual_api", "DAY", periodWindow(localService),
                Collections.<StockWatchTechnicalAnalysis>emptyList(), null);
        JSONObject payload = promptPayload(prompt);

        assertEquals(0, payload.getJSONArray("recentSamePeriodAnalysis").size());
        assertEquals(0, payload.getJSONArray("recentTwoWeekAnalysisLedger").size());
        assertEquals(0, payload.getJSONObject("historyEvidenceCatalog").getJSONArray("entries").size());
        assertEquals(0, payload.getJSONObject("historySummary").getIntValue("eligibleCount"));
        assertEquals(1, payload.getJSONObject("historySummary").getIntValue("omittedRawCount"));
        assertEquals(1, payload.getJSONObject("historySummary").getIntValue("omittedSamePeriodCount"));
        assertEquals(0, payload.getJSONObject("recentTwoWeekCoverage").getIntValue("promptIncludedCount"));
        assertFalse(prompt.contains("历史方向锚定-最近同周期"));
        assertFalse(prompt.contains("历史方向锚定-两周账本"));
        assertTrue(prompt.contains("不可评估历史原文已省略"));
    }

    @Test
    void buildPromptKeepsOnlyEligibleAttributedHistory() {
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchTechnicalAnalysisService localService = serviceWithAnalysisMapper(analysisMapper);
        StockWatchTechnicalAnalysis recent = analysisRecord(
                2001L, 601L, "看涨", "可评估历史保留", "被支持的历史经验可以进入Prompt");
        when(analysisMapper.selectRecentBySymbol(
                org.mockito.ArgumentMatchers.eq("09988"),
                org.mockito.ArgumentMatchers.eq("hk"),
                org.mockito.ArgumentMatchers.eq("DAY"),
                org.mockito.ArgumentMatchers.eq(2))).thenReturn(Collections.singletonList(recent));
        Map<String, Object> eligibleLedger = promptRecord(
                2001L, "看涨", "可评估历史保留", "被支持的历史经验可以进入Prompt");
        Map<String, Object> ineligibleLedger = promptRecord(
                2002L, "看涨", "不可评估历史剔除", "没有测试定义的历史不能进入Prompt");
        JSONObject historyCatalog = historyCatalog(
                entry(2001L, 601L, "ATTRIBUTABLE", "SUPPORTED", true),
                entry(2002L, 602L, "NO_TEST_DEFINITION", "NOT_EVALUABLE", false));

        String prompt = ReflectionTestUtils.invokeMethod(localService, "buildPrompt",
                stockRef(localService, "09988", "hk"), "阿里巴巴-W",
                Collections.emptyMap(), Collections.emptyMap(),
                new JSONObject(true), historyCatalog, List.of(eligibleLedger, ineligibleLedger),
                session(), "manual_api", "DAY", periodWindow(localService),
                Collections.<StockWatchTechnicalAnalysis>emptyList(), null);
        JSONObject payload = promptPayload(prompt);
        JSONArray ledger = payload.getJSONArray("recentTwoWeekAnalysisLedger");
        JSONArray entries = payload.getJSONObject("historyEvidenceCatalog").getJSONArray("entries");

        assertEquals(1, payload.getJSONArray("recentSamePeriodAnalysis").size());
        assertEquals(1, ledger.size());
        assertEquals(1, entries.size());
        assertEquals("SUPPORTED", ledger.getJSONObject(0).getString("aggregateVerdict"));
        assertEquals(true, ledger.getJSONObject(0).getBooleanValue("narrowingEligible"));
        assertEquals("SUPPORTED", entries.getJSONObject(0).getString("aggregateVerdict"));
        assertEquals(1, payload.getJSONObject("historySummary").getIntValue("eligibleCount"));
        assertEquals(1, payload.getJSONObject("historySummary").getIntValue("omittedRawCount"));
        assertTrue(prompt.contains("可评估历史保留"));
        assertFalse(prompt.contains("不可评估历史剔除"));
    }

    @Test
    void demandPolicyVersionChangeIsRejectedBeforeProviderSubmission() {
        StockWatchAnalysisPolicyService policyService = mock(StockWatchAnalysisPolicyService.class);
        ReflectionTestUtils.setField(service, "analysisPolicyService", policyService);
        StockWatchAnalysisDemand demand = new StockWatchAnalysisDemand();
        demand.setSymbol("688017");
        demand.setMarket("sh");
        demand.setTriggerType("DAILY_CLOSE");
        demand.setSourcePolicyVersion(7L);
        StockWatchItem item = new StockWatchItem();
        item.setEnabled(true);
        item.setAnalysisMode("DAILY_CLOSE");
        item.setAnalysisPolicyVersion(8L);
        when(policyService.currentItem("688017", "sh")).thenReturn(item);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "validateDemandPolicyStillCurrent", demand));

        assertEquals("analysis_policy_version_changed", error.getMessage());
    }

    @Test
    void submissionIdentityIsStableForTheSameSourceDateAndRunSlot() {
        Object ref = ReflectionTestUtils.invokeMethod(service, "normalizeStockRef", "002979.sz", "");
        String observationKey = ReflectionTestUtils.invokeMethod(service, "analysisObservationKey",
                "DAY", LocalDate.of(2026, 7, 16), "10:30");

        String submissionKey = ReflectionTestUtils.invokeMethod(service, "analysisSubmissionKey", ref, observationKey);

        assertEquals("DAY:2026-07-16:10:30", observationKey);
        assertEquals("stock-analysis-slot-v1:sz:002979:DAY:2026-07-16:10:30", submissionKey);
        assertFalse(submissionKey.contains(":g"));
    }

    @Test
    void exactMainlandCloseDeltaUsesCumulativeBoundaryDifference() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(flowRow("2026-07-16 14:45", -535_216_172d, -937_805_528d, 402_589_356d));
        rows.add(flowRow("2026-07-16 14:59", -543_000_000d, -945_000_000d, 402_000_000d));
        rows.add(flowRow("2026-07-16 15:00", -544_942_222d, -946_444_180d, 401_501_958d));

        Map<?, ?> delta = ReflectionTestUtils.invokeMethod(service, "exactCloseFlowDelta",
                new ArrayList<Object>(rows), "sz");

        assertEquals(-9_726_050d, (Double) delta.get("mainDeltaYuan"), 0.001d);
        assertEquals(-8_638_652d, (Double) delta.get("superLargeDeltaYuan"), 0.001d);
        assertEquals(-1_087_398d, (Double) delta.get("largeDeltaYuan"), 0.001d);
        assertEquals("2026-07-16 14:45", delta.get("startTime"));
        assertEquals("2026-07-16 15:00", delta.get("endTime"));
    }

    @Test
    void closeDeltaFailsClosedForMissingBoundaryAndDuplicateTimestamp() {
        List<Object> missing = new ArrayList<>();
        missing.add(flowRow("2026-07-16 14:46", 1d, 1d, 1d));
        missing.add(flowRow("2026-07-16 15:00", 2d, 2d, 2d));
        Map<?, ?> missingDelta = ReflectionTestUtils.invokeMethod(service, "exactCloseFlowDelta", missing, "sh");
        assertTrue(String.valueOf(missingDelta.get("limitation")).contains("exact_endpoint_missing"));
        assertFalse(missingDelta.containsKey("mainDeltaYuan"));

        List<Object> duplicate = new ArrayList<>();
        duplicate.add(flowRow("2026-07-16 14:45", 1d, 1d, 1d));
        duplicate.add(flowRow("2026-07-16 14:45", 2d, 2d, 2d));
        duplicate.add(flowRow("2026-07-16 15:00", 3d, 3d, 3d));
        Map<?, ?> duplicateDelta = ReflectionTestUtils.invokeMethod(service, "exactCloseFlowDelta", duplicate, "sh");
        assertEquals("tail15_close_duplicate_timestamp", duplicateDelta.get("limitation"));
    }

    @Test
    void v6ServerProfileAppliesCounterCapAndIgnoresModelTactic() {
        Map<String, Object> raw = evidenceRawWithNews(0);
        Map<String, Object> indicators = (Map<String, Object>) raw.get("technicalIndicators");
        indicators.put("macdDif", -2d);
        indicators.put("macdDea", -1d);
        indicators.put("macdHistogram", -2d);
        JSONObject catalog = ReflectionTestUtils.invokeMethod(service, "buildExternalEvidenceCatalog", raw);
        Map<String, Object> signals = ReflectionTestUtils.invokeMethod(service,
                "buildEvidenceSignals", raw, "DAY", "sz", catalog);
        JSONObject modelIntent = new JSONObject(true);
        modelIntent.put("positioningIntent", "ACCUMULATE");
        modelIntent.put("executionTactic", "SUPPRESS");
        modelIntent.put("confidence", 0.99d);

        Object validation = ReflectionTestUtils.invokeMethod(service, "validateResponseContract",
                6, modelIntent, signals, catalog, emptyHistoryCatalog());
        JSONObject trusted = (JSONObject) ReflectionTestUtils.getField(validation, "intent");
        JSONObject positioning = trusted.getJSONObject("positioningEvaluation");
        JSONObject tactic = trusted.getJSONObject("executionTacticEvaluation");

        assertEquals("ACCUMULATE", trusted.getString("intent"));
        assertEquals("WEAK", positioning.getString("tier"));
        assertEquals(0.45d, positioning.getDoubleValue("confidence"), 0.001d);
        assertTrue(positioning.getJSONArray("counterAtomIds").contains("TECH_MACD_BEAR"));
        assertEquals("UNKNOWN", tactic.getString("normalizedValue"));
        assertEquals("UNSUPPORTED", tactic.getString("tier"));
        assertFalse(trusted.toJSONString().contains("SUPPRESS"));
        assertFalse(positioning.getJSONObject("permissions").getBooleanValue("intentTestEligible"));
    }

    @Test
    void v6NormalRecordPayloadDoesNotExposeRawModelText() {
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setId(901L);
        record.setAnalysisTaskId(902L);
        record.setResponseContractVersion(6);
        record.setAnalysisDate(java.sql.Date.valueOf("2026-07-16"));
        record.setRunSlot("15:00");
        record.setPeriodType("DAY");
        record.setPeriodKey("2026-07-16");
        record.setStatus("SUCCESS");
        record.setStance("UNKNOWN");
        record.setTone("blue");
        record.setConfidence(0d);
        record.setTitle("主力意图证据不足（未满足可验证画像）");
        record.setConclusion("服务器可信摘要");
        record.setEvidenceJson("[]");
        record.setMissingDataJson("[]");
        record.setSourceRecordIds("[]");
        record.setRawResponse("压低股价以利于建仓");
        record.setMainForceIntentJson("{damaged-main-force-json");
        record.setEvidenceSignalJson(JSON.toJSONString(evidenceSignals()));

        Map<?, ?> payload = ReflectionTestUtils.invokeMethod(service, "toRecordPayload", record);

        assertEquals(true, payload.get("rawResponseAuditOnly"));
        assertFalse(payload.containsKey("rawResponse"));
        assertFalse(payload.toString().contains("压低股价"));
        assertEquals("当日技术信号偏多", payload.get("title"));
        assertEquals("看涨", payload.get("stance"));
        assertEquals("SERVER_TECHNICAL_V1", payload.get("technicalEvidenceQuality"));
        assertEquals(1, payload.get("technicalRenderVersion"));
        assertTrue(((List<?>) payload.get("evidence")).stream().anyMatch(item -> item.toString().contains("MA20")));
    }

    @Test
    void offHoursSlotIsStableForTheCanonicalSourceTradeDate() {
        ZonedDateTime first = ZonedDateTime.of(2026, 7, 17, 6, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        ZonedDateTime later = first.plusHours(2);

        assertEquals("15:00", ReflectionTestUtils.invokeMethod(service, "currentRunSlot",
                first, true, "sz", LocalDate.of(2026, 7, 16)));
        assertEquals("15:00", ReflectionTestUtils.invokeMethod(service, "currentRunSlot",
                later, true, "sz", LocalDate.of(2026, 7, 16)));
    }

    @Test
    void legacyStoredContextLimitUsesCurrentDeepSeekContextBudget() {
        ReflectionTestUtils.setField(service, "contextLimitChars", 500_000);
        ReflectionTestUtils.setField(service, "contextRotateRatio", 1.0d);
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setSymbol("300750");
        session.setMarket("sz");
        session.setStockName("宁德时代");
        session.setPeriodType("DAY");
        session.setSessionKey("stock-watch-day-sz-300750-g64");
        session.setStatus("ACTIVE");
        session.setGeneration(64);
        session.setContextLimitChars(80_000);
        session.setContextChars(100_000);

        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "shouldRotate", session));
        assertEquals(Integer.valueOf(500_000),
                ReflectionTestUtils.invokeMethod(service, "effectiveContextLimitChars", session));
        Object ref = ReflectionTestUtils.invokeMethod(service, "normalizeStockRef", "300750", "sz");
        Map<?, ?> payload = ReflectionTestUtils.invokeMethod(service, "toSessionPayload", session, ref, "DAY");
        assertEquals(20, payload.get("contextUsage"));
        assertEquals("复用中", payload.get("status"));

        session.setContextChars(500_000);
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "shouldRotate", session));

        session.setContextLimitChars(120_000);
        session.setContextChars(120_000);
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "shouldRotate", session));
        assertEquals(Integer.valueOf(120_000),
                ReflectionTestUtils.invokeMethod(service, "effectiveContextLimitChars", session));
    }

    @Test
    void missingRequiredMarketDataReturnsTransientDataUnavailableWithoutModelTask() {
        EastmoneyMarketDataService marketDataService = mock(EastmoneyMarketDataService.class);
        StockWatchTechnicalSessionMapper sessionMapper = mock(StockWatchTechnicalSessionMapper.class);
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchAnalysisTaskService taskService = mock(StockWatchAnalysisTaskService.class);
        StockWatchAnalysisProjectionService projectionService = mock(StockWatchAnalysisProjectionService.class);
        StockWatchRiskPatternService riskPatternService = mock(StockWatchRiskPatternService.class);
        StockWatchFactPersistenceService factService = mock(StockWatchFactPersistenceService.class);
        StockWatchTechnicalAnalysisService localService = new StockWatchTechnicalAnalysisService(
                marketDataService, mock(StockWatchPredictionService.class), mock(StockWatchListService.class),
                sessionMapper, analysisMapper, taskService, projectionService, riskPatternService, factService,
                mock(StockWatchFactObservationMapper.class), mock(StockWatchV6RuntimeGate.class),
                mock(StockWatchBridgeAdmissionService.class));
        ReflectionTestUtils.setField(localService, "tradingHoursOnly", false);
        ReflectionTestUtils.setField(localService, "contextLimitChars", 80_000);

        ZonedDateTime observedAt = ZonedDateTime.of(2026, 7, 16, 10, 30, 0, 0, ZoneId.of("Asia/Shanghai"));
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("symbol", "002979");
        quote.put("market", "sz");
        quote.put("name", "雷赛智能");
        quote.put("timestamp", observedAt.toEpochSecond());
        quote.put("price", 58.51);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("quote", quote);
        payload.put("dailyKline", Collections.emptyList());
        EastmoneyMarketDataService.MarketDataBundle bundle = new EastmoneyMarketDataService.MarketDataBundle(
                payload, Collections.singletonList("dailyKline:need_at_least_30_bars"), Collections.emptyList());
        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("derived", Collections.emptyMap());
        observations.put("patterns", Collections.emptyList());
        StockWatchRiskPatternService.RiskPatternResult risk = new StockWatchRiskPatternService.RiskPatternResult(
                observations, Collections.emptyMap(), observedAt, false);
        when(marketDataService.buildBundle("002979", "sz", "DAY")).thenReturn(bundle);
        when(riskPatternService.analyze(org.mockito.ArgumentMatchers.eq("002979"),
                org.mockito.ArgumentMatchers.eq("sz"), org.mockito.ArgumentMatchers.eq(quote),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(ZonedDateTime.class)))
                .thenReturn(risk);
        when(factService.observe(org.mockito.ArgumentMatchers.eq("002979"),
                org.mockito.ArgumentMatchers.eq("sz"), org.mockito.ArgumentMatchers.eq("雷赛智能"),
                org.mockito.ArgumentMatchers.eq(quote), org.mockito.ArgumentMatchers.eq(risk),
                org.mockito.ArgumentMatchers.any(ZonedDateTime.class)))
                .thenReturn(StockWatchFactPersistenceService.ObservationReceipt.skipped("test"));
        when(analysisMapper.insert(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ((com.aimeeting.room.entity.StockWatchTechnicalAnalysis) invocation.getArgument(0)).setId(31L);
            return 1;
        });

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("symbol", "002979.sz");
        request.put("name", "雷赛智能");
        request.put("ignoreTradingHours", true);
        Map<String, Object> result = localService.runManual(request);
        Map<?, ?> record = (Map<?, ?>) result.get("record");

        assertEquals("DATA_UNAVAILABLE", record.get("status"));
        assertEquals("本地数据闸门", record.get("provider"));
        assertEquals("local_data_gate", record.get("providerCode"));
        assertEquals(false, record.get("modelCalled"));
        assertTrue(String.valueOf(record.get("conclusion")).contains("本轮未调用模型"));
        assertEquals("待创建", ((Map<?, ?>) result.get("session")).get("status"));
        org.mockito.Mockito.verify(taskService).findCanonical(
                org.mockito.ArgumentMatchers.eq("002979"), org.mockito.ArgumentMatchers.eq("sz"),
                org.mockito.ArgumentMatchers.eq("DAY"), org.mockito.ArgumentMatchers.startsWith(
                        "DAY:" + LocalDate.now(ZoneId.of("Asia/Shanghai")) + ":"));
        verifyNoInteractions(projectionService, sessionMapper);
        org.mockito.Mockito.verify(analysisMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reuseOnlyMissStopsBeforeFactsSessionOrProviderWork() {
        EastmoneyMarketDataService marketDataService = mock(EastmoneyMarketDataService.class);
        StockWatchTechnicalSessionMapper sessionMapper = mock(StockWatchTechnicalSessionMapper.class);
        StockWatchAnalysisTaskService taskService = mock(StockWatchAnalysisTaskService.class);
        StockWatchAnalysisProjectionService projectionService = mock(StockWatchAnalysisProjectionService.class);
        StockWatchRiskPatternService riskPatternService = mock(StockWatchRiskPatternService.class);
        StockWatchFactPersistenceService factService = mock(StockWatchFactPersistenceService.class);
        StockWatchTechnicalAnalysisService localService = new StockWatchTechnicalAnalysisService(
                marketDataService, mock(StockWatchPredictionService.class), mock(StockWatchListService.class),
                sessionMapper, mock(StockWatchTechnicalAnalysisMapper.class), taskService, projectionService,
                riskPatternService, factService, mock(StockWatchFactObservationMapper.class),
                mock(StockWatchV6RuntimeGate.class), mock(StockWatchBridgeAdmissionService.class));
        ReflectionTestUtils.setField(localService, "tradingHoursOnly", false);

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("name", "雷赛智能");
        quote.put("price", 58.51d);
        quote.put("timestamp", ZonedDateTime.of(2026, 7, 16, 15, 0, 0, 0,
                ZoneId.of("Asia/Shanghai")).toEpochSecond());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("quote", quote);
        when(marketDataService.buildBundle("002979", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(
                        payload, Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("symbol", "002979.sz");
        request.put("name", "雷赛智能");
        request.put("periodType", "DAY");
        request.put("ignoreTradingHours", true);
        request.put("reuseOnly", true);

        AnalysisReuseOnlyMissException error = assertThrows(AnalysisReuseOnlyMissException.class,
                () -> localService.runManual(request));

        assertTrue(error.getMessage().startsWith("analysis_reuse_only_miss:DAY:"));
        org.mockito.Mockito.verify(taskService).findCanonical(
                org.mockito.ArgumentMatchers.eq("002979"), org.mockito.ArgumentMatchers.eq("sz"),
                org.mockito.ArgumentMatchers.eq("DAY"), org.mockito.ArgumentMatchers.startsWith("DAY:"));
        verifyNoInteractions(riskPatternService, factService, projectionService, sessionMapper);
    }

    @Test
    void terminalTaskReentryReturnsExistingProjectionAndInProgressTaskRefusesDuplicateWork() {
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchTechnicalAnalysisService localService = new StockWatchTechnicalAnalysisService(
                mock(EastmoneyMarketDataService.class), mock(StockWatchPredictionService.class),
                mock(StockWatchListService.class), mock(StockWatchTechnicalSessionMapper.class), analysisMapper,
                mock(StockWatchAnalysisTaskService.class), mock(StockWatchAnalysisProjectionService.class),
                mock(StockWatchRiskPatternService.class), mock(StockWatchFactPersistenceService.class),
                mock(StockWatchFactObservationMapper.class), mock(StockWatchV6RuntimeGate.class),
                mock(StockWatchBridgeAdmissionService.class));
        StockWatchAnalysisTask terminalTask = task(71L, "INVALID_RESPONSE", "digest-71");
        StockWatchTechnicalAnalysis existing = new StockWatchTechnicalAnalysis();
        existing.setId(701L);
        existing.setAnalysisTaskId(71L);
        existing.setStatus("ERROR");
        when(analysisMapper.selectByAnalysisTaskId(71L)).thenReturn(existing);

        StockWatchTechnicalAnalysis resolved = ReflectionTestUtils.invokeMethod(localService,
                "resolveExistingTaskResult", Reservation.existing(terminalTask, "different-current-digest"));

        assertSame(existing, resolved);
        StockWatchAnalysisTask submittedTask = task(72L, "SUBMITTED", "digest-72");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(localService, "resolveExistingTaskResult",
                        Reservation.existing(submittedTask, "digest-72")));
        assertTrue(error.getMessage().contains("analysis_submission_in_progress"));
    }

    @Test
    void frozenProjectionRestoresOriginalEvidenceWhenCurrentPromptDigestChangesAndRejectsTampering() {
        StockWatchTechnicalAnalysisService localService = new StockWatchTechnicalAnalysisService(
                mock(EastmoneyMarketDataService.class), mock(StockWatchPredictionService.class),
                mock(StockWatchListService.class), mock(StockWatchTechnicalSessionMapper.class),
                mock(StockWatchTechnicalAnalysisMapper.class), mock(StockWatchAnalysisTaskService.class),
                mock(StockWatchAnalysisProjectionService.class), mock(StockWatchRiskPatternService.class),
                mock(StockWatchFactPersistenceService.class), mock(StockWatchFactObservationMapper.class),
                mock(StockWatchV6RuntimeGate.class), mock(StockWatchBridgeAdmissionService.class));
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setId(81L);
        String promptHash = ReflectionTestUtils.invokeMethod(localService, "sha256", "original prompt");
        String projectionJson = ReflectionTestUtils.invokeMethod(localService, "frozenProjectionJson",
                session, "雷赛智能", LocalDate.of(2026, 7, 16), "10:30", "DAY", "2026-07-16",
                "[701]", "{\"fundFlowEvidence\":{\"anchorMainInflowYuan\":1}}",
                emptyExternalEvidenceCatalog().toJSONString(),
                "{\"quote\":{\"price\":58.51}}", 91L, 58.51, promptHash, 1234, "[]",
                emptyHistoryCatalog().toJSONString());
        JSONObject envelope = new JSONObject(true);
        envelope.put("projectionJson", projectionJson);
        envelope.put("projectionDigest", ReflectionTestUtils.invokeMethod(localService, "sha256", projectionJson));
        StockWatchAnalysisTask responseTask = task(82L, "RESPONSE_RECEIVED", promptHash);
        responseTask.setPeriodType("DAY");
        responseTask.setRequestEnvelopeJson(envelope.toJSONString());
        Reservation reservation = Reservation.existing(responseTask, "different-current-digest");

        Object frozen = ReflectionTestUtils.invokeMethod(localService, "restoreFrozenProjection", reservation);

        assertNotNull(frozen);
        assertFalse(reservation.isRequestDigestMatch());
        assertEquals("雷赛智能", ReflectionTestUtils.getField(frozen, "stockName"));
        assertEquals("[701]", ReflectionTestUtils.getField(frozen, "sourceRecordIds"));
        assertEquals(promptHash, ReflectionTestUtils.getField(frozen, "promptHash"));
        assertEquals(6, ReflectionTestUtils.getField(frozen, "responseContractVersion"));

        envelope.put("projectionDigest", "tampered");
        responseTask.setRequestEnvelopeJson(JSON.toJSONString(envelope));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(localService, "restoreFrozenProjection", reservation));
        assertTrue(error.getMessage().contains("digest_mismatch"));
    }

    @Test
    void parseAnalysisPayloadCoercesObjectEvidenceAndMissingData() {
        String raw = "{"
                + "\"stance\":\"看涨\","
                + "\"tone\":\"green\","
                + "\"confidence\":0.72,"
                + "\"title\":\"雷赛智能 技术面分析\","
                + "\"conclusion\":\"结构保持\","
                + "\"evidence\":{\"note\":\"模型误把证据写成对象\"},"
                + "\"missingData\":{\"items\":[\"无\"]},"
                + "\"forecasts\":["
                + "{\"horizon\":\"T1\",\"direction\":\"UP\",\"probability\":0.6,\"targetLow\":58,\"targetHigh\":59},"
                + "{\"horizon\":\"T5\",\"direction\":\"SIDEWAYS\",\"probability\":0.5,\"targetLow\":57,\"targetHigh\":60}"
                + "]"
                + "}";

        Object payload = ReflectionTestUtils.invokeMethod(service, "parseAnalysisPayload", raw, "雷赛智能");

        assertNotNull(payload);
        List<?> evidence = (List<?>) ReflectionTestUtils.getField(payload, "evidence");
        List<?> missingData = (List<?>) ReflectionTestUtils.getField(payload, "missingData");
        List<?> warnings = (List<?>) ReflectionTestUtils.getField(payload, "schemaWarnings");
        JSONArray forecasts = (JSONArray) ReflectionTestUtils.getField(payload, "forecasts");
        assertEquals(1, evidence.size());
        assertTrue(evidence.get(0).toString().contains("note"));
        assertEquals(1, missingData.size());
        assertEquals(2, forecasts.size());
        assertTrue(warnings.contains("evidence expected JSONArray but got JSONObject"));
        assertTrue(warnings.contains("missingData expected JSONArray but got JSONObject"));
    }

    @Test
    void parseAnalysisPayloadCoercesForecastObjectByHorizonKeys() {
        String raw = "{"
                + "\"stance\":\"看涨\","
                + "\"tone\":\"green\","
                + "\"confidence\":0.72,"
                + "\"title\":\"雷赛智能 技术面分析\","
                + "\"conclusion\":\"结构保持\","
                + "\"evidence\":[\"量价确认\"],"
                + "\"missingData\":[],"
                + "\"forecasts\":{"
                + "\"T+1\":{\"direction\":\"UP\",\"probability\":0.6,\"targetLow\":58,\"targetHigh\":59},"
                + "\"T+5\":{\"direction\":\"SIDEWAYS\",\"probability\":0.5,\"targetLow\":57,\"targetHigh\":60}"
                + "}"
                + "}";

        Object payload = ReflectionTestUtils.invokeMethod(service, "parseAnalysisPayload", raw, "雷赛智能");

        assertNotNull(payload);
        JSONArray forecasts = (JSONArray) ReflectionTestUtils.getField(payload, "forecasts");
        List<?> warnings = (List<?>) ReflectionTestUtils.getField(payload, "schemaWarnings");
        Set<String> horizons = forecasts.stream()
                .map(item -> ((com.alibaba.fastjson.JSONObject) item).getString("horizon"))
                .collect(Collectors.toSet());
        assertEquals(Set.of("T1", "T5"), horizons);
        assertTrue(warnings.contains("forecasts expected JSONArray but got JSONObject"));
    }

    @Test
    void validateMainForceIntentAcceptsStrongIntentWithProgramEvidencePaths() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        JSONObject intent = new JSONObject(true);
        intent.put("intent", "ACCUMULATE");
        intent.put("timeWindow", "INTRADAY");
        intent.put("confidence", 0.72);
        intent.put("evidence", jsonArray(
                "资金流引用 evidenceSignals.fundFlowEvidence.anchorMainInflowYuan",
                "价量引用 evidenceSignals.priceVolumeSignal.volumeRatio",
                "位置引用 evidenceSignals.positionSignal.closePosition20dPct"
        ));
        intent.put("counterEvidence", jsonArray());
        intent.put("assumptions", jsonArray());
        intent.put("risk", "若放量不延续，推断降级。");
        intent.put("watchPoints", jsonArray("观察量比是否维持"));
        intent.put("historyAttribution", emptyHistoryAttribution());

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals,
                emptyHistoryCatalog());

        assertNotNull(result);
        assertEquals(true, ReflectionTestUtils.getField(result, "valid"));
        JSONObject normalized = (JSONObject) ReflectionTestUtils.getField(result, "intent");
        assertEquals("ACCUMULATE", normalized.getString("intent"));
        assertEquals("VALID", ReflectionTestUtils.getField(result, "status"));
    }

    @Test
    void validateMainForceIntentRejectsStrongIntentWithoutValidatedEvidencePath() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        JSONObject intent = new JSONObject(true);
        intent.put("intent", "DISTRIBUTE");
        intent.put("timeWindow", "INTRADAY");
        intent.put("confidence", 0.54);
        intent.put("evidence", jsonArray("模型认为资金在出逃，但没有路径"));
        intent.put("counterEvidence", jsonArray());
        intent.put("assumptions", jsonArray());
        intent.put("risk", "证据不足。");
        intent.put("watchPoints", jsonArray());
        intent.put("historyAttribution", emptyHistoryAttribution());

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals,
                emptyHistoryCatalog());

        assertNotNull(result);
        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        List<?> problems = (List<?>) ReflectionTestUtils.getField(result, "problems");
        assertTrue(problems.contains("strong_intent_requires_valid_evidence_path"));
    }

    @Test
    void validateMainForceIntentRejectsAnyEvidenceItemWithoutAllowedPath() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        JSONObject intent = new JSONObject(true);
        intent.put("intent", "ACCUMULATE");
        intent.put("timeWindow", "INTRADAY");
        intent.put("confidence", 0.7);
        intent.put("evidence", jsonArray(
                "资金流引用 evidenceSignals.fundFlowEvidence.anchorMainInflowYuan",
                "第二条只写模型判断没有路径"
        ));
        intent.put("counterEvidence", jsonArray());
        intent.put("assumptions", jsonArray());
        intent.put("risk", "无效证据项应硬拒。");
        intent.put("watchPoints", jsonArray());
        intent.put("historyAttribution", emptyHistoryAttribution());

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals,
                emptyHistoryCatalog());

        assertNotNull(result);
        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        List<?> problems = (List<?>) ReflectionTestUtils.getField(result, "problems");
        assertTrue(problems.contains("mainForceIntent.evidence[1]_missing_valid_evidence_path"));
    }

    @Test
    void validateMainForceIntentRejectsCounterEvidenceItemWithoutAllowedPath() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        JSONObject intent = new JSONObject(true);
        intent.put("intent", "ACCUMULATE");
        intent.put("timeWindow", "INTRADAY");
        intent.put("confidence", 0.7);
        intent.put("evidence", jsonArray(
                "资金流引用 evidenceSignals.fundFlowEvidence.anchorMainInflowYuan",
                "价量引用 evidenceSignals.priceVolumeSignal.volumeRatio",
                "位置引用 evidenceSignals.positionSignal.closePosition20dPct"
        ));
        intent.put("counterEvidence", jsonArray("反证无路径也不能保存"));
        intent.put("assumptions", jsonArray());
        intent.put("risk", "反证路径也必须可审计。");
        intent.put("watchPoints", jsonArray());
        intent.put("historyAttribution", emptyHistoryAttribution());

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals,
                emptyHistoryCatalog());

        assertNotNull(result);
        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        List<?> problems = (List<?>) ReflectionTestUtils.getField(result, "problems");
        assertTrue(problems.contains("mainForceIntent.counterEvidence[0]_missing_valid_evidence_path"));
    }

    @Test
    void validateMainForceIntentRejectsMixedAllowedAndFabricatedPathsInOneItem() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        JSONObject catalog = legacyHistoryCatalog();
        JSONObject intent = validStrongIntent(catalog);
        intent.put("evidence", jsonArray(
                "合法路径 evidenceSignals.fundFlowEvidence.anchorMainInflowYuan，伪造路径 externalEvidenceCatalog.entriesByRef.news_fake.title",
                "价量引用 evidenceSignals.priceVolumeSignal.volumeRatio",
                "位置引用 evidenceSignals.positionSignal.closePosition20dPct"));

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent,
                evidenceSignals, catalog);

        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        List<?> problems = (List<?>) ReflectionTestUtils.getField(result, "problems");
        assertTrue(problems.contains("mainForceIntent.evidence[0]_invalid_evidence_path"));
    }

    @Test
    void responseContractV5NormalizesDeclaredOneWeekAliasAndKeepsRawValue() {
        Map<String, Object> raw = evidenceRawWithNews(2);
        JSONObject externalCatalog = ReflectionTestUtils.invokeMethod(service,
                "buildExternalEvidenceCatalog", raw);
        Map<String, Object> evidenceSignals = ReflectionTestUtils.invokeMethod(service,
                "buildEvidenceSignals", raw, "DAY", externalCatalog);
        JSONObject historyCatalog = legacyHistoryCatalog();
        JSONObject intent = validStrongIntent(historyCatalog);
        intent.put("timeWindow", "ONE_WEEK");

        Object result = ReflectionTestUtils.invokeMethod(service, "validateResponseContract", 5, intent,
                evidenceSignals, externalCatalog, historyCatalog);

        assertEquals(true, ReflectionTestUtils.getField(result, "valid"));
        JSONObject normalized = (JSONObject) ReflectionTestUtils.getField(result, "intent");
        assertEquals("ONE_WEEK", normalized.getString("rawTimeWindow"));
        assertEquals("FIVE_DAY", normalized.getString("timeWindow"));
        List<?> warnings = (List<?>) ReflectionTestUtils.getField(result, "warnings");
        assertTrue(warnings.contains("mainForceIntent.timeWindow_alias:ONE_WEEK->FIVE_DAY"));
    }

    @Test
    void responseContractV5RejectsUnknownWindowAndTamperedExternalCatalog() {
        Map<String, Object> raw = evidenceRawWithNews(1);
        JSONObject externalCatalog = ReflectionTestUtils.invokeMethod(service,
                "buildExternalEvidenceCatalog", raw);
        Map<String, Object> evidenceSignals = ReflectionTestUtils.invokeMethod(service,
                "buildEvidenceSignals", raw, "DAY", externalCatalog);
        JSONObject historyCatalog = legacyHistoryCatalog();
        JSONObject intent = validStrongIntent(historyCatalog);
        intent.put("timeWindow", "CALENDAR_WEEK");

        Object result = ReflectionTestUtils.invokeMethod(service, "validateResponseContract", 5, intent,
                evidenceSignals, externalCatalog, historyCatalog);
        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        assertTrue(((List<?>) ReflectionTestUtils.getField(result, "problems"))
                .contains("mainForceIntent.timeWindow_invalid:CALENDAR_WEEK"));

        externalCatalog.getJSONObject("entriesByRef").values().stream().findFirst()
                .map(JSONObject.class::cast).ifPresent(entry -> entry.put("title", "tampered"));
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "validateResponseContract", 5, validStrongIntent(historyCatalog), evidenceSignals,
                externalCatalog, historyCatalog));
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "validateResponseContract", 4, validStrongIntent(historyCatalog), evidenceSignals,
                ReflectionTestUtils.invokeMethod(service, "buildExternalEvidenceCatalog", raw),
                historyCatalog));
    }

    @Test
    void externalEvidenceCatalogUsesOnlySixCompactNewsItemsAndExactResolvablePaths() {
        Map<String, Object> raw = evidenceRawWithNews(10);
        JSONObject catalog = ReflectionTestUtils.invokeMethod(service, "buildExternalEvidenceCatalog", raw);
        Map<String, Object> signals = ReflectionTestUtils.invokeMethod(service,
                "buildEvidenceSignals", raw, "DAY", catalog);

        assertEquals(6, catalog.getJSONObject("entriesByRef").size());
        assertEquals(true, ReflectionTestUtils.invokeMethod(service,
                "verifyExternalEvidenceCatalogDigest", JSON.parseObject(catalog.toJSONString())));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service,
                "externalCatalogMatchesCitationPaths", catalog, signals));
        List<?> paths = (List<?>) signals.get("citationPaths");
        assertTrue(paths.stream().anyMatch(path -> String.valueOf(path)
                .matches("externalEvidenceCatalog\\.entriesByRef\\.news_[0-9a-f]{16}\\.title")));
        assertFalse(paths.contains("externalEvidenceCatalog.entriesByRef"));
        assertFalse(catalog.getJSONObject("entriesByRef").values().stream()
                .map(JSONObject.class::cast)
                .anyMatch(entry -> "news-6".equals(entry.getString("title"))));
    }

    @Test
    void validateMainForceIntentRejectsHighConfidenceWhenPositionCoverageMissing() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) evidenceSignals.get("evidenceCoverage");
        coverage.put("position", false);
        coverage.put("highConfidenceAllowed", false);
        JSONObject intent = new JSONObject(true);
        intent.put("intent", "ACCUMULATE");
        intent.put("timeWindow", "INTRADAY");
        intent.put("confidence", 0.8);
        intent.put("evidence", jsonArray(
                "资金流引用 evidenceSignals.fundFlowEvidence.anchorMainInflowYuan",
                "价量引用 evidenceSignals.priceVolumeSignal.volumeRatio"
        ));
        intent.put("counterEvidence", jsonArray());
        intent.put("assumptions", jsonArray());
        intent.put("risk", "位置证据缺失。");
        intent.put("watchPoints", jsonArray());
        intent.put("historyAttribution", emptyHistoryAttribution());

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals,
                emptyHistoryCatalog());

        assertNotNull(result);
        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        List<?> problems = (List<?>) ReflectionTestUtils.getField(result, "problems");
        assertTrue(problems.contains("confidence_above_0.55_requires_fund_flow_price_volume_position_coverage"));
    }

    @Test
    void historyAttributionRejectsFabricatedSupportedVerdictForLegacyAnalysis() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        JSONObject catalog = legacyHistoryCatalog();
        JSONObject intent = validStrongIntent(catalog);
        JSONObject attribution = intent.getJSONObject("historyAttribution");
        attribution.getJSONArray("assessments").getJSONObject(0).put("verdict", "SUPPORTED");
        attribution.getJSONArray("assessments").getJSONObject(0).put("usedForNarrowing", true);

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent,
                evidenceSignals, catalog);

        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        List<?> problems = (List<?>) ReflectionTestUtils.getField(result, "problems");
        assertTrue(problems.contains("historyAttribution.verdict_mismatch:analysis:612"));
        assertTrue(problems.contains("historyAttribution.ineligible_narrowing:analysis:612:NOT_EVALUABLE"));
    }

    @Test
    void historyAttributionAcceptsExactLegacyVerdictAndCompleteCoverage() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        JSONObject catalog = legacyHistoryCatalog();

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent",
                validStrongIntent(catalog), evidenceSignals, catalog);

        assertEquals(true, ReflectionTestUtils.getField(result, "valid"));
        JSONObject normalized = (JSONObject) ReflectionTestUtils.getField(result, "intent");
        JSONObject attribution = normalized.getJSONObject("historyAttribution");
        assertEquals("NOT_EVALUABLE",
                attribution.getJSONArray("assessments").getJSONObject(0).getString("verdict"));
    }

    @Test
    void historyCatalogDigestSurvivesSerializationAndMapOrderChanges() {
        JSONObject original = legacyHistoryCatalog();
        JSONObject reparsed = JSON.parseObject(original.toJSONString());

        assertEquals(true, ReflectionTestUtils.invokeMethod(service,
                "verifyHistoryCatalogDigest", reparsed));
    }

    @Test
    void historyCatalogDigestSurvivesNestedTestTargetsAndNullOmission() {
        JSONObject catalog = legacyHistoryCatalog();
        JSONObject entry = catalog.getJSONArray("entries").getJSONObject(0);
        JSONObject test = new JSONObject(true);
        test.put("testRef", "intentTest:91");
        test.put("target", Map.of("upper", 62.25, "lower", 54.56));
        test.put("optional", null);
        entry.put("tests", jsonArray(test));
        catalog.put("catalogDigest", ReflectionTestUtils.invokeMethod(service,
                "historyCatalogDigest", catalog));

        JSONObject reparsed = JSON.parseObject(catalog.toJSONString());
        assertEquals(true, ReflectionTestUtils.invokeMethod(service,
                "verifyHistoryCatalogDigest", reparsed));
    }

    @Test
    void historyAttributionRejectsMissingAssessmentAndNonArrayNarrowingField() {
        Map<String, Object> evidenceSignals = evidenceSignals();
        JSONObject catalog = legacyHistoryCatalog();
        JSONObject intent = validStrongIntent(catalog);
        JSONObject attribution = intent.getJSONObject("historyAttribution");
        attribution.put("assessments", new JSONArray());
        attribution.getJSONObject("narrowing").put("retainedIntents", "ACCUMULATE");

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent,
                evidenceSignals, catalog);

        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        List<?> problems = (List<?>) ReflectionTestUtils.getField(result, "problems");
        assertTrue(problems.contains("historyAttribution.assessment_missing:analysis:612"));
        assertTrue(problems.contains("historyAttribution.narrowing.retainedIntents_must_be_array"));
    }

    @Test
    void sourceTradeDatePrefersExplicitPayloadDate() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceTradeDate", "2026-07-08");

        LocalDate result = ReflectionTestUtils.invokeMethod(service, "sourceTradeDate", payload, LocalDate.of(2026, 7, 10));

        assertEquals(LocalDate.of(2026, 7, 8), result);
    }

    private StockWatchTechnicalAnalysisService serviceWithAnalysisMapper(StockWatchTechnicalAnalysisMapper analysisMapper) {
        return new StockWatchTechnicalAnalysisService(
                mock(EastmoneyMarketDataService.class),
                mock(StockWatchPredictionService.class),
                mock(StockWatchListService.class),
                mock(StockWatchTechnicalSessionMapper.class),
                analysisMapper,
                mock(StockWatchAnalysisTaskService.class),
                mock(StockWatchAnalysisProjectionService.class),
                mock(StockWatchRiskPatternService.class),
                mock(StockWatchFactPersistenceService.class),
                mock(StockWatchFactObservationMapper.class),
                mock(StockWatchV6RuntimeGate.class),
                mock(StockWatchBridgeAdmissionService.class)
        );
    }

    private Object stockRef(StockWatchTechnicalAnalysisService target, String symbol, String market) {
        return ReflectionTestUtils.invokeMethod(target, "normalizeStockRef", symbol, market);
    }

    private Object periodWindow(StockWatchTechnicalAnalysisService target) {
        return ReflectionTestUtils.invokeMethod(target, "currentPeriodWindow",
                "DAY", LocalDate.of(2026, 7, 28));
    }

    private StockWatchTechnicalSession session() {
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setSessionKey("09988-hk-day");
        session.setGeneration(6);
        session.setContextChars(4096);
        session.setContextLimitChars(80_000);
        return session;
    }

    private JSONObject promptPayload(String prompt) {
        String marker = "输入数据：";
        int index = prompt.indexOf(marker);
        assertTrue(index >= 0);
        return JSON.parseObject(prompt.substring(index + marker.length()).trim());
    }

    private StockWatchTechnicalAnalysis analysisRecord(Long id,
                                                       Long taskId,
                                                       String stance,
                                                       String title,
                                                       String conclusion) {
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setId(id);
        record.setAnalysisTaskId(taskId);
        record.setAnalysisDate(java.sql.Date.valueOf(LocalDate.of(2026, 7, 28)));
        record.setRunSlot("11:20");
        record.setPeriodType("DAY");
        record.setStatus("SUCCESS");
        record.setStance(stance);
        record.setTone("green");
        record.setConfidence(0.82);
        record.setTitle(title);
        record.setConclusion(conclusion);
        record.setEvidenceJson("[\"历史证据文本\"]");
        record.setMissingDataJson("[]");
        JSONObject intent = new JSONObject(true);
        intent.put("contractVersion", 6);
        intent.put("intent", "ACCUMULATE");
        intent.put("confidence", 0.45);
        record.setMainForceIntentJson(intent.toJSONString());
        return record;
    }

    private Map<String, Object> promptRecord(Long id, String stance, String title, String conclusion) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("date", "2026-07-28");
        record.put("time", "11:20");
        record.put("status", "SUCCESS");
        record.put("stance", stance);
        record.put("tone", "green");
        record.put("confidence", "82%");
        record.put("title", title);
        record.put("conclusion", conclusion);
        record.put("evidence", jsonArray("历史证据文本"));
        record.put("missingData", new JSONArray());
        record.put("narrowingEligible", false);
        return record;
    }

    private JSONObject historyCatalog(JSONObject... entries) {
        JSONObject catalog = new JSONObject(true);
        catalog.put("schema", "stock-watch-history-catalog-v1");
        catalog.put("lookbackStart", "2026-07-15");
        catalog.put("lookbackEnd", "2026-07-28");
        catalog.put("selectionPolicy", "latest_success_per_date_slot_then_chronological");
        catalog.put("entries", jsonArray((Object[]) entries));
        catalog.put("catalogDigest", ReflectionTestUtils.invokeMethod(service, "historyCatalogDigest", catalog));
        return catalog;
    }

    private JSONObject entry(Long analysisId,
                             Long taskId,
                             String attributionStatus,
                             String aggregateVerdict,
                             boolean narrowingEligible) {
        JSONObject entry = new JSONObject(true);
        entry.put("historyRef", "analysis:" + analysisId);
        entry.put("analysisId", analysisId);
        entry.put("analysisTaskId", taskId);
        entry.put("claimRef", taskId == null ? null : "analysisTask:" + taskId + ":mainForceIntent");
        entry.put("claimDigest", "digest-" + analysisId);
        JSONObject claim = new JSONObject(true);
        claim.put("intent", "ACCUMULATE");
        claim.put("timeWindow", "INTRADAY");
        claim.put("confidence", 0.45);
        entry.put("claim", claim);
        entry.put("evidenceQuality", "SERVER_PROFILE_V1");
        entry.put("attributionStatus", attributionStatus);
        entry.put("tests", "ATTRIBUTABLE".equals(attributionStatus) ? jsonArray(intentTest(analysisId)) : new JSONArray());
        entry.put("aggregateVerdict", aggregateVerdict);
        entry.put("narrowingEligible", narrowingEligible);
        return entry;
    }

    private JSONObject intentTest(Long analysisId) {
        JSONObject test = new JSONObject(true);
        test.put("testRef", "intentTest:" + analysisId);
        test.put("metricPath", "quote.changePercent");
        test.put("operator", "GTE");
        test.put("target", 1.0);
        test.put("verdict", "SUPPORTED");
        test.put("ageCount", 1);
        test.put("observedCount", 1);
        test.put("requiredObservedCount", 1);
        test.put("lastEvaluatedSourceDate", "2026-07-28");
        return test;
    }

    private StockWatchAnalysisTask task(Long id, String status, String digest) {
        StockWatchAnalysisTask task = new StockWatchAnalysisTask();
        task.setId(id);
        task.setStatus(status);
        task.setRequestDigest(digest);
        task.setRowVersion(0L);
        return task;
    }

    private JSONObject emptyHistoryCatalog() {
        JSONObject catalog = new JSONObject(true);
        catalog.put("schema", "stock-watch-history-catalog-v1");
        catalog.put("lookbackStart", "2026-07-03");
        catalog.put("lookbackEnd", "2026-07-16");
        catalog.put("selectionPolicy", "latest_success_per_date_slot_then_chronological");
        catalog.put("entries", new JSONArray());
        catalog.put("catalogDigest", ReflectionTestUtils.invokeMethod(service, "historyCatalogDigest", catalog));
        return catalog;
    }

    private JSONObject emptyExternalEvidenceCatalog() {
        return ReflectionTestUtils.invokeMethod(service, "buildExternalEvidenceCatalog",
                Collections.emptyMap());
    }

    private JSONObject emptyHistoryAttribution() {
        JSONObject attribution = new JSONObject(true);
        JSONObject catalog = emptyHistoryCatalog();
        attribution.put("contractVersion", 1);
        attribution.put("catalogDigest", catalog.getString("catalogDigest"));
        attribution.put("assessments", new JSONArray());
        JSONObject narrowing = new JSONObject(true);
        narrowing.put("basisHistoryRefs", new JSONArray());
        narrowing.put("currentEvidencePaths", new JSONArray());
        narrowing.put("retainedIntents", new JSONArray());
        narrowing.put("rejectedIntents", new JSONArray());
        narrowing.put("summary", "没有可归因历史记录。");
        attribution.put("narrowing", narrowing);
        return attribution;
    }

    private JSONObject legacyHistoryCatalog() {
        JSONObject entry = new JSONObject(true);
        entry.put("historyRef", "analysis:612");
        entry.put("analysisId", 612L);
        entry.put("analysisTaskId", null);
        entry.put("claimRef", null);
        entry.put("claimDigest", "legacy-digest");
        entry.put("claim", null);
        entry.put("attributionStatus", "LEGACY_UNBOUND");
        entry.put("tests", new JSONArray());
        entry.put("aggregateVerdict", "NOT_EVALUABLE");
        JSONObject catalog = new JSONObject(true);
        catalog.put("schema", "stock-watch-history-catalog-v1");
        catalog.put("lookbackStart", "2026-07-03");
        catalog.put("lookbackEnd", "2026-07-16");
        catalog.put("selectionPolicy", "latest_success_per_date_slot_then_chronological");
        catalog.put("entries", jsonArray(entry));
        catalog.put("catalogDigest", ReflectionTestUtils.invokeMethod(service, "historyCatalogDigest", catalog));
        return catalog;
    }

    private JSONObject validStrongIntent(JSONObject catalog) {
        JSONObject intent = new JSONObject(true);
        intent.put("intent", "ACCUMULATE");
        intent.put("timeWindow", "INTRADAY");
        intent.put("confidence", 0.72);
        intent.put("evidence", jsonArray(
                "资金流引用 evidenceSignals.fundFlowEvidence.anchorMainInflowYuan",
                "价量引用 evidenceSignals.priceVolumeSignal.volumeRatio",
                "位置引用 evidenceSignals.positionSignal.closePosition20dPct"));
        intent.put("counterEvidence", new JSONArray());
        intent.put("assumptions", new JSONArray());
        intent.put("risk", "若量能不延续则降级。");
        intent.put("watchPoints", jsonArray("观察量比"));
        JSONObject assessment = new JSONObject(true);
        assessment.put("historyRef", "analysis:612");
        assessment.put("verdict", "NOT_EVALUABLE");
        assessment.put("testRefs", new JSONArray());
        assessment.put("usedForNarrowing", false);
        assessment.put("implication", "旧记录没有任务绑定验证定义，不能裁决。");
        JSONObject narrowing = new JSONObject(true);
        narrowing.put("basisHistoryRefs", new JSONArray());
        narrowing.put("currentEvidencePaths", jsonArray(
                "evidenceSignals.fundFlowEvidence.anchorMainInflowYuan"));
        narrowing.put("retainedIntents", jsonArray("ACCUMULATE"));
        narrowing.put("rejectedIntents", new JSONArray());
        narrowing.put("summary", "仅使用当前可审计证据保留候选意图。");
        JSONObject attribution = new JSONObject(true);
        attribution.put("contractVersion", 1);
        attribution.put("catalogDigest", catalog.getString("catalogDigest"));
        attribution.put("assessments", jsonArray(assessment));
        attribution.put("narrowing", narrowing);
        intent.put("historyAttribution", attribution);
        return intent;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evidenceSignals() {
        Map<String, Object> raw = evidenceRawWithNews(0);
        return ReflectionTestUtils.invokeMethod(service, "buildEvidenceSignals", raw, "DAY");
    }

    private Map<String, Object> evidenceRawWithNews(int newsCount) {
        Map<String, Object> raw = new LinkedHashMap<>();
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("price", 110.0);
        quote.put("changePercent", 3.2);
        quote.put("amount", 900_000_000.0);
        quote.put("turnover", 4.8);
        quote.put("volumeRatio", 1.9);
        quote.put("high", 112.0);
        quote.put("low", 103.0);
        raw.put("quote", quote);

        Map<String, Object> anchorFacts = new LinkedHashMap<>();
        anchorFacts.put("sourceTradeDate", "2026-07-08");
        anchorFacts.put("priceYuan", 110.0);
        anchorFacts.put("changePercent", 3.2);
        anchorFacts.put("amountYuan", 900_000_000.0);
        anchorFacts.put("turnoverPercent", 4.8);
        anchorFacts.put("volumeRatio", 1.9);
        anchorFacts.put("mainInflowYuan", 120_000_000.0);
        anchorFacts.put("mainInflowRatioPercent", 6.2);
        raw.put("anchorFacts", anchorFacts);

        Map<String, Object> indicators = new LinkedHashMap<>();
        indicators.put("ma5", 106.0);
        indicators.put("ma20", 100.0);
        raw.put("technicalIndicators", indicators);

        List<Map<String, Object>> dailyKline = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 6, 1);
        for (int i = 0; i < 25; i++) {
            double close = 90 + i;
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", start.plusDays(i).toString());
            bar.put("close", close);
            bar.put("high", close + 2);
            bar.put("low", close - 2);
            dailyKline.add(bar);
        }
        raw.put("dailyKline", dailyKline);

        List<Map<String, Object>> flow = new ArrayList<>();
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("time", "2026-07-08 14:55");
        latest.put("main", 120_000_000.0);
        latest.put("superLarge", 70_000_000.0);
        latest.put("large", 55_000_000.0);
        latest.put("small", -35_000_000.0);
        flow.add(latest);
        raw.put("intradayFundFlow", flow);

        List<Map<String, Object>> news = new ArrayList<>();
        for (int i = 0; i < newsCount; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", "news-" + i);
            item.put("showTime", "2026-07-" + String.format("%02d", 16 - i) + " 10:00:00");
            item.put("source", i % 2 == 0 ? "source-a" : "source-b");
            item.put("summary", "summary-" + i);
            item.put("url", "https://example.test/news/" + i);
            news.add(item);
        }
        raw.put("latestNews", news);
        return raw;
    }

    private Map<String, Object> flowRow(String time, double main, double superLarge, double large) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", time);
        row.put("main", main);
        row.put("superLarge", superLarge);
        row.put("large", large);
        return row;
    }

    private JSONArray jsonArray(Object... values) {
        JSONArray array = new JSONArray();
        for (Object value : values) {
            array.add(value);
        }
        return array;
    }
}
