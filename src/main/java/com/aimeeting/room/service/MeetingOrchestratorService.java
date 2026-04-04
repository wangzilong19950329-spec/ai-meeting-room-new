package com.aimeeting.room.service;

import com.aimeeting.room.dao.*;
import com.aimeeting.room.entity.*;
import com.aimeeting.room.service.ai.AiModelRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI编排服务：负责构建提示词并调用 AiModelRouter
 */
@Slf4j
@Service
public class MeetingOrchestratorService {

    @Autowired
    private AiModelRouter aiModelRouter;

    @Autowired
    private AiMeetingRoomMapper roomMapper;

    @Autowired
    private AiMeetingMemberMapper memberMapper;

    @Autowired
    private AiMeetingOpinionMapper opinionMapper;

    @Autowired
    private AiMeetingVoteMapper voteMapper;

    public String callModel(String modelType, String systemPrompt, String userPrompt) {
        return aiModelRouter.chat(modelType, systemPrompt, userPrompt);
    }

    public String buildDebateContext(Long meetingId, Long memberId, int roundNumber) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        AiMeetingMember member = memberMapper.selectById(memberId);
        List<AiMeetingOpinion> allOpinions = opinionMapper.selectByMeetingId(meetingId);

        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("【会议主题】").append(room.getTitle()).append("\n");
        if (room.getDescription() != null && !room.getDescription().isEmpty()) {
            sb.append("【会议背景】").append(room.getDescription()).append("\n");
        }
        sb.append("【当前轮次】第").append(roundNumber).append("轮\n\n");

        if (!allOpinions.isEmpty()) {
            sb.append("【历史发言】\n");
            for (AiMeetingOpinion op : allOpinions) {
                String name = nameMap.getOrDefault(op.getMemberId(), "未知");
                sb.append(String.format("[第%d轮] %s: %s\n", op.getRoundNumber(), name, op.getContent()));
                if (op.getEvidence() != null && !op.getEvidence().isEmpty()) {
                    sb.append("  (论据: ").append(op.getEvidence()).append(")\n");
                }
            }
            sb.append("\n");
        }

        if (roundNumber == 1) {
            sb.append("请以 ").append(member.getMemberName()).append(" 的身份，对以上议题发表你的看法。");
        } else {
            sb.append("请以 ").append(member.getMemberName())
              .append(" 的身份，基于以上讨论，发表或更新你的看法。可以同意、补充或反驳其他成员观点，但必须基于客观事实或可信逻辑。");
        }
        sb.append("\n\n请按格式回复：\n【观点】核心观点\n【论据】事实依据或逻辑推理\n【结论】对议题的结论性立场");

        return sb.toString();
    }

    public String buildVotePrompt(Long meetingId, Long memberId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("【会议主题】").append(room.getTitle()).append("\n\n【讨论记录】\n");
        for (AiMeetingOpinion op : opinions) {
            sb.append(String.format("[第%d轮] %s: %s\n",
                    op.getRoundNumber(), nameMap.getOrDefault(op.getMemberId(), "未知"), op.getContent()));
        }
        sb.append("\n基于完整讨论，请总结你的最终立场。");
        sb.append("\n\n请严格按JSON格式回复（不要有多余文字）：\n");
        sb.append("{\"position\": \"一句话结论\", \"confidence\": 0.85, \"reasoning\": \"核心理由\"}");

        return sb.toString();
    }

    public String buildClusterPrompt(Long meetingId, int roundNumber) {
        List<AiMeetingVote> votes = voteMapper.selectByMeetingIdAndRound(meetingId, roundNumber);

        StringBuilder sb = new StringBuilder();
        sb.append("以下是多位AI成员对同一议题的投票立场，请对语义相近的立场进行归类聚合。\n\n");
        for (AiMeetingVote v : votes) {
            sb.append(String.format("成员%d: %s (置信度: %s)\n", v.getMemberId(), v.getVotePosition(), v.getConfidence()));
        }
        sb.append("\n请严格按JSON数组格式返回（不要有多余文字）：\n");
        sb.append("[{\"clusterLabel\": \"该类观点的概括（一句话）\", \"memberIds\": [1, 2]}]");

        return sb.toString();
    }

    public String buildSummaryPrompt(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("请为以下AI多成员会议生成结构化总结。\n\n");
        sb.append("【主题】").append(room.getTitle()).append("\n");
        sb.append("【参与成员】").append(members.stream().map(AiMeetingMember::getMemberName).collect(Collectors.joining("、"))).append("\n");
        sb.append("【最终结论】").append(room.getFinalConclusion() != null ? room.getFinalConclusion() : "未达成").append("\n\n");
        sb.append("【完整记录】\n");
        for (AiMeetingOpinion op : opinions) {
            sb.append(String.format("[第%d轮] %s: %s\n", op.getRoundNumber(), nameMap.getOrDefault(op.getMemberId(), "未知"), op.getContent()));
        }
        sb.append("\n请生成包含以下部分的总结：\n1.议题概述  2.各方核心观点  3.主要争议点  4.共识形成过程  5.最终结论及依据  6.遗留问题（如有）");

        return sb.toString();
    }
}
