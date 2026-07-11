package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.Proxy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class StockWatchExternalResearchService {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String TRADING_VIEW_CHINA_SCAN_URL = "https://scanner.tradingview.com/china/scan";
    private static final List<String> EASTMONEY_QUOTE_URLS = Arrays.asList(
            "https://push2delay.eastmoney.com/api/qt/ulist.np/get",
            "https://push2.eastmoney.com/api/qt/ulist.np/get"
    );
    private static final String EASTMONEY_FIELDS = String.join(",",
            "f2", "f3", "f4", "f5", "f6", "f8", "f10", "f12", "f13", "f14",
            "f15", "f16", "f17", "f18", "f62", "f124", "f184");
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> TRADING_VIEW_COLUMNS = Arrays.asList(
            "name", "description", "exchange", "close", "change", "volume",
            "Recommend.All", "RSI", "MACD.macd", "MACD.signal", "SMA20", "EMA20", "market_cap_basic"
    );

    private final OkHttpClient http;
    private final String scannerUrl;

    public StockWatchExternalResearchService() {
        this(new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build(), TRADING_VIEW_CHINA_SCAN_URL);
    }

    StockWatchExternalResearchService(OkHttpClient http, String scannerUrl) {
        this.http = http;
        this.scannerUrl = scannerUrl;
    }

    public Map<String, Object> fetchEvidence(String rawSymbol, String rawMarket) {
        StockRef ref = normalizeStockRef(rawSymbol, rawMarket);
        Map<String, Object> payload = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        payload.put("symbol", ref.symbol);
        payload.put("market", ref.market);
        payload.put("checkedAt", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        payload.put("mode", "manual_external_research");
        payload.put("mailTriggerAffected", false);
        payload.put("monitorAffected", false);
        payload.put("storageAffected", false);

        String tradingViewPrefix = tradingViewPrefix(ref.market);
        if (!StringUtils.hasText(tradingViewPrefix)) {
            warnings.add("TradingView external evidence currently supports Shanghai/Shenzhen symbols only.");
            payload.put("ok", false);
            payload.put("status", "UNSUPPORTED_MARKET");
            payload.put("warnings", warnings);
            payload.put("tradingView", failedSource("TradingView China scanner", "unsupported_market"));
            payload.put("eastmoney", new LinkedHashMap<>());
            payload.put("comparison", new LinkedHashMap<>());
            return payload;
        }

        String tradingViewSymbol = tradingViewPrefix + ":" + ref.symbol;
        Map<String, Object> tradingView = fetchTradingViewSnapshot(tradingViewSymbol, warnings);
        Map<String, Object> eastmoney = fetchEastmoneySnapshot(ref, warnings);
        Map<String, Object> comparison = compareSnapshots(tradingView, eastmoney);

        payload.put("ok", Boolean.TRUE.equals(tradingView.get("ok")) && Boolean.TRUE.equals(eastmoney.get("ok")));
        payload.put("status", comparison.getOrDefault("verdict", "INCOMPLETE"));
        payload.put("warnings", warnings);
        payload.put("tradingView", tradingView);
        payload.put("eastmoney", eastmoney);
        payload.put("comparison", comparison);
        payload.put("boundary", Arrays.asList(
                "只做手动外部研究证据展示",
                "不接入 StockWatchMonitorService",
                "不触发 NotificationService",
                "不改变 Eastmoney 主行情链路"
        ));
        return payload;
    }

    private Map<String, Object> fetchTradingViewSnapshot(String tradingViewSymbol, List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "TradingView China scanner");
        result.put("sourceMode", "public-scanner-compatible-with-tradingview-mcp");
        result.put("symbol", tradingViewSymbol);
        result.put("columns", TRADING_VIEW_COLUMNS);
        result.put("volumeUnit", "share");

        JSONObject body = new JSONObject(true);
        JSONObject symbols = new JSONObject(true);
        JSONArray tickers = new JSONArray();
        tickers.add(tradingViewSymbol);
        JSONObject query = new JSONObject(true);
        query.put("types", new JSONArray());
        symbols.put("tickers", tickers);
        symbols.put("query", query);
        body.put("symbols", symbols);
        JSONArray columns = new JSONArray();
        for (String column : TRADING_VIEW_COLUMNS) {
            columns.add(column);
        }
        body.put("columns", columns);

        Request request = new Request.Builder()
                .url(scannerUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = http.newCall(request).execute()) {
            result.put("httpStatus", response.code());
            if (!response.isSuccessful() || response.body() == null) {
                String reason = "tradingview_http_" + response.code();
                warnings.add("TradingView scanner returned " + response.code());
                result.putAll(failedSource("TradingView China scanner", reason));
                return result;
            }
            String responseBody = response.body().string();
            JSONObject root = JSON.parseObject(responseBody);
            JSONArray rows = root == null ? null : root.getJSONArray("data");
            result.put("totalCount", root == null ? 0 : root.getIntValue("totalCount"));
            if (rows == null || rows.isEmpty()) {
                warnings.add("TradingView scanner returned no row for " + tradingViewSymbol);
                result.putAll(failedSource("TradingView China scanner", "tradingview_missing"));
                return result;
            }
            JSONObject row = rows.getJSONObject(0);
            JSONArray values = row.getJSONArray("d");
            Map<String, Object> fields = new LinkedHashMap<>();
            for (int i = 0; i < TRADING_VIEW_COLUMNS.size(); i += 1) {
                fields.put(TRADING_VIEW_COLUMNS.get(i), values == null || i >= values.size() ? null : values.get(i));
            }
            result.put("ok", true);
            result.put("tvSymbol", row.getString("s"));
            result.put("fields", fields);
            result.put("name", fields.get("name"));
            result.put("description", fields.get("description"));
            result.put("exchange", fields.get("exchange"));
            result.put("price", numberOrNull(fields.get("close")));
            result.put("changePercent", numberOrNull(fields.get("change")));
            result.put("volumeShares", numberOrNull(fields.get("volume")));
            result.put("recommendAll", numberOrNull(fields.get("Recommend.All")));
            result.put("rsi", numberOrNull(fields.get("RSI")));
            result.put("macd", numberOrNull(fields.get("MACD.macd")));
            result.put("macdSignal", numberOrNull(fields.get("MACD.signal")));
            result.put("sma20", numberOrNull(fields.get("SMA20")));
            result.put("ema20", numberOrNull(fields.get("EMA20")));
            result.put("marketCapBasic", numberOrNull(fields.get("market_cap_basic")));
            return result;
        } catch (Exception e) {
            log.warn("TradingView external evidence failed symbol={} error={}", tradingViewSymbol, e.getMessage());
            warnings.add("TradingView scanner error: " + e.getMessage());
            result.putAll(failedSource("TradingView China scanner", "tradingview_error"));
            return result;
        }
    }

    private Map<String, Object> fetchEastmoneySnapshot(StockRef ref, List<String> warnings) {
        String secid = ("sh".equals(ref.market) ? "1" : "0") + "." + ref.symbol;
        Exception lastError = null;
        Integer lastCode = null;
        for (String quoteUrl : EASTMONEY_QUOTE_URLS) {
            HttpUrl url = HttpUrl.parse(quoteUrl).newBuilder()
                    .addQueryParameter("fltt", "2")
                    .addQueryParameter("invt", "2")
                    .addQueryParameter("fields", EASTMONEY_FIELDS)
                    .addQueryParameter("secids", secid)
                    .addQueryParameter("_", String.valueOf(System.currentTimeMillis()))
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("Connection", "close")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://quote.eastmoney.com/")
                    .get()
                    .build();
            try (Response response = http.newCall(request).execute()) {
                lastCode = response.code();
                if (!response.isSuccessful() || response.body() == null) {
                    continue;
                }
                JSONObject root = JSON.parseObject(response.body().string());
                JSONObject data = root == null ? null : root.getJSONObject("data");
                JSONArray diff = data == null ? null : data.getJSONArray("diff");
                if (diff == null || diff.isEmpty()) {
                    continue;
                }
                return toEastmoneyQuote(diff.getJSONObject(0), quoteUrl);
            } catch (Exception e) {
                lastError = e;
                log.warn("Eastmoney external evidence compare failed url={} symbol={} market={} error={}",
                        quoteUrl, ref.symbol, ref.market, e.getMessage());
            }
        }
        String reason = lastError != null ? "eastmoney_error" : "eastmoney_http_" + lastCode;
        warnings.add("Eastmoney quote not available: " + reason);
        return failedSource("Eastmoney quote", reason);
    }

    private Map<String, Object> toEastmoneyQuote(JSONObject item, String sourceUrl) {
        Map<String, Object> quote = new LinkedHashMap<>();
        Long timestamp = longOrNull(item.get("f124"));
        Double volumeHands = numberOrNull(item.get("f5"));
        quote.put("ok", true);
        quote.put("source", "Eastmoney quote");
        quote.put("sourceUrl", sourceUrl);
        quote.put("symbol", item.getString("f12"));
        quote.put("market", marketFromEastmoney(item.getInteger("f13"), item.getString("f12")));
        quote.put("name", item.getString("f14"));
        quote.put("price", numberOrNull(item.get("f2")));
        quote.put("changePercent", numberOrNull(item.get("f3")));
        quote.put("changeAmount", numberOrNull(item.get("f4")));
        quote.put("volume", volumeHands);
        quote.put("volumeUnit", "hand");
        quote.put("volumeShares", volumeHands == null ? null : volumeHands * 100.0);
        quote.put("amount", numberOrNull(item.get("f6")));
        quote.put("turnover", numberOrNull(item.get("f8")));
        quote.put("volumeRatio", numberOrNull(item.get("f10")));
        quote.put("high", numberOrNull(item.get("f15")));
        quote.put("low", numberOrNull(item.get("f16")));
        quote.put("open", numberOrNull(item.get("f17")));
        quote.put("previousClose", numberOrNull(item.get("f18")));
        quote.put("mainInflow", numberOrNull(item.get("f62")));
        quote.put("mainInflowRatio", numberOrNull(item.get("f184")));
        quote.put("timestamp", timestamp);
        quote.put("updateTime", formatTime(timestamp, DATE_TIME_FORMATTER));
        quote.put("lastTime", formatTime(timestamp, TIME_FORMATTER));
        return quote;
    }

    Map<String, Object> compareSnapshots(Map<String, Object> tradingView, Map<String, Object> eastmoney) {
        Map<String, Object> comparison = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(tradingView.get("ok")) || !Boolean.TRUE.equals(eastmoney.get("ok"))) {
            comparison.put("verdict", "INCOMPLETE");
            comparison.put("summary", "至少一个数据源不可用，无法完成交叉校验。");
            return comparison;
        }

        Double tvPrice = numberOrNull(tradingView.get("price"));
        Double emPrice = numberOrNull(eastmoney.get("price"));
        Double tvChange = numberOrNull(tradingView.get("changePercent"));
        Double emChange = numberOrNull(eastmoney.get("changePercent"));
        Double tvVolumeShares = numberOrNull(tradingView.get("volumeShares"));
        Double emVolumeShares = numberOrNull(eastmoney.get("volumeShares"));

        Double priceDiff = diff(tvPrice, emPrice);
        Double changeDiff = diff(tvChange, emChange);
        Double volumeDiff = diff(tvVolumeShares, emVolumeShares);
        Double volumeDiffPercent = percentDiff(volumeDiff, emVolumeShares);

        boolean priceMatched = priceDiff != null && Math.abs(priceDiff) <= 0.01;
        boolean changeMatched = changeDiff != null && Math.abs(changeDiff) <= 0.03;
        boolean volumeMatched = volumeDiffPercent == null || Math.abs(volumeDiffPercent) <= 1.0;

        String verdict = priceMatched && changeMatched && volumeMatched ? "MATCH" : "CHECK";
        comparison.put("verdict", verdict);
        comparison.put("summary", "MATCH".equals(verdict)
                ? "TradingView 与 Eastmoney 价格/涨跌幅一致，成交量换算后偏差在容忍范围内。"
                : "TradingView 与 Eastmoney 存在偏差，建议人工复核数据时间和单位。");
        comparison.put("priceDiff", priceDiff);
        comparison.put("changePercentDiff", changeDiff);
        comparison.put("volumeDiffShares", volumeDiff);
        comparison.put("volumeDiffPercent", volumeDiffPercent);
        comparison.put("volumeUnitNote", "TradingView volume=股；Eastmoney f5=手，比较时已按 Eastmoney*100 换算。");
        comparison.put("priceMatched", priceMatched);
        comparison.put("changePercentMatched", changeMatched);
        comparison.put("volumeMatched", volumeMatched);
        return comparison;
    }

    private Map<String, Object> failedSource(String source, String reason) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("ok", false);
        failed.put("source", source);
        failed.put("reason", reason);
        return failed;
    }

    private StockRef normalizeStockRef(String rawSymbol, String rawMarket) {
        String value = firstNonBlank(rawSymbol, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String market = normalizeMarket(firstNonBlank(rawMarket, ""));
        java.util.regex.Matcher prefixed = java.util.regex.Pattern
                .compile("^(sh|sz|bj|sse|szse)[._:-]?(\\d{6})$")
                .matcher(value);
        if (prefixed.matches()) {
            return new StockRef(prefixed.group(2), normalizeMarket(prefixed.group(1)));
        }
        java.util.regex.Matcher suffixed = java.util.regex.Pattern
                .compile("^(\\d{6})(?:[._:-]?(sh|sz|bj|sse|szse))?$")
                .matcher(value);
        if (!suffixed.matches()) {
            throw new IllegalArgumentException("股票编码格式不正确");
        }
        String symbol = suffixed.group(1);
        String suffixMarket = normalizeMarket(suffixed.group(2));
        return new StockRef(symbol, StringUtils.hasText(market)
                ? market
                : (StringUtils.hasText(suffixMarket) ? suffixMarket : inferMarket(symbol)));
    }

    private String normalizeMarket(String market) {
        String value = firstNonBlank(market, "").toLowerCase(Locale.ROOT);
        if ("sse".equals(value)) return "sh";
        if ("szse".equals(value)) return "sz";
        return value;
    }

    private String inferMarket(String symbol) {
        if (symbol.startsWith("6") || symbol.startsWith("9") || symbol.startsWith("5")) return "sh";
        if (symbol.startsWith("4") || symbol.startsWith("8")) return "bj";
        return "sz";
    }

    private String tradingViewPrefix(String market) {
        String value = normalizeMarket(market);
        if ("sh".equals(value)) return "SSE";
        if ("sz".equals(value)) return "SZSE";
        return "";
    }

    private Double diff(Double first, Double second) {
        return first == null || second == null ? null : first - second;
    }

    private Double percentDiff(Double diff, Double base) {
        if (diff == null || base == null || Math.abs(base) < 0.000001) {
            return null;
        }
        return diff / base * 100.0;
    }

    private Double numberOrNull(Object raw) {
        if (raw == null || "-".equals(String.valueOf(raw))) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longOrNull(Object raw) {
        if (raw == null || "-".equals(String.valueOf(raw))) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String marketFromEastmoney(Integer value, String symbol) {
        if (value != null && value == 1) {
            return "sh";
        }
        if (symbol != null && (symbol.startsWith("4") || symbol.startsWith("8"))) {
            return "bj";
        }
        return "sz";
    }

    private String formatTime(Long timestamp, DateTimeFormatter formatter) {
        if (timestamp == null || timestamp <= 0) {
            return "--";
        }
        return Instant.ofEpochSecond(timestamp).atZone(CHINA_ZONE).format(formatter);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static class StockRef {
        private final String symbol;
        private final String market;

        private StockRef(String symbol, String market) {
            this.symbol = symbol;
            this.market = market;
        }
    }
}
