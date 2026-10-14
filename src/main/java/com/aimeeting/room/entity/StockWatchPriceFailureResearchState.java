package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchPriceFailureResearchState {
    private String ruleVersion;
    private String ruleSpecHash;
    private String ruleSpecJson;
    private Long bootstrapObservationId;
    private Long lastObservationId;
    private Long rowVersion;
    private Date startedAt;
    private Date gmtCreate;
    private Date gmtModify;
}
