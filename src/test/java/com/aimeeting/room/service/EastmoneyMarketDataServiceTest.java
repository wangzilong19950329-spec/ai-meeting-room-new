package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EastmoneyMarketDataServiceTest {

    @Test
    void calculatesDeterministicIndicatorsFromKline() {
        EastmoneyMarketDataService service = new EastmoneyMarketDataService(mock(StockQuoteService.class));
        List<Map<String, Object>> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 80; i++) {
            double close = 100 + i;
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", start.plusDays(i).toString());
            bar.put("open", close - 0.5);
            bar.put("close", close);
            bar.put("high", close + 1);
            bar.put("low", close - 1);
            bar.put("volume", 1000.0 + i * 10);
            bars.add(bar);
        }

        Map<String, Object> indicators = service.calculateIndicators(bars);

        assertEquals(177.0, (Double) indicators.get("ma5"), 0.0001);
        assertEquals(169.5, (Double) indicators.get("ma20"), 0.0001);
        assertEquals(100.0, (Double) indicators.get("rsi14"), 0.0001);
        assertTrue((Double) indicators.get("macdDif") > 0);
        assertTrue((Double) indicators.get("obv") > 0);
        assertEquals(159.0, (Double) indicators.get("support20"), 0.0001);
        assertEquals(180.0, (Double) indicators.get("resistance20"), 0.0001);
    }

    @Test
    void rejectsDailyBarsWhoseLatestTradeDateDoesNotMatchCanonicalQuote() {
        EastmoneyMarketDataService service = new EastmoneyMarketDataService(mock(StockQuoteService.class));
        List<Map<String, Object>> bars = dailyBars(LocalDate.of(2026, 6, 1), 45, 50.0);
        Map<String, Object> quote = quoteFor(bars.get(bars.size() - 1), LocalDate.of(2026, 7, 16));

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "validateCanonicalDailyKlines", bars, quote, "test"));
    }

    @Test
    void acceptsStrictSameDateOhlcvAndAlignsRelativeStrengthByCommonTradeDate() {
        EastmoneyMarketDataService service = new EastmoneyMarketDataService(mock(StockQuoteService.class));
        List<Map<String, Object>> stock = dailyBars(LocalDate.of(2026, 6, 1), 46, 50.0);
        LocalDate latest = LocalDate.parse(String.valueOf(stock.get(stock.size() - 1).get("date")));
        Map<String, Object> quote = quoteFor(stock.get(stock.size() - 1), latest);
        ReflectionTestUtils.invokeMethod(service, "validateCanonicalDailyKlines", stock, quote, "test");

        List<Map<String, Object>> benchmark = dailyBars(LocalDate.of(2026, 5, 29), 49, 100.0);
        Double result = ReflectionTestUtils.invokeMethod(service, "relativeStrength", stock, benchmark, 20);
        assertTrue(result != null && Double.isFinite(result));
    }

    private List<Map<String, Object>> dailyBars(LocalDate start, int count, double initialClose) {
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = initialClose + i * 0.1;
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", start.plusDays(i).toString());
            bar.put("open", close - 0.1);
            bar.put("close", close);
            bar.put("high", close + 0.2);
            bar.put("low", close - 0.2);
            bar.put("volume", 1000.0 + i);
            bars.add(bar);
        }
        return bars;
    }

    private Map<String, Object> quoteFor(Map<String, Object> latest, LocalDate quoteDate) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("timestamp", quoteDate.atTime(15, 0).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond());
        quote.put("price", latest.get("close"));
        quote.put("open", latest.get("open"));
        quote.put("high", latest.get("high"));
        quote.put("low", latest.get("low"));
        quote.put("volume", latest.get("volume"));
        return quote;
    }

    @Test
    void hongKongIndicatorRowsKeepPeriodsRatiosAndTheValuationInputs() {
        com.alibaba.fastjson.JSONObject root = com.alibaba.fastjson.JSON.parseObject("{\"result\":{\"data\":[{"
                + "\"START_DATE\":\"2026-04-01 00:00:00\",\"REPORT_DATE\":\"2026-06-30 00:00:00\",\"REPORT_TYPE\":\"2026年一季报\","
                + "\"OPERATE_INCOME\":268953000000,\"OPERATE_INCOME_YOY\":8.6011823042,\"HOLDER_PROFIT\":10614000000,"
                + "\"HOLDER_PROFIT_YOY\":-73.8886565475,\"GROSS_PROFIT_RATIO\":38.243484921157,\"NET_PROFIT_RATIO\":3.8832063595,"
                + "\"ROE_AVG\":0.998798787778,\"DEBT_ASSET_RATIO\":43.2297594068,\"OCF_SALES\":8.531230363669,"
                + "\"PE_TTM\":25.483043963964,\"PB_TTM\":1.78,\"TOTAL_MARKET_CAP\":2165333195123.2,"
                + "\"ISSUED_COMMON_SHARES\":19901959514,\"CURRENCY\":\"HKD\"}]}}");

        List<Map<String, Object>> rows = EastmoneyMarketDataService.hongKongIndicatorRows(root);

        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("2026-04-01", row.get("startDate"));
        assertEquals("2026-06-30", row.get("reportDate"));
        assertEquals("2026年一季报", row.get("reportType"));
        assertEquals(new java.math.BigDecimal("268953000000"), row.get("revenue"));
        assertEquals(-73.8886565475, ((java.math.BigDecimal) row.get("netProfitYoyPct")).doubleValue(), 1e-9);
        assertEquals(8.531230363669, ((java.math.BigDecimal) row.get("cashToRevenuePct")).doubleValue(), 1e-9);
        assertEquals(new java.math.BigDecimal("19901959514"), row.get("shares"));
        assertEquals(2165333195123.2, ((java.math.BigDecimal) row.get("marketCap")).doubleValue(), 1e-3);
        assertTrue(!row.containsKey("currency"), "the table's currency is the trading currency, not the report's");
    }
}
