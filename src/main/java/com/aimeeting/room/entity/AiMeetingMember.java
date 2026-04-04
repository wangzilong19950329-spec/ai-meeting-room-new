package com.aimeeting.room.entity;

import lombok.Data;
import java.util.Date;

@Data
public class AiMeetingMember {
    private Long id;
    private Long meetingId;
    private String memberName;
    /** 模型标识，如 gpt-4, claude-3-opus, qwen-max, ernie-bot-4 */
    private String modelType;
    private String persona;
    /** 0-参与者 1-主持人 */
    private Integer role;
    private Integer totalScore;
    private Date gmtCreate;
    private Date gmtModify;
}
