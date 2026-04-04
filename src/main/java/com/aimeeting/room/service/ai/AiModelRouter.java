package com.aimeeting.room.service.ai;

import com.aimeeting.room.config.AiModelProperties;
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
        if (modelType == null || modelType.isEmpty()) {
            modelType = properties.getDefaultModel();
        }

        for (AiModelClient client : clients) {
            if (client.supports(modelType)) {
                log.debug("Routing to {} for modelType={}", client.getClass().getSimpleName(), modelType);
                return client.chat(modelType, systemPrompt, userPrompt);
            }
        }

        log.warn("No client found for modelType={}, using first available", modelType);
        if (!clients.isEmpty()) {
            return clients.get(0).chat(properties.getDefaultModel(), systemPrompt, userPrompt);
        }
        return null;
    }
}
