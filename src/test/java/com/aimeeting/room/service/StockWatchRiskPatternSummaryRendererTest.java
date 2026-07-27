package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchRiskPatternSummaryRendererTest {

    private final StockWatchRiskPatternSummaryRenderer renderer = new StockWatchRiskPatternSummaryRenderer();

    @Test
    @SuppressWarnings("unchecked")
    void summaryProjectsRiskPatternsWithoutChangingJudgementSemantics() {
        Map<String, Object> summary = renderer.summarize(observations(), guidance(), factReceipt());

        assertNotNull(summary);
        assertEquals("ACTIVE", summary.get("status"));
        assertEquals(List.of("R01"), summary.get("activePatternIds"));
        assertEquals(List.of("R02"), summary.get("watchPatternIds"));
        assertEquals("deterministic_projection_only; does_not_affect_trigger_unless_rule_requires_RISK_PATTERN_REQUIRED",
                summary.get("judgementInvariant"));
        assertTrue(String.valueOf(summary.get("conclusionText")).contains("R01 突破后未站稳风险"));
        assertTrue(String.valueOf(summary.get("valueText")).contains("可复核的价量事实"));
        assertTrue(String.valueOf(summary.get("nextWatch")).contains("后续放量站稳突破位"));
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) summary.get("patterns");
        assertEquals("突破后未站稳风险", patterns.get(0).get("riskStructureName"));
        assertTrue(!patterns.get(0).containsKey("legalConclusion"));
        Map<String, Object> dataQuality = (Map<String, Object>) summary.get("dataQuality");
        assertEquals("FRESH", dataQuality.get("sourceTimeQuality"));
        assertEquals("行情时间新鲜", dataQuality.get("sourceTimeQualityLabel"));
        Map<String, Object> fact = (Map<String, Object>) summary.get("factObservation");
        assertEquals(9001L, fact.get("observationId"));
        assertEquals(1, fact.get("testVerdictsCount"));
    }

    @Test
    void mailBlockShowsOnlyCompactDetailsForActivePatterns() {
        Map<String, Object> summary = renderer.summarize(observations(), guidance(), factReceipt());

        String body = renderer.renderMailBlock(summary, "  ");

        assertTrue(body.contains("状态：有命中或关注结构"));
        assertTrue(body.contains("本轮结论：本轮发现 2 个需要复核的价量风险结构"));
        assertTrue(body.contains("为什么有用："));
        assertTrue(body.contains("R01 突破后未站稳风险"));
        assertTrue(body.contains("含义：突破前高或压力后回落"));
        assertTrue(body.contains("失效条件：后续放量站稳突破位"));
        assertTrue(body.contains("事实链路：fact-key-001"));
    }

    @Test
    void missingObservationsReturnNullForOldRecords() {
        assertNull(renderer.summarizeFromSnapshotJson("{}"));
        assertNull(renderer.summarize(null, null, Collections.emptyMap()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skippedSummaryUsesChineseLabelsAndKeepsRawReasonForDebugging() {
        Map<String, Object> sourceQuality = new LinkedHashMap<>();
        sourceQuality.put("freshnessStatus", "STALE_DATE");
        sourceQuality.put("completedBaselineBarCount", 0);
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("patternId", "R01");
        pattern.put("status", "skipped");
        pattern.put("skipReason", "data_quality_gate_failed");
        pattern.put("legalConclusion", "不应暴露的法律化结论");
        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("dataLevel", "L0");
        observations.put("evaluationStatus", "SKIPPED_UNAVAILABLE");
        observations.put("sourceQuality", sourceQuality);
        observations.put("completedBaselineBarCount", 0);
        observations.put("patterns", Collections.singletonList(pattern));

        Map<String, Object> summary = renderer.summarize(observations, englishGuidance(), Collections.emptyMap());

        assertEquals("L0 只识别可观察的价量风险结构，不能证明操纵、对倒、真实主力身份或违法意图。",
                summary.get("semanticBoundary"));
        assertTrue(String.valueOf(summary.get("conclusionText")).contains("先跳过套路识别"));
        assertTrue(String.valueOf(summary.get("dataLimitText")).contains("行情日期过期"));
        assertTrue(((List<String>) summary.get("skippedReasons")).get(0).contains("行情日期或新鲜度未通过"));
        assertEquals("R01:data_quality_gate_failed", ((List<String>) summary.get("rawSkippedReasons")).get(0));
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) summary.get("patterns");
        assertEquals("突破后未站稳风险", patterns.get(0).get("riskStructureName"));
        assertTrue(!patterns.get(0).containsKey("legalConclusion"));

        String body = renderer.renderMailBlock(summary, "  ");
        assertTrue(body.contains("数据限制：行情时间质量：行情日期过期"));
        assertTrue(body.contains("本轮未形成可用结构判断：R01：行情日期或新鲜度未通过"));
    }

    private Map<String, Object> observations() {
        Map<String, Object> sourceQuality = new LinkedHashMap<>();
        sourceQuality.put("freshnessStatus", "FRESH");
        sourceQuality.put("canonicalQuoteObservedAt", "2026-07-18T10:30:00+08:00");
        sourceQuality.put("completedBaselineBarCount", 20);
        sourceQuality.put("consistent", true);

        Map<String, Object> r01 = pattern("R01", "triggered", "突破后未站稳风险", "后续放量站稳突破位");
        Map<String, Object> r02 = pattern("R02", "watch", "活跃成交但价格推进有限", "价格放量突破当前整理区");
        Map<String, Object> r03 = pattern("R03", "not_triggered", "高开后回落且日内承接不足", "重新站上开盘价并靠近日内高位");
        Map<String, Object> r04 = new LinkedHashMap<>();
        r04.put("patternId", "R04");
        r04.put("status", "skipped");
        r04.put("skipReason", "need_4_completed_daily_bars");

        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("dataLevel", "L0");
        observations.put("evaluationStatus", "ACTIVE");
        observations.put("sourceQuality", sourceQuality);
        observations.put("completedBaselineBarCount", 20);
        observations.put("patterns", List.of(r01, r02, r03, r04));
        return observations;
    }

    private Map<String, Object> pattern(String id, String status, String legalConclusion, String invalidatesWhen) {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("patternId", id);
        pattern.put("status", status);
        pattern.put("legalConclusion", legalConclusion);
        pattern.put("invalidatesWhen", invalidatesWhen);
        pattern.put("primaryValue", 1.23);
        pattern.put("riskReviewConfidenceCap", 0.7);
        return pattern;
    }

    private Map<String, Object> guidance() {
        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("semanticBoundary", "L0 只识别可观察的价量风险结构，不能证明操纵、对倒、真实主力身份或违法意图。");
        return guidance;
    }

    private Map<String, Object> englishGuidance() {
        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("semanticBoundary", "L0 patterns cannot prove manipulation, coordinated accounts, wash trading, or the true intent of a main force.");
        return guidance;
    }

    private Map<String, Object> factReceipt() {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("observationId", 9001L);
        receipt.put("observationKey", "fact-key-001");
        receipt.put("sourceTimeEpochMs", 1_784_254_200_000L);
        receipt.put("sourceTimeQuality", "FRESH");
        receipt.put("testVerdicts", List.of(Map.of("verdict", "SUPPORTED")));
        receipt.put("createdTestCount", 2);
        return receipt;
    }
}
