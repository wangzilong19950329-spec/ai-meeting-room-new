package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.service.StockWatchRiskPatternService.RiskPatternResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class StockWatchRiskPatternSummaryRenderer {

    private static final String TITLE = "价量风险结构识别（套路识别）";
    private static final String BOUNDARY = "L0 只识别可观察的价量风险结构，不能证明操纵、对倒、真实主力身份或违法意图。";
    private static final Map<String, PatternMeta> PATTERN_META = patternMeta();

    public Map<String, Object> summarize(RiskPatternResult result, Map<String, Object> factReceipt) {
        if (result == null) return null;
        return summarize(result.getObservations(), result.getGuidance(), factReceipt);
    }

    public Map<String, Object> summarize(Object rawObservations, Object rawGuidance, Map<String, Object> factReceipt) {
        Map<String, Object> observations = map(rawObservations);
        if (observations.isEmpty()) return null;
        Map<String, Object> guidance = map(rawGuidance);
        Map<String, Object> summary = new LinkedHashMap<>();
        String status = text(observations.get("evaluationStatus"), "UNKNOWN");
        List<Map<String, Object>> patterns = compactPatterns(observations.get("patterns"));
        List<String> triggeredIds = idsByStatus(patterns, "triggered");
        List<String> watchIds = idsByStatus(patterns, "watch");
        List<String> skippedReasons = skippedReasons(patterns);
        Map<String, Object> dataQuality = dataQuality(observations, factReceipt);

        summary.put("enabled", true);
        summary.put("evaluated", !"SKIPPED_UNAVAILABLE".equals(status));
        summary.put("dataLevel", text(observations.get("dataLevel"), "L0"));
        summary.put("title", TITLE);
        summary.put("status", status);
        summary.put("statusLabel", statusLabel(status));
        summary.put("activePatternIds", triggeredIds);
        summary.put("watchPatternIds", watchIds);
        summary.put("skippedReasons", skippedReasons);
        summary.put("rawSkippedReasons", rawSkippedReasons(patterns));
        summary.put("patterns", patterns);
        summary.put("dataQuality", dataQuality);
        summary.put("semanticBoundary", semanticBoundary(guidance.get("semanticBoundary")));
        summary.put("conclusionText", conclusionText(status, patterns));
        summary.put("valueText", valueText(status, patterns));
        summary.put("nextWatch", nextWatchText(status, patterns));
        summary.put("dataLimitText", dataLimitText(status, dataQuality, skippedReasons));
        summary.put("judgementInvariant", "deterministic_projection_only; does_not_affect_trigger_unless_rule_requires_RISK_PATTERN_REQUIRED");
        putFactPointer(summary, factReceipt);
        return summary;
    }

    public Map<String, Object> summarizeFromSnapshotJson(String dataSnapshotJson) {
        if (!StringUtils.hasText(dataSnapshotJson)) return null;
        try {
            JSONObject snapshot = JSON.parseObject(dataSnapshotJson);
            if (snapshot == null) return null;
            return summarize(
                    snapshot.get("riskPatternObservations"),
                    snapshot.get("riskPatternGuidance"),
                    map(snapshot.get("factObservationReceipt"))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    public String renderMailBlock(Map<String, Object> summary, String indent) {
        if (summary == null || summary.isEmpty()) return "";
        String prefix = indent == null ? "" : indent;
        StringBuilder body = new StringBuilder();
        body.append(prefix).append("状态：").append(text(summary.get("statusLabel"), "--")).append("\n");
        body.append(prefix).append("本轮结论：").append(text(summary.get("conclusionText"), "--")).append("\n");
        body.append(prefix).append("为什么有用：").append(text(summary.get("valueText"), "--")).append("\n");
        if (StringUtils.hasText(text(summary.get("nextWatch"), ""))) {
            body.append(prefix).append("下一步观察：").append(text(summary.get("nextWatch"), "")).append("\n");
        }
        if (StringUtils.hasText(text(summary.get("dataLimitText"), ""))) {
            body.append(prefix).append("数据限制：").append(text(summary.get("dataLimitText"), "")).append("\n");
        }
        body.append(prefix).append("边界：").append(text(summary.get("semanticBoundary"), BOUNDARY)).append("\n");
        Map<String, Object> dataQuality = map(summary.get("dataQuality"));
        if (!dataQuality.isEmpty()) {
            body.append(prefix).append("数据质量：")
                    .append(text(dataQuality.get("sourceTimeQualityLabel"), text(dataQuality.get("sourceTimeQuality"), "--")))
                    .append("；基准K线 ").append(text(dataQuality.get("completedBaselineBarCount"), "--"))
                    .append(" 根\n");
        }
        List<Map<String, Object>> active = activePatterns(summary.get("patterns"));
        if (!active.isEmpty()) {
            body.append(prefix).append("命中/关注结构：\n");
            for (Map<String, Object> pattern : active) {
                body.append(prefix).append("  - ")
                        .append(text(pattern.get("patternId"), "--"))
                        .append(" ")
                        .append(text(pattern.get("riskStructureName"), "--"))
                        .append("：")
                        .append(statusLabel(text(pattern.get("status"), "")));
                if (StringUtils.hasText(text(pattern.get("description"), ""))) {
                    body.append("；含义：").append(text(pattern.get("description"), ""));
                }
                if (StringUtils.hasText(text(pattern.get("invalidatesWhen"), ""))) {
                    body.append("；失效条件：").append(text(pattern.get("invalidatesWhen"), ""));
                }
                body.append("\n");
            }
        } else {
            List<String> skippedReasons = stringList(summary.get("skippedReasons"));
            if (!skippedReasons.isEmpty()) {
                body.append(prefix).append("本轮未形成可用结构判断：")
                        .append(String.join("；", skippedReasons)).append("\n");
            } else {
                body.append(prefix).append("本轮未命中 R01-R04 价量风险结构。\n");
            }
        }
        Map<String, Object> fact = map(summary.get("factObservation"));
        if (!fact.isEmpty() && StringUtils.hasText(text(fact.get("observationKey"), ""))) {
            body.append(prefix).append("事实链路：").append(text(fact.get("observationKey"), "")).append("\n");
        }
        return body.toString();
    }

    private List<Map<String, Object>> compactPatterns(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(raw instanceof List)) return result;
        for (Object item : (List<?>) raw) {
            Map<String, Object> source = map(item);
            if (source.isEmpty()) continue;
            String id = text(source.get("patternId"), "");
            PatternMeta meta = PATTERN_META.getOrDefault(id, new PatternMeta(
                    "未知价量结构",
                    "该结构不在当前 R01-R04 白名单内，仅保留编号供排查。"
            ));
            Map<String, Object> pattern = new LinkedHashMap<>();
            pattern.put("patternId", id);
            pattern.put("status", text(source.get("status"), "unknown"));
            pattern.put("statusLabel", statusLabel(text(source.get("status"), "")));
            pattern.put("riskStructureName", meta.name);
            pattern.put("description", meta.description);
            pattern.put("invalidatesWhen", text(source.get("invalidatesWhen"), ""));
            pattern.put("primaryValue", source.get("primaryValue"));
            pattern.put("riskReviewConfidenceCap", source.get("riskReviewConfidenceCap"));
            if (source.containsKey("skipReason")) {
                String skipReason = text(source.get("skipReason"), "");
                pattern.put("skipReason", skipReason);
                pattern.put("skipReasonLabel", skipReasonLabel(skipReason));
            }
            result.add(pattern);
        }
        return result;
    }

    private Map<String, Object> dataQuality(Map<String, Object> observations, Map<String, Object> factReceipt) {
        Map<String, Object> sourceQuality = map(observations.get("sourceQuality"));
        Map<String, Object> quality = new LinkedHashMap<>();
        copyIfPresent(sourceQuality, quality, "canonicalQuoteObservedAt");
        copyIfPresent(sourceQuality, quality, "canonicalQuoteTradeDate");
        copyIfPresent(sourceQuality, quality, "latestDailyKlineDate");
        copyIfPresent(sourceQuality, quality, "quoteAgeSeconds");
        copyIfPresent(sourceQuality, quality, "freshnessStatus");
        copyIfPresent(sourceQuality, quality, "consistent");
        quality.put("completedBaselineBarCount", observations.get("completedBaselineBarCount"));
        Object sourceTimeQuality = factReceipt == null ? null : factReceipt.get("sourceTimeQuality");
        if (sourceTimeQuality == null) sourceTimeQuality = sourceQuality.get("freshnessStatus");
        if (sourceTimeQuality != null) {
            String raw = text(sourceTimeQuality, "");
            quality.put("sourceTimeQuality", raw);
            quality.put("sourceTimeQualityLabel", sourceTimeQualityLabel(raw));
        }
        String freshness = text(sourceQuality.get("freshnessStatus"), "");
        if (StringUtils.hasText(freshness)) {
            quality.put("freshnessStatusLabel", sourceTimeQualityLabel(freshness));
        }
        return quality;
    }

    private void putFactPointer(Map<String, Object> summary, Map<String, Object> factReceipt) {
        if (factReceipt == null || factReceipt.isEmpty()) return;
        Map<String, Object> fact = new LinkedHashMap<>();
        copyIfPresent(factReceipt, fact, "observationId");
        copyIfPresent(factReceipt, fact, "observationKey");
        copyIfPresent(factReceipt, fact, "sourceTimeEpochMs");
        copyIfPresent(factReceipt, fact, "sourceTimeQuality");
        copyIfPresent(factReceipt, fact, "createdTestCount");
        Object verdicts = factReceipt.get("testVerdicts");
        if (verdicts instanceof List) fact.put("testVerdictsCount", ((List<?>) verdicts).size());
        if (!fact.isEmpty()) summary.put("factObservation", fact);
    }

    private List<String> idsByStatus(List<Map<String, Object>> patterns, String status) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> pattern : patterns) {
            if (status.equals(pattern.get("status")) && StringUtils.hasText(text(pattern.get("patternId"), ""))) {
                ids.add(text(pattern.get("patternId"), ""));
            }
        }
        return ids;
    }

    private List<String> skippedReasons(List<Map<String, Object>> patterns) {
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> pattern : patterns) {
            if (!"skipped".equals(pattern.get("status"))) continue;
            String reason = text(pattern.get("patternId"), "--") + "：" + text(pattern.get("skipReasonLabel"),
                    skipReasonLabel(text(pattern.get("skipReason"), "skipped")));
            if (!reasons.contains(reason)) reasons.add(reason);
        }
        return reasons;
    }

    private List<String> rawSkippedReasons(List<Map<String, Object>> patterns) {
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> pattern : patterns) {
            if (!"skipped".equals(pattern.get("status"))) continue;
            String reason = text(pattern.get("patternId"), "--") + ":" + text(pattern.get("skipReason"), "skipped");
            if (!reasons.contains(reason)) reasons.add(reason);
        }
        return reasons;
    }

    private List<Map<String, Object>> activePatterns(Object raw) {
        List<Map<String, Object>> patterns = new ArrayList<>();
        if (!(raw instanceof List)) return patterns;
        for (Object item : (List<?>) raw) {
            Map<String, Object> pattern = map(item);
            String status = text(pattern.get("status"), "");
            if ("triggered".equals(status) || "watch".equals(status)) patterns.add(pattern);
        }
        return patterns;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        return Collections.emptyMap();
    }

    private List<String> stringList(Object raw) {
        List<String> values = new ArrayList<>();
        if (!(raw instanceof List)) return values;
        for (Object item : (List<?>) raw) {
            String value = text(item, "");
            if (StringUtils.hasText(value)) values.add(value);
        }
        return values;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key)) target.put(key, source.get(key));
    }

    private String semanticBoundary(Object raw) {
        String value = text(raw, "");
        if (!StringUtils.hasText(value)) return BOUNDARY;
        if (containsChinese(value)) return value;
        if (value.toLowerCase(Locale.ROOT).contains("cannot prove")) return BOUNDARY;
        return value;
    }

    private boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') return true;
        }
        return false;
    }

    private String text(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private String conclusionText(String status, List<Map<String, Object>> patterns) {
        List<Map<String, Object>> active = activePatterns(patterns);
        if (!active.isEmpty()) {
            return "本轮发现 " + active.size() + " 个需要复核的价量风险结构：" + patternNames(active) + "。";
        }
        if ("SKIPPED_UNAVAILABLE".equals(status)) {
            return "本轮行情事实暂不满足 L0 结构判断条件，先跳过套路识别。";
        }
        return "本轮已检查 R01-R04，暂未发现需要特别标记的价量风险结构。";
    }

    private String valueText(String status, List<Map<String, Object>> patterns) {
        if ("SKIPPED_UNAVAILABLE".equals(status)) {
            return "这层会先检查行情是否足够新鲜和完整，避免用过期或缺失数据误导后续主力意图判断。";
        }
        if (!activePatterns(patterns).isEmpty()) {
            return "这层把模型分析约束在可复核的价量事实上，后续可用失效条件验证本次主力意图假设。";
        }
        return "这层记录已排查的结构，让后续主力意图分析知道哪些常见误导信号本轮没有出现。";
    }

    private String nextWatchText(String status, List<Map<String, Object>> patterns) {
        List<Map<String, Object>> active = activePatterns(patterns);
        if (!active.isEmpty()) {
            List<String> invalidations = new ArrayList<>();
            for (Map<String, Object> pattern : active) {
                String invalidatesWhen = text(pattern.get("invalidatesWhen"), "");
                if (StringUtils.hasText(invalidatesWhen) && !invalidations.contains(invalidatesWhen)) {
                    invalidations.add(invalidatesWhen);
                }
            }
            if (!invalidations.isEmpty()) return String.join("；", invalidations);
            return "观察下一根K线是否继续确认或削弱当前结构。";
        }
        if ("SKIPPED_UNAVAILABLE".equals(status)) {
            return "等待行情源刷新且基准K线满足后，再复核 R01-R04。";
        }
        return "继续观察是否出现突破后回落、活跃成交但价格推进有限、高开回落或长上影聚集。";
    }

    private String dataLimitText(String status, Map<String, Object> dataQuality, List<String> skippedReasons) {
        List<String> parts = new ArrayList<>();
        String quality = text(dataQuality.get("sourceTimeQualityLabel"), "");
        if (StringUtils.hasText(quality) && !"行情时间新鲜".equals(quality) && !"收盘后行情可用".equals(quality)) {
            parts.add("行情时间质量：" + quality);
        }
        Object barCount = dataQuality.get("completedBaselineBarCount");
        if (barCount != null && !"null".equals(String.valueOf(barCount))) {
            try {
                int count = Integer.parseInt(String.valueOf(barCount));
                if (count < 20) parts.add("基准K线不足20根");
            } catch (Exception ignored) {
                // Keep the original quality labels when the count is not numeric.
            }
        }
        if ("SKIPPED_UNAVAILABLE".equals(status) && !skippedReasons.isEmpty()) {
            parts.add("跳过原因：" + String.join("；", skippedReasons));
        }
        return String.join("；", parts);
    }

    private String patternNames(List<Map<String, Object>> patterns) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> pattern : patterns) {
            String label = text(pattern.get("patternId"), "--") + " " + text(pattern.get("riskStructureName"), "--");
            if (!names.contains(label)) names.add(label);
        }
        return String.join("、", names);
    }

    private String skipReasonLabel(String reason) {
        String value = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (value.startsWith("quote_freshness:")) {
            return "行情时间质量未通过（" + sourceTimeQualityLabel(value.substring("quote_freshness:".length()).toUpperCase(Locale.ROOT)) + "）";
        }
        if ("data_quality_gate_failed".equals(value)) return "行情日期或新鲜度未通过，避免用过期数据判断";
        if ("need_20_completed_daily_bars".equals(value)) return "少于20根已完成日K，无法判断突破/成交基线";
        if ("need_4_completed_daily_bars".equals(value)) return "少于4根已完成日K，无法判断上影线聚集";
        if ("required_derived_value_missing".equals(value)) return "关键衍生指标缺失";
        if ("source_trade_date_conflict".equals(value)) return "行情源交易日不一致";
        if ("canonical_quote_unavailable".equals(value)) return "标准行情事实不可用";
        return StringUtils.hasText(reason) ? reason : "数据不足";
    }

    private String sourceTimeQualityLabel(String quality) {
        String value = quality == null ? "" : quality.toUpperCase(Locale.ROOT);
        if ("FRESH".equals(value)) return "行情时间新鲜";
        if ("POST_CLOSE_VALID".equals(value)) return "收盘后行情可用";
        if ("QUOTE_ALERT".equals(value)) return "报价异动锚点";
        if ("STALE_DATE".equals(value)) return "行情日期过期";
        if ("STALE_AGE".equals(value)) return "行情报价过旧";
        if ("OUTSIDE_REALTIME_SESSION".equals(value)) return "非实时交易时段";
        if ("TIMESTAMP_MISSING".equals(value)) return "行情时间戳缺失";
        if ("FUTURE_QUOTE".equals(value)) return "行情时间异常超前";
        if ("UNKNOWN".equals(value)) return "行情时间质量未知";
        return StringUtils.hasText(quality) ? quality : "--";
    }

    private String statusLabel(String status) {
        String value = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if ("active".equals(value)) return "有命中或关注结构";
        if ("evaluated_none".equals(value) || "evaluated".equals(value)) return "已评估，未命中";
        if ("skipped_unavailable".equals(value)) return "数据不足，跳过";
        if ("triggered".equals(value)) return "命中";
        if ("watch".equals(value)) return "关注";
        if ("not_triggered".equals(value)) return "未命中";
        if ("skipped".equals(value)) return "跳过";
        return StringUtils.hasText(status) ? status : "--";
    }

    private static Map<String, PatternMeta> patternMeta() {
        Map<String, PatternMeta> meta = new LinkedHashMap<>();
        meta.put("R01", new PatternMeta("突破后未站稳风险", "突破前高或压力后回落，观察是否是假突破或派发压力。"));
        meta.put("R02", new PatternMeta("活跃成交但价格推进有限", "成交活跃但价格没有有效推进，观察是否存在换手消耗或上方压制。"));
        meta.put("R03", new PatternMeta("高开后回落且日内承接不足", "高开后回落，观察追高资金承接是否不足。"));
        meta.put("R04", new PatternMeta("近5根K线长上影聚集", "近期上影线聚集，观察上方抛压是否反复出现。"));
        return meta;
    }

    private static class PatternMeta {
        private final String name;
        private final String description;

        private PatternMeta(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }
}
