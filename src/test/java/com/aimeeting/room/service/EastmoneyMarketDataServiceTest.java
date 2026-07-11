package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
