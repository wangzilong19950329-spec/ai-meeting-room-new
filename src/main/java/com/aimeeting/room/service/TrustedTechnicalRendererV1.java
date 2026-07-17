package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic V6 technical view. Model-authored narrative is never an input. */
public final class TrustedTechnicalRendererV1 {

    public static final int RENDER_VERSION = 1;
    public static final String EVIDENCE_QUALITY = "SERVER_TECHNICAL_V1";
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private TrustedTechnicalRendererV1() {
    }

    public static boolean isTrustedV6(StockWatchTechnicalAnalysis record) {
        return record != null && Integer.valueOf(6).equals(record.getResponseContractVersion());
    }

    public static RenderedTechnical render(StockWatchTechnicalAnalysis record) {
        if (record == null) {
            return render((Map<String, Object>) null, "DAY", null, "");
        }
        LocalDate analysisDate = toLocalDate(record.getAnalysisDate());
        return render(record.getEvidenceSignalJson(), record.getPeriodType(), analysisDate, record.getRunSlot());
    }

    public static RenderedTechnical render(String evidenceSignalJson,
                                           String expectedPeriodType,
                                           LocalDate analysisDate,
                                           String observationSlot) {
        return render(parseObject(evidenceSignalJson), expectedPeriodType, analysisDate, observationSlot);
    }

    public static RenderedTechnical render(Map<String, Object> evidenceSignals,
                                           String expectedPeriodType,
                                           LocalDate analysisDate,
                                           String observationSlot) {
        JSONObject signals = evidenceSignals instanceof JSONObject
                ? (JSONObject) evidenceSignals
                : (evidenceSignals == null ? null : new JSONObject(evidenceSignals));
        String expectedPeriod = periodValue(expectedPeriodType);
        String periodType = validPeriod(expectedPeriod);
        List<String> limitations = new ArrayList<>();
        List<String> validationErrors = new ArrayList<>();
        if (signals == null) {
            validationErrors.add("冻结技术证据不存在。");
            String digest = inputDigest(null, expectedPeriod, "", analysisDate, observationSlot,
                    null, limitations, validationErrors);
            return insufficient(periodType, analysisDate, observationSlot, null,
                    combinedIssues(validationErrors, limitations), digest);
        }
        if (periodType == null) validationErrors.add("分析记录周期必须是 DAY、WEEK 或 MONTH。");
        if (!"stock_watch_evidence_signals_v2".equals(signals.getString("schema"))) {
            validationErrors.add("冻结技术证据版本不受支持。");
        }
        Double atomCatalogVersion = finite(signals.get("atomCatalogVersion"));
        if (atomCatalogVersion == null || Double.compare(atomCatalogVersion, 2d) != 0) {
            validationErrors.add("技术原子目录版本不受支持。");
        }
        String signalPeriodValue = periodValue(signals.getString("periodType"));
        String signalPeriod = validPeriod(signalPeriodValue);
        if (signalPeriod == null) {
            validationErrors.add("冻结证据周期必须是 DAY、WEEK 或 MONTH。");
        } else if (periodType != null && !periodType.equals(signalPeriod)) {
            validationErrors.add("冻结证据周期与分析记录不一致。");
        }
        Object rawSourceTimestamp = signals.get("sourceTimestamp");
        Object sourceTimestamp = scalarTimestamp(rawSourceTimestamp);
        if (signals.containsKey("sourceTimestamp") && sourceTimestamp == null) {
            validationErrors.add("冻结源时间戳格式无效。");
        }

        JSONObject priceVolume = section(signals, "priceVolumeSignal", validationErrors);
        JSONObject position = section(signals, "positionSignal", validationErrors);
        JSONObject fundFlow = section(signals, "fundFlowEvidence", validationErrors);
        validateNumericFields(priceVolume, "priceVolumeSignal", validationErrors,
                "changePercent", "volumeRatio", "turnoverPercent");
        validateNumericFields(position, "positionSignal", validationErrors,
                "return5Pct", "return20Pct", "closeYuan", "ma20", "macdDif", "macdDea",
                "macdHistogram", "rsi14");
        validateNumericFields(fundFlow, "fundFlowEvidence", validationErrors,
                "anchorMainInflowYuan", "anchorMainInflowRatioPercent", "latestMainInflowYuan",
                "tail15MainDeltaYuan");
        validatePositive(position, "positionSignal", validationErrors, "closeYuan", "ma20");
        validateActivity(priceVolume, validationErrors);
        appendLimitations(limitations, fundFlow, "fundFlowEvidence", validationErrors);
        appendLimitations(limitations, priceVolume, "priceVolumeSignal", validationErrors);
        appendLimitations(limitations, position, "positionSignal", validationErrors);
        String digest = inputDigest(signals, expectedPeriod, signalPeriodValue, analysisDate, observationSlot,
                rawSourceTimestamp, limitations, validationErrors);
        if (!validationErrors.isEmpty()) {
            return insufficient(periodType, analysisDate, observationSlot, sourceTimestamp,
                    combinedIssues(validationErrors, limitations), digest);
        }

        List<FamilyVote> families = new ArrayList<>();
        families.add(returnVote(periodType, priceVolume, position));
        families.add(maVote(position));
        families.add(macdVote(position));

        int usable = 0;
        int bullish = 0;
        int bearish = 0;
        List<String> evidence = new ArrayList<>();
        for (FamilyVote family : families) {
            if (!family.usable) continue;
            usable++;
            if (family.vote > 0) bullish++;
            if (family.vote < 0) bearish++;
            evidence.add(family.evidence);
        }
        appendContextEvidence(evidence, priceVolume, position, fundFlow);
        for (String limitation : limitations) {
            evidence.add("数据限制：" + limitation);
        }

        Direction direction;
        double confidence;
        if (usable < 2) {
            direction = Direction.UNKNOWN;
            confidence = 0d;
        } else if (bullish > 0 && bearish > 0) {
            direction = Direction.MIXED;
            confidence = 0.45d;
        } else if (bullish >= 2) {
            direction = Direction.BULLISH;
            confidence = bullish >= 3 ? 0.65d : 0.55d;
        } else if (bearish >= 2) {
            direction = Direction.BEARISH;
            confidence = bearish >= 3 ? 0.65d : 0.55d;
        } else {
            direction = Direction.NEUTRAL;
            confidence = 0.45d;
        }

        String windowLabel = windowLabel(periodType);
        String title = windowLabel + direction.titleSuffix;
        String conclusion = conclusion(windowLabel, direction, usable, bullish, bearish, limitations);
        return new RenderedTechnical(direction.code, direction.stance, direction.tone, confidence, title,
                conclusion, evidence, limitations, periodType, analysisDate, text(observationSlot),
                sourceTimestamp, digest);
    }

    public static RenderedTechnical applyTrustedView(StockWatchTechnicalAnalysis record) {
        if (!isTrustedV6(record)) return null;
        RenderedTechnical rendered = render(record);
        record.setStance(rendered.getStance());
        record.setTone(rendered.getTone());
        record.setConfidence(rendered.getConfidence());
        record.setTitle(rendered.getTitle());
        record.setConclusion(rendered.getConclusion());
        record.setEvidenceJson(JSON.toJSONString(rendered.getEvidence()));
        return rendered;
    }

    private static FamilyVote returnVote(String periodType, JSONObject priceVolume, JSONObject position) {
        Double value;
        double threshold;
        String label;
        if ("WEEK".equals(periodType)) {
            value = finite(position == null ? null : position.get("return5Pct"));
            threshold = 3d;
            label = "近5个交易日收益";
        } else if ("MONTH".equals(periodType)) {
            value = finite(position == null ? null : position.get("return20Pct"));
            threshold = 5d;
            label = "近20个交易日收益";
        } else {
            value = finite(priceVolume == null ? null : priceVolume.get("changePercent"));
            threshold = 1d;
            label = "当日涨跌幅";
        }
        if (value == null) return FamilyVote.unusable();
        int vote = value >= threshold ? 1 : (value <= -threshold ? -1 : 0);
        return new FamilyVote(true, vote, label + " " + percent(value) + directionText(vote));
    }

    private static FamilyVote maVote(JSONObject position) {
        Double close = finite(position == null ? null : position.get("closeYuan"));
        Double ma20 = finite(position == null ? null : position.get("ma20"));
        if (close == null || ma20 == null || close <= 0 || ma20 <= 0) return FamilyVote.unusable();
        int vote = Double.compare(close, ma20);
        String relation = vote > 0 ? "高于" : (vote < 0 ? "低于" : "等于");
        return new FamilyVote(true, Integer.compare(vote, 0),
                "收盘价 " + decimal(close) + " 元" + relation + " MA20 " + decimal(ma20) + " 元"
                        + directionText(Integer.compare(vote, 0)));
    }

    private static FamilyVote macdVote(JSONObject position) {
        Double dif = finite(position == null ? null : position.get("macdDif"));
        Double dea = finite(position == null ? null : position.get("macdDea"));
        Double histogram = finite(position == null ? null : position.get("macdHistogram"));
        if (dif == null || dea == null || histogram == null) return FamilyVote.unusable();
        int vote = dif > dea && histogram > 0 ? 1 : (dif < dea && histogram < 0 ? -1 : 0);
        return new FamilyVote(true, vote,
                "MACD DIF=" + decimal(dif) + "、DEA=" + decimal(dea) + "、柱值=" + decimal(histogram)
                        + directionText(vote));
    }

    private static void appendContextEvidence(List<String> evidence,
                                              JSONObject priceVolume,
                                              JSONObject position,
                                              JSONObject fundFlow) {
        Double rsi = finite(position == null ? null : position.get("rsi14"));
        if (rsi != null) {
            evidence.add("RSI14=" + decimal(rsi) + "，仅作超买超卖背景，不参与方向判定");
        }
        Double volumeRatio = finite(priceVolume == null ? null : priceVolume.get("volumeRatio"));
        Double turnover = finite(priceVolume == null ? null : priceVolume.get("turnoverPercent"));
        String activity = allowlisted(priceVolume == null ? null : priceVolume.getString("activity"),
                "LOW", "MEDIUM", "HIGH");
        List<String> volumeParts = new ArrayList<>();
        if (volumeRatio != null) volumeParts.add("量比 " + decimal(volumeRatio));
        if (turnover != null) volumeParts.add("换手率 " + percent(turnover));
        if (!activity.isEmpty()) volumeParts.add("活跃度 " + activity);
        if (!volumeParts.isEmpty()) {
            evidence.add("量能背景：" + String.join("，", volumeParts) + "（不参与方向判定）");
        }
        Double inflow = firstFinite(fundFlow, "anchorMainInflowYuan", "latestMainInflowYuan", "tail15MainDeltaYuan");
        Double ratio = finite(fundFlow == null ? null : fundFlow.get("anchorMainInflowRatioPercent"));
        if (inflow != null || ratio != null) {
            List<String> flowParts = new ArrayList<>();
            if (inflow != null) flowParts.add("主力净流" + (inflow >= 0 ? "入 " : "出 ") + decimal(Math.abs(inflow) / 10_000d) + " 万元");
            if (ratio != null) flowParts.add("占比 " + percent(ratio));
            evidence.add("资金流背景：" + String.join("，", flowParts) + "（不参与技术方向判定）");
        }
    }

    private static void appendLimitations(List<String> target,
                                          JSONObject section,
                                          String sectionName,
                                          List<String> validationErrors) {
        if (section == null) return;
        if (!section.containsKey("limitations")) return;
        Object raw = section.get("limitations");
        if (!(raw instanceof Iterable)) {
            validationErrors.add(sectionName + ".limitations 必须是数组。");
            return;
        }
        for (Object value : (Iterable<?>) raw) {
            if (!(value instanceof String)) {
                validationErrors.add(sectionName + ".limitations 只能包含字符串。");
                continue;
            }
            String limitation = text(value);
            if (!limitation.isEmpty() && !target.contains(limitation)) target.add(limitation);
        }
    }

    private static List<String> combinedIssues(List<String> validationErrors, List<String> limitations) {
        List<String> result = new ArrayList<>();
        if (validationErrors != null) {
            for (String value : validationErrors) {
                if (!result.contains(value)) result.add(value);
            }
        }
        if (limitations != null) {
            for (String value : limitations) {
                if (!result.contains(value)) result.add(value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject section(JSONObject root, String key, List<String> validationErrors) {
        if (root == null || !root.containsKey(key)) return null;
        Object raw = root.get(key);
        if (raw == null) return null;
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw instanceof Map) return new JSONObject((Map<String, Object>) raw);
        validationErrors.add(key + " 必须是 JSON 对象。");
        return null;
    }

    private static void validateNumericFields(JSONObject source,
                                              String sectionName,
                                              List<String> validationErrors,
                                              String... keys) {
        if (source == null) return;
        for (String key : keys) {
            if (source.containsKey(key) && finite(source.get(key)) == null) {
                validationErrors.add(sectionName + "." + key + " 必须是有限数值。");
            }
        }
    }

    private static void validatePositive(JSONObject source,
                                         String sectionName,
                                         List<String> validationErrors,
                                         String... keys) {
        if (source == null) return;
        for (String key : keys) {
            if (!source.containsKey(key)) continue;
            Double value = finite(source.get(key));
            if (value != null && value <= 0d) {
                validationErrors.add(sectionName + "." + key + " 必须大于 0。");
            }
        }
    }

    private static void validateActivity(JSONObject priceVolume, List<String> validationErrors) {
        if (priceVolume == null || !priceVolume.containsKey("activity")) return;
        String value = allowlisted(priceVolume.getString("activity"), "LOW", "MEDIUM", "HIGH");
        if (value.isEmpty()) validationErrors.add("priceVolumeSignal.activity 取值不受支持。");
    }

    private static RenderedTechnical insufficient(String periodType,
                                                  LocalDate analysisDate,
                                                  String observationSlot,
                                                  Object sourceTimestamp,
                                                  List<String> limitations,
                                                  String digest) {
        String normalizedPeriod = periodType == null ? "UNKNOWN" : periodType;
        List<String> evidence = new ArrayList<>();
        for (String limitation : limitations) evidence.add("数据限制：" + limitation);
        return new RenderedTechnical(Direction.UNKNOWN.code, Direction.UNKNOWN.stance, Direction.UNKNOWN.tone,
                0d, windowLabel(normalizedPeriod) + Direction.UNKNOWN.titleSuffix,
                "冻结技术证据不足，无法形成可信方向结论。", evidence, new ArrayList<>(limitations),
                normalizedPeriod, analysisDate, text(observationSlot), sourceTimestamp,
                digest);
    }

    private static String conclusion(String windowLabel,
                                     Direction direction,
                                     int usable,
                                     int bullish,
                                     int bearish,
                                     List<String> limitations) {
        String summary;
        if (direction == Direction.BULLISH || direction == Direction.BEARISH) {
            int aligned = direction == Direction.BULLISH ? bullish : bearish;
            summary = windowLabel + "：" + aligned + " 个方向族一致" + (direction == Direction.BULLISH ? "偏多" : "偏空") + "。";
        } else if (direction == Direction.MIXED) {
            summary = windowLabel + "：方向证据相互冲突，当前按混合结构处理。";
        } else if (direction == Direction.NEUTRAL) {
            summary = windowLabel + "：有效方向证据不足以形成一致趋势，当前按中性结构处理。";
        } else {
            summary = windowLabel + "：仅有 " + usable + " 个可用方向族，无法形成可信方向结论。";
        }
        if (!limitations.isEmpty()) summary += " 存在 " + limitations.size() + " 项数据限制。";
        return summary + " 该结论由服务器固定规则生成，不包含模型自由文本。";
    }

    private static String inputDigest(JSONObject signals,
                                      String expectedPeriodType,
                                      String signalPeriodType,
                                      LocalDate analysisDate,
                                      String observationSlot,
                                      Object sourceTimestamp,
                                      List<String> limitations,
                                      List<String> validationErrors) {
        JSONObject canonical = new JSONObject(true);
        canonical.put("technicalRenderVersion", RENDER_VERSION);
        canonical.put("schema", signals == null ? null : canonicalValue(signals.get("schema")));
        canonical.put("atomCatalogVersion", signals == null ? null : canonicalValue(signals.get("atomCatalogVersion")));
        canonical.put("expectedPeriodType", expectedPeriodType);
        canonical.put("signalPeriodType", signalPeriodType);
        canonical.put("analysisDate", analysisDate == null ? null : analysisDate.toString());
        canonical.put("observationSlot", text(observationSlot));
        canonical.put("sourceTimestamp", canonicalValue(sourceTimestamp));
        canonical.put("priceVolumeSignal", canonicalSignal(signals == null ? null : signals.get("priceVolumeSignal"),
                "changePercent", "volumeRatio", "turnoverPercent", "activity"));
        canonical.put("positionSignal", canonicalSignal(signals == null ? null : signals.get("positionSignal"),
                "return5Pct", "return20Pct", "closeYuan", "ma20", "macdDif", "macdDea",
                "macdHistogram", "rsi14"));
        canonical.put("fundFlowEvidence", canonicalSignal(signals == null ? null : signals.get("fundFlowEvidence"),
                "anchorMainInflowYuan", "anchorMainInflowRatioPercent", "latestMainInflowYuan",
                "tail15MainDeltaYuan"));
        canonical.put("limitations", new ArrayList<>(limitations));
        canonical.put("validationErrors", new ArrayList<>(validationErrors));
        return sha256(JSON.toJSONString(canonical));
    }

    private static JSONObject allowlistedSignal(JSONObject source, String... keys) {
        JSONObject result = new JSONObject(true);
        if (source == null) return result;
        for (String key : keys) {
            if (source.containsKey(key)) result.put(key, canonicalValue(source.get(key)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object canonicalSignal(Object raw, String... keys) {
        if (raw == null) return new JSONObject(true);
        if (raw instanceof JSONObject) return allowlistedSignal((JSONObject) raw, keys);
        if (raw instanceof Map) return allowlistedSignal(new JSONObject((Map<String, Object>) raw), keys);
        JSONObject invalid = new JSONObject(true);
        invalid.put("_invalid", canonicalValue(raw));
        return invalid;
    }

    private static Object canonicalValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            Double number = finite(value);
            return number == null ? String.valueOf(value) : number;
        }
        if (value instanceof String || value instanceof Boolean) return String.valueOf(value).trim();
        return value.getClass().getSimpleName() + ":" + String.valueOf(value);
    }

    private static Object scalarTimestamp(Object raw) {
        if (raw instanceof Number) return finite(raw);
        if (raw instanceof String) {
            String value = text(raw);
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private static Double firstFinite(JSONObject source, String... keys) {
        if (source == null) return null;
        for (String key : keys) {
            Double value = finite(source.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Double finite(Object raw) {
        if (raw == null) return null;
        try {
            double value = raw instanceof Number ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw));
            return Double.isFinite(value) ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject parseObject(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return JSON.parseObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDate toLocalDate(Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Date) return ((java.sql.Date) date).toLocalDate();
        return Instant.ofEpochMilli(date.getTime()).atZone(CHINA_ZONE).toLocalDate();
    }

    private static String periodValue(String raw) {
        return text(raw).toUpperCase(Locale.ROOT);
    }

    private static String validPeriod(String raw) {
        String value = text(raw).toUpperCase(Locale.ROOT);
        return "DAY".equals(value) || "WEEK".equals(value) || "MONTH".equals(value) ? value : null;
    }

    private static String windowLabel(String periodType) {
        if ("WEEK".equals(periodType)) return "近5个交易日";
        if ("MONTH".equals(periodType)) return "近20个交易日";
        if ("DAY".equals(periodType)) return "当日";
        return "";
    }

    private static String directionText(int vote) {
        return vote > 0 ? "（偏多）" : (vote < 0 ? "（偏空）" : "（中性）");
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(double value) {
        return decimal(value) + "%";
    }

    private static String allowlisted(String raw, String... allowed) {
        String value = text(raw).toUpperCase(Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        return "";
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("technical_render_digest_failed", e);
        }
    }

    private enum Direction {
        BULLISH("BULLISH", "看涨", "green", "技术信号偏多"),
        BEARISH("BEARISH", "看跌", "red", "技术信号偏空"),
        MIXED("MIXED", "中性", "blue", "技术信号分化"),
        NEUTRAL("NEUTRAL", "中性", "blue", "技术信号中性"),
        UNKNOWN("UNKNOWN", "待确认", "blue", "技术证据不足");

        private final String code;
        private final String stance;
        private final String tone;
        private final String titleSuffix;

        Direction(String code, String stance, String tone, String titleSuffix) {
            this.code = code;
            this.stance = stance;
            this.tone = tone;
            this.titleSuffix = titleSuffix;
        }
    }

    private static final class FamilyVote {
        private final boolean usable;
        private final int vote;
        private final String evidence;

        private FamilyVote(boolean usable, int vote, String evidence) {
            this.usable = usable;
            this.vote = vote;
            this.evidence = evidence;
        }

        private static FamilyVote unusable() {
            return new FamilyVote(false, 0, "");
        }
    }

    public static final class RenderedTechnical {
        private final String direction;
        private final String stance;
        private final String tone;
        private final double confidence;
        private final String title;
        private final String conclusion;
        private final List<String> evidence;
        private final List<String> limitations;
        private final String periodType;
        private final LocalDate asOfDate;
        private final String observationSlot;
        private final Object sourceTimestamp;
        private final String inputDigest;

        private RenderedTechnical(String direction,
                                  String stance,
                                  String tone,
                                  double confidence,
                                  String title,
                                  String conclusion,
                                  List<String> evidence,
                                  List<String> limitations,
                                  String periodType,
                                  LocalDate asOfDate,
                                  String observationSlot,
                                  Object sourceTimestamp,
                                  String inputDigest) {
            this.direction = direction;
            this.stance = stance;
            this.tone = tone;
            this.confidence = confidence;
            this.title = title;
            this.conclusion = conclusion;
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
            this.limitations = Collections.unmodifiableList(new ArrayList<>(limitations));
            this.periodType = periodType;
            this.asOfDate = asOfDate;
            this.observationSlot = observationSlot;
            this.sourceTimestamp = sourceTimestamp;
            this.inputDigest = inputDigest;
        }

        public String getDirection() { return direction; }
        public String getStance() { return stance; }
        public String getTone() { return tone; }
        public double getConfidence() { return confidence; }
        public String getTitle() { return title; }
        public String getConclusion() { return conclusion; }
        public List<String> getEvidence() { return evidence; }
        public List<String> getLimitations() { return limitations; }

        public Map<String, Object> metadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("technicalRenderVersion", RENDER_VERSION);
            metadata.put("technicalEvidenceQuality", EVIDENCE_QUALITY);
            metadata.put("technicalDirection", direction);
            metadata.put("technicalPeriodType", periodType);
            metadata.put("technicalAsOfDate", asOfDate == null ? null : asOfDate.toString());
            metadata.put("technicalObservationSlot", observationSlot);
            if (sourceTimestamp != null) metadata.put("technicalSourceTimestamp", sourceTimestamp);
            metadata.put("technicalInputDigest", inputDigest);
            return metadata;
        }
    }
}
