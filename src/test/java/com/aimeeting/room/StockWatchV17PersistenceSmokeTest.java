package com.aimeeting.room;

import com.aimeeting.room.service.StockWatchAnalysisTaskService;
import com.aimeeting.room.service.StockWatchAnalysisTaskService.Reservation;
import com.aimeeting.room.service.StockWatchFactPersistenceService;
import com.aimeeting.room.service.StockWatchRiskPatternService;
import com.aimeeting.room.service.StockWatchRiskPatternService.RiskPatternResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class StockWatchV17PersistenceSmokeTest {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    @Autowired StockWatchRiskPatternService riskPatternService;
    @Autowired StockWatchFactPersistenceService factPersistenceService;
    @Autowired StockWatchAnalysisTaskService analysisTaskService;

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
