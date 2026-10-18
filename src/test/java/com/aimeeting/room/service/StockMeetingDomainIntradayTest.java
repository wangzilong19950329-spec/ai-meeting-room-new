package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Before the close, today's daily bar is still moving: it must not count as a finished day or a closing price. */
class StockMeetingDomainIntradayTest {
    private static final ZoneId MARKET = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2027, 1, 8);
    private static final JSONObject CARD = JSON.parseObject("{\"stock\":{\"symbol\":\"300750\",\"market\":\"sz\"}}");
    private static final JSONObject QUESTION = JSON.parseObject("{\"baseDate\":\"2026-10-16\",\"horizonTradingDays\":60}");

    @Test
    void todaysBarIsLeftOutBeforeTheCloseSoTheOutcomeIsNotJudgedOnAnIntradayPrice() {
        MeetingDomain.OutcomeObservation observed = domainAt(10, 30).observeOutcome(CARD, QUESTION);

        assertEquals(MeetingDomain.OutcomeObservation.NOT_DUE, observed.status);
        assertEquals(59, observed.elapsedTradingDays);
        assertEquals("x59", observed.endDate);
    }

    @Test
    void afterTheCloseTodaysBarCountsAndTheOutcomeIsJudged() {
        MeetingDomain.OutcomeObservation observed = domainAt(16, 0).observeOutcome(CARD, QUESTION);

        assertEquals(MeetingDomain.OutcomeObservation.DUE, observed.status);
        assertEquals(TODAY.toString(), observed.endDate);
        assertEquals(new BigDecimal("112.2"), observed.endClose);
    }

    private static StockMeetingDomain domainAt(int hour, int minute) {
        EastmoneyMarketDataService eastmoney = mock(EastmoneyMarketDataService.class);
        when(eastmoney.fetchDailyHistory(eq("300750"), eq("sz"), anyInt())).thenReturn(history());
        Clock clock = Clock.fixed(ZonedDateTime.of(TODAY.atTime(hour, minute), MARKET).toInstant(), MARKET);
        return new StockMeetingDomain(eastmoney, clock);
    }

    /** 2026-10-16 base close 102, bars x1..x59 at 105, then today's bar at 112.2 (the 60th after the base). */
    private static List<Map<String, Object>> history() {
        List<Map<String, Object>> bars = new ArrayList<>();
        bars.add(bar("2026-10-16", "102"));
        for (int i = 1; i <= 59; i++) bars.add(bar("x" + i, "105"));
        bars.add(bar(TODAY.toString(), "112.2"));
        return bars;
    }

    private static Map<String, Object> bar(String date, String close) {
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("date", date);
        bar.put("close", new BigDecimal(close));
        return bar;
    }
}
