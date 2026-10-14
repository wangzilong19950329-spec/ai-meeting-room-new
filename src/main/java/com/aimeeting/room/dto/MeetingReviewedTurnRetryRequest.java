package com.aimeeting.room.dto;

import lombok.Data;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class MeetingReviewedTurnRetryRequest {
    @NotNull @Min(1)
    private Long skipId;
    @NotNull @Min(1)
    private Integer expectedRound;
    @NotNull @Min(0)
    private Long expectedEpoch;
}
