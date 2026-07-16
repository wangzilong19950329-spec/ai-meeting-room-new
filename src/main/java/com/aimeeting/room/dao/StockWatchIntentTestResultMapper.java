package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchIntentTestResult;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockWatchIntentTestResultMapper {
    int insert(StockWatchIntentTestResult result);
}
