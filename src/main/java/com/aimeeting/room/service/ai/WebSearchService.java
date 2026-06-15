package com.aimeeting.room.service.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 真实网络搜索服务。
 * 使用 Serper.dev（Google Search API，中国大陆可访问，有免费额度）。
 * 若未配置 API Key，则返回空列表，AI 继续使用训练知识生成依据。
 */
@Slf4j
@Service
public class WebSearchService {

    @Value("${ai.search.serper-api-key:}")
    private String serperApiKey;
    @Value("${ai.search.provider-order:tavily,deepseek-web,zhipu,serper}")
    private String providerOrder;
    @Value("${ai.search.tavily.api-key:}")
    private String tavilyApiKey;
    @Value("${ai.search.tavily.api-url:https://api.tavily.com/search}")
    private String tavilyApiUrl;
    @Value("${ai.search.tavily.search-depth:advanced}")
    private String tavilySearchDepth;
    @Value("${ai.search.tavily.include-answer:true}")
    private boolean tavilyIncludeAnswer;
    @Value("${ai.search.tavily.country:}")
    private String tavilyCountry;
    @Value("${ai.search.tavily.timeout-seconds:30}")
    private int tavilyTimeoutSeconds;
    @Value("${ai.search.zhipu.api-key:}")
    private String zhipuApiKey;
    @Value("${ai.search.zhipu.api-url:https://open.bigmodel.cn/api/paas/v4/web_search}")
    private String zhipuApiUrl;
    @Value("${ai.search.zhipu.digest-enabled:true}")
    private boolean zhipuDigestEnabled;
    @Value("${ai.search.zhipu.digest-api-url:https://open.bigmodel.cn/api/paas/v4/chat/completions}")
    private String zhipuDigestApiUrl;
    @Value("${ai.search.zhipu.digest-model:glm-4-flash-250414}")
    private String zhipuDigestModel;
    @Value("${ai.search.zhipu.digest-timeout-seconds:60}")
    private int zhipuDigestTimeoutSeconds;
    @Value("${ai.search.zhipu.search-engine:search_std}")
    private String zhipuSearchEngine;
    @Value("${ai.search.zhipu.content-size:medium}")
    private String zhipuContentSize;
    @Value("${ai.search.zhipu.recency-filter:noLimit}")
    private String zhipuRecencyFilter;
    @Value("${ai.search.zhipu.timeout-seconds:60}")
    private int zhipuTimeoutSeconds;
    @Value("${ai.search.deepseek-web.enabled:false}")
    private boolean deepseekWebEnabled;
    @Value("${ai.search.deepseek-web.node-command:node}")
    private String deepseekWebNodeCommand;
    @Value("${ai.search.deepseek-web.script-path:scripts/deepseek-web-search.js}")
    private String deepseekWebScriptPath;
    @Value("${ai.search.deepseek-web.allow-script-fallback:false}")
    private boolean deepseekWebAllowScriptFallback;
    @Value("${ai.search.deepseek-web.timeout-seconds:150}")
    private int deepseekWebTimeoutSeconds;
    @Value("${ai.search.deepseek-web.bridge-url:http://127.0.0.1:8789/search}")
    private String deepseekWebBridgeUrl;
    @Value("${ai.search.deepseek-web.connect-timeout-seconds:10}")
    private int deepseekWebConnectTimeoutSeconds;
    @Value("${ai.search.deepseek-web.read-timeout-seconds:180}")
    private int deepseekWebReadTimeoutSeconds;

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    @Autowired
    public WebSearchService() {
        this.httpClient = new OkHttpClient();
    }

    private final ThreadLocal<SearchMeta> currentSearchMeta = new ThreadLocal<>();

    public boolean isEnabled() {
        return hasTavily()
                || isDeepSeekWebEnabled()
                || hasZhipuWebSearch()
                || (serperApiKey != null && !serperApiKey.isBlank());
    }

    public boolean isDeepSeekWebSearchEnabled() {
        return isDeepSeekWebEnabled();
    }

    public boolean isDeepSeekWebBridgeReachable() {
        if (!isDeepSeekWebEnabled()) {
            return false;
        }
        try {
            OkHttpClient bridgeClient = httpClient.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url(resolveDeepSeekWebBridgeHealthUrl())
                    .get()
                    .build();
            try (Response response = bridgeClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return false;
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                JSONObject payload = parseJsonObject(responseBody);
                return payload != null && payload.getBooleanValue("ok");
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public void beginSession() {
        currentSearchMeta.set(new SearchMeta());
    }

    public SearchMeta consumeSessionMeta() {
        SearchMeta meta = currentSearchMeta.get();
        currentSearchMeta.remove();
        return meta;
    }

    public SearchMeta peekSessionMeta() {
        return currentSearchMeta.get();
    }

    /**
     * 用给定关键词做 Google 搜索，返回前 N 条结果摘要。
     * 失败时静默返回空列表（不影响议员正常发言）。
     */
    public List<SearchResult> search(String query, int maxResults) {
        return search(query, maxResults, null);
    }

    public List<SearchResult> search(String query, int maxResults, List<String> preferredProviders) {
        return search(query, maxResults, preferredProviders, false);
    }

    public List<SearchResult> search(String query, int maxResults, List<String> preferredProviders, boolean isolatedDeepSeekSession) {
        if (!isEnabled()) return Collections.emptyList();
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isEmpty()) return Collections.emptyList();

        SiteDirective directive = extractSiteDirectives(normalizedQuery);
        if (!directive.domains.isEmpty()) {
            log.info("Search targeted to domains {} (from site: directive)", directive.domains);
        }

        for (String provider : resolveProviderOrder(preferredProviders)) {
            log.info("Search '{}' trying provider {}", normalizedQuery, provider);
            List<SearchResult> providerResults = searchViaProvider(provider, directive, maxResults, isolatedDeepSeekSession);
            if (!providerResults.isEmpty()) {
                return copyResults(providerResults, maxResults);
            }
        }
        markMeta("none", "provider_exhausted", buildProviderExhaustedNote());
        return Collections.emptyList();
    }

    /**
     * 解析 query 中的 site: 操作符，实现「靠 prompt 指定定向搜索」。
     * 例：「工业富联 技术指标 site:eastmoney.com」→ 定向到 eastmoney.com。
     * Tavily 用 include_domains 严格定向；Google/Serper 原生支持 site:，保留原串。
     */
    private SiteDirective extractSiteDirectives(String normalizedQuery) {
        SiteDirective directive = new SiteDirective();
        directive.rawQuery = normalizedQuery;
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            directive.strippedQuery = normalizedQuery;
            return directive;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)site:([^\\s]+)")
                .matcher(normalizedQuery);
        while (matcher.find()) {
            String domain = cleanDomainToken(matcher.group(1));
            if (domain != null && !directive.domains.contains(domain)) {
                directive.domains.add(domain);
            }
        }
        directive.strippedQuery = matcher.replaceAll("").trim().replaceAll("\\s+", " ");
        if (directive.strippedQuery.isBlank()) {
            directive.strippedQuery = normalizedQuery;
        }
        return directive;
    }

    private String cleanDomainToken(String token) {
        if (token == null) return null;
        String d = token.trim().toLowerCase();
        int scheme = d.indexOf("://");
        if (scheme >= 0) d = d.substring(scheme + 3);
        if (d.startsWith("www.")) d = d.substring(4);
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        d = d.replaceAll("[，,。；;]+$", "");
        return d.isBlank() ? null : d;
    }

    private static class SiteDirective {
        String rawQuery;
        String strippedQuery;
        final List<String> domains = new ArrayList<>();
    }

    private List<String> resolveProviderOrder() {
        return resolveProviderOrder(null);
    }

    private List<String> resolveProviderOrder(List<String> preferredProviders) {
        List<String> providers = new ArrayList<>();
        if (preferredProviders != null) {
            for (String token : preferredProviders) {
                String normalized = token == null ? "" : token.trim().toLowerCase();
                if (!normalized.isBlank() && !providers.contains(normalized)) {
                    providers.add(normalized);
                }
            }
            if (!providers.isEmpty()) {
                return providers;
            }
        }
        if (providerOrder != null && !providerOrder.isBlank()) {
            for (String token : providerOrder.split(",")) {
                String normalized = token == null ? "" : token.trim().toLowerCase();
                if (!normalized.isBlank() && !providers.contains(normalized)) {
                    providers.add(normalized);
                }
            }
        }
        if (providers.isEmpty()) {
            providers.add("tavily");
            providers.add("deepseek-web");
            providers.add("zhipu");
            providers.add("serper");
        }
        return providers;
    }

    private List<SearchResult> searchViaProvider(String provider, SiteDirective directive, int maxResults, boolean isolatedDeepSeekSession) {
        boolean targeted = !directive.domains.isEmpty();
        // Tavily 用 include_domains 严格定向；zhipu/deepseek 不支持 site:，传去掉操作符的查询词。
        String cleanQuery = directive.strippedQuery;
        // Google/Serper 原生支持 site:，保留原始查询串。
        String googleQuery = directive.rawQuery;
        if ("tavily".equals(provider) || "tavily-web".equals(provider)) {
            if (!hasTavily()) {
                markProviderSkipped("tavily", "未配置 TAVILY_API_KEY。");
                return Collections.emptyList();
            }
            return searchViaTavily(cleanQuery, maxResults, targeted ? directive.domains : null);
        }
        if ("deepseek-web".equals(provider) || "deepseek_web".equals(provider) || "deepseek".equals(provider)) {
            return searchViaDeepSeekProvider(cleanQuery, maxResults, isolatedDeepSeekSession);
        }
        if ("zhipu".equals(provider) || "zhipu-web".equals(provider) || "zhipu_web".equals(provider)) {
            return searchViaZhipuWebSearch(cleanQuery, maxResults);
        }
        if ("serper".equals(provider)) {
            return searchViaSerperIfConfigured(googleQuery, maxResults);
        }
        return Collections.emptyList();
    }

    private List<SearchResult> searchViaDeepSeekProvider(String normalizedQuery, int maxResults, boolean isolatedDeepSeekSession) {
        if (!isDeepSeekWebEnabled()) {
            markProviderSkipped("deepseek_web_bridge", "DeepSeek 网页搜索当前未启用。");
            return Collections.emptyList();
        }
        List<SearchResult> webResults = searchViaDeepSeekWebBridge(normalizedQuery, maxResults, isolatedDeepSeekSession);
        if (webResults.isEmpty() && deepseekWebAllowScriptFallback) {
            webResults = searchViaDeepSeekWebScript(normalizedQuery, maxResults);
        }
        return webResults;
    }

    private List<SearchResult> searchViaSerperIfConfigured(String normalizedQuery, int maxResults) {
        if (serperApiKey == null || serperApiKey.isBlank()) {
            markProviderSkipped("serper", "未配置 Serper API Key。");
            return Collections.emptyList();
        }
        return searchViaSerper(normalizedQuery, maxResults);
    }

    private boolean hasTavily() {
        return tavilyApiKey != null && !tavilyApiKey.isBlank();
    }

    /**
     * Tavily 搜索。中文友好，作为默认优先 provider。
     * includeDomains 非空时执行定向搜索（仅检索指定站点）。
     */
    public List<SearchResult> searchViaTavily(String query, int maxResults, List<String> includeDomains) {
        if (!hasTavily()) {
            markProviderSkipped("tavily", "未配置 TAVILY_API_KEY。");
            return Collections.emptyList();
        }
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONObject body = new JSONObject();
            body.put("api_key", tavilyApiKey);
            body.put("query", normalizedQuery);
            body.put("search_depth", tavilySearchDepth);
            body.put("max_results", Math.max(1, Math.min(maxResults, 10)));
            body.put("include_answer", tavilyIncludeAnswer);
            if (tavilyCountry != null && !tavilyCountry.isBlank()) {
                body.put("country", tavilyCountry.trim());
            }
            if (includeDomains != null && !includeDomains.isEmpty()) {
                JSONArray domains = new JSONArray();
                for (String d : includeDomains) {
                    if (d != null && !d.isBlank()) {
                        domains.add(d.trim());
                    }
                }
                if (!domains.isEmpty()) {
                    body.put("include_domains", domains);
                }
            }

            OkHttpClient tavilyClient = httpClient.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(Math.max(20, tavilyTimeoutSeconds), TimeUnit.SECONDS)
                    .callTimeout(Math.max(25, tavilyTimeoutSeconds + 5L), TimeUnit.SECONDS)
                    .build();
            long startedAt = System.currentTimeMillis();
            Request request = new Request.Builder()
                    .url(tavilyApiUrl)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                    .build();

            try (Response response = tavilyClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("Tavily search failed: {} {}", response.code(), truncate(responseBody, 600));
                    markMeta("tavily", resolveTavilyErrorStatus(response.code()), resolveTavilyErrorNote(response.code(), responseBody));
                    return Collections.emptyList();
                }
                JSONObject payload = parseJsonObject(responseBody);
                JSONArray organic = payload != null ? payload.getJSONArray("results") : null;
                String answer = payload != null ? payload.getString("answer") : null;

                List<SearchResult> results = new ArrayList<>();
                if (answer != null && !answer.isBlank()) {
                    SearchResult summary = new SearchResult();
                    summary.source = "Tavily 智能摘要";
                    summary.snippet = answer.length() > 12000 ? answer.substring(0, 12000) + "…" : answer;
                    summary.url = (organic != null && !organic.isEmpty()) ? organic.getJSONObject(0).getString("url") : null;
                    results.add(summary);
                }
                if (organic != null) {
                    for (int i = 0; i < organic.size() && results.size() < maxResults + 1; i++) {
                        JSONObject item = organic.getJSONObject(i);
                        if (item == null) continue;
                        String content = item.getString("content");
                        String title = item.getString("title");
                        if ((content == null || content.isBlank()) && (title == null || title.isBlank())) continue;
                        SearchResult r = new SearchResult();
                        r.source = title != null && !title.isBlank() ? title : "Tavily 搜索结果";
                        r.snippet = content;
                        r.url = item.getString("url");
                        results.add(r);
                    }
                }
                if (results.isEmpty()) {
                    markMeta("tavily", "empty", includeDomains != null && !includeDomains.isEmpty()
                            ? "Tavily 定向搜索未在指定站点检索到有效结果。"
                            : "Tavily 未返回可用结果。");
                    return Collections.emptyList();
                }
                enrichMeta(maxResults, results.size(), System.currentTimeMillis() - startedAt, null, null);
                log.info("Tavily search '{}' -> {} results", normalizedQuery, results.size());
                markMeta("tavily", "success", includeDomains != null && !includeDomains.isEmpty()
                        ? "已通过 Tavily 定向搜索指定站点并返回中文结果。"
                        : "已通过 Tavily 返回中文搜索结果（含智能摘要）。");
                return results;
            }
        } catch (Exception e) {
            log.warn("Tavily search error for '{}': {}", normalizedQuery, e.getMessage());
            markMeta("tavily", "error", "Tavily 搜索失败：" + e.getMessage());
            return Collections.emptyList();
        }
    }

    private String resolveTavilyErrorStatus(int code) {
        if (code == 401 || code == 403) return "unauthorized";
        if (code == 429) return "rate_limited";
        if (code == 400) return "bad_request";
        return "error";
    }

    private String resolveTavilyErrorNote(int code, String responseBody) {
        if (code == 401 || code == 403) return "Tavily API Key 无效或无权访问。";
        if (code == 429) return "Tavily 当前触发限流，请稍后重试。";
        if (code == 400) return "Tavily 搜索请求参数无效。";
        String raw = responseBody == null ? "" : responseBody;
        return raw.isBlank() ? "Tavily 搜索请求失败。" : "Tavily 搜索失败：" + truncate(raw, 200);
    }

    private boolean hasZhipuWebSearch() {
        return zhipuApiKey != null && !zhipuApiKey.isBlank();
    }

    private List<SearchResult> searchViaZhipuWebSearch(String normalizedQuery, int maxResults) {
        if (!hasZhipuWebSearch()) {
            markProviderSkipped("zhipu_web_search", "未配置 ZHIPU_API_KEY。");
            return Collections.emptyList();
        }
        try {
            JSONObject body = new JSONObject();
            body.put("search_query", normalizedQuery);
            body.put("search_engine", zhipuSearchEngine);
            body.put("search_intent", false);
            body.put("count", Math.max(1, Math.min(maxResults, 10)));
            body.put("search_recency_filter", zhipuRecencyFilter);
            body.put("content_size", zhipuContentSize);

            OkHttpClient zhipuClient = httpClient.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(Math.max(20, zhipuTimeoutSeconds), TimeUnit.SECONDS)
                    .callTimeout(Math.max(25, zhipuTimeoutSeconds + 5L), TimeUnit.SECONDS)
                    .build();
            long startedAt = System.currentTimeMillis();
            Request request = new Request.Builder()
                    .url(zhipuApiUrl)
                    .header("Authorization", "Bearer " + zhipuApiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                    .build();

            try (Response response = zhipuClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String note = resolveZhipuErrorNote(response.code(), responseBody);
                    log.warn("Zhipu web search failed: {} {}", response.code(), truncate(responseBody, 600));
                    markMeta("zhipu_web_search", resolveZhipuErrorStatus(response.code(), responseBody), note);
                    return Collections.emptyList();
                }
                JSONObject payload = parseJsonObject(responseBody);
                JSONArray searchResults = payload != null ? payload.getJSONArray("search_result") : null;
                if (searchResults == null || searchResults.isEmpty()) {
                    markMeta("zhipu_web_search", "empty", "智谱联网搜索未返回可用结果。");
                    return Collections.emptyList();
                }

                List<SearchResult> results = new ArrayList<>();
                for (int i = 0; i < searchResults.size() && results.size() < maxResults; i++) {
                    JSONObject item = searchResults.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String title = item.getString("title");
                    String content = item.getString("content");
                    String link = item.getString("link");
                    String media = item.getString("media");
                    String publishDate = item.getString("publish_date");
                    if ((title == null || title.isBlank()) && (content == null || content.isBlank())) {
                        continue;
                    }
                    SearchResult result = new SearchResult();
                    result.source = joinNonBlank(" | ", media, title);
                    if (result.source == null || result.source.isBlank()) {
                        result.source = title != null && !title.isBlank() ? title : "智谱联网搜索结果";
                    }
                    result.snippet = content;
                    if (publishDate != null && !publishDate.isBlank()) {
                        String prefix = "发布日期：" + publishDate;
                        result.snippet = result.snippet == null || result.snippet.isBlank()
                                ? prefix
                                : prefix + "；" + result.snippet;
                    }
                    result.url = (link != null && !link.isBlank())
                            ? link
                            : buildFallbackSearchUrl(title, media, content, normalizedQuery);
                    results.add(result);
                }

                enrichMeta(
                        maxResults,
                        results.size(),
                        System.currentTimeMillis() - startedAt,
                        null,
                        null
                );
                log.info("Zhipu web search '{}' -> {} results", normalizedQuery, results.size());
                markMeta("zhipu_web_search", "success", "已通过智谱 Web Search API 返回结构化搜索结果。");
                return results;
            }
        } catch (Exception e) {
            log.warn("Zhipu web search error for '{}': {}", normalizedQuery, e.getMessage());
            markMeta("zhipu_web_search", "error", "智谱联网搜索异常：" + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchResult> searchViaSerper(String normalizedQuery, int maxResults) {
        try {
            JSONObject body = new JSONObject();
            body.put("q", normalizedQuery);
            body.put("num", maxResults);
            body.put("gl", "cn");   // 偏向中文结果
            body.put("hl", "zh-cn");

            Request request = new Request.Builder()
                    .url("https://google.serper.dev/search")
                    .header("X-API-KEY", serperApiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Serper search failed: {} {}", response.code(), response.message());
                    return Collections.emptyList();
                }
                JSONObject resp = JSON.parseObject(response.body().string());
                JSONArray organic = resp.getJSONArray("organic");
                if (organic == null || organic.isEmpty()) return Collections.emptyList();

                List<SearchResult> results = new ArrayList<>();
                for (int i = 0; i < Math.min(organic.size(), maxResults); i++) {
                    JSONObject item = organic.getJSONObject(i);
                    SearchResult r = new SearchResult();
                    r.source  = item.getString("title");
                    r.snippet = item.getString("snippet");
                    r.url     = item.getString("link");
                    if (r.snippet != null && !r.snippet.isBlank()) results.add(r);
                }
                log.info("Serper search '{}' -> {} results", normalizedQuery, results.size());
                markMeta("serper", "success", "DeepSeek 网页搜索不可用，已使用 Serper 返回结果。");
                return copyResults(results, maxResults);
            }
        } catch (Exception e) {
            log.warn("Serper search error for '{}': {}", normalizedQuery, e.getMessage());
            markMeta("serper", "error", "Serper 搜索失败：" + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchResult> searchViaDeepSeekWebBridge(String normalizedQuery, int maxResults, boolean isolatedDeepSeekSession) {
        try {
            JSONObject body = new JSONObject();
            body.put("query", normalizedQuery);
            body.put("isolatedSession", isolatedDeepSeekSession);
            body.put("closeTargetAfter", isolatedDeepSeekSession);
            OkHttpClient bridgeClient = httpClient.newBuilder()
                    .connectTimeout(Math.max(5, deepseekWebConnectTimeoutSeconds), TimeUnit.SECONDS)
                    .readTimeout(Math.max(30, deepseekWebReadTimeoutSeconds), TimeUnit.SECONDS)
                    .build();
            long startedAt = System.currentTimeMillis();
            Request request = new Request.Builder()
                    .url(deepseekWebBridgeUrl)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                    .build();
            try (Response response = bridgeClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    JSONObject errorPayload = parseJsonObject(responseBody);
                    String status = resolveBridgeErrorStatus(response.code(), errorPayload);
                    String note = resolveBridgeErrorNote(response.code(), errorPayload);
                    log.warn("DeepSeek web bridge search failed: {} {}", response.code(), truncate(responseBody, 600));
                    markMeta("deepseek_web_bridge", status, note);
                    return Collections.emptyList();
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                JSONObject payload = JSON.parseObject(responseBody);
                if (payload == null || !payload.getBooleanValue("ok")) {
                    markMeta("deepseek_web_bridge", "invalid_payload", "DeepSeek 网页搜索 bridge 返回无效结果。");
                    return Collections.emptyList();
                }
                JSONObject result = payload.getJSONObject("result");
                String contentStatus = result != null ? result.getString("contentStatus") : null;
                List<SearchResult> parsed = parseDeepSeekWebResult(result, maxResults, shouldIncludeSummary(result));
                enrichMeta(
                        maxResults,
                        result != null ? result.getInteger("searchCount") : null,
                        result != null ? result.getLong("providerTimingMs") : null,
                        result != null ? result.getJSONObject("modeState") : null,
                        System.currentTimeMillis() - startedAt
                );
                enrichContentMeta(result);
                if (!parsed.isEmpty()) {
                    String note = result != null && result.getString("contentNote") != null
                            ? result.getString("contentNote")
                            : "已通过本地 DeepSeek 网页搜索 bridge 返回结果。";
                    markMeta("deepseek_web_bridge", contentStatus == null ? "success" : contentStatus, note);
                } else if (result != null) {
                    String note = result.getString("contentNote");
                    markMeta("deepseek_web_bridge", contentStatus == null ? "empty" : contentStatus, note != null ? note : "DeepSeek 网页搜索未返回可用内容。");
                }
                return parsed;
            }
        } catch (Exception e) {
            log.warn("DeepSeek web bridge error for '{}': {}", normalizedQuery, e.getMessage());
            markMeta("deepseek_web_bridge", "error", "DeepSeek 网页搜索 bridge 异常：" + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchResult> searchViaDeepSeekWebScript(String normalizedQuery, int maxResults) {
        try {
            Path scriptPath = resolveScriptPath();
            if (!scriptPath.toFile().exists()) {
                log.warn("DeepSeek web search script not found: {}", scriptPath);
                return Collections.emptyList();
            }
            ProcessBuilder builder = new ProcessBuilder(
                    deepseekWebNodeCommand,
                    scriptPath.toString(),
                    normalizedQuery
            );
            builder.directory(new File(System.getProperty("user.dir")));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(Math.max(30, deepseekWebTimeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("DeepSeek web search timed out after {}s", deepseekWebTimeoutSeconds);
                markMeta("deepseek_web", "timeout", "DeepSeek 网页搜索超时。");
                return Collections.emptyList();
            }
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                output = sb.toString().trim();
            }
            if (process.exitValue() != 0 || output.isBlank()) {
                log.warn("DeepSeek web search failed, exitCode={}, output={}", process.exitValue(), truncate(output, 600));
                markMeta("deepseek_web_script", "error", "DeepSeek 网页搜索脚本执行失败。");
                return Collections.emptyList();
            }
            JSONObject response = JSON.parseObject(output);
            if (response == null) {
                markMeta("deepseek_web_script", "empty", "DeepSeek 网页搜索未返回可解析结果。");
                return Collections.emptyList();
            }
            List<SearchResult> results = parseDeepSeekWebResult(response, maxResults, shouldIncludeSummary(response));
            log.info("DeepSeek web search '{}' -> {} synthetic results", normalizedQuery, results.size());
            enrichContentMeta(response);
            String contentStatus = response.getString("contentStatus");
            String contentNote = response.getString("contentNote");
            markMeta("deepseek_web_script", contentStatus == null ? "success" : contentStatus,
                    contentNote != null ? contentNote : "已通过 DeepSeek 网页版智能搜索脚本返回结果。");
            return results;
        } catch (Exception e) {
            log.warn("DeepSeek web search error for '{}': {}", normalizedQuery, e.getMessage());
            markMeta("deepseek_web_script", "error", "DeepSeek 网页搜索异常：" + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchResult> parseDeepSeekWebResult(JSONObject response, int maxResults, boolean includeSummary) {
        if (response == null) {
            return Collections.emptyList();
        }
        String answerText = response.getString("answerText");
        JSONArray links = response.getJSONArray("links");
        List<SearchResult> results = new ArrayList<>();
        if (includeSummary && answerText != null && !answerText.isBlank()) {
            SearchResult summary = new SearchResult();
            summary.source = "DeepSeek 网页智能搜索摘要";
            summary.snippet = answerText.length() > 12000 ? answerText.substring(0, 12000) + "…" : answerText;
            summary.url = links != null && !links.isEmpty() ? links.getJSONObject(0).getString("href") : null;
            results.add(summary);
        }
        if (links != null) {
            for (int i = 0; i < links.size() && results.size() < maxResults; i++) {
                JSONObject item = links.getJSONObject(i);
                if (item == null) continue;
                String href = item.getString("href");
                if (href == null || href.isBlank()) continue;
                SearchResult result = new SearchResult();
                result.source = item.getString("text");
                if (result.source == null || result.source.isBlank()) {
                    result.source = href;
                }
                result.snippet = "来自 DeepSeek 网页智能搜索引用来源";
                result.url = href;
                results.add(result);
            }
        }
        return results;
    }

    /** 拼接搜索结果为可注入 prompt 的文本（含 URL 用于引用） */
    public String formatResultsForPrompt(List<SearchResult> results) {
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(String.format("[%d] %s\n    摘要：%s\n    来源：%s\n",
                    i + 1, r.source, r.snippet,
                    r.url != null ? r.url : "（无链接）"));
        }
        return sb.toString();
    }

    public ResearchDigest buildResearchDigest(String topic,
                                              String intent,
                                              Integer targetYear,
                                              List<String> queries,
                                              List<SearchResult> results) {
        if (!zhipuDigestEnabled || !hasZhipuWebSearch() || results == null || results.isEmpty()) {
            return null;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("model", zhipuDigestModel);
            body.put("temperature", 0.1);
            body.put("max_tokens", 1200);

            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是联网研究代理。你只能依据给定网页搜索结果做压缩总结、时效判断和交叉验证判断，不得补充外部常识，不得编造最新数据。输出必须是单个 JSON 对象。");
            messages.add(sys);

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", buildDigestPrompt(topic, intent, targetYear, queries, results));
            messages.add(user);
            body.put("messages", messages);

            OkHttpClient digestClient = httpClient.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(Math.max(20, zhipuDigestTimeoutSeconds), TimeUnit.SECONDS)
                    .callTimeout(Math.max(25, zhipuDigestTimeoutSeconds + 5L), TimeUnit.SECONDS)
                    .build();
            long startedAt = System.currentTimeMillis();
            Request request = new Request.Builder()
                    .url(zhipuDigestApiUrl)
                    .header("Authorization", "Bearer " + zhipuApiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                    .build();
            try (Response response = digestClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String note = "智谱研究摘要代理失败：" + resolveZhipuErrorNote(response.code(), responseBody);
                    log.warn("Zhipu digest failed: {} {}", response.code(), truncate(responseBody, 600));
                    markDigestMeta(resolveZhipuErrorStatus(response.code(), responseBody), note, null);
                    return null;
                }
                JSONObject payload = parseJsonObject(responseBody);
                String content = extractChatContent(payload);
                ResearchDigest digest = parseResearchDigest(content, topic, intent, targetYear, results);
                if (digest == null || digest.summary == null || digest.summary.isBlank()) {
                    markDigestMeta("invalid_payload", "智谱研究摘要代理未返回可解析的结构化结论。", null);
                    return null;
                }
                digest.generatedBy = zhipuDigestModel;
                digest.generatedAt = System.currentTimeMillis();
                digest.sourceCount = results.size();
                digest.queryCount = queries != null ? queries.size() : 0;
                markDigestMeta("success",
                        "已通过智谱免费模型生成结构化研究摘要，向上层返回压缩后的结论与来源映射。",
                        digest);
                SearchMeta meta = currentSearchMeta.get();
                if (meta != null) {
                    meta.digestTimingMs = System.currentTimeMillis() - startedAt;
                }
                return digest.copy();
            }
        } catch (Exception e) {
            log.warn("Zhipu digest error: {}", e.getMessage());
            markDigestMeta("error", "智谱研究摘要代理异常：" + e.getMessage(), null);
            return null;
        }
    }

    public String formatResearchDigestForPrompt(ResearchDigest digest) {
        if (digest == null || digest.summary == null || digest.summary.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【研究摘要代理结论】\n");
        sb.append("结论摘要：").append(digest.summary.trim()).append("\n");
        if (digest.verificationLevel != null || digest.verificationNote != null) {
            sb.append("交叉验证：")
                    .append(firstNonBlank(digest.verificationLevel, "未标记"));
            if (digest.verificationNote != null && !digest.verificationNote.isBlank()) {
                sb.append("；").append(digest.verificationNote.trim());
            }
            sb.append("\n");
        }
        if (digest.freshnessNote != null && !digest.freshnessNote.isBlank()) {
            sb.append("时效判断：").append(digest.freshnessNote.trim()).append("\n");
        }
        int claimLimit = Math.min(digest.keyClaims.size(), 3);
        if (claimLimit > 0) {
            sb.append("【核心论据】\n");
            for (int i = 0; i < claimLimit; i++) {
                ResearchClaim claim = digest.keyClaims.get(i);
                sb.append(i + 1).append(". ").append(claim.claim != null ? claim.claim.trim() : "（未命名论据）");
                if (claim.supportLevel != null && !claim.supportLevel.isBlank()) {
                    sb.append("（支持度：").append(claim.supportLevel.trim()).append("）");
                }
                if (!claim.sourceIndexes.isEmpty()) {
                    sb.append(" 来源:");
                    for (Integer index : claim.sourceIndexes) {
                        sb.append("[").append(index).append("]");
                    }
                }
                sb.append("\n");
            }
        }
        int sourceLimit = Math.min(digest.sourceRefs.size(), 4);
        if (sourceLimit > 0) {
            sb.append("【来源索引】\n");
            for (int i = 0; i < sourceLimit; i++) {
                SourceRef ref = digest.sourceRefs.get(i);
                sb.append("[").append(ref.index).append("] ")
                        .append(firstNonBlank(ref.title, "来源未标记"));
                if (ref.domain != null && !ref.domain.isBlank()) {
                    sb.append(" | ").append(ref.domain.trim());
                }
                if (ref.publishDate != null && !ref.publishDate.isBlank()) {
                    sb.append(" | ").append(ref.publishDate.trim());
                }
                sb.append("\n");
            }
        }
        if (digest.caution != null && !digest.caution.isBlank()) {
            sb.append("谨慎项：").append(digest.caution.trim()).append("\n");
        }
        return sb.toString();
    }

    public static class SearchResult {
        public String source;
        public String snippet;
        public String url;
    }

    public static class ResearchDigest {
        public String topic;
        public String intent;
        public Integer targetYear;
        public String summary;
        public String verificationLevel;
        public String verificationNote;
        public String freshnessNote;
        public String caution;
        public String generatedBy;
        public Long generatedAt;
        public Integer sourceCount;
        public Integer queryCount;
        public final List<ResearchClaim> keyClaims = new ArrayList<>();
        public final List<SourceRef> sourceRefs = new ArrayList<>();

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("topic", topic);
            obj.put("intent", intent);
            obj.put("targetYear", targetYear);
            obj.put("summary", summary);
            obj.put("verificationLevel", verificationLevel);
            obj.put("verificationNote", verificationNote);
            obj.put("freshnessNote", freshnessNote);
            obj.put("caution", caution);
            obj.put("generatedBy", generatedBy);
            obj.put("generatedAt", generatedAt);
            obj.put("sourceCount", sourceCount);
            obj.put("queryCount", queryCount);
            JSONArray claims = new JSONArray();
            for (ResearchClaim claim : keyClaims) {
                JSONObject item = new JSONObject();
                item.put("claim", claim.claim);
                item.put("supportLevel", claim.supportLevel);
                item.put("freshness", claim.freshness);
                JSONArray refs = new JSONArray();
                claim.sourceIndexes.forEach(refs::add);
                item.put("sourceIndexes", refs);
                claims.add(item);
            }
            obj.put("keyClaims", claims);
            JSONArray sources = new JSONArray();
            for (SourceRef ref : sourceRefs) {
                JSONObject item = new JSONObject();
                item.put("index", ref.index);
                item.put("title", ref.title);
                item.put("domain", ref.domain);
                item.put("url", ref.url);
                item.put("publishDate", ref.publishDate);
                sources.add(item);
            }
            obj.put("sourceRefs", sources);
            return obj;
        }

        public ResearchDigest copy() {
            ResearchDigest copy = new ResearchDigest();
            copy.topic = topic;
            copy.intent = intent;
            copy.targetYear = targetYear;
            copy.summary = summary;
            copy.verificationLevel = verificationLevel;
            copy.verificationNote = verificationNote;
            copy.freshnessNote = freshnessNote;
            copy.caution = caution;
            copy.generatedBy = generatedBy;
            copy.generatedAt = generatedAt;
            copy.sourceCount = sourceCount;
            copy.queryCount = queryCount;
            for (ResearchClaim claim : keyClaims) {
                ResearchClaim clone = new ResearchClaim();
                clone.claim = claim.claim;
                clone.supportLevel = claim.supportLevel;
                clone.freshness = claim.freshness;
                clone.sourceIndexes.addAll(claim.sourceIndexes);
                copy.keyClaims.add(clone);
            }
            for (SourceRef ref : sourceRefs) {
                SourceRef clone = new SourceRef();
                clone.index = ref.index;
                clone.title = ref.title;
                clone.domain = ref.domain;
                clone.url = ref.url;
                clone.publishDate = ref.publishDate;
                copy.sourceRefs.add(clone);
            }
            return copy;
        }
    }

    public static class ResearchClaim {
        public String claim;
        public String supportLevel;
        public String freshness;
        public final List<Integer> sourceIndexes = new ArrayList<>();
    }

    public static class SourceRef {
        public int index;
        public String title;
        public String domain;
        public String url;
        public String publishDate;
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ");
    }

    private String buildDigestPrompt(String topic,
                                     String intent,
                                     Integer targetYear,
                                     List<String> queries,
                                     List<SearchResult> results) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请基于以下搜索结果，输出一个 JSON 对象，不要输出 JSON 以外的任何文字。\n");
        prompt.append("任务要求：\n");
        prompt.append("1. 先总结核心结论，长度控制在220字以内。\n");
        prompt.append("2. 判断当前证据属于 strong / partial / weak 哪一档交叉验证，并说明原因。\n");
        prompt.append("3. 判断这些结果的时效性是否足够支撑当前主题。\n");
        prompt.append("4. 提炼 2-3 条核心论据，每条都必须标出来源索引 sourceIndexes。\n");
        prompt.append("5. 如果证据不足、来源互相转载、或没有明确发布日期，要明确写入 caution。\n");
        prompt.append("6. 禁止编造任何未出现在给定搜索结果中的数字、事件、机构观点或日期。\n");
        prompt.append("JSON schema:\n");
        prompt.append("{\"summary\":\"...\",\"verificationLevel\":\"strong|partial|weak\",\"verificationNote\":\"...\",")
                .append("\"freshnessNote\":\"...\",\"caution\":\"...\",\"keyClaims\":[{\"claim\":\"...\",")
                .append("\"supportLevel\":\"strong|medium|weak\",\"freshness\":\"high|medium|low\",")
                .append("\"sourceIndexes\":[1,2]}]}\n\n");
        prompt.append("主题：").append(topic != null ? topic : "未提供").append("\n");
        if (intent != null && !intent.isBlank()) {
            prompt.append("意图：").append(intent).append("\n");
        }
        if (targetYear != null) {
            prompt.append("目标年份：").append(targetYear).append("\n");
        }
        if (queries != null && !queries.isEmpty()) {
            prompt.append("搜索词：").append(String.join(" | ", queries)).append("\n");
        }
        prompt.append("搜索结果：\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            prompt.append("[").append(i + 1).append("] 标题：")
                    .append(firstNonBlank(result.source, "来源未标记")).append("\n");
            if (result.snippet != null && !result.snippet.isBlank()) {
                prompt.append("摘要：").append(truncateForDigest(result.snippet.trim(), 320)).append("\n");
            }
            if (result.url != null && !result.url.isBlank()) {
                prompt.append("链接：").append(result.url.trim()).append("\n");
            }
            String domain = extractDomain(result.url);
            if (domain != null) {
                prompt.append("域名：").append(domain).append("\n");
            }
            String publishDate = extractPublishDate(result.snippet);
            if (publishDate != null) {
                prompt.append("发布日期：").append(publishDate).append("\n");
            }
            prompt.append("\n");
        }
        return prompt.toString();
    }

    private ResearchDigest parseResearchDigest(String rawContent,
                                               String topic,
                                               String intent,
                                               Integer targetYear,
                                               List<SearchResult> results) {
        JSONObject parsed = extractJsonObject(rawContent);
        if (parsed == null) {
            return null;
        }
        ResearchDigest digest = new ResearchDigest();
        digest.topic = topic;
        digest.intent = intent;
        digest.targetYear = targetYear;
        digest.summary = parsed.getString("summary");
        digest.verificationLevel = parsed.getString("verificationLevel");
        digest.verificationNote = parsed.getString("verificationNote");
        digest.freshnessNote = parsed.getString("freshnessNote");
        digest.caution = parsed.getString("caution");
        JSONArray claims = parsed.getJSONArray("keyClaims");
        if (claims != null) {
            for (int i = 0; i < claims.size() && i < 4; i++) {
                JSONObject item = claims.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String claimText = item.getString("claim");
                if (claimText == null || claimText.isBlank()) {
                    continue;
                }
                ResearchClaim claim = new ResearchClaim();
                claim.claim = claimText.trim();
                claim.supportLevel = item.getString("supportLevel");
                claim.freshness = item.getString("freshness");
                JSONArray refs = item.getJSONArray("sourceIndexes");
                if (refs != null) {
                    for (int j = 0; j < refs.size(); j++) {
                        Integer idx = refs.getInteger(j);
                        if (idx != null && idx > 0 && idx <= results.size() && !claim.sourceIndexes.contains(idx)) {
                            claim.sourceIndexes.add(idx);
                        }
                    }
                }
                digest.keyClaims.add(claim);
            }
        }
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            SourceRef ref = new SourceRef();
            ref.index = i + 1;
            ref.title = firstNonBlank(result.source, "来源未标记");
            ref.url = result.url;
            ref.domain = extractDomain(result.url);
            ref.publishDate = extractPublishDate(result.snippet);
            digest.sourceRefs.add(ref);
        }
        return digest;
    }

    private JSONObject extractJsonObject(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        JSONObject parsed = parseJsonObject(rawContent.trim());
        if (parsed != null) {
            return parsed;
        }
        int start = rawContent.indexOf('{');
        int end = rawContent.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return parseJsonObject(rawContent.substring(start, end + 1));
        }
        return null;
    }

    private String extractChatContent(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        JSONArray choices = payload.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject first = choices.getJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONObject message = first.getJSONObject("message");
        if (message == null) {
            return null;
        }
        return message.getString("content");
    }

    private String truncateForDigest(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "…";
    }

    private void markDigestMeta(String status, String note, ResearchDigest digest) {
        SearchMeta meta = currentSearchMeta.get();
        if (meta == null) {
            meta = new SearchMeta();
            currentSearchMeta.set(meta);
        }
        meta.digestStatus = status;
        meta.digestNote = note;
        meta.digestMode = "zhipu_research_proxy";
        if (digest != null) {
            meta.digestSummaryLength = digest.summary != null ? digest.summary.length() : null;
            meta.digestClaimCount = digest.keyClaims.size();
            meta.digestSourceCount = digest.sourceRefs.size();
        }
    }

    private String extractPublishDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(20\\d{2}[-/.年]\\d{1,2}([-/\\.月]\\d{1,2}日?)?)")
                .matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String normalized = url.trim();
        int schemeIdx = normalized.indexOf("://");
        if (schemeIdx >= 0) {
            normalized = normalized.substring(schemeIdx + 3);
        }
        int slashIdx = normalized.indexOf('/');
        if (slashIdx >= 0) {
            normalized = normalized.substring(0, slashIdx);
        }
        int colonIdx = normalized.indexOf(':');
        if (colonIdx >= 0) {
            normalized = normalized.substring(0, colonIdx);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private boolean isDeepSeekWebEnabled() {
        return deepseekWebEnabled;
    }

    private Path resolveScriptPath() {
        Path configured = Paths.get(deepseekWebScriptPath);
        if (configured.isAbsolute()) {
            return configured;
        }
        return Paths.get(System.getProperty("user.dir")).resolve(configured).normalize();
    }

    private String resolveDeepSeekWebBridgeHealthUrl() {
        String base = deepseekWebBridgeUrl != null ? deepseekWebBridgeUrl.trim() : "";
        if (base.endsWith("/search")) {
            return base.substring(0, base.length() - "/search".length()) + "/health";
        }
        if (base.endsWith("/")) {
            return base + "health";
        }
        return base + "/health";
    }

    private String truncate(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        return text.substring(0, limit) + "…";
    }

    private void markMeta(String provider, String status, String note) {
        SearchMeta meta = currentSearchMeta.get();
        if (meta == null) {
            meta = new SearchMeta();
            currentSearchMeta.set(meta);
        }
        meta.provider = provider;
        meta.status = status;
        meta.note = note;
        if (provider != null && !"none".equalsIgnoreCase(provider) && status != null && !"success".equalsIgnoreCase(status)) {
            appendProviderDiagnostic(provider, note);
        }
    }

    private void markProviderSkipped(String provider, String note) {
        appendProviderDiagnostic(provider, note);
    }

    private void appendProviderDiagnostic(String provider, String note) {
        if (provider == null || provider.isBlank() || note == null || note.isBlank()) {
            return;
        }
        SearchMeta meta = currentSearchMeta.get();
        if (meta == null) {
            meta = new SearchMeta();
            currentSearchMeta.set(meta);
        }
        String entry = provider + "： " + note.trim();
        if (!meta.providerDiagnostics.contains(entry)) {
            meta.providerDiagnostics.add(entry);
        }
    }

    private String buildProviderExhaustedNote() {
        SearchMeta meta = currentSearchMeta.get();
        if (meta == null || meta.providerDiagnostics.isEmpty()) {
            return "未配置可用搜索 provider，或所有 provider 均未返回结果。";
        }
        return truncate("所有搜索 provider 均未返回结果。原因：" + String.join("；", meta.providerDiagnostics), 400);
    }

    private void enrichMeta(Integer queryCount,
                            Integer providerResultCount,
                            Long providerTimingMs,
                            JSONObject modeState,
                            Long bridgeRoundTripMs) {
        SearchMeta meta = currentSearchMeta.get();
        if (meta == null) {
            meta = new SearchMeta();
            currentSearchMeta.set(meta);
        }
        if (queryCount != null) {
            meta.queryCount = queryCount;
        }
        if (providerResultCount != null) {
            meta.providerResultCount = providerResultCount;
        }
        if (providerTimingMs != null) {
            meta.providerTimingMs = providerTimingMs;
        }
        if (bridgeRoundTripMs != null) {
            meta.bridgeRoundTripMs = bridgeRoundTripMs;
        }
        if (modeState != null) {
            meta.expertMode = modeState.getBoolean("expertMode");
            meta.quickMode = modeState.getBoolean("quickMode");
            meta.bridgeMode = modeState.getString("bridgeMode");
            meta.deepThinkEnabled = modeState.getBoolean("deepThinkEnabled");
            meta.searchEnabled = modeState.getBoolean("searchEnabled");
        }
    }

    private void enrichContentMeta(JSONObject result) {
        if (result == null) {
            return;
        }
        SearchMeta meta = currentSearchMeta.get();
        if (meta == null) {
            meta = new SearchMeta();
            currentSearchMeta.set(meta);
        }
        meta.contentStatus = result.getString("contentStatus");
        meta.contentNote = result.getString("contentNote");
        meta.answerSource = result.getString("answerSource");
        meta.waitDurationMs = result.getLong("waitDurationMs");
        meta.completionReason = result.getString("completionReason");
        meta.isolatedSession = result.getBoolean("isolatedSession");
        meta.targetId = result.getString("targetId");
        meta.answerLength = result.getInteger("answerLength");
        meta.newLinkCount = result.getInteger("newLinkCount");
        meta.candidateBlockCount = result.getInteger("candidateBlockCount");
        meta.assistantBlockDeltaCount = result.getInteger("assistantBlockDeltaCount");
        meta.bodyDeltaLength = result.getInteger("bodyDeltaLength");
    }

    private boolean shouldIncludeSummary(JSONObject result) {
        if (result == null) {
            return false;
        }
        String contentStatus = result.getString("contentStatus");
        return contentStatus == null
                || "success".equalsIgnoreCase(contentStatus);
    }

    private JSONObject parseJsonObject(String raw) {
        try {
            return JSON.parseObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveBridgeErrorStatus(int code, JSONObject errorPayload) {
        String error = errorPayload != null ? errorPayload.getString("error") : null;
        if ("provider_timeout".equals(error) || code == 504) {
            return "timeout";
        }
        if ("login_required".equals(error)) {
            return "login_required";
        }
        if ("debugger_unavailable".equals(error)) {
            return "debugger_unavailable";
        }
        if ("submit_failed".equals(error)) {
            return "submit_failed";
        }
        if (code == 429) {
            return "bridge_busy";
        }
        return "error";
    }

    private String resolveBridgeErrorNote(int code, JSONObject errorPayload) {
        String message = errorPayload != null ? errorPayload.getString("message") : null;
        String error = errorPayload != null ? errorPayload.getString("error") : null;
        if ("provider_timeout".equals(error) || code == 504) {
            return "DeepSeek 网页搜索等待超时，当前未拿到稳定返回。";
        }
        if ("login_required".equals(error)) {
            return "DeepSeek 网页会话不可用，可能需要重新登录。";
        }
        if ("debugger_unavailable".equals(error)) {
            return "Chrome 远程调试端口不可用，bridge 当前无法接管页面。";
        }
        if ("submit_failed".equals(error)) {
            return "DeepSeek 页面已打开，但提交查询失败。";
        }
        if (code == 429) {
            return "DeepSeek bridge 当前繁忙，未在等待窗口内拿到空闲槽位。";
        }
        return message != null && !message.isBlank()
                ? "DeepSeek 网页搜索 bridge 异常：" + message
                : "DeepSeek 网页搜索 bridge 不可用。";
    }

    private String resolveZhipuErrorStatus(int code, String responseBody) {
        String raw = responseBody == null ? "" : responseBody;
        if (code == 401) {
            return "unauthorized";
        }
        if (raw.contains("\"1113\"") || raw.contains("余额不足") || raw.contains("无可用资源包")) {
            return "insufficient_quota";
        }
        if (code == 429 || raw.contains("\"1305\"")) {
            return "rate_limited";
        }
        if (code == 400) {
            return "bad_request";
        }
        return "error";
    }

    private String resolveZhipuErrorNote(int code, String responseBody) {
        String raw = responseBody == null ? "" : responseBody;
        if (code == 401) {
            return "智谱 API Key 无效，或当前账号无权访问联网搜索接口。";
        }
        if (raw.contains("\"1113\"") || raw.contains("余额不足") || raw.contains("无可用资源包")) {
            return "智谱联网搜索余额不足或无可用资源包，请充值后重试。";
        }
        if (code == 429 || raw.contains("\"1305\"")) {
            return "智谱联网搜索当前访问量过大，触发了官方限流。";
        }
        if (code == 400) {
            return "智谱联网搜索请求参数无效。";
        }
        return raw.isBlank()
                ? "智谱联网搜索请求失败。"
                : "智谱联网搜索失败：" + truncate(raw, 200);
    }

    private String joinNonBlank(String delimiter, String... parts) {
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                tokens.add(part.trim());
            }
        }
        return tokens.isEmpty() ? null : String.join(delimiter, tokens);
    }

    private String buildFallbackSearchUrl(String title, String media, String content, String query) {
        String seed = joinNonBlank(" ", title, media);
        if ((seed == null || seed.isBlank()) && content != null && !content.isBlank()) {
            seed = content.length() > 40 ? content.substring(0, 40) : content;
        }
        if (seed == null || seed.isBlank()) {
            seed = query;
        }
        if (seed == null || seed.isBlank()) {
            return null;
        }
        return "https://www.bing.com/search?q=" + URLEncoder.encode(seed, StandardCharsets.UTF_8);
    }

    private List<SearchResult> copyResults(List<SearchResult> source, int maxResults) {
        if (source.isEmpty()) return Collections.emptyList();
        List<SearchResult> limited = new ArrayList<>();
        for (int i = 0; i < Math.min(source.size(), maxResults); i++) {
            SearchResult original = source.get(i);
            SearchResult copy = new SearchResult();
            copy.source = original.source;
            copy.snippet = original.snippet;
            copy.url = original.url;
            limited.add(copy);
        }
        return limited;
    }

    public static class SearchMeta {
        public String provider;
        public String status;
        public String note;
        public final List<String> providerDiagnostics = new ArrayList<>();
        public String digestMode;
        public String digestStatus;
        public String digestNote;
        public Integer digestSummaryLength;
        public Integer digestClaimCount;
        public Integer digestSourceCount;
        public Long digestTimingMs;
        public Integer queryCount;
        public Integer providerResultCount;
        public Long providerTimingMs;
        public Long bridgeRoundTripMs;
        public Boolean expertMode;
        public Boolean quickMode;
        public String bridgeMode;
        public Boolean deepThinkEnabled;
        public Boolean searchEnabled;
        public String contentStatus;
        public String contentNote;
        public String answerSource;
        public Long waitDurationMs;
        public String completionReason;
        public Boolean isolatedSession;
        public String targetId;
        public Integer answerLength;
        public Integer newLinkCount;
        public Integer candidateBlockCount;
        public Integer assistantBlockDeltaCount;
        public Integer bodyDeltaLength;
    }

}
