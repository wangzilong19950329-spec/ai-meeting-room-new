package com.aimeeting.room.dao;

import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MeetingReviewedTurnSkipMapper {
    MeetingReviewedTurnSkip findById(@Param("id") Long id);

    MeetingReviewedTurnSkip findByTurn(@Param("meetingId") Long meetingId,
                                       @Param("roundNumber") int roundNumber,
                                       @Param("memberId") Long memberId,
                                       @Param("targetQuestionId") Long targetQuestionId);

    List<MeetingReviewedTurnSkip> listByMeetingAndRound(@Param("meetingId") Long meetingId,
                                                        @Param("roundNumber") int roundNumber);

    List<MeetingReviewedTurnSkip> listByMeetingId(@Param("meetingId") Long meetingId);

    int insert(MeetingReviewedTurnSkip skip);

    int prepareRetry(@Param("id") Long id, @Param("expectedEpoch") long expectedEpoch);

    int deleteByMeetingId(@Param("meetingId") Long meetingId);
}
