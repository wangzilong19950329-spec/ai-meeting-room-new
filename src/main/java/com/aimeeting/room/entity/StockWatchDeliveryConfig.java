package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchDeliveryConfig {
    private Long id;
    private String symbol;
    private String market;
    private String stockName;
    private Boolean enabled;
    private String recipientsJson;
    private String senderMode;
    private String frequencyType;
    private Integer intervalMinutes;
    private String triggerPolicyJson;
    private Date activeFrom;
    private String lastDispatchStatus;
    private String lastDispatchMessage;
    private Date lastDispatchAt;
    private Date gmtCreate;
    private Date gmtModify;
}
