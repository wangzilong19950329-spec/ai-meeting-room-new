package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class AiWorkflowStageTemplate {
    private Long id;
    private Long templateId;
    private String stageKey;
    private String stageName;
    private Integer stageOrder;
    private String stageType;
    private String actorType;
    private String actorBindingMode;
    private String actorSlotKey;
    private String completionRule;
    private String skillKey;
    private String skillPrompt;
    private String skillFileIds;
    private String searchStrategy;
    private String inputStageKeys;
    private String passTarget;
    private String rejectTarget;
    private String reopenTarget;
    private Integer autoStart;
    private Integer reviewRequired;
    private Integer maxReviewAttempts;
    private Date gmtCreate;
    private Date gmtModify;
}
