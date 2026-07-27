package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchAlertDeliveryObligation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface StockWatchAlertDeliveryObligationMapper {
    int insert(StockWatchAlertDeliveryObligation record);

    StockWatchAlertDeliveryObligation selectById(@Param("id") Long id);

    StockWatchAlertDeliveryObligation selectByEventRecipient(@Param("eventKey") String eventKey,
                                                             @Param("recipientHash") String recipientHash);

    List<StockWatchAlertDeliveryObligation> selectInitialDispatchable(@Param("now") Date now,
                                                                      @Param("limit") int limit);

    List<StockWatchAlertDeliveryObligation> selectSupplementDispatchable(@Param("now") Date now,
                                                                         @Param("limit") int limit);

    List<StockWatchAlertDeliveryObligation> selectWaitingByDemandId(@Param("demandId") Long demandId);

    int releaseExpiredEpisode(@Param("openEpisodeKey") String openEpisodeKey, @Param("now") Date now);

    int claimInitial(@Param("id") Long id,
                     @Param("expectedRowVersion") Long expectedRowVersion,
                     @Param("leaseToken") String leaseToken,
                     @Param("leaseUntil") Date leaseUntil,
                     @Param("now") Date now);

    int markInitialSent(@Param("id") Long id,
                        @Param("leaseToken") String leaseToken,
                        @Param("sentAt") Date sentAt,
                        @Param("suppressedUntil") Date suppressedUntil);

    int markInitialRetry(@Param("id") Long id,
                         @Param("leaseToken") String leaseToken,
                         @Param("nextAttemptAt") Date nextAttemptAt,
                         @Param("error") String error);

    int markInitialDeadLetter(@Param("id") Long id,
                              @Param("leaseToken") String leaseToken,
                              @Param("error") String error);

    int attachDemand(@Param("id") Long id,
                     @Param("demandId") Long demandId);

    int markSupplementReady(@Param("id") Long id,
                            @Param("demandId") Long demandId,
                            @Param("analysisId") Long analysisId,
                            @Param("subject") String subject,
                            @Param("body") String body);

    int claimSupplement(@Param("id") Long id,
                        @Param("expectedRowVersion") Long expectedRowVersion,
                        @Param("leaseToken") String leaseToken,
                        @Param("leaseUntil") Date leaseUntil,
                        @Param("now") Date now);

    int markSupplementSent(@Param("id") Long id,
                           @Param("leaseToken") String leaseToken,
                           @Param("sentAt") Date sentAt);

    int markSupplementRetry(@Param("id") Long id,
                            @Param("leaseToken") String leaseToken,
                            @Param("nextAttemptAt") Date nextAttemptAt,
                            @Param("error") String error);

    int markSupplementDeadLetterByDemand(@Param("demandId") Long demandId,
                                         @Param("error") String error);

    int markSupplementDeadLetter(@Param("id") Long id,
                                 @Param("leaseToken") String leaseToken,
                                 @Param("error") String error);

    int hasDurableCooldown(@Param("ruleId") String ruleId,
                           @Param("symbol") String symbol,
                           @Param("market") String market,
                           @Param("signalFingerprint") String signalFingerprint,
                           @Param("now") Date now);
}
