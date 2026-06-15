package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class AiWorkflowTemplate {
    private Long id;
    private String templateKey;
    private String name;
    private String description;
    private String layoutConfig;
    private String actorSlotsConfig;
    private Integer builtIn;
    private Integer active;
    private Date gmtCreate;
    private Date gmtModify;
}
