package com.aimeeting.room.entity;

import lombok.Data;
import java.util.Date;

@Data
public class AiMeetingScoreLog {
    private Long id;
    private Long meetingId;
    private Long memberId;
    private Integer scoreDelta;
    private String reason;
    private String contributionDetail;
    private Date gmtCreate;
}
