package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

/** Durable member-turn abstention; no opinion or vote is fabricated. */
@Data
public class MeetingReviewedTurnSkip {
    private Long id;
    private Long meetingId;
    private Integer roundNumber;
    private Long memberId;
    private Long targetQuestionId;
    private String reasonCode;
    private Long runEpoch;
    private String asOf;
    private Long contextOpinionId;
    private Long planId;
    private Long gapId;
    /** 1 while blocking the turn; null after an explicit, audited retry preparation. */
    private Integer activeTurn;
    private Date retryPreparedAt;
    private Long retryPreparedEpoch;
    private Date gmtCreate;
}
