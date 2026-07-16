package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StockWatchTechnicalAnalysisServiceTest {

    private final StockWatchTechnicalAnalysisService service = new StockWatchTechnicalAnalysisService(
            mock(EastmoneyMarketDataService.class),
            mock(StockWatchPredictionService.class),
            mock(StockWatchListService.class),
            mock(StockWatchTechnicalSessionMapper.class),
            mock(StockWatchTechnicalAnalysisMapper.class),
            mock(StockWatchAnalysisTaskService.class),
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

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals);

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

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals);

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

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals);

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

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals);

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

        Object result = ReflectionTestUtils.invokeMethod(service, "validateMainForceIntent", intent, evidenceSignals);

        assertNotNull(result);
        assertEquals(false, ReflectionTestUtils.getField(result, "valid"));
        List<?> problems = (List<?>) ReflectionTestUtils.getField(result, "problems");
        assertTrue(problems.contains("confidence_above_0.55_requires_fund_flow_price_volume_position_coverage"));
    }

    @Test
    void sourceTradeDatePrefersExplicitPayloadDate() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceTradeDate", "2026-07-08");

        LocalDate result = ReflectionTestUtils.invokeMethod(service, "sourceTradeDate", payload, LocalDate.of(2026, 7, 10));

        assertEquals(LocalDate.of(2026, 7, 8), result);
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

    private JSONArray jsonArray(String... values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
