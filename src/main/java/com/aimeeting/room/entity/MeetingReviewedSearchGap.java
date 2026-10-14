package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

/** Durable, at-most-one automatic dispatch of an exact reviewed-search question. */
@Data
public class MeetingReviewedSearchGap {
    private Long id;
    private Long meetingId;
    private Integer roundNumber;
    private String asOf;
    private String questionHash;
    private String exactQuestion;
    private String requestId;
    private String status;
    private String packetJson;
    private String statusReason;
    private Date gmtCreate;
    private Date gmtModify;
}
