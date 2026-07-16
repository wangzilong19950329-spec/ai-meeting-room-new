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
}
