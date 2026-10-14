package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockWatchN8nFacadeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T01:30:00Z");

    @Test
    void normalizesAshareVolumeAndAmountWithExplicitUnits() {
        StockQuoteService quoteService = mock(StockQuoteService.class);
        when(quoteService.fetchQuotes("300750.sz")).thenReturn(List.of(validQuote(NOW.minusSeconds(10))));
        when(quoteService.fetchIntradayBars("300750", "sz")).thenReturn(intraday(NOW.minusSeconds(10)));
        StockWatchN8nFacadeService service = service(quoteService);

        Map<String, Object> envelope = service.fetchQuotes("300750.sz");
        Map<String, Object> item = firstItem(envelope);
        Map<?, ?> cumulative = (Map<?, ?>) item.get("cumulative");
        Map<?, ?> quality = (Map<?, ?>) item.get("quality");

        assertEquals("PARTIAL", quality.get("status"));
        assertEquals("LOT_100_SHARES", cumulative.get("volumeUnit"));
        assertEquals(12_300.0, cumulative.get("volumeShares"));
        assertEquals("CNY", cumulative.get("amountUnit"));
        assertEquals("EMPIRICAL_A_SHARE_ONLY", cumulative.get("unitContractStatus"));
        assertEquals(List.of("EMPIRICAL_EASTMONEY_F5_F6_UNIT_CONTRACT"), quality.get("limitations"));
        assertEquals(Boolean.TRUE, envelope.get("allUsable"));
    }

    @Test
    void marksOldMarketTimestampStale() {
        StockQuoteService quoteService = mock(StockQuoteService.class);
        when(quoteService.fetchQuotes("300750.sz")).thenReturn(List.of(validQuote(NOW.minusSeconds(600))));
        when(quoteService.fetchIntradayBars("300750", "sz")).thenReturn(intraday(NOW.minusSeconds(600)));

        Map<String, Object> item = firstItem(service(quoteService).fetchQuotes("300750.sz"));
        Map<?, ?> quality = (Map<?, ?>) item.get("quality");

        assertEquals("STALE", quality.get("status"));
        assertEquals(List.of("STALE_QUOTE"), quality.get("errors"));
    }

    @Test
    void rejectsInvalidIntradayEnvelopeEvenWhenItContainsARecentTimestamp() {
        StockQuoteService quoteService = mock(StockQuoteService.class);
        when(quoteService.fetchQuotes("300750.sz")).thenReturn(List.of(validQuote(NOW.minusSeconds(10))));
        Map<String, Object> invalidIntraday = new LinkedHashMap<>(intraday(NOW.minusSeconds(10)));
        invalidIntraday.put("status", "INVALID");
        when(quoteService.fetchIntradayBars("300750", "sz")).thenReturn(invalidIntraday);

        Map<String, Object> envelope = service(quoteService).fetchQuotes("300750.sz");
        Map<?, ?> quality = (Map<?, ?>) firstItem(envelope).get("quality");

        assertEquals("INVALID", quality.get("status"));
        assertEquals(List.of("INTRADAY_STATUS_INVALID", "MISSING_MARKET_DATA_TIME"), quality.get("errors"));
        assertEquals(Boolean.FALSE, envelope.get("allUsable"));
    }

    @Test
    void evaluatesFreshnessAfterProviderCallsComplete() {
        StockQuoteService quoteService = mock(StockQuoteService.class);
        AtomicReference<Instant> current = new AtomicReference<>(NOW);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> current.get());
        when(quoteService.fetchQuotes("300750.sz")).thenReturn(List.of(validQuote(NOW)));
        when(quoteService.fetchIntradayBars("300750", "sz")).thenAnswer(ignored -> {
            current.set(NOW.plusSeconds(200));
            return intraday(NOW);
        });
        StockWatchN8nFacadeService service = new StockWatchN8nFacadeService(quoteService, clock, 180, 5);

        Map<String, Object> item = firstItem(service.fetchQuotes("300750.sz"));
        Map<?, ?> quality = (Map<?, ?>) item.get("quality");

        assertEquals("STALE", quality.get("status"));
        assertEquals(200L, quality.get("quoteAgeSeconds"));
    }

    @Test
    void sourceFailureDoesNotInventPrices() {
        StockQuoteService quoteService = mock(StockQuoteService.class);
        when(quoteService.fetchQuotes("300750.sz")).thenReturn(List.of(Map.of(
                "ok", false,
                "symbol", "300750",
                "market", "sz",
                "source", "Eastmoney quote",
                "reason", "eastmoney_error"
        )));

        Map<String, Object> envelope = service(quoteService).fetchQuotes("300750.sz");
        Map<String, Object> item = firstItem(envelope);
        Map<?, ?> quality = (Map<?, ?>) item.get("quality");

        assertEquals("SOURCE_ERROR", quality.get("status"));
        assertFalse(item.containsKey("pricing"));
        assertNull(item.get("quoteTime"));
        assertEquals(Boolean.FALSE, envelope.get("allUsable"));
    }

    @Test
    void rejectsMarketsOutsideTheAshareUnitContract() {
        StockQuoteService quoteService = mock(StockQuoteService.class);
        Map<String, Object> unsupported = validQuote(NOW.minusSeconds(10));
        unsupported.put("market", "hk");
        when(quoteService.fetchQuotes("00700.hk")).thenReturn(List.of(unsupported));

        Map<String, Object> item = firstItem(service(quoteService).fetchQuotes("00700.hk"));
        Map<?, ?> quality = (Map<?, ?>) item.get("quality");

        assertEquals("INVALID", quality.get("status"));
        assertEquals(List.of("UNSUPPORTED_MARKET_UNIT_CONTRACT", "MISSING_MARKET_DATA_TIME"), quality.get("errors"));
    }

    private StockWatchN8nFacadeService service(StockQuoteService quoteService) {
        return new StockWatchN8nFacadeService(
                quoteService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                180,
                5
        );
    }

    private Map<String, Object> validQuote(Instant quoteTime) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("source", "Eastmoney quote");
        quote.put("symbol", "300750");
        quote.put("market", "sz");
        quote.put("name", "test");
        quote.put("price", 100.0);
        quote.put("open", 99.0);
        quote.put("high", 101.0);
        quote.put("low", 98.0);
        quote.put("previousClose", 99.5);
        quote.put("volume", 123.0);
        quote.put("amount", 1_230_000.0);
        quote.put("timestamp", quoteTime.getEpochSecond());
        return quote;
    }

    private Map<String, Object> intraday(Instant marketTime) {
        String asOfTime = java.time.LocalDateTime.ofInstant(marketTime, java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return Map.of("status", "VALID", "asOfTime", asOfTime);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstItem(Map<String, Object> envelope) {
        return ((List<Map<String, Object>>) envelope.get("items")).get(0);
    }
}
