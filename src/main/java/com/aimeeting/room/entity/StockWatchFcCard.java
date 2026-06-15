package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchFcCard {
    private Long id;
    private String symbol;
    private String market;
    private String stockName;
    private String cardKey;
    private String cardTitle;
    private String cardType;
    private String thesis;
    private String triggerLogic;
    private String dataSourceTier;
    private String dataSources;
    private String collectionMethod;
    private String frequency;
    private String parserSpec;
    private String baselineReading;
    private String failureMonitor;
    private String autoProxy;
    private String activationStatus;
    private String activationBlockers;
    private Boolean enabled;
    private Boolean notifyEnabled;
    private String lastCollectStatus;
    private String lastCollectNote;
    private Date lastCollectedAt;
    private String rawMarkdown;
    private Date gmtCreate;
    private Date gmtModify;
}
