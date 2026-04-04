package com.aimeeting.room.entity;

import lombok.Data;
import java.util.Date;

@Data
public class AiMeetingRoom {
    private Long id;
    private String title;
    private String description;
    private Long creatorId;
    private Integer status;
    private Integer maxRounds;
    private Integer currentRound;
    private String finalConclusion;
    private Date conclusionTime;
    private String summary;
    private Date gmtCreate;
    private Date gmtModify;
}
