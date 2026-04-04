package com.aimeeting.room.entity;

import lombok.Data;
import java.util.Date;

@Data
public class AiMeetingOpinion {
    private Long id;
    private Long meetingId;
    private Long memberId;
    private Integer roundNumber;
    private Long parentOpinionId;
    private String content;
    private String evidence;
    /** 0-陈述 1-反驳 2-让步 */
    private Integer opinionType;
    private Date gmtCreate;
}
