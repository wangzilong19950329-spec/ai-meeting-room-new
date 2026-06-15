package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMeetingTrackingItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMeetingTrackingItemMapper {
    int insert(AiMeetingTrackingItem record);
    int batchInsert(List<AiMeetingTrackingItem> records);
    AiMeetingTrackingItem selectById(@Param("id") Long id);
    List<AiMeetingTrackingItem> selectByMeetingId(@Param("meetingId") Long meetingId);
    List<AiMeetingTrackingItem> selectAll();
    List<AiMeetingTrackingItem> selectDueForRefresh(@Param("now") java.util.Date now,
                                                    @Param("limit") int limit);
    AiMeetingTrackingItem selectByMeetingIdAndTitle(@Param("meetingId") Long meetingId,
                                                    @Param("title") String title);
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("lastUpdateAt") java.util.Date lastUpdateAt,
                     @Param("closedAt") java.util.Date closedAt);
    int updateRefreshState(@Param("id") Long id,
                           @Param("nextCheckAt") java.util.Date nextCheckAt,
                           @Param("lastCheckedAt") java.util.Date lastCheckedAt,
                           @Param("lastCheckStatus") String lastCheckStatus,
                           @Param("lastCheckNote") String lastCheckNote,
                           @Param("lastCheckFingerprint") String lastCheckFingerprint,
                           @Param("changeSeverity") String changeSeverity,
                           @Param("lastUpdateAt") java.util.Date lastUpdateAt);
    int deleteByMeetingId(@Param("meetingId") Long meetingId);
}
