package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dto.ModelConfigRequest;
import com.aimeeting.room.service.ai.AiModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final AiModelProperties aiModelProperties;
    private final AiModelRouter aiModelRouter;

    /**
     * 获取当前模型配置（API Key 脱敏返回）
     */
    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> getModelConfig() {
        Map<String, Object> result = new HashMap<>();

        Map<String, Object> modelsView = new HashMap<>();
        aiModelProperties.getModels().forEach((key, cfg) -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("apiUrl", cfg.getApiUrl());
            entry.put("apiKey", maskKey(cfg.getApiKey()));
            entry.put("secretKey", maskKey(cfg.getSecretKey()));
            entry.put("apiVersion", cfg.getApiVersion());
            entry.put("enabled", cfg.isEnabled());
            modelsView.put(key, entry);
        });
        result.put("models", modelsView);

        AiModelProperties.MeetingConfig m = aiModelProperties.getMeeting();
        Map<String, Object> meeting = new HashMap<>();
        meeting.put("consensusThreshold", m.getConsensusThreshold());
        meeting.put("minAgreementCount", m.getMinAgreementCount());
        meeting.put("maxRoundsDefault", m.getMaxRoundsDefault());
        meeting.put("baseReward", m.getBaseReward());
        meeting.put("basePenalty", m.getBasePenalty());
        meeting.put("dissentPenaltyFactor", m.getDissentPenaltyFactor());
        result.put("meeting", meeting);

        return ApiResponse.ok(result);
    }

    /**
     * 保存模型配置（运行时热更新内存配置，需重启才能持久化到配置文件）
     */
    @PostMapping("/models")
    public ApiResponse<Void> saveModelConfig(@RequestBody ModelConfigRequest req) {
        if (req.getModels() != null) {
            req.getModels().forEach((key, entry) -> {
                AiModelProperties.ModelConfig cfg = aiModelProperties.getModels()
                        .computeIfAbsent(key, k -> new AiModelProperties.ModelConfig());
                if (entry.getApiUrl() != null && !entry.getApiUrl().isBlank()) {
                    cfg.setApiUrl(entry.getApiUrl());
                }
                // 只有传入非脱敏值（不含 ***）才更新
                if (entry.getApiKey() != null && !entry.getApiKey().contains("***")) {
                    cfg.setApiKey(entry.getApiKey());
                }
                if (entry.getSecretKey() != null && !entry.getSecretKey().contains("***")) {
                    cfg.setSecretKey(entry.getSecretKey());
                }
                if (entry.getApiVersion() != null) {
                    cfg.setApiVersion(entry.getApiVersion());
                }
                cfg.setEnabled(entry.isEnabled());
            });
        }
        if (req.getMeeting() != null) {
            ModelConfigRequest.MeetingParams mp = req.getMeeting();
            AiModelProperties.MeetingConfig mc = aiModelProperties.getMeeting();
            if (mp.getConsensusThreshold() != null) mc.setConsensusThreshold(mp.getConsensusThreshold());
            if (mp.getMinAgreementCount() != null) mc.setMinAgreementCount(mp.getMinAgreementCount());
            if (mp.getMaxRoundsDefault() != null) mc.setMaxRoundsDefault(mp.getMaxRoundsDefault());
            if (mp.getBaseReward() != null) mc.setBaseReward(mp.getBaseReward());
            if (mp.getBasePenalty() != null) mc.setBasePenalty(mp.getBasePenalty());
            if (mp.getDissentPenaltyFactor() != null) mc.setDissentPenaltyFactor(mp.getDissentPenaltyFactor());
        }
        log.info("Model config updated in memory");
        return ApiResponse.ok(null);
    }

    /**
     * 测试指定 provider 的连通性
     */
    @PostMapping("/models/test/{provider}")
    public ApiResponse<Map<String, Object>> testConnection(
            @PathVariable String provider,
            @RequestBody ModelConfigRequest.ModelEntry entry) {

        Map<String, Object> result = new HashMap<>();
        try {
            // 临时用传入的 key 构造配置测试
            String testModel = providerToModel(provider);
            AiModelProperties.ModelConfig original = aiModelProperties.getModels().get(provider);

            // 如果传入了非脱敏 key，则临时替换
            AiModelProperties.ModelConfig tmp = new AiModelProperties.ModelConfig();
            tmp.setApiUrl(entry.getApiUrl() != null ? entry.getApiUrl() : (original != null ? original.getApiUrl() : ""));
            tmp.setApiKey(entry.getApiKey() != null && !entry.getApiKey().contains("***")
                    ? entry.getApiKey() : (original != null ? original.getApiKey() : ""));
            tmp.setSecretKey(entry.getSecretKey() != null && !entry.getSecretKey().contains("***")
                    ? entry.getSecretKey() : (original != null ? original.getSecretKey() : ""));
            tmp.setApiVersion(entry.getApiVersion() != null ? entry.getApiVersion() : (original != null ? original.getApiVersion() : ""));
            tmp.setEnabled(true);

            // 临时注入配置
            aiModelProperties.getModels().put(provider, tmp);
            try {
                String reply = aiModelRouter.chat(testModel, "You are a test assistant.", "Reply with OK");
                result.put("success", reply != null && !reply.isBlank());
                result.put("message", "连接成功，模型响应正常");
            } finally {
                // 恢复原配置
                if (original != null) {
                    aiModelProperties.getModels().put(provider, original);
                } else {
                    aiModelProperties.getModels().remove(provider);
                }
            }
        } catch (Exception e) {
            log.warn("Connection test failed for provider {}: {}", provider, e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return ApiResponse.ok(result);
    }

    private String providerToModel(String provider) {
        switch (provider) {
            case "openai": return "gpt-3.5-turbo";
            case "claude": return "claude-3-5-sonnet-20241022";
            case "tongyi": return "qwen-plus";
            case "wenxin": return "ernie-bot";
            default: return provider;
        }
    }

    private String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }
}
