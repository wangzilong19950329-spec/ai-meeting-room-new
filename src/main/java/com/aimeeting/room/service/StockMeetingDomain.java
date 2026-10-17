package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A-share topics: Eastmoney structured data goes into the fact base before any reviewed search. Only a
 * dozen key fields and program-computed indicators are kept, each as one fact marked as single-source.
 */
@Component
public class StockMeetingDomain implements MeetingDomain {

    private static final BigDecimal HUNDRED_MILLION = new BigDecimal("100000000");
    private static final Set<String> MARKETS = Set.of("sh", "sz", "bj");

    private final EastmoneyMarketDataService eastmoney;

    public StockMeetingDomain(EastmoneyMarketDataService eastmoney) {
        this.eastmoney = eastmoney;
    }

    @Override
    public boolean appliesTo(JSONObject issueCard) {
        return issueCard != null && normalizeStock(issueCard.getJSONObject("stock")) != null;
    }

    @Override
    public String issueCardHint() {
        return "如果议题针对一只 A 股个股，在 stock 字段给出 {\"symbol\":\"300750\",\"market\":\"sz\"}（6 位代码，市场为 sh/sz/bj），"
                + "否则 stock 写 null。给出股票时，系统会自动提供东方财富结构化数据：收盘价、当日涨跌幅、总市值、"
                + "市盈率与市净率及其近一年分位、最近一期营业总收入与归母净利润及同比、近 5/20/60 个交易日涨跌幅、"
                + "距近 60 个交易日最高价回撤、相对 MA20/MA60 偏离、20 日历史波动率。取证题不要再问这些。";
    }

    @Override
    public void normalizeIssueCard(JSONObject raw, JSONObject normalized) {
        JSONObject stock = normalizeStock(raw.getJSONObject("stock"));
        if (stock != null) normalized.put("stock", stock);
    }

    @Override
    public List<JSONObject> structuredFacts(JSONObject issueCard) {
        JSONObject stock = normalizeStock(issueCard.getJSONObject("stock"));
        String label = "东方财富结构化数据（" + stock.getString("symbol") + "." + stock.getString("market") + "）";
        Map<String, Object> payload;
        try {
            EastmoneyMarketDataService.MarketDataBundle bundle =
                    eastmoney.buildBundle(stock.getString("symbol"), stock.getString("market"), "DAY");
            payload = bundle == null ? null : bundle.getPayload();
        } catch (RuntimeException unavailable) {
            return List.of(gap(label, "EASTMONEY_UNAVAILABLE: " + unavailable.getMessage()));
        }
        if (payload == null || !stock.getString("symbol").equals(String.valueOf(payload.get("symbol")))) {
            return List.of(gap(label, "EASTMONEY_SYMBOL_MISMATCH"));
        }
        List<JSONObject> facts = new ArrayList<>();
        Map<String, Object> indicators = map(payload.get("technicalIndicators"));
        String asOf = text(indicators.get("asOfDate"));
        BigDecimal close = decimal(indicators.get("close"));
        add(facts, "收盘价（" + asOf + "，前复权日K）", close, "元", asOf);
        Map<String, Object> detail = map(payload.get("stockDetail"));
        add(facts, "当日涨跌幅（" + asOf + "）", decimal(detail.get("changePercent")), "%", asOf);
        add(facts, "总市值（" + asOf + "）", scaled(decimal(detail.get("marketCap"))), "亿元", asOf);

        Map<String, Object> valuation = map(payload.get("valuation"));
        String valuationDate = text(valuation.get("tradeDate"));
        add(facts, "市盈率TTM（" + valuationDate + "）", decimal(valuation.get("peTtm")), "倍", valuationDate);
        add(facts, "市盈率TTM近一年分位（" + valuationDate + "）", decimal(valuation.get("pePercentile1y")), "%", valuationDate);
        add(facts, "市净率MRQ（" + valuationDate + "）", decimal(valuation.get("pbMrq")), "倍", valuationDate);
        add(facts, "市净率MRQ近一年分位（" + valuationDate + "）", decimal(valuation.get("pbPercentile1y")), "%", valuationDate);

        Object financials = payload.get("financials");
        if (financials instanceof List && !((List<?>) financials).isEmpty()) {
            Map<String, Object> latest = map(((List<?>) financials).get(0));
            String period = text(latest.get("reportDate"));
            if (period != null && period.length() > 10) period = period.substring(0, 10);
            add(facts, "营业总收入（报告期 " + period + "，累计）", scaled(decimal(latest.get("revenue"))), "亿元", period);
            add(facts, "营业总收入同比（报告期 " + period + "）", decimal(latest.get("revenueYoyPct")), "%", period);
            add(facts, "归母净利润（报告期 " + period + "，累计）", scaled(decimal(latest.get("netProfit"))), "亿元", period);
            add(facts, "归母净利润同比（报告期 " + period + "）", decimal(latest.get("netProfitYoyPct")), "%", period);
        }

        add(facts, "近5个交易日涨跌幅（截至 " + asOf + "）", decimal(indicators.get("return5Pct")), "%", asOf);
        add(facts, "近20个交易日涨跌幅（截至 " + asOf + "）", decimal(indicators.get("return20Pct")), "%", asOf);
        List<Map<String, Object>> bars = bars(payload.get("dailyKline"));
        if (bars.size() >= 61 && close != null) {
            BigDecimal base = decimal(bars.get(bars.size() - 61).get("close"));
            if (base != null && base.signum() != 0) {
                add(facts, "近60个交易日涨跌幅（截至 " + asOf + "，程序按东财前复权日K计算）",
                        percentChange(close, base), "%", asOf);
            }
            BigDecimal high = null;
            for (Map<String, Object> bar : bars.subList(bars.size() - 60, bars.size())) {
                BigDecimal barHigh = decimal(bar.get("high"));
                if (barHigh != null && (high == null || barHigh.compareTo(high) > 0)) high = barHigh;
            }
            if (high != null && high.signum() != 0) {
                add(facts, "距近60个交易日最高价回撤（截至 " + asOf + "，程序计算）", percentChange(close, high), "%", asOf);
            }
        }
        add(facts, "收盘价相对MA20偏离（" + asOf + "）", decimal(indicators.get("priceVsMa20Pct")), "%", asOf);
        BigDecimal ma60 = decimal(indicators.get("ma60"));
        if (close != null && ma60 != null && ma60.signum() != 0) {
            add(facts, "收盘价相对MA60偏离（" + asOf + "，程序计算）", percentChange(close, ma60), "%", asOf);
        }
        add(facts, "20日历史波动率（年化，" + asOf + "）", decimal(indicators.get("historicalVolatility20Pct")), "%", asOf);
        return facts.isEmpty() ? List.of(gap(label, "EASTMONEY_FIELDS_MISSING")) : facts;
    }

    static JSONObject normalizeStock(JSONObject stock) {
        if (stock == null) return null;
        String symbol = stock.getString("symbol");
        if (symbol == null || !symbol.trim().matches("\\d{6}")) return null;
        symbol = symbol.trim();
        String market = stock.getString("market");
        market = market == null ? inferMarket(symbol) : market.trim().toLowerCase();
        if (!MARKETS.contains(market)) return null;
        JSONObject normalized = new JSONObject(true);
        normalized.put("symbol", symbol);
        normalized.put("market", market);
        return normalized;
    }

    private static String inferMarket(String symbol) {
        char first = symbol.charAt(0);
        if (first == '6' || first == '9') return "sh";
        if (first == '0' || first == '2' || first == '3') return "sz";
        if (first == '4' || first == '8') return "bj";
        return null;
    }

    private static void add(List<JSONObject> facts, String label, BigDecimal value, String unit, String period) {
        if (value == null || period == null) return;
        JSONObject fact = new JSONObject(true);
        fact.put("tier", "F");
        fact.put("question", label);
        fact.put("statement", label + "：" + value.toPlainString() + unit);
        fact.put("value", value.toPlainString());
        fact.put("unit", unit);
        fact.put("period", period);
        fact.put("source", "东方财富");
        fact.put("singleSource", true);
        facts.add(fact);
    }

    private static JSONObject gap(String question, String reason) {
        JSONObject fact = new JSONObject(true);
        fact.put("tier", "G");
        fact.put("question", question);
        fact.put("reason", reason);
        return fact;
    }

    private static BigDecimal percentChange(BigDecimal now, BigDecimal base) {
        return now.divide(base, 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal yuan) {
        return yuan == null ? null : yuan.divide(HUNDRED_MILLION, 2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bars(Object value) {
        List<Map<String, Object>> bars = new ArrayList<>();
        if (!(value instanceof List)) return bars;
        for (Object item : (List<?>) value) {
            if (item instanceof Map) bars.add((Map<String, Object>) item);
        }
        return bars;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
