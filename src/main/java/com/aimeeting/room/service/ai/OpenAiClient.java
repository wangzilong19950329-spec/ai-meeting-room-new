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
 * OpenAI 及兼容格式客户端
 * 支持: gpt-*, 以及所有使用 OpenAI 兼容接口的服务（Azure OpenAI、通义千问 DashScope 兼容模式、Ollama 等）
 */
@Slf4j
@Component
public class OpenAiClient implements AiModelClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    @Autowired
    private AiModelProperties properties;

    @Override
    public boolean supports(String modelType) {
        if (modelType == null) return false;
        String m = modelType.toLowerCase();
        // gpt-* 走 openai；qwen-* 走通义 DashScope 兼容接口
        return m.startsWith("gpt-") || m.startsWith("o1") || m.startsWith("o3")
                || m.startsWith("qwen-");
    }

    @Override
    public String chat(String modelType, String systemPrompt, String userPrompt) {
        // qwen 走 tongyi 配置，其余走 openai 配置
        AiModelProperties.ModelConfig cfg = modelType.toLowerCase().startsWith("qwen-")
                ? properties.getModels().get("tongyi")
                : properties.getModels().get("openai");

        if (cfg == null || !cfg.isEnabled()) {
            log.warn("OpenAI/Tongyi config not enabled for modelType={}", modelType);
            return mockResponse(modelType, userPrompt);
        }

        JSONObject body = new JSONObject();
        body.put("model", modelType);
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
        body.put("messages", messages);
        body.put("temperature", 0.7);
        body.put("max_tokens", 2048);

        Request request = new Request.Builder()
                .url(cfg.getApiUrl())
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            JSONObject resp = JSON.parseObject(responseBody);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                return choices.getJSONObject(0).getJSONObject("message").getString("content");
            }
            log.warn("No choices in response: {}", responseBody);
            return null;
        } catch (Exception e) {
            log.error("OpenAI call failed, model={}", modelType, e);
            return null;
        }
    }

    private String mockResponse(String modelType, String prompt) {
        return String.format("[Mock-%s] 基于对议题的分析，我认为该问题需要从多角度综合考量。\n【观点】议题涉及多个层面，需要权衡利弊。\n【论据】从历史经验和逻辑推导来看，单一视角往往不够全面。\n【结论】建议采取平衡、多元的解决方案。", modelType);
    }
}
