package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchPriceFailureLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface StockWatchPriceFailureLedgerMapper {
    int insert(StockWatchPriceFailureLedger ledger);

    StockWatchPriceFailureLedger selectBySampleKey(@Param("sampleKey") String sampleKey);

    StockWatchPriceFailureLedger selectSignalBySymbolDate(@Param("ruleVersion") String ruleVersion,
                                                          @Param("symbol") String symbol,
                                                          @Param("market") String market,
                                                          @Param("sourceDate") String sourceDate);

    List<StockWatchPriceFailureLedger> selectSignalsNeedingControls(@Param("ruleVersion") String ruleVersion,
                                                                    @Param("limit") int limit);

    List<StockWatchPriceFailureLedger> selectControlsBySignal(@Param("matchedSignalId") Long matchedSignalId);

    int markControlsFinalized(@Param("id") Long id, @Param("rowVersion") Long rowVersion);

    List<StockWatchPriceFailureLedger> selectPending(@Param("ruleVersion") String ruleVersion,
                                                     @Param("limit") int limit);

    int updateOutcome(@Param("id") Long id,
                      @Param("rowVersion") Long rowVersion,
                      @Param("status") String status,
                      @Param("entryObservationId") Long entryObservationId,
                      @Param("entrySourceTimeEpochMs") Long entrySourceTimeEpochMs,
                      @Param("entrySourceDate") String entrySourceDate,
                      @Param("entryPrice") Double entryPrice,
                      @Param("t1TradeDate") String t1TradeDate,
                      @Param("t1Open") Double t1Open,
                      @Param("t1High") Double t1High,
                      @Param("t1Low") Double t1Low,
                      @Param("t1Close") Double t1Close,
                      @Param("t1MaxDrawdownPct") Double t1MaxDrawdownPct,
                      @Param("t1CloseReturnPct") Double t1CloseReturnPct,
                      @Param("t1GapPct") Double t1GapPct,
                      @Param("t1MaxUpsidePct") Double t1MaxUpsidePct,
                      @Param("t1Drawdown5Hit") Boolean t1Drawdown5Hit,
                      @Param("dataQualityReason") String dataQualityReason,
                      @Param("maturedAt") Date maturedAt);

    int countMatureSignals(@Param("ruleVersion") String ruleVersion);
}
