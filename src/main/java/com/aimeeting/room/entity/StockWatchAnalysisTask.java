package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchAnalysisTask {
    private Long id;
    private String submissionKey;
    private String requestDigest;
    private String purpose;
    private String symbol;
    private String market;
    private String periodType;
    private String observationKey;
    private Integer generation;
    private String status;
    private String provider;
    private String requestEnvelopeJson;
    private String responseHash;
    private String rawResponse;
    private String contentStatus;
    private String completionReason;
    private String providerStatus;
    private Date submittedAt;
    private Date completedAt;
    private String errorMessage;
    private Long rowVersion;
    private Date gmtCreate;
    private Date gmtModify;
}
