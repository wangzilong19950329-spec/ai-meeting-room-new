package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

/** One model or GEN-10 call made on behalf of a meeting. Token fields are null when unmetered. */
@Data
public class AiMeetingCallLog {
    private Long id;
    private Long meetingId;
    private Integer roundNumber;
    private Long memberId;
    private String stage;
    private String channel;
    private String model;
    private String status;
    private Long durationMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer cacheHitTokens;
    private Integer promptChars;
    private Integer completionChars;
    private Date gmtCreate;
}
