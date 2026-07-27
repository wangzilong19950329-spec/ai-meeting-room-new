package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchAnalysisDemand {
    private Long id;
    private String demandKey;
    private String symbol;
    private String market;
    private String stockName;
    private String capability;
    private String triggerType;
    private String scheduleSlotKey;
    private String runSlot;
    private String coalesceKey;
    private String activeCoalesceKey;
    private Long sourcePolicyVersion;
    private String reasonCodesJson;
    private String triggerContextJson;
    private String periodType;
    private Long targetFactObservationId;
    private String targetFactObservationKey;
    private Long targetSourceTimeEpochMs;
    private Date tradeDate;
    private Integer businessGeneration;
    private Long retryOfDemandId;
    private Long supersededByDemandId;
    private String status;
    private Integer priority;
    private Date notBefore;
    private Date deadlineAt;
    private Integer providerSubmitCount;
    private Long analysisTaskId;
    private Long analysisId;
    private String leaseToken;
    private Date leaseUntil;
    private String errorMessage;
    private Long rowVersion;
    private Date gmtCreate;
    private Date gmtModify;
}
