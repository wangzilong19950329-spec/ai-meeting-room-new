package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface StockWatchTechnicalAnalysisMapper {
    int insert(StockWatchTechnicalAnalysis record);

    int updateRecovered(StockWatchTechnicalAnalysis record);

    int updateAbandonedDiagnostic(StockWatchTechnicalAnalysis record);

    StockWatchTechnicalAnalysis selectById(@Param("id") Long id);

    StockWatchTechnicalAnalysis selectByAnalysisTaskId(@Param("analysisTaskId") Long analysisTaskId);

    StockWatchTechnicalAnalysis selectLatestBySymbolDateSlot(@Param("symbol") String symbol,
                                                             @Param("market") String market,
                                                             @Param("analysisDate") Date analysisDate,
                                                             @Param("runSlot") String runSlot,
                                                             @Param("periodType") String periodType,
                                                             @Param("periodKey") String periodKey);

    List<StockWatchTechnicalAnalysis> selectBySymbolDateRange(@Param("symbol") String symbol,
                                                              @Param("market") String market,
                                                              @Param("startDate") Date startDate,
                                                              @Param("endDate") Date endDate,
                                                              @Param("periodType") String periodType);

    List<StockWatchTechnicalAnalysis> selectBySymbolPeriodKeys(@Param("symbol") String symbol,
                                                               @Param("market") String market,
                                                               @Param("periodType") String periodType,
                                                               @Param("periodKeys") List<String> periodKeys);

    List<StockWatchTechnicalAnalysis> selectRecentBySymbol(@Param("symbol") String symbol,
                                                           @Param("market") String market,
                                                           @Param("periodType") String periodType,
                                                           @Param("limit") int limit);

    StockWatchTechnicalAnalysis selectLatestSuccessfulBySymbolSince(@Param("symbol") String symbol,
                                                                    @Param("market") String market,
                                                                    @Param("periodType") String periodType,
                                                                    @Param("since") Date since);

    StockWatchTechnicalAnalysis selectLatestSuccessfulBySession(@Param("sessionId") Long sessionId,
                                                                @Param("symbol") String symbol,
                                                                @Param("market") String market,
                                                                @Param("periodType") String periodType,
                                                                @Param("maxAnalysisDate") Date maxAnalysisDate,
                                                                @Param("maxRunSlot") String maxRunSlot);

    List<StockWatchTechnicalAnalysis> selectSuccessfulByDateRange(@Param("symbol") String symbol,
                                                                  @Param("market") String market,
                                                                  @Param("startDate") Date startDate,
                                                                  @Param("endDate") Date endDate,
                                                                  @Param("periodType") String periodType,
                                                                  @Param("limit") int limit);

    List<StockWatchTechnicalAnalysis> selectLatestSuccessfulDailyByDateRange(@Param("symbol") String symbol,
                                                                             @Param("market") String market,
                                                                             @Param("startDate") Date startDate,
                                                                             @Param("endDate") Date endDate,
                                                                             @Param("periodType") String periodType,
                                                                             @Param("limit") int limit);

    List<StockWatchTechnicalAnalysis> selectRecentSuccessfulSlotsByDateRange(@Param("symbol") String symbol,
                                                                             @Param("market") String market,
                                                                             @Param("startDate") Date startDate,
                                                                             @Param("endDate") Date endDate,
                                                                             @Param("periodType") String periodType,
                                                                             @Param("limit") int limit);

    List<StockWatchTechnicalAnalysis> selectSuccessfulSince(@Param("symbol") String symbol,
                                                            @Param("market") String market,
                                                            @Param("activeFrom") Date activeFrom,
                                                            @Param("limit") int limit);
}
