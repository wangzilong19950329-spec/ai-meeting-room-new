package com.aimeeting.room.dto;

import lombok.Data;

@Data
public class ContributionResult {

    private Long memberId;
    private String memberName;
    private double participationRate;
    private double rebuttalQuality;
    private double earlyAgreement;
    private double evidenceRate;
    private double positionConsistency;
    private double contributionWeight;
    private boolean inConsensusCluster;
}
