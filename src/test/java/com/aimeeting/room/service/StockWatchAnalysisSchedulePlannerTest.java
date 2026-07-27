package com.aimeeting.room.service;

import com.aimeeting.room.entity.StockWatchItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchAnalysisSchedulePlannerTest {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private final StockWatchAnalysisSchedulePlanner planner = new StockWatchAnalysisSchedulePlanner();

    @Test
    void intervalUsesStableWallClockSlotsWithoutCompletionDrift() {
        StockWatchItem item = item("INTERVAL", 60, "sh");
        ZonedDateTime now = at(2026, 7, 22, 10, 45);

        StockWatchAnalysisSchedulePlanner.DueDecision decision = planner.currentDayDecision(item, now);

        assertTrue(decision.isDue());
        assertEquals("DAY:2026-07-22:INTERVAL:10:30", decision.getScheduleSlotKey());
        assertEquals("10:30", decision.getRunSlot());
        assertEquals(at(2026, 7, 22, 11, 30), decision.getNextDueAt());
    }

    @Test
    void tenMinuteModeIsActuallyPlannable() {
        StockWatchItem item = item("INTERVAL", 10, "sh");
        StockWatchAnalysisSchedulePlanner.DueDecision decision =
                planner.currentDayDecision(item, at(2026, 7, 22, 9, 41));

        assertTrue(decision.isDue());
        assertEquals("DAY:2026-07-22:INTERVAL:09:40", decision.getScheduleSlotKey());
        assertEquals("09:40", decision.getRunSlot());
    }

    @Test
    void policyChangeDoesNotReplayOlderIntervalSlot() {
        StockWatchItem item = item("INTERVAL", 30, "sh");
        item.setAnalysisPolicyModifiedAt(Date.from(at(2026, 7, 22, 10, 45).toInstant()));

        StockWatchAnalysisSchedulePlanner.DueDecision decision =
                planner.currentDayDecision(item, at(2026, 7, 22, 10, 46));

        assertFalse(decision.isDue());
        assertEquals("POLICY_CHANGED_AFTER_SLOT", decision.getReason());
        assertEquals(at(2026, 7, 22, 11, 0), decision.getNextDueAt());
    }

    @Test
    void dailyCloseUsesMarketSpecificWindowAndCanonicalCloseSlot() {
        StockWatchItem aShare = item("DAILY_CLOSE", null, "sh");
        StockWatchItem hongKong = item("DAILY_CLOSE", null, "hk");

        StockWatchAnalysisSchedulePlanner.DueDecision a =
                planner.currentDayDecision(aShare, at(2026, 7, 22, 15, 12));
        StockWatchAnalysisSchedulePlanner.DueDecision hkBefore =
                planner.currentDayDecision(hongKong, at(2026, 7, 22, 15, 12));
        StockWatchAnalysisSchedulePlanner.DueDecision hkAfter =
                planner.currentDayDecision(hongKong, at(2026, 7, 22, 16, 12));

        assertTrue(a.isDue());
        assertEquals("DAY:2026-07-22:DAILY_CLOSE", a.getScheduleSlotKey());
        assertEquals("15:00", a.getRunSlot());
        assertFalse(hkBefore.isDue());
        assertEquals(at(2026, 7, 22, 16, 10), hkBefore.getNextDueAt());
        assertTrue(hkAfter.isDue());
        assertEquals("16:00", hkAfter.getRunSlot());
    }

    @Test
    void dailyCloseDoesNotCrossNaturalDayAndWeekendWaits() {
        StockWatchItem item = item("DAILY_CLOSE", null, "sh");
        StockWatchAnalysisSchedulePlanner.DueDecision expired =
                planner.currentDayDecision(item, at(2026, 7, 22, 23, 1));
        StockWatchAnalysisSchedulePlanner.DueDecision weekend =
                planner.currentDayDecision(item, at(2026, 7, 25, 16, 0));

        assertFalse(expired.isDue());
        assertEquals(LocalDate.of(2026, 7, 23), expired.getNextDueAt().toLocalDate());
        assertFalse(weekend.isDue());
        assertEquals(LocalDate.of(2026, 7, 27), weekend.getNextDueAt().toLocalDate());
    }

    @Test
    void pausedAndAnomalyOnlyHaveNoScheduledDue() {
        StockWatchItem paused = item("PAUSED", null, "sh");
        StockWatchItem anomaly = item("ON_ANOMALY", null, "sh");

        assertTrue(planner.dueDecisions(paused, at(2026, 7, 22, 10, 0)).isEmpty());
        assertTrue(planner.dueDecisions(anomaly, at(2026, 7, 22, 10, 0)).isEmpty());
        assertNull(planner.nextDueAt(paused, at(2026, 7, 22, 10, 0)));
        assertNull(planner.nextDueAt(anomaly, at(2026, 7, 22, 10, 0)));
    }

    @Test
    void reviewsAreExplicitAndIndependentFromDayMode() {
        StockWatchItem item = item("DAILY_CLOSE", null, "sh");
        item.setWeeklyReviewEnabled(true);
        item.setMonthlyReviewEnabled(false);
        List<StockWatchAnalysisSchedulePlanner.DueDecision> decisions =
                planner.dueDecisions(item, at(2026, 7, 24, 15, 12));

        assertEquals(2, decisions.size());
        assertTrue(decisions.stream().anyMatch(value -> "DAILY_CLOSE".equals(value.getTriggerType())));
        assertTrue(decisions.stream().anyMatch(value -> "WEEKLY_REVIEW".equals(value.getTriggerType())));
    }

    private StockWatchItem item(String mode, Integer interval, String market) {
        StockWatchItem item = new StockWatchItem();
        item.setId(1L);
        item.setSymbol("600000");
        item.setMarket(market);
        item.setEnabled(true);
        item.setAnalysisMode(mode);
        item.setAnalysisIntervalMinutes(interval);
        item.setAnomalyTriggerMode("IMMEDIATE_ANALYSIS");
        item.setWeeklyReviewEnabled(false);
        item.setMonthlyReviewEnabled(false);
        item.setAnalysisPolicyVersion(0L);
        item.setAnalysisPolicyModifiedAt(new Date(0L));
        return item;
    }

    private ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, CHINA);
    }
}
