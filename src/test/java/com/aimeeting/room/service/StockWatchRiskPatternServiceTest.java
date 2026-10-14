package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StockWatchRiskPatternServiceTest {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final StockWatchRiskPatternService service = new StockWatchRiskPatternService();

    @Test
    void r01TriggersOnlyWithFreshQuoteAndCompletedBaseline() {
        ZonedDateTime quoteAt = ZonedDateTime.of(2026, 7, 16, 10, 0, 0, 0, CHINA);
        Map<String, Object> quote = quote(quoteAt, 99.0, 99.0, 101.0, 96.0, 98.0, 1.5);

        StockWatchRiskPatternService.RiskPatternResult result = service.analyze(
                "002979", "sz", quote, baseline(20, 100.0), quoteAt.plusMinutes(2));

        assertEquals("ACTIVE", result.getObservations().get("evaluationStatus"));
        assertTrue(result.hasTriggeredPattern());
        assertEquals("triggered", pattern(result, "R01").get("status"));
    }

    @Test
    void intradayAmountCannotSubstituteForTimeNormalizedVolumeRatio() {
        ZonedDateTime quoteAt = ZonedDateTime.of(2026, 7, 16, 10, 0, 0, 0, CHINA);
        Map<String, Object> quote = quote(quoteAt, 100.2, 100.0, 100.8, 99.5, 100.0, 0.8);
        quote.put("amount", 9_000_000_000.0);
        quote.put("changePercent", 0.2);

        StockWatchRiskPatternService.RiskPatternResult result = service.analyze(
                "002979", "sz", quote, baseline(20, 102.0), quoteAt.plusMinutes(2));

        assertEquals("not_triggered", pattern(result, "R02").get("status"));
        Map<?, ?> derived = (Map<?, ?>) result.getObservations().get("derived");
        assertEquals(Boolean.FALSE, derived.get("amountRatio20EligibleForTrigger"));
    }

    @Test
    void staleQuoteSkipsAllPatterns() {
        ZonedDateTime quoteAt = ZonedDateTime.of(2026, 7, 16, 10, 0, 0, 0, CHINA);
        StockWatchRiskPatternService.RiskPatternResult result = service.analyze(
                "002979", "sz", quote(quoteAt, 99, 99, 101, 96, 98, 2.0), baseline(20, 100), quoteAt.plusMinutes(10));

        assertEquals("SKIPPED_UNAVAILABLE", result.getObservations().get("evaluationStatus"));
        assertFalse(result.hasTriggeredPattern());
        assertEquals("skipped", pattern(result, "R01").get("status"));
    }

    @Test
    void hongKongMarketIsExplicitlyOutsideL0Capability() {
        ZonedDateTime quoteAt = ZonedDateTime.of(2026, 7, 16, 10, 0, 0, 0, CHINA);
        StockWatchRiskPatternService.RiskPatternResult result = service.analyze(
                "09988", "hk", quote(quoteAt, 99, 99, 101, 96, 98, 2.0), baseline(20, 100), quoteAt.plusMinutes(1));

        assertEquals("SKIPPED_UNAVAILABLE", result.getObservations().get("evaluationStatus"));
        assertTrue(String.valueOf(result.getObservations().get("riskEligibilityMissing")).contains("market_not_supported_for_l0"));
    }

    @Test
    void r04RequiresCurrentLongUpperShadowAndAtLeastOneHistoricalMatch() {
        ZonedDateTime quoteAt = ZonedDateTime.of(2026, 7, 16, 10, 0, 0, 0, CHINA);
        List<Map<String, Object>> bars = baseline(20, 110.0);
        Map<String, Object> historicalMatch = bars.get(bars.size() - 1);
        historicalMatch.put("open", 100.0);
        historicalMatch.put("close", 99.0);
        historicalMatch.put("high", 110.0);
        historicalMatch.put("low", 98.0);
        Map<String, Object> current = quote(quoteAt, 99.0, 100.0, 110.0, 98.0, 100.0, 1.0);

        StockWatchRiskPatternService.RiskPatternResult result = service.analyze(
                "002979", "sz", current, bars, quoteAt.plusMinutes(1));

        assertEquals("triggered", pattern(result, "R04").get("status"));
        assertEquals(2, pattern(result, "R04").get("windowMatchCount"));
    }

    @Test
    void derivesPointInTimePositionFeaturesFromCurrentQuoteAndCompletedBarsOnly() {
        ZonedDateTime quoteAt = ZonedDateTime.of(2026, 7, 16, 10, 0, 0, 0, CHINA);
        List<Map<String, Object>> bars = baseline(20, 100.0);
        Map<String, Object> current = quote(quoteAt, 110.0, 108.0, 112.0, 107.0, 99.0, 2.1);

        StockWatchRiskPatternService.RiskPatternResult result = service.analyze(
                "002979", "sz", current, bars, quoteAt.plusMinutes(1));

        Map<?, ?> derived = (Map<?, ?>) result.getObservations().get("derived");
        Map<?, ?> research = result.getPriceFailureResearchFeatures();
        assertEquals((110.0 / 99.0 - 1.0) * 100.0, (Double) research.get("return5Pct"), 0.000001);
        assertEquals((19 * 99.0 + 110.0) / 20.0, (Double) research.get("ma20"), 0.000001);
        assertNotNull(research.get("closePosition20dPct"));
        assertNotNull(research.get("volatility20Pct"));
        assertFalse(derived.containsKey("return5Pct"));
        assertFalse(derived.containsKey("priceVsMa20Pct"));
    }

    private Map<String, Object> pattern(StockWatchRiskPatternService.RiskPatternResult result, String id) {
        for (Object raw : (List<?>) result.getObservations().get("patterns")) {
            Map<String, Object> item = (Map<String, Object>) raw;
            if (id.equals(item.get("patternId"))) return item;
        }
        throw new AssertionError("missing pattern " + id);
    }

    private Map<String, Object> quote(ZonedDateTime observedAt,
                                      double close,
                                      double open,
                                      double high,
                                      double low,
                                      double previousClose,
                                      double volumeRatio) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("symbol", "002979");
        quote.put("market", "sz");
        quote.put("source", "test");
        quote.put("timestamp", observedAt.toEpochSecond());
        quote.put("price", close);
        quote.put("open", open);
        quote.put("high", high);
        quote.put("low", low);
        quote.put("previousClose", previousClose);
        quote.put("changePercent", (close / previousClose - 1.0) * 100.0);
        quote.put("volumeRatio", volumeRatio);
        quote.put("amount", 200_000_000.0);
        quote.put("turnover", 3.0);
        return quote;
    }

    private List<Map<String, Object>> baseline(int count, double high) {
        List<Map<String, Object>> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 6, 1);
        for (int i = 0; i < count; i++) {
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", start.plusDays(i).toString());
            bar.put("open", high - 2.0);
            bar.put("close", high - 1.0);
            bar.put("high", high);
            bar.put("low", high - 3.0);
            bar.put("amount", 100_000_000.0);
            bar.put("turnoverPct", 2.0);
            bars.add(bar);
        }
        return bars;
    }
}
