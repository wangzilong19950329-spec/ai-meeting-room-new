package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class StockWatchItem {
    private Long id;
    private String symbol;
    private String market;
    private String stockName;
    private String imageUrl;
    private Boolean enabled;
    private String source;
    private Date gmtCreate;
    private Date gmtModify;
}
