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
    }
}
