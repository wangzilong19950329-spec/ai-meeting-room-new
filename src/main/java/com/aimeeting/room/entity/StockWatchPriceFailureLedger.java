package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchPriceFailureLedger {
    private Long id;
    private String sampleKey;
    private String signalDayKey;
    private String ruleVersion;
    private String ruleSpecHash;
    private String sampleRole;
    private Long matchedSignalId;
    private Long originAssessmentId;
    private Long originObservationId;
    private String symbol;
    private String market;
    private String stockName;
    private String stratum;
    private String sourceDate;
    private Long sourceTimeEpochMs;
    private Long availableTimeEpochMs;
    private Date availableAt;
    private Double signalPrice;
    private String featureSnapshotJson;
    private Double matchScore;
    private Boolean controlsFinalized;
    private String status;
    private Long entryObservationId;
    private Long entrySourceTimeEpochMs;
    private String entrySourceDate;
    private Double entryPrice;
    private String t1TradeDate;
    private Double t1Open;
    private Double t1High;
    private Double t1Low;
    private Double t1Close;
    private Double t1MaxDrawdownPct;
    private Double t1CloseReturnPct;
    private Double t1GapPct;
    private Double t1MaxUpsidePct;
    private Boolean t1Drawdown5Hit;
    private String dataQualityReason;
    private Long rowVersion;
    private Date maturedAt;
    private Date gmtCreate;
    private Date gmtModify;
}
