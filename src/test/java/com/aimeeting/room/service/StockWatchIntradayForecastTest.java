package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchFactHeadMapper;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchIntentTestMapper;
import com.aimeeting.room.dao.StockWatchIntentTestResultMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StockWatchIntradayForecastTest {
    private static final Instant START = Instant.parse("2026-10-08T02:00:00Z");

    private Map<String, Object> quote() {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("symbol", "002156");
        quote.put("market", "sz");
        quote.put("providerSymbol", "002156");
        quote.put("providerMarket", "sz");
        quote.put("priceBasis", "UNADJUSTED");
        quote.put("price", 10d);
        quote.put("timestamp", START.getEpochSecond());
        return quote;
    }

    private StockWatchTechnicalAnalysis record(JSONObject request, String raw) {
        String projection = JSON.toJSONString(Map.of("intradayForecastRequest", request));
        String hash;
        // Use the same SHA-256 encoding required by the frozen envelope, not a regenerated time window.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(projection.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            hash = java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setSymbol("002156");
        record.setMarket("sz");
        record.setPeriodType("DAY");
        record.setAnalysisDate(Date.from(START));
        record.setRunSlot("10:00");
        record.setStatus("SUCCESS");
        record.setResponseContractVersion(6);
        record.setTaskRequestDigest("a".repeat(64));
        record.setTaskRequestEnvelopeJson(JSON.toJSONString(Map.of("requestDigest", "a".repeat(64),
                "projectionJson", projection, "projectionDigest", hash)));
        record.setGmtCreate(Date.from(START.plusSeconds(180)));
        record.setRawResponse(raw);
        return record;
    }

    private String prediction() {
        return "{\"intradayForecast\":{\"status\":\"PREDICTED\",\"direction\":\"DOWN\",\"targetLow\":9.8,\"targetHigh\":9.95}}";
    }

    @Test
    void fixedDeadlineAndExplicitResearchBoundary() {
        JSONObject request = StockWatchIntradayForecast.request("002156", "sz", quote(), START);
        assertEquals("2026-10-08T02:30:00Z", request.getString("targetAt"));
        assertEquals("ELIGIBLE", request.getString("inputStatus"));
        assertEquals("UNVERIFIED_WEB_SEARCH", request.getString("informationCutoffStatus"));
        Map<String, Object> view = StockWatchIntradayForecast.view(record(request, prediction()), START.plusSeconds(180));
        assertEquals("PREDICTED", view.get("status"));
        assertEquals("ACTIVE", view.get("lifecycle"));
        assertEquals("DB_INSERT_PROXY", view.get("timingBasis"));
        assertEquals("EXPIRED", StockWatchIntradayForecast.view(record(request, prediction()), START.plusSeconds(1800)).get("lifecycle"));
    }

    @Test
    void windowsDoNotCrossLunchCloseOrWeekend() {
        for (String time : List.of("2026-10-08T03:00:00Z", "2026-10-08T06:30:00Z"))
            assertTrue(StockWatchIntradayForecast.continuousWindow(Instant.parse(time)));
        for (String time : List.of("2026-10-08T03:00:00.001Z", "2026-10-08T06:30:00.001Z",
                "2026-10-08T04:00:00Z", "2026-10-10T02:00:00Z", "2026-10-08T01:29:59Z"))
            assertFalse(StockWatchIntradayForecast.continuousWindow(Instant.parse(time)));
    }

    @Test
    void sourceAgeIdentityAndFinitePriceAreRequired() {
        Map<String, Object> quote = quote();
        quote.put("timestamp", START.minusSeconds(120).getEpochSecond());
        assertEquals("ELIGIBLE", StockWatchIntradayForecast.request("002156", "sz", quote, START).get("inputStatus"));
        quote.put("timestamp", START.minusSeconds(121).getEpochSecond());
        assertEquals("STALE_INPUT", StockWatchIntradayForecast.request("002156", "sz", quote, START).get("inputStatus"));
        quote.put("timestamp", START.plusSeconds(1).getEpochSecond());
        assertEquals("FUTURE_INPUT", StockWatchIntradayForecast.request("002156", "sz", quote, START).get("inputStatus"));
        quote.put("timestamp", START.getEpochSecond());
        quote.put("providerMarket", "sh");
        assertEquals("IDENTITY_UNVERIFIED", StockWatchIntradayForecast.request("002156", "sz", quote, START).get("inputStatus"));
        quote.put("providerMarket", "sz");
        for (Object price : List.of(true, "10.0", Double.NaN, Double.POSITIVE_INFINITY, 0d, -1d)) {
            quote.put("price", price);
            assertEquals("PRICE_UNAVAILABLE", StockWatchIntradayForecast.request("002156", "sz", quote, START).get("inputStatus"));
        }
        assertFalse(StockWatchIntradayForecast.ordinaryStock("510300", "sh"));
        assertFalse(StockWatchIntradayForecast.ordinaryStock("09988", "hk"));
    }

    @Test
    void absentInvalidAndAbstainedExtensionsAreIndependentStates() {
        JSONObject request = StockWatchIntradayForecast.request("002156", "sz", quote(), START);
        assertEquals("MISSING", StockWatchIntradayForecast.view(record(request, "{}"), START.plusSeconds(180)).get("status"));
        for (String raw : List.of("{\"intradayForecast\":true}", "{\"intradayForecast\":null}",
                "{\"intradayForecast\":{\"status\":\"PREDICTED\",\"direction\":\"DOWN\",\"targetLow\":true,\"targetHigh\":10}}",
                "{\"intradayForecast\":{\"status\":\"ABSTAIN\",\"direction\":\"DOWN\"}}"))
            assertEquals("INVALID", StockWatchIntradayForecast.view(record(request, raw), START.plusSeconds(180)).get("status"));
        String abstain = "{\"intradayForecast\":{\"status\":\"ABSTAIN\",\"direction\":null,\"targetLow\":null,\"targetHigh\":null}}";
        assertEquals("ABSTAIN", StockWatchIntradayForecast.view(record(request, abstain), START.plusSeconds(180)).get("status"));
    }

    @Test
    void oldTasksAreNotUpgradedByReadingAndUntrustedOptionalDataNeverThrows() {
        StockWatchTechnicalAnalysis old = new StockWatchTechnicalAnalysis();
        old.setRawResponse(prediction());
        assertNull(StockWatchIntradayForecast.view(old, START));
        old.setTaskRequestEnvelopeJson("{\"projectionJson\":\"{\\\"intradayForecastRequest\\\":true}\"}");
        assertEquals("INVALID_REQUEST", StockWatchIntradayForecast.view(old, START).get("status"));
        JSONObject request = StockWatchIntradayForecast.request("002156", "sz", quote(), START);
        StockWatchTechnicalAnalysis tampered = record(request, prediction());
        tampered.setTaskRequestDigest("different");
        assertEquals("INVALID_REQUEST", StockWatchIntradayForecast.view(tampered, START).get("status"));
    }

    @Test
    void lateAndUnknownRecordingTimesAreNotCurrentSignals() {
        StockWatchTechnicalAnalysis record = record(StockWatchIntradayForecast.request("002156", "sz", quote(), START), prediction());
        record.setGmtCreate(Date.from(START.plusSeconds(301)));
        assertEquals("LATE", StockWatchIntradayForecast.view(record, START.plusSeconds(301)).get("timingStatus"));
        assertEquals("UNAVAILABLE", StockWatchIntradayForecast.view(record, START.plusSeconds(301)).get("lifecycle"));
        record.setGmtCreate(null);
        assertEquals("UNKNOWN", StockWatchIntradayForecast.view(record, START.plusSeconds(301)).get("timingStatus"));
    }

    @Test
    void apiOnlyExposesWhitelistedM30AndDoesNotExposeFrozenPrivateEnvelope() {
        StockWatchTechnicalAnalysisService service = service();
        StockWatchTechnicalAnalysis record = record(StockWatchIntradayForecast.request("002156", "sz", quote(), START), prediction());
        record.setDataSnapshotJson("{}");
        Map<?, ?> payload = ReflectionTestUtils.invokeMethod(service, "toRecordPayload", record);
        assertEquals("SUCCESS", payload.get("status"));
        assertNotNull(payload.get("intradayForecast"));
        assertFalse(payload.containsKey("taskRequestEnvelopeJson"));
        assertFalse(payload.containsKey("rawResponse"));
    }

    @Test
    void pilotSymbolsAreExplicitDistinctAndBounded() {
        StockWatchTechnicalAnalysisService service = service();
        ReflectionTestUtils.setField(service, "m30Symbols", "002156.sz,002156.sz,09988.hk,600026.sh,300760.sz,600519.sh");
        assertEquals(List.of("002156.sz", "600026.sh", "300760.sz"), ReflectionTestUtils.invokeMethod(service, "m30PilotSymbols"));
    }

    @Test
    void m30OnlyAttachesToOrdinaryNewDayTasks() {
        StockWatchTechnicalAnalysisService service = service();
        ReflectionTestUtils.setField(service, "m30Enabled", true);
        ReflectionTestUtils.setField(service, "m30Symbols", "300750.sz");
        Object ref = ReflectionTestUtils.invokeMethod(service, "normalizeStockRef", "300750", "sz");

        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.invokeMethod(service, "shouldAttachIntradayForecast", "DAY", ref, null));
        assertEquals(Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(service, "shouldAttachIntradayForecast", "WEEK", ref, null));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(service, "shouldAttachIntradayForecast", "DAY", ref,
                new StockWatchFactObservation()));
    }

    @Test
    void quoteProvenanceMetadataDoesNotChangeCanonicalFactSnapshot() {
        StockWatchFactPersistenceService facts = new StockWatchFactPersistenceService(mock(StockWatchFactHeadMapper.class),
                mock(StockWatchFactObservationMapper.class), mock(StockWatchIntentTestMapper.class), mock(StockWatchIntentTestResultMapper.class));
        Map<String, Object> withMetadata = quote();
        Map<String, Object> without = new LinkedHashMap<>(withMetadata);
        for (String key : List.of("providerSymbol", "providerMarket", "sourceEndpoint", "priceBasis")) without.remove(key);
        StockWatchRiskPatternService risk = new StockWatchRiskPatternService();
        var asOf = START.atZone(ZoneId.of("Asia/Shanghai"));
        Object before = ReflectionTestUtils.invokeMethod(facts, "canonicalFactSnapshot", "002156", "sz", without,
                risk.analyze("002156", "sz", without, List.of(), asOf));
        Object after = ReflectionTestUtils.invokeMethod(facts, "canonicalFactSnapshot", "002156", "sz", withMetadata,
                risk.analyze("002156", "sz", withMetadata, List.of(), asOf));
        assertEquals(JSON.toJSONString(before), JSON.toJSONString(after));
    }

    private StockWatchTechnicalAnalysisService service() {
        return new StockWatchTechnicalAnalysisService(mock(EastmoneyMarketDataService.class), mock(StockWatchPredictionService.class),
                mock(StockWatchListService.class), mock(StockWatchTechnicalSessionMapper.class), mock(StockWatchTechnicalAnalysisMapper.class),
                mock(StockWatchAnalysisTaskService.class), mock(StockWatchAnalysisProjectionService.class), mock(StockWatchRiskPatternService.class),
                mock(StockWatchFactPersistenceService.class), mock(StockWatchFactObservationMapper.class), mock(StockWatchV6RuntimeGate.class),
                mock(StockWatchBridgeAdmissionService.class));
    }
}
