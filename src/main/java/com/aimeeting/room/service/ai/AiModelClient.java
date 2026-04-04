package com.aimeeting.room.service.ai;

/**
 * AI模型客户端接口，每种模型实现一个
 */
public interface AiModelClient {

    /**
     * 返回该客户端支持的模型标识前缀，如 "gpt-", "claude-", "qwen-", "ernie-"
     */
    boolean supports(String modelType);

    /**
     * 调用模型
     */
    String chat(String modelType, String systemPrompt, String userPrompt);
}
