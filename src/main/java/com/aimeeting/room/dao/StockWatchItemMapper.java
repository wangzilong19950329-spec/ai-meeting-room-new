package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockWatchItemMapper {
    int insert(StockWatchItem record);

    int update(StockWatchItem record);

    StockWatchItem selectBySymbolMarket(@Param("symbol") String symbol,
                                        @Param("market") String market);

    List<StockWatchItem> selectEnabled();
}
