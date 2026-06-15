package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiWorkflowStageRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiWorkflowStageRunMapper {
    int insert(AiWorkflowStageRun record);
    int batchInsert(List<AiWorkflowStageRun> records);
    AiWorkflowStageRun selectById(@Param("id") Long id);
    List<AiWorkflowStageRun> selectByWorkflowRunId(@Param("workflowRunId") Long workflowRunId);
    List<AiWorkflowStageRun> selectByMeetingId(@Param("meetingId") Long meetingId);
    AiWorkflowStageRun selectLatestByMeetingAndStageKey(@Param("meetingId") Long meetingId,
                                                        @Param("stageKey") String stageKey);
    int updateExecution(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("handoffSummary") String handoffSummary,
                        @Param("handoffPayload") String handoffPayload,
                        @Param("outcome") String outcome,
                        @Param("rejectReason") String rejectReason,
                        @Param("startedAt") java.util.Date startedAt,
                        @Param("completedAt") java.util.Date completedAt);
    int updateHandoff(@Param("id") Long id,
                      @Param("handoffSummary") String handoffSummary,
                      @Param("handoffPayload") String handoffPayload);
    int deleteByMeetingId(@Param("meetingId") Long meetingId);
}
