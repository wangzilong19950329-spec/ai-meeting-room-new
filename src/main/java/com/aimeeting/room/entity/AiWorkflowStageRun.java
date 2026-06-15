package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class AiWorkflowStageRun {
    private Long id;
    private Long workflowRunId;
    private Long meetingId;
    private Long stageTemplateId;
    private String stageKey;
    private String stageName;
    private Integer stageOrder;
    private String stageType;
    private String actorType;
    private Integer attemptNo;
    private String status;
    private String handoffSummary;
    private String handoffPayload;
    private String outcome;
    private String rejectReason;
    private Date startedAt;
    private Date completedAt;
    private Date gmtCreate;
    private Date gmtModify;
}
