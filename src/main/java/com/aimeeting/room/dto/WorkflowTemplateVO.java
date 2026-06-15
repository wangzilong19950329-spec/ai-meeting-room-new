package com.aimeeting.room.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class WorkflowTemplateVO {
    private Long id;
    private String templateKey;
    private String name;
    private String description;
    private String layoutConfig;
    private List<ActorSlotVO> actorSlots = new ArrayList<>();
    private Integer builtIn;
    private Integer active;
    private Date gmtCreate;
    private Date gmtModify;
    private List<StageVO> stages = new ArrayList<>();

    @Data
    public static class ActorSlotVO {
        private String slotKey;
        private String slotName;
        private String description;
        private String defaultModelType;
        private String defaultPersona;
        private Integer required;
        private Integer allowMultiple;
        private List<DefaultMemberVO> defaultMembers = new ArrayList<>();
    }

    @Data
    public static class DefaultMemberVO {
        private Long memberTemplateId;
        private String memberName;
        private String modelType;
        private String persona;
        /** 引用的议员模版是否仍存在；false 表示模版已删除，使用存档快照 */
        private Boolean templateExists;
    }

    @Data
    public static class StageVO {
        private Long id;
        private String stageKey;
        private String stageName;
        private Integer stageOrder;
        private String stageType;
        private String actorType;
        private String actorBindingMode;
        private String actorSlotKey;
        private String completionRule;
        private String skillKey;
        private String skillPrompt;
        private List<Long> skillFileIds = new ArrayList<>();
        private List<SkillFileVO> skillFiles = new ArrayList<>();
        private String searchStrategy;
        private String searchStrategyLabel;
        private List<String> inputStageKeys = new ArrayList<>();
        private String passTarget;
        private String rejectTarget;
        private String reopenTarget;
        private Integer autoStart;
        private Integer reviewRequired;
        private Integer maxReviewAttempts;
    }
}
