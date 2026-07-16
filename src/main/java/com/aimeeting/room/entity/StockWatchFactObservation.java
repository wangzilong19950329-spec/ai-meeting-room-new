package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchFactObservation {
    private Long id;
    private String symbol;
    private String market;
    private String stockName;
    private String observationKey;
    private Long arrivalVersion;
    private Long sourceTimeEpochMs;
    private String sourceDate;
    private String sourceTimeQuality;
    private String factHash;
    private String factSnapshotJson;
    private String dataQualityJson;
    private Date sampledAt;
    private Date gmtCreate;
}
