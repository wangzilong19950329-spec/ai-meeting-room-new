package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchDeliveryConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface StockWatchDeliveryConfigMapper {
    int insert(StockWatchDeliveryConfig record);

    int update(StockWatchDeliveryConfig record);

    StockWatchDeliveryConfig selectBySymbolMarket(@Param("symbol") String symbol,
                                                  @Param("market") String market);

    List<StockWatchDeliveryConfig> selectEnabled();

    int updateDispatchStatus(@Param("id") Long id,
                             @Param("status") String status,
                             @Param("message") String message,
                             @Param("dispatchAt") Date dispatchAt);
}
