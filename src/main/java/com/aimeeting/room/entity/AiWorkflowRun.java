package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class AiWorkflowRun {
    private Long id;
    private Long meetingId;
    private Long templateId;
    private String templateKey;
    private String currentStageKey;
    private Integer currentStageOrder;
    private String status;
    private String blockedReason;
    private Integer reopenCount;
    private Date gmtCreate;
    private Date gmtModify;
}
