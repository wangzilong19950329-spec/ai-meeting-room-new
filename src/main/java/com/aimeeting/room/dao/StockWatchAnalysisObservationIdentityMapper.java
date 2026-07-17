package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchAnalysisObservationIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockWatchAnalysisObservationIdentityMapper {
    int insertIfAbsent(StockWatchAnalysisObservationIdentity identity);

    StockWatchAnalysisObservationIdentity selectByIdentityKey(@Param("identityKey") String identityKey);

    StockWatchAnalysisObservationIdentity selectByIdentityKeyForUpdate(@Param("identityKey") String identityKey);

    int bindCanonical(@Param("id") Long id,
                      @Param("expectedRowVersion") Long expectedRowVersion,
                      @Param("canonicalTaskId") Long canonicalTaskId,
                      @Param("migrationState") String migrationState);
}
