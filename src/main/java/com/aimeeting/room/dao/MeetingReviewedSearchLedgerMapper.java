package com.aimeeting.room.dao;

import com.aimeeting.room.entity.MeetingReviewedSearchGap;
import com.aimeeting.room.entity.MeetingReviewedSearchPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MeetingReviewedSearchLedgerMapper {
    MeetingReviewedSearchPlan findPlan(@Param("meetingId") Long meetingId,
                                       @Param("roundNumber") int roundNumber,
                                       @Param("memberId") Long memberId,
                                       @Param("targetQuestionId") Long targetQuestionId);

    MeetingReviewedSearchPlan findPlanVersion(@Param("meetingId") Long meetingId,
                                              @Param("roundNumber") int roundNumber,
                                              @Param("memberId") Long memberId,
                                              @Param("targetQuestionId") Long targetQuestionId,
                                              @Param("asOf") String asOf,
                                              @Param("contextOpinionId") Long contextOpinionId);

    List<MeetingReviewedSearchPlan> listPlansByMeetingId(@Param("meetingId") Long meetingId);

    List<MeetingReviewedSearchGap> listGapsByMeetingId(@Param("meetingId") Long meetingId);

    int insertPlan(MeetingReviewedSearchPlan plan);

    MeetingReviewedSearchGap findGap(@Param("meetingId") Long meetingId,
                                     @Param("roundNumber") int roundNumber,
                                     @Param("asOf") String asOf,
                                     @Param("questionHash") String questionHash);

    MeetingReviewedSearchGap findGapById(@Param("gapId") Long gapId);

    int insertGap(MeetingReviewedSearchGap gap);

    int claimPendingGap(@Param("gapId") Long gapId);

    int releaseUnsubmittedGap(@Param("gapId") Long gapId);

    int markCrossChecked(@Param("gapId") Long gapId, @Param("packetJson") String packetJson);

    int markLowConfidence(@Param("gapId") Long gapId, @Param("packetJson") String packetJson);

    int markUnknown(@Param("gapId") Long gapId, @Param("reason") String reason);

    int markRejected(@Param("gapId") Long gapId, @Param("reason") String reason);

    int deletePlansByMeetingId(@Param("meetingId") Long meetingId);

    int deleteGapsByMeetingId(@Param("meetingId") Long meetingId);
}
