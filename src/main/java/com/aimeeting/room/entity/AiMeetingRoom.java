package com.aimeeting.room.entity;

import lombok.Data;
import java.util.Date;

@Data
public class AiMeetingRoom {
    private Long id;
    private String title;
    private String description;
    private String projectKey;
    private String projectContext;
    private String inheritedContext;
    private Long creatorId;
    private Integer status;
    /** Monotonic generation used to fence asynchronous reviewed meeting workers. */
    private Long runEpoch;
    private Integer maxRounds;
    private Integer currentRound;
    private String finalConclusion;
    private Date conclusionTime;
    private String summary;
    private String decisionArtifact;
    private String roundSummaries;  // JSON array: [{"round":1,"summary":"..."}]
    private String conclusionHistory;
    private Date gmtCreate;
    private Date gmtModify;
}
