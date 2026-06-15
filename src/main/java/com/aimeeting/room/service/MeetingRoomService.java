package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingConclusionClusterMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingScoreLogMapper;
import com.aimeeting.room.dao.AiMeetingVoteMapper;
import com.aimeeting.room.dto.ConsensusResult;
import com.aimeeting.room.dto.MeetingCreateRequest;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.dto.TrackingItemUpdateRequest;
import com.aimeeting.room.dto.WorkflowStageReviewRequest;
import com.aimeeting.room.dto.WorkflowStageSubmitRequest;
import com.aimeeting.room.dto.WorkflowTemplateRequest;
import com.aimeeting.room.dto.WorkflowTemplateVO;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiMeetingVote;
import com.aimeeting.room.entity.AiWorkflowTemplate;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.MemberRoleEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MeetingRoomService {

    @Autowired private AiMeetingRoomMapper roomMapper;
    @Autowired private AiMeetingMemberMapper memberMapper;
    @Autowired private AiMeetingConclusionClusterMapper clusterMapper;
    @Autowired private AiMeetingOpinionMapper opinionMapper;
    @Autowired private AiMeetingVoteMapper voteMapper;
    @Autowired private AiMeetingScoreLogMapper scoreLogMapper;
    @Autowired private MeetingDebateService debateService;
    @Autowired private MeetingConsensusService consensusService;
    @Autowired private MeetingScoreService scoreService;
    @Autowired private MeetingOrchestratorService orchestrator;
    @Autowired private MeetingWorkflowService workflowService;
    @Autowired private NotificationService notificationService;
    @Autowired private AiModelProperties aiModelProperties;

    @Autowired
    @Qualifier("meetingExecutor")
    private ExecutorService meetingExecutor;

    /** 创建会议，返回 meetingId */
    public Long createMeeting(MeetingCreateRequest request) {
        workflowService.validateMeetingMembers(request.getWorkflowTemplateKey(), request.getMembers());
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle(request.getTitle());
        room.setDescription(request.getDescription());
        room.setProjectKey(normalizeProjectKey(request.getProjectKey()));
        room.setProjectContext(normalizeText(request.getProjectContext()));
        room.setInheritedContext(buildInheritedContext(request.getCreatorId(), room.getProjectKey()));
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
                m.setActorSlotKey(normalizeText(mr.getActorSlotKey()));
                m.setActorSlotName(normalizeText(mr.getActorSlotName()));
                m.setRole(mr.getRole() != null ? mr.getRole() : MemberRoleEnum.PARTICIPANT.getCode());
                m.setTotalScore(0);
                members.add(m);
            }
            memberMapper.batchInsert(members);
        }
        workflowService.ensureWorkflowForMeeting(room, request.getWorkflowTemplateKey());
        log.info("Meeting created: id={}, title={}", room.getId(), room.getTitle());
        return room.getId();
    }

    /** 复制会议：复用成员配置，清空讨论，可修改主题 */
    public Long cloneMeeting(Long sourceMeetingId, String newTitle, Long creatorId) {
        AiMeetingRoom source = roomMapper.selectById(sourceMeetingId);
        if (source == null) throw new IllegalArgumentException("源会议不存在");
        List<AiMeetingMember> sourceMembers = memberMapper.selectByMeetingId(sourceMeetingId);
        String sourceWorkflowTemplateKey = workflowService.resolveWorkflowTemplateKey(sourceMeetingId);

        AiMeetingRoom newRoom = new AiMeetingRoom();
        newRoom.setTitle(newTitle != null && !newTitle.trim().isEmpty() ? newTitle.trim()
                : source.getTitle() + "（副本）");
        newRoom.setDescription(source.getDescription());
        newRoom.setProjectKey(source.getProjectKey());
        newRoom.setProjectContext(source.getProjectContext());
        newRoom.setInheritedContext(source.getInheritedContext());
        newRoom.setCreatorId(creatorId != null ? creatorId : source.getCreatorId());
        newRoom.setStatus(MeetingStatusEnum.CREATED.getCode());
        newRoom.setMaxRounds(source.getMaxRounds());
        newRoom.setCurrentRound(0);
        roomMapper.insert(newRoom);

        if (!sourceMembers.isEmpty()) {
            List<AiMeetingMember> newMembers = new ArrayList<>();
            for (AiMeetingMember sm : sourceMembers) {
                AiMeetingMember m = new AiMeetingMember();
                m.setMeetingId(newRoom.getId());
                m.setMemberName(sm.getMemberName());
                m.setModelType(sm.getModelType());
                m.setPersona(sm.getPersona());
                m.setActorSlotKey(sm.getActorSlotKey());
                m.setActorSlotName(sm.getActorSlotName());
                m.setRole(sm.getRole());
                m.setTotalScore(0);
                newMembers.add(m);
            }
            memberMapper.batchInsert(newMembers);
        }
        workflowService.ensureWorkflowForMeeting(newRoom, sourceWorkflowTemplateKey);
        log.info("Meeting cloned: source={}, new={}", sourceMeetingId, newRoom.getId());
        return newRoom.getId();
    }

    /** 异步启动会议 */
    public void startMeeting(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        if (!MeetingStatusEnum.CREATED.getCode().equals(room.getStatus())
                && !MeetingStatusEnum.PAUSED.getCode().equals(room.getStatus()))
            throw new IllegalStateException("会议状态不允许启动: status=" + room.getStatus());

        workflowService.ensureWorkflowForMeeting(room, null);
        roomMapper.updateStatus(meetingId, MeetingStatusEnum.IN_PROGRESS.getCode());
        if (workflowService.isDefaultRoundtableWorkflow(meetingId)) {
            workflowService.activateMeetingWorkflow(meetingId);
            meetingExecutor.submit(() -> runMeetingFlow(meetingId));
        } else {
            meetingExecutor.submit(() -> {
                try {
                    workflowService.activateMeetingWorkflow(meetingId);
                } catch (Exception e) {
                    log.error("Workflow activation error, meetingId={}", meetingId, e);
                    AiMeetingRoom current = roomMapper.selectById(meetingId);
                    if (current != null && isActiveStatus(current.getStatus())) {
                        roomMapper.updateStatus(meetingId, MeetingStatusEnum.PAUSED.getCode());
                    }
                }
            });
        }
        log.info("Meeting started async: id={}", meetingId);
    }

    public MeetingDetailVO getMeetingDetail(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) return null;
        workflowService.ensureWorkflowForMeeting(room, null);
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        List<AiMeetingVote> votes = voteMapper.selectByMeetingId(meetingId);
        return buildVO(room, members, opinions, votes);
    }

    public List<MeetingDetailVO> listByCreator(Long creatorId) {
        return roomMapper.selectByCreatorId(creatorId).stream()
                .map(r -> buildVO(r, memberMapper.selectByMeetingId(r.getId()), null, null))
                .collect(Collectors.toList());
    }

    public List<AiWorkflowTemplate> listWorkflowTemplates() {
        return workflowService.listActiveTemplates();
    }

    public List<WorkflowTemplateVO> listWorkflowTemplateDetails() {
        return workflowService.listTemplateDetails();
    }

    public WorkflowTemplateVO createWorkflowTemplate(WorkflowTemplateRequest request) {
        return workflowService.createTemplate(request);
    }

    public WorkflowTemplateVO updateWorkflowTemplate(Long templateId, WorkflowTemplateRequest request) {
        return workflowService.updateTemplate(templateId, request);
    }

    public void deleteWorkflowTemplate(Long templateId) {
        workflowService.deleteTemplate(templateId);
    }

    public void cancelMeeting(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        if (!isActiveStatus(room.getStatus())) {
            throw new IllegalStateException("只有进行中的会议才能暂停: status=" + room.getStatus());
        }
        roomMapper.updateStatus(meetingId, MeetingStatusEnum.PAUSED.getCode());
        log.info("Meeting {} paused", meetingId);
    }

    /** 强制终止并生成结论（用于用户主动终止进行中的会议） */
    public void terminateMeeting(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        if (!isActiveStatus(room.getStatus()))
            throw new IllegalStateException("只有进行中的或重开中的会议才能终止: status=" + room.getStatus());

        ConsensusResult forced = consensusService.forceConclude(meetingId);
        if (forced.getConclusion() == null || forced.getConclusion().isBlank()) {
            forced = buildTerminationFallbackResult(meetingId);
        }
        finalizeMeeting(meetingId, forced);
    }

    /** 重启会议：清空讨论记录，重置为待启动状态 */
    public void restartMeeting(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        Integer status = room.getStatus();
        // 允许从已结论/已验证/已取消状态重启
        if (MeetingStatusEnum.IN_PROGRESS.getCode().equals(status))
            throw new IllegalStateException("请先终止进行中的会议再重启");

        clusterMapper.deleteByMeetingId(meetingId);
        opinionMapper.deleteByMeetingId(meetingId);
        voteMapper.deleteByMeetingId(meetingId);
        roomMapper.resetForRestart(meetingId);
        log.info("Meeting {} reset for restart", meetingId);
    }

    /**
     * 删除会议及其全部关联数据（级联清理）。
     * 进行中/修订中的会议会先切到暂停态，让异步流程在下一次状态检查时停止，再执行级联清理。
     */
    @Transactional
    public void deleteMeeting(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        if (isActiveStatus(room.getStatus())) {
            roomMapper.updateStatus(meetingId, MeetingStatusEnum.PAUSED.getCode());
            log.info("Meeting {} paused before deletion", meetingId);
        }

        // 工作流运行 + 阶段记录 + 追踪项/追踪更新
        workflowService.purgeWorkflowAndTrackingData(meetingId);
        // 会议自身的子数据
        scoreLogMapper.deleteByMeetingId(meetingId);
        clusterMapper.deleteByMeetingId(meetingId);
        voteMapper.deleteByMeetingId(meetingId);
        opinionMapper.deleteByMeetingId(meetingId);
        memberMapper.deleteByMeetingId(meetingId);
        roomMapper.deleteById(meetingId);
        log.info("Meeting {} and all related data deleted", meetingId);
    }

    /** 批量删除会议，复用单会议级联清理与状态保护逻辑。 */
    @Transactional
    public int deleteMeetings(List<Long> meetingIds) {
        if (CollectionUtils.isEmpty(meetingIds)) {
            throw new IllegalArgumentException("请选择要删除的会议");
        }
        List<Long> distinctIds = meetingIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的会议");
        }
        for (Long meetingId : distinctIds) {
            deleteMeeting(meetingId);
        }
        log.info("{} meetings deleted in batch: {}", distinctIds.size(), distinctIds);
        return distinctIds.size();
    }

    /** 人类用户发送消息，插入当前轮次 */
    public void addHumanMessage(Long meetingId, String content, boolean asDirective, Long targetMemberId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) {
            throw new IllegalArgumentException("会议不存在: " + meetingId);
        }
        Integer status = room.getStatus();
        if (MeetingStatusEnum.PAUSED.getCode().equals(status)) {
            throw new IllegalStateException("会议已暂停，请先继续讨论再发送追问");
        }
        if (MeetingStatusEnum.CANCELLED.getCode().equals(status)) {
            throw new IllegalStateException("已取消的会议不能继续追问");
        }
        if (MeetingStatusEnum.CREATED.getCode().equals(status)) {
            throw new IllegalStateException("请先启动会议再发送消息");
        }
        boolean needsReopen = isEndedStatus(status);
        if (needsReopen) {
            reopenMeetingForFollowUp(room, content, asDirective, targetMemberId);
            return;
        }
        if (!isActiveStatus(status)) {
            throw new IllegalStateException("当前会议状态不支持发送消息: status=" + status);
        }
        int round = room.getCurrentRound() > 0 ? room.getCurrentRound() : 1;
        AiMeetingMember targetMember = resolveTargetMember(meetingId, targetMemberId);

        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(meetingId);
        opinion.setMemberId(0L);               // 0 = 人类用户/主持人
        opinion.setRoundNumber(round);
        opinion.setContent(content);
        opinion.setEvidence(buildHumanOpinionMeta(targetMember));
        opinion.setOpinionType(resolveHumanOpinionType(asDirective, targetMember));
        opinionMapper.insert(opinion);
        log.info("Human intervention added to meeting {} directive={} targetMemberId={} content={}",
                meetingId, asDirective, targetMember != null ? targetMember.getId() : null, content);
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

    public void updateTrackingItem(Long trackingItemId, TrackingItemUpdateRequest request) {
        MeetingWorkflowService.TrackingProgressResult result = workflowService.updateTrackingItem(
                trackingItemId,
                request.getStatus(),
                request.getNote(),
                request.getEvidenceRef()
        );
        if (result.getMeetingId() == null || !result.isShouldResumeMeeting()) {
            return;
        }
        resumeMeetingFromTracking(
                result.getMeetingId(),
                result.getTrackingItemId(),
                result.getTrackingItemTitle(),
                result.getResumeReason(),
                result.getNote(),
                result.getEvidenceRef()
        );
    }

    public void reopenMeetingFromTrackingSignal(Long meetingId,
                                                Long trackingItemId,
                                                String trackingItemTitle,
                                                String resumeReason,
                                                String note,
                                                String evidenceRef) {
        resumeMeetingFromTracking(meetingId, trackingItemId, trackingItemTitle, resumeReason, note, evidenceRef);
    }

    public void submitWorkflowStage(Long meetingId, WorkflowStageSubmitRequest request) {
        workflowService.submitCurrentStage(meetingId, request);
    }

    public void reviewWorkflowStage(Long meetingId, WorkflowStageReviewRequest request) {
        workflowService.reviewCurrentStage(meetingId, request);
    }

    public void retryWorkflowAutoStage(Long meetingId) {
        workflowService.retryCurrentAutoStage(meetingId);
    }

    // ---- 内部流程 ----

    private void runMeetingFlow(Long meetingId) {
        try {
            AiMeetingRoom room = roomMapper.selectById(meetingId);
            if (room == null) {
                log.warn("Meeting {} disappeared before async flow started", meetingId);
                return;
            }
            int startRound = room.getCurrentRound() > 0 ? room.getCurrentRound() + 1 : 1;
            int endRound = startRound + room.getMaxRounds() - 1;

            for (int round = startRound; round <= endRound; round++) {
                if (!isMeetingInProgress(meetingId)) return;

                log.info("Meeting {} round {} starting", meetingId, round);
                roomMapper.updateCurrentRound(meetingId, round);

                debateService.executeDebateRound(meetingId, round);
                if (!isMeetingInProgress(meetingId)) return;

                consensusService.conductVoting(meetingId, round);
                if (!isMeetingInProgress(meetingId)) return;

                consensusService.clusterVotePositions(meetingId, round);
                if (!isMeetingInProgress(meetingId)) return;

                // 生成本轮摘要（用 deepseek-chat 快速调用，节约成本）
                generateAndSaveRoundSummary(meetingId, round);
                if (!isMeetingInProgress(meetingId)) return;

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
            if (isMeetingInProgress(meetingId)) {
                roomMapper.updateStatus(meetingId, MeetingStatusEnum.CANCELLED.getCode());
            }
        }
    }

    private void generateAndSaveRoundSummary(Long meetingId, int round) {
        try {
            String meetingModelType = orchestrator.resolveOperationalMeetingModelType(meetingId);
            String summaryText = orchestrator.callModel(meetingModelType,
                    "你是会议记录员，请用一到两句话（100字以内）概括本轮讨论的核心分歧和主要共识，输出纯文本。",
                    orchestrator.buildRoundSummaryPrompt(meetingId, round),
                    220);
            if (summaryText == null || summaryText.isBlank()) return;

            // 读取现有 roundSummaries JSON，追加本轮摘要
            AiMeetingRoom room = roomMapper.selectById(meetingId);
            com.alibaba.fastjson.JSONArray arr;
            try {
                arr = room.getRoundSummaries() != null
                        ? com.alibaba.fastjson.JSON.parseArray(room.getRoundSummaries())
                        : new com.alibaba.fastjson.JSONArray();
            } catch (Exception e) {
                arr = new com.alibaba.fastjson.JSONArray();
            }
            com.alibaba.fastjson.JSONObject entry = new com.alibaba.fastjson.JSONObject();
            entry.put("round", round);
            entry.put("summary", summaryText.trim());
            arr.add(entry);
            roomMapper.updateRoundSummaries(meetingId, arr.toJSONString());
            log.info("Round {} summary saved for meeting {}", round, meetingId);
        } catch (Exception e) {
            log.warn("Failed to generate round summary for meeting {} round {}: {}", meetingId, round, e.getMessage());
        }
    }

    private void finalizeMeeting(Long meetingId, ConsensusResult result) {
        AiMeetingRoom existing = roomMapper.selectById(meetingId);
        if (existing == null) {
            log.warn("Skip finalizing missing meeting {}", meetingId);
            return;
        }
        if (!isActiveStatus(existing.getStatus())) {
            log.info("Skip duplicate finalize for meeting {}, current status={}", meetingId, existing.getStatus());
            return;
        }
        if (result == null) {
            result = new ConsensusResult();
        }
        if (result.getConclusion() == null || result.getConclusion().isBlank()) {
            result.setConclusion(buildFallbackConclusion(existing));
        }

        Date concludedAt = new Date();
        AiMeetingRoom concludedRoom = new AiMeetingRoom();
        concludedRoom.setId(meetingId);
        concludedRoom.setFinalConclusion(result.getConclusion());
        concludedRoom.setConclusionTime(concludedAt);
        concludedRoom.setStatus(MeetingStatusEnum.CONCLUDED.getCode());

        // 生成总结
        String meetingModelType = orchestrator.resolveOperationalMeetingModelType(meetingId);
        String summary = orchestrator.callModel(meetingModelType,
                "你是专业会议记录员，请生成结构化会议总结。",
                orchestrator.buildSummaryPrompt(meetingId));
        if (summary != null) {
            roomMapper.updateSummary(meetingId, summary);
        }
        String decisionArtifact = orchestrator.callModel(
                meetingModelType,
                "你是企业决策秘书。请将会议结果整理成严格 JSON 决策卡片，便于后续项目复用。不要输出 JSON 以外的任何文字。",
                orchestrator.buildDecisionArtifactPrompt(meetingId)
        );
        String artifactJson = buildDecisionArtifactJson(meetingId, result.getConclusion(), summary, decisionArtifact);

        AiMeetingRoom conclusionContext = roomMapper.selectById(meetingId);
        if (conclusionContext == null) {
            log.warn("Meeting {} disappeared before conclusion persistence", meetingId);
            return;
        }
        conclusionContext.setFinalConclusion(result.getConclusion());
        conclusionContext.setConclusionTime(concludedAt);
        conclusionContext.setStatus(MeetingStatusEnum.CONCLUDED.getCode());

        roomMapper.updateDecisionArtifact(meetingId, artifactJson);
        roomMapper.updateConclusionHistory(meetingId, appendConclusionHistoryEvent(
                conclusionContext,
                "CONCLUDED",
                result.getConclusion(),
                existing.getStatus() != null && existing.getStatus().equals(MeetingStatusEnum.REOPENED.getCode())
                        ? "会议重开后形成的新结论"
                        : "会议首次达成结论",
                existing.getCurrentRound(),
                concludedAt
        ));
        workflowService.markMeetingConcluded(
                conclusionContext,
                parseDecisionArtifact(artifactJson)
        );
        roomMapper.updateConclusion(concludedRoom);
        log.info("Meeting {} concluded: {}", meetingId, result.getConclusion());

        // 发送微信通知
        AiMeetingRoom concluded = roomMapper.selectById(meetingId);
        notificationService.sendMeetingConclusion(
                concluded.getTitle(), result.getConclusion());
    }

    private boolean isMeetingInProgress(Long meetingId) {
        AiMeetingRoom current = roomMapper.selectById(meetingId);
        if (current == null) {
            log.warn("Meeting {} disappeared during async flow", meetingId);
            return false;
        }
        if (!isActiveStatus(current.getStatus())) {
            log.info("Meeting {} no longer in progress, stop async flow at status={}", meetingId, current.getStatus());
            return false;
        }
        return true;
    }

    private void reopenMeetingForFollowUp(AiMeetingRoom room, String content, boolean asDirective, Long targetMemberId) {
        int nextRound = room.getCurrentRound() > 0 ? room.getCurrentRound() + 1 : 1;
        AiMeetingMember targetMember = resolveTargetMember(room.getId(), targetMemberId);
        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(room.getId());
        opinion.setMemberId(0L);
        opinion.setRoundNumber(nextRound);
        opinion.setContent(content);
        opinion.setEvidence(buildHumanOpinionMeta(targetMember));
        opinion.setOpinionType(resolveHumanOpinionType(asDirective, targetMember));
        opinionMapper.insert(opinion);

        if (!asDirective && targetMember != null) {
            roomMapper.updateCurrentRound(room.getId(), nextRound);
            roomMapper.updateConclusionHistory(room.getId(), appendConclusionHistoryEvent(
                    room,
                    "FOLLOW_UP_TARGETED",
                    null,
                    content,
                    nextRound,
                    new Date()
            ));
            debateService.generateSingleTargetReply(room.getId(), targetMember.getId(), nextRound);
            log.info("Meeting {} handled targeted follow-up synchronously for member {}", room.getId(), targetMember.getId());
            return;
        }

        roomMapper.updateStatus(room.getId(), MeetingStatusEnum.REOPENED.getCode());
        roomMapper.updateCurrentRound(room.getId(), nextRound - 1);
        roomMapper.updateConclusionHistory(room.getId(), appendConclusionHistoryEvent(
                room,
                "REOPEN_HUMAN",
                null,
                content,
                nextRound - 1,
                new Date()
        ));
        workflowService.reopenMeetingWorkflow(room.getId());
        meetingExecutor.submit(() -> runMeetingFlow(room.getId()));
        log.info("Meeting {} reopened by human follow-up, directive={}, nextRound={}", room.getId(), asDirective, nextRound);
    }

    private ConsensusResult buildTerminationFallbackResult(Long meetingId) {
        String conclusion = orchestrator.callModel(
                orchestrator.resolveNonSearchMeetingModelType(meetingId),
                "你是会议主持人。请基于当前讨论内容给出一句话临时结论；如果分歧仍然明显，请明确写出“尚未形成共识”，并概括主要分歧。只输出一句中文结论。",
                orchestrator.buildSummaryPrompt(meetingId)
        );
        if (conclusion == null || conclusion.isBlank()) {
            conclusion = "会议已终止，当前讨论尚未形成稳定共识。";
        }

        ConsensusResult result = new ConsensusResult();
        result.setConsensusReached(false);
        result.setConclusion(conclusion.trim());
        return result;
    }

    private MeetingDetailVO buildVO(AiMeetingRoom room, List<AiMeetingMember> members,
                                     List<AiMeetingOpinion> opinions, List<AiMeetingVote> votes) {
        MeetingDetailVO vo = new MeetingDetailVO();
        vo.setMeetingId(room.getId());
        vo.setTitle(room.getTitle());
        vo.setDescription(room.getDescription());
        vo.setProjectKey(room.getProjectKey());
        vo.setProjectContext(room.getProjectContext());
        vo.setInheritedContext(room.getInheritedContext());
        vo.setStatus(room.getStatus());
        MeetingStatusEnum statusEnum = MeetingStatusEnum.of(room.getStatus());
        vo.setStatusName(statusEnum != null ? statusEnum.getDesc() : "未知");
        vo.setCurrentRound(room.getCurrentRound());
        vo.setTotalRounds(room.getMaxRounds());
        vo.setFinalConclusion(room.getFinalConclusion());
        vo.setConclusionTime(room.getConclusionTime());
        vo.setSummary(room.getSummary());
        vo.setDecisionArtifact(parseDecisionArtifact(room.getDecisionArtifact()));
        vo.setRoundSummaries(room.getRoundSummaries());
        vo.setConclusionHistory(parseConclusionHistory(room.getConclusionHistory(), room));
        vo.setWorkflow(workflowService.buildWorkflowSummary(room.getId()));
        vo.setTracking(workflowService.buildTrackingSummary(room.getId()));
        enrichConclusionHistoryWithTracking(vo.getConclusionHistory(), vo.getTracking());
        vo.setMembers(members);

        if (opinions != null) {
            Map<Long, String> nameMap = members.stream()
                    .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));
            TargetedThreadResolution targetedThreadResolution = resolveTargetedThreads(opinions);
            List<MeetingDetailVO.OpinionVO> opVOs = opinions.stream().map(op -> {
                MeetingDetailVO.OpinionVO ov = new MeetingDetailVO.OpinionVO();
                ov.setId(op.getId());
                ov.setMemberId(op.getMemberId());
                boolean isHuman = op.getOpinionType() != null && op.getOpinionType() == OpinionTypeEnum.HUMAN.getCode();
                boolean isDirective = op.getOpinionType() != null && op.getOpinionType() == OpinionTypeEnum.HUMAN_DIRECTIVE.getCode();
                boolean isTargeted = op.getOpinionType() != null && op.getOpinionType() == OpinionTypeEnum.HUMAN_TARGETED.getCode();
                HumanOpinionMeta meta = parseHumanOpinionMeta(op.getEvidence());
                ov.setMemberName(isDirective ? "主持人" : ((isHuman || isTargeted) ? "你" : nameMap.getOrDefault(op.getMemberId(), "未知")));
                if (meta != null) {
                    ov.setTargetMemberId(meta.targetMemberId);
                    ov.setTargetMemberName(meta.targetMemberName);
                }
                ov.setRoundNumber(op.getRoundNumber());
                ov.setParentOpinionId(op.getParentOpinionId());
                AiMeetingOpinion resolvedQuestion = targetedThreadResolution.questionByReplyOpinionId.get(op.getId());
                if (resolvedQuestion != null) {
                    ov.setResponseToTargetedQuestion(true);
                    ov.setReplyToOpinionId(resolvedQuestion.getId());
                    HumanOpinionMeta parentMeta = parseHumanOpinionMeta(resolvedQuestion.getEvidence());
                    if (parentMeta != null && parentMeta.targetMemberName != null) {
                        ov.setReplyToLabel("回应对「" + parentMeta.targetMemberName + "」的定向追问");
                    } else {
                        ov.setReplyToLabel("回应定向追问");
                    }
                }
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
        if (votes != null) {
            Map<Long, String> nameMap = members.stream()
                    .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));
            List<MeetingDetailVO.VoteVO> voteVOs = votes.stream().map(v -> {
                MeetingDetailVO.VoteVO vv = new MeetingDetailVO.VoteVO();
                vv.setId(v.getId());
                vv.setMemberId(v.getMemberId());
                vv.setMemberName(nameMap.getOrDefault(v.getMemberId(), "未知"));
                vv.setRoundNumber(v.getRoundNumber());
                vv.setVotePosition(v.getVotePosition());
                vv.setConfidence(v.getConfidence());
                vv.setReasoning(v.getReasoning());
                vv.setGmtCreate(v.getGmtCreate());
                return vv;
            }).collect(Collectors.toList());
            vo.setVotes(voteVOs);
        }
        return vo;
    }

    private void enrichConclusionHistoryWithTracking(List<MeetingDetailVO.ConclusionHistoryVO> history,
                                                    MeetingDetailVO.TrackingSummaryVO trackingSummary) {
        if (history == null || history.isEmpty() || trackingSummary == null || trackingSummary.getItems() == null) {
            return;
        }
        MeetingDetailVO.ConclusionHistoryVO latestConcluded = null;
        for (MeetingDetailVO.ConclusionHistoryVO entry : history) {
            if ("CONCLUDED".equalsIgnoreCase(entry.getEventType()) && entry.getConclusion() != null && !entry.getConclusion().isBlank()) {
                latestConcluded = entry;
            }
        }
        if (latestConcluded == null) {
            return;
        }
        List<MeetingDetailVO.ConclusionTrackingLinkVO> links = new ArrayList<>();
        for (MeetingDetailVO.TrackingItemVO item : trackingSummary.getItems()) {
            if (Boolean.TRUE.equals(item.getResolvedByLatestConclusion())) {
                MeetingDetailVO.ConclusionTrackingLinkVO link = new MeetingDetailVO.ConclusionTrackingLinkVO();
                link.setTrackingItemId(item.getId());
                link.setTrackingItemTitle(item.getTitle());
                links.add(link);
            }
        }
        latestConcluded.setRelatedTrackingItems(links);

        for (MeetingDetailVO.ConclusionHistoryVO entry : history) {
            if (!"REOPEN_TRACKING".equalsIgnoreCase(entry.getEventType())) {
                continue;
            }
            TriggeredTrackingRef ref = resolveTriggeredTrackingRef(entry, trackingSummary.getItems());
            if (ref == null) {
                continue;
            }
            entry.setTriggerTrackingItemId(ref.trackingItemId);
            entry.setTriggerTrackingItemTitle(ref.trackingItemTitle);
            entry.setTriggerTrackingUpdateId(ref.trackingUpdateId);
            entry.setTriggerOpinionId(ref.opinionId);
        }

        for (MeetingDetailVO.ConclusionHistoryVO entry : history) {
            if (!"REOPEN_TRACKING".equalsIgnoreCase(entry.getEventType()) || entry.getTriggerTrackingUpdateId() == null) {
                continue;
            }
            bindTrackingUpdateToConclusionVersion(entry.getTriggerTrackingUpdateId(), entry.getVersionNo(), trackingSummary.getItems());
        }
    }

    private void bindTrackingUpdateToConclusionVersion(Long trackingUpdateId,
                                                       Integer versionNo,
                                                       List<MeetingDetailVO.TrackingItemVO> items) {
        if (trackingUpdateId == null || versionNo == null || items == null) {
            return;
        }
        for (MeetingDetailVO.TrackingItemVO item : items) {
            if (item.getUpdates() == null) {
                continue;
            }
            for (MeetingDetailVO.TrackingUpdateVO update : item.getUpdates()) {
                if (trackingUpdateId.equals(update.getId())) {
                    update.setRelatedConclusionVersionNo(versionNo);
                    return;
                }
            }
        }
    }

    private TriggeredTrackingRef resolveTriggeredTrackingRef(MeetingDetailVO.ConclusionHistoryVO entry,
                                                             List<MeetingDetailVO.TrackingItemVO> items) {
        if (entry == null || items == null || items.isEmpty()) {
            return null;
        }
        long targetTime = entry.getEventTime() != null ? entry.getEventTime().getTime() : -1L;
        TriggeredTrackingRef best = null;
        long bestDelta = Long.MAX_VALUE;
        for (MeetingDetailVO.TrackingItemVO item : items) {
            if (item.getUpdates() == null || item.getUpdates().isEmpty()) {
                continue;
            }
            for (MeetingDetailVO.TrackingUpdateVO update : item.getUpdates()) {
                if (!"TRIGGER_REOPEN".equalsIgnoreCase(update.getUpdateType())) {
                    continue;
                }
                long updateTime = update.getGmtCreate() != null ? update.getGmtCreate().getTime() : -1L;
                long delta = targetTime > 0 && updateTime > 0 ? Math.abs(targetTime - updateTime) : Long.MAX_VALUE / 2;
                boolean reasonMatched = entry.getTriggerReason() != null
                        && update.getContent() != null
                        && update.getContent().contains(entry.getTriggerReason());
                if (best == null || reasonMatched || delta < bestDelta) {
                    TriggeredTrackingRef ref = new TriggeredTrackingRef();
                    ref.trackingItemId = item.getId();
                    ref.trackingItemTitle = item.getTitle();
                    ref.trackingUpdateId = update.getId();
                    ref.opinionId = parseOpinionId(update.getEvidenceRef());
                    best = ref;
                    bestDelta = delta;
                    if (reasonMatched && delta <= 5_000L) {
                        return best;
                    }
                }
            }
        }
        if (bestDelta <= 60_000L || best != null) {
            return best;
        }
        return null;
    }

    private Long parseOpinionId(String evidenceRef) {
        if (evidenceRef == null || evidenceRef.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("opinion:(\\d+)").matcher(evidenceRef);
        if (matcher.find()) {
            try {
                return Long.valueOf(matcher.group(1));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static class TriggeredTrackingRef {
        private Long trackingItemId;
        private String trackingItemTitle;
        private Long trackingUpdateId;
        private Long opinionId;
    }

    private String normalizeProjectKey(String projectKey) {
        String normalized = normalizeText(projectKey);
        return normalized == null ? null : normalized.toLowerCase();
    }

    private String normalizeText(String text) {
        if (text == null) return null;
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String buildInheritedContext(Long creatorId, String projectKey) {
        if (creatorId == null || projectKey == null) {
            return null;
        }
        List<AiMeetingRoom> history = roomMapper.selectRecentConcludedByProject(creatorId, projectKey);
        if (history == null || history.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是同项目最近已完成会议沉淀，可作为本次讨论的背景记忆与延续：\n");
        int index = 1;
        for (AiMeetingRoom item : history) {
            sb.append(index++).append(". 会议：").append(item.getTitle()).append("\n");
            if (item.getFinalConclusion() != null && !item.getFinalConclusion().isBlank()) {
                sb.append("   上次结论：").append(item.getFinalConclusion()).append("\n");
            }
            if (item.getDecisionArtifact() != null && !item.getDecisionArtifact().isBlank()) {
                sb.append("   决策卡片：").append(safeCompact(item.getDecisionArtifact(), 500)).append("\n");
            } else if (item.getSummary() != null && !item.getSummary().isBlank()) {
                sb.append("   会议总结：").append(safeCompact(item.getSummary(), 240)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String buildDecisionArtifactJson(Long meetingId, String finalConclusion, String summary, String rawArtifact) {
        String safeFinalConclusion = (finalConclusion == null || finalConclusion.isBlank())
                ? buildFallbackConclusion(roomMapper.selectById(meetingId))
                : finalConclusion;
        try {
            com.alibaba.fastjson.JSONObject parsed = com.alibaba.fastjson.JSON.parseObject(rawArtifact);
            ensureDecisionArtifactDefaults(parsed, meetingId, safeFinalConclusion, summary);
            return parsed.toJSONString();
        } catch (Exception ignored) {
            com.alibaba.fastjson.JSONObject fallback = new com.alibaba.fastjson.JSONObject(true);
            AiMeetingRoom room = roomMapper.selectById(meetingId);
            fallback.put("topicOverview", room != null ? room.getTitle() : "");
            fallback.put("projectKey", room != null ? room.getProjectKey() : null);
            fallback.put("projectContext", room != null ? room.getProjectContext() : null);
            fallback.put("finalDecision", safeFinalConclusion);
            fallback.put("consensusPath", summary);
            fallback.put("keyViewpoints", new com.alibaba.fastjson.JSONArray());
            fallback.put("majorDisputes", new com.alibaba.fastjson.JSONArray());
            fallback.put("decisionBasis", new com.alibaba.fastjson.JSONArray());
            com.alibaba.fastjson.JSONArray openQuestions = new com.alibaba.fastjson.JSONArray();
            openQuestions.add("当前证据不足以形成稳定共识，需要补充关键事实、数据或验证结果。");
            fallback.put("openQuestions", openQuestions);
            com.alibaba.fastjson.JSONArray nextActions = new com.alibaba.fastjson.JSONArray();
            nextActions.add("补充关键证据后重新开启会议，对现有结论进行复核。");
            fallback.put("nextActions", nextActions);
            return fallback.toJSONString();
        }
    }

    private void ensureDecisionArtifactDefaults(com.alibaba.fastjson.JSONObject artifact,
                                                Long meetingId,
                                                String finalConclusion,
                                                String summary) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (!artifact.containsKey("topicOverview")) {
            artifact.put("topicOverview", room != null ? room.getTitle() : "");
        }
        if (!artifact.containsKey("projectKey")) {
            artifact.put("projectKey", room != null ? room.getProjectKey() : null);
        }
        if (!artifact.containsKey("projectContext")) {
            artifact.put("projectContext", room != null ? room.getProjectContext() : null);
        }
        if (!artifact.containsKey("finalDecision")) {
            artifact.put("finalDecision", finalConclusion);
        } else {
            String currentFinalDecision = artifact.getString("finalDecision");
            if (shouldOverrideArtifactFinalDecision(currentFinalDecision, finalConclusion)) {
                artifact.put("finalDecision", finalConclusion);
            }
        }
        if (!artifact.containsKey("consensusPath")) {
            artifact.put("consensusPath", summary);
        }
        ensureArray(artifact, "keyViewpoints");
        ensureArray(artifact, "majorDisputes");
        ensureArray(artifact, "decisionBasis");
        ensureArray(artifact, "openQuestions");
        ensureArray(artifact, "nextActions");
    }

    private boolean shouldOverrideArtifactFinalDecision(String currentFinalDecision, String finalConclusion) {
        if (finalConclusion == null || finalConclusion.isBlank()) {
            return false;
        }
        if (currentFinalDecision == null || currentFinalDecision.isBlank()) {
            return true;
        }
        String normalizedCurrent = currentFinalDecision.replaceAll("\\s+", "").trim();
        String normalizedFinal = finalConclusion.replaceAll("\\s+", "").trim();
        if (normalizedCurrent.equals(normalizedFinal)) {
            return false;
        }
        if (normalizedFinal.startsWith("未达成一致")) {
            return !normalizedCurrent.startsWith("未达成一致，当前占优观点为：");
        }
        return false;
    }

    private void ensureArray(com.alibaba.fastjson.JSONObject artifact, String key) {
        if (!artifact.containsKey(key) || artifact.getJSONArray(key) == null) {
            artifact.put(key, new com.alibaba.fastjson.JSONArray());
        }
    }

    private String safeCompact(String text, int maxLen) {
        if (text == null) return null;
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > maxLen ? compact.substring(0, maxLen) + "…" : compact;
    }

    private String buildFallbackConclusion(AiMeetingRoom room) {
        if (room == null) {
            return "会议已结束，但未能生成稳定结论。";
        }
        return "围绕“" + room.getTitle() + "”的讨论已结束，但由于模型调用或证据不足，暂未形成稳定共识。";
    }

    private void resumeMeetingFromTracking(Long meetingId,
                                           Long trackingItemId,
                                           String trackingItemTitle,
                                           String resumeReason,
                                           String note,
                                           String evidenceRef) {
        if (meetingId == null || trackingItemId == null) {
            return;
        }
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null || MeetingStatusEnum.CANCELLED.getCode().equals(room.getStatus())) {
            return;
        }
        if (isActiveStatus(room.getStatus())) {
            workflowService.markWorkflowWaitingTracking(room.getId(), "追踪项更新后等待当前轮次结束");
            return;
        }

        int nextRound = room.getCurrentRound() > 0 ? room.getCurrentRound() + 1 : 1;
        Long directiveOpinionId = insertTrackingResumeDirective(room, trackingItemTitle, resumeReason, note, evidenceRef, nextRound);
        roomMapper.updateStatus(room.getId(), MeetingStatusEnum.REOPENED.getCode());
        roomMapper.updateCurrentRound(room.getId(), nextRound - 1);
        roomMapper.updateConclusionHistory(room.getId(), appendConclusionHistoryEvent(
                room,
                "REOPEN_TRACKING",
                null,
                resumeReason,
                nextRound - 1,
                new Date()
        ));
        workflowService.markWorkflowActiveDiscussion(room.getId(), resumeReason);
        workflowService.appendTrackingReopenUpdate(
                trackingItemId,
                room.getId(),
                directiveOpinionId,
                buildTrackingReopenAuditContent(trackingItemTitle, resumeReason, nextRound),
                evidenceRef
        );
        meetingExecutor.submit(() -> runMeetingFlow(room.getId()));
        log.info("Meeting {} resumed by tracking signal, trackingItemId={}", room.getId(), trackingItemId);
    }

    private Long insertTrackingResumeDirective(AiMeetingRoom room,
                                               MeetingWorkflowService.TrackingProgressResult result,
                                               int nextRound) {
        return insertTrackingResumeDirective(
                room,
                result.getTrackingItemTitle(),
                result.getResumeReason(),
                result.getNote(),
                result.getEvidenceRef(),
                nextRound
        );
    }

    private Long insertTrackingResumeDirective(AiMeetingRoom room,
                                               String trackingItemTitle,
                                               String resumeReason,
                                               String note,
                                               String evidenceRef,
                                               int nextRound) {
        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(room.getId());
        opinion.setMemberId(0L);
        opinion.setRoundNumber(nextRound);
        opinion.setContent(buildTrackingResumeDirectiveContent(trackingItemTitle, resumeReason, note, evidenceRef));
        opinion.setEvidence(null);
        opinion.setOpinionType(OpinionTypeEnum.HUMAN_DIRECTIVE.getCode());
        opinionMapper.insert(opinion);
        return opinion.getId();
    }

    private String buildTrackingResumeDirectiveContent(MeetingWorkflowService.TrackingProgressResult result) {
        return buildTrackingResumeDirectiveContent(
                result.getTrackingItemTitle(),
                result.getResumeReason(),
                result.getNote(),
                result.getEvidenceRef()
        );
    }

    private String buildTrackingResumeDirectiveContent(String trackingItemTitle,
                                                       String resumeReason,
                                                       String note,
                                                       String evidenceRef) {
        StringBuilder sb = new StringBuilder();
        sb.append("追踪项「")
                .append(trackingItemTitle != null ? trackingItemTitle : "未命名追踪项")
                .append("」已推动会议重开。");
        if (resumeReason != null && !resumeReason.isBlank()) {
            sb.append("原因：").append(resumeReason.trim()).append("。");
        }
        if (note != null && !note.isBlank()) {
            sb.append("补充说明：").append(note.trim()).append("。");
        }
        if (evidenceRef != null && !evidenceRef.isBlank()) {
            sb.append("新增证据：").append(evidenceRef.trim()).append("。");
        }
        sb.append("请基于新增信息继续修订现有结论。");
        return sb.toString();
    }

    private String buildTrackingReopenAuditContent(MeetingWorkflowService.TrackingProgressResult result, int nextRound) {
        return buildTrackingReopenAuditContent(result.getTrackingItemTitle(), result.getResumeReason(), nextRound);
    }

    private String buildTrackingReopenAuditContent(String trackingItemTitle,
                                                   String resumeReason,
                                                   int nextRound) {
        StringBuilder sb = new StringBuilder();
        sb.append("追踪项");
        if (trackingItemTitle != null && !trackingItemTitle.isBlank()) {
            sb.append("「").append(trackingItemTitle.trim()).append("」");
        }
        sb.append("触发会议重开，第 ").append(nextRound).append(" 轮将继续讨论。");
        if (resumeReason != null && !resumeReason.isBlank()) {
            sb.append("触发原因：").append(resumeReason.trim()).append("。");
        }
        return sb.toString();
    }

    private boolean isActiveStatus(Integer status) {
        return MeetingStatusEnum.IN_PROGRESS.getCode().equals(status)
                || MeetingStatusEnum.REOPENED.getCode().equals(status);
    }

    private boolean isEndedStatus(Integer status) {
        return MeetingStatusEnum.CONCLUDED.getCode().equals(status)
                || MeetingStatusEnum.VERIFIED_VALID.getCode().equals(status)
                || MeetingStatusEnum.VERIFIED_INVALID.getCode().equals(status);
    }

    private MeetingDetailVO.DecisionArtifactVO parseDecisionArtifact(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(raw);
            MeetingDetailVO.DecisionArtifactVO vo = new MeetingDetailVO.DecisionArtifactVO();
            vo.setTopicOverview(json.getString("topicOverview"));
            vo.setProjectKey(json.getString("projectKey"));
            vo.setProjectContext(json.getString("projectContext"));
            vo.setConsensusPath(json.getString("consensusPath"));
            vo.setFinalDecision(json.getString("finalDecision"));
            vo.setKeyViewpoints(toStringList(json.getJSONArray("keyViewpoints")));
            vo.setMajorDisputes(toStringList(json.getJSONArray("majorDisputes")));
            vo.setDecisionBasis(toStringList(json.getJSONArray("decisionBasis")));
            vo.setOpenQuestions(toStringList(json.getJSONArray("openQuestions")));
            vo.setNextActions(toStringList(json.getJSONArray("nextActions")));
            return vo;
        } catch (Exception e) {
            log.warn("Failed to parse decision artifact JSON: {}", e.getMessage());
            return null;
        }
    }

    private List<String> toStringList(com.alibaba.fastjson.JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (Object item : array) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    list.add(text);
                }
            }
        }
        return list;
    }

    private List<MeetingDetailVO.ConclusionHistoryVO> parseConclusionHistory(String raw, AiMeetingRoom room) {
        List<MeetingDetailVO.ConclusionHistoryVO> history = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            try {
                com.alibaba.fastjson.JSONArray array = com.alibaba.fastjson.JSON.parseArray(raw);
                for (Object item : array) {
                    if (!(item instanceof com.alibaba.fastjson.JSONObject)) {
                        continue;
                    }
                    com.alibaba.fastjson.JSONObject json = (com.alibaba.fastjson.JSONObject) item;
                    MeetingDetailVO.ConclusionHistoryVO vo = new MeetingDetailVO.ConclusionHistoryVO();
                    vo.setVersionNo(json.getInteger("versionNo"));
                    vo.setEventType(json.getString("eventType"));
                    vo.setEventLabel(resolveConclusionEventLabel(json.getString("eventType")));
                    vo.setConclusion(json.getString("conclusion"));
                    vo.setTriggerReason(json.getString("triggerReason"));
                    vo.setRoundNumber(json.getInteger("roundNumber"));
                    Long eventTime = json.getLong("eventTime");
                    vo.setEventTime(eventTime != null ? new Date(eventTime) : null);
                    history.add(vo);
                }
            } catch (Exception e) {
                log.warn("Failed to parse conclusion history JSON: {}", e.getMessage());
            }
        }
        if (history.isEmpty() && room != null && room.getFinalConclusion() != null && !room.getFinalConclusion().isBlank()) {
            MeetingDetailVO.ConclusionHistoryVO fallback = new MeetingDetailVO.ConclusionHistoryVO();
            fallback.setVersionNo(1);
            fallback.setEventType("CONCLUDED");
            fallback.setEventLabel(resolveConclusionEventLabel("CONCLUDED"));
            fallback.setConclusion(room.getFinalConclusion());
            fallback.setTriggerReason("历史会议数据未保留版本链，已按当前结论回填。");
            fallback.setRoundNumber(room.getCurrentRound());
            fallback.setEventTime(room.getConclusionTime());
            history.add(fallback);
        }
        return history;
    }

    private String appendConclusionHistoryEvent(AiMeetingRoom room,
                                                String eventType,
                                                String conclusion,
                                                String triggerReason,
                                                Integer roundNumber,
                                                Date eventTime) {
        com.alibaba.fastjson.JSONArray array;
        try {
            array = room != null && room.getConclusionHistory() != null
                    ? com.alibaba.fastjson.JSON.parseArray(room.getConclusionHistory())
                    : new com.alibaba.fastjson.JSONArray();
        } catch (Exception e) {
            array = new com.alibaba.fastjson.JSONArray();
        }
        if (array.isEmpty()
                && room != null
                && room.getFinalConclusion() != null
                && !room.getFinalConclusion().isBlank()
                && !"CONCLUDED".equalsIgnoreCase(eventType)) {
            com.alibaba.fastjson.JSONObject seed = new com.alibaba.fastjson.JSONObject(true);
            seed.put("versionNo", 1);
            seed.put("eventType", "CONCLUDED");
            seed.put("conclusion", room.getFinalConclusion());
            seed.put("triggerReason", "历史会议数据未保留版本链，已按当前结论回填。");
            seed.put("roundNumber", room.getCurrentRound());
            seed.put("eventTime", room.getConclusionTime() != null ? room.getConclusionTime().getTime() : System.currentTimeMillis());
            array.add(seed);
        }
        if (isDuplicateConclusionHistoryEvent(array, eventType, conclusion, triggerReason)) {
            return array.toJSONString();
        }
        com.alibaba.fastjson.JSONObject entry = new com.alibaba.fastjson.JSONObject(true);
        entry.put("versionNo", array.size() + 1);
        entry.put("eventType", eventType);
        entry.put("conclusion", conclusion);
        entry.put("triggerReason", triggerReason);
        entry.put("roundNumber", roundNumber);
        entry.put("eventTime", eventTime != null ? eventTime.getTime() : System.currentTimeMillis());
        array.add(entry);
        return array.toJSONString();
    }

    private boolean isDuplicateConclusionHistoryEvent(com.alibaba.fastjson.JSONArray array,
                                                      String eventType,
                                                      String conclusion,
                                                      String triggerReason) {
        if (array == null || array.isEmpty()) {
            return false;
        }
        Object last = array.get(array.size() - 1);
        if (!(last instanceof com.alibaba.fastjson.JSONObject)) {
            return false;
        }
        com.alibaba.fastjson.JSONObject lastJson = (com.alibaba.fastjson.JSONObject) last;
        return java.util.Objects.equals(lastJson.getString("eventType"), eventType)
                && java.util.Objects.equals(lastJson.getString("conclusion"), conclusion)
                && java.util.Objects.equals(lastJson.getString("triggerReason"), triggerReason);
    }

    private String resolveConclusionEventLabel(String eventType) {
        if ("FOLLOW_UP_TARGETED".equalsIgnoreCase(eventType)) {
            return "定向追问";
        }
        if ("REOPEN_HUMAN".equalsIgnoreCase(eventType)) {
            return "人工追问重开";
        }
        if ("REOPEN_TRACKING".equalsIgnoreCase(eventType)) {
            return "追踪项触发重开";
        }
        if ("CONCLUDED".equalsIgnoreCase(eventType)) {
            return "形成结论";
        }
        return eventType;
    }

    private AiMeetingMember resolveTargetMember(Long meetingId, Long targetMemberId) {
        if (targetMemberId == null) {
            return null;
        }
        AiMeetingMember targetMember = memberMapper.selectById(targetMemberId);
        if (targetMember == null || !meetingId.equals(targetMember.getMeetingId())) {
            throw new IllegalArgumentException("指定议员不存在或不属于当前会议: " + targetMemberId);
        }
        return targetMember;
    }

    private Integer resolveHumanOpinionType(boolean asDirective, AiMeetingMember targetMember) {
        if (asDirective) {
            return OpinionTypeEnum.HUMAN_DIRECTIVE.getCode();
        }
        if (targetMember != null) {
            return OpinionTypeEnum.HUMAN_TARGETED.getCode();
        }
        return OpinionTypeEnum.HUMAN.getCode();
    }

    private String buildHumanOpinionMeta(AiMeetingMember targetMember) {
        if (targetMember == null) {
            return null;
        }
        com.alibaba.fastjson.JSONObject meta = new com.alibaba.fastjson.JSONObject(true);
        meta.put("targetMemberId", targetMember.getId());
        meta.put("targetMemberName", targetMember.getMemberName());
        return meta.toJSONString();
    }

    private HumanOpinionMeta parseHumanOpinionMeta(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            com.alibaba.fastjson.JSONObject meta = com.alibaba.fastjson.JSON.parseObject(raw);
            if (meta == null || !meta.containsKey("targetMemberId")) {
                return null;
            }
            HumanOpinionMeta result = new HumanOpinionMeta();
            result.targetMemberId = meta.getLong("targetMemberId");
            result.targetMemberName = meta.getString("targetMemberName");
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    private TargetedThreadResolution resolveTargetedThreads(List<AiMeetingOpinion> opinions) {
        TargetedThreadResolution resolution = new TargetedThreadResolution();
        if (opinions == null || opinions.isEmpty()) {
            return resolution;
        }
        List<AiMeetingOpinion> targetedQuestions = opinions.stream()
                .filter(op -> op.getOpinionType() != null && op.getOpinionType() == OpinionTypeEnum.HUMAN_TARGETED.getCode())
                .collect(Collectors.toList());
        if (targetedQuestions.isEmpty()) {
            return resolution;
        }

        Map<Long, List<AiMeetingOpinion>> aiOpinionsByMember = opinions.stream()
                .filter(this::isAiGeneratedOpinion)
                .collect(Collectors.groupingBy(AiMeetingOpinion::getMemberId));
        Set<Long> assignedReplyIds = new HashSet<>();

        for (AiMeetingOpinion question : targetedQuestions) {
            Long targetMemberId = parseTargetMemberId(question.getEvidence());
            if (targetMemberId == null) {
                continue;
            }
            List<AiMeetingOpinion> candidates = aiOpinionsByMember.getOrDefault(targetMemberId, new ArrayList<>());
            AiMeetingOpinion resolvedReply = findDirectTargetedReply(question, candidates, assignedReplyIds);
            if (resolvedReply == null) {
                resolvedReply = findImplicitTargetedReply(question, candidates, targetedQuestions, assignedReplyIds);
            }
            if (resolvedReply != null) {
                assignedReplyIds.add(resolvedReply.getId());
                resolution.questionByReplyOpinionId.put(resolvedReply.getId(), question);
            }
        }
        return resolution;
    }

    private AiMeetingOpinion findDirectTargetedReply(AiMeetingOpinion question,
                                                     List<AiMeetingOpinion> candidates,
                                                     Set<Long> assignedReplyIds) {
        for (AiMeetingOpinion candidate : candidates) {
            if (assignedReplyIds.contains(candidate.getId())) {
                continue;
            }
            if (candidate.getParentOpinionId() != null && candidate.getParentOpinionId().equals(question.getId())) {
                return candidate;
            }
        }
        return null;
    }

    private AiMeetingOpinion findImplicitTargetedReply(AiMeetingOpinion question,
                                                       List<AiMeetingOpinion> candidates,
                                                       List<AiMeetingOpinion> targetedQuestions,
                                                       Set<Long> assignedReplyIds) {
        Date nextQuestionTime = findNextTargetedQuestionTime(question, targetedQuestions);
        for (AiMeetingOpinion candidate : candidates) {
            if (assignedReplyIds.contains(candidate.getId())) {
                continue;
            }
            if (!isAfterQuestion(candidate, question)) {
                continue;
            }
            if (nextQuestionTime != null && candidate.getGmtCreate() != null
                    && !candidate.getGmtCreate().before(nextQuestionTime)) {
                continue;
            }
            if (candidate.getParentOpinionId() != null) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private Date findNextTargetedQuestionTime(AiMeetingOpinion currentQuestion, List<AiMeetingOpinion> targetedQuestions) {
        Long targetMemberId = parseTargetMemberId(currentQuestion.getEvidence());
        if (targetMemberId == null) {
            return null;
        }
        boolean seenCurrent = false;
        for (AiMeetingOpinion question : targetedQuestions) {
            if (!seenCurrent) {
                if (question.getId().equals(currentQuestion.getId())) {
                    seenCurrent = true;
                }
                continue;
            }
            Long nextTargetMemberId = parseTargetMemberId(question.getEvidence());
            if (targetMemberId.equals(nextTargetMemberId)) {
                return question.getGmtCreate();
            }
        }
        return null;
    }

    private boolean isAfterQuestion(AiMeetingOpinion candidate, AiMeetingOpinion question) {
        if (candidate.getGmtCreate() == null || question.getGmtCreate() == null) {
            return candidate.getRoundNumber() != null && question.getRoundNumber() != null
                    && candidate.getRoundNumber() >= question.getRoundNumber();
        }
        return !candidate.getGmtCreate().before(question.getGmtCreate());
    }

    private boolean isAiGeneratedOpinion(AiMeetingOpinion opinion) {
        if (opinion.getOpinionType() == null) {
            return false;
        }
        return opinion.getOpinionType() == OpinionTypeEnum.STATEMENT.getCode()
                || opinion.getOpinionType() == OpinionTypeEnum.REBUTTAL.getCode()
                || opinion.getOpinionType() == OpinionTypeEnum.CONCESSION.getCode();
    }

    private Long parseTargetMemberId(String raw) {
        HumanOpinionMeta meta = parseHumanOpinionMeta(raw);
        return meta != null ? meta.targetMemberId : null;
    }

    private static class TargetedThreadResolution {
        private final Map<Long, AiMeetingOpinion> questionByReplyOpinionId = new HashMap<>();
    }

    private static class HumanOpinionMeta {
        private Long targetMemberId;
        private String targetMemberName;
    }
}
