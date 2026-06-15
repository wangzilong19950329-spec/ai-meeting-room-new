package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchTechnicalSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

@Mapper
public interface StockWatchTechnicalSessionMapper {
    int insert(StockWatchTechnicalSession record);

    StockWatchTechnicalSession selectById(@Param("id") Long id);

    StockWatchTechnicalSession selectActive(@Param("symbol") String symbol,
                                            @Param("market") String market,
                                            @Param("periodType") String periodType);

    StockWatchTechnicalSession selectLatest(@Param("symbol") String symbol,
                                            @Param("market") String market,
                                            @Param("periodType") String periodType);

    int deactivateActive(@Param("symbol") String symbol,
                         @Param("market") String market,
                         @Param("periodType") String periodType,
                         @Param("status") String status,
                         @Param("lastError") String lastError);

    int updateAfterRun(@Param("id") Long id,
                       @Param("bridgeTargetId") String bridgeTargetId,
                       @Param("conversationUrl") String conversationUrl,
                       @Param("contextChars") Integer contextChars,
                       @Param("lastRunAt") Date lastRunAt,
                       @Param("lastRunStatus") String lastRunStatus,
                       @Param("lastError") String lastError);
}
