package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingOpinion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingOpinionMapper {

    int insert(AiMeetingOpinion record);

    List<AiMeetingOpinion> selectByMeetingId(Long meetingId);

    List<AiMeetingOpinion> selectByMeetingIdAndRound(@Param("meetingId") Long meetingId,
                                                      @Param("roundNumber") int roundNumber);

    int countByMeetingIdAndMemberId(@Param("meetingId") Long meetingId,
                                    @Param("memberId") Long memberId);

    int deleteByMeetingId(Long meetingId);
}
