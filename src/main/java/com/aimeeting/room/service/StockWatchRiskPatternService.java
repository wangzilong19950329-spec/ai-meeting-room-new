package com.aimeeting.room.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StockWatchRiskPatternService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int BASELINE_BARS = 20;

    public RiskPatternResult analyze(String symbol,
                                     String market,
                                     Map<String, Object> quote,
                                     List<Map<String, Object>> dailyBars,
                                     ZonedDateTime evaluationAt) {
        ZonedDateTime effectiveEvaluationAt = evaluationAt == null
                ? ZonedDateTime.now(CHINA_ZONE)
                : evaluationAt.withZoneSameInstant(CHINA_ZONE);
        String normalizedMarket = normalizeMarket(market);
        Map<String, Object> observations = new LinkedHashMap<>();
        Map<String, Object> guidance = guidance();
        List<String> eligibilityMissing = new ArrayList<>();
        List<Map<String, Object>> patterns = new ArrayList<>();

        observations.put("dataLevel", "L0");
        observations.put("symbol", symbol);
        observations.put("market", normalizedMarket);
        observations.put("evaluatedAt", effectiveEvaluationAt.toString());

        if (!isMainlandMarket(normalizedMarket)) {
            eligibilityMissing.add("market_not_supported_for_l0:" + normalizedMarket);
            addSkippedPatterns(patterns, "market_not_supported_for_l0");
            return finish(observations, guidance, eligibilityMissing, patterns, Collections.emptyMap(), null, 0);
        }
        if (quote == null || !bool(quote.get("ok"), true)) {
            eligibilityMissing.add("canonical_quote_unavailable");
            addSkippedPatterns(patterns, "canonical_quote_unavailable");
            return finish(observations, guidance, eligibilityMissing, patterns, Collections.emptyMap(), null, 0);
        }

        ZonedDateTime quoteObservedAt = parseQuoteTime(quote.get("timestamp"));
        if (quoteObservedAt == null) {
            quoteObservedAt = parseQuoteTime(quote.get("updateTime"));
        }
        LocalDate quoteTradeDate = quoteObservedAt == null ? null : quoteObservedAt.toLocalDate();
        String marketPhase = resolveMarketPhase(effectiveEvaluationAt, quoteObservedAt);
        long quoteAgeSeconds = quoteObservedAt == null
                ? Long.MAX_VALUE
                : ChronoUnit.SECONDS.between(quoteObservedAt, effectiveEvaluationAt);
        String freshnessStatus = freshnessStatus(effectiveEvaluationAt, quoteObservedAt, marketPhase, quoteAgeSeconds);
        if (!"FRESH".equals(freshnessStatus) && !"POST_CLOSE_VALID".equals(freshnessStatus)) {
            eligibilityMissing.add("quote_freshness:" + freshnessStatus);
        }

        List<Map<String, Object>> completedBars = completedBars(dailyBars, quoteTradeDate);
        Map<String, Object> sourceQuality = new LinkedHashMap<>();
        sourceQuality.put("canonicalQuoteSource", text(quote.get("source"), "monitor.quote"));
        sourceQuality.put("canonicalQuoteObservedAt", quoteObservedAt == null ? null : quoteObservedAt.toString());
        sourceQuality.put("canonicalQuoteTradeDate", quoteTradeDate == null ? null : quoteTradeDate.toString());
        sourceQuality.put("latestDailyKlineDate", latestDate(dailyBars));
        sourceQuality.put("completedBaselineBarCount", completedBars.size());
        sourceQuality.put("quoteAgeSeconds", quoteAgeSeconds == Long.MAX_VALUE ? null : quoteAgeSeconds);
        sourceQuality.put("freshnessStatus", freshnessStatus);
        sourceQuality.put("consistent", sourceDatesConsistent(dailyBars, quoteTradeDate));
        observations.put("sourceQuality", sourceQuality);
        observations.put("asOfTradeDate", quoteTradeDate == null ? null : quoteTradeDate.toString());
        observations.put("marketPhase", marketPhase);

        if (!bool(sourceQuality.get("consistent"), false)) {
            eligibilityMissing.add("source_trade_date_conflict");
        }

        Map<String, Object> derived = derive(quote, completedBars, marketPhase);
        boolean commonEligible = eligibilityMissing.isEmpty();
        patterns.add(r01(derived, commonEligible, completedBars.size()));
        patterns.add(r02(derived, commonEligible, completedBars.size()));
        patterns.add(r03(derived, commonEligible));
        patterns.add(r04(quote, completedBars, derived, commonEligible));
        return finish(observations, guidance, eligibilityMissing, patterns, derived, quoteObservedAt, completedBars.size());
    }

    private RiskPatternResult finish(Map<String, Object> observations,
                                     Map<String, Object> guidance,
                                     List<String> missing,
                                     List<Map<String, Object>> patterns,
                                     Map<String, Object> derived,
                                     ZonedDateTime quoteObservedAt,
                                     int completedBarCount) {
        boolean active = patterns.stream().anyMatch(this::activePattern);
        boolean skipped = patterns.stream().anyMatch(item -> "skipped".equals(item.get("status")));
        observations.put("evaluationStatus", active ? "ACTIVE" : (skipped ? "SKIPPED_UNAVAILABLE" : "EVALUATED_NONE"));
        observations.put("riskEligibilityMissing", missing);
        observations.put("derived", derived);
        observations.put("patterns", patterns);
        observations.put("completedBaselineBarCount", completedBarCount);
        return new RiskPatternResult(observations, guidance, quoteObservedAt, active);
    }

    private Map<String, Object> derive(Map<String, Object> quote,
                                       List<Map<String, Object>> completedBars,
                                       String marketPhase) {
        Map<String, Object> derived = new LinkedHashMap<>();
        Double close = number(quote.get("price"));
        Double open = number(quote.get("open"));
        Double high = number(quote.get("high"));
        Double low = number(quote.get("low"));
        Double previousClose = number(quote.get("previousClose"));
        Double dailyReturn = number(quote.get("changePercent"));
        if (dailyReturn == null) dailyReturn = pct(close, previousClose);
        Double gapOpen = pct(open, previousClose);
        Double intradayFromOpen = pct(close, open);
        Double range = validRange(high, low);
        Double closeLocation = range == null || close == null ? null : clamp01((close - low) / range);
        Double upperShadow = range == null || close == null || open == null || high == null
                ? null : clamp01((high - Math.max(open, close)) / range);
        Double upExcursion = range == null || open == null || high == null
                ? null : clamp01(Math.max(0.0, high - open) / range);
        List<Map<String, Object>> baseline20 = tail(completedBars, BASELINE_BARS);
        Double breakoutHigh20 = baseline20.size() < BASELINE_BARS ? null : max(baseline20, "high");
        Double breakoutPct = breakoutHigh20 == null || high == null ? null : (high / breakoutHigh20 - 1.0) * 100.0;
        Double amountRatio20 = null;
        Double turnoverPercentile20 = null;
        if (baseline20.size() == BASELINE_BARS) {
            Double averageAmount = average(baseline20, "amount");
            Double amount = number(quote.get("amount"));
            if (amount != null && averageAmount != null && averageAmount > 0) amountRatio20 = amount / averageAmount;
            Double turnover = number(quote.get("turnover"));
            if (turnover != null && baseline20.stream().allMatch(bar -> number(bar.get("turnoverPct")) != null)) {
                long count = baseline20.stream().filter(bar -> number(bar.get("turnoverPct")) <= turnover).count();
                turnoverPercentile20 = count * 100.0 / BASELINE_BARS;
            }
        }
        derived.put("close", close);
        derived.put("dailyReturnPct", dailyReturn);
        derived.put("gapOpenPct", gapOpen);
        derived.put("intradayFromOpenPct", intradayFromOpen);
        derived.put("closeLocationValue", closeLocation);
        derived.put("upperShadowPctOfRange", upperShadow);
        derived.put("upExcursionShare", upExcursion);
        derived.put("breakoutHigh20", breakoutHigh20);
        derived.put("breakoutPct", breakoutPct);
        derived.put("volumeRatio", number(quote.get("volumeRatio")));
        derived.put("amountRatio20", amountRatio20);
        derived.put("amountRatio20EligibleForTrigger", "POST_CLOSE".equals(marketPhase));
        derived.put("turnoverPercentile20", turnoverPercentile20);
        derived.put("turnoverPercentile20EligibleForTrigger", "POST_CLOSE".equals(marketPhase));
        derived.put("mainInflow", number(quote.get("mainInflow")));
        return derived;
    }

    private Map<String, Object> r01(Map<String, Object> d, boolean commonEligible, int completedBars) {
        if (!commonEligible) return skipped("R01", "data_quality_gate_failed", 0.70);
        if (completedBars < BASELINE_BARS) return skipped("R01", "need_20_completed_daily_bars", 0.70);
        Double breakoutPct = number(d.get("breakoutPct"));
        Double close = number(d.get("close"));
        Double breakoutHigh = number(d.get("breakoutHigh20"));
        Double closeLocation = number(d.get("closeLocationValue"));
        Boolean active = activity(d, 1.2, 1.2, 70.0);
        if (breakoutPct == null || close == null || breakoutHigh == null || closeLocation == null || active == null) {
            return skipped("R01", "required_derived_value_missing", 0.70);
        }
        boolean breakout = breakoutPct >= 0.5;
        boolean failed = close <= breakoutHigh;
        boolean weakHold = close > breakoutHigh && closeLocation <= 0.45;
        String status = breakout && failed && active ? "triggered" : (breakout && (failed || weakHold) ? "watch" : "not_triggered");
        return pattern("R01", status, 0.70,
                "突破后未站稳风险", "后续放量站稳突破位", breakoutPct, active, failed || weakHold);
    }

    private Map<String, Object> r02(Map<String, Object> d, boolean commonEligible, int completedBars) {
        if (!commonEligible) return skipped("R02", "data_quality_gate_failed", 0.68);
        if (completedBars < BASELINE_BARS) return skipped("R02", "need_20_completed_daily_bars", 0.68);
        Double dailyReturn = number(d.get("dailyReturnPct"));
        Double intraday = number(d.get("intradayFromOpenPct"));
        Boolean active = activity(d, 1.8, 1.5, 80.0);
        if (dailyReturn == null || intraday == null || active == null) {
            return skipped("R02", "required_derived_value_missing", 0.68);
        }
        boolean noProgress = dailyReturn >= -1.5 && dailyReturn <= 1.5 && Math.abs(intraday) <= 1.0;
        return pattern("R02", active && noProgress ? "triggered" : "not_triggered", 0.68,
                "活跃成交但价格推进有限", "价格放量突破当前整理区", dailyReturn, active, noProgress);
    }

    private Map<String, Object> r03(Map<String, Object> d, boolean commonEligible) {
        if (!commonEligible) return skipped("R03", "data_quality_gate_failed", 0.62);
        Double gap = number(d.get("gapOpenPct"));
        Double intraday = number(d.get("intradayFromOpenPct"));
        Double closeLocation = number(d.get("closeLocationValue"));
        Double upExcursion = number(d.get("upExcursionShare"));
        if (gap == null || intraday == null || closeLocation == null || upExcursion == null) {
            return skipped("R03", "required_derived_value_missing", 0.62);
        }
        boolean gapUp = gap >= 1.5;
        boolean faded = intraday < 0 && (intraday <= -1.0 || closeLocation <= 0.35);
        boolean excursion = upExcursion >= 0.25;
        return pattern("R03", gapUp && faded && excursion ? "triggered" : "not_triggered", 0.62,
                "高开后回落且日内承接不足", "重新站上开盘价并靠近日内高位", gap, gapUp, faded && excursion);
    }

    private Map<String, Object> r04(Map<String, Object> quote,
                                    List<Map<String, Object>> completedBars,
                                    Map<String, Object> d,
                                    boolean commonEligible) {
        if (!commonEligible) return skipped("R04", "data_quality_gate_failed", 0.66);
        if (completedBars.size() < 4) return skipped("R04", "need_4_completed_daily_bars", 0.66);
        Double currentUpper = number(d.get("upperShadowPctOfRange"));
        Double currentLocation = number(d.get("closeLocationValue"));
        if (currentUpper == null || currentLocation == null) return skipped("R04", "required_derived_value_missing", 0.66);
        int matches = currentUpper >= 0.45 && currentLocation <= 0.55 ? 1 : 0;
        for (Map<String, Object> bar : tail(completedBars, 4)) {
            if (longUpperShadow(number(bar.get("open")), number(bar.get("close")), number(bar.get("high")), number(bar.get("low")))) {
                matches++;
            }
        }
        boolean current = currentUpper >= 0.45 && currentLocation <= 0.55;
        Map<String, Object> pattern = pattern("R04", current && matches >= 2 ? "triggered" : "not_triggered", 0.66,
                "近5根K线长上影聚集", "后续收盘站上近期上影压力区", matches, current, matches >= 2);
        pattern.put("windowMatchCount", matches);
        return pattern;
    }

    private Boolean activity(Map<String, Object> d, double volumeThreshold, double amountThreshold, double turnoverThreshold) {
        Double volumeRatio = number(d.get("volumeRatio"));
        boolean postClose = bool(d.get("amountRatio20EligibleForTrigger"), false);
        if (!postClose) return volumeRatio == null ? null : volumeRatio >= volumeThreshold;
        Double amountRatio = number(d.get("amountRatio20"));
        Double turnoverPercentile = number(d.get("turnoverPercentile20"));
        if (volumeRatio == null && amountRatio == null && turnoverPercentile == null) return null;
        return (volumeRatio != null && volumeRatio >= volumeThreshold)
                || (amountRatio != null && amountRatio >= amountThreshold)
                || (turnoverPercentile != null && turnoverPercentile >= turnoverThreshold);
    }

    private Map<String, Object> pattern(String id,
                                        String status,
                                        double confidenceCap,
                                        String legalConclusion,
                                        String invalidatesWhen,
                                        Object primaryValue,
                                        boolean conditionOne,
                                        boolean conditionTwo) {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("patternId", id);
        pattern.put("status", status);
        pattern.put("riskReviewConfidenceCap", confidenceCap);
        pattern.put("legalConclusion", legalConclusion);
        pattern.put("invalidatesWhen", invalidatesWhen);
        pattern.put("primaryValue", primaryValue);
        List<String> checks = new ArrayList<>();
        checks.add("conditionOne=" + conditionOne);
        checks.add("conditionTwo=" + conditionTwo);
        pattern.put("conditions", checks);
        return pattern;
    }

    private Map<String, Object> skipped(String id, String reason, double cap) {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("patternId", id);
        pattern.put("status", "skipped");
        pattern.put("skipReason", reason);
        pattern.put("riskReviewConfidenceCap", cap);
        return pattern;
    }

    private void addSkippedPatterns(List<Map<String, Object>> patterns, String reason) {
        patterns.add(skipped("R01", reason, 0.70));
        patterns.add(skipped("R02", reason, 0.68));
        patterns.add(skipped("R03", reason, 0.62));
        patterns.add(skipped("R04", reason, 0.66));
    }

    private Map<String, Object> guidance() {
        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("dataLevel", "L0");
        guidance.put("legalConclusion", "not_determined");
        guidance.put("semanticBoundary", "L0 patterns cannot prove manipulation, coordinated accounts, wash trading, or the true intent of a main force.");
        guidance.put("normalExplanationCandidates", List.of("临近压力位的正常换手", "市场波动或获利回吐"));
        return guidance;
    }

    private List<Map<String, Object>> completedBars(List<Map<String, Object>> dailyBars, LocalDate quoteDate) {
        if (dailyBars == null || quoteDate == null) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> bar : dailyBars) {
            LocalDate date = parseDate(bar == null ? null : bar.get("date"));
            if (date != null && date.isBefore(quoteDate)) result.add(bar);
        }
        result.sort(Comparator.comparing(bar -> parseDate(bar.get("date"))));
        return result;
    }

    private String latestDate(List<Map<String, Object>> dailyBars) {
        if (dailyBars == null) return null;
        return dailyBars.stream().map(bar -> parseDate(bar.get("date"))).filter(date -> date != null)
                .max(Comparator.naturalOrder()).map(LocalDate::toString).orElse(null);
    }

    private boolean sourceDatesConsistent(List<Map<String, Object>> dailyBars, LocalDate quoteDate) {
        if (quoteDate == null) return false;
        if (dailyBars == null) return true;
        return dailyBars.stream().map(bar -> parseDate(bar.get("date"))).filter(date -> date != null)
                .noneMatch(date -> date.isAfter(quoteDate));
    }

    private String resolveMarketPhase(ZonedDateTime evaluationAt, ZonedDateTime quoteAt) {
        if (quoteAt == null || !quoteAt.toLocalDate().equals(evaluationAt.toLocalDate()) || !weekday(evaluationAt.getDayOfWeek())) {
            return "OUTSIDE_SESSION";
        }
        LocalTime time = evaluationAt.toLocalTime();
        boolean intraday = between(time, LocalTime.of(9, 30), LocalTime.of(11, 30))
                || between(time, LocalTime.of(13, 0), LocalTime.of(15, 0));
        if (intraday) return "INTRADAY";
        if (time.isAfter(LocalTime.of(15, 0)) && !quoteAt.toLocalTime().isBefore(LocalTime.of(14, 57))) return "POST_CLOSE";
        return "OUTSIDE_SESSION";
    }

    private String freshnessStatus(ZonedDateTime evaluationAt, ZonedDateTime quoteAt, String phase, long ageSeconds) {
        if (quoteAt == null) return "TIMESTAMP_MISSING";
        if (quoteAt.toLocalDate().isAfter(evaluationAt.toLocalDate()) || ageSeconds < -30) return "FUTURE_QUOTE";
        if (!quoteAt.toLocalDate().equals(evaluationAt.toLocalDate())) return "STALE_DATE";
        if ("INTRADAY".equals(phase)) return ageSeconds <= 300 ? "FRESH" : "STALE_AGE";
        if ("POST_CLOSE".equals(phase)) return "POST_CLOSE_VALID";
        return "OUTSIDE_REALTIME_SESSION";
    }

    private ZonedDateTime parseQuoteTime(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            if (Math.abs(value) < 10_000_000_000L) value *= 1000L;
            try {
                return Instant.ofEpochMilli(value).atZone(CHINA_ZONE);
            } catch (Exception ignored) {
                return null;
            }
        }
        String value = String.valueOf(raw).trim();
        if (!StringUtils.hasText(value)) return null;
        try {
            return ZonedDateTime.parse(value).withZoneSameInstant(CHINA_ZONE);
        } catch (DateTimeParseException ignored) {
            try {
                long epoch = Long.parseLong(value);
                return parseQuoteTime(epoch);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private boolean longUpperShadow(Double open, Double close, Double high, Double low) {
        Double range = validRange(high, low);
        if (open == null || close == null || range == null) return false;
        double upper = clamp01((high - Math.max(open, close)) / range);
        double location = clamp01((close - low) / range);
        return upper >= 0.45 && location <= 0.55;
    }

    private List<Map<String, Object>> tail(List<Map<String, Object>> values, int limit) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(values.subList(Math.max(0, values.size() - limit), values.size()));
    }

    private Double max(List<Map<String, Object>> bars, String key) {
        return bars.stream().map(bar -> number(bar.get(key))).filter(value -> value != null).max(Double::compareTo).orElse(null);
    }

    private Double average(List<Map<String, Object>> bars, String key) {
        double total = 0;
        int count = 0;
        for (Map<String, Object> bar : bars) {
            Double value = number(bar.get(key));
            if (value == null) return null;
            total += value;
            count++;
        }
        return count == 0 ? null : total / count;
    }

    private Double pct(Double value, Double base) {
        return value == null || base == null || base <= 0 ? null : (value / base - 1.0) * 100.0;
    }

    private Double validRange(Double high, Double low) {
        if (high == null || low == null || high <= low) return null;
        return high - low;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private Double number(Object raw) {
        if (raw == null) return null;
        double value;
        try {
            value = raw instanceof Number ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw));
        } catch (Exception ignored) {
            return null;
        }
        return Double.isFinite(value) ? value : null;
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        return "true".equalsIgnoreCase(String.valueOf(raw)) || "1".equals(String.valueOf(raw));
    }

    private String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }

    private LocalDate parseDate(Object raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(String.valueOf(raw).trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String normalizeMarket(String market) {
        return market == null ? "" : market.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isMainlandMarket(String market) {
        return "sh".equals(market) || "sz".equals(market) || "bj".equals(market);
    }

    private boolean activePattern(Map<String, Object> pattern) {
        return "triggered".equals(pattern.get("status")) || "watch".equals(pattern.get("status"));
    }

    private boolean weekday(DayOfWeek day) {
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private boolean between(LocalTime value, LocalTime start, LocalTime end) {
        return !value.isBefore(start) && !value.isAfter(end);
    }

    public static class RiskPatternResult {
        private final Map<String, Object> observations;
        private final Map<String, Object> guidance;
        private final ZonedDateTime quoteObservedAt;
        private final boolean active;

        RiskPatternResult(Map<String, Object> observations,
                          Map<String, Object> guidance,
                          ZonedDateTime quoteObservedAt,
                          boolean active) {
            this.observations = observations;
            this.guidance = guidance;
            this.quoteObservedAt = quoteObservedAt;
            this.active = active;
        }

        public Map<String, Object> getObservations() {
            return observations;
        }

        public Map<String, Object> getGuidance() {
            return guidance;
        }

        public ZonedDateTime getQuoteObservedAt() {
            return quoteObservedAt;
        }

        public boolean isActive() {
            return active;
        }

        public boolean isAvailable() {
            return !"SKIPPED_UNAVAILABLE".equals(observations.get("evaluationStatus"));
        }

        public boolean hasTriggeredPattern() {
            Object raw = observations.get("patterns");
            if (!(raw instanceof List)) return false;
            for (Object item : (List<?>) raw) {
                if (item instanceof Map && "triggered".equals(((Map<?, ?>) item).get("status"))) return true;
            }
            return false;
        }
    }
}
