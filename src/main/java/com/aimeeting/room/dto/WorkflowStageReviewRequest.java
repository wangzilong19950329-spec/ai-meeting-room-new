package com.aimeeting.room.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class WorkflowStageReviewRequest {
    @NotNull(message = "approved 不能为空")
    private Boolean approved;
    private String comment;
    private String actorRef;
}
