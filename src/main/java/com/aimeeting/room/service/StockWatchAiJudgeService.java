package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
import com.aimeeting.room.service.ai.AiModelRouter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class StockWatchAiJudgeService {

    private static final String SYSTEM_PROMPT = String.join("\n",
            "你是A股盯盘规则判断器，只能基于用户提供的结构化数据判断，不能编造实时行情。",
            "你的任务是判断用户的自然语言观察条件是否已经命中。",
            "必须只输出一个JSON对象，不要输出Markdown、解释性前后缀或代码块。",
            "JSON字段固定为：triggered(boolean), confidence(number 0-1), severity(string), shouldNotify(boolean), reason(string), matchedSignals(array), missingData(array)。",
            "如果数据不足，triggered=false，missingData列出缺失字段。",
            "如果条件语义模糊但有明显风险，可以提高severity，但confidence必须体现不确定性。"
    );

    private final AiModelRouter aiModelRouter;

    public StockWatchAiJudgeService(AiModelRouter aiModelRouter) {
        this.aiModelRouter = aiModelRouter;
    }

    public AiDecision judge(AiWatchRule rule, Map<String, Object> quote, ZonedDateTime now) {
        AiDecision structuredDecision = judgeStructuredQuoteRule(rule, quote);
        if (structuredDecision != null && structuredDecision.isTriggered()) {
            return structuredDecision;
        }

        String model = StringUtils.hasText(rule.getModel()) ? rule.getModel() : "deepseek-chat";
        String userPrompt = buildPrompt(rule, quote, now);
        String raw = null;
        try {
            raw = aiModelRouter.chat(model, SYSTEM_PROMPT, userPrompt, 900);
            if (!StringUtils.hasText(raw)) {
                if (structuredDecision != null) {
                    structuredDecision.setRawResponse(raw);
                    return structuredDecision;
                }
                return AiDecision.failed("模型调用未返回正文，请检查DeepSeek API Key、余额或模型配置", raw);
            }
            JSONObject parsed = extractJsonObject(raw);
            if (parsed == null) {
                if (structuredDecision != null) {
                    structuredDecision.setRawResponse(raw);
                    return structuredDecision;
                }
                return AiDecision.failed("模型未返回可解析JSON", raw);
            }
            AiDecision decision = new AiDecision();
            decision.setTriggered(bool(parsed.get("triggered"), false));
            decision.setConfidence(clamp(number(parsed.get("confidence"), 0.0)));
            decision.setSeverity(text(parsed.get("severity"), rule.getSeverity()));
            decision.setShouldNotify(bool(parsed.get("shouldNotify"), decision.isTriggered()));
            decision.setReason(text(parsed.get("reason"), ""));
            decision.setMatchedSignals(toStringList(parsed.getJSONArray("matchedSignals")));
            decision.setMissingData(toStringList(parsed.getJSONArray("missingData")));
            decision.setRawResponse(raw);
            decision.setModel(model);
            decision.setOk(true);
            return decision;
        } catch (Exception e) {
            log.warn("DeepSeek stock-watch rule judgment failed ruleId={} model={} error={}",
                    rule.getId(), model, e.getMessage());
            if (structuredDecision != null) {
                structuredDecision.setRawResponse(raw);
                return structuredDecision;
            }
            return AiDecision.failed("模型判断异常：" + e.getMessage(), raw);
        }
    }

    private AiDecision judgeStructuredQuoteRule(AiWatchRule rule, Map<String, Object> quote) {
        if (quote == null || quote.isEmpty() || !StringUtils.hasText(rule.getConditionText())) {
            return null;
        }
        String condition = rule.getConditionText().toLowerCase(Locale.ROOT);
        boolean supported = false;
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        Double changePercent = numberOrNull(quote.get("changePercent"));
        if (mentionsAny(condition, "changepercent", "change_pct", "涨跌幅")) {
            supported = true;
            if (changePercent == null) {
                missing.add("changePercent");
            } else {
                if (mentionsAny(condition, "绝对值", "abs") && Math.abs(changePercent) >= 5.0) {
                    matched.add("abs(changePercent)=" + format(changePercent) + " >= 5");
                }
                if (changePercent >= 9.5 || changePercent <= -9.5) {
                    matched.add("changePercent=" + format(changePercent) + " 接近涨跌停阈值 9.5");
                }
            }
        }

        Double volumeRatio = numberOrNull(quote.get("volumeRatio"));
        if (mentionsAny(condition, "volumeratio", "量比")) {
            supported = true;
            if (volumeRatio == null) {
                missing.add("volumeRatio");
            } else if (volumeRatio >= 2.0 && (changePercent == null || Math.abs(changePercent) >= 2.0)) {
                matched.add("volumeRatio=" + format(volumeRatio) + " >= 2"
                        + (changePercent == null ? "" : " 且 abs(changePercent)=" + format(changePercent) + " >= 2"));
            }
        }

        Double mainInflow = numberOrNull(quote.get("mainInflow"));
        if (mentionsAny(condition, "maininflow", "主力")) {
            supported = true;
            if (mainInflow == null) {
                missing.add("mainInflow");
            } else if (Math.abs(mainInflow) >= 100_000_000.0) {
                matched.add("abs(mainInflow)=" + format(Math.abs(mainInflow)) + " >= 100000000");
            }
        }

        Double turnover = numberOrNull(quote.get("turnover"));
        if (mentionsAny(condition, "turnover", "换手率")) {
            supported = true;
            if (turnover == null) {
                missing.add("turnover");
            } else if (turnover >= 5.0 && (changePercent == null || Math.abs(changePercent) >= 3.0)) {
                matched.add("turnover=" + format(turnover) + " >= 5"
                        + (changePercent == null ? "" : " 且 abs(changePercent)=" + format(changePercent) + " >= 3"));
            }
        }

        if (!supported) {
            return null;
        }

        AiDecision decision = new AiDecision();
        decision.setOk(true);
        decision.setTriggered(!matched.isEmpty());
        decision.setConfidence(matched.isEmpty() ? 0.82 : Math.min(0.98, 0.88 + matched.size() * 0.03));
        decision.setSeverity(matched.isEmpty() ? "info" : text(rule.getSeverity(), "risk"));
        decision.setShouldNotify(!matched.isEmpty());
        decision.setMatchedSignals(matched);
        decision.setMissingData(missing);
        decision.setModel("structured-quote-rule");
        decision.setReason(matched.isEmpty()
                ? "结构化行情规则未命中；已检查字段：" + checkedFields(condition)
                : "结构化行情规则命中：" + String.join("；", matched));
        return decision;
    }

    private String buildPrompt(AiWatchRule rule, Map<String, Object> quote, ZonedDateTime now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        payload.put("rule", rule);
        payload.put("quote", quote);
        payload.put("availableFields", quote == null ? List.of() : quote.keySet());
        payload.put("instruction", "判断 rule.conditionText 是否被 quote 中的数据命中。仅基于 quote，不要猜测缺失数据。");
        return JSON.toJSONString(payload, true);
    }

    private JSONObject extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        JSONObject direct = parseObject(text);
        if (direct != null) {
            return direct;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return parseObject(text.substring(start, end + 1));
        }
        return null;
    }

    private JSONObject parseObject(String text) {
        try {
            return JSON.parseObject(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value)) return false;
        return fallback;
    }

    private double number(Object raw, double fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Double numberOrNull(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean mentionsAny(String value, String... tokens) {
        if (value == null) return false;
        for (String token : tokens) {
            if (value.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String checkedFields(String condition) {
        List<String> fields = new ArrayList<>();
        if (mentionsAny(condition, "changepercent", "change_pct", "涨跌幅")) fields.add("changePercent");
        if (mentionsAny(condition, "volumeratio", "量比")) fields.add("volumeRatio");
        if (mentionsAny(condition, "maininflow", "主力")) fields.add("mainInflow");
        if (mentionsAny(condition, "turnover", "换手率")) fields.add("turnover");
        return String.join(", ", fields);
    }

    private String format(Double value) {
        if (value == null) return "null";
        if (Math.abs(value) >= 10000) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }

    private List<String> toStringList(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (item != null && StringUtils.hasText(String.valueOf(item))) {
                values.add(String.valueOf(item).trim());
            }
        }
        return values;
    }

    @Data
    public static class AiDecision {
        private boolean ok;
        private boolean triggered;
        private double confidence;
        private String severity;
        private boolean shouldNotify;
        private String reason;
        private List<String> matchedSignals = new ArrayList<>();
        private List<String> missingData = new ArrayList<>();
        private String rawResponse;
        private String model;

        public static AiDecision failed(String reason, String raw) {
            AiDecision decision = new AiDecision();
            decision.ok = false;
            decision.triggered = false;
            decision.confidence = 0.0;
            decision.severity = "info";
            decision.shouldNotify = false;
            decision.reason = reason;
            decision.rawResponse = raw;
            return decision;
        }
    }
}
