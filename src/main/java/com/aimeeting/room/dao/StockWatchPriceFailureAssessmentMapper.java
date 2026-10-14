package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchPriceFailureAssessment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockWatchPriceFailureAssessmentMapper {
    int insert(StockWatchPriceFailureAssessment assessment);

    StockWatchPriceFailureAssessment selectByRuleObservation(@Param("ruleVersion") String ruleVersion,
                                                              @Param("observationId") Long observationId);

    List<StockWatchPriceFailureAssessment> selectControlCandidates(@Param("ruleVersion") String ruleVersion,
                                                                    @Param("market") String market,
                                                                    @Param("sourceDate") String sourceDate,
                                                                    @Param("minimumSourceTimeEpochMs") Long minimumSourceTimeEpochMs,
                                                                    @Param("maximumSourceTimeEpochMs") Long maximumSourceTimeEpochMs,
                                                                    @Param("maximumAvailableTimeEpochMs") Long maximumAvailableTimeEpochMs);

    int countDistinctSourceDates(@Param("ruleVersion") String ruleVersion);
}
