package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class AiMeetingTrackingUpdate {
    private Long id;
    private Long trackingItemId;
    private Long meetingId;
    private String updateType;
    private String statusBefore;
    private String statusAfter;
    private String content;
    private String evidenceRef;
    private Date gmtCreate;
}
