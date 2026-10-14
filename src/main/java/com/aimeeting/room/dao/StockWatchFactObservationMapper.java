package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchFactObservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockWatchFactObservationMapper {
    int insert(StockWatchFactObservation observation);

    StockWatchFactObservation selectById(@Param("id") Long id);

    StockWatchFactObservation selectByObservationKey(@Param("observationKey") String observationKey);

    Long selectMaxId();

    List<StockWatchFactObservation> selectAfterId(@Param("afterId") Long afterId,
                                                   @Param("limit") int limit);

    List<StockWatchFactObservation> selectForOutcome(@Param("symbol") String symbol,
                                                      @Param("market") String market,
                                                      @Param("afterSourceTimeEpochMs") Long afterSourceTimeEpochMs,
                                                      @Param("limit") int limit);

    String selectNextObservedSourceDate(@Param("sourceDate") String sourceDate);

    String selectMaxSourceDate();
}
