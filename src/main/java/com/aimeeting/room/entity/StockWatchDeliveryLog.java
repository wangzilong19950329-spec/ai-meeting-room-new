package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchDeliveryLog {
    private Long id;
    private Long configId;
    private Long analysisId;
    private String symbol;
    private String market;
    private String recipientHash;
    private String recipientMasked;
    private String status;
    private String messageId;
    private String errorMessage;
    private Date sentAt;
    private Date gmtCreate;
    private Date gmtModify;
}
