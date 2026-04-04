package com.aimeeting.room.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class ModelConfigRequest {

    private Map<String, ModelEntry> models = new HashMap<>();
    private MeetingParams meeting;

    @Data
    public static class ModelEntry {
        private String apiUrl;
        private String apiKey;
        private String secretKey;
        private String apiVersion;
        private boolean enabled;
    }

    @Data
    public static class MeetingParams {
        private Double consensusThreshold;
        private Integer minAgreementCount;
        private Integer maxRoundsDefault;
        private Integer baseReward;
        private Integer basePenalty;
        private Double dissentPenaltyFactor;
    }
}
