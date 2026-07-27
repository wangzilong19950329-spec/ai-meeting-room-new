package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.net.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StockQuoteService {

    private static final List<String> EASTMONEY_QUOTE_URLS = Arrays.asList(
            "https://push2delay.eastmoney.com/api/qt/ulist.np/get",
            "https://push2.eastmoney.com/api/qt/ulist.np/get"
    );
    private static final List<String> EASTMONEY_TRENDS_URLS = Arrays.asList(
            "https://push2delay.eastmoney.com/api/qt/stock/trends2/get",
            "https://push2his.eastmoney.com/api/qt/stock/trends2/get",
            "https://push2.eastmoney.com/api/qt/stock/trends2/get"
    );
    private static final String EASTMONEY_FIELDS = String.join(",",
            "f2", "f3", "f4", "f5", "f6", "f8", "f10", "f12", "f13", "f14",
            "f15", "f16", "f17", "f18", "f62", "f66", "f69", "f72", "f75", "f78", "f81",
            "f84", "f87", "f124", "f152", "f184");
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter MINUTE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();

    public List<Map<String, Object>> fetchQuotes(String rawSymbols) {
        List<StockSymbol> refs = parseStockRefs(rawSymbols);
        if (refs.isEmpty()) {
            return Collections.emptyList();
        }

        String secids = refs.stream()
                .map(StockSymbol::eastmoneySecid)
                .distinct()
                .collect(Collectors.joining(","));

        Exception lastError = null;
        Integer lastCode = null;
        for (String quoteUrl : EASTMONEY_QUOTE_URLS) {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(quoteUrl)).newBuilder()
                    .addQueryParameter("fltt", "2")
                    .addQueryParameter("invt", "2")
                    .addQueryParameter("fields", EASTMONEY_FIELDS)
                    .addQueryParameter("secids", secids)
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
                    log.warn("Eastmoney quote failed: url={}, code={}", quoteUrl, response.code());
                    continue;
                }
                String body = response.body().string();
                JSONObject root = JSON.parseObject(body);
                JSONObject data = root.getJSONObject("data");
                JSONArray diff = data == null ? null : data.getJSONArray("diff");
                if (diff == null || diff.isEmpty()) {
                    log.warn("Eastmoney quote returned empty diff: url={}", quoteUrl);
                    continue;
                }

                Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
                for (int i = 0; i < diff.size(); i++) {
                    JSONObject item = diff.getJSONObject(i);
                    Map<String, Object> quote = toQuote(item);
                    byCode.put(String.valueOf(quote.get("symbol")), quote);
                }

                return refs.stream()
                        .map(ref -> {
                            Map<String, Object> quote = byCode.get(ref.getSymbol());
                            if (quote == null) {
                                return failedQuote(ref, "eastmoney_missing");
                            }
                            Map<String, Object> normalized = new LinkedHashMap<>(quote);
                            normalized.put("market", ref.getMarket());
                            return normalized;
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                lastError = e;
                log.warn("Eastmoney quote error: url={}, error={}", quoteUrl, e.getMessage());
            }
        }
        String reason = lastCode != null ? "eastmoney_http_" + lastCode : "eastmoney_error";
        if (lastError != null) {
            reason = "eastmoney_error";
        }
        final String failureReason = reason;
        return refs.stream().map(ref -> failedQuote(ref, failureReason)).collect(Collectors.toList());
    }

    public Map<String, Object> fetchIntradayBars(String rawSymbol, String rawMarket) {
        StockSymbol ref;
        try {
            ref = StockSymbol.parse(rawSymbol, rawMarket);
        } catch (IllegalArgumentException e) {
            return intradayEnvelope(rawSymbol, rawMarket, "INVALID_REQUEST", e.getMessage(), Collections.emptyList());
        }
        String lastReason = "eastmoney_error";
        for (String trendsUrl : EASTMONEY_TRENDS_URLS) {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(trendsUrl)).newBuilder()
                    .addQueryParameter("secid", ref.eastmoneySecid())
                    .addQueryParameter("ndays", "1")
                    .addQueryParameter("fields1", "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13")
                    .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57,f58")
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
                if (!response.isSuccessful() || response.body() == null) {
                    lastReason = "eastmoney_http_" + response.code();
                    log.warn("Eastmoney intraday bars failed: url={}, code={}", trendsUrl, response.code());
                    continue;
                }
                JSONObject root = JSON.parseObject(response.body().string());
                JSONObject data = root == null ? null : root.getJSONObject("data");
                JSONArray rows = data == null ? null : data.getJSONArray("trends");
                if (rows == null || rows.isEmpty()) {
                    lastReason = "eastmoney_empty_trends";
                    continue;
                }
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object raw : rows) {
                    Map<String, Object> bar = toIntradayBar(raw);
                    if (bar != null) {
                        result.add(bar);
                    }
                }
                return intradayEnvelope(ref, summarizeIntradayStatus(result), "", result);
            } catch (Exception e) {
                lastReason = "eastmoney_error:" + e.getClass().getSimpleName();
                log.warn("Eastmoney intraday bars error: url={}, symbol={}, market={}, error={}",
                        trendsUrl, ref.getSymbol(), ref.getMarket(), e.getMessage());
            }
        }
        return intradayEnvelope(ref, "SOURCE_ERROR", lastReason, Collections.emptyList());
    }

    private Map<String, Object> intradayEnvelope(StockSymbol ref, String status, String reason, List<Map<String, Object>> bars) {
        return intradayEnvelope(ref.getSymbol(), ref.getMarket(), status, reason, bars);
    }

    private Map<String, Object> intradayEnvelope(String symbol, String market, String status, String reason, List<Map<String, Object>> bars) {
        List<Map<String, Object>> safeBars = bars == null ? Collections.emptyList() : bars;
        long validBars = safeBars.stream().filter(bar -> "VALID".equals(bar.get("qualityStatus"))).count();
        long invalidBars = safeBars.stream().filter(bar -> "INVALID".equals(bar.get("qualityStatus"))).count();
        Map<String, Object> qualitySummary = new LinkedHashMap<>();
        qualitySummary.put("totalBars", safeBars.size());
        qualitySummary.put("validBars", validBars);
        qualitySummary.put("invalidBars", invalidBars);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestedSymbol", symbol);
        result.put("requestedMarket", market);
        result.put("provider", "Eastmoney");
        result.put("source", "Eastmoney trends2");
        result.put("status", status);
        result.put("reason", reason);
        result.put("fetchedAt", Instant.now().toString());
        result.put("asOfTime", safeBars.isEmpty() ? null : safeBars.get(safeBars.size() - 1).get("startTime"));
        result.put("qualitySummary", qualitySummary);
        result.put("bars", safeBars);
        return result;
    }

    private String summarizeIntradayStatus(List<Map<String, Object>> bars) {
        if (bars == null || bars.isEmpty()) {
            return "EMPTY";
        }
        long invalidBars = bars.stream().filter(bar -> "INVALID".equals(bar.get("qualityStatus"))).count();
        if (invalidBars == 0) {
            return "VALID";
        }
        return invalidBars == bars.size() ? "INVALID" : "DEGRADED";
    }

    private Map<String, Object> toQuote(JSONObject item) {
        String symbol = item.getString("f12");
        String market = marketFromEastmoney(item.getInteger("f13"), symbol);
        Long timestamp = readLong(item.get("f124"));
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("source", "Eastmoney quote");
        quote.put("symbol", symbol);
        quote.put("market", market);
        quote.put("name", item.getString("f14"));
        quote.put("price", readDouble(item.get("f2")));
        quote.put("changePercent", readDouble(item.get("f3")));
        quote.put("changeAmount", readDouble(item.get("f4")));
        quote.put("volume", readDouble(item.get("f5")));
        quote.put("amount", readDouble(item.get("f6")));
        quote.put("turnover", readDouble(item.get("f8")));
        quote.put("volumeRatio", readDouble(item.get("f10")));
        quote.put("high", readDouble(item.get("f15")));
        quote.put("low", readDouble(item.get("f16")));
        quote.put("open", readDouble(item.get("f17")));
        quote.put("previousClose", readDouble(item.get("f18")));
        quote.put("mainInflow", readDouble(item.get("f62")));
        quote.put("mainInflowRatio", readDouble(item.get("f184")));
        quote.put("superLargeInflow", readDouble(item.get("f66")));
        quote.put("superLargeInflowRatio", readDouble(item.get("f69")));
        quote.put("largeInflow", readDouble(item.get("f72")));
        quote.put("largeInflowRatio", readDouble(item.get("f75")));
        quote.put("mediumInflow", readDouble(item.get("f78")));
        quote.put("mediumInflowRatio", readDouble(item.get("f81")));
        quote.put("smallInflow", readDouble(item.get("f84")));
        quote.put("smallInflowRatio", readDouble(item.get("f87")));
        quote.put("timestamp", timestamp);
        quote.put("updateTime", formatTime(timestamp, DATE_TIME_FORMATTER));
        quote.put("lastTime", formatTime(timestamp, TIME_FORMATTER));
        return quote;
    }

    private Map<String, Object> toIntradayBar(Object value) {
        String[] parts = String.valueOf(value).split(",", -1);
        if (parts.length < 8) {
            return null;
        }
        Double open = readDouble(parts[1]);
        Double close = readDouble(parts[2]);
        Double high = readDouble(parts[3]);
        Double low = readDouble(parts[4]);
        Map<String, Object> bar = new LinkedHashMap<>();
        String startTime = parts[0];
        bar.put("startTime", startTime);
        bar.put("endTime", endTime(startTime));
        bar.put("open", open);
        bar.put("high", high);
        bar.put("low", low);
        bar.put("close", close);
        bar.put("volume", readDouble(parts[5]));
        bar.put("amount", readDouble(parts[6]));
        bar.put("qualityStatus", open == null || high == null || low == null || close == null ? "INVALID" : "VALID");
        return bar;
    }

    private String endTime(String startTime) {
        if (startTime == null || startTime.trim().isEmpty()) {
            return startTime;
        }
        try {
            return LocalDateTime.parse(startTime, MINUTE_TIME_FORMATTER)
                    .plusMinutes(1)
                    .format(MINUTE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return startTime;
        }
    }

    private Map<String, Object> failedQuote(StockSymbol ref, String reason) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", false);
        quote.put("source", "Eastmoney quote");
        quote.put("symbol", ref.getSymbol());
        quote.put("market", ref.getMarket());
        quote.put("reason", reason);
        return quote;
    }

    private List<StockSymbol> parseStockRefs(String rawSymbols) {
        if (rawSymbols == null || rawSymbols.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<StockSymbol> refs = new ArrayList<>();
        for (String token : rawSymbols.split("[,;\\s]+")) {
            StockSymbol ref = StockSymbol.tryParse(token, "");
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private String marketFromEastmoney(Integer value, String symbol) {
        return StockSymbol.marketFromEastmoney(value, symbol);
    }

    private Double readDouble(Object value) {
        if (value == null || "-".equals(String.valueOf(value))) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long readLong(Object value) {
        if (value == null || "-".equals(String.valueOf(value))) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatTime(Long timestamp, DateTimeFormatter formatter) {
        if (timestamp == null || timestamp <= 0) {
            return "--";
        }
        return Instant.ofEpochSecond(timestamp).atZone(CHINA_ZONE).format(formatter);
    }

}
