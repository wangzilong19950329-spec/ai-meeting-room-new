package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingVote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingVoteMapper {

    int insert(AiMeetingVote record);

    List<AiMeetingVote> selectByMeetingIdAndRound(@Param("meetingId") Long meetingId,
                                                   @Param("roundNumber") int roundNumber);

    AiMeetingVote selectByMeetingMemberRound(@Param("meetingId") Long meetingId,
                                              @Param("memberId") Long memberId,
                                              @Param("roundNumber") int roundNumber);
}
