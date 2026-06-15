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

    /** 统计全部会议中以该名称参会的议员数量（用于模版占用提示，议员与模版为复制关系，按名称近似匹配） */
    int countByMemberName(@Param("name") String name);

    int updateTotalScore(@Param("id") Long id, @Param("totalScore") int totalScore);

    int deleteByMeetingId(@Param("meetingId") Long meetingId);
}
