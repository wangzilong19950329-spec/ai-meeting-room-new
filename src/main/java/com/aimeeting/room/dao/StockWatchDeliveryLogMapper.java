package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchDeliveryLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface StockWatchDeliveryLogMapper {
    int insert(StockWatchDeliveryLog record);

    StockWatchDeliveryLog selectByAnalysisRecipient(@Param("analysisId") Long analysisId,
                                                    @Param("recipientHash") String recipientHash);

    int resetPending(@Param("id") Long id);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("messageId") String messageId,
                     @Param("errorMessage") String errorMessage);

    List<Map<String, Object>> countByConfig(@Param("configId") Long configId);
}
