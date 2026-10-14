package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.service.StockWatchPriceFailureResearchRule.RuleAssessment;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchPriceFailureResearchRuleTest {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final StockWatchPriceFailureResearchRule rule = new StockWatchPriceFailureResearchRule();

    @Test
    void ruleSpecHashIsFrozen() {
        assertEquals("f5811c387c6bb922f14927808add85823ed1b163b3f8e51f4a15e031d6ca38e7",
                rule.getRuleSpecHash());
    }

    @Test
    void priceFailureTriggersWithoutStrongFlowAndKeepsFlowAsSeparateStratum() {
        ZonedDateTime at = ZonedDateTime.of(2026, 8, 3, 10, 30, 0, 0, CHINA);
        StockWatchFactObservation fact = fact(at, -1.0, 12.0, 12.0, 85.0,
                0.5, -0.2, 0.20, 6.0, -1.0, 1000.0);

        RuleAssessment result = rule.evaluate(fact, at.plusSeconds(5).toInstant().toEpochMilli());

        assertTrue(result.isEligible());
        assertTrue(result.isPriceShapeTriggered());
        assertFalse(result.isStrongOutflow());
        assertEquals("PRICE_SHAPE_ONLY", result.getStratum());
        assertEquals("WEAK_RANGE_LOCATION", result.getShapeCode());
    }

    @Test
    void strongOutflowUpgradesOnlyTheResearchStratum() {
        ZonedDateTime at = ZonedDateTime.of(2026, 8, 3, 10, 30, 0, 0, CHINA);
        StockWatchFactObservation fact = fact(at, -1.0, 12.0, 12.0, 85.0,
                2.0, -2.0, 0.20, 6.0, -6.0, -10_000_000.0);

        RuleAssessment result = rule.evaluate(fact, at.plusSeconds(5).toInstant().toEpochMilli());

        assertTrue(result.isPriceShapeTriggered());
        assertTrue(result.isStrongOutflow());
        assertEquals("PRICE_SHAPE_WITH_STRONG_OUTFLOW", result.getStratum());
    }

    @Test
    void historicalOrModelFieldsCannotChangeTheAssessment() {
        ZonedDateTime at = ZonedDateTime.of(2026, 8, 3, 10, 30, 0, 0, CHINA);
        StockWatchFactObservation clean = fact(at, -1.0, 12.0, 12.0, 85.0,
                2.0, -2.0, 0.20, 6.0, -6.0, -10_000_000.0);
        StockWatchFactObservation anchored = fact(at, -1.0, 12.0, 12.0, 85.0,
                2.0, -2.0, 0.20, 6.0, -6.0, -10_000_000.0);
        Map<String, Object> snapshot = JSON.parseObject(anchored.getFactSnapshotJson(), Map.class);
        snapshot.put("mainForceIntent", Map.of("intent", "ACCUMULATE"));
        snapshot.put("recentSamePeriodAnalysis", "典型派发");
        anchored.setFactSnapshotJson(JSON.toJSONString(snapshot));

        RuleAssessment left = rule.evaluate(clean, at.plusSeconds(5).toInstant().toEpochMilli());
        RuleAssessment right = rule.evaluate(anchored, at.plusSeconds(5).toInstant().toEpochMilli());

        assertEquals(left.featuresJson(), right.featuresJson());
        assertEquals(left.getStratum(), right.getStratum());
    }

    @Test
    void quoteAlertFactSchemaIsOutsidePriceFailureResearchScope() {
        ZonedDateTime at = ZonedDateTime.of(2026, 8, 3, 10, 30, 0, 0, CHINA);
        StockWatchFactObservation fact = fact(at, -1.0, 12.0, 12.0, 85.0,
                2.0, -2.0, 0.20, 6.0, -6.0, -10_000_000.0);
        Map<String, Object> snapshot = JSON.parseObject(fact.getFactSnapshotJson(), Map.class);
        snapshot.put("schema", StockWatchFactPersistenceService.QUOTE_ALERT_FACT_SCHEMA);
        snapshot.remove("priceFailureResearchFeatures");
        fact.setSourceTimeQuality(StockWatchFactPersistenceService.QUOTE_ALERT_SOURCE_TIME_QUALITY);
        fact.setFactSnapshotJson(JSON.toJSONString(snapshot));

        RuleAssessment result = rule.evaluate(fact, at.plusSeconds(5).toInstant().toEpochMilli());

        assertFalse(result.isEligible());
        assertEquals("FACT_SCHEMA_OUTSIDE_PRICE_FAILURE_SCOPE", result.getExclusionReason());
    }

    @Test
    void lateEvaluationIsRecordedButCannotBecomeAProspectiveSignal() {
        ZonedDateTime at = ZonedDateTime.of(2026, 8, 3, 15, 1, 0, 0, CHINA);
        StockWatchFactObservation fact = fact(at, -1.0, 12.0, 12.0, 85.0,
                2.0, -2.0, 0.20, 6.0, -6.0, -10_000_000.0);

        RuleAssessment result = rule.evaluate(fact, at.plusDays(1).toInstant().toEpochMilli());

        assertFalse(result.isEligible());
        assertEquals("RULE_COMPLETION_NOT_ON_SOURCE_DATE", result.getExclusionReason());
    }

    @Test
    void priceRiseAloneCannotAlsoSatisfyTheTradingActivityGate() {
        ZonedDateTime at = ZonedDateTime.of(2026, 8, 3, 10, 30, 0, 0, CHINA);
        StockWatchFactObservation fact = fact(at, 7.0, 12.0, 12.0, 85.0,
                2.0, -2.0, 0.20, 1.0, -6.0, -10_000_000.0);
        Map<String, Object> snapshot = JSON.parseObject(fact.getFactSnapshotJson(), Map.class);
        ((Map<String, Object>) snapshot.get("quote")).put("volumeRatio", 1.0);
        fact.setFactSnapshotJson(JSON.toJSONString(snapshot));

        RuleAssessment result = rule.evaluate(fact, at.plusSeconds(5).toInstant().toEpochMilli());

        assertTrue(result.isEligible());
        assertFalse(result.isActiveTurnover());
        assertFalse(result.isPriceShapeTriggered());
    }

    private StockWatchFactObservation fact(ZonedDateTime at,
                                            double dailyReturn,
                                            double return5,
                                            double priceVsMa20,
                                            double position20,
                                            double gap,
                                            double fromOpen,
                                            double closeLocation,
                                            double turnover,
                                            double mainRatio,
                                            double superLarge) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("price", 100.0);
        quote.put("turnover", turnover);
        quote.put("volumeRatio", 2.1);
        quote.put("amount", 500_000_000.0);
        quote.put("mainInflowRatio", mainRatio);
        quote.put("superLargeInflow", superLarge);
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("dailyReturnPct", dailyReturn);
        derived.put("previousDayReturnPct", 2.0);
        derived.put("return5Pct", return5);
        derived.put("priceVsMa20Pct", priceVsMa20);
        derived.put("closePosition20dPct", position20);
        derived.put("volatility20Pct", 3.0);
        derived.put("gapOpenPct", gap);
        derived.put("intradayFromOpenPct", fromOpen);
        derived.put("closeLocationValue", closeLocation);
        derived.put("activityLevel", "HIGH");
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("derived", Map.of("dailyReturnPct", dailyReturn));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schema", "stock-watch-fact-v1");
        snapshot.put("quote", quote);
        snapshot.put("riskPatternObservations", risk);
        snapshot.put("priceFailureResearchFeatures", derived);
        StockWatchFactObservation fact = new StockWatchFactObservation();
        fact.setId(101L);
        fact.setObservationKey("observation-101");
        fact.setFactHash("fact-101");
        fact.setSymbol("002979");
        fact.setMarket("sz");
        fact.setSourceDate(at.toLocalDate().toString());
        fact.setSourceTimeEpochMs(at.toInstant().toEpochMilli());
        fact.setSourceTimeQuality(at.getHour() >= 15 ? "POST_CLOSE_VALID" : "FRESH");
        fact.setFactSnapshotJson(JSON.toJSONString(snapshot));
        return fact;
    }
}
