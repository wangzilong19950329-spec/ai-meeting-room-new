package com.aimeeting.room.entity;

import lombok.Data;

import java.util.Date;

@Data
public class AiSkillFile {
    private Long id;
    private String fileName;
    private String storedName;
    private String contentType;
    private Long fileSize;
    private String storagePath;
    private Date gmtCreate;
    private Date gmtModify;
}
