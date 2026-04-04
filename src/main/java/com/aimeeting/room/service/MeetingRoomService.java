package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dto.ConsensusResult;
import com.aimeeting.room.dto.MeetingCreateRequest;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.MemberRoleEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MeetingRoomService {

    @Autowired private AiMeetingRoomMapper roomMapper;
    @Autowired private AiMeetingMemberMapper memberMapper;
    @Autowired private AiMeetingOpinionMapper opinionMapper;
    @Autowired private MeetingDebateService debateService;
    @Autowired private MeetingConsensusService consensusService;
    @Autowired private MeetingScoreService scoreService;
    @Autowired private MeetingOrchestratorService orchestrator;
    @Autowired private AiModelProperties aiModelProperties;

    @Autowired
    @Qualifier("meetingExecutor")
    private ExecutorService meetingExecutor;

    /** 创建会议，返回 meetingId */
    public Long createMeeting(MeetingCreateRequest request) {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle(request.getTitle());
        room.setDescription(request.getDescription());
        room.setCreatorId(request.getCreatorId());
        room.setStatus(MeetingStatusEnum.CREATED.getCode());
        room.setMaxRounds(request.getMaxRounds() != null
                ? request.getMaxRounds()
                : aiModelProperties.getMeeting().getMaxRoundsDefault());
        room.setCurrentRound(0);
        roomMapper.insert(room);

        if (!CollectionUtils.isEmpty(request.getMembers())) {
            List<AiMeetingMember> members = new ArrayList<>();
            for (MeetingCreateRequest.MemberRequest mr : request.getMembers()) {
                AiMeetingMember m = new AiMeetingMember();
                m.setMeetingId(room.getId());
                m.setMemberName(mr.getMemberName());
                m.setModelType(mr.getModelType());
                m.setPersona(mr.getPersona());
                m.setRole(mr.getRole() != null ? mr.getRole() : MemberRoleEnum.PARTICIPANT.getCode());
                m.setTotalScore(0);
                members.add(m);
            }
            memberMapper.batchInsert(members);
        }
        log.info("Meeting created: id={}, title={}", room.getId(), room.getTitle());
        return room.getId();
    }

    /** 异步启动会议 */
    public void startMeeting(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        if (!MeetingStatusEnum.CREATED.getCode().equals(room.getStatus()))
            throw new IllegalStateException("会议状态不允许启动: status=" + room.getStatus());

        roomMapper.updateStatus(meetingId, MeetingStatusEnum.IN_PROGRESS.getCode());
        meetingExecutor.submit(() -> runMeetingFlow(meetingId));
        log.info("Meeting started async: id={}", meetingId);
    }

    public MeetingDetailVO getMeetingDetail(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) return null;
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        return buildVO(room, members, opinions);
    }

    public List<MeetingDetailVO> listByCreator(Long creatorId) {
        return roomMapper.selectByCreatorId(creatorId).stream()
                .map(r -> buildVO(r, memberMapper.selectByMeetingId(r.getId()), null))
                .collect(Collectors.toList());
    }

    public void cancelMeeting(Long meetingId) {
        roomMapper.updateStatus(meetingId, MeetingStatusEnum.CANCELLED.getCode());
    }

    public void verifyConclusion(Long meetingId, boolean isValid) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null || !MeetingStatusEnum.CONCLUDED.getCode().equals(room.getStatus()))
            throw new IllegalStateException("只有已达成结论的会议才能验证: meetingId=" + meetingId);

        if (isValid) {
            roomMapper.updateStatus(meetingId, MeetingStatusEnum.VERIFIED_VALID.getCode());
            scoreService.distributeRewards(meetingId);
        } else {
            roomMapper.updateStatus(meetingId, MeetingStatusEnum.VERIFIED_INVALID.getCode());
            scoreService.distributePenalties(meetingId);
        }
    }

    // ---- 内部流程 ----

    private void runMeetingFlow(Long meetingId) {
        try {
            AiMeetingRoom room = roomMapper.selectById(meetingId);
            int maxRounds = room.getMaxRounds();

            for (int round = 1; round <= maxRounds; round++) {
                log.info("Meeting {} round {} starting", meetingId, round);
                roomMapper.updateCurrentRound(meetingId, round);

                debateService.executeDebateRound(meetingId, round);
                consensusService.conductVoting(meetingId, round);
                consensusService.clusterVotePositions(meetingId, round);

                ConsensusResult result = consensusService.checkConsensus(meetingId, round);
                if (result.isConsensusReached()) {
                    finalizeMeeting(meetingId, result);
                    return;
                }
                log.info("Meeting {} round {} no consensus yet", meetingId, round);
            }

            // 达到最大轮数，强制结论
            log.info("Meeting {} max rounds reached, forcing conclusion", meetingId);
            ConsensusResult forced = consensusService.forceConclude(meetingId);
            finalizeMeeting(meetingId, forced);

        } catch (Exception e) {
            log.error("Meeting flow error, meetingId={}", meetingId, e);
            roomMapper.updateStatus(meetingId, MeetingStatusEnum.CANCELLED.getCode());
        }
    }

    private void finalizeMeeting(Long meetingId, ConsensusResult result) {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(meetingId);
        room.setFinalConclusion(result.getConclusion());
        room.setConclusionTime(new Date());
        room.setStatus(MeetingStatusEnum.CONCLUDED.getCode());
        roomMapper.updateConclusion(room);

        // 生成总结
        String summary = orchestrator.callModel(null,
                "你是专业会议记录员，请生成结构化会议总结。",
                orchestrator.buildSummaryPrompt(meetingId));
        if (summary != null) {
            roomMapper.updateSummary(meetingId, summary);
        }
        log.info("Meeting {} concluded: {}", meetingId, result.getConclusion());
    }

    private MeetingDetailVO buildVO(AiMeetingRoom room, List<AiMeetingMember> members,
                                     List<AiMeetingOpinion> opinions) {
        MeetingDetailVO vo = new MeetingDetailVO();
        vo.setMeetingId(room.getId());
        vo.setTitle(room.getTitle());
        vo.setDescription(room.getDescription());
        vo.setStatus(room.getStatus());
        MeetingStatusEnum statusEnum = MeetingStatusEnum.of(room.getStatus());
        vo.setStatusName(statusEnum != null ? statusEnum.getDesc() : "未知");
        vo.setTotalRounds(room.getCurrentRound());
        vo.setFinalConclusion(room.getFinalConclusion());
        vo.setConclusionTime(room.getConclusionTime());
        vo.setSummary(room.getSummary());
        vo.setMembers(members);

        if (opinions != null) {
            Map<Long, String> nameMap = members.stream()
                    .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));
            List<MeetingDetailVO.OpinionVO> opVOs = opinions.stream().map(op -> {
                MeetingDetailVO.OpinionVO ov = new MeetingDetailVO.OpinionVO();
                ov.setId(op.getId());
                ov.setMemberId(op.getMemberId());
                ov.setMemberName(nameMap.getOrDefault(op.getMemberId(), "未知"));
                ov.setRoundNumber(op.getRoundNumber());
                ov.setParentOpinionId(op.getParentOpinionId());
                ov.setContent(op.getContent());
                ov.setEvidence(op.getEvidence());
                ov.setOpinionType(op.getOpinionType());
                OpinionTypeEnum t = OpinionTypeEnum.of(op.getOpinionType());
                ov.setOpinionTypeName(t != null ? t.getDesc() : "未知");
                ov.setGmtCreate(op.getGmtCreate());
                return ov;
            }).collect(Collectors.toList());
            vo.setOpinions(opVOs);
        }
        return vo;
    }
}
