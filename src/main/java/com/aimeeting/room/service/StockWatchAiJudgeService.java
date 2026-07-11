package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
import com.aimeeting.room.service.ai.AiModelRouter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class StockWatchAiJudgeService {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final String SYSTEM_PROMPT = String.join("\n",
            "你是A股盯盘规则判断器，只能基于用户提供的结构化数据判断，不能编造实时行情。",
            "你的任务是判断用户的自然语言观察条件是否已经命中。",
            "必须只输出一个JSON对象，不要输出Markdown、解释性前后缀或代码块。",
            "JSON字段固定为：triggered(boolean), confidence(number 0-1), severity(string), shouldNotify(boolean), reason(string), analysis(string), matchedSignals(array), missingData(array)。",
            "reason只写简洁的事实触发依据；analysis必须给出真正的分析，包括异动含义、持续性判断、主要风险和下一步观察点，不要重复阈值公式。",
            "如果数据不足，triggered=false，missingData列出缺失字段。",
            "如果条件语义模糊但有明显风险，可以提高severity，但confidence必须体现不确定性。"
    );

    private final AiModelRouter aiModelRouter;

    @Value("${stock-watch.ai-rules.bridge-url:${ai.search.deepseek-web.bridge-url:http://127.0.0.1:8789/search}}")
    private String bridgeUrl;

    @Value("${stock-watch.ai-rules.bridge-connect-timeout-seconds:10}")
    private int bridgeConnectTimeoutSeconds;

    @Value("${stock-watch.ai-rules.bridge-read-timeout-seconds:210}")
    private int bridgeReadTimeoutSeconds;

    public StockWatchAiJudgeService(AiModelRouter aiModelRouter) {
        this.aiModelRouter = aiModelRouter;
    }

    public AiDecision judge(AiWatchRule rule, Map<String, Object> quote, ZonedDateTime now) {
        return judge(rule, quote, now, null);
    }

    public AiDecision judge(AiWatchRule rule,
                            Map<String, Object> quote,
                            ZonedDateTime now,
                            Map<String, Object> aiContext) {
        AiDecision structuredDecision = judgeStructuredQuoteRule(rule, quote);
        String model = StringUtils.hasText(rule.getModel()) ? rule.getModel() : "deepseek-chat";
        String actualModel = model;
        String userPrompt = buildPrompt(rule, quote, now, aiContext);
        String raw = null;
        try {
            raw = aiModelRouter.chat(model, SYSTEM_PROMPT, userPrompt, 900);
            if (!StringUtils.hasText(raw)) {
                actualModel = "deepseek-web";
                raw = callDeepSeekWebBridgeWithRetry(rule, userPrompt);
            }
            if (!StringUtils.hasText(raw)) {
                if (structuredDecision != null) {
                    structuredDecision.setRawResponse(raw);
                    structuredDecision.setModel(actualModel);
                    structuredDecision.setAnalysis("DeepSeek 本轮未返回分析正文，仅保留结构化触发结果。请检查模型配置后复核。");
                    return structuredDecision;
                }
                return AiDecision.failed("模型调用未返回正文，请检查DeepSeek API Key、余额或模型配置", raw);
            }
            JSONObject parsed = extractJsonObject(raw);
            if (parsed == null && !"deepseek-web".equals(actualModel)) {
                actualModel = "deepseek-web";
                raw = callDeepSeekWebBridgeWithRetry(rule, userPrompt);
                parsed = extractJsonObject(raw);
            }
            if (parsed == null) {
                if (structuredDecision != null) {
                    structuredDecision.setRawResponse(raw);
                    structuredDecision.setModel(actualModel);
                    structuredDecision.setAnalysis("DeepSeek 返回内容无法解析，本轮仅保留结构化触发结果。");
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
            decision.setAnalysis(text(parsed.get("analysis"), decision.getReason()));
            decision.setMatchedSignals(toStringList(parsed.getJSONArray("matchedSignals")));
            decision.setMissingData(toStringList(parsed.getJSONArray("missingData")));
            decision.setRawResponse(raw);
            decision.setModel(actualModel);
            decision.setOk(true);
            return mergeStructuredDecision(structuredDecision, decision);
        } catch (Exception e) {
            log.warn("DeepSeek stock-watch rule judgment failed ruleId={} model={} error={}",
                    rule.getId(), model, e.getMessage());
            if (structuredDecision != null) {
                structuredDecision.setRawResponse(raw);
                structuredDecision.setModel(actualModel);
                structuredDecision.setAnalysis("DeepSeek 分析调用异常，本轮仅保留结构化触发结果：" + e.getMessage());
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
        List<String> matchedDescriptions = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        Double changePercent = numberOrNull(quote.get("changePercent"));
        if (mentionsAny(condition, "changepercent", "change_pct", "涨跌幅")) {
            supported = true;
            if (changePercent == null) {
                missing.add("changePercent");
            } else {
                if (mentionsAny(condition, "绝对值", "abs") && Math.abs(changePercent) >= 5.0) {
                    matched.add("abs(changePercent)=" + format(changePercent) + " >= 5");
                    matchedDescriptions.add("涨跌幅达到异动阈值");
                }
                if (changePercent >= 9.5 || changePercent <= -9.5) {
                    matched.add("changePercent=" + format(changePercent) + " 接近涨跌停阈值 9.5");
                    matchedDescriptions.add("涨跌幅接近涨跌停阈值");
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
                matchedDescriptions.add("量比与涨跌幅组合达到异动阈值");
            }
        }

        Double mainInflow = numberOrNull(quote.get("mainInflow"));
        if (mentionsAny(condition, "maininflow", "主力")) {
            supported = true;
            if (mainInflow == null) {
                missing.add("mainInflow");
            } else if (Math.abs(mainInflow) >= 100_000_000.0) {
                matched.add("abs(mainInflow)=" + format(Math.abs(mainInflow)) + " >= 100000000");
                matchedDescriptions.add(mainInflow >= 0
                        ? "主力净流入达到异动阈值"
                        : "主力净流出达到异动阈值");
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
                matchedDescriptions.add("换手率与涨跌幅组合达到异动阈值");
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
                : "结构化行情规则命中：" + String.join("；", matchedDescriptions));
        return decision;
    }

    private AiDecision mergeStructuredDecision(AiDecision structured, AiDecision modelDecision) {
        if (structured == null) {
            return modelDecision;
        }
        if (structured.isTriggered()) {
            modelDecision.setTriggered(true);
            modelDecision.setShouldNotify(true);
            modelDecision.setConfidence(Math.max(structured.getConfidence(), modelDecision.getConfidence()));
            modelDecision.setSeverity(text(modelDecision.getSeverity(), structured.getSeverity()));
            modelDecision.setReason(structured.getReason());
        }
        modelDecision.setMatchedSignals(mergeLists(structured.getMatchedSignals(), modelDecision.getMatchedSignals()));
        modelDecision.setMissingData(mergeLists(structured.getMissingData(), modelDecision.getMissingData()));
        return modelDecision;
    }

    private List<String> mergeLists(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        if (first != null) {
            for (String item : first) {
                if (StringUtils.hasText(item) && !merged.contains(item)) merged.add(item);
            }
        }
        if (second != null) {
            for (String item : second) {
                if (StringUtils.hasText(item) && !merged.contains(item)) merged.add(item);
            }
        }
        return merged;
    }

    private String buildPrompt(AiWatchRule rule,
                               Map<String, Object> quote,
                               ZonedDateTime now,
                               Map<String, Object> aiContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        payload.put("rule", rule);
        payload.put("quote", quote);
        payload.put("availableFields", quote == null ? List.of() : quote.keySet());
        if (aiContext != null && !aiContext.isEmpty()) {
            payload.put("aiContext", sanitizeAiContext(aiContext));
        }
        payload.put("factPriority", "事实优先级：本轮quote实时行情 > aiContext.currentFacts本轮增强摘要；aiContext.recentTechnicalAnalysis仅为历史记录元数据，不含可触发结论文本。");
        payload.put("instruction", "判断 rule.conditionText 是否被本轮数据命中。优先使用quote；aiContext.status=available时可参考currentFacts；recentTechnicalAnalysis只能证明历史分析存在，不能作为触发事实或补充主力意图。不要猜测缺失数据。");
        return JSON.toJSONString(payload, true);
    }

    private Map<String, Object> sanitizeAiContext(Map<String, Object> aiContext) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (aiContext == null || aiContext.isEmpty()) {
            return sanitized;
        }
        for (Map.Entry<String, Object> entry : aiContext.entrySet()) {
            String key = entry.getKey();
            if ("recentTechnicalAnalysis".equals(key)) {
                Map<String, Object> technical = sanitizeRecentTechnicalAnalysis(entry.getValue());
                if (!technical.isEmpty()) {
                    sanitized.put(key, technical);
                }
            } else {
                sanitized.put(key, entry.getValue());
            }
        }
        return sanitized;
    }

    private Map<String, Object> sanitizeRecentTechnicalAnalysis(Object raw) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return sanitized;
        }
        Map<?, ?> source = (Map<?, ?>) raw;
        copyContextField(source, sanitized, "id");
        copyContextField(source, sanitized, "analysisDate");
        copyContextField(source, sanitized, "runSlot");
        copyContextField(source, sanitized, "periodType");
        copyContextField(source, sanitized, "status");
        copyContextField(source, sanitized, "confidence");
        copyContextField(source, sanitized, "gmtCreate");
        copyContextField(source, sanitized, "usage");
        copyContextField(source, sanitized, "excludedFields");
        if (!sanitized.containsKey("usage")) {
            sanitized.put("usage", "metadata_only_do_not_use_as_trigger_fact");
        }
        return sanitized;
    }

    private void copyContextField(Map<?, ?> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private String callDeepSeekWebBridgeWithRetry(AiWatchRule rule, String userPrompt) throws Exception {
        try {
            return callDeepSeekWebBridge(rule, userPrompt, false);
        } catch (Exception first) {
            String message = text(first.getMessage(), "").toLowerCase(Locale.ROOT);
            if (!isRetryableBridgeFailure(message)) {
                throw first;
            }
            log.warn("DeepSeek stock-watch rule bridge returned retryable failure ruleId={} error={}, retrying with fresh session",
                    rule.getId(), first.getMessage());
            return callDeepSeekWebBridge(rule, userPrompt, true);
        }
    }

    private boolean isRetryableBridgeFailure(String message) {
        return message.contains("未返回分析正文")
                || message.contains("未完整生成")
                || message.contains("incomplete_json")
                || message.contains("timeout")
                || message.contains("max_inflight")
                || message.contains("bridge http 500")
                || message.contains("provider_timeout")
                || message.contains("search_failed");
    }

    private String callDeepSeekWebBridge(AiWatchRule rule, String userPrompt, boolean forceNewSession) throws Exception {
        if (!StringUtils.hasText(bridgeUrl)) {
            throw new IllegalStateException("DeepSeek 网页 Bridge 未配置");
        }
        JSONObject body = new JSONObject();
        body.put("query", SYSTEM_PROMPT + "\n\n任务数据：\n" + userPrompt);
        body.put("sessionKey", "stock-watch-rule-" + rule.getMarket() + "-" + rule.getSymbol());
        body.put("forceNewSession", forceNewSession);
        body.put("closeTargetAfter", false);
        body.put("expectedJson", true);
        body.put("mode", "expert");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.max(5, bridgeConnectTimeoutSeconds), TimeUnit.SECONDS)
                .readTimeout(Math.max(30, bridgeReadTimeoutSeconds), TimeUnit.SECONDS)
                .callTimeout(Math.max(40, bridgeReadTimeoutSeconds + 10), TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(bridgeUrl)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("DeepSeek 网页 Bridge HTTP " + response.code() + " " + truncate(responseBody, 300));
            }
            JSONObject payload = JSON.parseObject(responseBody);
            if (payload == null || !payload.getBooleanValue("ok")) {
                throw new IllegalStateException("DeepSeek 网页 Bridge 返回无效结果：" + truncate(responseBody, 300));
            }
            JSONObject result = payload == null ? null : payload.getJSONObject("result");
            String contentStatus = result == null ? "" : text(result.get("contentStatus"), "");
            String completionReason = result == null ? "" : text(result.get("completionReason"), "");
            if ("incomplete".equalsIgnoreCase(contentStatus)) {
                throw new IllegalStateException("DeepSeek 网页回答未完整生成，completionReason=" + completionReason);
            }
            String answer = result == null ? "" : text(result.get("answerText"), "");
            if (!StringUtils.hasText(answer)) {
                throw new IllegalStateException("DeepSeek 网页 Bridge 未返回分析正文，contentStatus=" + contentStatus
                        + "，completionReason=" + completionReason);
            }
            return answer;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
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
        private String analysis;
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
            decision.analysis = reason;
            decision.rawResponse = raw;
            return decision;
        }
    }
}
