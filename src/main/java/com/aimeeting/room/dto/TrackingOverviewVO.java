package com.aimeeting.room.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class TrackingOverviewVO {
    private Integer totalCount = 0;
    private Integer autoRefreshCount = 0;
    private Integer dueSoonCount = 0;
    private Integer materialChangeCount = 0;
    private Integer meetingLinkedCount = 0;
    private Integer customCount = 0;
    private List<TrackingItemVO> items = new ArrayList<>();

    @Data
    public static class TrackingItemVO {
        private Long id;
        private Long creatorId;
        private Long meetingId;
        private String meetingTitle;
        private Integer meetingStatus;
        private String meetingStatusName;
        private String sourceType;
        private String itemType;
        private String title;
        private String description;
        private String status;
        private String statusName;
        private String ownerType;
        private String ownerRef;
        private Boolean reopenTrigger;
        private Boolean requiredForContinue;
        private String reopenPolicy;
        private Boolean autoRefreshEnabled;
        private String refreshMode;
        private Integer refreshIntervalHours;
        private String watchQuery;
        private String watchScope;
        private Date nextCheckAt;
        private Date lastCheckedAt;
        private String lastCheckStatus;
        private String lastCheckNote;
        private String changeSeverity;
        private Date dueAt;
        private Date lastUpdateAt;
        private Date closedAt;
        private String latestUpdateSummary;
        private Integer todayUpdateCount;
        private List<TrackingUpdateVO> todayUpdates = new ArrayList<>();
        private List<TrackingUpdateVO> updates = new ArrayList<>();
    }

    @Data
    public static class TrackingUpdateVO {
        private Long id;
        private String updateType;
        private String statusBefore;
        private String statusAfter;
        private String content;
        private String evidenceRef;
        private Date gmtCreate;
    }
}
