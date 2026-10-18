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
    /** About five years of daily bars for the reference class. */
    private static final int HISTORY_BARS = 1300;
    /** Volatility for the outcome intervals comes from the most recent year of daily returns. */
    private static final int VOLATILITY_WINDOW = 250;
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
                + "市盈率与市净率及其近一年分位、最近一期营业总收入与归母净利润及同比、扣非归母净利润、"
                + "加权净资产收益率、销售毛利率与净利率、经营现金流净额占营收比例、资产负债率（比率附上一报告期）、"
                + "近 5/20/60 个交易日涨跌幅、"
                + "距近 60 个交易日最高价回撤、相对 MA20/MA60 偏离、20 日历史波动率。取证题不要再问这些。"
                + "给出股票时，程序还会自动生成“未来 N 个交易日收益率”的结果区间题，子问题不要再问股价涨跌方向或幅度，"
                + "只拆推动走势的驱动因素。";
    }

    @Override
    public String issueCardField() {
        return "\"stock\":null";
    }

    @Override
    public void normalizeIssueCard(JSONObject raw, JSONObject normalized) {
        JSONObject stock = normalizeStock(raw.getJSONObject("stock"));
        if (stock != null) normalized.put("stock", stock);
    }

    @Override
    public DomainEvidence evidence(JSONObject issueCard) {
        List<JSONObject> facts = new ArrayList<>(marketFacts(issueCard));
        JSONObject stock = normalizeStock(issueCard.getJSONObject("stock"));
        int horizon = issueCard.getIntValue("horizonTradingDays");
        String label = "历史参照数据（" + stock.getString("symbol") + "." + stock.getString("market")
                + "，展望" + horizon + "个交易日）";
        History fetched;
        try {
            fetched = history(stock);
        } catch (IllegalStateException unavailable) {
            facts.add(gap(label, unavailable.getMessage()));
            return new DomainEvidence(facts, null);
        }
        String source = fetched.source;
        List<Map<String, Object>> history = fetched.bars;
        // A review keeps the original expiry: it forecasts from the latest close over the trading days left.
        JSONObject reviewOf = issueCard.getJSONObject("reviewOf");
        if (reviewOf != null && reviewOf.getString("baseDate") != null) {
            int base = -1;
            for (int i = 0; history != null && i < history.size(); i++) {
                if (reviewOf.getString("baseDate").equals(text(history.get(i).get("date")))) base = i;
            }
            if (base < 0) {
                facts.add(gap(label, "REVIEW_BASE_DATE_MISSING: " + reviewOf.getString("baseDate")));
                return new DomainEvidence(facts, null);
            }
            horizon = reviewOf.getIntValue("horizonTradingDays") - (history.size() - 1 - base);
            if (horizon <= 0) {
                facts.add(gap(label, "REVIEW_HORIZON_PASSED"));
                return new DomainEvidence(facts, null);
            }
        }
        List<BigDecimal> closes = new ArrayList<>();
        String baseDate = null;
        for (Map<String, Object> bar : history == null ? List.<Map<String, Object>>of() : history) {
            BigDecimal close = decimal(bar.get("close"));
            if (close != null && close.signum() > 0) {
                closes.add(close);
                baseDate = text(bar.get("date"));
            }
        }
        if (horizon <= 0 || closes.size() < horizon + VOLATILITY_WINDOW + 1) {
            facts.add(gap(label, "HISTORY_TOO_SHORT: " + closes.size() + " bars"));
            return new DomainEvidence(facts, null);
        }
        facts.add(referenceFact(closes, horizon, source));
        return new DomainEvidence(facts, outcomeQuestion(closes, horizon,
                baseDate == null ? issueCard.getString("asOf") : baseDate));
    }

    /**
     * Base and end closes come from one freshly fetched forward-adjusted series, so dividends between the
     * meeting and the judgment cannot shift the return. Expiry is the horizon-th bar after the base date.
     */
    @Override
    public OutcomeObservation observeOutcome(JSONObject issueCard, JSONObject outcomeQuestion) {
        JSONObject stock = normalizeStock(issueCard == null ? null : issueCard.getJSONObject("stock"));
        if (stock == null || outcomeQuestion == null) return null;
        String baseDate = outcomeQuestion.getString("baseDate");
        int horizon = outcomeQuestion.getIntValue("horizonTradingDays");
        if (baseDate == null || horizon <= 0) return OutcomeObservation.unavailable("结果题缺少起点日期或展望期");
        History fetched;
        try {
            fetched = history(stock);
        } catch (IllegalStateException unavailable) {
            return OutcomeObservation.unavailable(unavailable.getMessage());
        }
        List<Map<String, Object>> bars = fetched.bars == null ? List.of() : fetched.bars;
        int base = -1;
        for (int i = 0; i < bars.size(); i++) {
            if (baseDate.equals(text(bars.get(i).get("date")))) base = i;
        }
        if (base < 0) return OutcomeObservation.unavailable("起点 " + baseDate + " 不在" + fetched.source + "日K序列中");
        int elapsed = bars.size() - 1 - base;
        if (elapsed < horizon) {
            return OutcomeObservation.notDue(elapsed, baseDate, decimal(bars.get(base).get("close")),
                    text(bars.get(bars.size() - 1).get("date")), decimal(bars.get(bars.size() - 1).get("close")),
                    fetched.source);
        }
        Map<String, Object> end = bars.get(base + horizon);
        BigDecimal baseClose = decimal(bars.get(base).get("close"));
        BigDecimal endClose = decimal(end.get("close"));
        if (baseClose == null || baseClose.signum() <= 0 || endClose == null) {
            return OutcomeObservation.unavailable("起点或终点收盘价缺失");
        }
        return OutcomeObservation.due(elapsed, baseDate, baseClose, text(end.get("date")), endClose, fetched.source);
    }

    private static final class History {
        private final List<Map<String, Object>> bars;
        private final String source;

        private History(List<Map<String, Object>> bars, String source) {
            this.bars = bars;
            this.source = source;
        }
    }

    /** Eastmoney's history endpoint drops connections now and then: retry once, then use Tencent and say so. */
    private History history(JSONObject stock) {
        try {
            return new History(eastmoneyHistory(stock), "东方财富");
        } catch (RuntimeException eastmoneyDown) {
            try {
                return new History(eastmoney.fetchTencentDailyHistory(stock.getString("symbol"),
                        stock.getString("market"), HISTORY_BARS), "腾讯证券");
            } catch (RuntimeException tencentDown) {
                throw new IllegalStateException("EASTMONEY_HISTORY_UNAVAILABLE: " + eastmoneyDown.getMessage()
                        + "; TENCENT: " + tencentDown.getMessage());
            }
        }
    }

    private List<Map<String, Object>> eastmoneyHistory(JSONObject stock) {
        try {
            return eastmoney.fetchDailyHistory(stock.getString("symbol"), stock.getString("market"), HISTORY_BARS);
        } catch (RuntimeException firstFailure) {
            return eastmoney.fetchDailyHistory(stock.getString("symbol"), stock.getString("market"), HISTORY_BARS);
        }
    }

    /** Reference class: the distribution of every rolling horizon-day return in the available history. */
    private static JSONObject referenceFact(List<BigDecimal> closes, int horizon, String source) {
        List<Double> returns = new ArrayList<>();
        for (int i = 0; i + horizon < closes.size(); i++) {
            returns.add(closes.get(i + horizon).doubleValue() / closes.get(i).doubleValue() - 1);
        }
        java.util.Collections.sort(returns);
        long up = returns.stream().filter(value -> value > 0).count();
        String label = "历史参照（近" + closes.size() + "个交易日，滚动" + horizon
                + "个交易日涨跌幅，程序按" + ("腾讯证券".equals(source) ? "腾讯" : "东财") + "前复权日K计算）";
        String statement = label + "：P10 " + pct(percentile(returns, 0.10)) + "，P25 " + pct(percentile(returns, 0.25))
                + "，P50 " + pct(percentile(returns, 0.50)) + "，P75 " + pct(percentile(returns, 0.75))
                + "，P90 " + pct(percentile(returns, 0.90)) + "，上涨占比 " + pct((double) up / returns.size());
        JSONObject fact = new JSONObject(true);
        fact.put("tier", "F");
        fact.put("question", label);
        fact.put("statement", statement);
        fact.put("source", source);
        fact.put("singleSource", true);
        fact.put("referenceClass", true);
        return fact;
    }

    /**
     * Five return intervals cut symmetrically at ±0.5σ and ±1.5σ, where σ is the recent daily volatility scaled
     * to the horizon. Bounds are whole percentage points and are stored for judging the outcome at expiry.
     */
    private static JSONObject outcomeQuestion(List<BigDecimal> closes, int horizon, String baseDate) {
        List<Double> daily = new ArrayList<>();
        for (int i = closes.size() - VOLATILITY_WINDOW; i < closes.size(); i++) {
            daily.add(closes.get(i).doubleValue() / closes.get(i - 1).doubleValue() - 1);
        }
        double mean = daily.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = daily.stream().mapToDouble(value -> (value - mean) * (value - mean)).sum() / (daily.size() - 1);
        double sigmaPct = Math.sqrt(variance) * Math.sqrt(horizon) * 100;
        int inner = Math.max(1, (int) Math.round(0.5 * sigmaPct));
        int outer = Math.max(inner + 1, (int) Math.round(1.5 * sigmaPct));
        com.alibaba.fastjson.JSONArray options = new com.alibaba.fastjson.JSONArray();
        options.add(option("A", "跌幅超过" + outer + "%", null, -outer));
        options.add(option("B", "下跌" + inner + "%–" + outer + "%", -outer, -inner));
        options.add(option("C", "在±" + inner + "%以内", -inner, inner));
        options.add(option("D", "上涨" + inner + "%–" + outer + "%", inner, outer));
        options.add(option("E", "涨幅超过" + outer + "%", outer, null));
        JSONObject question = new JSONObject(true);
        question.put("id", "OUTCOME");
        question.put("kind", "PROBABILITY");
        question.put("title", "未来" + horizon + "个交易日收益率（以" + baseDate + "收盘价为起点，前复权）");
        question.put("horizonTradingDays", horizon);
        // The starting point for judging the outcome at expiry: the latest close actually available.
        question.put("baseDate", baseDate);
        question.put("baseClose", closes.get(closes.size() - 1));
        question.put("sigmaPct", BigDecimal.valueOf(sigmaPct).setScale(2, RoundingMode.HALF_UP));
        question.put("options", options);
        return question;
    }

    private static JSONObject option(String id, String label, Integer lower, Integer upper) {
        JSONObject option = new JSONObject(true);
        option.put("id", id);
        option.put("label", label);
        option.put("lowerPct", lower);
        option.put("upperPct", upper);
        return option;
    }

    private static double percentile(List<Double> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String pct(double ratio) {
        return BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private List<JSONObject> marketFacts(JSONObject issueCard) {
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
        String asOf = day(text(indicators.get("asOfDate")));
        BigDecimal close = decimal(indicators.get("close"));
        add(facts, "收盘价（" + asOf + "，前复权日K）", close, "元", asOf);
        Map<String, Object> detail = map(payload.get("stockDetail"));
        add(facts, "当日涨跌幅（" + asOf + "）", decimal(detail.get("changePercent")), "%", asOf);
        add(facts, "总市值（" + asOf + "）", scaled(decimal(detail.get("marketCap"))), "亿元", asOf);

        Map<String, Object> valuation = map(payload.get("valuation"));
        String valuationDate = day(text(valuation.get("tradeDate")));
        add(facts, "市盈率TTM（" + valuationDate + "）", decimal(valuation.get("peTtm")), "倍", valuationDate);
        add(facts, "市盈率TTM近一年分位（" + valuationDate + "，0%为近一年最低）",
                decimal(valuation.get("pePercentile1y")), "%", valuationDate);
        add(facts, "市净率MRQ（" + valuationDate + "）", decimal(valuation.get("pbMrq")), "倍", valuationDate);
        add(facts, "市净率MRQ近一年分位（" + valuationDate + "，0%为近一年最低）",
                decimal(valuation.get("pbPercentile1y")), "%", valuationDate);

        Object financials = payload.get("financials");
        if (financials instanceof List && !((List<?>) financials).isEmpty()) {
            Map<String, Object> latest = map(((List<?>) financials).get(0));
            String period = day(text(latest.get("reportDate")));
            add(facts, "营业总收入（报告期 " + period + "，累计）", scaled(decimal(latest.get("revenue"))), "亿元", period);
            add(facts, "营业总收入同比（报告期 " + period + "）", decimal(latest.get("revenueYoyPct")), "%", period);
            add(facts, "归母净利润（报告期 " + period + "，累计）", scaled(decimal(latest.get("netProfit"))), "亿元", period);
            add(facts, "归母净利润同比（报告期 " + period + "）", decimal(latest.get("netProfitYoyPct")), "%", period);
            add(facts, "扣非归母净利润（报告期 " + period + "，累计）", scaled(decimal(latest.get("netProfitExNonRecurring"))),
                    "亿元", period);
            // Ratios carry the previous report period, so members can see the direction without searching.
            Map<String, Object> previous = ((List<?>) financials).size() > 1 ? map(((List<?>) financials).get(1)) : Map.of();
            String previousPeriod = day(text(previous.get("reportDate")));
            addRatio(facts, "加权净资产收益率", "roePct", latest, previous, period, previousPeriod);
            addRatio(facts, "销售毛利率", "grossMarginPct", latest, previous, period, previousPeriod);
            addRatio(facts, "销售净利率", "netMarginPct", latest, previous, period, previousPeriod);
            addRatio(facts, "经营现金流净额占营收比例", "cashToRevenuePct", latest, previous, period, previousPeriod);
            addRatio(facts, "资产负债率", "debtRatioPct", latest, previous, period, previousPeriod);
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

    /** Values are shown with two decimals; members cite what they see, and the number gate matches by rounding. */
    private static void add(List<JSONObject> facts, String label, BigDecimal value, String unit, String period) {
        if (value == null || period == null) return;
        String shown = value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        JSONObject fact = new JSONObject(true);
        fact.put("tier", "F");
        fact.put("question", label);
        fact.put("statement", label + "：" + shown + unit);
        fact.put("value", shown);
        fact.put("unit", unit);
        fact.put("period", period);
        fact.put("source", "东方财富");
        fact.put("singleSource", true);
        facts.add(fact);
    }

    private static void addRatio(List<JSONObject> facts, String name, String key, Map<String, Object> latest,
                                 Map<String, Object> previous, String period, String previousPeriod) {
        BigDecimal value = decimal(latest.get(key));
        if (value == null || period == null) return;
        add(facts, name + "（报告期 " + period + "）", value, "%", period);
        BigDecimal before = decimal(previous.get(key));
        if (before != null && previousPeriod != null) {
            JSONObject fact = facts.get(facts.size() - 1);
            fact.put("statement", fact.getString("statement") + "（上一报告期 " + previousPeriod + "："
                    + before.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%）");
        }
    }

    /** "2026-10-16 00:00:00" → "2026-10-16". */
    private static String day(String date) {
        return date != null && date.length() > 10 ? date.substring(0, 10) : date;
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
