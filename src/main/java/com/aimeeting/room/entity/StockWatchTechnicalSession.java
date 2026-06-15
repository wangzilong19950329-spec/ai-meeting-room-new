package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchTechnicalSession {
    private Long id;
    private String symbol;
    private String market;
    private String stockName;
    private String periodType;
    private String sessionKey;
    private String bridgeTargetId;
    private String conversationUrl;
    private String status;
    private Integer generation;
    private Integer contextChars;
    private Integer contextLimitChars;
    private Date lastRunAt;
    private String lastRunStatus;
    private String lastError;
    private Date gmtCreate;
    private Date gmtModify;
}
