package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiWorkflowStageSubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiWorkflowStageSubmissionMapper {
    int insert(AiWorkflowStageSubmission record);
    List<AiWorkflowStageSubmission> selectByMeetingId(@Param("meetingId") Long meetingId);
    AiWorkflowStageSubmission selectLatestByStageRunId(@Param("stageRunId") Long stageRunId);
}
