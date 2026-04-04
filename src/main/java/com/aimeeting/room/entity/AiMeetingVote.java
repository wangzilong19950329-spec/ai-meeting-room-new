package com.aimeeting.room.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class AiMeetingVote {
    private Long id;
    private Long meetingId;
    private Long memberId;
    private Integer roundNumber;
    private String votePosition;
    private BigDecimal confidence;
    private String reasoning;
    private Date gmtCreate;
}
