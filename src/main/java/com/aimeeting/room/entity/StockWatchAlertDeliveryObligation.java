package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchAlertDeliveryObligation {
    private Long id;
    private String eventKey;
    private String openEpisodeKey;
    private Long configId;
    private String ruleId;
    private String symbol;
    private String market;
    private String stockName;
    private String recipientHash;
    private String recipientAddress;
    private String senderSnapshotJson;
    private Long targetFactObservationId;
    private String targetFactObservationKey;
    private Long targetSourceTimeEpochMs;
    private Date tradeDate;
    private String signalFingerprint;
    private Long inlineAnalysisId;
    private Long analysisDemandId;
    private Date deadlineAt;
    private Date episodeSuppressedUntil;
    private String initialSubject;
    private String initialBody;
    private String initialMessageId;
    private String initialStatus;
    private Integer initialAttemptCount;
    private Date initialNextAttemptAt;
    private String initialLeaseToken;
    private Date initialLeaseUntil;
    private Date initialSentAt;
    private String initialError;
    private Long supplementAnalysisId;
    private String supplementSubject;
    private String supplementBody;
    private String supplementMessageId;
    private String supplementStatus;
    private Integer supplementAttemptCount;
    private Date supplementNextAttemptAt;
    private String supplementLeaseToken;
    private Date supplementLeaseUntil;
    private Date supplementSentAt;
    private String supplementError;
    private Long rowVersion;
    private Date gmtCreate;
    private Date gmtModify;
}
