package com.aimeeting.room.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class AiMeetingConclusionCluster {
    private Long id;
    private Long meetingId;
    private Integer roundNumber;
    private String clusterLabel;
    private Integer memberCount;
    private String memberIds;
    private BigDecimal agreementRatio;
    private Integer isConsensus;
    private Date gmtCreate;
}
