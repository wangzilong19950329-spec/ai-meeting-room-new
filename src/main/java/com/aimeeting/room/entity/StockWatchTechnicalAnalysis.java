package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchTechnicalAnalysis {
    private Long id;
    private Long sessionId;
    private String symbol;
    private String market;
    private String stockName;
    private Date analysisDate;
    private String runSlot;
    private String periodType;
    private String periodKey;
    private String sourceRecordIds;
    private String status;
    private String stance;
    private String tone;
    private Double confidence;
    private String title;
    private String conclusion;
    private String evidenceJson;
    private String rawResponse;
    private String promptHash;
    private String provider;
    private String bridgeTargetId;
    private String conversationUrl;
    private Integer contextCharsDelta;
    private String errorMessage;
    private Date gmtCreate;
}
