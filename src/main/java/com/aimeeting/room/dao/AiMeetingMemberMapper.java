package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingMemberMapper {

    int insert(AiMeetingMember record);

    int batchInsert(List<AiMeetingMember> records);

    AiMeetingMember selectById(Long id);

    List<AiMeetingMember> selectByMeetingId(Long meetingId);

    int countByMeetingId(Long meetingId);

    int updateTotalScore(@Param("id") Long id, @Param("totalScore") int totalScore);
}
