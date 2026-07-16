package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchFactHead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockWatchFactHeadMapper {
    StockWatchFactHead selectBySymbolMarket(@Param("symbol") String symbol, @Param("market") String market);

    StockWatchFactHead selectForUpdate(@Param("symbol") String symbol, @Param("market") String market);

    int insert(StockWatchFactHead head);

    int updateLatest(@Param("id") Long id,
                     @Param("expectedRowVersion") Long expectedRowVersion,
                     @Param("arrivalVersion") Long arrivalVersion,
                     @Param("latestObservationId") Long latestObservationId,
                     @Param("lastSourceTimeEpochMs") Long lastSourceTimeEpochMs,
                     @Param("lastSourceDate") String lastSourceDate,
                     @Param("lastSourceQuality") String lastSourceQuality,
                     @Param("lastFactHash") String lastFactHash);

    int updateArrivalVersion(@Param("id") Long id,
                             @Param("expectedRowVersion") Long expectedRowVersion,
                             @Param("arrivalVersion") Long arrivalVersion);
}
