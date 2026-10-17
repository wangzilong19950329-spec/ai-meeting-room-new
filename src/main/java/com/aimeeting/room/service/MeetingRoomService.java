package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.config.MeetingReviewedSearchLedgerSchemaInitializer;
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
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
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

    private static final String ENDED_TARGETED_ORIGINAL_STATUS = "endedTargetedOriginalStatus";

    @Autowired private MeetingReviewedEvidenceService reviewedEvidenceService;
    @Autowired private MeetingReviewedSearchLedgerService reviewedSearchLedgerService;
    @Autowired private MeetingReviewedTurnSkipService reviewedTurnSkipService;
    @Autowired private MeetingReviewedSearchProgressService reviewedSearchProgressService;
    @Autowired private MeetingReviewedSearchLedgerSchemaInitializer reviewedSearchLedgerSchemaInitializer;
    @Autowired private MeetingReviewedOpinionWriter reviewedOpinionWriter;
    @Autowired private MeetingReviewedFinalizationWriter reviewedFinalizationWriter;
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
    @Autowired(required = false) private MeetingCallLogService meetingCallLogService;
    @Autowired(required = false) private RoundtableV2Service roundtableV2Service;

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

        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        boolean roundtableV2 = workflowService.isRoundtableV2Workflow(meetingId);
        if (roundtableV2 && !reviewed) {
            throw new IllegalStateException("圆桌 v2 需要先开启审核搜索（AI_MEETING_REVIEWED_SEARCH_ENABLED=true）");
        }
        if (reviewed) reviewedSearchLedgerSchemaInitializer.requireReady();

        if (MeetingStatusEnum.PAUSED.getCode().equals(room.getStatus())
                && reviewed) {
            // An ended meeting's targeted addendum must resume as one reply, not as a new voting round.
            EndedTargetedFollowUp followUp = findEndedTargetedFollowUp(room);
            if (followUp != null) {
                if (followUp.answered) {
                    claimReviewedStart(meetingId, room.getStatus(), epochOf(room), followUp.originalStatus);
                    return;
                }
                long followUpEpoch = claimReviewedStart(meetingId, room.getStatus(), epochOf(room),
                        MeetingStatusEnum.REOPENED.getCode());
                try {
                    meetingExecutor.submit(() -> retryEndedTargetedFollowUp(meetingId, followUp, followUpEpoch));
                } catch (RuntimeException e) {
                    pauseEndedTargetedFollowUp(meetingId, followUp.roundNumber,
                            MeetingStatusEnum.REOPENED.getCode(), followUpEpoch, e);
                    throw e;
                }
                log.info("Meeting {} resumes ended targeted follow-up for member {}", meetingId, followUp.memberId);
                return;
            }
        }

        Long runEpoch = null;
        if (reviewed) {
            runEpoch = claimReviewedStart(meetingId, room.getStatus(), epochOf(room),
                    MeetingStatusEnum.IN_PROGRESS.getCode());
        } else {
            workflowService.ensureWorkflowForMeeting(room, null);
            roomMapper.updateStatus(meetingId, MeetingStatusEnum.IN_PROGRESS.getCode());
        }
        try {
            if (reviewed) {
                workflowService.ensureWorkflowForMeeting(room, null);
            }
            if (roundtableV2) {
                workflowService.activateMeetingWorkflow(meetingId);
                Long capturedEpoch = runEpoch;
                meetingExecutor.submit(() -> roundtableV2Service.run(meetingId, capturedEpoch));
            } else if (workflowService.isDefaultRoundtableWorkflow(meetingId)) {
                workflowService.activateMeetingWorkflow(meetingId);
                Long capturedEpoch = runEpoch;
                meetingExecutor.submit(() -> runMeetingFlow(meetingId, capturedEpoch));
            } else {
                Long capturedEpoch = runEpoch;
                meetingExecutor.submit(() -> {
                    try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                            meetingId, null, null, MeetingCallContext.OTHER)) {
                        workflowService.activateMeetingWorkflow(meetingId);
                    } catch (Exception e) {
                        log.error("Workflow activation error, meetingId={}", meetingId, e);
                        AiMeetingRoom current = roomMapper.selectById(meetingId);
                        if (current != null && isActiveStatus(current.getStatus())) {
                            if (reviewed) {
                                if (isMeetingInProgress(meetingId, capturedEpoch)) {
                                    roomMapper.updateStatusIfCurrentAndEpoch(meetingId, current.getStatus(),
                                            capturedEpoch, MeetingStatusEnum.PAUSED.getCode());
                                }
                            } else {
                                roomMapper.updateStatus(meetingId, MeetingStatusEnum.PAUSED.getCode());
                            }
                        }
                    }
                });
            }
        } catch (RuntimeException e) {
            if (reviewed) {
                try {
                    if (roomMapper.updateStatusIfCurrentAndEpoch(meetingId,
                            MeetingStatusEnum.IN_PROGRESS.getCode(), runEpoch,
                            MeetingStatusEnum.PAUSED.getCode()) == 1) {
                        workflowService.markReviewedEvidenceBlocked(meetingId,
                                "会议启动失败：" + e.getMessage());
                    }
                } catch (RuntimeException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
            }
            throw e;
        }
        log.info("Meeting started async: id={}", meetingId);
    }

    private long claimReviewedStart(Long meetingId, int expectedStatus, long expectedEpoch, int nextStatus) {
        if (roomMapper.claimReviewedRun(meetingId, expectedStatus, expectedEpoch, nextStatus) != 1) {
            AiMeetingRoom current = roomMapper.selectById(meetingId);
            throw new IllegalStateException("会议状态已变化，无法重复启动: status="
                    + (current == null ? "missing" : current.getStatus()));
        }
        return expectedEpoch + 1;
    }

    /** Preparation commits before the caller separately starts the paused meeting. */
    public MeetingReviewedTurnSkip prepareReviewedTurnRetry(Long meetingId, Long memberId,
            Long skipId, Integer expectedRound, Long expectedEpoch) {
        if (reviewedEvidenceService == null || !reviewedEvidenceService.isEnabled()) {
            throw new IllegalStateException("REVIEWED_SEARCH_DISABLED");
        }
        reviewedSearchLedgerSchemaInitializer.requireReady();
        AiMeetingMember member = memberMapper.selectById(memberId);
        if (member == null || !Objects.equals(member.getMeetingId(), meetingId)) {
            throw new IllegalArgumentException("REVIEWED_RETRY_MEMBER_MISMATCH");
        }
        return reviewedTurnSkipService.prepareRetry(meetingId, memberId, skipId, expectedRound, expectedEpoch);
    }

    private long epochOf(AiMeetingRoom room) {
        return room.getRunEpoch() == null ? 0L : room.getRunEpoch();
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
        if (reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()) {
            if (roomMapper.updateStatusIfCurrentAndEpoch(meetingId, room.getStatus(), epochOf(room),
                    MeetingStatusEnum.PAUSED.getCode()) != 1) {
                throw new IllegalStateException("会议状态已变化，无法暂停");
            }
        } else {
            roomMapper.updateStatus(meetingId, MeetingStatusEnum.PAUSED.getCode());
        }
        log.info("Meeting {} paused", meetingId);
    }

    /** 强制终止并生成结论（用于用户主动终止进行中的会议） */
    public void terminateMeeting(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        if (!isActiveStatus(room.getStatus()))
            throw new IllegalStateException("只有进行中的或重开中的会议才能终止: status=" + room.getStatus());

        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        Long terminationEpoch = null;
        if (reviewed) {
            if (roomMapper.bumpEpochIfStatusAndEpoch(meetingId, room.getStatus(), epochOf(room)) != 1) {
                throw new IllegalStateException("会议运行代次已变化，无法终止");
            }
            terminationEpoch = epochOf(room) + 1;
        }
        try {
            ConsensusResult forced = consensusService.forceConclude(meetingId);
            if (forced.getConclusion() == null || forced.getConclusion().isBlank()) {
                forced = reviewed ? buildTerminationFallbackResultStrict(meetingId)
                        : buildTerminationFallbackResult(meetingId);
            }
            finalizeMeeting(meetingId, forced, terminationEpoch);
        } catch (RuntimeException e) {
            if (reviewed) {
                roomMapper.updateStatusIfCurrentAndEpoch(meetingId, room.getStatus(), terminationEpoch,
                        MeetingStatusEnum.PAUSED.getCode());
            }
            throw e;
        }
    }

    /** 重启会议：清空讨论记录，重置为待启动状态 */
    @Transactional
    public void restartMeeting(Long meetingId) {
        // Hold the room row lock until child deletion and reset commit. A concurrent
        // start must not claim PAUSED while this operation is clearing its data.
        AiMeetingRoom room = roomMapper.selectByIdForUpdate(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        Integer status = room.getStatus();
        // 允许从已结论/已验证/已取消状态重启
        if (isActiveStatus(status))
            throw new IllegalStateException("请先终止进行中的会议再重启");

        clusterMapper.deleteByMeetingId(meetingId);
        opinionMapper.deleteByMeetingId(meetingId);
        voteMapper.deleteByMeetingId(meetingId);
        if (reviewedSearchLedgerService != null && reviewedSearchLedgerSchemaInitializer.hasLedgerTables()) {
            reviewedSearchLedgerService.deleteByMeetingId(meetingId);
        }
        if (reviewedTurnSkipService != null && reviewedSearchLedgerSchemaInitializer.hasTurnSkipTable()) {
            reviewedTurnSkipService.deleteByMeetingId(meetingId);
        }
        if (reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()) {
            if (roomMapper.resetForRestartIfCurrentAndEpoch(meetingId, status, epochOf(room)) != 1) {
                throw new IllegalStateException("会议状态或运行代次已变化，无法重启");
            }
        } else if (roomMapper.resetForRestart(meetingId) != 1) {
            throw new IllegalStateException("会议状态已变化，无法重启");
        }
        log.info("Meeting {} reset for restart", meetingId);
    }

    /**
     * 删除会议及其全部关联数据（级联清理）。
     * 进行中/修订中的会议会先切到暂停态，让异步流程在下一次状态检查时停止，再执行级联清理。
     */
    @Transactional
    public void deleteMeeting(Long meetingId) {
        // Serialize deletion against a concurrent start claim on this room.
        AiMeetingRoom room = roomMapper.selectByIdForUpdate(meetingId);
        if (room == null) throw new IllegalArgumentException("会议不存在: " + meetingId);
        if (isActiveStatus(room.getStatus())) {
            if (reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()) {
                if (roomMapper.updateStatusIfCurrentAndEpoch(meetingId, room.getStatus(), epochOf(room),
                        MeetingStatusEnum.PAUSED.getCode()) != 1) {
                    throw new IllegalStateException("会议运行代次已变化，无法删除");
                }
            } else {
                roomMapper.updateStatus(meetingId, MeetingStatusEnum.PAUSED.getCode());
            }
            log.info("Meeting {} paused before deletion", meetingId);
        }

        // 工作流运行 + 阶段记录 + 追踪项/追踪更新
        workflowService.purgeWorkflowAndTrackingData(meetingId);
        // 会议自身的子数据
        scoreLogMapper.deleteByMeetingId(meetingId);
        if (meetingCallLogService != null) meetingCallLogService.deleteByMeetingId(meetingId);
        if (roundtableV2Service != null) roundtableV2Service.deleteByMeetingId(meetingId);
        clusterMapper.deleteByMeetingId(meetingId);
        voteMapper.deleteByMeetingId(meetingId);
        opinionMapper.deleteByMeetingId(meetingId);
        if (reviewedSearchLedgerService != null && reviewedSearchLedgerSchemaInitializer.hasLedgerTables()) {
            reviewedSearchLedgerService.deleteByMeetingId(meetingId);
        }
        if (reviewedTurnSkipService != null && reviewedSearchLedgerSchemaInitializer.hasTurnSkipTable()) {
            reviewedTurnSkipService.deleteByMeetingId(meetingId);
        }
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
        if (reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()) {
            reviewedOpinionWriter.insertHumanIfCurrent(opinion, epochOf(room), status);
        } else {
            opinionMapper.insert(opinion);
        }
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
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, null, null, MeetingCallContext.OTHER)) {
            workflowService.submitCurrentStage(meetingId, request);
        }
    }

    public void reviewWorkflowStage(Long meetingId, WorkflowStageReviewRequest request) {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, null, null, MeetingCallContext.OTHER)) {
            workflowService.reviewCurrentStage(meetingId, request);
        }
    }

    public void retryWorkflowAutoStage(Long meetingId) {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, null, null, MeetingCallContext.OTHER)) {
            workflowService.retryCurrentAutoStage(meetingId);
        }
    }

    // ---- 内部流程 ----

    private void runMeetingFlow(Long meetingId, Long expectedEpoch) {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, null, null, MeetingCallContext.OTHER)) {
            runMeetingFlowInScope(meetingId, expectedEpoch);
        }
    }

    private void runMeetingFlowInScope(Long meetingId, Long expectedEpoch) {
        try {
            AiMeetingRoom room = roomMapper.selectById(meetingId);
            if (room == null) {
                log.warn("Meeting {} disappeared before async flow started", meetingId);
                return;
            }
            boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
            if (reviewed && (expectedEpoch == null || !isMeetingInProgress(meetingId, expectedEpoch))) return;
            int startRound = room.getCurrentRound() > 0 ? room.getCurrentRound() + 1 : 1;
            if (reviewed && room.getCurrentRound() > 0 && !hasRoundSummary(room, room.getCurrentRound())) {
                // An interrupted search, vote, clustering or summary resumes the same round.
                startRound = room.getCurrentRound();
            }
            if (reviewed && room.getCurrentRound() > 0 && hasRoundSummary(room, room.getCurrentRound())) {
                // Old checkpoints may have a summary without enough reviewed speakers or ballots.
                requireReviewedQuorum(meetingId, room.getCurrentRound(), false);
                requireReviewedQuorum(meetingId, room.getCurrentRound(), true);
                ConsensusResult previous = consensusService.checkConsensus(meetingId, room.getCurrentRound());
                if (!isMeetingInProgress(meetingId, expectedEpoch)) return;
                if (previous.isConsensusReached()) {
                    finalizeMeeting(meetingId, previous, expectedEpoch);
                    return;
                }
                if (room.getCurrentRound() >= room.getMaxRounds()) {
                    if (!isMeetingInProgress(meetingId, expectedEpoch)) return;
                    finalizeMeeting(meetingId, consensusService.forceConclude(meetingId), expectedEpoch);
                    return;
                }
            }
            int endRound = reviewed ? room.getMaxRounds() : startRound + room.getMaxRounds() - 1;

            for (int round = startRound; round <= endRound; round++) {
                if (!isMeetingInProgress(meetingId, expectedEpoch)) return;

                log.info("Meeting {} round {} starting", meetingId, round);
                if (reviewed) {
                    if (roomMapper.updateCurrentRoundIfEpoch(meetingId, expectedEpoch, round) != 1) return;
                } else {
                    roomMapper.updateCurrentRound(meetingId, round);
                }

                if (reviewed) {
                    debateService.executeDebateRound(meetingId, round, expectedEpoch);
                } else {
                    debateService.executeDebateRound(meetingId, round);
                }
                if (!isMeetingInProgress(meetingId, expectedEpoch)) return;

                if (reviewed) requireReviewedQuorum(meetingId, round, false);

                if (reviewed) {
                    consensusService.conductVoting(meetingId, round, expectedEpoch);
                } else {
                    consensusService.conductVoting(meetingId, round);
                }
                if (!isMeetingInProgress(meetingId, expectedEpoch)) return;

                if (reviewed) requireReviewedQuorum(meetingId, round, true);

                if (reviewed) {
                    consensusService.clusterVotePositions(meetingId, round, expectedEpoch);
                } else {
                    consensusService.clusterVotePositions(meetingId, round);
                }
                if (!isMeetingInProgress(meetingId, expectedEpoch)) return;

                // 生成本轮摘要（用 deepseek-chat 快速调用，节约成本）
                generateAndSaveRoundSummary(meetingId, round, expectedEpoch);
                if (!isMeetingInProgress(meetingId, expectedEpoch)) return;

                ConsensusResult result = consensusService.checkConsensus(meetingId, round);
                if (!isMeetingInProgress(meetingId, expectedEpoch)) return;
                if (result.isConsensusReached()) {
                    finalizeMeeting(meetingId, result, expectedEpoch);
                    return;
                }
                log.info("Meeting {} round {} no consensus yet", meetingId, round);
            }

            // 达到最大轮数，强制结论
            if (!isMeetingInProgress(meetingId, expectedEpoch)) return;
            log.info("Meeting {} max rounds reached, forcing conclusion", meetingId);
            ConsensusResult forced = consensusService.forceConclude(meetingId);
            if (!isMeetingInProgress(meetingId, expectedEpoch)) return;
            finalizeMeeting(meetingId, forced, expectedEpoch);

        } catch (Exception e) {
            log.error("Meeting flow error, meetingId={}", meetingId, e);
            if (isMeetingInProgress(meetingId, expectedEpoch)) {
                if (reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()) {
                    AiMeetingRoom current = roomMapper.selectById(meetingId);
                    if (current != null && roomMapper.updateStatusIfCurrentAndEpoch(
                            meetingId, current.getStatus(), expectedEpoch,
                            MeetingStatusEnum.PAUSED.getCode()) == 1) {
                        workflowService.markReviewedEvidenceBlocked(meetingId,
                                "会议模型或审核步骤失败：" + e.getMessage());
                    }
                } else {
                    roomMapper.updateStatus(meetingId, MeetingStatusEnum.CANCELLED.getCode());
                }
            }
        }
    }

    /** A missing speaker may not stop peers, but a minority cannot decide for the room. */
    private void requireReviewedQuorum(Long meetingId, int roundNumber, boolean afterVoting) {
        int total = memberMapper.countByMeetingId(meetingId);
        int required = total <= 1 ? 1 : total / 2 + 1;
        Set<Long> eligible = consensusService.reviewedEligibleSpeakerIds(meetingId, roundNumber);
        if (eligible.size() < required) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected(
                    "本轮有效发言不足：需要 " + required + " 位，实际 " + eligible.size() + " 位");
        }
        if (afterVoting) {
            long votes = voteMapper.selectByMeetingIdAndRound(meetingId, roundNumber).stream()
                    .map(AiMeetingVote::getMemberId)
                    .filter(eligible::contains)
                    .distinct().count();
            if (votes < required) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected(
                        "本轮有效投票不足：需要 " + required + " 票，实际 " + votes + " 票");
            }
        }
    }

    private boolean hasRoundSummary(AiMeetingRoom room, int round) {
        if (room == null || room.getRoundSummaries() == null) return false;
        try {
            com.alibaba.fastjson.JSONArray summaries = com.alibaba.fastjson.JSON.parseArray(room.getRoundSummaries());
            return summaries != null && summaries.stream().anyMatch(item -> item instanceof com.alibaba.fastjson.JSONObject
                    && ((com.alibaba.fastjson.JSONObject) item).getIntValue("round") == round);
        } catch (Exception e) {
            return false;
        }
    }

    private void generateAndSaveRoundSummary(Long meetingId, int round, Long expectedEpoch) {
        try {
            boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
            if (reviewed && hasRoundSummary(roomMapper.selectById(meetingId), round)) return;
            String meetingModelType = reviewed ? orchestrator.resolveMeetingModelType(meetingId)
                    : orchestrator.resolveOperationalMeetingModelType(meetingId);
            String summaryText = inCallStage(meetingId, round, MeetingCallContext.ROUND_SUMMARY, () -> reviewed
                    ? orchestrator.callModelStrict(meetingModelType,
                        "你是会议记录员，请用一到两句话（100字以内）概括本轮讨论的核心分歧和主要共识，输出纯文本。",
                        orchestrator.buildRoundSummaryPrompt(meetingId, round), 220)
                    : orchestrator.callModel(meetingModelType,
                        "你是会议记录员，请用一到两句话（100字以内）概括本轮讨论的核心分歧和主要共识，输出纯文本。",
                        orchestrator.buildRoundSummaryPrompt(meetingId, round), 220));
            if (summaryText == null || summaryText.isBlank()) return;
            if (reviewed && !isMeetingInProgress(meetingId, expectedEpoch)) return;

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
            if (reviewed) {
                if (roomMapper.updateRoundSummariesIfEpoch(meetingId, expectedEpoch,
                        arr.toJSONString()) != 1) return;
            } else {
                roomMapper.updateRoundSummaries(meetingId, arr.toJSONString());
            }
            log.info("Round {} summary saved for meeting {}", round, meetingId);
        } catch (Exception e) {
            if (reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()) throw e;
            log.warn("Failed to generate round summary for meeting {} round {}: {}", meetingId, round, e.getMessage());
        }
    }

    private void finalizeMeeting(Long meetingId, ConsensusResult result, Long expectedEpoch) {
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
        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        if (reviewed && !isMeetingInProgress(meetingId, expectedEpoch)) return;
        if (reviewed && (result.getConclusion() == null || result.getConclusion().isBlank())) {
            throw new IllegalStateException("meeting_conclusion_unavailable");
        }
        if (result.getConclusion() == null || result.getConclusion().isBlank()) {
            result.setConclusion(buildFallbackConclusion(existing));
        }
        ReviewedNumberGate.StoredEvidence finalEvidence = reviewed
                ? ReviewedNumberGate.fromStoredOpinions(opinionMapper.selectByMeetingId(meetingId),
                        existing.getCurrentRound() != null ? existing.getCurrentRound() : Integer.MAX_VALUE)
                : null;
        if (reviewed) {
            // The voted conclusion has no rewrite step, so unsupported numbers are masked directly.
            result.setConclusion(finalEvidence.publishable(result.getConclusion()));
        }
        if (reviewed && hasLowConfidenceReviewedStatement(meetingId, existing.getCurrentRound())) {
            String warning = "【本轮部分材料未经充分交叉核验；以下结论为条件性判断】\n";
            if (!result.getConclusion().startsWith(warning)) {
                result.setConclusion(warning + result.getConclusion());
            }
        }

        Date concludedAt = new Date();
        AiMeetingRoom concludedRoom = new AiMeetingRoom();
        concludedRoom.setId(meetingId);
        concludedRoom.setFinalConclusion(result.getConclusion());
        concludedRoom.setConclusionTime(concludedAt);
        concludedRoom.setStatus(MeetingStatusEnum.CONCLUDED.getCode());

        // 生成总结
        String meetingModelType = reviewed ? orchestrator.resolveMeetingModelType(meetingId)
                : orchestrator.resolveOperationalMeetingModelType(meetingId);
        String reviewedAttendance = reviewed
                ? buildReviewedFinalizationAttendance(meetingId, existing.getCurrentRound(), result)
                : "";
        String summarySystem = "你是专业会议记录员，请生成结构化会议总结。";
        String reviewedSummaryPrompt = reviewed
                ? orchestrator.buildSummaryPrompt(meetingId, result.getConclusion()) + reviewedAttendance
                : null;
        Integer finalRound = existing.getCurrentRound();
        String summary = reviewed
                ? gateFinalText(finalEvidence,
                    inCallStage(meetingId, finalRound, MeetingCallContext.SUMMARY,
                            () -> orchestrator.callModelStrict(meetingModelType, summarySystem,
                                    reviewedSummaryPrompt, null)),
                    rewrite -> inCallStage(meetingId, finalRound, MeetingCallContext.SUMMARY_REWRITE,
                            () -> orchestrator.callModelStrict(meetingModelType, summarySystem,
                                    reviewedSummaryPrompt + "\n\n" + rewrite, null)))
                : inCallStage(meetingId, finalRound, MeetingCallContext.SUMMARY,
                    () -> orchestrator.callModel(meetingModelType, summarySystem,
                            orchestrator.buildSummaryPrompt(meetingId)));
        if (reviewed && !isMeetingInProgress(meetingId, expectedEpoch)) return;
        if (summary != null) {
            if (reviewed) {
                if (roomMapper.updateSummaryIfEpoch(meetingId, expectedEpoch, summary) != 1) return;
            } else {
                roomMapper.updateSummary(meetingId, summary);
            }
        }
        String artifactSystem = "你是企业决策秘书。请将会议结果整理成严格 JSON 决策卡片，便于后续项目复用。不要输出 JSON 以外的任何文字。";
        String finalConclusion = result.getConclusion();
        String reviewedArtifactPrompt = reviewed
                ? orchestrator.buildDecisionArtifactPrompt(meetingId, finalConclusion)
                        + reviewedAttendance + "\n只输出 JSON，不要 Markdown，不要代码块。"
                : null;
        String decisionArtifact = inCallStage(meetingId, finalRound, MeetingCallContext.DECISION_CARD,
                () -> reviewed
                        ? orchestrator.callModelStrict(meetingModelType, artifactSystem, reviewedArtifactPrompt, null)
                        : orchestrator.callModel(meetingModelType, artifactSystem,
                            orchestrator.buildDecisionArtifactPrompt(meetingId)));
        if (reviewed && !isMeetingInProgress(meetingId, expectedEpoch)) return;
        String artifactJson = reviewed
                ? gateDecisionArtifact(finalEvidence,
                    buildDecisionArtifactJsonStrict(meetingId, finalConclusion, summary, decisionArtifact),
                    rewrite -> buildDecisionArtifactJsonStrict(meetingId, finalConclusion, summary,
                            inCallStage(meetingId, finalRound, MeetingCallContext.DECISION_CARD_REWRITE,
                                    () -> orchestrator.callModelStrict(meetingModelType, artifactSystem,
                                            reviewedArtifactPrompt + "\n\n" + rewrite, null))))
                : buildDecisionArtifactJson(meetingId, result.getConclusion(), summary, decisionArtifact);

        AiMeetingRoom conclusionContext = roomMapper.selectById(meetingId);
        if (conclusionContext == null) {
            log.warn("Meeting {} disappeared before conclusion persistence", meetingId);
            return;
        }
        String expectedConclusionHistory = conclusionContext.getConclusionHistory();
        conclusionContext.setFinalConclusion(result.getConclusion());
        conclusionContext.setConclusionTime(concludedAt);
        conclusionContext.setStatus(MeetingStatusEnum.CONCLUDED.getCode());

        String conclusionHistory = appendConclusionHistoryEvent(
                conclusionContext,
                "CONCLUDED",
                result.getConclusion(),
                existing.getStatus() != null && existing.getStatus().equals(MeetingStatusEnum.REOPENED.getCode())
                        ? "会议重开后形成的新结论"
                        : "会议首次达成结论",
                existing.getCurrentRound(),
                concludedAt
        );
        if (reviewed) {
            reviewedFinalizationWriter.concludeIfCurrent(meetingId, expectedEpoch,
                    existing.getStatus(), existing.getCurrentRound(), expectedConclusionHistory,
                    artifactJson, conclusionHistory, result.getConclusion(), concludedAt);
            workflowService.markMeetingConcluded(conclusionContext, parseDecisionArtifact(artifactJson));
        } else {
            roomMapper.updateDecisionArtifact(meetingId, artifactJson);
            roomMapper.updateConclusionHistory(meetingId, conclusionHistory);
            workflowService.markMeetingConcluded(conclusionContext, parseDecisionArtifact(artifactJson));
            roomMapper.updateConclusion(concludedRoom);
        }
        log.info("Meeting {} concluded: {}", meetingId, result.getConclusion());

        // 发送微信通知
        AiMeetingRoom concluded = roomMapper.selectById(meetingId);
        notificationService.sendMeetingConclusion(
                concluded.getTitle(), result.getConclusion());
    }

    private <T> T inCallStage(Long meetingId, Integer round, String stage, java.util.function.Supplier<T> call) {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, round, null, stage)) {
            return call.get();
        }
    }

    /** Asks the model once to drop unsupported numbers, then masks whatever is still unsupported. */
    private String gateFinalText(ReviewedNumberGate.StoredEvidence evidence, String text,
                                 java.util.function.UnaryOperator<String> rewrite) {
        if (text == null || text.isBlank()) return text;
        ReviewedNumberGate.Audit audit = evidence.audit(text);
        if (audit.hasUnsupported()) {
            String rewritten = rewrite.apply(ReviewedNumberGate.rewriteInstruction(audit));
            if (rewritten != null && !rewritten.isBlank()) text = rewritten;
        }
        return evidence.publishable(text);
    }

    /** Same policy as {@link #gateFinalText}, applied to every string in the decision card before tracking items are created. */
    private String gateDecisionArtifact(ReviewedNumberGate.StoredEvidence evidence, String artifactJson,
                                        java.util.function.UnaryOperator<String> rewrite) {
        List<String> texts = new ArrayList<>();
        mapArtifactStrings(com.alibaba.fastjson.JSON.parseObject(artifactJson), text -> {
            texts.add(text);
            return text;
        });
        ReviewedNumberGate.Audit audit = evidence.audit(String.join("\n；", texts));
        if (audit.hasUnsupported()) {
            try {
                artifactJson = rewrite.apply(ReviewedNumberGate.rewriteInstruction(audit));
            } catch (RuntimeException invalidRewrite) {
                log.warn("Decision artifact rewrite was unusable; masking the original card instead: {}",
                        invalidRewrite.getMessage());
            }
        }
        return mapArtifactStrings(com.alibaba.fastjson.JSON.parseObject(artifactJson), evidence::publishable)
                .toString();
    }

    private Object mapArtifactStrings(Object node, java.util.function.UnaryOperator<String> mapper) {
        if (node instanceof String) return mapper.apply((String) node);
        if (node instanceof com.alibaba.fastjson.JSONObject) {
            com.alibaba.fastjson.JSONObject object = (com.alibaba.fastjson.JSONObject) node;
            for (String key : new ArrayList<>(object.keySet())) {
                object.put(key, mapArtifactStrings(object.get(key), mapper));
            }
        } else if (node instanceof com.alibaba.fastjson.JSONArray) {
            com.alibaba.fastjson.JSONArray array = (com.alibaba.fastjson.JSONArray) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, mapArtifactStrings(array.get(i), mapper));
            }
        }
        return node;
    }

    private String buildReviewedFinalizationAttendance(Long meetingId, int roundNumber,
                                                        ConsensusResult result) {
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        Map<Long, String> names = members.stream().collect(Collectors.toMap(
                AiMeetingMember::getId, AiMeetingMember::getMemberName, (first, ignored) -> first));
        Set<Long> absent = result.getAbsentMemberIds() != null
                ? new HashSet<>(result.getAbsentMemberIds())
                : new HashSet<>(names.keySet());
        if (result.getAbsentMemberIds() == null) {
            Set<Long> eligible = consensusService.reviewedEligibleSpeakerIds(meetingId, roundNumber);
            voteMapper.selectByMeetingIdAndRound(meetingId, roundNumber).stream()
                    .map(AiMeetingVote::getMemberId).filter(eligible::contains).forEach(absent::remove);
        }
        List<String> absentNames = members.stream().map(AiMeetingMember::getId)
                .filter(absent::contains).map(names::get).collect(Collectors.toList());
        List<MeetingReviewedTurnSkip> skipped = reviewedTurnSkipService.listSkippedByMeetingAndRound(
                meetingId, roundNumber);
        List<String> skippedNames = skipped.stream()
                .map(skip -> names.getOrDefault(skip.getMemberId(), "议员" + skip.getMemberId())
                        + "（" + skip.getReasonCode() + "）")
                .collect(Collectors.toList());
        return "\n【第" + roundNumber + "轮未投票席位】"
                + (absentNames.isEmpty() ? "无" : String.join("、", absentNames))
                + "\n【本轮资料或模型失败而跳过发言的席位】"
                + (skippedNames.isEmpty() ? "无" : String.join("、", skippedNames))
                + "\n上述未投票或跳过席位没有本轮有效立场，不得代写其观点或将其计入支持者、反对者。\n";
    }

    private boolean hasLowConfidenceReviewedStatement(Long meetingId, int roundNumber) {
        Set<Long> eligible = consensusService.reviewedEligibleSpeakerIds(meetingId, roundNumber);
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        if (opinions == null) return false;
        for (AiMeetingOpinion opinion : opinions) {
            if (opinion == null || !eligible.contains(opinion.getMemberId())
                    || !Objects.equals(opinion.getOpinionType(), OpinionTypeEnum.STATEMENT.getCode())
                    || opinion.getEvidence() == null) continue;
            try {
                com.alibaba.fastjson.JSONObject evidence =
                        com.alibaba.fastjson.JSON.parseObject(opinion.getEvidence());
                if (evidence != null && "LOW_CONFIDENCE".equals(evidence.getString("reviewedSearchStatus"))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Malformed evidence never changes the finalization caveat.
            }
        }
        return false;
    }

    private boolean isMeetingInProgress(Long meetingId, Long expectedEpoch) {
        AiMeetingRoom current = roomMapper.selectById(meetingId);
        if (current == null) {
            log.warn("Meeting {} disappeared during async flow", meetingId);
            return false;
        }
        if (!isActiveStatus(current.getStatus())) {
            log.info("Meeting {} no longer in progress, stop async flow at status={}", meetingId, current.getStatus());
            return false;
        }
        if (expectedEpoch != null && epochOf(current) != expectedEpoch) {
            log.info("Meeting {} worker epoch {} is stale; current epoch={}",
                    meetingId, expectedEpoch, epochOf(current));
            return false;
        }
        return true;
    }

    private void reopenMeetingForFollowUp(AiMeetingRoom room, String content, boolean asDirective, Long targetMemberId) {
        int nextRound = room.getCurrentRound() > 0 ? room.getCurrentRound() + 1 : 1;
        int originalStatus = room.getStatus();
        AiMeetingMember targetMember = resolveTargetMember(room.getId(), targetMemberId);
        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        Long reopenedEpoch = reviewed
                ? claimReviewedStart(room.getId(), originalStatus, epochOf(room),
                        MeetingStatusEnum.REOPENED.getCode())
                : null;
        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(room.getId());
        opinion.setMemberId(0L);
        opinion.setRoundNumber(nextRound);
        opinion.setContent(content);
        String humanEvidence = buildHumanOpinionMeta(targetMember);
        if (!asDirective && targetMember != null && reviewed) {
            com.alibaba.fastjson.JSONObject meta = com.alibaba.fastjson.JSON.parseObject(humanEvidence);
            meta.put(ENDED_TARGETED_ORIGINAL_STATUS, originalStatus);
            humanEvidence = meta.toJSONString();
        }
        opinion.setEvidence(humanEvidence);
        opinion.setOpinionType(resolveHumanOpinionType(asDirective, targetMember));
        try {
            opinionMapper.insert(opinion);
        } catch (RuntimeException e) {
            if (reviewed) {
                roomMapper.updateStatusIfCurrentAndEpoch(room.getId(),
                        MeetingStatusEnum.REOPENED.getCode(), reopenedEpoch,
                        MeetingStatusEnum.PAUSED.getCode());
            }
            throw e;
        }

        if (!asDirective && targetMember != null) {
            if (reviewed) {
                if (roomMapper.updateCurrentRoundIfEpoch(room.getId(), reopenedEpoch, nextRound) != 1) return;
            } else {
                roomMapper.updateCurrentRound(room.getId(), nextRound);
            }
            String history = appendConclusionHistoryEvent(
                    room,
                    "FOLLOW_UP_TARGETED",
                    null,
                    content,
                    nextRound,
                    new Date()
            );
            if (reviewed) {
                if (roomMapper.updateConclusionHistoryIfEpoch(room.getId(), reopenedEpoch, history) != 1) return;
            } else {
                roomMapper.updateConclusionHistory(room.getId(), history);
            }
            try {
                if (reviewed) {
                    debateService.generateSingleTargetReply(room.getId(), targetMember.getId(),
                            nextRound, reopenedEpoch);
                    roomMapper.updateStatusIfCurrentAndEpoch(room.getId(),
                            MeetingStatusEnum.REOPENED.getCode(), reopenedEpoch, originalStatus);
                } else {
                    debateService.generateSingleTargetReply(room.getId(), targetMember.getId(), nextRound);
                }
            } catch (RuntimeException e) {
                if (reviewed) {
                    pauseEndedTargetedFollowUp(room.getId(), nextRound,
                            MeetingStatusEnum.REOPENED.getCode(), reopenedEpoch, e);
                }
                throw e;
            }
            log.info("Meeting {} handled targeted follow-up synchronously for member {}", room.getId(), targetMember.getId());
            return;
        }

        if (reviewed) {
            if (roomMapper.updateCurrentRoundIfEpoch(room.getId(), reopenedEpoch, nextRound - 1) != 1) return;
        } else {
            roomMapper.updateStatus(room.getId(), MeetingStatusEnum.REOPENED.getCode());
            roomMapper.updateCurrentRound(room.getId(), nextRound - 1);
        }
        String history = appendConclusionHistoryEvent(
                room,
                "REOPEN_HUMAN",
                null,
                content,
                nextRound - 1,
                new Date()
        );
        if (reviewed) {
            if (roomMapper.updateConclusionHistoryIfEpoch(room.getId(), reopenedEpoch, history) != 1) return;
        } else {
            roomMapper.updateConclusionHistory(room.getId(), history);
        }
        workflowService.reopenMeetingWorkflow(room.getId());
        meetingExecutor.submit(() -> runMeetingFlow(room.getId(), reopenedEpoch));
        log.info("Meeting {} reopened by human follow-up, directive={}, nextRound={}", room.getId(), asDirective, nextRound);
    }

    private void retryEndedTargetedFollowUp(Long meetingId, EndedTargetedFollowUp followUp,
                                             long expectedEpoch) {
        try {
            AiMeetingRoom current = roomMapper.selectById(meetingId);
            if (current == null || !MeetingStatusEnum.REOPENED.getCode().equals(current.getStatus())
                    || epochOf(current) != expectedEpoch) return;
            EndedTargetedFollowUp latest = findEndedTargetedFollowUp(current);
            if (latest == null || !followUp.questionId.equals(latest.questionId)) {
                throw new IllegalStateException("targeted_follow_up_identity_changed");
            }
            if (!latest.answered) {
                debateService.generateSingleTargetReply(meetingId, followUp.memberId,
                        followUp.roundNumber, expectedEpoch);
            }
            current = roomMapper.selectById(meetingId);
            if (current != null && MeetingStatusEnum.REOPENED.getCode().equals(current.getStatus())
                    && epochOf(current) == expectedEpoch) {
                roomMapper.updateStatusIfCurrentAndEpoch(meetingId,
                        MeetingStatusEnum.REOPENED.getCode(), expectedEpoch, followUp.originalStatus);
            }
        } catch (RuntimeException e) {
            pauseEndedTargetedFollowUp(meetingId, followUp.roundNumber,
                    MeetingStatusEnum.REOPENED.getCode(), expectedEpoch, e);
            log.error("Meeting {} ended targeted follow-up retry failed", meetingId, e);
        }
    }

    private void pauseEndedTargetedFollowUp(Long meetingId, int roundNumber,
                                           int expectedStatus, long expectedEpoch, RuntimeException failure) {
        if (roomMapper.updateStatusIfCurrentAndEpoch(meetingId, expectedStatus, expectedEpoch,
                MeetingStatusEnum.PAUSED.getCode()) != 1) {
            log.info("Meeting {} targeted follow-up failure arrived after status changed", meetingId);
            return;
        }
        AiMeetingRoom current = roomMapper.selectById(meetingId);
        if (current != null) {
            String reason = "点名追问资料或发言失败：" + failure.getMessage();
            roomMapper.updateConclusionHistoryIfEpoch(meetingId, expectedEpoch + 1,
                    appendConclusionHistoryEvent(
                    current, "FOLLOW_UP_TARGETED_BLOCKED", current.getFinalConclusion(),
                    reason, roundNumber, new Date()));
        }
        log.warn("Meeting {} paused after targeted follow-up failure: {}", meetingId, failure.getMessage());
    }

    private EndedTargetedFollowUp findEndedTargetedFollowUp(AiMeetingRoom room) {
        if (room == null || room.getId() == null || room.getCurrentRound() == null) return null;
        List<AiMeetingOpinion> roundOpinions = opinionMapper.selectByMeetingIdAndRound(
                room.getId(), room.getCurrentRound());
        if (roundOpinions == null || roundOpinions.isEmpty()) return null;
        for (int i = roundOpinions.size() - 1; i >= 0; i--) {
            AiMeetingOpinion question = roundOpinions.get(i);
            if (question == null || !OpinionTypeEnum.HUMAN_TARGETED.getCode().equals(question.getOpinionType())
                    || question.getId() == null || question.getEvidence() == null) continue;
            com.alibaba.fastjson.JSONObject meta;
            try {
                meta = com.alibaba.fastjson.JSON.parseObject(question.getEvidence());
            } catch (Exception ignored) {
                continue;
            }
            if (meta == null) continue;
            Integer originalStatus = meta.getInteger(ENDED_TARGETED_ORIGINAL_STATUS);
            Long memberId = meta.getLong("targetMemberId");
            if (!isEndedStatus(originalStatus) || memberId == null) continue;
            boolean answered = roundOpinions.stream().anyMatch(opinion -> opinion != null
                    && question.getId().equals(opinion.getParentOpinionId())
                    && memberId.equals(opinion.getMemberId())
                    && OpinionTypeEnum.STATEMENT.getCode().equals(opinion.getOpinionType()));
            return new EndedTargetedFollowUp(question.getId(), memberId,
                    room.getCurrentRound(), originalStatus, answered);
        }
        return null;
    }

    private static final class EndedTargetedFollowUp {
        private final Long questionId;
        private final Long memberId;
        private final int roundNumber;
        private final int originalStatus;
        private final boolean answered;

        private EndedTargetedFollowUp(Long questionId, Long memberId, int roundNumber,
                                      int originalStatus, boolean answered) {
            this.questionId = questionId;
            this.memberId = memberId;
            this.roundNumber = roundNumber;
            this.originalStatus = originalStatus;
            this.answered = answered;
        }
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

    private ConsensusResult buildTerminationFallbackResultStrict(Long meetingId) {
        String conclusion = orchestrator.callModelStrict(
                orchestrator.resolveMeetingModelType(meetingId),
                "你是会议主持人。请基于当前已记录的讨论给出一句话临时结论；若分歧明显请说明尚未形成共识。只输出一句中文结论。",
                orchestrator.buildSummaryPrompt(meetingId), null);
        if (conclusion == null || conclusion.isBlank()) {
            throw new IllegalStateException("meeting_termination_conclusion_unavailable");
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

        if (opinions != null && reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()
                && reviewedSearchProgressService != null) {
            reviewedSearchProgressService.fill(vo, room.getId(), members, opinions);
        }

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

    private String buildDecisionArtifactJsonStrict(Long meetingId, String finalConclusion,
                                                   String summary, String rawArtifact) {
        com.alibaba.fastjson.JSONObject parsed;
        try {
            String text = rawArtifact == null ? "" : rawArtifact.trim();
            if (!text.startsWith("{") || !text.endsWith("}")) {
                throw new IllegalArgumentException("not_json_object");
            }
            parsed = com.alibaba.fastjson.JSON.parseObject(text);
            if (parsed == null) throw new IllegalArgumentException("not_json_object");
            for (String key : List.of("topicOverview", "projectKey", "projectContext", "consensusPath",
                    "finalDecision", "keyViewpoints", "majorDisputes", "decisionBasis",
                    "openQuestions", "nextActions")) {
                if (!parsed.containsKey(key)) throw new IllegalArgumentException("missing_" + key);
            }
            for (String key : List.of("keyViewpoints", "majorDisputes", "decisionBasis",
                    "openQuestions", "nextActions")) {
                com.alibaba.fastjson.JSONArray values = parsed.getJSONArray(key);
                if (values == null || values.stream().anyMatch(value -> !(value instanceof String))) {
                    throw new IllegalArgumentException("invalid_" + key);
                }
            }
            if (parsed.getString("finalDecision") == null || parsed.getString("finalDecision").isBlank()) {
                throw new IllegalArgumentException("finalDecision_blank");
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("meeting_decision_artifact_invalid", invalid);
        }
        ensureDecisionArtifactDefaults(parsed, meetingId, finalConclusion, summary);
        // The model may echo an older room conclusion; the voted result is authoritative.
        parsed.put("finalDecision", finalConclusion);
        return parsed.toJSONString();
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
        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        Long resumedEpoch = reviewed
                ? claimReviewedStart(room.getId(), room.getStatus(), epochOf(room),
                        MeetingStatusEnum.REOPENED.getCode())
                : null;
        Long directiveOpinionId = insertTrackingResumeDirective(room, trackingItemTitle, resumeReason, note, evidenceRef, nextRound);
        if (reviewed) {
            if (roomMapper.updateCurrentRoundIfEpoch(room.getId(), resumedEpoch, nextRound - 1) != 1) return;
        } else {
            roomMapper.updateStatus(room.getId(), MeetingStatusEnum.REOPENED.getCode());
            roomMapper.updateCurrentRound(room.getId(), nextRound - 1);
        }
        String history = appendConclusionHistoryEvent(
                room,
                "REOPEN_TRACKING",
                null,
                resumeReason,
                nextRound - 1,
                new Date()
        );
        if (reviewed) {
            if (roomMapper.updateConclusionHistoryIfEpoch(room.getId(), resumedEpoch, history) != 1) return;
        } else {
            roomMapper.updateConclusionHistory(room.getId(), history);
        }
        workflowService.markWorkflowActiveDiscussion(room.getId(), resumeReason);
        workflowService.appendTrackingReopenUpdate(
                trackingItemId,
                room.getId(),
                directiveOpinionId,
                buildTrackingReopenAuditContent(trackingItemTitle, resumeReason, nextRound),
                evidenceRef
        );
        meetingExecutor.submit(() -> runMeetingFlow(room.getId(), resumedEpoch));
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
        if ("FOLLOW_UP_TARGETED_BLOCKED".equalsIgnoreCase(eventType)) {
            return "定向追问暂停";
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
