package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingScoreLog;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AiMeetingScoreLogMapper {

    int insert(AiMeetingScoreLog record);

    List<AiMeetingScoreLog> selectByMeetingId(Long meetingId);

    List<AiMeetingScoreLog> selectByMemberId(Long memberId);

    int deleteByMeetingId(Long meetingId);
}
