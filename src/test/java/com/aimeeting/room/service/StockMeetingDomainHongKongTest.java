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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Hong Kong stocks: codes, a fact base from daily bars and the HK report table, and the HK close at 16:00. */
class StockMeetingDomainHongKongTest {
    private static final ZoneId MARKET = ZoneId.of("Asia/Shanghai");
    private static final JSONObject CARD = JSON.parseObject("{\"asOf\":\"2026-10-18\",\"horizonTradingDays\":60,"
            + "\"stock\":{\"symbol\":\"09988\",\"market\":\"hk\"}}");

    @Test
    void hongKongCodesAreNormalisedToFiveDigitsAndAShareCodesStayAsTheyWere() {
        assertEquals("{\"symbol\":\"09988\",\"market\":\"hk\"}",
                StockMeetingDomain.normalizeStock(JSON.parseObject("{\"symbol\":\"9988\",\"market\":\"HK\"}")).toJSONString());
        assertEquals("{\"symbol\":\"09988\",\"market\":\"hk\"}",
                StockMeetingDomain.normalizeStock(JSON.parseObject("{\"symbol\":\"09988\"}")).toJSONString());
        assertEquals("{\"symbol\":\"00700\",\"market\":\"hk\"}",
                StockMeetingDomain.normalizeStock(JSON.parseObject("{\"symbol\":\"00700.HK\"}")).toJSONString());
        assertEquals("{\"symbol\":\"300750\",\"market\":\"sz\"}",
                StockMeetingDomain.normalizeStock(JSON.parseObject("{\"symbol\":\"300750\"}")).toJSONString());
        assertNull(StockMeetingDomain.normalizeStock(JSON.parseObject("{\"symbol\":\"BABA\",\"market\":\"us\"}")));
    }

    @Test
    void theIssueCardHintExplainsHowToGiveAHongKongStock() {
        String hint = new StockMeetingDomain(mock(EastmoneyMarketDataService.class)).issueCardHint();
        assertTrue(hint.contains("港股") && hint.contains("\"market\":\"hk\""), hint);
    }

    @Test
    void aHongKongFactBaseComesFromDailyBarsAndTheHongKongReportTableWithoutGuessingTheCurrency() {
        EastmoneyMarketDataService eastmoney = mock(EastmoneyMarketDataService.class);
        when(eastmoney.fetchDailyHistory(eq("09988"), eq("hk"), anyInt())).thenThrow(new IllegalStateException("closed"));
        when(eastmoney.fetchTencentDailyHistory(eq("09988"), eq("hk"), anyInt())).thenReturn(bars());
        when(eastmoney.fetchHongKongIndicators("09988")).thenReturn(List.of(indicators()));
        StockMeetingDomain domain = new StockMeetingDomain(eastmoney, at(LocalDate.of(2026, 10, 18), 20, 0));

        MeetingDomain.DomainEvidence evidence = domain.evidence(CARD);

        verify(eastmoney, never()).buildBundle(anyString(), anyString(), anyString());
        JSONObject close = fact(evidence, "收盘价");
        assertEquals("收盘价（2026-10-16，前复权日K）：108.80港元", close.getString("statement"));
        assertEquals("腾讯证券", close.getString("source"));
        // 108.80 × 19,901,959,514 shares = 21,653.33 亿港元; the table implied 100.00 a share, so PE and PB scale by 1.088.
        assertEquals("21653.33", fact(evidence, "总市值").getString("value"));
        assertEquals("亿港元", fact(evidence, "总市值").getString("unit"));
        assertEquals("27.73", fact(evidence, "市盈率TTM").getString("value"));
        assertEquals("1.94", fact(evidence, "市净率").getString("value"));
        JSONObject revenue = fact(evidence, "营业收入（");
        assertTrue(revenue.getString("statement").contains("报告期 2026-04-01 至 2026-06-30，2026年一季报")
                && revenue.getString("statement").contains("币种未标明") && revenue.getString("value").equals("2689.53"),
                revenue.getString("statement"));
        assertFalse(revenue.getString("statement").contains("亿元") || revenue.getString("statement").contains("港元"),
                revenue.getString("statement"));
        assertEquals("8.60", fact(evidence, "营业收入同比").getString("value"));
        assertEquals("-73.89", fact(evidence, "归母净利润同比").getString("value"));
        assertEquals("38.24", fact(evidence, "毛利率").getString("value"));
        assertEquals("43.23", fact(evidence, "资产负债率").getString("value"));
        assertEquals("8.53", fact(evidence, "经营现金流净额占营收比例").getString("value"));
        assertEquals("8.80", fact(evidence, "近5个交易日涨跌幅").getString("value"), "108.8 against 100 five bars back");
        assertTrue(fact(evidence, "20日历史波动率").getString("statement").contains("%"));
        assertEquals("2026-10-16", evidence.outcomeQuestion.getString("baseDate"));
    }

    @Test
    void unadjustedTencentBarsAreLabelledAsSuchInTheFactsAndTheOutcomeQuestion() {
        EastmoneyMarketDataService eastmoney = mock(EastmoneyMarketDataService.class);
        when(eastmoney.fetchDailyHistory(eq("09988"), eq("hk"), anyInt())).thenThrow(new IllegalStateException("closed"));
        List<Map<String, Object>> unadjusted = bars();
        for (Map<String, Object> bar : unadjusted) bar.put("adjustment", "none");
        when(eastmoney.fetchTencentDailyHistory(eq("09988"), eq("hk"), anyInt())).thenReturn(unadjusted);
        when(eastmoney.fetchHongKongIndicators("09988")).thenReturn(List.of(indicators()));

        MeetingDomain.DomainEvidence evidence = new StockMeetingDomain(eastmoney, at(LocalDate.of(2026, 10, 18), 20, 0))
                .evidence(CARD);

        JSONObject close = fact(evidence, "收盘价");
        assertEquals("收盘价（2026-10-16，不复权日K，未计分红）：108.80港元", close.getString("statement"));
        assertEquals("腾讯证券（不复权）", close.getString("source"));
        assertTrue(fact(evidence, "历史参照").getString("statement").contains("不复权"), fact(evidence, "历史参照").toJSONString());
        assertTrue(evidence.outcomeQuestion.getString("title").contains("不复权"), evidence.outcomeQuestion.getString("title"));
        assertTrue(!evidence.outcomeQuestion.getString("title").contains("前复权"), evidence.outcomeQuestion.getString("title"));
    }

    @Test
    void aHongKongBarIsUnsettledUntil1630BecauseTheMarketClosesAt1600() {
        EastmoneyMarketDataService eastmoney = mock(EastmoneyMarketDataService.class);
        List<Map<String, Object>> withToday = bars();
        withToday.add(bar("2026-10-19", "120", "121"));
        when(eastmoney.fetchDailyHistory(eq("09988"), eq("hk"), anyInt())).thenReturn(withToday);
        JSONObject question = JSON.parseObject("{\"baseDate\":\"2026-10-16\",\"horizonTradingDays\":60}");

        MeetingDomain.OutcomeObservation during = new StockMeetingDomain(eastmoney, at(LocalDate.of(2026, 10, 19), 16, 10))
                .observeOutcome(CARD, question);
        MeetingDomain.OutcomeObservation after = new StockMeetingDomain(eastmoney, at(LocalDate.of(2026, 10, 19), 16, 40))
                .observeOutcome(CARD, question);

        assertEquals("2026-10-16", during.endDate, "at 16:10 the closing auction may still run");
        assertEquals("2026-10-19", after.endDate);
    }

    private static JSONObject fact(MeetingDomain.DomainEvidence evidence, String prefix) {
        for (JSONObject fact : evidence.facts) {
            if (fact.getString("question") != null && fact.getString("question").startsWith(prefix)) return fact;
        }
        throw new AssertionError("no fact starting with " + prefix + ": " + evidence.facts);
    }

    private static Clock at(LocalDate day, int hour, int minute) {
        return Clock.fixed(ZonedDateTime.of(day.atTime(hour, minute), MARKET).toInstant(), MARKET);
    }

    /** 400 bars alternating 100/102 (highs one above), then the last five rising to 108.8 on 2026-10-16. */
    private static List<Map<String, Object>> bars() {
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < 395; i++) {
            String close = i % 2 == 0 ? "100" : "102";
            bars.add(bar("d" + i, close, String.valueOf(Integer.parseInt(close) + 1)));
        }
        String[] last = {"107.33", "107.6", "108.0", "108.4", "108.8"};
        for (int i = 0; i < last.length; i++) {
            bars.add(bar(i == last.length - 1 ? "2026-10-16" : "e" + i, last[i], "110.9"));
        }
        return bars;
    }

    private static Map<String, Object> bar(String date, String close, String high) {
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("date", date);
        bar.put("close", new BigDecimal(close));
        bar.put("high", new BigDecimal(high));
        return bar;
    }

    private static Map<String, Object> indicators() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("startDate", "2026-04-01");
        row.put("reportDate", "2026-06-30");
        row.put("reportType", "2026年一季报");
        row.put("revenue", new BigDecimal("268953000000"));
        row.put("revenueYoyPct", new BigDecimal("8.6011823042"));
        row.put("netProfit", new BigDecimal("10614000000"));
        row.put("netProfitYoyPct", new BigDecimal("-73.8886565475"));
        row.put("grossMarginPct", new BigDecimal("38.243484921157"));
        row.put("netMarginPct", new BigDecimal("3.8832063595"));
        row.put("roePct", new BigDecimal("0.998798787778"));
        row.put("debtRatioPct", new BigDecimal("43.2297594068"));
        row.put("cashToRevenuePct", new BigDecimal("8.531230363669"));
        row.put("peTtm", new BigDecimal("25.483043963964"));
        row.put("pbTtm", new BigDecimal("1.78"));
        // Implies 100.00 a share, while the latest close is 108.80.
        row.put("marketCap", new BigDecimal("1990195951400"));
        row.put("shares", new BigDecimal("19901959514"));
        return row;
    }
}
