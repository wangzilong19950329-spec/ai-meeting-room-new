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

    /** "count-maxId-totalPayloadLength" of a meeting's records: changes when one is added or rewritten. */
    String selectVersion(@Param("meetingId") Long meetingId);

    int deleteByMeetingId(@Param("meetingId") Long meetingId);

    /** Only for records whose state changes, such as a queued user intervention once it is applied. */
    int updatePayload(@Param("id") Long id, @Param("payload") String payload);
}
