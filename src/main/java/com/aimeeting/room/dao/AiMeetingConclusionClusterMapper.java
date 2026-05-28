package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingConclusionCluster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingConclusionClusterMapper {

    int insert(AiMeetingConclusionCluster record);

    int batchInsert(List<AiMeetingConclusionCluster> records);

    List<AiMeetingConclusionCluster> selectByMeetingIdAndRound(@Param("meetingId") Long meetingId,
                                                                @Param("roundNumber") int roundNumber);

    AiMeetingConclusionCluster selectConsensusCluster(@Param("meetingId") Long meetingId,
                                                       @Param("roundNumber") int roundNumber);

    int deleteByMeetingId(Long meetingId);
}
