package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class EastmoneyMarketDataService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final String STOCK_DETAIL_URL = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String TRENDS_URL = "https://push2his.eastmoney.com/api/qt/stock/trends2/get";
    private static final String INTRADAY_FLOW_URL = "https://push2.eastmoney.com/api/qt/stock/fflow/kline/get";
    private static final String DATA_CENTER_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";
    private static final String ANNOUNCEMENT_URL = "https://np-anotice-stock.eastmoney.com/api/security/ann";
    private static final String NEWS_BULLETIN_URL = "https://emweb.securities.eastmoney.com/PC_HSF10/NewsBulletin/PageAjax";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StockQuoteService stockQuoteService;
    private final ThreadLocal<List<Map<String, Object>>> currentRequestTrace = new ThreadLocal<>();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build();

    public EastmoneyMarketDataService(StockQuoteService stockQuoteService) {
        this.stockQuoteService = stockQuoteService;
    }

    public MarketDataBundle buildBundle(String symbol, String market, String periodType) {
        String normalizedMarket = normalizeMarket(market, symbol);
        String normalizedPeriod = normalizePeriod(periodType);
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> sources = new LinkedHashMap<>();
        List<String> requiredMissing = new ArrayList<>();
        List<String> optionalMissing = new ArrayList<>();

        payload.put("asOf", ZonedDateTime.now(CHINA_ZONE).toString());
        payload.put("symbol", symbol);
        payload.put("market", normalizedMarket);
        payload.put("periodType", normalizedPeriod);

        Map<String, Object> quote = capture("quote", true, sources, requiredMissing, optionalMissing,
                () -> fetchQuote(symbol, normalizedMarket));
        if (quote != null) payload.put("quote", quote);

        Map<String, Object> detail = capture("stockDetail", false, sources, requiredMissing, optionalMissing,
                () -> fetchStockDetail(symbol, normalizedMarket));
        if (detail != null) payload.put("stockDetail", detail);

        int dailyLimit = "MONTH".equals(normalizedPeriod) ? 500 : ("WEEK".equals(normalizedPeriod) ? 300 : 160);
        List<Map<String, Object>> dailyBars = capture("dailyKline", true, sources, requiredMissing, optionalMissing,
                () -> fetchKlines(symbol, normalizedMarket, 101, dailyLimit));
        if (dailyBars == null) dailyBars = Collections.emptyList();
        if (dailyBars.size() < 30 && !requiredMissing.contains("dailyKline")) {
            requiredMissing.add("dailyKline:need_at_least_30_bars");
        }
        payload.put("dailyKline", tail(dailyBars, "DAY".equals(normalizedPeriod) ? 120 : 180));
        LocalDate sourceTradeDate = resolveSourceTradeDate(quote, dailyBars);
        payload.put("sourceTradeDate", sourceTradeDate.toString());
        payload.put("sourceTradeDateQuality", sourceTradeDateQuality(quote, dailyBars, sourceTradeDate));
        Map<String, Object> technicalIndicators = calculateIndicators(dailyBars);
        payload.put("technicalIndicators", technicalIndicators);
        payload.put("anchorFacts", buildAnchorFacts(quote, technicalIndicators, sourceTradeDate));

        List<Map<String, Object>> minuteTrends = capture("minuteTrends5d", false, sources, requiredMissing, optionalMissing,
                () -> fetchMinuteTrends(symbol, normalizedMarket));
        if (minuteTrends != null) payload.put("minuteTrends5d", tail(minuteTrends, 100));
        List<Map<String, Object>> intradayFlow = capture("intradayFundFlow", false, sources, requiredMissing, optionalMissing,
                () -> fetchIntradayFundFlow(symbol, normalizedMarket));
        if (intradayFlow != null) payload.put("intradayFundFlow", tail(intradayFlow, 80));

        int periodKlt = "MONTH".equals(normalizedPeriod) ? 103 : ("WEEK".equals(normalizedPeriod) ? 102 : 101);
        if (periodKlt != 101) {
            List<Map<String, Object>> periodBars = capture(normalizedPeriod.toLowerCase(Locale.ROOT) + "Kline", false,
                    sources, requiredMissing, optionalMissing,
                    () -> fetchKlines(symbol, normalizedMarket, periodKlt, 120));
            if (periodBars != null) payload.put("periodKline", tail(periodBars, 80));
        }

        Map<String, Object> valuation = capture("valuation", false, sources, requiredMissing, optionalMissing,
                () -> fetchValuation(symbol));
        if (valuation != null) payload.put("valuation", valuation);

        List<Map<String, Object>> financials = capture("financials", false, sources, requiredMissing, optionalMissing,
                () -> fetchFinancials(symbol, normalizedMarket));
        if (financials != null) payload.put("financials", financials);

        List<Map<String, Object>> announcements = capture("announcements", false, sources, requiredMissing, optionalMissing,
                () -> fetchAnnouncements(symbol));
        if (announcements != null) payload.put("announcements", announcements);

        List<Map<String, Object>> latestNews = capture("latestNews", false, sources, requiredMissing, optionalMissing,
                () -> fetchRecentNews(symbol, normalizedMarket));
        if (latestNews != null) payload.put("latestNews", latestNews);

        List<Map<String, Object>> shareholderCounts = capture("shareholderCounts", false, sources, requiredMissing, optionalMissing,
                () -> fetchShareholderCounts(symbol, normalizedMarket));
        if (shareholderCounts != null) payload.put("shareholderCounts", shareholderCounts);

        List<Map<String, Object>> benchmarkBars = capture("marketBenchmark", false, sources, requiredMissing, optionalMissing,
                () -> fetchBenchmarkKlines(symbol, normalizedMarket, 120));
        if (benchmarkBars != null) {
            Map<String, Object> benchmark = new LinkedHashMap<>();
            benchmark.put("name", benchmarkName(symbol, normalizedMarket));
            benchmark.put("return5Pct", periodReturn(benchmarkBars, 5));
            benchmark.put("return20Pct", periodReturn(benchmarkBars, 20));
            benchmark.put("relativeStrength20Pct", relativeStrength(dailyBars, benchmarkBars, 20));
            payload.put("marketBenchmark", benchmark);
        }

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("requiredMissing", requiredMissing);
        quality.put("optionalMissing", optionalMissing);
        quality.put("canPredict", requiredMissing.isEmpty());
        quality.put("sourceStatus", sources);
        payload.put("dataQuality", quality);
        return new MarketDataBundle(payload, requiredMissing, optionalMissing);
    }

    public List<Map<String, Object>> fetchDailyBars(String symbol, String market, int limit) {
        return fetchKlines(symbol, normalizeMarket(market, symbol), 101, Math.max(30, limit));
    }

    private Map<String, Object> fetchQuote(String symbol, String market) {
        List<Map<String, Object>> quotes = stockQuoteService.fetchQuotes(symbol + "." + market);
        Map<String, Object> quote = quotes.isEmpty() ? null : quotes.get(0);
        if (quote == null || !bool(quote.get("ok"))) {
            throw new IllegalStateException("quote_unavailable:" + (quote == null ? "empty" : quote.get("reason")));
        }
        return quote;
    }

    private Map<String, Object> fetchStockDetail(String symbol, String market) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("secid", secid(symbol, market));
        params.put("fltt", "2");
        params.put("invt", "2");
        params.put("fields", "f43,f44,f45,f46,f47,f48,f50,f57,f58,f60,f84,f85,f116,f117,f127,f162,f167,f168,f169,f170,f171,f173");
        JSONObject data = getJson(STOCK_DETAIL_URL, params).getJSONObject("data");
        if (data == null) throw new IllegalStateException("empty_stock_detail");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price", decimal(data.get("f43")));
        result.put("high", decimal(data.get("f44")));
        result.put("low", decimal(data.get("f45")));
        result.put("open", decimal(data.get("f46")));
        result.put("volume", decimal(data.get("f47")));
        result.put("amount", decimal(data.get("f48")));
        result.put("volumeRatio", decimal(data.get("f50")));
        result.put("previousClose", decimal(data.get("f60")));
        result.put("totalShares", decimal(data.get("f84")));
        result.put("freeFloatShares", decimal(data.get("f85")));
        result.put("marketCap", decimal(data.get("f116")));
        result.put("freeFloatMarketCap", decimal(data.get("f117")));
        result.put("industry", text(data.get("f127")));
        result.put("dynamicPe", decimal(data.get("f162")));
        result.put("pb", decimal(data.get("f167")));
        result.put("turnover", decimal(data.get("f168")));
        result.put("changeAmount", decimal(data.get("f169")));
        result.put("changePercent", decimal(data.get("f170")));
        result.put("amplitude", decimal(data.get("f171")));
        result.put("roe", decimal(data.get("f173")));
        return result;
    }

    private List<Map<String, Object>> fetchKlines(String symbol, String market, int klt, int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("secid", secid(symbol, market));
        params.put("fields1", "f1,f2,f3,f4,f5,f6");
        params.put("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61");
        params.put("klt", String.valueOf(klt));
        params.put("fqt", "1");
        params.put("beg", "19900101");
        params.put("end", "20500101");
        params.put("lmt", String.valueOf(limit));
        JSONObject data = getJson(KLINE_URL, params).getJSONObject("data");
        JSONArray klines = data == null ? null : data.getJSONArray("klines");
        if (klines == null || klines.isEmpty()) throw new IllegalStateException("empty_kline");
        List<Map<String, Object>> bars = new ArrayList<>();
        for (Object value : klines) {
            String[] parts = String.valueOf(value).split(",", -1);
            if (parts.length < 11) continue;
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("date", parts[0]);
            bar.put("open", decimal(parts[1]));
            bar.put("close", decimal(parts[2]));
            bar.put("high", decimal(parts[3]));
            bar.put("low", decimal(parts[4]));
            bar.put("volume", decimal(parts[5]));
            bar.put("amount", decimal(parts[6]));
            bar.put("amplitudePct", decimal(parts[7]));
            bar.put("changePct", decimal(parts[8]));
            bar.put("change", decimal(parts[9]));
            bar.put("turnoverPct", decimal(parts[10]));
            bars.add(bar);
        }
        if (bars.isEmpty()) throw new IllegalStateException("invalid_kline");
        return bars;
    }

    private List<Map<String, Object>> fetchMinuteTrends(String symbol, String market) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("secid", secid(symbol, market));
        params.put("ndays", "5");
        params.put("fields1", "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13");
        params.put("fields2", "f51,f52,f53,f54,f55,f56,f57,f58");
        JSONObject data = getJson(TRENDS_URL, params).getJSONObject("data");
        JSONArray rows = data == null ? null : data.getJSONArray("trends");
        if (rows == null || rows.isEmpty()) throw new IllegalStateException("empty_minute_trends");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : rows) {
            String[] parts = String.valueOf(value).split(",", -1);
            if (parts.length < 8) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", parts[0]);
            item.put("open", decimal(parts[1]));
            item.put("close", decimal(parts[2]));
            item.put("high", decimal(parts[3]));
            item.put("low", decimal(parts[4]));
            item.put("volume", decimal(parts[5]));
            item.put("amount", decimal(parts[6]));
            item.put("vwap", decimal(parts[7]));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> fetchIntradayFundFlow(String symbol, String market) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("secid", secid(symbol, market));
        params.put("lmt", "0");
        params.put("klt", "1");
        params.put("fields1", "f1,f2,f3,f7");
        params.put("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65");
        params.put("ut", "b2884a393a59ad64002292a3e90d46a5");
        JSONObject data = getJson(INTRADAY_FLOW_URL, params).getJSONObject("data");
        JSONArray rows = data == null ? null : data.getJSONArray("klines");
        if (rows == null || rows.isEmpty()) throw new IllegalStateException("empty_intraday_flow");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : rows) {
            String[] parts = String.valueOf(value).split(",", -1);
            if (parts.length < 6) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", parts[0]);
            item.put("main", decimal(parts[1]));
            item.put("small", decimal(parts[2]));
            item.put("medium", decimal(parts[3]));
            item.put("large", decimal(parts[4]));
            item.put("superLarge", decimal(parts[5]));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> fetchBenchmarkKlines(String symbol, String market, int limit) {
        if ("hk".equals(market)) {
            throw new IllegalStateException("unsupported_hk_benchmark");
        }
        String code = benchmarkCode(symbol, market);
        return fetchKlines(code.substring(code.indexOf('.') + 1), "1".equals(code.substring(0, 1)) ? "sh" : "sz", 101, limit);
    }

    private Map<String, Object> fetchValuation(String symbol) {
        Map<String, String> params = dataCenterParams("RPT_VALUEANALYSIS_DET");
        params.put("columns", "SECURITY_CODE,SECURITY_NAME_ABBR,TRADE_DATE,CLOSE_PRICE,CHANGE_RATE,PE_TTM,PE_LAR,PB_MRQ,PEG_CAR,PS_TTM,PCF_OCF_TTM,TOTAL_MARKET_CAP,BOARD_CODE,BOARD_NAME");
        params.put("filter", "(SECURITY_CODE=\"" + symbol + "\")");
        params.put("sortColumns", "TRADE_DATE");
        params.put("sortTypes", "-1");
        params.put("pageSize", "260");
        JSONArray rows = resultRows(getJson(DATA_CENTER_URL, params));
        if (rows.isEmpty()) throw new IllegalStateException("empty_valuation");
        JSONObject latest = rows.getJSONObject(0);
        List<Double> peHistory = new ArrayList<>();
        List<Double> pbHistory = new ArrayList<>();
        for (Object rowValue : rows) {
            JSONObject row = (JSONObject) rowValue;
            addNumber(peHistory, row.get("PE_TTM"));
            addNumber(pbHistory, row.get("PB_MRQ"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tradeDate", text(latest.get("TRADE_DATE")));
        result.put("close", decimal(latest.get("CLOSE_PRICE")));
        result.put("peTtm", decimal(latest.get("PE_TTM")));
        result.put("pbMrq", decimal(latest.get("PB_MRQ")));
        result.put("peg", decimal(latest.get("PEG_CAR")));
        result.put("psTtm", decimal(latest.get("PS_TTM")));
        result.put("pcfTtm", decimal(latest.get("PCF_OCF_TTM")));
        result.put("boardCode", text(latest.get("BOARD_CODE")));
        result.put("boardName", text(latest.get("BOARD_NAME")));
        result.put("pePercentile1y", percentile(peHistory, decimal(latest.get("PE_TTM"))));
        result.put("pbPercentile1y", percentile(pbHistory, decimal(latest.get("PB_MRQ"))));
        return result;
    }

    private List<Map<String, Object>> fetchFinancials(String symbol, String market) {
        Map<String, String> params = dataCenterParams("RPT_F10_FINANCE_MAINFINADATA");
        params.put("columns", "SECUCODE,REPORT_DATE,REPORT_TYPE,EPSJB,TOTALOPERATEREVE,TOTALOPERATEREVETZ,PARENTNETPROFIT,PARENTNETPROFITTZ,KCFJCXSYJLR,ROEJQ,XSMLL,XSJLL,JYXJLYYSR,ZCFZL,YSZKZZL,CHZZL");
        params.put("filter", "(SECUCODE=\"" + symbol + "." + market.toUpperCase(Locale.ROOT) + "\")");
        params.put("sortColumns", "REPORT_DATE");
        params.put("sortTypes", "-1");
        params.put("pageSize", "12");
        JSONArray rows = resultRows(getJson(DATA_CENTER_URL, params));
        if (rows.isEmpty()) throw new IllegalStateException("empty_financials");
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(12, rows.size()); i++) {
            JSONObject row = rows.getJSONObject(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reportDate", text(row.get("REPORT_DATE")));
            item.put("reportType", text(row.get("REPORT_TYPE")));
            item.put("eps", decimal(row.get("EPSJB")));
            item.put("revenue", decimal(row.get("TOTALOPERATEREVE")));
            item.put("revenueYoyPct", decimal(row.get("TOTALOPERATEREVETZ")));
            item.put("netProfit", decimal(row.get("PARENTNETPROFIT")));
            item.put("netProfitYoyPct", decimal(row.get("PARENTNETPROFITTZ")));
            item.put("netProfitExNonRecurring", decimal(row.get("KCFJCXSYJLR")));
            item.put("roePct", decimal(row.get("ROEJQ")));
            item.put("grossMarginPct", decimal(row.get("XSMLL")));
            item.put("netMarginPct", decimal(row.get("XSJLL")));
            item.put("cashToRevenuePct", decimal(row.get("JYXJLYYSR")));
            item.put("debtRatioPct", decimal(row.get("ZCFZL")));
            item.put("receivableTurnover", decimal(row.get("YSZKZZL")));
            item.put("inventoryTurnover", decimal(row.get("CHZZL")));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> fetchAnnouncements(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("stock_list", symbol);
        params.put("ann_type", "A");
        params.put("page_index", "1");
        params.put("page_size", "12");
        params.put("client_source", "web");
        JSONObject root = getJson(ANNOUNCEMENT_URL, params);
        JSONObject data = root.getJSONObject("data");
        JSONArray list = data == null ? null : data.getJSONArray("list");
        if (list == null) throw new IllegalStateException("empty_announcements");
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(12, list.size()); i++) {
            JSONObject row = list.getJSONObject(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("artCode", text(row.get("art_code")));
            item.put("title", text(row.get("title")));
            item.put("noticeDate", text(row.get("notice_date")));
            item.put("displayTime", text(row.get("display_time")));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> fetchRecentNews(String symbol, String market) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", market.toUpperCase(Locale.ROOT) + symbol);
        JSONObject root = getJson(NEWS_BULLETIN_URL, params);
        JSONObject newsSection = root.getJSONObject("gszx");
        JSONObject data = newsSection == null ? null : newsSection.getJSONObject("data");
        JSONArray list = data == null ? null : data.getJSONArray("items");
        if (list == null || list.isEmpty()) throw new IllegalStateException("empty_latest_news");
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            JSONObject row = list.getJSONObject(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", text(row.get("title")));
            item.put("source", text(row.get("source")));
            item.put("showTime", formatEpochMillis(row.get("showDateTime")));
            item.put("url", text(row.get("uniqueUrl")));
            item.put("summary", truncate(text(row.get("summary")), 260));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> fetchShareholderCounts(String symbol, String market) {
        Map<String, String> params = dataCenterParams("RPT_HOLDERNUM_DET");
        params.put("columns", "SECURITY_CODE,SECUCODE,SECURITY_NAME_ABBR,HOLDER_NUM,PRE_HOLDER_NUM,HOLDER_NUM_CHANGE,HOLDER_NUM_RATIO,INTERVAL_CHRATE,END_DATE,PRE_END_DATE,AVG_MARKET_CAP,AVG_HOLD_NUM,TOTAL_MARKET_CAP,TOTAL_A_SHARES,HOLD_NOTICE_DATE,CHANGE_SHARES,CHANGE_REASON,CLOSE_PRICE,REPORT");
        params.put("filter", "(SECURITY_CODE=\"" + symbol + "\")");
        params.put("sortColumns", "END_DATE");
        params.put("sortTypes", "-1");
        params.put("pageSize", "6");
        JSONArray rows = resultRows(getJson(DATA_CENTER_URL, params));
        if (rows.isEmpty()) throw new IllegalStateException("empty_shareholder_counts");
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(6, rows.size()); i++) {
            JSONObject row = rows.getJSONObject(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("endDate", text(row.get("END_DATE")));
            item.put("noticeDate", text(row.get("HOLD_NOTICE_DATE")));
            item.put("report", text(row.get("REPORT")));
            item.put("holderNum", decimal(row.get("HOLDER_NUM")));
            item.put("preHolderNum", decimal(row.get("PRE_HOLDER_NUM")));
            item.put("holderNumChange", decimal(row.get("HOLDER_NUM_CHANGE")));
            item.put("holderNumRatioPct", decimal(row.get("HOLDER_NUM_RATIO")));
            item.put("intervalPriceChangePct", decimal(row.get("INTERVAL_CHRATE")));
            item.put("avgHoldNum", decimal(row.get("AVG_HOLD_NUM")));
            item.put("avgMarketCap", decimal(row.get("AVG_MARKET_CAP")));
            item.put("totalMarketCap", decimal(row.get("TOTAL_MARKET_CAP")));
            item.put("totalAShares", decimal(row.get("TOTAL_A_SHARES")));
            item.put("closePrice", decimal(row.get("CLOSE_PRICE")));
            item.put("changeShares", decimal(row.get("CHANGE_SHARES")));
            item.put("changeReason", text(row.get("CHANGE_REASON")));
            result.add(item);
        }
        return result;
    }

    Map<String, Object> calculateIndicators(List<Map<String, Object>> bars) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (bars == null || bars.size() < 2) return result;
        List<Double> close = values(bars, "close");
        List<Double> high = values(bars, "high");
        List<Double> low = values(bars, "low");
        List<Double> volume = values(bars, "volume");
        double last = close.get(close.size() - 1);
        result.put("asOfDate", bars.get(bars.size() - 1).get("date"));
        result.put("close", round(last));
        putIfFinite(result, "ma5", averageTail(close, 5));
        putIfFinite(result, "ma10", averageTail(close, 10));
        putIfFinite(result, "ma20", averageTail(close, 20));
        putIfFinite(result, "ma60", averageTail(close, 60));
        double ema12 = ema(close, 12);
        double ema26 = ema(close, 26);
        double macd = ema12 - ema26;
        List<Double> diffs = macdSeries(close, 12, 26);
        double signal = ema(diffs, 9);
        putIfFinite(result, "macdDif", macd);
        putIfFinite(result, "macdDea", signal);
        putIfFinite(result, "macdHistogram", (macd - signal) * 2);
        putIfFinite(result, "rsi14", rsi(close, 14));
        putIfFinite(result, "atr14", atr(high, low, close, 14));
        double ma20 = averageTail(close, 20);
        double std20 = stdTail(close, 20);
        putIfFinite(result, "bollMiddle", ma20);
        putIfFinite(result, "bollUpper", ma20 + 2 * std20);
        putIfFinite(result, "bollLower", ma20 - 2 * std20);
        putIfFinite(result, "historicalVolatility20Pct", volatility(close, 20));
        putIfFinite(result, "return5Pct", returnPct(close, 5));
        putIfFinite(result, "return20Pct", returnPct(close, 20));
        putIfFinite(result, "support20", minTail(low, 20));
        putIfFinite(result, "resistance20", maxTail(high, 20));
        putIfFinite(result, "obv", obv(close, volume));
        if (Double.isFinite(ma20) && ma20 != 0) result.put("priceVsMa20Pct", round((last / ma20 - 1) * 100));
        return result;
    }

    private <T> T capture(String name, boolean required, Map<String, Object> sources,
                          List<String> requiredMissing, List<String> optionalMissing, Supplier<T> supplier) {
        long start = System.currentTimeMillis();
        Map<String, Object> status = new LinkedHashMap<>();
        List<Map<String, Object>> requestTrace = new ArrayList<>();
        currentRequestTrace.set(requestTrace);
        try {
            T value = supplier.get();
            status.put("ok", true);
            status.put("durationMs", System.currentTimeMillis() - start);
            status.put("count", value instanceof List ? ((List<?>) value).size() : 1);
            appendRequestTrace(status, requestTrace);
            sources.put(name, status);
            return value;
        } catch (Exception e) {
            status.put("ok", false);
            status.put("durationMs", System.currentTimeMillis() - start);
            status.put("reason", e.getMessage());
            appendRequestTrace(status, requestTrace);
            sources.put(name, status);
            (required ? requiredMissing : optionalMissing).add(name + ":" + e.getMessage());
            log.warn("Eastmoney data source failed source={} error={}", name, e.getMessage());
            return null;
        } finally {
            currentRequestTrace.remove();
        }
    }

    private JSONObject getJson(String baseUrl, Map<String, String> params) {
        Exception lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            String effectiveUrl = attempt > 0 && baseUrl.startsWith("https://datacenter-web.eastmoney.com")
                    ? baseUrl.replaceFirst("^https://", "http://")
                    : baseUrl;
            HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(effectiveUrl)).newBuilder();
            params.forEach(builder::addQueryParameter);
            HttpUrl url = builder.build();
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://quote.eastmoney.com/")
                    .get()
                    .build();
            long requestStart = System.currentTimeMillis();
            Map<String, Object> trace = buildRequestTrace(baseUrl, effectiveUrl, url, params, attempt + 1);
            boolean recorded = false;
            try (Response response = http.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (body.startsWith("\uFEFF")) {
                    body = body.substring(1);
                }
                trace.put("httpStatus", response.code());
                trace.put("durationMs", System.currentTimeMillis() - requestStart);
                trace.put("responseBytes", body.length());
                trace.put("responseHash", sha256Hex(body));
                if (!response.isSuccessful()) throw new IllegalStateException("http_" + response.code());
                JSONObject parsed = JSON.parseObject(body);
                if (parsed == null) throw new IllegalStateException("invalid_json");
                trace.put("ok", true);
                recordRequestTrace(trace);
                recorded = true;
                return parsed;
            } catch (Exception e) {
                if (!recorded) {
                    trace.put("ok", false);
                    trace.put("durationMs", System.currentTimeMillis() - requestStart);
                    trace.put("reason", e.getMessage());
                    recordRequestTrace(trace);
                }
                lastError = e;
                try {
                    Thread.sleep(300L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", interrupted);
                }
            }
        }
        throw new IllegalStateException(lastError == null ? "request_failed" : lastError.getMessage(), lastError);
    }

    private Map<String, Object> buildRequestTrace(String originalBaseUrl,
                                                  String effectiveBaseUrl,
                                                  HttpUrl url,
                                                  Map<String, String> params,
                                                  int attempt) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("provider", "eastmoney");
        trace.put("sourceType", "HTTP_JSON");
        trace.put("method", "GET");
        trace.put("attempt", attempt);
        trace.put("baseUrl", originalBaseUrl);
        if (!Objects.equals(originalBaseUrl, effectiveBaseUrl)) {
            trace.put("effectiveBaseUrl", effectiveBaseUrl);
        }
        trace.put("url", url.toString());
        trace.put("params", new LinkedHashMap<>(params));
        trace.put("paramsHash", sha256Hex(JSON.toJSONString(params)));
        trace.put("requestedAt", ZonedDateTime.now(CHINA_ZONE).toString());
        return trace;
    }

    private void recordRequestTrace(Map<String, Object> trace) {
        List<Map<String, Object>> traces = currentRequestTrace.get();
        if (traces != null && trace != null) {
            traces.add(trace);
        }
    }

    private void appendRequestTrace(Map<String, Object> status, List<Map<String, Object>> requestTrace) {
        if (requestTrace == null || requestTrace.isEmpty()) {
            status.put("sourceType", "INTERNAL_SERVICE");
            return;
        }
        status.put("sourceType", "HTTP_JSON");
        status.put("requestCount", requestTrace.size());
        Map<String, Object> last = requestTrace.get(requestTrace.size() - 1);
        status.put("lastUrl", last.get("url"));
        status.put("lastParamsHash", last.get("paramsHash"));
        status.put("lastResponseHash", last.get("responseHash"));
        status.put("requests", requestTrace);
    }

    private String sha256Hex(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> buildAnchorFacts(Map<String, Object> quote,
                                                 Map<String, Object> indicators,
                                                 LocalDate sourceTradeDate) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("sourceTradeDate", sourceTradeDate == null ? null : sourceTradeDate.toString());
        if (quote != null) {
            facts.put("priceYuan", quote.get("price"));
            facts.put("changePercent", quote.get("changePercent"));
            facts.put("amountYuan", quote.get("amount"));
            facts.put("turnoverPercent", quote.get("turnover"));
            facts.put("volumeRatio", quote.get("volumeRatio"));
            facts.put("mainInflowYuan", quote.get("mainInflow"));
            facts.put("mainInflowRatioPercent", quote.get("mainInflowRatio"));
            facts.put("quoteTimestamp", quote.get("timestamp"));
        }
        facts.put("technicalIndicators", indicators);
        facts.put("unitPolicy", "金额字段均为人民币元，比例字段均为百分数；必须直接引用，不得由模型重复换算");
        return facts;
    }

    private LocalDate resolveSourceTradeDate(Map<String, Object> quote, List<Map<String, Object>> dailyBars) {
        LocalDate quoteDate = tradeDateFromQuote(quote);
        if (quoteDate != null) {
            return quoteDate;
        }
        LocalDate dailyDate = latestDailyTradeDate(dailyBars);
        if (dailyDate != null) {
            return dailyDate;
        }
        return LocalDate.now(CHINA_ZONE);
    }

    private Map<String, Object> sourceTradeDateQuality(Map<String, Object> quote,
                                                       List<Map<String, Object>> dailyBars,
                                                       LocalDate selectedDate) {
        LocalDate quoteDate = tradeDateFromQuote(quote);
        LocalDate dailyDate = latestDailyTradeDate(dailyBars);
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("selectedSourceTradeDate", selectedDate == null ? null : selectedDate.toString());
        quality.put("quoteTradeDate", quoteDate == null ? null : quoteDate.toString());
        quality.put("dailyKlineTradeDate", dailyDate == null ? null : dailyDate.toString());
        quality.put("selectedSource", quoteDate != null ? "quote.timestamp"
                : (dailyDate != null ? "dailyKline.latest" : "system_date_fallback"));
        boolean consistent = quoteDate == null || dailyDate == null || quoteDate.equals(dailyDate);
        quality.put("consistent", consistent);
        if (!consistent) {
            quality.put("warning", "quote_trade_date_and_daily_kline_latest_date_mismatch");
        }
        return quality;
    }

    private LocalDate latestDailyTradeDate(List<Map<String, Object>> dailyBars) {
        if (dailyBars == null || dailyBars.isEmpty()) {
            return null;
        }
        Object rawDate = dailyBars.get(dailyBars.size() - 1).get("date");
        try {
            if (StringUtils.hasText(String.valueOf(rawDate))) {
                return LocalDate.parse(String.valueOf(rawDate).trim());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private LocalDate tradeDateFromQuote(Map<String, Object> quote) {
        if (quote == null || quote.isEmpty()) {
            return null;
        }
        Double epochSeconds = decimal(quote.get("timestamp"));
        if (epochSeconds != null && epochSeconds > 0) {
            try {
                return Instant.ofEpochSecond(epochSeconds.longValue()).atZone(CHINA_ZONE).toLocalDate();
            } catch (Exception ignored) {
            }
        }
        String updateTime = text(quote.get("updateTime"));
        if (StringUtils.hasText(updateTime)) {
            try {
                return java.time.LocalDateTime.parse(updateTime.trim(), DATE_TIME_FORMATTER).toLocalDate();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Map<String, String> dataCenterParams(String reportName) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("reportName", reportName);
        params.put("pageNumber", "1");
        params.put("source", "WEB");
        params.put("client", "WEB");
        return params;
    }

    private JSONArray resultRows(JSONObject root) {
        JSONObject result = root.getJSONObject("result");
        JSONArray rows = result == null ? null : result.getJSONArray("data");
        return rows == null ? new JSONArray() : rows;
    }

    private String secid(String symbol, String market) {
        return StockSymbol.eastmoneySecid(symbol, market);
    }

    private String benchmarkCode(String symbol, String market) {
        if ("sh".equals(market) && symbol != null && symbol.startsWith("688")) return "1.000688";
        if ("sh".equals(market)) return "1.000001";
        return "0.399001";
    }

    private String benchmarkName(String symbol, String market) {
        if ("hk".equals(market)) return "恒生指数";
        if ("sh".equals(market) && symbol != null && symbol.startsWith("688")) return "科创50";
        return "sh".equals(market) ? "上证指数" : "深证成指";
    }

    private String normalizeMarket(String market, String symbol) {
        if (StringUtils.hasText(market)) {
            return StockSymbol.normalizeMarket(market);
        }
        return StockSymbol.inferMarket(symbol);
    }

    private String normalizePeriod(String period) {
        String value = StringUtils.hasText(period) ? period.trim().toUpperCase(Locale.ROOT) : "DAY";
        return "WEEK".equals(value) || "MONTH".equals(value) ? value : "DAY";
    }

    private <T> List<T> tail(List<T> values, int count) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(values.subList(Math.max(0, values.size() - count), values.size()));
    }

    private List<Double> values(List<Map<String, Object>> bars, String key) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> bar : bars) {
            Double value = decimal(bar.get(key));
            if (value != null) values.add(value);
        }
        return values;
    }

    private double averageTail(List<Double> values, int count) {
        if (values.size() < count) return Double.NaN;
        return values.subList(values.size() - count, values.size()).stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private double stdTail(List<Double> values, int count) {
        if (values.size() < count) return Double.NaN;
        double mean = averageTail(values, count);
        double sum = 0;
        for (int i = values.size() - count; i < values.size(); i++) sum += Math.pow(values.get(i) - mean, 2);
        return Math.sqrt(sum / count);
    }

    private double ema(List<Double> values, int period) {
        if (values == null || values.isEmpty()) return Double.NaN;
        double alpha = 2.0 / (period + 1.0);
        double result = values.get(0);
        for (int i = 1; i < values.size(); i++) result = alpha * values.get(i) + (1 - alpha) * result;
        return result;
    }

    private List<Double> macdSeries(List<Double> values, int shortPeriod, int longPeriod) {
        List<Double> result = new ArrayList<>();
        if (values.isEmpty()) return result;
        double shortEma = values.get(0);
        double longEma = values.get(0);
        double shortAlpha = 2.0 / (shortPeriod + 1.0);
        double longAlpha = 2.0 / (longPeriod + 1.0);
        for (Double value : values) {
            shortEma = shortAlpha * value + (1 - shortAlpha) * shortEma;
            longEma = longAlpha * value + (1 - longAlpha) * longEma;
            result.add(shortEma - longEma);
        }
        return result;
    }

    private double rsi(List<Double> close, int period) {
        if (close.size() <= period) return Double.NaN;
        double gain = 0;
        double loss = 0;
        for (int i = close.size() - period; i < close.size(); i++) {
            double change = close.get(i) - close.get(i - 1);
            if (change >= 0) gain += change; else loss -= change;
        }
        if (loss == 0) return 100;
        return 100 - 100 / (1 + gain / loss);
    }

    private double atr(List<Double> high, List<Double> low, List<Double> close, int period) {
        int size = Math.min(high.size(), Math.min(low.size(), close.size()));
        if (size <= period) return Double.NaN;
        double sum = 0;
        for (int i = size - period; i < size; i++) {
            double previous = close.get(i - 1);
            sum += Math.max(high.get(i) - low.get(i), Math.max(Math.abs(high.get(i) - previous), Math.abs(low.get(i) - previous)));
        }
        return sum / period;
    }

    private double volatility(List<Double> close, int period) {
        if (close.size() <= period) return Double.NaN;
        List<Double> returns = new ArrayList<>();
        for (int i = close.size() - period; i < close.size(); i++) returns.add(Math.log(close.get(i) / close.get(i - 1)));
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0);
        return Math.sqrt(variance) * Math.sqrt(252) * 100;
    }

    private double returnPct(List<Double> values, int periods) {
        if (values.size() <= periods) return Double.NaN;
        double base = values.get(values.size() - 1 - periods);
        return base == 0 ? Double.NaN : (values.get(values.size() - 1) / base - 1) * 100;
    }

    private Double periodReturn(List<Map<String, Object>> bars, int periods) {
        return finiteOrNull(returnPct(values(bars, "close"), periods));
    }

    private Double relativeStrength(List<Map<String, Object>> stock, List<Map<String, Object>> benchmark, int periods) {
        Double stockReturn = periodReturn(stock, periods);
        Double benchmarkReturn = periodReturn(benchmark, periods);
        return stockReturn == null || benchmarkReturn == null ? null : round(stockReturn - benchmarkReturn);
    }

    private double minTail(List<Double> values, int count) {
        if (values.size() < count) return Double.NaN;
        return values.subList(values.size() - count, values.size()).stream().min(Comparator.naturalOrder()).orElse(Double.NaN);
    }

    private double maxTail(List<Double> values, int count) {
        if (values.size() < count) return Double.NaN;
        return values.subList(values.size() - count, values.size()).stream().max(Comparator.naturalOrder()).orElse(Double.NaN);
    }

    private double obv(List<Double> close, List<Double> volume) {
        int size = Math.min(close.size(), volume.size());
        if (size < 2) return Double.NaN;
        double result = 0;
        for (int i = 1; i < size; i++) {
            if (close.get(i) > close.get(i - 1)) result += volume.get(i);
            else if (close.get(i) < close.get(i - 1)) result -= volume.get(i);
        }
        return result;
    }

    private void putIfFinite(Map<String, Object> target, String key, double value) {
        if (Double.isFinite(value)) target.put(key, round(value));
    }

    private Double finiteOrNull(double value) {
        return Double.isFinite(value) ? round(value) : null;
    }

    private Double percentile(List<Double> values, Double current) {
        if (current == null || values.isEmpty()) return null;
        long atOrBelow = values.stream().filter(value -> value <= current).count();
        return round(atOrBelow * 100.0 / values.size());
    }

    private void addNumber(List<Double> target, Object value) {
        Double number = decimal(value);
        if (number != null && Double.isFinite(number)) target.add(number);
    }

    private Double decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value)) || "-".equals(String.valueOf(value))) return null;
        try {
            return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String formatEpochMillis(Object value) {
        Double number = decimal(value);
        if (number == null || number <= 0) return text(value);
        try {
            return Instant.ofEpochMilli(number.longValue()).atZone(CHINA_ZONE).format(DATE_TIME_FORMATTER);
        } catch (Exception ignored) {
            return text(value);
        }
    }

    private String truncate(String value, int limit) {
        if (value == null || limit <= 0) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private boolean bool(Object value) {
        return value instanceof Boolean ? (Boolean) value : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    @Getter
    public static class MarketDataBundle {
        private final Map<String, Object> payload;
        private final List<String> requiredMissing;
        private final List<String> optionalMissing;

        public MarketDataBundle(Map<String, Object> payload, List<String> requiredMissing, List<String> optionalMissing) {
            this.payload = payload;
            this.requiredMissing = new ArrayList<>(requiredMissing);
            this.optionalMissing = new ArrayList<>(optionalMissing);
        }

        public boolean canPredict() {
            return requiredMissing.isEmpty();
        }

        public String toJson() {
            return JSON.toJSONString(payload);
        }
    }
}
