package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchPrediction {
    private Long id;
    private Long analysisId;
    private String symbol;
    private String market;
    private String stockName;
    private String horizonCode;
    private Integer horizonTradingDays;
    private Date forecastDate;
    private Double sourcePrice;
    private String direction;
    private Double probability;
    private Double targetLow;
    private Double targetHigh;
    private Double expectedReturnLow;
    private Double expectedReturnHigh;
    private Double invalidationPrice;
    private String rationale;
    private String inputSnapshotJson;
    private String status;
    private Date evaluatedTradeDate;
    private Double actualClose;
    private Double actualHigh;
    private Double actualLow;
    private Double actualReturnPct;
    private Boolean directionHit;
    private Boolean rangeCloseHit;
    private Boolean rangeTouchHit;
    private Boolean invalidated;
    private Double absoluteErrorPct;
    private Double brierScore;
    private String evaluationNote;
    private Date evaluatedAt;
    private Date gmtCreate;
    private Date gmtModify;
}
