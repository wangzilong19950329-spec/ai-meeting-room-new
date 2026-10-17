package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingCallLogMapper {
    int insert(AiMeetingCallLog log);

    List<AiMeetingCallLog> selectByMeetingId(@Param("meetingId") Long meetingId);

    int deleteByMeetingId(@Param("meetingId") Long meetingId);
}
