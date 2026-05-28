package com.aimeeting.room.service.ai;

import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.dto.ChatResult;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * 通用 AI 客户端：从 ModelRegistryStore 查找配置，支持 OpenAI 兼容接口和 Claude 接口。
 * 优先级 @Order(0) 保证在旧客户端之前被路由器选中。
 */
@Slf4j
@Component
@Order(0)
public class GenericAiClient implements AiModelClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int EXPERIMENTAL_SEARCH_MAX_ATTEMPTS = 3;
    private static final long EXPERIMENTAL_SEARCH_RETRY_SLEEP_MILLIS = 1500L;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .protocols(java.util.Arrays.asList(okhttp3.Protocol.HTTP_1_1))  // 强制 HTTP/1.1，规避 HTTP/2 兼容问题
            .build();

    private final OkHttpClient experimentalSearchClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .protocols(java.util.Arrays.asList(okhttp3.Protocol.HTTP_1_1))
            .build();

    @Autowired
    private ModelRegistryStore registry;

    @Autowired
    private WebSearchService webSearch;

    @Override
    public boolean supports(String modelType) {
        ModelEntry e = registry.getByModelId(modelType);
        return e != null && e.isEnabled();
    }

    @Override
    public String chat(String modelType, String systemPrompt, String userPrompt) {
        return chat(modelType, systemPrompt, userPrompt, null);
    }

    public String chat(String modelType, String systemPrompt, String userPrompt, Integer maxTokens) {
        return chatWithEvidence(modelType, systemPrompt, userPrompt, maxTokens).content;
    }

    public ChatResult chatWithEvidence(String modelType, String systemPrompt, String userPrompt) {
        return chatWithEvidence(modelType, systemPrompt, userPrompt, null);
    }

    public ChatResult chatWithEvidence(String modelType, String systemPrompt, String userPrompt, Integer maxTokens) {
        ModelEntry entry = registry.getByModelId(modelType);
        if (entry == null || !entry.isEnabled()) {
            log.warn("Model {} not found or disabled in registry", modelType);
            return new ChatResult(null, null, null,
                    "unavailable", "missing_model", "当前模型未启用或不存在。",
                    "missing_model", "当前模型未启用或不存在。");
        }
        if ("claude".equalsIgnoreCase(entry.getApiFormat())) {
            return callClaudeWithEvidence(entry, systemPrompt, userPrompt, maxTokens);
        }
        return callOpenAiWithEvidence(entry, entry.getModelId(), systemPrompt, userPrompt, maxTokens,
                "model_only", "no_builtin_search", "本次未启用内置联网搜索。");
    }

    // ---- OpenAI 兼容格式 ----

    private ChatResult callOpenAiWithEvidence(ModelEntry entry, String modelType, String systemPrompt, String userPrompt,
                                              Integer maxTokens, String searchMode, String searchStatus, String searchNote) {
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
        }
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userPrompt);
        messages.add(user);

        JSONObject body = new JSONObject();
        body.put("model", modelType);
        body.put("messages", messages);
        body.put("temperature", 0.7);
        body.put("max_tokens", normalizeMaxTokens(maxTokens, 4096));

        // 直接调用，搜索在 MeetingDebateService Phase 1 中完成（Serper）
        String raw = callApiOnce(entry, body);
        if (raw == null) {
            return new ChatResult(null, null, null,
                    searchMode, searchStatus, searchNote,
                    "api_call_failed", "模型 API 调用失败、超时或返回非 2xx 状态。");
        }
        try {
            JSONObject resp = JSON.parseObject(raw);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("No choices in response for model={}", modelType);
                return new ChatResult(null, null, null,
                        searchMode, searchStatus, searchNote,
                        "empty_choices", "模型响应中未包含可解析的候选答案。");
            }
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
            if (content != null && !content.isEmpty()) {
                return new ChatResult(content, null, null,
                        searchMode, searchStatus, searchNote,
                        "success", "主模型已返回稳定正文。");
            }
        } catch (Exception e) {
            log.warn("Response parse failed for model={}: {}", modelType, e.getMessage());
            return new ChatResult(null, null, null,
                    searchMode, searchStatus, searchNote,
                    "response_parse_failed", "模型响应解析失败，未提取出可用正文。");
        }
        return new ChatResult(null, null, null,
                searchMode, searchStatus, searchNote,
                "blank_content", "模型返回了响应，但正文为空。");
    }

    private String callApiOnce(ModelEntry entry, JSONObject body) {
        String url = entry.getApiUrl();
        String modelId = body.getString("model");
        log.info("Calling API url={} model={}", url, modelId);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + entry.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            log.info("API response code={} bodyLen={}", response.code(), responseBody.length());
            if (!response.isSuccessful()) {
                log.warn("API error {} url={} body={}", response.code(), url, responseBody);
                return null;
            }
            return responseBody;
        } catch (Exception e) {
            log.warn("API call failed url={} error={}", url, e.getMessage());
            return null;
        }
    }

    // ---- Claude 原生格式 ----

    private String callClaude(ModelEntry entry, String systemPrompt, String userPrompt, Integer maxTokens) {
        JSONObject body = new JSONObject();
        body.put("model", entry.getModelId());
        body.put("max_tokens", normalizeMaxTokens(maxTokens, 4096));
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            body.put("system", systemPrompt);
        }
        JSONArray messages = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        body.put("messages", messages);

        Request request = new Request.Builder()
                .url(entry.getApiUrl())
                .header("x-api-key", entry.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                log.warn("Claude API error {}: {}", response.code(), responseBody);
                return null;
            }
            JSONObject resp = JSON.parseObject(responseBody);
            JSONArray content = resp.getJSONArray("content");
            if (content != null && !content.isEmpty()) {
                return content.getJSONObject(0).getString("text");
            }
            log.warn("No content in Claude response: {}", responseBody);
            return null;
        } catch (Exception e) {
            log.warn("Claude call failed ({}), using mock for model={}", e.getMessage(), entry.getModelId());
            return null;
        }
    }

    private ChatResult callClaudeWithEvidence(ModelEntry entry, String systemPrompt, String userPrompt, Integer maxTokens) {
        if (supportsExperimentalDeepSeekSearch(entry)) {
            log.info("Calling experimental DeepSeek built-in web_search model={}", entry.getModelId());
            ChatResult result = callClaudeWithExperimentalSearch(entry, systemPrompt, userPrompt, maxTokens);
            if (result != null && result.content != null && !result.content.isBlank()) {
                return result;
            }
            if (result != null && result.hasSearchEvidence()) {
                log.info("Experimental DeepSeek built-in web_search returned tool evidence without stable正文, modelStatus={}", result.modelStatus);
                return result;
            }
            String guardedUserPrompt = appendNoLiveSearchGuard(userPrompt);
            ModelEntry fallbackEntry = findExperimentalSearchFallbackEntry(entry);
            if (fallbackEntry != null) {
                log.info("Experimental DeepSeek web_search fallback to stable {} path for model={}",
                        fallbackEntry.getApiFormat(), entry.getModelId());
                if ("claude".equalsIgnoreCase(fallbackEntry.getApiFormat())) {
                    String plain = callClaude(fallbackEntry, systemPrompt, guardedUserPrompt, maxTokens);
                    return new ChatResult(plain, null, null,
                            "built_in_search", "fallback_to_stable",
                            "内置联网搜索超时或失败，已自动降级到稳定模型直连回答。当前未获得可核验的实时搜索结果。",
                            plain != null && !plain.isBlank() ? "success" : "fallback_model_failed",
                            plain != null && !plain.isBlank() ? "稳定回退模型已返回正文。" : "稳定回退模型也未返回可用正文。");
                }
                return callOpenAiWithEvidence(fallbackEntry, fallbackEntry.getModelId(), systemPrompt, guardedUserPrompt, maxTokens,
                        "built_in_search", "fallback_to_stable",
                        "内置联网搜索超时或失败，已自动降级到稳定模型直连回答。当前未获得可核验的实时搜索结果。");
            }
            log.warn("Experimental DeepSeek web_search failed and no stable fallback entry found for model={}", entry.getModelId());
            return new ChatResult(null, null, null,
                    "built_in_search", "timeout_without_fallback",
                    "内置联网搜索超时，且没有可用的稳定模型可回退。",
                    "timeout_without_fallback", "内置联网搜索超时，且没有可用稳定模型回退。");
        }
        String plain = callClaude(entry, systemPrompt, userPrompt, maxTokens);
        return new ChatResult(plain, null, null,
                "model_only", "no_builtin_search", "本次未启用内置联网搜索。",
                plain != null && !plain.isBlank() ? "success" : "api_call_failed",
                plain != null && !plain.isBlank() ? "主模型已返回稳定正文。" : "Claude/兼容接口调用失败、超时或正文为空。");
    }

    private ModelEntry findExperimentalSearchFallbackEntry(ModelEntry experimentalEntry) {
        if (experimentalEntry == null || experimentalEntry.getModelId() == null) {
            return null;
        }
        for (ModelEntry candidate : registry.getEnabled()) {
            if (candidate == null || candidate == experimentalEntry) {
                continue;
            }
            if (!experimentalEntry.getModelId().equals(candidate.getModelId())) {
                continue;
            }
            if ("claude".equalsIgnoreCase(candidate.getApiFormat())
                    && candidate.getApiUrl() != null
                    && candidate.getApiUrl().contains("/anthropic/")) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private boolean supportsExperimentalDeepSeekSearch(ModelEntry entry) {
        if (entry == null) {
            return false;
        }
        String modelId = entry.getModelId() != null ? entry.getModelId().toLowerCase() : "";
        return "claude".equalsIgnoreCase(entry.getApiFormat())
                && entry.getApiUrl() != null
                && entry.getApiUrl().contains("/anthropic/")
                && (modelId.startsWith("deepseek-v4") || modelId.startsWith("deepseek-v4-flash"));
    }

    private String appendNoLiveSearchGuard(String userPrompt) {
        String guard = "\n\n【联网搜索失败后的强约束】\n"
                + "当前没有拿到可核验的实时联网结果。\n"
                + "禁止编造“截至某日的最新价格、最新新闻、最新机构预测”这类具体实时数据。\n"
                + "如果没有已提供的真实搜索结果支撑，请明确写“未完成最新数据核验”，只做趋势判断或条件式分析。\n";
        return (userPrompt == null ? "" : userPrompt) + guard;
    }

    private ChatResult callClaudeWithExperimentalSearch(ModelEntry entry, String systemPrompt, String userPrompt, Integer maxTokens) {
        JSONObject body = new JSONObject();
        body.put("model", entry.getModelId());
        body.put("max_tokens", normalizeMaxTokens(maxTokens, 4096));
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            body.put("system", systemPrompt);
        }
        JSONArray messages = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        body.put("messages", messages);

        JSONArray tools = new JSONArray();
        JSONObject tool = new JSONObject();
        tool.put("type", "web_search_20250305");
        tool.put("name", "web_search");
        tool.put("max_uses", 3);
        tools.add(tool);
        body.put("tools", tools);

        Request request = new Request.Builder()
                .url(entry.getApiUrl())
                .header("x-api-key", entry.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        for (int attempt = 1; attempt <= EXPERIMENTAL_SEARCH_MAX_ATTEMPTS; attempt++) {
            try (Response response = experimentalSearchClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("Experimental DeepSeek search API error attempt {}/{} {}: {}",
                            attempt, EXPERIMENTAL_SEARCH_MAX_ATTEMPTS, response.code(), responseBody);
                    return null;
                }
                JSONObject resp = JSON.parseObject(responseBody);
                JSONArray content = resp.getJSONArray("content");
                if (content == null || content.isEmpty()) {
                    log.warn("No content in experimental DeepSeek search response attempt {}/{}",
                            attempt, EXPERIMENTAL_SEARCH_MAX_ATTEMPTS);
                    return null;
                }

                StringBuilder text = new StringBuilder();
                java.util.List<String> queries = new java.util.ArrayList<>();
                java.util.List<WebSearchService.SearchResult> results = new java.util.ArrayList<>();
                for (int i = 0; i < content.size(); i++) {
                    JSONObject block = content.getJSONObject(i);
                    String type = block.getString("type");
                    if ("text".equals(type)) {
                        String piece = block.getString("text");
                        if (piece != null && !piece.isBlank()) {
                            if (text.length() > 0) text.append("\n");
                            text.append(piece.trim());
                        }
                    } else if ("server_tool_use".equals(type)) {
                        JSONObject input = block.getJSONObject("input");
                        if (input != null) {
                            String query = input.getString("query");
                            if (query != null && !query.isBlank()) {
                                queries.add(query.trim());
                            }
                        }
                    } else if ("web_search_tool_result".equals(type)) {
                        JSONArray toolResults = block.getJSONArray("content");
                        if (toolResults == null) continue;
                        for (int j = 0; j < toolResults.size(); j++) {
                            JSONObject item = toolResults.getJSONObject(j);
                            if (!"web_search_result".equals(item.getString("type"))) continue;
                            WebSearchService.SearchResult result = new WebSearchService.SearchResult();
                            result.source = item.getString("title");
                            result.url = item.getString("url");
                            result.snippet = item.getString("title");
                            results.add(result);
                        }
                    }
                }
                String stopReason = resp.getString("stop_reason");
                String normalizedText = text.toString().trim();
                String note = results.isEmpty()
                        ? "已触发内置联网搜索，但未返回可展示的搜索结果列表。"
                        : "已触发内置联网搜索，并返回了实时搜索结果。";
                if (attempt > 1) {
                    note += " 本次结果来自超时后的重试。";
                }
                if (StringUtils.hasText(stopReason) && !"end_turn".equalsIgnoreCase(stopReason)) {
                    note += " stop_reason=" + stopReason + "。";
                }
                if (normalizedText.isEmpty() && (queries.isEmpty() && results.isEmpty())) {
                    log.warn("Experimental DeepSeek search returned no text and no evidence attempt {}/{}",
                            attempt, EXPERIMENTAL_SEARCH_MAX_ATTEMPTS);
                    return null;
                }
                log.info("Experimental DeepSeek built-in web_search success attempt {}/{} queries={} results={} stopReason={}",
                        attempt, EXPERIMENTAL_SEARCH_MAX_ATTEMPTS, queries.size(), results.size(), stopReason);
                return new ChatResult(normalizedText, queries, results,
                        "built_in_search", "success", note,
                        normalizedText.isEmpty() ? "tool_only" : "success",
                        normalizedText.isEmpty()
                                ? "内置联网搜索已返回工具证据，但正文为空。"
                                : "主模型已返回稳定正文。");
            } catch (Exception e) {
                boolean timeout = isRetryableExperimentalSearchException(e);
                if (timeout && attempt < EXPERIMENTAL_SEARCH_MAX_ATTEMPTS) {
                    log.warn("Experimental DeepSeek search attempt {}/{} timed out ({}), retrying",
                            attempt, EXPERIMENTAL_SEARCH_MAX_ATTEMPTS, e.getMessage());
                    sleepBeforeExperimentalRetry();
                    continue;
                }
                log.warn("Experimental DeepSeek search call failed on attempt {}/{} ({}), model={}, falling back",
                        attempt, EXPERIMENTAL_SEARCH_MAX_ATTEMPTS, e.getMessage(), entry.getModelId());
                return null;
            }
        }
        return null;
    }

    private int normalizeMaxTokens(Integer maxTokens, int defaultValue) {
        if (maxTokens == null || maxTokens <= 0) {
            return defaultValue;
        }
        return Math.min(maxTokens, defaultValue);
    }

    private boolean isRetryableExperimentalSearchException(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("timed out");
    }

    private void sleepBeforeExperimentalRetry() {
        try {
            Thread.sleep(EXPERIMENTAL_SEARCH_RETRY_SLEEP_MILLIS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
