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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private static final String EASTMONEY_FIELDS = String.join(",",
            "f2", "f3", "f4", "f5", "f6", "f8", "f10", "f12", "f13", "f14",
            "f15", "f16", "f17", "f18", "f62", "f124", "f152");
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();

    public List<Map<String, Object>> fetchQuotes(String rawSymbols) {
        List<StockRef> refs = parseStockRefs(rawSymbols);
        if (refs.isEmpty()) {
            return Collections.emptyList();
        }

        String secids = refs.stream()
                .map(StockRef::secid)
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
                        .map(ref -> byCode.getOrDefault(ref.symbol, failedQuote(ref, "eastmoney_missing")))
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
        quote.put("timestamp", timestamp);
        quote.put("updateTime", formatTime(timestamp, DATE_TIME_FORMATTER));
        quote.put("lastTime", formatTime(timestamp, TIME_FORMATTER));
        return quote;
    }

    private Map<String, Object> failedQuote(StockRef ref, String reason) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", false);
        quote.put("source", "Eastmoney quote");
        quote.put("symbol", ref.symbol);
        quote.put("market", ref.market);
        quote.put("reason", reason);
        return quote;
    }

    private List<StockRef> parseStockRefs(String rawSymbols) {
        if (rawSymbols == null || rawSymbols.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<StockRef> refs = new ArrayList<>();
        for (String token : rawSymbols.split("[,;\\s]+")) {
            StockRef ref = parseStockRef(token);
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private StockRef parseStockRef(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (value.isEmpty()) {
            return null;
        }

        java.util.regex.Matcher prefixed = java.util.regex.Pattern
                .compile("^(sh|sz|bj)[._-]?(\\d{6})$")
                .matcher(value);
        if (prefixed.matches()) {
            return new StockRef(prefixed.group(2), prefixed.group(1));
        }

        java.util.regex.Matcher suffixed = java.util.regex.Pattern
                .compile("^(\\d{6})(?:[._-]?(sh|sz|bj))?$")
                .matcher(value);
        if (!suffixed.matches()) {
            return null;
        }
        String symbol = suffixed.group(1);
        String market = suffixed.group(2) != null ? suffixed.group(2) : inferMarket(symbol);
        return new StockRef(symbol, market);
    }

    private String inferMarket(String symbol) {
        if (symbol == null) {
            return "sh";
        }
        if (symbol.startsWith("6") || symbol.startsWith("9")) {
            return "sh";
        }
        if (symbol.startsWith("4") || symbol.startsWith("8")) {
            return "bj";
        }
        return "sz";
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

    private static class StockRef {
        private final String symbol;
        private final String market;

        private StockRef(String symbol, String market) {
            this.symbol = symbol;
            this.market = market;
        }

        private String secid() {
            return ("sh".equals(market) ? "1" : "0") + "." + symbol;
        }
    }
}
