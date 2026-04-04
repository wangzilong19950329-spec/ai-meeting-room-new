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
 * 百度文心一言客户端
 * 支持: ernie-* 系列模型
 * 需先用 API Key + Secret Key 换取 access_token，再调用对话接口
 */
@Slf4j
@Component
public class WenxinClient implements AiModelClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    @Autowired
    private AiModelProperties properties;

    /** 简单缓存 access_token，生产中应加过期刷新逻辑 */
    private volatile String cachedAccessToken;

    @Override
    public boolean supports(String modelType) {
        return modelType != null && modelType.toLowerCase().startsWith("ernie-");
    }

    @Override
    public String chat(String modelType, String systemPrompt, String userPrompt) {
        AiModelProperties.ModelConfig cfg = properties.getModels().get("wenxin");
        if (cfg == null || !cfg.isEnabled()) {
            log.warn("Wenxin config not enabled for modelType={}", modelType);
            return mockResponse(modelType, userPrompt);
        }

        String accessToken = getAccessToken(cfg);
        if (accessToken == null) return null;

        JSONObject body = new JSONObject();
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "user");
            sys.put("content", "[系统角色设定] " + systemPrompt);
            messages.add(sys);
            JSONObject sysReply = new JSONObject();
            sysReply.put("role", "assistant");
            sysReply.put("content", "我理解并接受以上角色设定。");
            messages.add(sysReply);
        }
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        body.put("messages", messages);

        String url = cfg.getApiUrl() + "?access_token=" + accessToken;
        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            JSONObject resp = JSON.parseObject(responseBody);
            return resp.getString("result");
        } catch (Exception e) {
            log.error("Wenxin call failed, model={}", modelType, e);
            return null;
        }
    }

    private String getAccessToken(AiModelProperties.ModelConfig cfg) {
        if (cachedAccessToken != null) return cachedAccessToken;
        String tokenUrl = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials"
                + "&client_id=" + cfg.getApiKey()
                + "&client_secret=" + cfg.getSecretKey();
        Request request = new Request.Builder().url(tokenUrl)
                .post(RequestBody.create("", MediaType.get("application/json")))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            JSONObject resp = JSON.parseObject(response.body().string());
            cachedAccessToken = resp.getString("access_token");
            return cachedAccessToken;
        } catch (Exception e) {
            log.error("Failed to get Wenxin access token", e);
            return null;
        }
    }

    private String mockResponse(String modelType, String prompt) {
        return String.format("[Mock-%s] 作为文心一言，我对该议题的分析如下。\n【观点】该问题具有一定的复杂性和多面性。\n【论据】结合实际情况和理论依据进行综合判断。\n【结论】建议在充分调研的基础上制定针对性方案。", modelType);
    }
}
