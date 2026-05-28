package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingRoomMapper {

    int insert(AiMeetingRoom record);

    AiMeetingRoom selectById(Long id);

    int updateStatus(@Param("id") Long id, @Param("status") int status);

    int updateConclusion(AiMeetingRoom record);

    int updateCurrentRound(@Param("id") Long id, @Param("currentRound") int currentRound);

    int updateSummary(@Param("id") Long id, @Param("summary") String summary);

    int updateDecisionArtifact(@Param("id") Long id, @Param("decisionArtifact") String decisionArtifact);

    int updateRoundSummaries(@Param("id") Long id, @Param("roundSummaries") String roundSummaries);

    int updateConclusionHistory(@Param("id") Long id, @Param("conclusionHistory") String conclusionHistory);

    int resetForRestart(Long id);

    List<AiMeetingRoom> selectByCreatorId(Long creatorId);

    List<AiMeetingRoom> selectRecentConcludedByProject(@Param("creatorId") Long creatorId,
                                                       @Param("projectKey") String projectKey);
}
