package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

/** One checkpointed product of a roundtable v2 meeting (issue card, fact base, member answer or result). */
@Data
public class AiMeetingV2Record {
    private Long id;
    private Long meetingId;
    private String kind;
    /** 0 for meeting-level records. */
    private Long memberId;
    private String payload;
    private Long runEpoch;
    private Date gmtCreate;
}
