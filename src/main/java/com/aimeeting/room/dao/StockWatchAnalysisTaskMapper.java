package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchAnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

@Mapper
public interface StockWatchAnalysisTaskMapper {
    StockWatchAnalysisTask selectBySubmissionKey(@Param("submissionKey") String submissionKey);

    int insert(StockWatchAnalysisTask task);

    int markSubmitted(@Param("id") Long id,
                      @Param("expectedRowVersion") Long expectedRowVersion,
                      @Param("provider") String provider,
                      @Param("submittedAt") Date submittedAt);

    int complete(@Param("id") Long id,
                 @Param("status") String status,
                 @Param("responseHash") String responseHash,
                 @Param("rawResponse") String rawResponse,
                 @Param("contentStatus") String contentStatus,
                 @Param("completionReason") String completionReason,
                 @Param("providerStatus") String providerStatus,
                 @Param("completedAt") Date completedAt,
                 @Param("errorMessage") String errorMessage);
}
