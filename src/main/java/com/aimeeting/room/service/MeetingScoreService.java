package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dao.*;
import com.aimeeting.room.dto.ConsensusResult;
import com.aimeeting.room.dto.ContributionResult;
import com.aimeeting.room.entity.*;
import com.aimeeting.room.enums.OpinionTypeEnum;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class MeetingScoreService {

    private static final double W_PARTICIPATION  = 0.20;
    private static final double W_REBUTTAL       = 0.25;
    private static final double W_EARLY_AGREE    = 0.20;
    private static final double W_EVIDENCE       = 0.20;
    private static final double W_CONSISTENCY    = 0.15;

    @Autowired private AiMeetingRoomMapper roomMapper;
    @Autowired private AiMeetingMemberMapper memberMapper;
    @Autowired private AiMeetingOpinionMapper opinionMapper;
    @Autowired private AiMeetingVoteMapper voteMapper;
    @Autowired private AiMeetingScoreLogMapper scoreLogMapper;
    @Autowired private MeetingConsensusService consensusService;
    @Autowired private AiModelProperties aiModelProperties;

    public List<ContributionResult> calculateContributions(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        List<AiMeetingOpinion> allOpinions = opinionMapper.selectByMeetingId(meetingId);
        int totalRounds = room.getCurrentRound();
        ConsensusResult consensus = consensusService.checkConsensus(meetingId, totalRounds);

        List<ContributionResult> results = new ArrayList<>();
        double totalWeight = 0;

        for (AiMeetingMember member : members) {
            ContributionResult c = new ContributionResult();
            c.setMemberId(member.getId());
            c.setMemberName(member.getMemberName());

            int opCount = opinionMapper.countByMeetingIdAndMemberId(meetingId, member.getId());
            c.setParticipationRate(totalRounds > 0 ? Math.min(1.0, (double) opCount / totalRounds) : 0);

            long rebuttalCount = allOpinions.stream()
                    .filter(o -> o.getMemberId().equals(member.getId())
                            && OpinionTypeEnum.REBUTTAL.getCode() == o.getOpinionType())
                    .count();
            c.setRebuttalQuality(opCount > 0 ? Math.min(1.0, (double) rebuttalCount / opCount) : 0);

            c.setEarlyAgreement(calcEarlyAgreement(meetingId, member.getId(), totalRounds, consensus));

            long evidenceCount = allOpinions.stream()
                    .filter(o -> o.getMemberId().equals(member.getId())
                            && o.getEvidence() != null && !o.getEvidence().isEmpty())
                    .count();
            c.setEvidenceRate(opCount > 0 ? (double) evidenceCount / opCount : 0);

            AiMeetingVote lastVote = voteMapper.selectByMeetingMemberRound(meetingId, member.getId(), totalRounds);
            c.setPositionConsistency(lastVote != null ? lastVote.getConfidence().doubleValue() : 0.5);

            boolean inConsensus = consensus.isConsensusReached()
                    && consensus.getAgreeingMemberIds() != null
                    && consensus.getAgreeingMemberIds().contains(member.getId());
            c.setInConsensusCluster(inConsensus);

            double weight = c.getParticipationRate() * W_PARTICIPATION
                    + c.getRebuttalQuality() * W_REBUTTAL
                    + c.getEarlyAgreement() * W_EARLY_AGREE
                    + c.getEvidenceRate() * W_EVIDENCE
                    + c.getPositionConsistency() * W_CONSISTENCY;
            c.setContributionWeight(weight);
            totalWeight += weight;
            results.add(c);
        }

        // 归一化
        if (totalWeight > 0) {
            for (ContributionResult c : results) {
                c.setContributionWeight(c.getContributionWeight() / totalWeight * members.size());
            }
        }
        return results;
    }

    public void distributeRewards(Long meetingId) {
        AiModelProperties.MeetingConfig cfg = aiModelProperties.getMeeting();
        List<ContributionResult> contributions = calculateContributions(meetingId);
        for (ContributionResult c : contributions) {
            int reward = (int) Math.round(cfg.getBaseReward() * c.getContributionWeight());
            recordScore(meetingId, c.getMemberId(), reward, "贡献奖励", c);
        }
        log.info("奖励已发放, meetingId={}", meetingId);
    }

    public void distributePenalties(Long meetingId) {
        AiModelProperties.MeetingConfig cfg = aiModelProperties.getMeeting();
        List<ContributionResult> contributions = calculateContributions(meetingId);
        for (ContributionResult c : contributions) {
            int penalty;
            String reason;
            if (c.isInConsensusCluster()) {
                penalty = -(int) Math.round(cfg.getBasePenalty() * c.getContributionWeight());
                reason = "无效结论扣分";
            } else {
                penalty = -(int) Math.round(cfg.getBasePenalty() * cfg.getDissentPenaltyFactor());
                reason = "持异议减轻扣分";
            }
            recordScore(meetingId, c.getMemberId(), penalty, reason, c);
        }
        log.info("扣分已执行, meetingId={}", meetingId);
    }

    public List<AiMeetingScoreLog> getMemberScoreHistory(Long memberId) {
        return scoreLogMapper.selectByMemberId(memberId);
    }

    private void recordScore(Long meetingId, Long memberId, int delta, String reason, ContributionResult c) {
        AiMeetingScoreLog log = new AiMeetingScoreLog();
        log.setMeetingId(meetingId);
        log.setMemberId(memberId);
        log.setScoreDelta(delta);
        log.setReason(reason);
        log.setContributionDetail(JSON.toJSONString(c));
        scoreLogMapper.insert(log);

        AiMeetingMember member = memberMapper.selectById(memberId);
        memberMapper.updateTotalScore(memberId, member.getTotalScore() + delta);
    }

    private double calcEarlyAgreement(Long meetingId, Long memberId, int totalRounds, ConsensusResult consensus) {
        if (!consensus.isConsensusReached() || totalRounds <= 1) return 0.5;
        for (int r = 1; r <= totalRounds; r++) {
            AiMeetingVote v = voteMapper.selectByMeetingMemberRound(meetingId, memberId, r);
            if (v != null && consensus.getAgreeingMemberIds() != null
                    && consensus.getAgreeingMemberIds().contains(memberId)) {
                return (double) (totalRounds - r + 1) / totalRounds;
            }
        }
        return 0.0;
    }
}
