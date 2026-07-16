package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchAnalysisTask;
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
            mock(StockWatchFactPersistenceService.class)
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
    void submissionIdentityIsStableForTheSameSourceDateAndRunSlot() {
        Object ref = ReflectionTestUtils.invokeMethod(service, "normalizeStockRef", "002979.sz", "");
        String observationKey = ReflectionTestUtils.invokeMethod(service, "analysisObservationKey",
                "DAY", LocalDate.of(2026, 7, 16), "10:30");

        String submissionKey = ReflectionTestUtils.invokeMethod(service, "analysisSubmissionKey", ref, observationKey);

        assertEquals("DAY:2026-07-16:10:30", observationKey);
        assertEquals("stock-analysis-v4:sz:002979:DAY:2026-07-16:10:30", submissionKey);
        assertFalse(submissionKey.contains(":g"));
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
    void missingRequiredMarketDataPersistsDataUnavailableWithoutModelTask() {
        EastmoneyMarketDataService marketDataService = mock(EastmoneyMarketDataService.class);
        StockWatchTechnicalSessionMapper sessionMapper = mock(StockWatchTechnicalSessionMapper.class);
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchAnalysisTaskService taskService = mock(StockWatchAnalysisTaskService.class);
        StockWatchAnalysisProjectionService projectionService = mock(StockWatchAnalysisProjectionService.class);
        StockWatchRiskPatternService riskPatternService = mock(StockWatchRiskPatternService.class);
        StockWatchFactPersistenceService factService = mock(StockWatchFactPersistenceService.class);
        StockWatchTechnicalAnalysisService localService = new StockWatchTechnicalAnalysisService(
                marketDataService, mock(StockWatchPredictionService.class), mock(StockWatchListService.class),
                sessionMapper, analysisMapper, taskService, projectionService, riskPatternService, factService);
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
        org.mockito.Mockito.verify(taskService).find("stock-analysis-v4:sz:002979:DAY:2026-07-17:15:00");
        verifyNoInteractions(projectionService, sessionMapper);
    }

    @Test
    void terminalTaskReentryReturnsExistingProjectionAndInProgressTaskRefusesDuplicateWork() {
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchTechnicalAnalysisService localService = new StockWatchTechnicalAnalysisService(
                mock(EastmoneyMarketDataService.class), mock(StockWatchPredictionService.class),
                mock(StockWatchListService.class), mock(StockWatchTechnicalSessionMapper.class), analysisMapper,
                mock(StockWatchAnalysisTaskService.class), mock(StockWatchAnalysisProjectionService.class),
                mock(StockWatchRiskPatternService.class), mock(StockWatchFactPersistenceService.class));
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
                mock(StockWatchFactPersistenceService.class));
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setId(81L);
        String promptHash = ReflectionTestUtils.invokeMethod(localService, "sha256", "original prompt");
        String projectionJson = ReflectionTestUtils.invokeMethod(localService, "frozenProjectionJson",
                session, "雷赛智能", LocalDate.of(2026, 7, 16), "10:30", "DAY", "2026-07-16",
                "[701]", "{\"fundFlowEvidence\":{\"anchorMainInflowYuan\":1}}",
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

        return ReflectionTestUtils.invokeMethod(service, "buildEvidenceSignals", raw, "DAY");
    }

    private JSONArray jsonArray(Object... values) {
        JSONArray array = new JSONArray();
        for (Object value : values) {
            array.add(value);
        }
        return array;
    }
}
