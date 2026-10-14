package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchPriceFailureAssessmentMapper;
import com.aimeeting.room.dao.StockWatchPriceFailureLedgerMapper;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.entity.StockWatchPriceFailureLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
@Transactional
class StockWatchPriceFailureResearchPersistenceTest {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    @Autowired StockWatchFactObservationMapper factMapper;
    @Autowired StockWatchPriceFailureAssessmentMapper assessmentMapper;
    @Autowired StockWatchPriceFailureLedgerMapper ledgerMapper;
    @Autowired StockWatchPriceFailureResearchService service;

    @Test
    void bootstrapsWithoutBackfillThenPersistsControlsEntryAndMatureT1Outcome() {
        LocalDate sourceDate = LocalDate.now(CHINA);
        long sessionStart = sourceDate.atTime(10, 0).atZone(CHINA).toInstant().toEpochMilli();
        StockWatchFactObservation historical = insertFact("009981", 1L, sourceDate, sessionStart - 600_000,
                "FRESH", snapshot(100.0, 0.0, 0.0, 0.0, 50.0, 0.0, 0.0, 0.8,
                        2.0, 0.0, 1.0, 100_000_000.0));

        Map<String, Object> bootstrap = service.runOnce();

        assertEquals(0, bootstrap.get("processedObservations"));
        assertNull(assessmentMapper.selectByRuleObservation(
                StockWatchPriceFailureResearchRule.RULE_VERSION, historical.getId()));

        StockWatchFactObservation controlOrigin = insertFact("009982", 1L, sourceDate, sessionStart,
                "FRESH", snapshot(101.0, 7.0, 11.0, 11.0, 84.0, 0.5, 0.2, 0.80,
                        6.0, -1.0, 3.0, 400_000_000.0));
        StockWatchFactObservation signalOrigin = insertFact("009983", 1L, sourceDate, sessionStart + 60_000,
                "FRESH", snapshot(100.0, -1.0, 12.0, 12.0, 85.0, 2.0, -2.0, 0.20,
                        6.0, -6.0, 3.2, 500_000_000.0));
        StockWatchFactObservation lateControlOrigin = insertFact("009986", 1L, sourceDate, sessionStart + 30_000,
                "FRESH", snapshot(101.0, 7.0, 11.0, 11.0, 84.0, 0.5, 0.2, 0.80,
                        6.0, -1.0, 3.0, 400_000_000.0));

        service.processObservationAt(controlOrigin, sessionStart + 120_000);
        service.processObservationAt(signalOrigin, sessionStart + 121_000);
        service.processObservationAt(lateControlOrigin, sessionStart + 600_000);
        Map<String, Object> first = service.runOnce();

        assertEquals(3, first.get("processedObservations"));
        StockWatchPriceFailureLedger signal = ledgerMapper.selectSignalBySymbolDate(
                StockWatchPriceFailureResearchRule.RULE_VERSION, "009983", "sz", sourceDate.toString());
        assertNotNull(signal);
        assertEquals("WAITING_ENTRY", signal.getStatus());
        List<StockWatchPriceFailureLedger> controls = ledgerMapper.selectControlsBySignal(signal.getId());
        assertEquals(1, controls.size());
        assertEquals(controlOrigin.getId(), controls.get(0).getOriginObservationId());
        assertFalse(lateControlOrigin.getId().equals(controls.get(0).getOriginObservationId()));

        StockWatchFactObservation controlEntry = insertFact("009982", 2L, sourceDate, sessionStart + 180_000,
                "FRESH", snapshot(102.0, 0.0, 0.0, 0.0, 50.0, 0.0, 0.0, 0.8,
                        2.0, 0.0, 2.0, 300_000_000.0));
        StockWatchFactObservation signalEntry = insertFact("009983", 2L, sourceDate, sessionStart + 180_500,
                "FRESH", snapshot(98.0, -2.0, 8.0, 8.0, 75.0, 0.0, 0.0, 0.8,
                        2.0, -2.0, 3.5, 450_000_000.0));
        LocalDate targetDate = sourceDate.plusDays(1);
        insertFact("009982", 3L, targetDate, sessionStart + 86_400_000,
                "POST_CLOSE_VALID", snapshotWithOhlc(105.0, 103.0, 106.0, 101.0));
        insertFact("009983", 3L, targetDate, sessionStart + 86_400_500,
                "POST_CLOSE_VALID", snapshotWithOhlc(91.0, 97.0, 99.0, 90.0));

        service.runOnce();

        StockWatchPriceFailureLedger matureSignal = ledgerMapper.selectSignalBySymbolDate(
                StockWatchPriceFailureResearchRule.RULE_VERSION, "009983", "sz", sourceDate.toString());
        assertEquals("MATURE", matureSignal.getStatus());
        assertEquals(signalEntry.getId(), matureSignal.getEntryObservationId());
        assertTrue(matureSignal.getEntrySourceTimeEpochMs() > matureSignal.getAvailableTimeEpochMs());
        assertEquals(targetDate.toString(), matureSignal.getT1TradeDate());
        assertEquals(90.0, matureSignal.getT1Low(), 0.000001);
        assertEquals(91.0, matureSignal.getT1Close(), 0.000001);
        assertEquals((90.0 / 98.0 - 1.0) * 100.0, matureSignal.getT1MaxDrawdownPct(), 0.000001);
        assertEquals((97.0 / 100.0 - 1.0) * 100.0, matureSignal.getT1GapPct(), 0.000001);
        assertTrue(matureSignal.getT1Drawdown5Hit());
        assertTrue(matureSignal.getControlsFinalized());
        assertEquals(1, service.progress().get("sourceDateCount"));

        StockWatchPriceFailureLedger matureControl = ledgerMapper.selectControlsBySignal(signal.getId()).get(0);
        assertEquals("MATURE", matureControl.getStatus());
        assertEquals(controlEntry.getId(), matureControl.getEntryObservationId());
        assertFalse(Boolean.TRUE.equals(matureControl.getT1Drawdown5Hit()));
    }

    @Test
    void crossDateEntryUsesNextTradingDayOpenInsteadOfLaterObservedPrice() {
        LocalDate sourceDate = LocalDate.now(CHINA);
        long sessionStart = sourceDate.atTime(10, 0).atZone(CHINA).toInstant().toEpochMilli();
        insertFact("009984", 1L, sourceDate, sessionStart - 600_000,
                "FRESH", snapshot(100.0, 0.0, 0.0, 0.0, 50.0, 0.0, 0.0, 0.8,
                        2.0, 0.0, 1.0, 100_000_000.0));
        service.runOnce();

        StockWatchFactObservation signalOrigin = insertFact("009985", 1L, sourceDate, sessionStart,
                "FRESH", snapshot(100.0, -1.0, 12.0, 12.0, 85.0, 2.0, -2.0, 0.20,
                        6.0, -6.0, 3.2, 500_000_000.0));
        LocalDate targetDate = sourceDate.plusDays(1);
        StockWatchFactObservation firstTargetQuote = insertFact("009985", 2L, targetDate,
                sessionStart + 86_400_000, "POST_CLOSE_VALID", snapshotWithOhlc(95.0, 98.0, 99.0, 90.0));
        insertFact("009985", 3L, targetDate,
                sessionStart + 86_400_000, "POST_CLOSE_VALID", snapshotWithOhlc(94.0, 98.0, 100.0, 89.0));

        service.processObservationAt(signalOrigin, sessionStart + 30_000);
        service.runOnce();

        StockWatchPriceFailureLedger signal = ledgerMapper.selectSignalBySymbolDate(
                StockWatchPriceFailureResearchRule.RULE_VERSION, "009985", "sz", sourceDate.toString());
        assertNotNull(signal);
        assertEquals("MATURE", signal.getStatus());
        assertEquals(firstTargetQuote.getId(), signal.getEntryObservationId());
        assertEquals(98.0, signal.getEntryPrice(), 0.000001);
        assertEquals(89.0, signal.getT1Low(), 0.000001);
        assertEquals(94.0, signal.getT1Close(), 0.000001);
        assertEquals((89.0 / 98.0 - 1.0) * 100.0, signal.getT1MaxDrawdownPct(), 0.000001);
        assertEquals((98.0 / 100.0 - 1.0) * 100.0, signal.getT1GapPct(), 0.000001);
    }

    @Test
    void partialIntradayTargetSnapshotCannotBecomeAMatureClose() {
        LocalDate sourceDate = LocalDate.now(CHINA);
        long sessionStart = sourceDate.atTime(10, 0).atZone(CHINA).toInstant().toEpochMilli();
        insertFact("009987", 1L, sourceDate, sessionStart - 600_000,
                "FRESH", snapshot(100.0, 0.0, 0.0, 0.0, 50.0, 0.0, 0.0, 0.8,
                        2.0, 0.0, 1.0, 100_000_000.0));
        service.runOnce();

        StockWatchFactObservation signalOrigin = insertFact("009988", 1L, sourceDate, sessionStart,
                "FRESH", snapshot(100.0, -1.0, 12.0, 12.0, 85.0, 2.0, -2.0, 0.20,
                        6.0, -6.0, 3.2, 500_000_000.0));
        insertFact("009988", 2L, sourceDate, sessionStart + 60_000,
                "FRESH", snapshot(99.0, -1.0, 8.0, 8.0, 80.0, 0.0, 0.0, 0.8,
                        2.0, -2.0, 3.5, 450_000_000.0));
        LocalDate targetDate = sourceDate.plusDays(1);
        insertFact("009988", 3L, targetDate, sessionStart + 86_400_000,
                "FRESH", snapshotWithOhlc(95.0, 97.0, 99.0, 90.0));
        insertFact("009989", 1L, targetDate.plusDays(1), sessionStart + 2 * 86_400_000L,
                "POST_CLOSE_VALID", snapshotWithOhlc(100.0, 100.0, 101.0, 99.0));

        service.processObservationAt(signalOrigin, sessionStart + 30_000);
        service.runOnce();

        StockWatchPriceFailureLedger signal = ledgerMapper.selectSignalBySymbolDate(
                StockWatchPriceFailureResearchRule.RULE_VERSION, "009988", "sz", sourceDate.toString());
        assertNotNull(signal);
        assertEquals("DATA_QUALITY_FAILED", signal.getStatus());
        assertEquals("T1_POST_CLOSE_SNAPSHOT_MISSING", signal.getDataQualityReason());
        assertNull(signal.getT1MaxDrawdownPct());
    }

    @Test
    void postCloseMarkerCannotBorrowMissingOhlcFromIntradaySnapshot() {
        LocalDate sourceDate = LocalDate.now(CHINA);
        long sessionStart = sourceDate.atTime(10, 0).atZone(CHINA).toInstant().toEpochMilli();
        insertFact("pfpc-base", 1L, sourceDate, sessionStart - 600_000,
                "FRESH", snapshot(100.0, 0.0, 0.0, 0.0, 50.0, 0.0, 0.0, 0.8,
                        2.0, 0.0, 1.0, 100_000_000.0));
        service.runOnce();

        StockWatchFactObservation signalOrigin = insertFact("pfpc-signal", 1L, sourceDate, sessionStart,
                "FRESH", snapshot(100.0, -1.0, 12.0, 12.0, 85.0, 2.0, -2.0, 0.20,
                        6.0, -6.0, 3.2, 500_000_000.0));
        insertFact("pfpc-signal", 2L, sourceDate, sessionStart + 60_000,
                "FRESH", snapshot(99.0, -1.0, 8.0, 8.0, 80.0, 0.0, 0.0, 0.8,
                        2.0, -2.0, 3.5, 450_000_000.0));
        LocalDate targetDate = sourceDate.plusDays(1);
        insertFact("pfpc-signal", 3L, targetDate, sessionStart + 86_400_000,
                "FRESH", snapshotWithOhlc(95.0, 97.0, 99.0, 90.0));
        Map<String, Object> incompletePostClose = snapshotWithOhlc(94.0, 97.0, 100.0, 89.0);
        ((Map<String, Object>) incompletePostClose.get("quote")).remove("low");
        insertFact("pfpc-signal", 4L, targetDate, sessionStart + 86_400_000 + 21_600_000,
                "POST_CLOSE_VALID", incompletePostClose);

        service.processObservationAt(signalOrigin, sessionStart + 30_000);
        service.runOnce();

        StockWatchPriceFailureLedger signal = ledgerMapper.selectSignalBySymbolDate(
                StockWatchPriceFailureResearchRule.RULE_VERSION, "pfpc-signal", "sz", sourceDate.toString());
        assertNotNull(signal);
        assertEquals("DATA_QUALITY_FAILED", signal.getStatus());
        assertEquals("T1_OHLC_INCOMPLETE", signal.getDataQualityReason());
        assertNull(signal.getT1Low());
        assertNull(signal.getT1MaxDrawdownPct());
    }

    @Test
    void recordedEntryCannotBeReplacedByLateEarlierFact() {
        LocalDate sourceDate = LocalDate.now(CHINA);
        long sessionStart = sourceDate.atTime(10, 0).atZone(CHINA).toInstant().toEpochMilli();
        insertFact("pffreeze-base", 1L, sourceDate, sessionStart - 600_000,
                "FRESH", snapshot(100.0, 0.0, 0.0, 0.0, 50.0, 0.0, 0.0, 0.8,
                        2.0, 0.0, 1.0, 100_000_000.0));
        service.runOnce();

        StockWatchFactObservation signalOrigin = insertFact("pffreeze-signal", 1L, sourceDate, sessionStart,
                "FRESH", snapshot(100.0, -1.0, 12.0, 12.0, 85.0, 2.0, -2.0, 0.20,
                        6.0, -6.0, 3.2, 500_000_000.0));
        StockWatchFactObservation firstEntry = insertFact("pffreeze-signal", 2L, sourceDate, sessionStart + 120_000,
                "FRESH", snapshot(98.0, -2.0, 8.0, 8.0, 75.0, 0.0, 0.0, 0.8,
                        2.0, -2.0, 3.5, 450_000_000.0));
        service.processObservationAt(signalOrigin, sessionStart + 30_000);
        service.runOnce();

        StockWatchPriceFailureLedger waiting = ledgerMapper.selectSignalBySymbolDate(
                StockWatchPriceFailureResearchRule.RULE_VERSION, "pffreeze-signal", "sz", sourceDate.toString());
        assertNotNull(waiting);
        assertEquals("WAITING_T1", waiting.getStatus());
        assertEquals(firstEntry.getId(), waiting.getEntryObservationId());
        assertEquals(98.0, waiting.getEntryPrice(), 0.000001);

        insertFact("pffreeze-signal", 3L, sourceDate, sessionStart + 60_000,
                "FRESH", snapshot(90.0, -2.0, 8.0, 8.0, 75.0, 0.0, 0.0, 0.8,
                        2.0, -2.0, 3.5, 450_000_000.0));
        LocalDate targetDate = sourceDate.plusDays(1);
        insertFact("pffreeze-signal", 4L, targetDate, sessionStart + 86_400_000,
                "POST_CLOSE_VALID", snapshotWithOhlc(91.0, 97.0, 99.0, 90.0));
        service.runOnce();

        StockWatchPriceFailureLedger mature = ledgerMapper.selectSignalBySymbolDate(
                StockWatchPriceFailureResearchRule.RULE_VERSION, "pffreeze-signal", "sz", sourceDate.toString());
        assertEquals("MATURE", mature.getStatus());
        assertEquals(firstEntry.getId(), mature.getEntryObservationId());
        assertEquals(98.0, mature.getEntryPrice(), 0.000001);
        assertEquals((90.0 / 98.0 - 1.0) * 100.0, mature.getT1MaxDrawdownPct(), 0.000001);
    }

    private StockWatchFactObservation insertFact(String symbol,
                                                  long arrivalVersion,
                                                  LocalDate sourceDate,
                                                  long sourceTime,
                                                  String quality,
                                                  Map<String, Object> snapshot) {
        StockWatchFactObservation fact = new StockWatchFactObservation();
        fact.setSymbol(symbol);
        fact.setMarket("sz");
        fact.setStockName("研究测试股" + symbol);
        fact.setObservationKey(symbol + "-" + arrivalVersion + "-" + System.nanoTime());
        fact.setArrivalVersion(arrivalVersion);
        fact.setSourceTimeEpochMs(sourceTime);
        fact.setSourceDate(sourceDate.toString());
        fact.setSourceTimeQuality(quality);
        fact.setFactHash("hash-" + fact.getObservationKey());
        fact.setFactSnapshotJson(JSON.toJSONString(snapshot));
        fact.setDataQualityJson("{}");
        fact.setSampledAt(new Date());
        assertEquals(1, factMapper.insert(fact));
        assertNotNull(fact.getId());
        return fact;
    }

    private Map<String, Object> snapshot(double price,
                                         double dailyReturn,
                                         double return5,
                                         double priceVsMa20,
                                         double position20,
                                         double gap,
                                         double fromOpen,
                                         double closeLocation,
                                         double turnover,
                                         double mainRatio,
                                         double volatility,
                                         double amount) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("price", price);
        quote.put("open", price);
        quote.put("high", price + 1.0);
        quote.put("low", price - 1.0);
        quote.put("turnover", turnover);
        quote.put("volumeRatio", turnover >= 5.0 ? 2.0 : 1.0);
        quote.put("amount", amount);
        quote.put("mainInflowRatio", mainRatio);
        quote.put("superLargeInflow", mainRatio <= -5.0 ? -10_000_000.0 : 1_000_000.0);
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("dailyReturnPct", dailyReturn);
        derived.put("previousDayReturnPct", 2.0);
        derived.put("return5Pct", return5);
        derived.put("priceVsMa20Pct", priceVsMa20);
        derived.put("closePosition20dPct", position20);
        derived.put("volatility20Pct", volatility);
        derived.put("gapOpenPct", gap);
        derived.put("intradayFromOpenPct", fromOpen);
        derived.put("closeLocationValue", closeLocation);
        derived.put("activityLevel", turnover >= 5.0 || Math.abs(dailyReturn) >= 5.0 ? "HIGH" : "LOW");
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("derived", Map.of("dailyReturnPct", dailyReturn));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "stock-watch-fact-v1");
        root.put("quote", quote);
        root.put("riskPatternObservations", risk);
        root.put("priceFailureResearchFeatures", derived);
        return root;
    }

    private Map<String, Object> snapshotWithOhlc(double price, double open, double high, double low) {
        Map<String, Object> root = snapshot(price, 0.0, 0.0, 0.0, 50.0,
                0.0, 0.0, 0.8, 2.0, 0.0, 2.0, 200_000_000.0);
        Map<String, Object> quote = (Map<String, Object>) root.get("quote");
        quote.put("open", open);
        quote.put("high", high);
        quote.put("low", low);
        return root;
    }
}
