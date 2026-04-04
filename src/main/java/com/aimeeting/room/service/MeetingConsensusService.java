package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dao.*;
import com.aimeeting.room.dto.ConsensusResult;
import com.aimeeting.room.entity.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MeetingConsensusService {

    @Autowired
    private AiMeetingVoteMapper voteMapper;

    @Autowired
    private AiMeetingMemberMapper memberMapper;

    @Autowired
    private AiMeetingRoomMapper roomMapper;

    @Autowired
    private AiMeetingConclusionClusterMapper clusterMapper;

    @Autowired
    private MeetingOrchestratorService orchestrator;

    @Autowired
    private AiModelProperties aiModelProperties;

    /** 投票：每位成员表明当前立场 */
    public void conductVoting(Long meetingId, int roundNumber) {
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        for (AiMeetingMember member : members) {
            String prompt = orchestrator.buildVotePrompt(meetingId, member.getId());
            String systemPrompt = "你是会议成员「" + member.getMemberName() + "」，请表明你的最终立场。";
            String response = orchestrator.callModel(member.getModelType(), systemPrompt, prompt);
            if (response == null) continue;

            BigDecimal confidence = new BigDecimal("0.50");
            String position = response;
            String reasoning = "";
            try {
                String json = extractJson(response, "{");
                JSONObject obj = JSON.parseObject(json);
                position = obj.getString("position");
                confidence = obj.getBigDecimal("confidence");
                reasoning = obj.getString("reasoning");
            } catch (Exception e) {
                log.warn("Vote parse failed for member={}, using raw response", member.getId());
            }

            AiMeetingVote vote = new AiMeetingVote();
            vote.setMeetingId(meetingId);
            vote.setMemberId(member.getId());
            vote.setRoundNumber(roundNumber);
            vote.setVotePosition(position != null ? position : response);
            vote.setConfidence(confidence);
            vote.setReasoning(reasoning);
            voteMapper.insert(vote);
        }
    }

    /** 对投票结果进行语义聚类 */
    public void clusterVotePositions(Long meetingId, int roundNumber) {
        int totalMembers = memberMapper.countByMeetingId(meetingId);
        AiModelProperties.MeetingConfig cfg = aiModelProperties.getMeeting();
        int required = Math.max(cfg.getMinAgreementCount(),
                (int) Math.ceil(totalMembers * cfg.getConsensusThreshold()));

        String prompt = orchestrator.buildClusterPrompt(meetingId, roundNumber);
        String response = orchestrator.callModel(null,
                "你是文本聚类专家，请对以下投票立场进行语义聚类，相似观点归为同一组。", prompt);
        if (response == null) return;

        try {
            String jsonStr = extractJson(response, "[");
            JSONArray clusters = JSON.parseArray(jsonStr);
            List<AiMeetingConclusionCluster> toInsert = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                JSONObject c = clusters.getJSONObject(i);
                String label = c.getString("clusterLabel");
                JSONArray ids = c.getJSONArray("memberIds");
                List<Long> memberIds = new ArrayList<>();
                for (int j = 0; j < ids.size(); j++) memberIds.add(ids.getLong(j));

                int count = memberIds.size();
                BigDecimal ratio = new BigDecimal(count)
                        .divide(new BigDecimal(totalMembers), 2, RoundingMode.HALF_UP);

                AiMeetingConclusionCluster cluster = new AiMeetingConclusionCluster();
                cluster.setMeetingId(meetingId);
                cluster.setRoundNumber(roundNumber);
                cluster.setClusterLabel(label);
                cluster.setMemberCount(count);
                cluster.setMemberIds(memberIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
                cluster.setAgreementRatio(ratio);
                cluster.setIsConsensus(count >= required ? 1 : 0);
                toInsert.add(cluster);
            }
            if (!toInsert.isEmpty()) clusterMapper.batchInsert(toInsert);
        } catch (Exception e) {
            log.error("Cluster parse failed, meetingId={}, round={}", meetingId, roundNumber, e);
        }
    }

    /** 检查是否达成共识 */
    public ConsensusResult checkConsensus(Long meetingId, int roundNumber) {
        AiMeetingConclusionCluster consensus = clusterMapper.selectConsensusCluster(meetingId, roundNumber);
        if (consensus != null) {
            List<Long> agreeIds = parseIds(consensus.getMemberIds());
            List<Long> allIds = memberMapper.selectByMeetingId(meetingId).stream()
                    .map(AiMeetingMember::getId).collect(Collectors.toList());
            List<Long> dissentIds = allIds.stream().filter(id -> !agreeIds.contains(id)).collect(Collectors.toList());
            return ConsensusResult.reached(consensus.getClusterLabel(), consensus.getAgreementRatio(), agreeIds, dissentIds);
        }
        return ConsensusResult.notReached();
    }

    /** 强制结论：取最大聚类 */
    public ConsensusResult forceConclude(Long meetingId) {
        int lastRound = roomMapper.selectById(meetingId).getCurrentRound();
        List<AiMeetingConclusionCluster> clusters = clusterMapper.selectByMeetingIdAndRound(meetingId, lastRound);
        if (clusters.isEmpty()) return ConsensusResult.notReached();

        AiMeetingConclusionCluster largest = clusters.get(0);
        List<Long> agreeIds = parseIds(largest.getMemberIds());
        List<Long> allIds = memberMapper.selectByMeetingId(meetingId).stream()
                .map(AiMeetingMember::getId).collect(Collectors.toList());
        List<Long> dissentIds = allIds.stream().filter(id -> !agreeIds.contains(id)).collect(Collectors.toList());
        return ConsensusResult.reached(largest.getClusterLabel(), largest.getAgreementRatio(), agreeIds, dissentIds);
    }

    private List<Long> parseIds(String memberIds) {
        return Arrays.stream(memberIds.split(",")).map(Long::parseLong).collect(Collectors.toList());
    }

    private String extractJson(String text, String startChar) {
        int start = text.indexOf(startChar);
        String endChar = startChar.equals("[") ? "]" : "}";
        int end = text.lastIndexOf(endChar);
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }
}
