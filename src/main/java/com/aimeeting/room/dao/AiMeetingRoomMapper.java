package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingRoomMapper {

    int insert(AiMeetingRoom record);

    AiMeetingRoom selectById(Long id);

    AiMeetingRoom selectByIdForUpdate(Long id);

    int updateStatus(@Param("id") Long id, @Param("status") int status);

    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("expectedStatus") int expectedStatus,
                              @Param("status") int status);

    int claimReviewedRun(@Param("id") Long id,
                         @Param("expectedStatus") int expectedStatus,
                         @Param("expectedEpoch") long expectedEpoch,
                         @Param("status") int status);

    int updateStatusIfCurrentAndEpoch(@Param("id") Long id,
                                      @Param("expectedStatus") int expectedStatus,
                                      @Param("expectedEpoch") long expectedEpoch,
                                      @Param("status") int status);

    int bumpEpochIfStatusAndEpoch(@Param("id") Long id,
                                  @Param("expectedStatus") int expectedStatus,
                                  @Param("expectedEpoch") long expectedEpoch);

    int updateConclusion(AiMeetingRoom record);

    int updateCurrentRound(@Param("id") Long id, @Param("currentRound") int currentRound);

    int updateCurrentRoundIfEpoch(@Param("id") Long id,
                                  @Param("expectedEpoch") long expectedEpoch,
                                  @Param("currentRound") int currentRound);

    int updateSummaryIfEpoch(@Param("id") Long id,
                             @Param("expectedEpoch") long expectedEpoch,
                             @Param("summary") String summary);

    int updateDecisionArtifactIfEpoch(@Param("id") Long id,
                                      @Param("expectedEpoch") long expectedEpoch,
                                      @Param("decisionArtifact") String decisionArtifact);

    int updateRoundSummariesIfEpoch(@Param("id") Long id,
                                    @Param("expectedEpoch") long expectedEpoch,
                                    @Param("roundSummaries") String roundSummaries);

    int updateConclusionHistoryIfEpoch(@Param("id") Long id,
                                       @Param("expectedEpoch") long expectedEpoch,
                                       @Param("conclusionHistory") String conclusionHistory);

    int updateConclusionIfEpoch(@Param("id") Long id,
                                @Param("expectedEpoch") long expectedEpoch,
                                @Param("finalConclusion") String finalConclusion,
                                @Param("conclusionTime") java.util.Date conclusionTime,
                                @Param("status") int status);

    int updateSummary(@Param("id") Long id, @Param("summary") String summary);

    int updateDecisionArtifact(@Param("id") Long id, @Param("decisionArtifact") String decisionArtifact);

    int updateRoundSummaries(@Param("id") Long id, @Param("roundSummaries") String roundSummaries);

    int updateConclusionHistory(@Param("id") Long id, @Param("conclusionHistory") String conclusionHistory);

    int resetForRestart(Long id);

    int resetForRestartIfCurrentAndEpoch(@Param("id") Long id,
                                         @Param("expectedStatus") int expectedStatus,
                                         @Param("expectedEpoch") long expectedEpoch);

    List<AiMeetingRoom> selectByCreatorId(Long creatorId);

    List<AiMeetingRoom> selectRecentConcludedByProject(@Param("creatorId") Long creatorId,
                                                       @Param("projectKey") String projectKey);

    int deleteById(Long id);
}
