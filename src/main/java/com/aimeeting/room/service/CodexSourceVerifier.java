package com.aimeeting.room.service;

import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Third evidence tier: the user's local Codex API (the Responses endpoint with web search) finds and opens the
 * original source of one low-confidence fact or gap. A verdict is accepted only when its link is one the search
 * actually returned, the source is dated no later than the meeting's as-of date, and every number in the stated
 * finding appears in the quoted source text. Anything else is "not found"; a failed call is "unavailable".
 */
@Slf4j
@Service
public class CodexSourceVerifier {
    public static final String VERIFIED = "VERIFIED";
    public static final String CONTRADICTED = "CONTRADICTED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String INSTRUCTIONS = "你是事实核查员。用网络搜索找到并打开最原始的出处：公司公告、交易所披露、"
            + "政府和统计机构发布优先，其次是首发报道；只根据打开的原文判断，不凭记忆。只输出 JSON。";

    private final ModelRegistryStore registry;
    private final MeetingCallLogService callLog;
    private final String selector;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .build();

    public CodexSourceVerifier(ModelRegistryStore registry, MeetingCallLogService callLog,
                               @Value("${AI_MEETING_SOURCE_CHECK_MODEL:gpt-5.6-luna}") String selector) {
        this.registry = registry;
        this.callLog = callLog;
        this.selector = selector;
    }

    /** Reasons a reply lacked proof; Codex is asked once more with the reason, as members are asked to rewrite. */
    private static final Map<String, String> RETRY_REASONS = Map.of(
            "SOURCE_NOT_CITED", "sourceUrl 的链接不在你打开或引用过的页面里",
            "SOURCE_PROOF_MISSING", "缺少 statement、quote 或 sourceUrl",
            "SOURCE_DATE_MISSING", "publishedAt 不是 YYYY-MM-DD 格式的原文发布日期",
            "NUMBERS_NOT_IN_QUOTE", "statement 里有 quote 中没有的数字",
            "CODEX_REPLY_INVALID", "回复不是要求的 JSON");

    public JSONObject check(String asOf, JSONObject fact) {
        ModelEntry entry = registry.getByModelId(selector);
        if (entry == null || !entry.isEnabled() || entry.getApiUrl() == null) {
            return verdict(fact, UNAVAILABLE, "SOURCE_CHECK_MODEL_MISSING");
        }
        String input = prompt(asOf, fact);
        JSONObject verdict = askAndRead(entry, input, asOf, fact);
        String retry = retryReason(verdict);
        if (retry == null) return verdict;
        return askAndRead(entry, input + "\n\n【上一版不合格】" + retry + "。请重新打开原文核对，按要求输出完整 JSON。",
                asOf, fact);
    }

    private static String retryReason(JSONObject verdict) {
        String reason = verdict.getString("reason");
        if ("QUOTE_DOES_NOT_SUPPORT".equals(reason)) {
            return "quote 不能直接读出 statement 的全部内容（缺少：" + verdict.getString("missing")
                    + "）；请摘录能直接读出结论的原文，比较或变化要写出双方的数值和日期";
        }
        return reason == null ? null : RETRY_REASONS.get(reason);
    }

    /**
     * A verdict that passed the mechanical rules is accepted only when a separate reading — the same model,
     * without search, seeing nothing but the question, the statement and the quote — finds that the quote
     * alone says everything the statement says. Numbers are checked mechanically; this catches the rest,
     * such as a comparison or direction the quote does not contain.
     */
    private JSONObject askAndRead(ModelEntry entry, String input, String asOf, JSONObject fact) {
        JSONObject verdict = ask(entry, input, asOf, fact);
        String status = verdict.getString("status");
        if (!VERIFIED.equals(status) && !CONTRADICTED.equals(status)) return verdict;
        JSONObject reading = read(entry, fact, verdict);
        if (reading == null) return verdict(fact, UNAVAILABLE, "SUPPORT_CHECK_FAILED");
        if (reading.getBooleanValue("supported")) return verdict;
        JSONObject rejected = verdict(fact, NOT_FOUND, "QUOTE_DOES_NOT_SUPPORT");
        rejected.put("missing", firstNonBlank(reading.getString("missing"), "未说明"));
        return rejected;
    }

    private JSONObject read(ModelEntry entry, JSONObject fact, JSONObject verdict) {
        String prompt = "判断原文摘录能否直接支持这个说法。\n问题：" + fact.getString("question")
                + "\n说法：" + verdict.getString("statement") + "\n原文摘录：" + verdict.getString("quote")
                + "\n只看摘录本身，不用常识或记忆补全。说法的每一部分（数值、比较对象、变化方向、时间范围）都能从摘录直接读出才算支持。"
                + "\n只输出 JSON：{\"supported\":true,\"missing\":\"摘录缺少的内容，支持时留空\"}";
        JSONObject body = new JSONObject(true);
        body.put("model", entry.getModelId());
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是严格的文本核对员，只判断摘录是否支持说法。只输出 JSON。"),
                Map.of("role", "user", "content", prompt)));
        JSONObject response = post(entry, entry.getApiUrl(), body);
        if (response == null) return null;
        try {
            String content = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            JSONObject reading = parseObject(content == null ? "" : content);
            return reading == null || !(reading.get("supported") instanceof Boolean) ? null : reading;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Posts one request and records its usage; returns the parsed body, or null when the call failed. */
    private JSONObject post(ModelEntry entry, String url, JSONObject body) {
        Request.Builder request = new Request.Builder().url(url).post(RequestBody.create(body.toJSONString(), JSON_TYPE));
        if (entry.getApiKey() != null && !entry.getApiKey().isBlank()) {
            request.header("Authorization", "Bearer " + entry.getApiKey().trim());
        }
        long started = System.currentTimeMillis();
        try (Response response = client.newCall(request.build()).execute()) {
            String text = response.body() == null ? null : response.body().string();
            if (callLog != null) {
                callLog.recordApiCall(entry.getModelId(), System.currentTimeMillis() - started,
                        response.isSuccessful() ? "ok" : "http_" + response.code(), body, text);
            }
            return response.isSuccessful() && text != null ? JSON.parseObject(text) : null;
        } catch (Exception failure) {
            log.warn("Codex call to {} failed: {}", url, failure.getMessage());
            if (callLog != null) callLog.recordApiCall(entry.getModelId(), System.currentTimeMillis() - started, "error", body, null);
            return null;
        }
    }

    private JSONObject ask(ModelEntry entry, String input, String asOf, JSONObject fact) {
        JSONObject body = new JSONObject(true);
        body.put("model", entry.getModelId());
        body.put("instructions", INSTRUCTIONS);
        body.put("input", input);
        body.put("tools", List.of(Map.of("type", "web_search")));
        Request.Builder request = new Request.Builder().url(responsesUrl(entry.getApiUrl()))
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE));
        if (entry.getApiKey() != null && !entry.getApiKey().isBlank()) {
            request.header("Authorization", "Bearer " + entry.getApiKey().trim());
        }
        long started = System.currentTimeMillis();
        try (Response response = client.newCall(request.build()).execute()) {
            String text = response.body() == null ? null : response.body().string();
            if (callLog != null) {
                callLog.recordApiCall(entry.getModelId(), System.currentTimeMillis() - started,
                        response.isSuccessful() ? "ok" : "http_" + response.code(), body, text);
            }
            if (!response.isSuccessful() || text == null) return verdict(fact, UNAVAILABLE, "CODEX_HTTP_" + response.code());
            return judge(JSON.parseObject(text), asOf, fact);
        } catch (Exception failure) {
            log.warn("Codex source check failed for {}: {}", fact.getString("id"), failure.getMessage());
            if (callLog != null) {
                callLog.recordApiCall(entry.getModelId(), System.currentTimeMillis() - started, "error", body, null);
            }
            return verdict(fact, UNAVAILABLE, "CODEX_OUTCOME_UNKNOWN");
        }
    }

    static JSONObject judge(JSONObject response, String asOf, JSONObject fact) {
        StringBuilder text = new StringBuilder();
        Set<String> cited = new LinkedHashSet<>();
        JSONArray output = response == null ? null : response.getJSONArray("output");
        for (int i = 0; output != null && i < output.size(); i++) {
            JSONObject item = output.getJSONObject(i);
            if ("web_search_call".equals(item.getString("type"))) {
                JSONObject action = item.getJSONObject("action");
                if (action != null && action.getString("url") != null) cited.add(normalize(action.getString("url")));
                JSONArray sources = action == null ? null : action.getJSONArray("sources");
                for (int j = 0; sources != null && j < sources.size(); j++) {
                    String url = sources.getJSONObject(j).getString("url");
                    if (url != null) cited.add(normalize(url));
                }
            }
            if (!"message".equals(item.getString("type"))) continue;
            JSONArray content = item.getJSONArray("content");
            for (int j = 0; content != null && j < content.size(); j++) {
                JSONObject part = content.getJSONObject(j);
                if (part.getString("text") != null) text.append(part.getString("text"));
                JSONArray annotations = part.getJSONArray("annotations");
                for (int k = 0; annotations != null && k < annotations.size(); k++) {
                    String url = annotations.getJSONObject(k).getString("url");
                    if (url != null) cited.add(normalize(url));
                }
            }
        }
        JSONObject reply = parseObject(text.toString());
        String status = reply == null ? null : reply.getString("status");
        if (!VERIFIED.equals(status) && !CONTRADICTED.equals(status) && !NOT_FOUND.equals(status)) {
            return verdict(fact, UNAVAILABLE, "CODEX_REPLY_INVALID");
        }
        if (NOT_FOUND.equals(status)) return verdict(fact, NOT_FOUND, "SOURCE_NOT_FOUND");
        // A gap has no claim to contradict: whatever the source says is its answer.
        if ("G".equals(fact.getString("tier"))) status = VERIFIED;
        String statement = trimmed(reply.getString("statement"));
        String quote = trimmed(reply.getString("quote"));
        String sourceUrl = trimmed(reply.getString("sourceUrl"));
        if (statement == null || quote == null || sourceUrl == null) return verdict(fact, NOT_FOUND, "SOURCE_PROOF_MISSING");
        if (!cited.contains(normalize(sourceUrl))) return verdict(fact, NOT_FOUND, "SOURCE_NOT_CITED");
        LocalDate published;
        try {
            published = LocalDate.parse(String.valueOf(reply.getString("publishedAt")).trim());
        } catch (RuntimeException unparsable) {
            return verdict(fact, NOT_FOUND, "SOURCE_DATE_MISSING");
        }
        if (published.isAfter(LocalDate.parse(asOf))) return verdict(fact, NOT_FOUND, "SOURCE_AFTER_AS_OF");
        if (ReviewedNumberGate.audit(statement, quote, "", null).hasUnsupported()) {
            return verdict(fact, NOT_FOUND, "NUMBERS_NOT_IN_QUOTE");
        }
        // "Verified" is not taken on trust: a claim whose own numbers the source does not contain was corrected.
        if (VERIFIED.equals(status) && "L".equals(fact.getString("tier")) && fact.getString("statement") != null
                && ReviewedNumberGate.audit(fact.getString("statement"), quote, "", null).hasUnsupported()) {
            status = CONTRADICTED;
        }
        JSONObject verdict = verdict(fact, status, null);
        verdict.put("statement", statement);
        verdict.put("quote", quote);
        verdict.put("sourceUrl", stripTracking(sourceUrl));
        verdict.put("publishedAt", published.toString());
        return verdict;
    }

    private static String prompt(String asOf, JSONObject fact) {
        StringBuilder sb = new StringBuilder();
        boolean gap = "G".equals(fact.getString("tier"));
        if (gap) {
            sb.append("截至 ").append(asOf).append("，用原文回答这个问题：").append(fact.getString("question")).append("\n");
        } else {
            sb.append("截至 ").append(asOf).append("，核对这条资料是否与原文一致：\n资料：").append(fact.getString("statement"))
                    .append("\n它回答的问题：").append(fact.getString("question")).append("\n");
            if (fact.getString("source") != null) sb.append("此前给出的来源：").append(fact.getString("source")).append("\n");
        }
        sb.append("规则：只采用 ").append(asOf).append(" 及以前发布的原文；quote 原样摘录原文中支撑判断的一句话；")
                .append("statement 用一句话写原文的说法，其中的数字必须原样出现在 quote 里；publishedAt 写原文的发布日期；")
                .append("sourceUrl 必须是你打开过的原文页面，不能凭记忆给链接；")
                .append("问题涉及比较或变化时，statement 写出比较双方的数值和日期，并都出现在 quote 中（quote 可以摘录两处原文，用“……”连接）。\n");
        if (gap) {
            sb.append("在原文里找到答案 status=VERIFIED；找不到原文 status=NOT_FOUND。\n");
        } else {
            sb.append("原文与资料一致 status=VERIFIED；原文说法不同 status=CONTRADICTED（statement 写原文的说法）；")
                    .append("找不到原文 status=NOT_FOUND。\n");
        }
        sb.append("只输出 JSON：{\"status\":\"\",\"statement\":\"\",\"quote\":\"\",\"sourceUrl\":\"\",\"publishedAt\":\"YYYY-MM-DD\"}");
        return sb.toString();
    }

    private static JSONObject verdict(JSONObject fact, String status, String reason) {
        JSONObject verdict = new JSONObject(true);
        verdict.put("factId", fact.getString("id"));
        verdict.put("status", status);
        if (reason != null) verdict.put("reason", reason);
        return verdict;
    }

    /** The registry stores the chat-completions URL; web search lives on the Responses endpoint beside it. */
    private static String responsesUrl(String apiUrl) {
        String base = apiUrl.replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) return base.substring(0, base.length() - "/chat/completions".length()) + "/responses";
        if (base.endsWith("/responses")) return base;
        return base + "/responses";
    }

    private static String normalize(String url) {
        return stripTracking(url.trim()).replaceAll("/+$", "").toLowerCase();
    }

    private static String stripTracking(String url) {
        return url.replaceAll("([?&])utm_[a-z]+=[^&#]*&?", "$1").replaceAll("[?&]$", "");
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String trimmed(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static JSONObject parseObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return JSON.parseObject(text.substring(start, end + 1));
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
