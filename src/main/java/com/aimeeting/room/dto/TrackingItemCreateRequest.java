package com.aimeeting.room.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.Date;

@Data
public class TrackingItemCreateRequest {
    private Long creatorId;
    private Long meetingId;
    @NotBlank(message = "标题不能为空")
    private String title;
    private String description;
    private String itemType;
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
    private Date dueAt;
}
