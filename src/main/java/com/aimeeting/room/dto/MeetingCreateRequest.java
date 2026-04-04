package com.aimeeting.room.dto;

import lombok.Data;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import java.util.List;

@Data
public class MeetingCreateRequest {

    @NotBlank(message = "会议主题不能为空")
    private String title;

    private String description;
    private String projectKey;
    private String projectContext;
    private String workflowTemplateKey;

    private Long creatorId;

    /** 最大轮数，默认10 */
    @Min(value = 1, message = "最大轮数至少为1")
    @Max(value = 20, message = "最大轮数不超过20")
    private Integer maxRounds;

    private List<MemberRequest> members;

    @Data
    public static class MemberRequest {
        @NotBlank(message = "成员名称不能为空")
        private String memberName;

        @NotBlank(message = "模型类型不能为空")
        private String modelType;

        @NotBlank(message = "人设不能为空")
        private String persona;

        private String actorSlotKey;
        private String actorSlotName;

        /** 0-参与者 1-主持人 */
        private Integer role;
    }
}
