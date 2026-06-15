package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiWorkflowRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiWorkflowRunMapper {
    int insert(AiWorkflowRun record);
    AiWorkflowRun selectByMeetingId(@Param("meetingId") Long meetingId);
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("blockedReason") String blockedReason);
    int updateCurrentStage(@Param("id") Long id,
                           @Param("currentStageKey") String currentStageKey,
                           @Param("currentStageOrder") Integer currentStageOrder);
    int incrementReopenCount(@Param("id") Long id);
    int deleteByMeetingId(@Param("meetingId") Long meetingId);
}
