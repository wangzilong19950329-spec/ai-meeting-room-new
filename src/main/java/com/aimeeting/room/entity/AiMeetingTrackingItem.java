package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class AiMeetingTrackingItem {
    private Long id;
    private Long creatorId;
    private Long meetingId;
    private Long workflowRunId;
    private Long stageRunId;
    private String sourceType;
    private String itemType;
    private String title;
    private String description;
    private String status;
    private String ownerType;
    private String ownerRef;
    private Integer reopenTrigger;
    private Integer requiredForContinue;
    private String reopenPolicy;
    private Integer autoRefreshEnabled;
    private String refreshMode;
    private Integer refreshIntervalHours;
    private String watchQuery;
    private String watchScope;
    private Date nextCheckAt;
    private Date lastCheckedAt;
    private String lastCheckStatus;
    private String lastCheckNote;
    private String lastCheckFingerprint;
    private String changeSeverity;
    private Date dueAt;
    private Date lastUpdateAt;
    private Date closedAt;
    private Date gmtCreate;
    private Date gmtModify;
}
