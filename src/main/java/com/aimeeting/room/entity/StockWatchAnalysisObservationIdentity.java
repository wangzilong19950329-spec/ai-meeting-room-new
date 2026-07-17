package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchAnalysisObservationIdentity {
    private Long id;
    private String identityKey;
    private String analysisScope;
    private String symbol;
    private String market;
    private String periodType;
    private String observationKey;
    private Long canonicalTaskId;
    private String migrationState;
    private Long rowVersion;
    private Date gmtCreate;
    private Date gmtModify;
}
