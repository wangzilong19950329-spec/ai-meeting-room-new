package com.aimeeting.room.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ConsensusResult {

    private boolean consensusReached;
    private String conclusion;
    private BigDecimal agreementRatio;
    private List<Long> agreeingMemberIds;
    private List<Long> dissentingMemberIds;

    public static ConsensusResult reached(String conclusion, BigDecimal ratio,
                                          List<Long> agreeingIds, List<Long> dissentingIds) {
        ConsensusResult r = new ConsensusResult();
        r.consensusReached = true;
        r.conclusion = conclusion;
        r.agreementRatio = ratio;
        r.agreeingMemberIds = agreeingIds;
        r.dissentingMemberIds = dissentingIds;
        return r;
    }

    public static ConsensusResult notReached() {
        ConsensusResult r = new ConsensusResult();
        r.consensusReached = false;
        return r;
    }
}
