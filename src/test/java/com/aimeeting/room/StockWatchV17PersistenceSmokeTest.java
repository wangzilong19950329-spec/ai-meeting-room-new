package com.aimeeting.room;

import com.aimeeting.room.service.StockWatchAnalysisTaskService;
import com.aimeeting.room.service.StockWatchAnalysisTaskService.Reservation;
import com.aimeeting.room.service.StockWatchAnalysisProjectionService;
import com.aimeeting.room.service.StockWatchFactPersistenceService;
import com.aimeeting.room.service.StockWatchRiskPatternService;
import com.aimeeting.room.service.StockWatchRiskPatternService.RiskPatternResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.entity.StockWatchTechnicalSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class StockWatchV17PersistenceSmokeTest {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    @Autowired StockWatchRiskPatternService riskPatternService;
    @Autowired StockWatchFactPersistenceService factPersistenceService;
    @Autowired StockWatchAnalysisTaskService analysisTaskService;
    @Autowired StockWatchAnalysisProjectionService projectionService;
    @Autowired StockWatchTechnicalAnalysisMapper technicalAnalysisMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void concurrentClaimsChooseOneOwnerBeforeSessionWorkAndWinnerCanPrepareFrozenRequest() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Reservation> claim = () -> {
                start.await();
                return analysisTaskService.claim("stock-analysis-v2:sz:009998:DAY:2026-07-16:10:30",
                        "stock-analysis-v1", "009998", "sz", "DAY", "DAY:2026-07-16:10:30",
                        "deepseek_web_bridge");
            };
            Future<Reservation> first = executor.submit(claim);
            Future<Reservation> second = executor.submit(claim);
            start.countDown();
            Reservation left = first.get();
            Reservation right = second.get();
            assertEquals(1, (left.isNewSubmission() ? 1 : 0) + (right.isNewSubmission() ? 1 : 0));

            Reservation owner = left.isNewSubmission() ? left : right;
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema", "stock-analysis-v1");
            envelope.put("projectionDigest", "projection-digest");
            analysisTaskService.prepare(owner, analysisTaskService.sha256("prepared prompt"), 3, envelope);
            assertEquals(3, owner.getTask().getGeneration());
            assertEquals(1L, owner.getTask().getRowVersion());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateFactDoesNotAdvanceAndSecondObservationCanFalsifyFirstTest() {
        ZonedDateTime firstAt = ZonedDateTime.of(2026, 7, 16, 10, 0, 0, 0, CHINA);
        Map<String, Object> firstQuote = quote("009991", firstAt, 99.0, 99.0, 101.0, 96.0, 98.0, 1.5);
        RiskPatternResult firstRisk = riskPatternService.analyze(
                "009991", "sz", firstQuote, baseline(20, 100.0), firstAt.plusMinutes(1));

        Map<String, Object> first = factPersistenceService.observe(
                "009991", "sz", "V17测试股", firstQuote, firstRisk, firstAt.plusMinutes(1)).toMap();
        Map<String, Object> duplicate = factPersistenceService.observe(
                "009991", "sz", "V17测试股", firstQuote, firstRisk, firstAt.plusMinutes(1)).toMap();

        assertEquals("CREATED", first.get("status"));
        assertTrue(((Number) first.get("createdTestCount")).intValue() >= 1);
        assertEquals("DUPLICATE", duplicate.get("status"));

        ZonedDateTime secondAt = firstAt.plusMinutes(2);
        Map<String, Object> secondQuote = quote("009991", secondAt, 101.5, 100.0, 102.0, 99.0, 99.0, 1.0);
        RiskPatternResult secondRisk = riskPatternService.analyze(
                "009991", "sz", secondQuote, baseline(20, 100.0), secondAt.plusMinutes(1));
        Map<String, Object> second = factPersistenceService.observe(
                "009991", "sz", "V17测试股", secondQuote, secondRisk, secondAt.plusMinutes(1)).toMap();

        assertEquals("CREATED", second.get("status"));
        List<?> verdicts = (List<?>) second.get("testVerdicts");
        assertFalse(verdicts.isEmpty());
        assertTrue(verdicts.stream().anyMatch(item -> String.valueOf(item).contains("FALSIFIED")));
    }

    @Test
    void invalidResponseReceiptPreventsSameSubmissionKeyFromBecomingNewAgain() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "stock-analysis-v1");
        Reservation first = analysisTaskService.reserve(
                "smoke-invalid-response-009992", analysisTaskService.sha256("prompt"), "stock-analysis-v1",
                "009992", "sz", "DAY", "obs-1", 1, "deepseek-web", envelope);
        assertTrue(first.isNewSubmission());

        analysisTaskService.markSubmitted(first, "deepseek-web");
        analysisTaskService.markInvalid(first, "not-json", "invalid", "json_parse_failed", "invalid JSON");

        Reservation second = analysisTaskService.reserve(
                "smoke-invalid-response-009992", analysisTaskService.sha256("prompt"), "stock-analysis-v1",
                "009992", "sz", "DAY", "obs-1", 1, "deepseek-web", envelope);
        assertFalse(second.isNewSubmission());
        assertFalse(second.hasReusableSuccess());
        assertEquals("INVALID_RESPONSE", second.getTask().getStatus());

        Reservation changedPrompt = analysisTaskService.reserve(
                "smoke-invalid-response-009992", analysisTaskService.sha256("changed prompt"), "stock-analysis-v1",
                "009992", "sz", "DAY", "obs-1", 1, "deepseek-web", envelope);
        assertFalse(changedPrompt.isNewSubmission());
        assertFalse(changedPrompt.isRequestDigestMatch());
    }

    @Test
    void failureProjectionCommitsTerminalTaskAndAnalysisEvenWhenSessionTelemetryCannotUpdate() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "stock-analysis-v1");
        Reservation reservation = analysisTaskService.reserve(
                "smoke-failure-projection-009994", analysisTaskService.sha256("failure prompt"),
                "stock-analysis-v1", "009994", "sz", "DAY", "obs-failure", 1,
                "deepseek-web", envelope);
        analysisTaskService.markSubmitted(reservation, "deepseek-web");

        StockWatchTechnicalAnalysis record = failureRecord(
                reservation.getTask().getId(), 987654322L, "009994", "失败投影会话遥测测试股");
        StockWatchTechnicalSession missingSession = new StockWatchTechnicalSession();
        missingSession.setId(987654322L);
        missingSession.setContextChars(0);

        StockWatchTechnicalAnalysis persistedRecord = projectionService.projectFailure(
                reservation, record, missingSession, "FAILED", null, null, null,
                "forced failure", null, null);

        assertEquals(record.getId(), persistedRecord.getId());
        assertEquals(record.getId(), technicalAnalysisMapper.selectByAnalysisTaskId(
                reservation.getTask().getId()).getId());
        Reservation persisted = analysisTaskService.reserve(
                "smoke-failure-projection-009994", analysisTaskService.sha256("failure prompt"),
                "stock-analysis-v1", "009994", "sz", "DAY", "obs-failure", 1,
                "deepseek-web", envelope);
        assertEquals("FAILED", persisted.getTask().getStatus());
    }

    @Test
    void failureProjectionRollsBackTerminalTransitionWhenAnalysisInsertFails() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "stock-analysis-v1");
        Reservation reservation = analysisTaskService.reserve(
                "smoke-failure-insert-009995", analysisTaskService.sha256("insert failure prompt"),
                "stock-analysis-v1", "009995", "sz", "DAY", "obs-insert-failure", 1,
                "deepseek-web", envelope);
        analysisTaskService.markSubmitted(reservation, "deepseek-web");
        StockWatchTechnicalAnalysis existing = failureRecord(
                reservation.getTask().getId(), 987654323L, "009995", "预置冲突记录");
        technicalAnalysisMapper.insert(existing);
        StockWatchTechnicalAnalysis duplicate = failureRecord(
                reservation.getTask().getId(), 987654323L, "009995", "重复冲突记录");
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setId(987654323L);
        session.setContextChars(0);

        assertThrows(RuntimeException.class, () -> projectionService.projectFailure(
                reservation, duplicate, session, "FAILED", null, null, null,
                "forced insert failure", null, null));

        Reservation persisted = analysisTaskService.reserve(
                "smoke-failure-insert-009995", analysisTaskService.sha256("insert failure prompt"),
                "stock-analysis-v1", "009995", "sz", "DAY", "obs-insert-failure", 1,
                "deepseek-web", envelope);
        assertEquals("SUBMITTED", persisted.getTask().getStatus());
        assertEquals(existing.getId(), technicalAnalysisMapper.selectByAnalysisTaskId(
                reservation.getTask().getId()).getId());
    }

    @Test
    void responseIntegrityRejectsTamperedReplayAndInvalidReplayBecomesTerminalWithErrorRecord() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "stock-analysis-v1");
        Reservation integrity = analysisTaskService.reserve(
                "smoke-response-integrity-009996", analysisTaskService.sha256("integrity prompt"),
                "stock-analysis-v1", "009996", "sz", "DAY", "obs-integrity", 1,
                "deepseek-web", envelope);
        analysisTaskService.markSubmitted(integrity, "deepseek-web");
        analysisTaskService.markResponseReceived(integrity, "{\"answer\":true}",
                "complete", "done", "PARSED_VALID", "target-integrity",
                "https://chat.deepseek.com/a/chat/s/integrity");
        analysisTaskService.verifyResponseIntegrity(integrity);
        integrity.getTask().setRawResponse("{\"answer\":false}");
        assertThrows(IllegalStateException.class, () -> analysisTaskService.verifyResponseIntegrity(integrity));
        integrity.getTask().setRawResponse("{\"answer\":true}");
        integrity.getTask().setResponseEnvelopeJson("{\"schema\":\"tampered\"}");
        assertThrows(IllegalStateException.class, () -> analysisTaskService.verifyResponseIntegrity(integrity));

        Reservation replay = analysisTaskService.reserve(
                "smoke-invalid-replay-009997", analysisTaskService.sha256("replay prompt"),
                "stock-analysis-v1", "009997", "sz", "DAY", "obs-replay", 1,
                "deepseek-web", envelope);
        analysisTaskService.markSubmitted(replay, "deepseek-web");
        analysisTaskService.markResponseReceived(replay, "{\"answer\":true}",
                "complete", "done", "PARSED_VALID", "target-replay",
                "https://chat.deepseek.com/a/chat/s/replay");
        replay = analysisTaskService.reserve(
                "smoke-invalid-replay-009997", analysisTaskService.sha256("replay prompt"),
                "stock-analysis-v1", "009997", "sz", "DAY", "obs-replay", 1,
                "deepseek-web", envelope);
        StockWatchTechnicalAnalysis errorRecord = failureRecord(
                replay.getTask().getId(), null, "009997", "损坏重放测试股");
        errorRecord.setSessionId(null);
        errorRecord.setMainForceIntentStatus("PROJECTION_INVALID");

        projectionService.projectReplayFailure(replay, errorRecord, "analysis_response_hash_mismatch");

        Reservation terminal = analysisTaskService.reserve(
                "smoke-invalid-replay-009997", analysisTaskService.sha256("replay prompt"),
                "stock-analysis-v1", "009997", "sz", "DAY", "obs-replay", 1,
                "deepseek-web", envelope);
        assertEquals("PROJECTION_INVALID", terminal.getTask().getStatus());
        assertTrue(terminal.isTerminalFailure());
        assertEquals(errorRecord.getId(), technicalAnalysisMapper.selectByAnalysisTaskId(
                replay.getTask().getId()).getId());
    }

    @Test
    void projectionFailureRollsBackAnalysisAndPredictionsButKeepsReusableRawResponse() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "stock-analysis-v1");
        Reservation reservation = analysisTaskService.reserve(
                "smoke-projection-failure-009993", analysisTaskService.sha256("projection-prompt"),
                "stock-analysis-v1", "009993", "sz", "DAY", "obs-projection", 1,
                "deepseek-web", envelope);
        analysisTaskService.markSubmitted(reservation, "deepseek-web");
        analysisTaskService.markResponseReceived(reservation, "{\"ok\":true}",
                "complete", "done", "PARSED_VALID", "target-projection",
                "https://chat.deepseek.com/a/chat/s/projection");

        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setAnalysisTaskId(reservation.getTask().getId());
        record.setSessionId(987654321L);
        record.setSymbol("009993");
        record.setMarket("sz");
        record.setStockName("投影回滚测试股");
        record.setAnalysisDate(java.sql.Date.valueOf(LocalDate.of(2026, 7, 16)));
        record.setRunSlot("10:30");
        record.setPeriodType("DAY");
        record.setPeriodKey("2026-07-16");
        record.setStatus("SUCCESS");
        record.setStance("看涨");
        record.setTone("green");
        record.setConfidence(0.7);
        record.setTitle("事务回滚测试");
        record.setConclusion("会话更新应失败并回滚前序投影。");
        record.setEvidenceJson("[]");
        record.setMissingDataJson("[]");
        record.setDataSnapshotJson("{}");
        record.setRawResponse("{\"ok\":true}");
        record.setProvider("deepseek-web");
        record.setContextCharsDelta(20);
        StockWatchTechnicalSession missingSession = new StockWatchTechnicalSession();
        missingSession.setId(987654321L);
        missingSession.setContextChars(0);
        JSONArray forecasts = new JSONArray();
        JSONObject forecast = new JSONObject();
        forecast.put("horizon", "T1");
        forecast.put("direction", "UP");
        forecast.put("probability", 0.6);
        forecast.put("targetLow", 10.0);
        forecast.put("targetHigh", 11.0);
        forecasts.add(forecast);

        assertThrows(IllegalStateException.class, () -> projectionService.project(reservation, null,
                Collections.emptyMap(), record, forecasts, LocalDate.of(2026, 7, 16), 9.8,
                "{}", missingSession, "target", "https://chat.example/test"));

        assertNull(technicalAnalysisMapper.selectByAnalysisTaskId(reservation.getTask().getId()));
        Integer predictionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_watch_prediction WHERE analysis_id = ?", Integer.class, record.getId());
        assertEquals(0, predictionCount);
        analysisTaskService.markProjectionFailed(reservation, "analysis_projection_session_update_failed");
        Reservation retry = analysisTaskService.reserve(
                "smoke-projection-failure-009993", analysisTaskService.sha256("projection-prompt"),
                "stock-analysis-v1", "009993", "sz", "DAY", "obs-projection", 1,
                "deepseek-web", envelope);
        assertFalse(retry.isNewSubmission());
        assertTrue(retry.hasReusableResponse());
        assertEquals("PROJECTION_FAILED", retry.getTask().getStatus());
        assertEquals("{\"ok\":true}", retry.getTask().getRawResponse());
    }

    private Map<String, Object> quote(String symbol,
                                      ZonedDateTime observedAt,
                                      double close,
                                      double open,
                                      double high,
                                      double low,
                                      double previousClose,
                                      double volumeRatio) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("symbol", symbol);
        quote.put("market", "sz");
        quote.put("name", "V17测试股");
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
        quote.put("mainInflow", 20_000_000.0);
        return quote;
    }

    private StockWatchTechnicalAnalysis failureRecord(Long taskId,
                                                       Long sessionId,
                                                       String symbol,
                                                       String stockName) {
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setAnalysisTaskId(taskId);
        record.setSessionId(sessionId);
        record.setSymbol(symbol);
        record.setMarket("sz");
        record.setStockName(stockName);
        record.setAnalysisDate(java.sql.Date.valueOf(LocalDate.of(2026, 7, 16)));
        record.setRunSlot("11:00");
        record.setPeriodType("DAY");
        record.setPeriodKey("2026-07-16");
        record.setStatus("ERROR");
        record.setStance("分析失败");
        record.setTone("red");
        record.setConfidence(0.0);
        record.setTitle("失败投影事务测试");
        record.setConclusion("失败状态和记录必须保持一致");
        record.setEvidenceJson("[]");
        record.setMissingDataJson("[]");
        record.setDataSnapshotJson("{}");
        record.setRawResponse("");
        record.setProvider("deepseek-web");
        record.setContextCharsDelta(20);
        return record;
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
