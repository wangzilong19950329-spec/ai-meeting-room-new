package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.entity.StockWatchFactObservation;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class StockWatchPriceFailureResearchRule {

    public static final String RULE_VERSION = "RESEARCH_HIGH_POSITION_PRICE_FAILURE_V1";
    public static final int MIN_UNBLIND_SOURCE_DAYS = 120;
    public static final int MIN_UNBLIND_MATURE_SIGNALS = 30;
    public static final int MAX_CONTROLS = 3;
    public static final long CONTROL_TIME_WINDOW_MS = 10 * 60 * 1000L;

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> ALLOWED_SOURCE_QUALITIES = Arrays.asList("FRESH", "POST_CLOSE_VALID");

    private final String ruleSpecJson;
    private final String ruleSpecHash;

    public StockWatchPriceFailureResearchRule() {
        this.ruleSpecJson = JSON.toJSONString(buildRuleSpec());
        this.ruleSpecHash = sha256(ruleSpecJson);
    }

    public RuleAssessment evaluate(StockWatchFactObservation observation, long completedAtEpochMs) {
        if (observation == null || observation.getId() == null) {
            return RuleAssessment.ineligible("FACT_OBSERVATION_MISSING", Collections.emptyMap());
        }
        JSONObject snapshot;
        try {
            snapshot = JSON.parseObject(observation.getFactSnapshotJson());
        } catch (Exception invalid) {
            return RuleAssessment.ineligible("FACT_SNAPSHOT_INVALID_JSON", Collections.emptyMap());
        }
        String schema = snapshot == null ? "" : snapshot.getString("schema");
        if (StockWatchFactPersistenceService.QUOTE_ALERT_FACT_SCHEMA.equals(schema)) {
            return RuleAssessment.ineligible("FACT_SCHEMA_OUTSIDE_PRICE_FAILURE_SCOPE", Collections.emptyMap());
        }
        JSONObject quote = snapshot == null ? null : snapshot.getJSONObject("quote");
        JSONObject researchFeatures = snapshot == null
                ? null : snapshot.getJSONObject("priceFailureResearchFeatures");
        if (quote == null || researchFeatures == null) {
            return RuleAssessment.ineligible("FACT_REQUIRED_SECTION_MISSING", Collections.emptyMap());
        }

        Map<String, Object> features = featureSnapshot(observation, quote, researchFeatures, completedAtEpochMs);
        String market = normalize(observation.getMarket());
        if (!Arrays.asList("sh", "sz", "bj").contains(market)) {
            return RuleAssessment.ineligible("MARKET_OUTSIDE_RULE_SCOPE", features);
        }
        if (!ALLOWED_SOURCE_QUALITIES.contains(observation.getSourceTimeQuality())) {
            return RuleAssessment.ineligible("SOURCE_QUALITY_NOT_POINT_IN_TIME", features);
        }
        LocalDate sourceDate = parseDate(observation.getSourceDate());
        LocalDate completionDate = Instant.ofEpochMilli(completedAtEpochMs).atZone(CHINA_ZONE).toLocalDate();
        if (sourceDate == null || !sourceDate.equals(completionDate)) {
            return RuleAssessment.ineligible("RULE_COMPLETION_NOT_ON_SOURCE_DATE", features);
        }

        Double dailyReturn = number(researchFeatures.get("dailyReturnPct"));
        Double return5 = number(researchFeatures.get("return5Pct"));
        Double priceVsMa20 = number(researchFeatures.get("priceVsMa20Pct"));
        Double position20 = number(researchFeatures.get("closePosition20dPct"));
        Double turnover = number(quote.get("turnover"));
        Double volumeRatio = number(quote.get("volumeRatio"));
        Double gap = number(researchFeatures.get("gapOpenPct"));
        Double fromOpen = number(researchFeatures.get("intradayFromOpenPct"));
        Double closeLocation = number(researchFeatures.get("closeLocationValue"));
        Double previousDayReturn = number(researchFeatures.get("previousDayReturnPct"));
        Double price = number(quote.get("price"));
        if (dailyReturn == null || price == null || price <= 0) {
            return RuleAssessment.ineligible("CURRENT_PRICE_OR_RETURN_MISSING", features);
        }

        boolean highPosition = dailyReturn >= 7.0
                || (return5 != null && return5 >= 10.0)
                || (priceVsMa20 != null && priceVsMa20 >= 10.0)
                || (position20 != null && position20 >= 80.0);
        if (!highPosition && (return5 == null || priceVsMa20 == null || position20 == null)) {
            return RuleAssessment.ineligible("HIGH_POSITION_FEATURES_INCOMPLETE", features);
        }
        boolean active = (turnover != null && turnover >= 5.0) || (volumeRatio != null && volumeRatio >= 2.0);
        if (turnover == null && volumeRatio == null) {
            return RuleAssessment.ineligible("ACTIVITY_FEATURES_MISSING", features);
        }
        if (gap == null || fromOpen == null || closeLocation == null) {
            return RuleAssessment.ineligible("PRICE_FAILURE_FEATURES_MISSING", features);
        }

        boolean highGapFade = gap >= 1.5 && fromOpen <= -1.0;
        boolean weakRangeLocation = closeLocation <= 0.35;
        boolean priorSurgeGapDown = previousDayReturn != null
                && previousDayReturn >= 7.0 && gap <= -3.0 && dailyReturn < 0.0;
        if (!highGapFade && !weakRangeLocation && previousDayReturn == null) {
            return RuleAssessment.ineligible("PREVIOUS_DAY_RETURN_MISSING", features);
        }
        boolean notCollapsed = dailyReturn > -3.0;
        boolean priceShape = notCollapsed && highPosition && active
                && (highGapFade || weakRangeLocation || priorSurgeGapDown);
        String shapeCode = shapeCode(highGapFade, weakRangeLocation, priorSurgeGapDown);

        Double mainRatio = number(quote.get("mainInflowRatio"));
        Double superLarge = number(quote.get("superLargeInflow"));
        boolean flowDataComplete = mainRatio != null && superLarge != null;
        boolean strongOutflow = flowDataComplete && mainRatio <= -5.0 && superLarge < 0.0;
        String stratum = priceShape && strongOutflow
                ? "PRICE_SHAPE_WITH_STRONG_OUTFLOW" : "PRICE_SHAPE_ONLY";
        features.put("highPositionOrRise", highPosition);
        features.put("activeTurnover", active);
        features.put("currentNotCollapsed", notCollapsed);
        features.put("highGapFade", highGapFade);
        features.put("weakRangeLocation", weakRangeLocation);
        features.put("priorSurgeGapDown", priorSurgeGapDown);
        features.put("priceShapeTriggered", priceShape);
        features.put("flowDataComplete", flowDataComplete);
        features.put("strongOutflow", strongOutflow);
        features.put("shapeCode", shapeCode);
        features.put("stratum", stratum);
        return RuleAssessment.eligible(highPosition, active, notCollapsed, priceShape, flowDataComplete,
                strongOutflow, shapeCode, stratum, price, features);
    }

    public String getRuleSpecJson() {
        return ruleSpecJson;
    }

    public String getRuleSpecHash() {
        return ruleSpecHash;
    }

    private Map<String, Object> buildRuleSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("ruleVersion", RULE_VERSION);
        spec.put("purpose", "offline_prospective_t1_drawdown_research");
        spec.put("semanticBoundary", "observable_high_position_price_failure_risk_not_main_force_intent");
        spec.put("allowedInputs", Arrays.asList("stock_watch_fact_observation.quote",
                "stock_watch_fact_observation.priceFailureResearchFeatures"));
        spec.put("forbiddenInputs", Arrays.asList("DeepSeek", "raw_response", "mainForceIntent",
                "historical_analysis", "notification", "position", "trade_action"));
        spec.put("sourceQuality", ALLOWED_SOURCE_QUALITIES);
        spec.put("sameSourceDateCompletionRequired", true);
        spec.put("noHistoricalBackfill", true);

        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("currentReturnExclusiveMinimumPct", -3.0);
        thresholds.put("highPositionAny", mapOf(
                "dailyReturnMinimumPct", 7.0,
                "return5MinimumPct", 10.0,
                "priceVsMa20MinimumPct", 10.0,
                "closePosition20dMinimumPct", 80.0));
        thresholds.put("activeAny", mapOf("turnoverMinimumPct", 5.0, "volumeRatioMinimum", 2.0));
        thresholds.put("priceFailureAny", mapOf(
                "gapUpMinimumPct", 1.5,
                "fromOpenMaximumPct", -1.0,
                "closeLocationMaximum", 0.35,
                "previousDayReturnMinimumPct", 7.0,
                "gapDownMaximumPct", -3.0,
                "gapDownCurrentReturnMaximumPct", 0.0));
        thresholds.put("strongOutflow", mapOf("mainInflowRatioMaximumPct", -5.0,
                "superLargeInflowMaximum", 0.0));
        spec.put("thresholds", thresholds);
        spec.put("firstSignalPerSymbolSourceDate", true);
        spec.put("controlTimeWindowSeconds", CONTROL_TIME_WINDOW_MS / 1000L);
        spec.put("maxControlsPerSignal", MAX_CONTROLS);
        spec.put("controlEligibility", "source_within_symmetric_window_and_available_no_later_than_signal");
        spec.put("controlFinalization", "three_controls_or_first_observed_later_source_date");
        spec.put("entryProxy", mapOf(
                "intraday", "first_fact_price_strictly_after_rule_available_time",
                "crossDateOrPostClose", "next_observed_trading_date_open"));
        spec.put("primaryOutcome", "t1_max_drawdown_from_entry_proxy_lte_minus_5pct");
        spec.put("secondaryOutcomes", Arrays.asList("t1_close_return_from_entry_proxy",
                "t1_open_vs_signal_price", "t1_max_upside_from_entry_proxy"));
        spec.put("outcomeMaturity", "sample_specific_t1_post_close_snapshot_required");
        spec.put("postCloseRevisionPolicy", "latest_arrival_at_latest_source_timestamp");
        spec.put("unblindMinimumSourceDates", MIN_UNBLIND_SOURCE_DAYS);
        spec.put("unblindMinimumMatureSignals", MIN_UNBLIND_MATURE_SIGNALS);
        spec.put("unblindSourceDateDefinition", "distinct_source_dates_with_at_least_one_eligible_assessment");
        spec.put("priorThresholdSearch", mapOf(
                "closeLocationMaximum", Arrays.asList(0.30, 0.35, 0.40),
                "previousDayReturnMinimumPct", Arrays.asList(5.0, 7.0, 9.0),
                "gapDownMaximumPct", Arrays.asList(-2.0, -3.0, -4.0),
                "mainInflowRatioMaximumPct", Arrays.asList(-3.0, -5.0, -7.0)));
        spec.put("excludedBranch", "LIMIT_UP_SUPPLY_SEPARATE_requires_a_future_point_in_time_definition");
        return spec;
    }

    private Map<String, Object> featureSnapshot(StockWatchFactObservation observation,
                                                JSONObject quote,
                                                JSONObject researchFeatures,
                                                long completedAtEpochMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "stock-watch-price-failure-assessment-v1");
        result.put("ruleVersion", RULE_VERSION);
        result.put("ruleSpecHash", ruleSpecHash);
        result.put("observationId", observation.getId());
        result.put("observationKey", observation.getObservationKey());
        result.put("factHash", observation.getFactHash());
        result.put("sourceTimeEpochMs", observation.getSourceTimeEpochMs());
        result.put("sourceDate", observation.getSourceDate());
        result.put("sourceTimeQuality", observation.getSourceTimeQuality());
        result.put("ruleCompletedAtEpochMs", completedAtEpochMs);
        result.put("price", number(quote.get("price")));
        result.put("dailyReturnPct", number(researchFeatures.get("dailyReturnPct")));
        result.put("previousDayReturnPct", number(researchFeatures.get("previousDayReturnPct")));
        result.put("return5Pct", number(researchFeatures.get("return5Pct")));
        result.put("priceVsMa20Pct", number(researchFeatures.get("priceVsMa20Pct")));
        result.put("closePosition20dPct", number(researchFeatures.get("closePosition20dPct")));
        result.put("volatility20Pct", number(researchFeatures.get("volatility20Pct")));
        result.put("gapOpenPct", number(researchFeatures.get("gapOpenPct")));
        result.put("intradayFromOpenPct", number(researchFeatures.get("intradayFromOpenPct")));
        result.put("closeLocationValue", number(researchFeatures.get("closeLocationValue")));
        result.put("turnoverPct", number(quote.get("turnover")));
        result.put("volumeRatio", number(quote.get("volumeRatio")));
        result.put("amount", number(quote.get("amount")));
        result.put("mainInflowRatioPct", number(quote.get("mainInflowRatio")));
        result.put("superLargeInflow", number(quote.get("superLargeInflow")));
        return result;
    }

    private String shapeCode(boolean highGapFade, boolean weakRangeLocation, boolean priorSurgeGapDown) {
        if (highGapFade) return "HIGH_GAP_FADE";
        if (weakRangeLocation) return "WEAK_RANGE_LOCATION";
        if (priorSurgeGapDown) return "PRIOR_SURGE_GAP_DOWN";
        return "NONE";
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private Double number(Object value) {
        if (value == null) return null;
        try {
            double number = value instanceof Number ? ((Number) value).doubleValue()
                    : Double.parseDouble(String.valueOf(value));
            return Double.isFinite(number) ? number : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("price_failure_rule_hash_failed", e);
        }
    }

    public static class RuleAssessment {
        private final boolean eligible;
        private final boolean highPosition;
        private final boolean activeTurnover;
        private final boolean currentNotCollapsed;
        private final boolean priceShapeTriggered;
        private final boolean flowDataComplete;
        private final boolean strongOutflow;
        private final String shapeCode;
        private final String stratum;
        private final Double signalPrice;
        private final String exclusionReason;
        private final Map<String, Object> features;

        private RuleAssessment(boolean eligible,
                               boolean highPosition,
                               boolean activeTurnover,
                               boolean currentNotCollapsed,
                               boolean priceShapeTriggered,
                               boolean flowDataComplete,
                               boolean strongOutflow,
                               String shapeCode,
                               String stratum,
                               Double signalPrice,
                               String exclusionReason,
                               Map<String, Object> features) {
            this.eligible = eligible;
            this.highPosition = highPosition;
            this.activeTurnover = activeTurnover;
            this.currentNotCollapsed = currentNotCollapsed;
            this.priceShapeTriggered = priceShapeTriggered;
            this.flowDataComplete = flowDataComplete;
            this.strongOutflow = strongOutflow;
            this.shapeCode = shapeCode;
            this.stratum = stratum;
            this.signalPrice = signalPrice;
            this.exclusionReason = exclusionReason;
            this.features = features;
        }

        static RuleAssessment eligible(boolean highPosition,
                                       boolean activeTurnover,
                                       boolean currentNotCollapsed,
                                       boolean priceShapeTriggered,
                                       boolean flowDataComplete,
                                       boolean strongOutflow,
                                       String shapeCode,
                                       String stratum,
                                       Double signalPrice,
                                       Map<String, Object> features) {
            return new RuleAssessment(true, highPosition, activeTurnover, currentNotCollapsed,
                    priceShapeTriggered, flowDataComplete, strongOutflow, shapeCode, stratum,
                    signalPrice, null, features);
        }

        static RuleAssessment ineligible(String reason, Map<String, Object> features) {
            return new RuleAssessment(false, false, false, false, false, false, false,
                    "NONE", "INELIGIBLE", null, reason, features);
        }

        public boolean isEligible() { return eligible; }
        public boolean isHighPosition() { return highPosition; }
        public boolean isActiveTurnover() { return activeTurnover; }
        public boolean isCurrentNotCollapsed() { return currentNotCollapsed; }
        public boolean isPriceShapeTriggered() { return priceShapeTriggered; }
        public boolean isFlowDataComplete() { return flowDataComplete; }
        public boolean isStrongOutflow() { return strongOutflow; }
        public String getShapeCode() { return shapeCode; }
        public String getStratum() { return stratum; }
        public Double getSignalPrice() { return signalPrice; }
        public String getExclusionReason() { return exclusionReason; }
        public Map<String, Object> getFeatures() { return features; }
        public String featuresJson() { return JSON.toJSONString(features); }
    }
}
