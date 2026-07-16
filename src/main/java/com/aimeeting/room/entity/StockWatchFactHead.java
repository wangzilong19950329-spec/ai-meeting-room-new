package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchFactHead {
    private Long id;
    private String symbol;
    private String market;
    private Long arrivalVersion;
    private Long latestObservationId;
    private Long lastSourceTimeEpochMs;
    private String lastSourceDate;
    private String lastSourceQuality;
    private String lastFactHash;
    private Long rowVersion;
    private Date gmtCreate;
    private Date gmtModify;
}
