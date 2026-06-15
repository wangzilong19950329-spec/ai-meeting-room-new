package com.aimeeting.room.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class WorkflowTemplateRequest {
    @NotBlank(message = "模板名称不能为空")
    private String name;

    @NotBlank(message = "模板标识不能为空")
    private String templateKey;

    private String description;
    private String layoutConfig;
    @Valid
    private List<ActorSlotRequest> actorSlots;

    @NotEmpty(message = "至少需要一个阶段")
    @Valid
    private List<StageRequest> stages;

    @Data
    public static class ActorSlotRequest {
        @NotBlank(message = "槽位标识不能为空")
        private String slotKey;
        @NotBlank(message = "槽位名称不能为空")
        private String slotName;
        private String description;
        private String defaultModelType;
        private String defaultPersona;
        private Integer required;
        private Integer allowMultiple;
        /** 槽位默认阵容：引用议员模版库（软引用 + 快照），建会议时预填 */
        private List<DefaultMemberRequest> defaultMembers;
    }

    @Data
    public static class DefaultMemberRequest {
        private Long memberTemplateId;
        private String memberName;
        private String modelType;
        private String persona;
    }

    @Data
    public static class StageRequest {
        @NotBlank(message = "阶段标识不能为空")
        private String stageKey;
        @NotBlank(message = "阶段名称不能为空")
        private String stageName;
        @NotBlank(message = "阶段类型不能为空")
        private String stageType;
        @NotBlank(message = "执行者类型不能为空")
        private String actorType;
        private String actorBindingMode;
        private String actorSlotKey;
        private String completionRule;
        private String skillKey;
        private String skillPrompt;
        private List<Long> skillFileIds;
        private String searchStrategy;
        private List<String> inputStageKeys;
        private String passTarget;
        private String rejectTarget;
        private String reopenTarget;
        private Integer autoStart;
        private Integer reviewRequired;
        private Integer maxReviewAttempts;
    }
}
