package com.aimeeting.room.dto;

import lombok.Data;

import java.util.Date;

@Data
public class SkillFileVO {
    private Long id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private Date gmtCreate;
}
