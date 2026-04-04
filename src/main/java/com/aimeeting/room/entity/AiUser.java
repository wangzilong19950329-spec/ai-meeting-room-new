package com.aimeeting.room.entity;

import lombok.Data;
import java.util.Date;

@Data
public class AiUser {
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private Date gmtCreate;
    private Date gmtModify;
}
