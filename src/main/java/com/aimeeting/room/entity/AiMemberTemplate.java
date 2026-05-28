package com.aimeeting.room.entity;

import lombok.Data;
import java.util.Date;

@Data
public class AiMemberTemplate {
    private Long id;
    /** 模版名称 */
    private String name;
    /** 立场/背景 */
    private String persona;
    /** 专业技能领域 */
    private String skills;
    /** 性格特点 */
    private String personality;
    /** 默认绑定模型 */
    private String modelType;
    /** 标签，逗号分隔 */
    private String tags;
    private Date gmtCreate;
    private Date gmtModify;
}
