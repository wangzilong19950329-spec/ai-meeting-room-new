package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchIntentTestResult {
    private Long id;
    private Long observationId;
    private Long observationVersion;
    private Long testId;
    private String actualValueJson;
    private String matchStatus;
    private String verdictAfter;
    private Integer ageAfter;
    private Integer observedAfter;
    private Date evaluatedAt;
    private Date gmtCreate;
}
