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
            "JSON字段固定为：triggered(boolean), confidence(number 0-1), severity(string), shouldNotify(boolean), reason(string), analysis(string), matchedSignals(array), missingData(array), beliefUpdate(object，可为空)。",
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

    @Value("${stock-watch.ai-rules.bridge-first:false}")
    private boolean bridgeFirst;

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
            if (shouldUseBridgeFirst(model)) {
                actualModel = "deepseek-web";
                raw = callDeepSeekWebBridgeWithRetry(rule, userPrompt);
            } else {
                raw = aiModelRouter.chat(model, SYSTEM_PROMPT, userPrompt, 900);
                if (!StringUtils.hasText(raw)) {
                    actualModel = "deepseek-web";
                    raw = callDeepSeekWebBridgeWithRetry(rule, userPrompt);
                }
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
            decision.setBeliefUpdate(toObjectMap(parsed.get("beliefUpdate")));
            decision.setRawResponse(raw);
            decision.setModel(actualModel);
            decision.setOk(true);
            guardHistoricalTriggerSource(decision);
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

    private boolean shouldUseBridgeFirst(String model) {
        if (bridgeFirst) {
            return true;
        }
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return "deepseek-web".equals(normalized)
                || "deepseek_web".equals(normalized)
                || "deepseek-web-bridge".equals(normalized)
                || "deepseek_web_bridge".equals(normalized);
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

    private void guardHistoricalTriggerSource(AiDecision decision) {
        if (decision == null || !decision.isTriggered()) {
            return;
        }
        if (!mentionsHistoricalTriggerSource(decision.getReason())
                && !mentionsHistoricalTriggerSource(decision.getMatchedSignals())) {
            return;
        }
        decision.setTriggered(false);
        decision.setShouldNotify(false);
        decision.setConfidence(Math.min(decision.getConfidence(), 0.49));
        decision.setReason("模型触发依据引用历史技术分析，已按本轮事实边界拦截；历史技术分析仅保留为analysis背景。");
        decision.setMatchedSignals(new ArrayList<>());
    }

    private boolean mentionsHistoricalTriggerSource(List<String> values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (mentionsHistoricalTriggerSource(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsHistoricalTriggerSource(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("recenttechnicalanalysis")
                || normalized.contains("历史技术分析")
                || normalized.contains("历史分析")
                || normalized.contains("历史mainforceintent")
                || normalized.contains("mainforceintent");
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
        payload.put("factPriority", "事实优先级：本轮quote实时行情 > aiContext.currentFacts本轮增强摘要 > aiContext.recentTechnicalAnalysis.records[].priorValidation程序化历史验证；recentTechnicalAnalysis历史技术分析只能用于analysis/beliefUpdate里的主力意图连续性、反证、风险和观察点。");
        payload.put("instruction", "判断 rule.conditionText 是否被本轮数据命中。triggered、reason、matchedSignals只能引用quote或aiContext.currentFacts里的本轮事实；不得用recentTechnicalAnalysis、历史技术分析、历史mainForceIntent或priorValidation作为本轮触发依据。analysis必须结合recentTechnicalAnalysis.records[].priorValidation说明过去两周每轮技术判断中哪些被支持、削弱、证伪或仍未决，并在beliefUpdate里输出当前主力意图连续判断、被证伪/缩小的旧假设、下一轮最关键观察点。若历史结论与本轮事实冲突，以本轮事实为准。不要猜测缺失数据。");
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
        copyContextField(source, sanitized, "status");
        copyContextField(source, sanitized, "lookbackDays");
        copyContextField(source, sanitized, "windowStart");
        copyContextField(source, sanitized, "windowEnd");
        copyContextField(source, sanitized, "recordCount");
        copyContextField(source, sanitized, "maxRecords");
        copyContextField(source, sanitized, "selectionPolicy");
        copyContextField(source, sanitized, "usage");
        copyContextField(source, sanitized, "truncatedByMaxChars");
        Object records = source.get("records");
        if (records instanceof List) {
            List<Object> sanitizedRecords = new ArrayList<>();
            for (Object record : (List<?>) records) {
                if (sanitizedRecords.size() >= 48) {
                    break;
                }
                Map<String, Object> sanitizedRecord = sanitizeTechnicalRecord(record);
                if (!sanitizedRecord.isEmpty()) {
                    sanitizedRecords.add(sanitizedRecord);
                }
            }
            if (!sanitizedRecords.isEmpty()) {
                sanitized.put("records", sanitizedRecords);
                sanitized.put("recordCount", sanitizedRecords.size());
            }
        } else {
            Map<String, Object> singleRecord = sanitizeTechnicalRecord(source);
            if (!singleRecord.isEmpty()) {
                sanitized.put("records", List.of(singleRecord));
                sanitized.put("recordCount", 1);
            }
        }
        if (!sanitized.containsKey("usage")) {
            sanitized.put("usage", "historical_analysis_only_for_analysis_continuity_not_trigger_fact");
        }
        return sanitized;
    }

    private Map<String, Object> sanitizeTechnicalRecord(Object raw) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return sanitized;
        }
        Map<?, ?> source = (Map<?, ?>) raw;
        copyContextField(source, sanitized, "id");
        copyTextContextField(source, sanitized, "date", 20);
        copyTextContextField(source, sanitized, "analysisDate", 20);
        copyTextContextField(source, sanitized, "time", 16);
        copyTextContextField(source, sanitized, "runSlot", 16);
        copyTextContextField(source, sanitized, "periodType", 16);
        copyTextContextField(source, sanitized, "status", 24);
        copyTextContextField(source, sanitized, "stance", 24);
        copyTextContextField(source, sanitized, "tone", 24);
        copyContextField(source, sanitized, "confidence");
        copyTextContextField(source, sanitized, "title", 90);
        copyTextContextField(source, sanitized, "conclusion", 280);
        copyStringListContextField(source, sanitized, "evidence", 3, 140);
        copyStringListContextField(source, sanitized, "missingData", 3, 120);
        Map<String, Object> intent = sanitizeMainForceIntent(source.get("mainForceIntent"));
        if (!intent.isEmpty()) {
            sanitized.put("mainForceIntent", intent);
        }
        copyTextContextField(source, sanitized, "mainForceIntentStatus", 40);
        Map<String, Object> priorValidation = sanitizePriorValidation(source.get("priorValidation"));
        if (!priorValidation.isEmpty()) {
            sanitized.put("priorValidation", priorValidation);
        }
        return sanitized;
    }

    private void copyContextField(Map<?, ?> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private void copyTextContextField(Map<?, ?> source, Map<String, Object> target, String key, int maxLength) {
        if (!source.containsKey(key) || source.get(key) == null) {
            return;
        }
        String value = String.valueOf(source.get(key)).trim();
        if (StringUtils.hasText(value)) {
            target.put(key, truncate(value, maxLength));
        }
    }

    private void copyStringListContextField(Map<?, ?> source,
                                            Map<String, Object> target,
                                            String key,
                                            int maxItems,
                                            int maxLength) {
        Object raw = source.get(key);
        List<String> values = sanitizeStringList(raw, maxItems, maxLength);
        if (!values.isEmpty()) {
            target.put(key, values);
        }
    }

    private Map<String, Object> sanitizeMainForceIntent(Object raw) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return sanitized;
        }
        Map<?, ?> source = (Map<?, ?>) raw;
        copyContextField(source, sanitized, "schemaVersion");
        copyTextContextField(source, sanitized, "hypothesisId", 64);
        copyTextContextField(source, sanitized, "intent", 32);
        copyTextContextField(source, sanitized, "timeWindow", 32);
        copyContextField(source, sanitized, "confidence");
        copyStringListContextField(source, sanitized, "evidence", 3, 140);
        copyStringListContextField(source, sanitized, "counterEvidence", 3, 140);
        copyStringListContextField(source, sanitized, "assumptions", 3, 120);
        copyTextContextField(source, sanitized, "risk", 180);
        List<Object> watchPoints = sanitizeWatchPoints(source.get("watchPoints"), 6);
        if (!watchPoints.isEmpty()) {
            sanitized.put("watchPoints", watchPoints);
        }
        copyStringListContextField(source, sanitized, "validatedEvidencePaths", 5, 160);
        copyContextField(source, sanitized, "previousAnalysisId");
        copyContextField(source, sanitized, "sourceAnalysisIds");
        Map<String, Object> lastValidation = sanitizePriorValidation(source.get("lastValidation"));
        if (!lastValidation.isEmpty()) {
            sanitized.put("lastValidation", lastValidation);
        }
        copyTextContextField(source, sanitized, "invalidatedAt", 40);
        copyTextContextField(source, sanitized, "invalidatedBy", 180);
        return sanitized;
    }

    private Map<String, Object> sanitizePriorValidation(Object raw) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return sanitized;
        }
        Map<?, ?> source = (Map<?, ?>) raw;
        copyTextContextField(source, sanitized, "verdict", 24);
        copyTextContextField(source, sanitized, "validatedAt", 40);
        copyContextField(source, sanitized, "sourceAnalysisId");
        copyContextField(source, sanitized, "basedOn");
        copyTextContextField(source, sanitized, "summary", 240);
        Object checks = source.get("checks");
        if (checks instanceof List) {
            List<Object> sanitizedChecks = new ArrayList<>();
            for (Object item : (List<?>) checks) {
                if (sanitizedChecks.size() >= 8) {
                    break;
                }
                Map<String, Object> check = sanitizePriorValidationCheck(item);
                if (!check.isEmpty()) {
                    sanitizedChecks.add(check);
                }
            }
            if (!sanitizedChecks.isEmpty()) {
                sanitized.put("checks", sanitizedChecks);
            }
        }
        return sanitized;
    }

    private Map<String, Object> sanitizePriorValidationCheck(Object raw) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return sanitized;
        }
        Map<?, ?> source = (Map<?, ?>) raw;
        copyTextContextField(source, sanitized, "type", 32);
        copyTextContextField(source, sanitized, "verdict", 24);
        copyTextContextField(source, sanitized, "reason", 180);
        copyTextContextField(source, sanitized, "intent", 32);
        copyTextContextField(source, sanitized, "watchPoint", 180);
        copyTextContextField(source, sanitized, "metricPath", 120);
        copyTextContextField(source, sanitized, "operator", 12);
        copyContextField(source, sanitized, "threshold");
        copyContextField(source, sanitized, "actualValue");
        copyContextField(source, sanitized, "mainInflow");
        copyContextField(source, sanitized, "changePercent");
        return sanitized;
    }

    private List<Object> sanitizeWatchPoints(Object raw, int maxItems) {
        List<Object> values = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.size() && values.size() < maxItems; i++) {
                addSanitizedWatchPoint(values, array.get(i));
            }
        } else if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (values.size() >= maxItems) break;
                addSanitizedWatchPoint(values, item);
            }
        } else if (raw != null) {
            addSanitizedWatchPoint(values, raw);
        }
        return values;
    }

    private void addSanitizedWatchPoint(List<Object> values, Object raw) {
        if (raw instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) raw;
            Map<String, Object> item = new LinkedHashMap<>();
            copyTextContextField(source, item, "metricPath", 120);
            copyTextContextField(source, item, "path", 120);
            copyTextContextField(source, item, "operator", 12);
            copyTextContextField(source, item, "op", 12);
            copyContextField(source, item, "threshold");
            copyContextField(source, item, "value");
            copyTextContextField(source, item, "window", 32);
            copyTextContextField(source, item, "expiry", 40);
            copyTextContextField(source, item, "description", 180);
            copyContextField(source, item, "falsifyOnMiss");
            if (!item.isEmpty()) {
                values.add(item);
            }
            return;
        }
        if (raw != null && StringUtils.hasText(String.valueOf(raw))) {
            values.add(truncate(String.valueOf(raw).trim(), 160));
        }
    }

    private List<String> sanitizeStringList(Object raw, int maxItems, int maxLength) {
        List<String> values = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.size() && values.size() < maxItems; i++) {
                addSanitizedString(values, array.get(i), maxLength);
            }
        } else if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (values.size() >= maxItems) break;
                addSanitizedString(values, item, maxLength);
            }
        } else if (raw != null) {
            addSanitizedString(values, raw, maxLength);
        }
        return values;
    }

    private void addSanitizedString(List<String> values, Object raw, int maxLength) {
        if (raw == null) {
            return;
        }
        String value = String.valueOf(raw).trim();
        if (StringUtils.hasText(value)) {
            values.add(truncate(value, maxLength));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> toObjectMap(Object raw) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return values;
        }
        Map<?, ?> source = (Map<?, ?>) raw;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                values.put(String.valueOf(entry.getKey()), entry.getValue());
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
        private Map<String, Object> beliefUpdate = new LinkedHashMap<>();
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
