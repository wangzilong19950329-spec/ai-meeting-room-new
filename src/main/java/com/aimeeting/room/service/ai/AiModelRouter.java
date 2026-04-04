package com.aimeeting.room.service.ai;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dto.ChatResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI模型路由器
 * 根据 modelType 自动选择对应的客户端实现
 */
@Slf4j
@Service
public class AiModelRouter {

    @Autowired
    private List<AiModelClient> clients;

    @Autowired
    private AiModelProperties properties;

    /**
     * 路由并调用对应的AI模型
     *
     * @param modelType    模型标识，如 gpt-4, claude-3-opus, qwen-max, ernie-bot-4
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户消息
     * @return 模型回复
     */
    public String chat(String modelType, String systemPrompt, String userPrompt) {
        return chat(modelType, systemPrompt, userPrompt, null);
    }

    public String chat(String modelType, String systemPrompt, String userPrompt, Integer maxTokens) {
        if (modelType == null || modelType.isEmpty()) {
            modelType = properties.getDefaultModel();
        }

        for (AiModelClient client : clients) {
            if (client.supports(modelType)) {
                log.debug("Routing to {} for modelType={}", client.getClass().getSimpleName(), modelType);
                if (client instanceof GenericAiClient) {
                    return ((GenericAiClient) client).chat(modelType, systemPrompt, userPrompt, maxTokens);
                }
                return client.chat(modelType, systemPrompt, userPrompt);
            }
        }

        log.warn("No client found for modelType={}, using first available", modelType);
        if (!clients.isEmpty()) {
            AiModelClient first = clients.get(0);
            if (first instanceof GenericAiClient) {
                return ((GenericAiClient) first).chat(properties.getDefaultModel(), systemPrompt, userPrompt, maxTokens);
            }
            return first.chat(properties.getDefaultModel(), systemPrompt, userPrompt);
        }
        return null;
    }

    /**
     * 调用模型并返回带搜索证据的结果（DeepSeek tool_call 搜索证据会被捕获）
     */
    public ChatResult chatEx(String modelType, String systemPrompt, String userPrompt) {
        if (modelType == null || modelType.isEmpty()) {
            modelType = properties.getDefaultModel();
        }
        for (AiModelClient client : clients) {
            if (client.supports(modelType)) {
                if (client instanceof GenericAiClient) {
                    return ((GenericAiClient) client).chatWithEvidence(modelType, systemPrompt, userPrompt);
                }
                String content = client.chat(modelType, systemPrompt, userPrompt);
                return new ChatResult(content);
            }
        }
        if (!clients.isEmpty()) {
            String content = clients.get(0).chat(properties.getDefaultModel(), systemPrompt, userPrompt);
            return new ChatResult(content);
        }
        return new ChatResult(null);
    }
}
