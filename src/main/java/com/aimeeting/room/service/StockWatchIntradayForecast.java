package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional M30 contract; never participates in legacy forecast validation or fact identity. */
final class StockWatchIntradayForecast {
    static final String VERSION = "M30_MVP_V1";
    static final int MAX_QUOTE_AGE_SECONDS = 120;
    static final int MAX_RECORDING_DELAY_SECONDS = 300;
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private StockWatchIntradayForecast() { }

    static JSONObject request(String symbol, String market, Map<String, Object> quote, Instant asOf) {
        Instant source = sourceTime(quote == null ? null : quote.get("timestamp"));
        Double price = positive(quote == null ? null : quote.get("price"));
        String status = "ELIGIBLE";
        if (!ordinaryStock(symbol, market)) status = "UNSUPPORTED_STOCK";
        else if (!continuousWindow(asOf)) status = "OUTSIDE_CONTINUOUS_WINDOW";
        else if (quote == null || !Boolean.TRUE.equals(quote.get("ok"))) status = "QUOTE_UNAVAILABLE";
        else if (!symbol.equals(quote.get("providerSymbol")) || !market.equals(quote.get("providerMarket"))
                || !"UNADJUSTED".equals(quote.get("priceBasis"))) status = "IDENTITY_UNVERIFIED";
        else if (price == null) status = "PRICE_UNAVAILABLE";
        else if (source == null) status = "SOURCE_TIME_UNAVAILABLE";
        else if (source.isAfter(asOf)) status = "FUTURE_INPUT";
        else if (Duration.between(source, asOf).compareTo(Duration.ofSeconds(MAX_QUOTE_AGE_SECONDS)) > 0)
            status = "STALE_INPUT";
        JSONObject result = new JSONObject(true);
        result.put("version", VERSION);
        result.put("asOfAt", asOf.toString());
        result.put("targetAt", asOf.plusSeconds(1800).toString());
        result.put("quoteSourceAt", source == null ? null : source.toString());
        result.put("referencePrice", price);
        result.put("priceBasis", "UNADJUSTED");
        result.put("inputStatus", status);
        result.put("informationCutoffStatus", "UNVERIFIED_WEB_SEARCH");
        result.put("evaluationScope", "FROZEN_QUOTE_TO_TARGET");
        result.put("maxQuoteAgeSeconds", MAX_QUOTE_AGE_SECONDS);
        result.put("maxPublishDelaySeconds", MAX_RECORDING_DELAY_SECONDS);
        return result;
    }

    static boolean ordinaryStock(String symbol, String market) {
        return symbol != null && (("sh".equals(market) && symbol.matches("(?:600|601|603|605|688)\\d{3}"))
                || ("sz".equals(market) && symbol.matches("(?:000|001|002|003|300|301)\\d{3}")));
    }

    static boolean continuousWindow(Instant start) {
        ZonedDateTime local = start.atZone(CHINA_ZONE);
        if (local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        LocalTime time = local.toLocalTime();
        return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 0)))
                || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(14, 30)));
    }

    static String promptExtension(JSONObject request) {
        return "\nM30_MVP_V1 独立扩展：保留上述所有基础v6字段及T1/T5 forecasts，不把M30放入forecasts。"
                + "在根对象额外返回intradayForecast，且仅有status、direction、targetLow、targetHigh四个字段。"
                + "这是从服务端asOfAt到targetAt的未来30分钟到期价格预测，不是全程最高/最低价。"
                + "PREDICTED时direction只能UP或DOWN，targetLow/targetHigh为未复权正数且low<=high。"
                + "无法判断或inputStatus不是ELIGIBLE时必须ABSTAIN，direction/targetLow/targetHigh均为null。"
                + "不得改写服务端时间，不输出概率或置信度，不用历史结论代替本次证据。"
                + "\nintradayForecastRequest=" + request.toJSONString();
    }

    static Map<String, Object> view(StockWatchTechnicalAnalysis record, Instant now) {
        try { return validatedView(record, now); }
        catch (RuntimeException invalidOptionalExtension) {
            return Map.of("version", VERSION, "status", "INVALID_REQUEST", "timingBasis", "DB_INSERT_PROXY");
        }
    }

    private static Map<String, Object> validatedView(StockWatchTechnicalAnalysis record, Instant now) {
        if (record == null) return null;
        JSONObject envelope = object(record.getTaskRequestEnvelopeJson());
        JSONObject projection = envelope == null ? null : object(envelope.getString("projectionJson"));
        JSONObject request = projection == null ? null : projection.getJSONObject("intradayForecastRequest");
        if (request == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION);
        result.put("timingBasis", "DB_INSERT_PROXY");
        result.put("evaluationScope", "FROZEN_QUOTE_TO_TARGET");
        result.put("informationCutoffStatus", "UNVERIFIED_WEB_SEARCH");
        result.put("status", "INVALID_REQUEST");
        String digest = record.getTaskRequestDigest();
        if (!VERSION.equals(request.get("version")) || !"DAY".equals(record.getPeriodType())
                || !ordinaryStock(record.getSymbol(), record.getMarket())
                || digest == null || !digest.matches("[a-f0-9]{64}") || !digest.equals(envelope.getString("requestDigest"))
                || !sha256(envelope.getString("projectionJson")).equals(envelope.getString("projectionDigest"))) return result;
        if (!"UNVERIFIED_WEB_SEARCH".equals(request.get("informationCutoffStatus"))
                || !"FROZEN_QUOTE_TO_TARGET".equals(request.get("evaluationScope"))
                || !(request.get("maxQuoteAgeSeconds") instanceof Number)
                || ((Number) request.get("maxQuoteAgeSeconds")).doubleValue() != MAX_QUOTE_AGE_SECONDS
                || !(request.get("maxPublishDelaySeconds") instanceof Number)
                || ((Number) request.get("maxPublishDelaySeconds")).doubleValue() != MAX_RECORDING_DELAY_SECONDS) return result;
        Instant start = instant(request.get("asOfAt"));
        Instant target = instant(request.get("targetAt"));
        Instant source = instant(request.get("quoteSourceAt"));
        Double reference = positive(request.get("referencePrice"));
        if (start == null || target == null || !target.equals(start.plusSeconds(1800))) return result;
        result.put("asOfAt", start.toString());
        result.put("targetAt", target.toString());
        result.put("referencePrice", reference);
        result.put("recordedAt", record.getGmtCreate() == null ? null : record.getGmtCreate().toInstant().toString());
        String inputStatus = request.getString("inputStatus");
        if (!"ELIGIBLE".equals(inputStatus)) {
            result.put("status", "INPUT_UNAVAILABLE");
            result.put("inputStatus", knownInputStatus(inputStatus));
        } else if (!continuousWindow(start) || reference == null || source == null || source.isAfter(start)
                || Duration.between(source, start).compareTo(Duration.ofSeconds(MAX_QUOTE_AGE_SECONDS)) > 0
                || !"UNADJUSTED".equals(request.get("priceBasis"))) {
            return result;
        } else if (!"SUCCESS".equals(record.getStatus())) {
            result.put("status", "FAILED");
        } else {
            JSONObject root = responseObject(record.getRawResponse());
            Object raw = root == null ? null : root.get("intradayForecast");
            boolean missing = root == null ? record.getRawResponse() == null || record.getRawResponse().isBlank()
                    : !root.containsKey("intradayForecast");
            result.put("status", missing ? "MISSING" : "INVALID");
            if (raw instanceof Map) {
                Map<?, ?> forecast = (Map<?, ?>) raw;
                if ("ABSTAIN".equals(forecast.get("status")) && forecast.containsKey("direction")
                        && forecast.containsKey("targetLow") && forecast.containsKey("targetHigh")
                        && forecast.get("direction") == null && forecast.get("targetLow") == null && forecast.get("targetHigh") == null) {
                    result.put("status", "ABSTAIN");
                } else if ("PREDICTED".equals(forecast.get("status"))
                        && ("UP".equals(forecast.get("direction")) || "DOWN".equals(forecast.get("direction")))) {
                    Double low = positive(forecast.get("targetLow"));
                    Double high = positive(forecast.get("targetHigh"));
                    if (low != null && high != null && low <= high) {
                        result.put("status", "PREDICTED");
                        result.put("direction", forecast.get("direction"));
                        result.put("targetLow", low);
                        result.put("targetHigh", high);
                    }
                }
            }
        }
        // Recording is only a measurable proxy, not proof the client had the signal then.
        Instant recorded = record.getGmtCreate() == null ? null : record.getGmtCreate().toInstant();
        String timing = recorded == null ? "UNKNOWN" : recorded.isBefore(start) || recorded.isAfter(now) ? "INVALID"
                : Duration.between(start, recorded).compareTo(Duration.ofSeconds(MAX_RECORDING_DELAY_SECONDS)) > 0 ? "LATE" : "TIMELY_PROXY";
        result.put("timingStatus", timing);
        result.put("lifecycle", !now.isBefore(target) ? "EXPIRED"
                : now.isBefore(start) ? "NOT_STARTED"
                : "TIMELY_PROXY".equals(timing) && "PREDICTED".equals(result.get("status")) ? "ACTIVE" : "UNAVAILABLE");
        return result;
    }

    private static String knownInputStatus(String status) {
        return java.util.Set.of("UNSUPPORTED_STOCK", "OUTSIDE_CONTINUOUS_WINDOW", "QUOTE_UNAVAILABLE",
                "IDENTITY_UNVERIFIED", "PRICE_UNAVAILABLE", "SOURCE_TIME_UNAVAILABLE", "FUTURE_INPUT", "STALE_INPUT")
                .contains(status == null ? "" : status) ? status : "UNKNOWN";
    }

    private static JSONObject responseObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start < 0 || end < start ? null : object(text.substring(start, end + 1));
    }

    private static JSONObject object(String text) {
        try { return text == null ? null : JSON.parseObject(text); }
        catch (RuntimeException invalid) { return null; }
    }

    private static Double positive(Object value) {
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number > 0 ? number : null;
    }

    private static Instant sourceTime(Object value) {
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number <= 0 || number != Math.rint(number)) return null;
        try { return Instant.ofEpochSecond(((Number) value).longValue()); }
        catch (RuntimeException invalid) { return null; }
    }

    private static Instant instant(Object value) {
        try { return value instanceof String ? Instant.parse((String) value) : null; }
        catch (RuntimeException invalid) { return null; }
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception invalid) { return ""; }
    }
}
