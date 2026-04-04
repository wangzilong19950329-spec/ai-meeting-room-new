package com.aimeeting.room.dto;

import lombok.Data;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class MeetingCreateRequest {

    @NotBlank(message = "会议主题不能为空")
    private String title;

    private String description;

    private Long creatorId;

    /** 最大轮数，默认10 */
    @Min(value = 1, message = "最大轮数至少为1")
    @Max(value = 20, message = "最大轮数不超过20")
    private Integer maxRounds;

    @NotEmpty(message = "至少需要一个成员")
    private List<MemberRequest> members;

    @Data
    public static class MemberRequest {
        @NotBlank(message = "成员名称不能为空")
        private String memberName;

        @NotBlank(message = "模型类型不能为空")
        private String modelType;

        @NotBlank(message = "人设不能为空")
        private String persona;

        /** 0-参与者 1-主持人 */
        private Integer role;
    }
}
