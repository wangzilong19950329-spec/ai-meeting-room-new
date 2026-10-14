package com.aimeeting.room.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiModelProperties {

    private String defaultModel = "gpt-4";
    private Map<String, ModelConfig> models = new HashMap<>();
    private MeetingConfig meeting = new MeetingConfig();

    @Data
    public static class ModelConfig {
        private String apiUrl;
        private String apiKey;
        private String secretKey;
        private String apiVersion;
        private boolean enabled = false;
    }

    @Data
    public static class MeetingConfig {
        private int asyncThreadPoolSize = 5;
        private int maxRoundsDefault = 10;
        private double consensusThreshold = 0.70;
        private int minAgreementCount = 2;
        private int baseReward = 100;
        private int basePenalty = 50;
        private double dissentPenaltyFactor = 0.3;
        private DeepSeekWebBridgeConfig deepseekWebBridge = new DeepSeekWebBridgeConfig();
    }

    @Data
    public static class DeepSeekWebBridgeConfig {
        private boolean enabled = false;
        private String bridgeUrl = "http://127.0.0.1:8789/search";
        private String apiKey;
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 480;
        private String mode = "expert";
        private String searchMode = "fast";
        private boolean serialized = false;
    }
}
