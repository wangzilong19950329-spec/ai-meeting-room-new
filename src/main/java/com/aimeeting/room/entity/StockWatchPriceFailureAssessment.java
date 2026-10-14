package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchPriceFailureAssessment {
    private Long id;
    private String ruleVersion;
    private String ruleSpecHash;
    private Long observationId;
    private String symbol;
    private String market;
    private String stockName;
    private String sourceDate;
    private Long sourceTimeEpochMs;
    private Long availableTimeEpochMs;
    private Date evaluatedAt;
    private Boolean eligible;
    private Boolean highPosition;
    private Boolean activeTurnover;
    private Boolean currentNotCollapsed;
    private Boolean priceShapeTriggered;
    private Boolean flowDataComplete;
    private Boolean strongOutflow;
    private String shapeCode;
    private String stratum;
    private Double signalPrice;
    private String featureSnapshotJson;
    private String exclusionReason;
    private Date gmtCreate;
}
