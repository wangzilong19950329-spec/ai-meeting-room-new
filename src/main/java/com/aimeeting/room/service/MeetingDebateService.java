package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.enums.OpinionTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class MeetingDebateService {

    @Autowired
    private AiMeetingMemberMapper memberMapper;

    @Autowired
    private AiMeetingOpinionMapper opinionMapper;

    @Autowired
    private MeetingOrchestratorService orchestrator;

    /**
     * 执行一轮辩论：先各自陈述，再互相反驳
     */
    public List<AiMeetingOpinion> executeDebateRound(Long meetingId, int roundNumber) {
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        List<AiMeetingOpinion> roundOpinions = new ArrayList<>();

        // 第一阶段：每人发表意见
        for (AiMeetingMember member : members) {
            AiMeetingOpinion opinion = generateOpinion(meetingId, member, roundNumber);
            if (opinion != null) roundOpinions.add(opinion);
        }

        // 第二阶段：每人对其他人的意见发表反驳
        for (AiMeetingMember member : members) {
            for (AiMeetingOpinion target : roundOpinions) {
                if (!target.getMemberId().equals(member.getId())) {
                    AiMeetingOpinion rebuttal = generateRebuttal(meetingId, member, target, roundNumber);
                    if (rebuttal != null) roundOpinions.add(rebuttal);
                    break; // 每人每轮只反驳一个
                }
            }
        }

        return roundOpinions;
    }

    private AiMeetingOpinion generateOpinion(Long meetingId, AiMeetingMember member, int roundNumber) {
        String context = orchestrator.buildDebateContext(meetingId, member.getId(), roundNumber);
        String systemPrompt = buildSystemPrompt(member);
        String response = orchestrator.callModel(member.getModelType(), systemPrompt, context);
        if (response == null) return null;

        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(meetingId);
        opinion.setMemberId(member.getId());
        opinion.setRoundNumber(roundNumber);
        opinion.setContent(response);
        opinion.setEvidence(extractSection(response, "【论据】"));
        opinion.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
        opinionMapper.insert(opinion);
        return opinion;
    }

    private AiMeetingOpinion generateRebuttal(Long meetingId, AiMeetingMember member,
                                               AiMeetingOpinion target, int roundNumber) {
        List<AiMeetingOpinion> currentRound = opinionMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        StringBuilder prompt = new StringBuilder();
        prompt.append("本轮其他成员的发言：\n\n");
        for (AiMeetingOpinion op : currentRound) {
            if (!op.getMemberId().equals(member.getId())) {
                prompt.append("发言: ").append(op.getContent()).append("\n\n");
            }
        }
        prompt.append("请基于客观事实或严密逻辑，对你不认同的观点进行反驳。若你同意所有观点，请说明理由。");
        prompt.append("\n\n格式：\n【反驳/认同对象】指出具体观点\n【理由】基于事实或逻辑\n【论据】支撑证据");

        String response = orchestrator.callModel(member.getModelType(), buildSystemPrompt(member), prompt.toString());
        if (response == null) return null;

        int opType = response.contains("我同意") || response.contains("赞同")
                ? OpinionTypeEnum.CONCESSION.getCode()
                : OpinionTypeEnum.REBUTTAL.getCode();

        AiMeetingOpinion rebuttal = new AiMeetingOpinion();
        rebuttal.setMeetingId(meetingId);
        rebuttal.setMemberId(member.getId());
        rebuttal.setRoundNumber(roundNumber);
        rebuttal.setParentOpinionId(target.getId());
        rebuttal.setContent(response);
        rebuttal.setEvidence(extractSection(response, "【论据】"));
        rebuttal.setOpinionType(opType);
        opinionMapper.insert(rebuttal);
        return rebuttal;
    }

    private String buildSystemPrompt(AiMeetingMember member) {
        return String.format(
                "你是AI会议成员「%s」。人设：%s\n\n规则：\n" +
                "1. 所有观点必须基于客观事实或可信逻辑推理\n" +
                "2. 反驳必须有具体的事实依据或严密逻辑链\n" +
                "3. 不得使用人身攻击、情感诉求或无根据断言\n" +
                "4. 若你的观点被有效反驳，应坦然调整立场\n" +
                "5. 保持独立思考，不盲从多数",
                member.getMemberName(), member.getPersona());
    }

    private String extractSection(String text, String section) {
        if (text == null || section == null) return null;
        int start = text.indexOf(section);
        if (start < 0) return null;
        start += section.length();
        int end = text.indexOf("【", start);
        return end >= 0 ? text.substring(start, end).trim() : text.substring(start).trim();
    }
}
