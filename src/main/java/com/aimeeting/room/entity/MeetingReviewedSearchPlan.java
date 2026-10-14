package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

/** One immutable material request plan for a member's turn. */
@Data
public class MeetingReviewedSearchPlan {
    private Long id;
    private Long meetingId;
    private Integer roundNumber;
    private Long memberId;
    /** Zero means an ordinary turn; otherwise the targeted question's opinion ID. */
    private Long targetQuestionId;
    private String asOf;
    /** Latest human opinion ID in this round when this plan was made; zero means none. */
    private Long contextOpinionId;
    /** Prior immutable plan for this member turn, if a new day or human input required replanning. */
    private Long supersedesPlanId;
    private String normalizedPlanJson;
    private Date gmtCreate;
}
