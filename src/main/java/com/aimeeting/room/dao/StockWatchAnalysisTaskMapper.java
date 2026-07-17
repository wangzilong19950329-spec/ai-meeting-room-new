package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchAnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface StockWatchAnalysisTaskMapper {
    StockWatchAnalysisTask selectBySubmissionKey(@Param("submissionKey") String submissionKey);

    StockWatchAnalysisTask selectById(@Param("id") Long id);

    List<StockWatchAnalysisTask> selectByObservation(@Param("symbol") String symbol,
                                                     @Param("market") String market,
                                                     @Param("periodType") String periodType,
                                                     @Param("observationKey") String observationKey);

    List<StockWatchAnalysisTask> selectRecoverableBefore(@Param("before") Date before,
                                                         @Param("limit") int limit);

    int insert(StockWatchAnalysisTask task);

    int prepare(@Param("id") Long id,
                @Param("expectedRowVersion") Long expectedRowVersion,
                @Param("requestDigest") String requestDigest,
                @Param("generation") Integer generation,
                @Param("requestEnvelopeJson") String requestEnvelopeJson);

    int markSubmitted(@Param("id") Long id,
                      @Param("expectedRowVersion") Long expectedRowVersion,
                      @Param("provider") String provider,
                      @Param("submittedAt") Date submittedAt);

    int transition(@Param("id") Long id,
                 @Param("expectedRowVersion") Long expectedRowVersion,
                 @Param("fromStatuses") List<String> fromStatuses,
                 @Param("status") String status,
                 @Param("responseHash") String responseHash,
                 @Param("responseEnvelopeHash") String responseEnvelopeHash,
                 @Param("responseEnvelopeJson") String responseEnvelopeJson,
                 @Param("rawResponse") String rawResponse,
                 @Param("contentStatus") String contentStatus,
                 @Param("completionReason") String completionReason,
                 @Param("providerStatus") String providerStatus,
                 @Param("completedAt") Date completedAt,
                 @Param("errorMessage") String errorMessage);
}
