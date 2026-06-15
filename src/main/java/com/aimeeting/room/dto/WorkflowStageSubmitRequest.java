package com.aimeeting.room.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class WorkflowStageSubmitRequest {
    @NotBlank(message = "提交内容不能为空")
    private String content;
    private String actorRef;
}
