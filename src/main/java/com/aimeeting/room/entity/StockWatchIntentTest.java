package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchIntentTest {
    private Long id;
    private String symbol;
    private String market;
    private Long originObservationId;
    private Long activationAfterObservationId;
    private String testKey;
    private String claimRef;
    private String metricPath;
    private String comparisonOperator;
    private String targetValueJson;
    private Integer maxObservationAge;
    private Integer requiredObservedCount;
    private Integer ageCount;
    private Integer observedCount;
    private String testVerdict;
    private Long rowVersion;
    private Long lastEvaluatedObservationId;
    private String lastEvaluatedSourceDate;
    private Long resolvedObservationId;
    private Date gmtCreate;
    private Date gmtModify;
}
