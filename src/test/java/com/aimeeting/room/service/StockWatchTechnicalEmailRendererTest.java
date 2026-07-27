package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchTechnicalEmailRendererTest {

    private final StockWatchTechnicalEmailRenderer renderer = new StockWatchTechnicalEmailRenderer();

    @Test
    void sourceTimestampEpochSecondsAcceptsSeconds() {
        StockWatchTechnicalAnalysis analysis = analysisWithSourceTimestamp(1_784_254_200L);

        assertEquals(1_784_254_200L, renderer.sourceTimestampEpochSeconds(analysis));
    }

    @Test
    void sourceTimestampEpochSecondsAcceptsMilliseconds() {
        StockWatchTechnicalAnalysis analysis = analysisWithSourceTimestamp(1_784_254_200_000L);

        assertEquals(1_784_254_200L, renderer.sourceTimestampEpochSeconds(analysis));
    }

    @Test
    void renderIncludesRiskPatternSummaryWhenSnapshotHasL0Observations() {
        StockWatchTechnicalAnalysis analysis = trustedAnalysis();
        analysis.setDataSnapshotJson(JSON.toJSONString(snapshotWithRiskPattern()));

        String body = renderer.render(analysis, "技术分析结果");

        assertTrue(body.contains("价量风险结构识别（套路识别）"));
        assertTrue(body.contains("R01 突破后未站稳风险"));
        assertTrue(body.contains("L0 只识别可观察的价量风险结构"));
    }

    @Test
    void renderOmitsRiskPatternSummaryForOldSnapshot() {
        StockWatchTechnicalAnalysis analysis = trustedAnalysis();
        analysis.setDataSnapshotJson("{}");

        String body = renderer.render(analysis, "技术分析结果");

        assertFalse(body.contains("价量风险结构识别（套路识别）"));
    }

    private StockWatchTechnicalAnalysis analysisWithSourceTimestamp(long sourceTimestamp) {
        StockWatchTechnicalAnalysis analysis = new StockWatchTechnicalAnalysis();
        analysis.setEvidenceSignalJson(JSON.toJSONString(Map.of("sourceTimestamp", sourceTimestamp)));
        return analysis;
    }

    private StockWatchTechnicalAnalysis trustedAnalysis() {
        StockWatchTechnicalAnalysis analysis = new StockWatchTechnicalAnalysis();
        analysis.setResponseContractVersion(6);
        analysis.setAnalysisDate(Date.valueOf("2026-07-18"));
        analysis.setPeriodType("DAY");
        analysis.setRunSlot("10:30");
        analysis.setEvidenceSignalJson(JSON.toJSONString(Map.of(
                "schema", "stock_watch_evidence_signals_v2",
                "atomCatalogVersion", 2,
                "periodType", "DAY"
        )));
        analysis.setMissingDataJson("[]");
        analysis.setMainForceIntentStatus("INVALID");
        return analysis;
    }

    private Map<String, Object> snapshotWithRiskPattern() {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("patternId", "R01");
        pattern.put("status", "triggered");
        pattern.put("legalConclusion", "突破后未站稳风险");
        pattern.put("invalidatesWhen", "后续放量站稳突破位");
        Map<String, Object> sourceQuality = new LinkedHashMap<>();
        sourceQuality.put("freshnessStatus", "FRESH");
        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("dataLevel", "L0");
        observations.put("evaluationStatus", "ACTIVE");
        observations.put("sourceQuality", sourceQuality);
        observations.put("completedBaselineBarCount", 20);
        observations.put("patterns", List.of(pattern));
        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("semanticBoundary", "L0 只识别可观察的价量风险结构，不能证明操纵、对倒、真实主力身份或违法意图。");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("observationKey", "fact-key-001");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("riskPatternObservations", observations);
        snapshot.put("riskPatternGuidance", guidance);
        snapshot.put("factObservationReceipt", receipt);
        return snapshot;
    }
}
