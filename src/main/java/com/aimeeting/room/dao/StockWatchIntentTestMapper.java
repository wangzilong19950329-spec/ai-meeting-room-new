package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchIntentTest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockWatchIntentTestMapper {
    int insert(StockWatchIntentTest test);

    List<StockWatchIntentTest> selectActiveForUpdate(@Param("symbol") String symbol,
                                                     @Param("market") String market,
                                                     @Param("beforeObservationId") Long beforeObservationId);

    int updateProgress(@Param("id") Long id,
                       @Param("expectedRowVersion") Long expectedRowVersion,
                       @Param("ageCount") Integer ageCount,
                       @Param("observedCount") Integer observedCount,
                       @Param("testVerdict") String testVerdict,
                       @Param("lastEvaluatedObservationId") Long lastEvaluatedObservationId,
                       @Param("resolvedObservationId") Long resolvedObservationId);
}
