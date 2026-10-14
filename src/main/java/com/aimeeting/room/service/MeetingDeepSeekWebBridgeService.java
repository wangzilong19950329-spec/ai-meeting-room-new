package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Meeting-scoped DeepSeek Web bridge adapter.
 *
 * This intentionally sits above AiModelRouter so meeting calls can avoid DeepSeek API
 * spending without changing stock-watch or global model routing behavior.
 */
@Slf4j
@Service
public class MeetingDeepSeekWebBridgeService {

    public enum RouteDecision {
        BRIDGE,
        PASS_THROUGH,
        FAIL_CLOSED
    }

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String BROKER_CAPABILITY = "general.analysis";
    private static final int BROKER_PRIORITY = 0;
    private static final List<String> LEGACY_NON_DEEPSEEK_PREFIXES = Arrays.asList(
            "gpt-", "o1", "o3", "qwen-", "claude-", "ernie-"
    );

    private final ReentrantLock bridgeLock = new ReentrantLock(true);

    @Autowired
    private ModelRegistryStore modelRegistryStore;

    @Autowired
    private AiModelProperties aiModelProperties;

    public RouteDecision routeDecision(String modelType) {
        AiModelProperties.DeepSeekWebBridgeConfig cfg = config();
        if (cfg == null || !cfg.isEnabled()) {
            return RouteDecision.PASS_THROUGH;
        }

        String selector = normalize(modelType);
        if (!StringUtils.hasText(selector)) {
            return isEnabledDeepSeekApiEntry(resolveDefaultEntry())
                    ? RouteDecision.BRIDGE
                    : RouteDecision.PASS_THROUGH;
        }

        ModelEntry entry = modelRegistryStore.getByModelId(selector);
        if (entry != null) {
            if (entry.isEnabled()) {
                return isDeepSeekApiEntry(entry)
                        ? RouteDecision.BRIDGE
                        : RouteDecision.PASS_THROUGH;
            }
            if (isLegacyNonDeepSeekSelector(selector)) {
                return RouteDecision.PASS_THROUGH;
            }
            return isEnabledDeepSeekApiEntry(resolveDefaultEntry())
                    ? RouteDecision.FAIL_CLOSED
                    : RouteDecision.PASS_THROUGH;
        }

        if (isLegacyNonDeepSeekSelector(selector)) {
            return RouteDecision.PASS_THROUGH;
        }
        if (isDeepSeekLikeSelector(selector)) {
            return RouteDecision.BRIDGE;
        }
        return isEnabledDeepSeekApiEntry(resolveDefaultEntry())
                ? RouteDecision.FAIL_CLOSED
                : RouteDecision.PASS_THROUGH;
    }

    public ChatResult failClosedResult(String modelType) {
        String note = "会议 DeepSeek 网页 bridge 已启用，但模型选择器无法安全解析；为避免回落到 DeepSeek API，本次调用已 fail-closed。modelType="
                + (StringUtils.hasText(modelType) ? modelType : "<default>");
        log.warn(note);
        return failure("fail_closed", "unresolved_model_blocked", note);
    }

    public ChatResult chat(String modelType, String systemPrompt, String userPrompt, Integer maxTokens) {
        return chat(modelType, systemPrompt, userPrompt, maxTokens, true);
    }

    public ChatResult chatWithoutSearch(String modelType, String systemPrompt, String userPrompt, Integer maxTokens) {
        return chat(modelType, systemPrompt, userPrompt, maxTokens, false);
    }

    private ChatResult chat(String modelType, String systemPrompt, String userPrompt, Integer maxTokens,
                            boolean enableSearch) {
        RouteDecision decision = routeDecision(modelType);
        if (decision == RouteDecision.FAIL_CLOSED) {
            return failClosedResult(modelType);
        }
        if (decision != RouteDecision.BRIDGE) {
            return failure("not_routed", "not_deepseek_api_model",
                    "当前模型未命中会议 DeepSeek 网页 bridge 路由。");
        }

        AiModelProperties.DeepSeekWebBridgeConfig cfg = config();
        String query = buildQuery(systemPrompt, userPrompt, maxTokens);
        boolean expectedJson = shouldExpectJson(systemPrompt, userPrompt);
        boolean searchExecutor = enableSearch
                && isSearchExecutorCall(resolveEntryForRouting(modelType), systemPrompt, userPrompt);
        String mode = normalizeMode(searchExecutor ? cfg.getSearchMode() : cfg.getMode(), searchExecutor ? "fast" : "expert");

        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("capability", BROKER_CAPABILITY);
        body.put("purpose", searchExecutor ? "meeting.web-search.v1" : "meeting.model-generation.v1");
        body.put("priority", BROKER_PRIORITY);
        body.put("expectedJson", expectedJson);
        body.put("mode", mode);
        body.put("forceNewSession", true);
        body.put("isolatedSession", true);
        body.put("closeTargetAfter", false);
        if (!enableSearch) {
            body.put("enableSearch", false);
        }
        String submissionKey = "meeting-web-v1:" + UUID.randomUUID();
        body.put("submissionKey", submissionKey);
        body.put("requestDigest", enableSearch
                ? StockWatchBridgeRequestIdentity.digest(
                    query, BROKER_CAPABILITY, "", mode, expectedJson, true, true, false, "")
                : StockWatchBridgeRequestIdentity.digestWithoutSearch(
                    query, BROKER_CAPABILITY, "", mode, expectedJson, true, true, false, ""));

        if (cfg.isSerialized()) {
            bridgeLock.lock();
            try {
                return callBridge(cfg, body, userPrompt);
            } finally {
                bridgeLock.unlock();
            }
        }
        return callBridge(cfg, body, userPrompt);
    }

    private ChatResult callBridge(AiModelProperties.DeepSeekWebBridgeConfig cfg,
                                  JSONObject body,
                                  String userPrompt) {
        if (cfg == null || !StringUtils.hasText(cfg.getBridgeUrl())) {
            return failure("bridge_not_configured", "bridge_not_configured", "会议 DeepSeek 网页 bridge URL 未配置。");
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.max(5, cfg.getConnectTimeoutSeconds()), TimeUnit.SECONDS)
                .readTimeout(Math.max(30, cfg.getReadTimeoutSeconds()), TimeUnit.SECONDS)
                .callTimeout(Math.max(40, cfg.getReadTimeoutSeconds() + 10L), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        Request.Builder requestBuilder = new Request.Builder()
                .url(cfg.getBridgeUrl())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE));
        if (StringUtils.hasText(cfg.getApiKey())) {
            requestBuilder.header("Authorization", "Bearer " + cfg.getApiKey().trim());
        }
        Request request = requestBuilder.build();
        long startedAt = System.currentTimeMillis();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                log.warn("会议 DeepSeek 网页 bridge HTTP {}: {}", response.code(), truncate(responseBody, 300));
                return failureFromPayload(response.code(), parseObject(responseBody));
            }
            JSONObject payload = JSON.parseObject(responseBody);
            if (payload == null || !payload.getBooleanValue("ok")) {
                log.warn("会议 DeepSeek 网页 bridge 返回无效结果：{}", truncate(responseBody, 300));
                return failure("invalid_payload", "invalid_payload",
                        "会议 DeepSeek 网页 bridge 返回无效结果。", true, false);
            }
            return parseBridgeResult(payload.getJSONObject("result"), userPrompt, startedAt,
                    !Boolean.FALSE.equals(body.getBoolean("enableSearch")));
        } catch (Exception e) {
            log.warn("会议 DeepSeek 网页 bridge 调用异常：{}", e.getMessage());
            BridgeLedgerLookup lookup = lookupBridgeLedger(cfg, body);
            if (lookup.result != null) {
                return parseBridgeResult(lookup.result, userPrompt, startedAt,
                        !Boolean.FALSE.equals(body.getBoolean("enableSearch")));
            }
            boolean definitelyPreSubmit = "NOT_FOUND".equals(lookup.status)
                    || "FAILED_PRE_SUBMIT".equals(lookup.status)
                    || ("UNREACHABLE".equals(lookup.status) && isDefinitelyPreSubmitTransport(e));
            if (definitelyPreSubmit) {
                return failure("bridge_error", "bridge_pre_submit_failed",
                        "会议 DeepSeek 网页 bridge 暂时不可用，且本次请求未提交。", false, true);
            }
            return failure("provider_outcome_unknown", "provider_outcome_unknown",
                    "会议 DeepSeek 网页 bridge 返回状态不确定；为避免重复提交，本次不会自动重试。",
                    true, false);
        }
    }

    private ChatResult parseBridgeResult(JSONObject result, String userPrompt, long startedAt,
                                         boolean enableSearch) {
        if (result == null) {
            return failure("missing_result", "missing_result",
                    "会议 DeepSeek 网页 bridge 未返回 result。", true, false);
        }
        if (!enableSearch) {
            JSONObject modeState = result.getJSONObject("modeState");
            if (modeState == null || !Boolean.TRUE.equals(modeState.getBoolean("modeVerified"))
                    || !Boolean.FALSE.equals(modeState.getBoolean("searchEnabled"))) {
                return failure("search_mode_unverified", "search_mode_unverified",
                        "会议模型推理未能证明联网搜索已关闭。", false, false);
            }
        }
        String contentStatus = firstNonBlank(result.getString("contentStatus"), "success");
        String contentNote = firstNonBlank(result.getString("contentNote"), "已通过 DeepSeek 网页 bridge 返回会议模型结果。");
        String completionReason = result.getString("completionReason");
        if (!enableSearch && (!"success".equalsIgnoreCase(contentStatus)
                || !StringUtils.hasText(completionReason)
                || !completionReason.startsWith("complete"))) {
            return failure("inference_incomplete", "inference_incomplete",
                    "会议模型推理结果未完整生成。", false, false);
        }
        if ("incomplete".equalsIgnoreCase(contentStatus)) {
            String note = "会议 DeepSeek 网页 bridge 回答未完整生成，completionReason="
                    + firstNonBlank(completionReason, "unknown");
            log.warn(note);
            return failure(contentStatus, "incomplete", note, false, false);
        }
        String answerText = result.getString("answerText");
        if (!StringUtils.hasText(answerText)) {
            String note = "会议 DeepSeek 网页 bridge 未返回正文，contentStatus="
                    + contentStatus + "，completionReason=" + firstNonBlank(completionReason, "unknown");
            log.warn(note);
            return failure(contentStatus, "empty_answer", note, false, false);
        }

        String queryLabel = buildQueryLabel(userPrompt);
        List<WebSearchService.SearchResult> searchResults = enableSearch
                ? parseSearchResults(result, answerText, queryLabel)
                : Collections.emptyList();
        List<String> searchQueries = searchResults.isEmpty()
                ? Collections.emptyList()
                : Collections.singletonList(queryLabel);
        long elapsed = System.currentTimeMillis() - startedAt;
        String modelNote = contentNote + " elapsedMs=" + elapsed;
        return new ChatResult(
                answerText,
                searchQueries,
                searchResults,
                "deepseek_web_bridge",
                contentStatus,
                contentNote,
                "success",
                modelNote
        );
    }

    private ChatResult failureFromPayload(int statusCode, JSONObject payload) {
        String ledgerStatus = normalize(payload == null ? null : payload.getString("ledgerStatus")).toUpperCase(Locale.ROOT);
        boolean providerOutcomeUnknown = payload != null
                && Boolean.TRUE.equals(payload.getBoolean("providerOutcomeUnknown"));
        if ("UNKNOWN".equals(ledgerStatus) || "IN_FLIGHT".equals(ledgerStatus)) {
            providerOutcomeUnknown = true;
        }
        Boolean retrySafe = payload != null && payload.containsKey("retrySafe")
                ? payload.getBoolean("retrySafe")
                : null;
        if (providerOutcomeUnknown || retrySafe == null) {
            retrySafe = false;
        }
        String errorCode = firstNonBlank(payload == null ? null : payload.getString("error"),
                "bridge_http_" + statusCode);
        String note = providerOutcomeUnknown
                ? "会议 DeepSeek 网页 bridge 返回状态不确定；为避免重复提交，本次不会自动重试。"
                : "会议 DeepSeek 网页 bridge 请求失败，HTTP " + statusCode + "。";
        return failure("bridge_http_" + statusCode, errorCode, note, providerOutcomeUnknown, retrySafe);
    }

    private BridgeLedgerLookup lookupBridgeLedger(AiModelProperties.DeepSeekWebBridgeConfig cfg, JSONObject body) {
        HttpUrl base = HttpUrl.parse(cfg.getBridgeUrl());
        if (base == null) {
            return BridgeLedgerLookup.status("UNAVAILABLE");
        }
        HttpUrl statusUrl = base.newBuilder()
                .encodedPath("/request-status")
                .query(null)
                .addQueryParameter("submissionKey", body.getString("submissionKey"))
                .addQueryParameter("requestDigest", body.getString("requestDigest"))
                .build();
        OkHttpClient statusClient = new OkHttpClient.Builder()
                .connectTimeout(Math.max(5, cfg.getConnectTimeoutSeconds()), TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        Request.Builder requestBuilder = new Request.Builder().url(statusUrl).get();
        if (StringUtils.hasText(cfg.getApiKey())) {
            requestBuilder.header("Authorization", "Bearer " + cfg.getApiKey().trim());
        }
        try (Response response = statusClient.newCall(requestBuilder.build()).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (response.code() == 404) {
                return BridgeLedgerLookup.status("NOT_FOUND");
            }
            JSONObject payload = parseObject(responseBody);
            String ledgerStatus = normalize(payload == null ? null : payload.getString("ledgerStatus"))
                    .toUpperCase(Locale.ROOT);
            if (response.isSuccessful() && payload != null && payload.getBooleanValue("ok")
                    && "SUCCESS".equals(ledgerStatus) && payload.getJSONObject("result") != null) {
                return BridgeLedgerLookup.success(payload.getJSONObject("result"));
            }
            return BridgeLedgerLookup.status(StringUtils.hasText(ledgerStatus) ? ledgerStatus : "UNAVAILABLE");
        } catch (Exception statusError) {
            log.warn("会议 DeepSeek 网页 bridge ledger 查询异常：{}", statusError.getMessage());
            return BridgeLedgerLookup.status("UNREACHABLE");
        }
    }

    private boolean isDefinitelyPreSubmitTransport(Exception error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.ConnectException || current instanceof java.net.UnknownHostException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private JSONObject parseObject(String raw) {
        try {
            return JSON.parseObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<WebSearchService.SearchResult> parseSearchResults(JSONObject result,
                                                                  String answerText,
                                                                  String queryLabel) {
        JSONArray links = result.getJSONArray("links");
        if (links == null || links.isEmpty()) {
            return Collections.emptyList();
        }
        List<WebSearchService.SearchResult> results = new ArrayList<>();
        WebSearchService.SearchResult summary = new WebSearchService.SearchResult();
        summary.source = "DeepSeek 网页智能搜索摘要";
        summary.snippet = answerText.length() > 12000 ? answerText.substring(0, 12000) + "..." : answerText;
        JSONObject first = links.getJSONObject(0);
        summary.url = first != null ? first.getString("href") : null;
        summary.withTrace("deepseek_web_bridge", "MODEL_SUMMARY", queryLabel, answerText);
        results.add(summary);

        for (int i = 0; i < links.size(); i++) {
            JSONObject item = links.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String href = item.getString("href");
            if (!StringUtils.hasText(href)) {
                continue;
            }
            WebSearchService.SearchResult searchResult = new WebSearchService.SearchResult();
            searchResult.source = firstNonBlank(item.getString("text"), href);
            searchResult.url = href;
            searchResult.snippet = "来自 DeepSeek 网页智能搜索引用来源";
            searchResult.withTrace("deepseek_web_bridge", "WEB_PAGE", queryLabel, item.toJSONString());
            results.add(searchResult);
        }
        return results;
    }

    private ChatResult failure(String searchStatus, String modelStatus, String note) {
        return failure(searchStatus, modelStatus, note, false, null);
    }

    private ChatResult failure(String searchStatus,
                               String modelStatus,
                               String note,
                               boolean providerOutcomeUnknown,
                               Boolean retrySafe) {
        return new ChatResult(
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                "deepseek_web_bridge",
                searchStatus,
                note,
                modelStatus,
                note,
                providerOutcomeUnknown,
                retrySafe
        );
    }

    private static final class BridgeLedgerLookup {
        private final String status;
        private final JSONObject result;

        private BridgeLedgerLookup(String status, JSONObject result) {
            this.status = status;
            this.result = result;
        }

        private static BridgeLedgerLookup status(String status) {
            return new BridgeLedgerLookup(status, null);
        }

        private static BridgeLedgerLookup success(JSONObject result) {
            return new BridgeLedgerLookup("SUCCESS", result);
        }
    }

    private String buildQuery(String systemPrompt, String userPrompt, Integer maxTokens) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(systemPrompt)) {
            builder.append("【系统指令】\n").append(systemPrompt.trim()).append("\n\n");
        }
        builder.append("【用户任务】\n").append(StringUtils.hasText(userPrompt) ? userPrompt.trim() : "").append("\n\n");
        builder.append("【输出约束】\n");
        builder.append("- 只输出本次任务需要的最终内容，不要解释你正在使用网页端。\n");
        builder.append("- 如果任务要求 JSON，必须输出可解析 JSON，不要包 Markdown 代码块。\n");
        if (maxTokens != null && maxTokens > 0) {
            builder.append("- 尽量将回答控制在约 ").append(maxTokens).append(" tokens 以内。\n");
        }
        return builder.toString();
    }

    private boolean shouldExpectJson(String systemPrompt, String userPrompt) {
        String combined = ((systemPrompt == null ? "" : systemPrompt) + "\n" + (userPrompt == null ? "" : userPrompt));
        String lower = combined.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("\\s+", "");
        return compact.contains("只输出json")
                || compact.contains("仅输出json")
                || compact.contains("返回json")
                || compact.contains("输出为json")
                || compact.contains("输出成json")
                || compact.contains("输出可解析json")
                || compact.contains("使用json格式")
                || compact.contains("以json格式")
                || compact.contains("json格式输出")
                || compact.contains("不要输出json以外")
                || compact.contains("除json外不要输出")
                || compact.contains("jsonobject")
                || compact.contains("jsonarray")
                || lower.contains("return json")
                || lower.contains("output json")
                || lower.contains("valid json")
                || lower.contains("json only")
                || lower.contains("respond with json")
                || lower.contains("as json")
                || lower.contains("json object")
                || lower.contains("json array");
    }

    private boolean isSearchExecutorCall(ModelEntry entry, String systemPrompt, String userPrompt) {
        if (entry != null
                && "claude".equalsIgnoreCase(entry.getApiFormat())
                && StringUtils.hasText(entry.getApiUrl())
                && entry.getApiUrl().contains("/anthropic/")
                && startsWithDeepSeekV4(entry.getModelId())) {
            return true;
        }
        String combined = ((systemPrompt == null ? "" : systemPrompt) + "\n" + (userPrompt == null ? "" : userPrompt));
        return combined.contains("联网搜索执行器")
                || combined.contains("只执行联网搜索")
                || combined.contains("只负责执行网页搜索")
                || combined.contains("检索证据");
    }

    private boolean startsWithDeepSeekV4(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            return false;
        }
        String normalized = modelId.toLowerCase(Locale.ROOT);
        return normalized.startsWith("deepseek-v4") || normalized.startsWith("deepseek-v4-flash");
    }

    private ModelEntry resolveEntryForRouting(String modelType) {
        String selector = normalize(modelType);
        if (StringUtils.hasText(selector)) {
            ModelEntry entry = modelRegistryStore.getByModelId(selector);
            if (entry != null) {
                return entry;
            }
        }
        return resolveDefaultEntry();
    }

    private ModelEntry resolveDefaultEntry() {
        String defaultModel = aiModelProperties != null ? aiModelProperties.getDefaultModel() : null;
        return StringUtils.hasText(defaultModel) ? modelRegistryStore.getByModelId(defaultModel) : null;
    }

    private boolean isEnabledDeepSeekApiEntry(ModelEntry entry) {
        return entry != null && entry.isEnabled() && isDeepSeekApiEntry(entry);
    }

    private boolean isDeepSeekApiEntry(ModelEntry entry) {
        return entry != null
                && StringUtils.hasText(entry.getApiUrl())
                && entry.getApiUrl().toLowerCase(Locale.ROOT).contains("api.deepseek.com");
    }

    private boolean isLegacyNonDeepSeekSelector(String selector) {
        String normalized = normalize(selector).toLowerCase(Locale.ROOT);
        for (String prefix : LEGACY_NON_DEEPSEEK_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeepSeekLikeSelector(String selector) {
        String normalized = normalize(selector).toLowerCase(Locale.ROOT);
        return normalized.equals("deepseek")
                || normalized.startsWith("deepseek-")
                || normalized.startsWith("deepseek_");
    }

    private String normalizeMode(String raw, String fallback) {
        String value = normalize(raw).toLowerCase(Locale.ROOT);
        if ("expert".equals(value) || "professional".equals(value)) {
            return "expert";
        }
        if ("fast".equals(value) || "quick".equals(value) || "default".equals(value)) {
            return "fast";
        }
        return fallback;
    }

    private AiModelProperties.DeepSeekWebBridgeConfig config() {
        if (aiModelProperties == null || aiModelProperties.getMeeting() == null) {
            return new AiModelProperties.DeepSeekWebBridgeConfig();
        }
        AiModelProperties.DeepSeekWebBridgeConfig cfg = aiModelProperties.getMeeting().getDeepseekWebBridge();
        return cfg != null ? cfg : new AiModelProperties.DeepSeekWebBridgeConfig();
    }

    private String buildQueryLabel(String userPrompt) {
        String compact = normalize(userPrompt).replaceAll("\\s+", " ");
        if (!StringUtils.hasText(compact)) {
            return "DeepSeek Web autonomous search";
        }
        return compact.length() > 160 ? compact.substring(0, 160) + "..." : compact;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
