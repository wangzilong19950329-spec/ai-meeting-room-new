package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchAnalysisDemand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface StockWatchAnalysisDemandMapper {
    int insert(StockWatchAnalysisDemand record);

    StockWatchAnalysisDemand selectById(@Param("id") Long id);

    StockWatchAnalysisDemand selectByDemandKey(@Param("demandKey") String demandKey);

    StockWatchAnalysisDemand selectActiveByCoalesceKey(@Param("activeCoalesceKey") String activeCoalesceKey);

    List<StockWatchAnalysisDemand> selectDispatchable(@Param("now") Date now, @Param("limit") int limit);

    List<StockWatchAnalysisDemand> selectSubmitted(@Param("limit") int limit);

    int claim(@Param("id") Long id,
              @Param("expectedRowVersion") Long expectedRowVersion,
              @Param("leaseToken") String leaseToken,
              @Param("leaseUntil") Date leaseUntil,
              @Param("now") Date now);

    int reserveProviderSubmission(@Param("id") Long id,
                                  @Param("leaseToken") String leaseToken);

    int markSupersededIfUnsubmitted(@Param("id") Long id,
                                    @Param("reason") String reason,
                                    @Param("now") Date now);

    int markClaimedSuperseded(@Param("id") Long id,
                              @Param("leaseToken") String leaseToken,
                              @Param("reason") String reason);

    int linkSuperseded(@Param("id") Long id,
                       @Param("supersededByDemandId") Long supersededByDemandId);

    int supersedePolicyMismatch(@Param("symbol") String symbol,
                                @Param("market") String market,
                                @Param("currentPolicyVersion") Long currentPolicyVersion,
                                @Param("reason") String reason);

    int markSubmitted(@Param("id") Long id,
                      @Param("leaseToken") String leaseToken,
                      @Param("analysisTaskId") Long analysisTaskId);

    int markSatisfied(@Param("id") Long id,
                      @Param("analysisTaskId") Long analysisTaskId,
                      @Param("analysisId") Long analysisId);

    int markDeadLetter(@Param("id") Long id,
                       @Param("error") String error);

    int releaseForRetry(@Param("id") Long id,
                        @Param("leaseToken") String leaseToken,
                        @Param("notBefore") Date notBefore,
                        @Param("error") String error);
}
