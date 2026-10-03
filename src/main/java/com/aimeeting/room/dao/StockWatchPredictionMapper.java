package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchPrediction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface StockWatchPredictionMapper {
    int insert(StockWatchPrediction prediction);

    List<StockWatchPrediction> selectByAnalysisId(@Param("analysisId") Long analysisId);

    List<StockWatchPrediction> selectRecent(@Param("symbol") String symbol,
                                            @Param("market") String market,
                                            @Param("limit") int limit);

    List<StockWatchPrediction> selectRecentBefore(@Param("symbol") String symbol,
                                                  @Param("market") String market,
                                                  @Param("beforeDate") Date beforeDate,
                                                  @Param("beforeId") Long beforeId,
                                                  @Param("limit") int limit);

    List<StockWatchPrediction> selectPending(@Param("limit") int limit);

    int updateEvaluation(@Param("id") Long id,
                         @Param("status") String status,
                         @Param("evaluatedTradeDate") Date evaluatedTradeDate,
                         @Param("actualClose") Double actualClose,
                         @Param("actualHigh") Double actualHigh,
                         @Param("actualLow") Double actualLow,
                         @Param("actualReturnPct") Double actualReturnPct,
                         @Param("directionHit") Boolean directionHit,
                         @Param("rangeCloseHit") Boolean rangeCloseHit,
                         @Param("rangeTouchHit") Boolean rangeTouchHit,
                         @Param("invalidated") Boolean invalidated,
                         @Param("absoluteErrorPct") Double absoluteErrorPct,
                         @Param("brierScore") Double brierScore,
                         @Param("evaluationNote") String evaluationNote,
                         @Param("evaluatedAt") Date evaluatedAt);
}
