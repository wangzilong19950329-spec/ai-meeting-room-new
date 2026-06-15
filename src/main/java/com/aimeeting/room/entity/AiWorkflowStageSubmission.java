package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class AiWorkflowStageSubmission {
    private Long id;
    private Long workflowRunId;
    private Long stageRunId;
    private Long meetingId;
    private String stageKey;
    private Integer attemptNo;
    private String actionType;
    private String actorRef;
    private String content;
    private String payloadJson;
    private Date gmtCreate;
}
