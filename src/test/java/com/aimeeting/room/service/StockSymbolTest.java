package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockSymbolTest {

    @Test
    void normalizesHongKongInputForms() {
        assertStock("H09988", "", "09988", "hk", "116.09988");
        assertStock("HK03441", "", "03441", "hk", "116.03441");
        assertStock("09988.HK", "", "09988", "hk", "116.09988");
        assertStock("9988", "", "09988", "hk", "116.09988");
        assertStock("03441", "", "03441", "hk", "116.03441");
    }

    @Test
    void keepsMainlandNormalizationCompatible() {
        assertStock("688017.SH", "", "688017", "sh", "1.688017");
        assertStock("sz300750", "", "300750", "sz", "0.300750");
        assertStock("920001", "bj", "920001", "bj", "0.920001");
    }

    @Test
    void rejectsSixDigitSymbolForcedIntoHongKongMarket() {
        assertThrows(IllegalArgumentException.class, () -> StockSymbol.parse("099988", "hk"));
    }

    private void assertStock(String rawSymbol,
                             String rawMarket,
                             String expectedSymbol,
                             String expectedMarket,
                             String expectedSecid) {
        StockSymbol stock = StockSymbol.parse(rawSymbol, rawMarket);
        assertEquals(expectedSymbol, stock.getSymbol());
        assertEquals(expectedMarket, stock.getMarket());
        assertEquals(expectedSecid, stock.eastmoneySecid());
    }
}
