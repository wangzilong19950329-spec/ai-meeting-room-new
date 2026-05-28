package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingTrackingUpdate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingTrackingUpdateMapper {
    int insert(AiMeetingTrackingUpdate record);
    List<AiMeetingTrackingUpdate> selectByTrackingItemId(@Param("trackingItemId") Long trackingItemId);
}
