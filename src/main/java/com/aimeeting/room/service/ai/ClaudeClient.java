package com.aimeeting.room.service.ai;

import com.aimeeting.room.config.AiModelProperties;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Anthropic Claude 客户端
 * 支持: claude-* 系列模型
 * Claude API 与 OpenAI 格式不同，system 作为顶层字段
 */
@Slf4j
@Component
public class ClaudeClient implements AiModelClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    @Autowired
    private AiModelProperties properties;

    @Override
    public boolean supports(String modelType) {
        return modelType != null && modelType.toLowerCase().startsWith("claude-");
    }

    @Override
    public String chat(String modelType, String systemPrompt, String userPrompt) {
        AiModelProperties.ModelConfig cfg = properties.getModels().get("claude");
        if (cfg == null || !cfg.isEnabled()) {
            log.warn("Claude config not enabled for modelType={}", modelType);
            return mockResponse(modelType, userPrompt);
        }

        JSONObject body = new JSONObject();
        body.put("model", modelType);
        body.put("max_tokens", 2048);

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
                .url(cfg.getApiUrl())
                .header("x-api-key", cfg.getApiKey())
                .header("anthropic-version", cfg.getApiVersion() != null ? cfg.getApiVersion() : "2023-06-01")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            JSONObject resp = JSON.parseObject(responseBody);
            JSONArray content = resp.getJSONArray("content");
            if (content != null && !content.isEmpty()) {
                return content.getJSONObject(0).getString("text");
            }
            log.warn("No content in Claude response: {}", responseBody);
            return null;
        } catch (Exception e) {
            log.error("Claude call failed, model={}", modelType, e);
            return null;
        }
    }

    private String mockResponse(String modelType, String prompt) {
        return String.format("[Mock-%s] 经过深入思考，我对该议题有以下见解。\n【观点】问题的核心在于如何平衡多方利益。\n【论据】从理性分析的角度出发，需要考虑短期和长期影响。\n【结论】推荐采用系统性思维框架来分析和解决此问题。", modelType);
    }
}
