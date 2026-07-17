package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedTechnicalRendererV1Test {

    @Test
    void dayUsesThreeAlignedDirectionalFamilies() {
        Map<String, Object> signals = signals("DAY", -1.64, -12.4, 4.5,
                57.55, 59.87, 1.75, 2.54, -1.59);

        TrustedTechnicalRendererV1.RenderedTechnical rendered = TrustedTechnicalRendererV1.render(
                signals, "DAY", LocalDate.of(2026, 7, 17), "11:30");

        assertEquals("BEARISH", rendered.getDirection());
        assertEquals("看跌", rendered.getStance());
        assertEquals(0.65d, rendered.getConfidence(), 0.001d);
        assertEquals("当日技术信号偏空", rendered.getTitle());
        assertTrue(rendered.getEvidence().get(0).contains("当日涨跌幅 -1.64%"));
        assertEquals("2026-07-17", rendered.metadata().get("technicalAsOfDate"));
        assertEquals("11:30", rendered.metadata().get("technicalObservationSlot"));
    }

    @Test
    void weekUsesFiveDayReturnInsteadOfIntradayChange() {
        Map<String, Object> signals = signals("WEEK", 9.0, -4.0, 8.0,
                57.0, 60.0, -2.0, -1.0, -1.5);

        TrustedTechnicalRendererV1.RenderedTechnical rendered = TrustedTechnicalRendererV1.render(
                signals, "WEEK", LocalDate.of(2026, 7, 17), "WEEK");

        assertEquals("BEARISH", rendered.getDirection());
        assertEquals("近5个交易日技术信号偏空", rendered.getTitle());
        assertTrue(rendered.getEvidence().get(0).contains("近5个交易日收益 -4.00%"));
        assertFalse(rendered.getConclusion().contains("周K"));
        assertEquals("WEEK", rendered.metadata().get("technicalObservationSlot"));
    }

    @Test
    void monthUsesTwentyDayReturnAndMixedEvidenceFailsClosedToNeutral() {
        Map<String, Object> signals = signals("MONTH", -9.0, -8.0, 6.0,
                61.0, 60.0, -2.0, -1.0, -1.5);

        TrustedTechnicalRendererV1.RenderedTechnical rendered = TrustedTechnicalRendererV1.render(
                signals, "MONTH", LocalDate.of(2026, 7, 17), "MONTH");

        assertEquals("MIXED", rendered.getDirection());
        assertEquals("中性", rendered.getStance());
        assertEquals(0.45d, rendered.getConfidence(), 0.001d);
        assertEquals("近20个交易日技术信号分化", rendered.getTitle());
    }

    @Test
    void fewerThanTwoUsableFamiliesIsUnknown() {
        Map<String, Object> signals = signals("DAY", 1.0, null, null,
                null, null, null, null, null);

        TrustedTechnicalRendererV1.RenderedTechnical rendered = TrustedTechnicalRendererV1.render(
                signals, "DAY", LocalDate.of(2026, 7, 17), "10:00");

        assertEquals("UNKNOWN", rendered.getDirection());
        assertEquals(0d, rendered.getConfidence(), 0.001d);
        assertTrue(rendered.getEvidence().get(0).contains("1.00%"));
    }

    @Test
    void malformedSchemaNeverReusesUntrustedTextAndDigestIsDeterministic() {
        Map<String, Object> malformed = signals("DAY", 3.0, 5.0, 8.0,
                61.0, 60.0, 2.0, 1.0, 1.0);
        malformed.put("schema", "model_free_text_v1");

        TrustedTechnicalRendererV1.RenderedTechnical first = TrustedTechnicalRendererV1.render(
                malformed, "DAY", LocalDate.of(2026, 7, 17), "10:30");
        TrustedTechnicalRendererV1.RenderedTechnical second = TrustedTechnicalRendererV1.render(
                malformed, "DAY", LocalDate.of(2026, 7, 17), "10:30");

        assertEquals("UNKNOWN", first.getDirection());
        assertTrue(first.getConclusion().contains("冻结技术证据不足"));
        assertEquals(first.metadata().get("technicalInputDigest"), second.metadata().get("technicalInputDigest"));
        assertNotEquals("", first.metadata().get("technicalInputDigest"));
    }

    @Test
    void periodMismatchOrMissingExpectedPeriodFailsClosed() {
        Map<String, Object> signals = signals("WEEK", 3.0, 5.0, 8.0,
                61.0, 60.0, 2.0, 1.0, 1.0);

        TrustedTechnicalRendererV1.RenderedTechnical mismatch = TrustedTechnicalRendererV1.render(
                signals, "DAY", LocalDate.of(2026, 7, 17), "10:30");
        TrustedTechnicalRendererV1.RenderedTechnical missing = TrustedTechnicalRendererV1.render(
                signals, null, LocalDate.of(2026, 7, 17), "10:30");

        assertEquals("UNKNOWN", mismatch.getDirection());
        assertEquals("UNKNOWN", missing.getDirection());
        assertTrue(mismatch.getLimitations().stream().anyMatch(value -> value.contains("周期与分析记录不一致")));
        assertTrue(missing.getLimitations().stream().anyMatch(value -> value.contains("分析记录周期")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedNumericOrActivityInvalidatesWholeView() {
        Map<String, Object> malformedNumber = signals("DAY", 3.0, 5.0, 8.0,
                61.0, 60.0, 2.0, 1.0, 1.0);
        ((Map<String, Object>) malformedNumber.get("priceVolumeSignal")).put("volumeRatio", "NaN");
        Map<String, Object> malformedActivity = signals("DAY", 3.0, 5.0, 8.0,
                61.0, 60.0, 2.0, 1.0, 1.0);
        ((Map<String, Object>) malformedActivity.get("priceVolumeSignal")).put("activity", "EXTREME");
        Map<String, Object> nonFiniteNumber = signals("DAY", 3.0, 5.0, 8.0,
                61.0, 60.0, 2.0, 1.0, 1.0);
        ((Map<String, Object>) nonFiniteNumber.get("positionSignal")).put("rsi14", Double.NaN);

        TrustedTechnicalRendererV1.RenderedTechnical numberResult = TrustedTechnicalRendererV1.render(
                malformedNumber, "DAY", LocalDate.of(2026, 7, 17), "10:30");
        TrustedTechnicalRendererV1.RenderedTechnical activityResult = TrustedTechnicalRendererV1.render(
                malformedActivity, "DAY", LocalDate.of(2026, 7, 17), "10:30");
        TrustedTechnicalRendererV1.RenderedTechnical nonFiniteResult = TrustedTechnicalRendererV1.render(
                nonFiniteNumber, "DAY", LocalDate.of(2026, 7, 17), "10:30");

        assertEquals("UNKNOWN", numberResult.getDirection());
        assertEquals("UNKNOWN", activityResult.getDirection());
        assertEquals("UNKNOWN", nonFiniteResult.getDirection());
        assertTrue(numberResult.getLimitations().stream().anyMatch(value -> value.contains("volumeRatio")));
        assertTrue(activityResult.getLimitations().stream().anyMatch(value -> value.contains("activity")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void digestCoversLimitationsDateSlotAndSourceTimestamp() {
        Map<String, Object> baseline = signals("DAY", 3.0, 5.0, 8.0,
                61.0, 60.0, 2.0, 1.0, 1.0);
        String baselineDigest = digest(baseline, LocalDate.of(2026, 7, 17), "10:30");
        assertEquals(baselineDigest, digest(baseline, LocalDate.of(2026, 7, 17), "10:30"));

        Map<String, Object> limitationChanged = signals("DAY", 3.0, 5.0, 8.0,
                61.0, 60.0, 2.0, 1.0, 1.0);
        ((Map<String, Object>) limitationChanged.get("fundFlowEvidence"))
                .put("limitations", Collections.singletonList("资金流快照延迟"));
        Map<String, Object> timestampChanged = signals("DAY", 3.0, 5.0, 8.0,
                61.0, 60.0, 2.0, 1.0, 1.0);
        timestampChanged.put("sourceTimestamp", 1_768_000_001L);

        assertNotEquals(baselineDigest, digest(limitationChanged, LocalDate.of(2026, 7, 17), "10:30"));
        assertNotEquals(baselineDigest, digest(baseline, LocalDate.of(2026, 7, 18), "10:30"));
        assertNotEquals(baselineDigest, digest(baseline, LocalDate.of(2026, 7, 17), "11:00"));
        assertNotEquals(baselineDigest, digest(timestampChanged, LocalDate.of(2026, 7, 17), "10:30"));
    }

    private String digest(Map<String, Object> signals, LocalDate date, String slot) {
        return String.valueOf(TrustedTechnicalRendererV1.render(signals, "DAY", date, slot)
                .metadata().get("technicalInputDigest"));
    }

    private Map<String, Object> signals(String periodType,
                                        Double changePercent,
                                        Double return5Pct,
                                        Double return20Pct,
                                        Double close,
                                        Double ma20,
                                        Double dif,
                                        Double dea,
                                        Double histogram) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "stock_watch_evidence_signals_v2");
        root.put("atomCatalogVersion", 2);
        root.put("periodType", periodType);
        root.put("sourceTimestamp", 1_768_000_000L);

        Map<String, Object> price = new LinkedHashMap<>();
        put(price, "changePercent", changePercent);
        price.put("volumeRatio", 1.2d);
        price.put("turnoverPercent", 4.74d);
        price.put("activity", "MEDIUM");
        price.put("limitations", Collections.emptyList());
        root.put("priceVolumeSignal", price);

        Map<String, Object> position = new LinkedHashMap<>();
        put(position, "return5Pct", return5Pct);
        put(position, "return20Pct", return20Pct);
        put(position, "closeYuan", close);
        put(position, "ma20", ma20);
        put(position, "macdDif", dif);
        put(position, "macdDea", dea);
        put(position, "macdHistogram", histogram);
        position.put("rsi14", 56.92d);
        position.put("limitations", Collections.emptyList());
        root.put("positionSignal", position);

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("anchorMainInflowYuan", 19_876_845d);
        flow.put("anchorMainInflowRatioPercent", 3.24d);
        flow.put("limitations", Collections.emptyList());
        root.put("fundFlowEvidence", flow);
        return root;
    }

    private void put(Map<String, Object> target, String key, Double value) {
        if (value != null) target.put(key, value);
    }
}
