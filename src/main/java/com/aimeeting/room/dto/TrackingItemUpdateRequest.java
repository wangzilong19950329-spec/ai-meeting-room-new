package com.aimeeting.room.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class TrackingItemUpdateRequest {
    @NotBlank(message = "状态不能为空")
    private String status;
    private String note;
    private String evidenceRef;
}
