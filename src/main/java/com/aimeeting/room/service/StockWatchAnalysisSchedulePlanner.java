package com.aimeeting.room.service;

import com.aimeeting.room.entity.StockWatchItem;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Component
public class StockWatchAnalysisSchedulePlanner {

    public static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime AFTERNOON_OPEN = LocalTime.of(13, 0);
    private static final LocalTime A_MORNING_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime HK_MORNING_CLOSE = LocalTime.of(12, 0);
    private static final LocalTime A_CLOSE = LocalTime.of(15, 0);
    private static final LocalTime HK_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime DAILY_DEADLINE = LocalTime.of(23, 0);

    public List<DueDecision> dueDecisions(StockWatchItem item, ZonedDateTime now) {
        if (item == null || !Boolean.TRUE.equals(item.getEnabled())) return Collections.emptyList();
        String mode = normalizeMode(item.getAnalysisMode());
        if ("PAUSED".equals(mode) || "ON_ANOMALY".equals(mode)) return Collections.emptyList();
        List<DueDecision> decisions = new ArrayList<>();
        DueDecision day = "DAILY_CLOSE".equals(mode)
                ? dailyCloseDecision(item, now)
                : intervalDecision(item, now);
        if (day != null && day.isDue()) decisions.add(day);
        if (Boolean.TRUE.equals(item.getWeeklyReviewEnabled())) {
            DueDecision weekly = reviewDecision(item, now, "WEEK");
            if (weekly != null && weekly.isDue()) decisions.add(weekly);
        }
        if (Boolean.TRUE.equals(item.getMonthlyReviewEnabled())) {
            DueDecision monthly = reviewDecision(item, now, "MONTH");
            if (monthly != null && monthly.isDue()) decisions.add(monthly);
        }
        return decisions;
    }

    public DueDecision currentDayDecision(StockWatchItem item, ZonedDateTime now) {
        if (item == null || !Boolean.TRUE.equals(item.getEnabled())) return DueDecision.inactive("DISABLED");
        String mode = normalizeMode(item.getAnalysisMode());
        if ("PAUSED".equals(mode)) return DueDecision.inactive("PAUSED");
        if ("ON_ANOMALY".equals(mode)) return DueDecision.inactive("WAITING_ANOMALY");
        return "DAILY_CLOSE".equals(mode) ? dailyCloseDecision(item, now) : intervalDecision(item, now);
    }

    public ZonedDateTime nextDueAt(StockWatchItem item, ZonedDateTime now) {
        if (item == null || !Boolean.TRUE.equals(item.getEnabled())) return null;
        String mode = normalizeMode(item.getAnalysisMode());
        if ("PAUSED".equals(mode) || "ON_ANOMALY".equals(mode)) return null;
        if ("DAILY_CLOSE".equals(mode)) {
            ZonedDateTime today = at(now.toLocalDate(), closeTime(item.getMarket()).plusMinutes(10));
            if (isWeekday(now.toLocalDate()) && now.isBefore(today)) return today;
            if (isWeekday(now.toLocalDate()) && !now.toLocalTime().isAfter(DAILY_DEADLINE)) return now;
            return at(nextWeekday(now.toLocalDate()), closeTime(item.getMarket()).plusMinutes(10));
        }
        int interval = normalizeInterval(item.getAnalysisIntervalMinutes());
        for (ZonedDateTime slot : intervalSlots(now.toLocalDate(), item.getMarket(), interval)) {
            if (slot.isAfter(now)) return slot;
        }
        return at(nextWeekday(now.toLocalDate()), OPEN);
    }

    public ZonedDateTime nextScheduledDueAt(StockWatchItem item, ZonedDateTime now) {
        ZonedDateTime next = nextDueAt(item, now);
        if (next != null) return next;
        if (item != null && "ON_ANOMALY".equals(normalizeMode(item.getAnalysisMode()))) return null;
        return next;
    }

    public LocalTime closeTime(String market) {
        return "hk".equalsIgnoreCase(market) ? HK_CLOSE : A_CLOSE;
    }

    private DueDecision intervalDecision(StockWatchItem item, ZonedDateTime now) {
        if (!isWeekday(now.toLocalDate()) || !inTradingSession(now.toLocalTime(), item.getMarket())) {
            return DueDecision.waiting(nextDueAt(item, now), "OUTSIDE_TRADING_SESSION");
        }
        int interval = normalizeInterval(item.getAnalysisIntervalMinutes());
        List<ZonedDateTime> slots = intervalSlots(now.toLocalDate(), item.getMarket(), interval);
        ZonedDateTime selected = null;
        for (ZonedDateTime slot : slots) {
            if (!slot.isAfter(now)) selected = slot;
        }
        if (selected == null) return DueDecision.waiting(slots.isEmpty() ? null : slots.get(0), "BEFORE_FIRST_SLOT");
        ZonedDateTime policyModifiedAt = toZoned(item.getAnalysisPolicyModifiedAt());
        if (policyModifiedAt != null && selected.isBefore(policyModifiedAt)) {
            return DueDecision.waiting(firstAfter(slots, now), "POLICY_CHANGED_AFTER_SLOT");
        }
        ZonedDateTime next = firstAfter(slots, selected);
        ZonedDateTime deadline = next == null
                ? at(selected.toLocalDate(), closeTime(item.getMarket()).plusMinutes(30))
                : next;
        return DueDecision.due(
                "DAY:" + selected.toLocalDate() + ":INTERVAL:" + selected.format(SLOT_FORMAT),
                "INTERVAL",
                "DAY",
                selected,
                deadline,
                next == null ? at(nextWeekday(selected.toLocalDate()), OPEN) : next,
                selected.toLocalDate(),
                selected.format(SLOT_FORMAT)
        );
    }

    private DueDecision dailyCloseDecision(StockWatchItem item, ZonedDateTime now) {
        if (!isWeekday(now.toLocalDate())) {
            return DueDecision.waiting(at(nextWeekday(now.toLocalDate()), closeTime(item.getMarket()).plusMinutes(10)),
                    "NON_TRADING_DAY_CANDIDATE");
        }
        LocalTime close = closeTime(item.getMarket());
        ZonedDateTime eligible = at(now.toLocalDate(), close.plusMinutes(10));
        ZonedDateTime deadline = at(now.toLocalDate(), DAILY_DEADLINE);
        if (now.isBefore(eligible)) return DueDecision.waiting(eligible, "WAITING_CLOSE_READY_WINDOW");
        if (now.isAfter(deadline)) {
            return DueDecision.waiting(at(nextWeekday(now.toLocalDate()), close.plusMinutes(10)), "DAILY_CLOSE_WINDOW_EXPIRED");
        }
        return DueDecision.due(
                "DAY:" + now.toLocalDate() + ":DAILY_CLOSE",
                "DAILY_CLOSE",
                "DAY",
                eligible,
                deadline,
                at(nextWeekday(now.toLocalDate()), close.plusMinutes(10)),
                now.toLocalDate(),
                close.format(SLOT_FORMAT)
        );
    }

    private DueDecision reviewDecision(StockWatchItem item, ZonedDateTime now, String periodType) {
        if (!isWeekday(now.toLocalDate())) return null;
        boolean dueDate = "WEEK".equals(periodType)
                ? now.getDayOfWeek() == DayOfWeek.FRIDAY
                : now.toLocalDate().equals(lastWeekdayOfMonth(now.toLocalDate()));
        if (!dueDate) return null;
        LocalTime close = closeTime(item.getMarket());
        ZonedDateTime eligible = at(now.toLocalDate(), close.plusMinutes(10));
        ZonedDateTime deadline = at(now.toLocalDate(), DAILY_DEADLINE);
        if (now.isBefore(eligible) || now.isAfter(deadline)) return null;
        String periodKey = "WEEK".equals(periodType)
                ? String.format(Locale.ROOT, "%04d-W%02d",
                now.toLocalDate().get(java.time.temporal.WeekFields.ISO.weekBasedYear()),
                now.toLocalDate().get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()))
                : YearMonth.from(now).toString();
        return DueDecision.due(
                periodType + ":" + periodKey,
                periodType + "LY_REVIEW",
                periodType,
                eligible,
                deadline,
                null,
                now.toLocalDate(),
                periodType
        );
    }

    private List<ZonedDateTime> intervalSlots(LocalDate date, String market, int intervalMinutes) {
        if (!isWeekday(date)) return Collections.emptyList();
        List<ZonedDateTime> slots = new ArrayList<>();
        ZonedDateTime cursor = at(date, OPEN);
        ZonedDateTime end = at(date, closeTime(market));
        while (!cursor.isAfter(end)) {
            if (inTradingSession(cursor.toLocalTime(), market)) slots.add(cursor);
            cursor = cursor.plusMinutes(intervalMinutes);
        }
        if (slots.isEmpty()) slots.add(at(date, OPEN));
        return slots;
    }

    private boolean inTradingSession(LocalTime time, String market) {
        LocalTime morningClose = "hk".equalsIgnoreCase(market) ? HK_MORNING_CLOSE : A_MORNING_CLOSE;
        LocalTime close = closeTime(market);
        return (!time.isBefore(OPEN) && !time.isAfter(morningClose))
                || (!time.isBefore(AFTERNOON_OPEN) && !time.isAfter(close));
    }

    private ZonedDateTime firstAfter(List<ZonedDateTime> slots, ZonedDateTime instant) {
        for (ZonedDateTime slot : slots) if (slot.isAfter(instant)) return slot;
        return null;
    }

    private int normalizeInterval(Integer value) {
        return Math.max(10, Math.min(1440, value == null ? 30 : value));
    }

    private String normalizeMode(String value) {
        String mode = value == null ? "INTERVAL" : value.trim().toUpperCase(Locale.ROOT);
        return List.of("INTERVAL", "DAILY_CLOSE", "ON_ANOMALY", "PAUSED").contains(mode) ? mode : "INTERVAL";
    }

    private ZonedDateTime at(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, CHINA_ZONE);
    }

    private ZonedDateTime toZoned(Date value) {
        return value == null ? null : ZonedDateTime.ofInstant(value.toInstant(), CHINA_ZONE);
    }

    private boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private LocalDate nextWeekday(LocalDate date) {
        LocalDate cursor = date.plusDays(1);
        while (!isWeekday(cursor)) cursor = cursor.plusDays(1);
        return cursor;
    }

    private LocalDate lastWeekdayOfMonth(LocalDate date) {
        LocalDate cursor = YearMonth.from(date).atEndOfMonth();
        while (!isWeekday(cursor)) cursor = cursor.minusDays(1);
        return cursor;
    }

    @Getter
    public static class DueDecision {
        private final boolean due;
        private final String scheduleSlotKey;
        private final String triggerType;
        private final String periodType;
        private final ZonedDateTime notBefore;
        private final ZonedDateTime deadlineAt;
        private final ZonedDateTime nextDueAt;
        private final LocalDate tradeDate;
        private final String runSlot;
        private final String reason;

        private DueDecision(boolean due,
                            String scheduleSlotKey,
                            String triggerType,
                            String periodType,
                            ZonedDateTime notBefore,
                            ZonedDateTime deadlineAt,
                            ZonedDateTime nextDueAt,
                            LocalDate tradeDate,
                            String runSlot,
                            String reason) {
            this.due = due;
            this.scheduleSlotKey = scheduleSlotKey;
            this.triggerType = triggerType;
            this.periodType = periodType;
            this.notBefore = notBefore;
            this.deadlineAt = deadlineAt;
            this.nextDueAt = nextDueAt;
            this.tradeDate = tradeDate;
            this.runSlot = runSlot;
            this.reason = reason;
        }

        static DueDecision due(String key,
                               String triggerType,
                               String periodType,
                               ZonedDateTime notBefore,
                               ZonedDateTime deadlineAt,
                               ZonedDateTime nextDueAt,
                               LocalDate tradeDate,
                               String runSlot) {
            return new DueDecision(true, key, triggerType, periodType, notBefore, deadlineAt,
                    nextDueAt, tradeDate, runSlot, "DUE");
        }

        static DueDecision waiting(ZonedDateTime nextDueAt, String reason) {
            return new DueDecision(false, null, null, "DAY", null, null, nextDueAt, null, null, reason);
        }

        static DueDecision inactive(String reason) {
            return waiting(null, reason);
        }

        public long millisUntilNext(ZonedDateTime now) {
            return nextDueAt == null ? -1L : Math.max(0L, Duration.between(now, nextDueAt).toMillis());
        }
    }
}
