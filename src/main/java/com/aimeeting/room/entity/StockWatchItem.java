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
    private String groupKey;
    private String analysisMode;
    private Integer analysisIntervalMinutes;
    private String anomalyTriggerMode;
    private Boolean weeklyReviewEnabled;
    private Boolean monthlyReviewEnabled;
    private Long analysisPolicyVersion;
    private Date analysisPolicyModifiedAt;
    private Date gmtCreate;
    private Date gmtModify;
}
