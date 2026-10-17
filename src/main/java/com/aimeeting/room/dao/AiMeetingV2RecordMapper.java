package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingV2Record;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingV2RecordMapper {
    int insert(AiMeetingV2Record record);

    AiMeetingV2Record selectOne(@Param("meetingId") Long meetingId, @Param("kind") String kind,
                                @Param("memberId") Long memberId);

    List<AiMeetingV2Record> selectByMeetingId(@Param("meetingId") Long meetingId);

    int deleteByMeetingId(@Param("meetingId") Long meetingId);
}
