package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchFactObservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockWatchFactObservationMapper {
    int insert(StockWatchFactObservation observation);

    StockWatchFactObservation selectById(@Param("id") Long id);

    StockWatchFactObservation selectByObservationKey(@Param("observationKey") String observationKey);
}
