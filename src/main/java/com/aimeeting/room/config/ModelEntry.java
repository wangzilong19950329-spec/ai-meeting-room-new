package com.aimeeting.room.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ModelEntry {
    /** 短 UUID，作为稳定 ID */
    private String id;
    /** 前端显示名，如 "GPT-4o（官方）" */
    private String displayName;
    /** 发给 API 的模型 ID，如 "gpt-4o" */
    private String modelId;
    /** 接口地址 */
    private String apiUrl;
    /** API Key（存储明文，返回前端时脱敏） */
    private String apiKey;
    /** 调用格式：openai | claude */
    private String apiFormat;
    private boolean enabled;
}
