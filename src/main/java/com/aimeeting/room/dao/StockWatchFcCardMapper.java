package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchFcCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockWatchFcCardMapper {
    int insert(StockWatchFcCard record);

    int update(StockWatchFcCard record);

    int deleteById(@Param("id") Long id);

    StockWatchFcCard selectById(@Param("id") Long id);

    StockWatchFcCard selectBySymbolCardKey(@Param("symbol") String symbol,
                                           @Param("market") String market,
                                           @Param("cardKey") String cardKey);

    List<StockWatchFcCard> selectBySymbolMarket(@Param("symbol") String symbol,
                                                @Param("market") String market);
}
