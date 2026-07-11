package com.aimeeting.room.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchExternalResearchServiceTest {

    private StockWatchExternalResearchService service;

    @BeforeEach
    void setUp() {
        service = new StockWatchExternalResearchService();
    }

    @Test
    void unsupportedMarketDoesNotAffectMainChains() {
        Map<String, Object> payload = service.fetchEvidence("430047", "bj");

        assertFalse(Boolean.TRUE.equals(payload.get("ok")));
        assertEquals("UNSUPPORTED_MARKET", payload.get("status"));
        assertFalse(Boolean.TRUE.equals(payload.get("mailTriggerAffected")));
        assertFalse(Boolean.TRUE.equals(payload.get("monitorAffected")));
    }

    @Test
    void comparisonNormalizesEastmoneyVolumeHandsToShares() {
        Map<String, Object> tradingView = new LinkedHashMap<>();
        tradingView.put("ok", true);
        tradingView.put("price", 152.80);
        tradingView.put("changePercent", 8.3227);
        tradingView.put("volumeShares", 88_985_081.0);

        Map<String, Object> eastmoney = new LinkedHashMap<>();
        eastmoney.put("ok", true);
        eastmoney.put("price", 152.80);
        eastmoney.put("changePercent", 8.32);
        eastmoney.put("volumeShares", 88_985_100.0);

        Map<String, Object> comparison = service.compareSnapshots(tradingView, eastmoney);

        assertEquals("MATCH", comparison.get("verdict"));
        assertTrue(Boolean.TRUE.equals(comparison.get("priceMatched")));
        assertTrue(Boolean.TRUE.equals(comparison.get("changePercentMatched")));
        assertTrue(Boolean.TRUE.equals(comparison.get("volumeMatched")));
    }
}
