package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingTrackingItemMapper;
import com.aimeeting.room.dao.AiMeetingTrackingUpdateMapper;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMemberTemplateMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiSkillFileMapper;
import com.aimeeting.room.dao.AiWorkflowRunMapper;
import com.aimeeting.room.dao.AiWorkflowStageRunMapper;
import com.aimeeting.room.dao.AiWorkflowStageSubmissionMapper;
import com.aimeeting.room.dao.AiWorkflowStageTemplateMapper;
import com.aimeeting.room.dao.AiWorkflowTemplateMapper;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.dto.MeetingCreateRequest;
import com.aimeeting.room.dto.SkillFileVO;
import com.aimeeting.room.dto.WorkflowStageReviewRequest;
import com.aimeeting.room.dto.WorkflowStageSubmitRequest;
import com.aimeeting.room.dto.WorkflowTemplateRequest;
import com.aimeeting.room.dto.WorkflowTemplateVO;
import com.aimeeting.room.service.ai.WebSearchService;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMemberTemplate;
import com.aimeeting.room.entity.AiMeetingTrackingItem;
import com.aimeeting.room.entity.AiMeetingTrackingUpdate;
import com.aimeeting.room.entity.AiSkillFile;
import com.aimeeting.room.entity.AiWorkflowRun;
import com.aimeeting.room.entity.AiWorkflowStageRun;
import com.aimeeting.room.entity.AiWorkflowStageSubmission;
import com.aimeeting.room.entity.AiWorkflowStageTemplate;
import com.aimeeting.room.entity.AiWorkflowTemplate;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.TrackingItemStatusEnum;
import com.aimeeting.room.enums.WorkflowRunStatusEnum;
import com.aimeeting.room.enums.WorkflowStageStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingWorkflowService {

    public static final String DEFAULT_TEMPLATE_KEY = "default-roundtable";
    public static final String STAGE_DISCUSSION = "discussion";
    public static final String STAGE_CONCLUSION = "conclusion";
    public static final String STAGE_TRACKING = "tracking";

    private final AiWorkflowTemplateMapper workflowTemplateMapper;
    private final AiWorkflowStageTemplateMapper workflowStageTemplateMapper;
    private final AiWorkflowRunMapper workflowRunMapper;
    private final AiWorkflowStageRunMapper workflowStageRunMapper;
    private final AiWorkflowStageSubmissionMapper workflowStageSubmissionMapper;
    private final AiMeetingTrackingItemMapper trackingItemMapper;
    private final AiMeetingTrackingUpdateMapper trackingUpdateMapper;
    private final AiMeetingMemberMapper memberMapper;
    private final AiMemberTemplateMapper memberTemplateMapper;
    private final AiMeetingRoomMapper roomMapper;
    private final AiSkillFileMapper skillFileMapper;
    private final MeetingOrchestratorService orchestrator;
    private final WebSearchService webSearch;

    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");

    private static class AutoStageExecutionResult {
        private final String finalContent;
        private final boolean mergedOutput;
        private final List<String> sourceActorRefs;
        private final List<WorkflowStageSearchTrace> searchTraces;
        private final String blockedReason;

        private AutoStageExecutionResult(String finalContent,
                                         boolean mergedOutput,
                                         List<String> sourceActorRefs,
                                         List<WorkflowStageSearchTrace> searchTraces,
                                         String blockedReason) {
            this.finalContent = finalContent;
            this.mergedOutput = mergedOutput;
            this.sourceActorRefs = sourceActorRefs;
            this.searchTraces = searchTraces;
            this.blockedReason = blockedReason;
        }

        private static AutoStageExecutionResult success(String finalContent,
                                                        boolean mergedOutput,
                                                        List<String> sourceActorRefs,
                                                        List<WorkflowStageSearchTrace> searchTraces) {
            return new AutoStageExecutionResult(finalContent, mergedOutput, sourceActorRefs, searchTraces, null);
        }

        private static AutoStageExecutionResult blocked(String blockedReason,
                                                        List<WorkflowStageSearchTrace> searchTraces) {
            return new AutoStageExecutionResult(null, false, new ArrayList<>(), searchTraces, blockedReason);
        }
    }

    private static class WorkflowStageSearchTrace {
        private final String strategy;
        private final boolean attempted;
        private final WorkflowSearchContract contract;
        private final WorkflowSearchPlan agentPlan;
        private final List<String> queries;
        private final List<WebSearchService.SearchResult> results;
        private final WebSearchService.SearchMeta meta;
        private final WebSearchService.ResearchDigest digest;
        private final long executedAt;
        private final String note;
        private Boolean judgementAcceptedAsDeliverable;
        private Boolean judgementIsSearchTraceOnly;
        private String judgementReason;
        private List<String> judgementMissing;

        private WorkflowStageSearchTrace(String strategy,
                                         boolean attempted,
                                         WorkflowSearchContract contract,
                                         WorkflowSearchPlan agentPlan,
                                         List<String> queries,
                                         List<WebSearchService.SearchResult> results,
                                         WebSearchService.SearchMeta meta,
                                         WebSearchService.ResearchDigest digest,
                                         long executedAt,
                                         String note) {
            this.strategy = strategy;
            this.attempted = attempted;
            this.contract = contract;
            this.agentPlan = agentPlan;
            this.queries = queries != null ? queries : new ArrayList<>();
            this.results = results != null ? results : new ArrayList<>();
            this.meta = meta;
            this.digest = digest;
            this.executedAt = executedAt;
            this.note = note;
        }
    }

    private static class NodeDeliverableContract {
        private final String identityTitle;
        private final String deliverableWhat;
        private final String deliverableShape;
        private final String deliverableNotDoing;
        private final String reviewCriteria;

        private NodeDeliverableContract(String identityTitle,
                                        String deliverableWhat,
                                        String deliverableShape,
                                        String deliverableNotDoing,
                                        String reviewCriteria) {
            this.identityTitle = identityTitle;
            this.deliverableWhat = deliverableWhat;
            this.deliverableShape = deliverableShape;
            this.deliverableNotDoing = deliverableNotDoing;
            this.reviewCriteria = reviewCriteria;
        }

        private String renderForPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("【节点产出契约】\n");
            sb.append("- 你产出的内容：").append(deliverableWhat).append("\n");
            sb.append("- 产出结构：").append(deliverableShape).append("\n");
            sb.append("- 禁止做：").append(deliverableNotDoing).append("\n");
            if (StringUtils.hasText(reviewCriteria)) {
                sb.append("- 节点指令（审核依据）：").append(reviewCriteria).append("\n");
            }
            return sb.toString();
        }
    }

    private static class DeliverableJudgement {
        private final boolean satisfiesDeliverable;
        private final boolean isSearchTraceOnly;
        private final String reason;
        private final List<String> missing;

        private DeliverableJudgement(boolean satisfiesDeliverable,
                                     boolean isSearchTraceOnly,
                                     String reason,
                                     List<String> missing) {
            this.satisfiesDeliverable = satisfiesDeliverable;
            this.isSearchTraceOnly = isSearchTraceOnly;
            this.reason = reason;
            this.missing = missing != null ? missing : new ArrayList<>();
        }

        private boolean acceptAsDeliverable() {
            return satisfiesDeliverable && !isSearchTraceOnly;
        }
    }

    private static class WorkflowSearchPlan {
        private final String mode;
        private final String note;
        private final List<String> queries;
        private final List<String> preferredSources;
        private final List<String> requiredFields;

        private WorkflowSearchPlan(String mode,
                                   String note,
                                   List<String> queries,
                                   List<String> preferredSources,
                                   List<String> requiredFields) {
            this.mode = StringUtils.hasText(mode) ? mode : "agent_planned";
            this.note = note;
            this.queries = queries != null ? queries : new ArrayList<>();
            this.preferredSources = preferredSources != null ? preferredSources : new ArrayList<>();
            this.requiredFields = requiredFields != null ? requiredFields : new ArrayList<>();
        }

        private boolean hasQueries() {
            return queries != null && queries.stream().anyMatch(StringUtils::hasText);
        }
    }

    private static class WorkflowSearchContract {
        private final boolean strict;
        private final String note;
        private final List<WorkflowSearchSourceRequirement> sourceRequirements;
        private final List<String> requiredFields;

        private WorkflowSearchContract(boolean strict,
                                       String note,
                                       List<WorkflowSearchSourceRequirement> sourceRequirements,
                                       List<String> requiredFields) {
            this.strict = strict;
            this.note = note;
            this.sourceRequirements = sourceRequirements != null ? sourceRequirements : new ArrayList<>();
            this.requiredFields = requiredFields != null ? requiredFields : new ArrayList<>();
        }
    }

    private static class WorkflowSearchSourceRequirement {
        private final String label;
        private final String domain;
        private final boolean required;
        private final List<String> focusTerms;

        private WorkflowSearchSourceRequirement(String label,
                                                String domain,
                                                boolean required,
                                                List<String> focusTerms) {
            this.label = label;
            this.domain = domain;
            this.required = required;
            this.focusTerms = focusTerms != null ? focusTerms : new ArrayList<>();
        }
    }

    private static class AutoReviewDecision {
        private final boolean approved;
        private final String comment;
        private final List<String> sourceActorRefs;

        private AutoReviewDecision(boolean approved, String comment, List<String> sourceActorRefs) {
            this.approved = approved;
            this.comment = comment;
            this.sourceActorRefs = sourceActorRefs;
        }
    }

    private static class WorkflowNodeSearchSummary {
        private boolean attempted;
        private String status;
        private String provider;
        private int queryCount;
        private int resultCount;
        private String note;
        private boolean degraded;
        private final List<String> diagnostics = new ArrayList<>();
    }

    public static class TrackingProgressResult {
        private final Long meetingId;
        private final Long trackingItemId;
        private final String trackingItemTitle;
        private final String newStatus;
        private final boolean shouldResumeMeeting;
        private final String resumeReason;
        private final String note;
        private final String evidenceRef;

        public TrackingProgressResult(Long meetingId, Long trackingItemId, String trackingItemTitle,
                                     String newStatus, boolean shouldResumeMeeting, String resumeReason,
                                     String note, String evidenceRef) {
            this.meetingId = meetingId;
            this.trackingItemId = trackingItemId;
            this.trackingItemTitle = trackingItemTitle;
            this.newStatus = newStatus;
            this.shouldResumeMeeting = shouldResumeMeeting;
            this.resumeReason = resumeReason;
            this.note = note;
            this.evidenceRef = evidenceRef;
        }

        public Long getMeetingId() {
            return meetingId;
        }

        public Long getTrackingItemId() {
            return trackingItemId;
        }

        public String getTrackingItemTitle() {
            return trackingItemTitle;
        }

        public String getNewStatus() {
            return newStatus;
        }

        public boolean isShouldResumeMeeting() {
            return shouldResumeMeeting;
        }

        public String getResumeReason() {
            return resumeReason;
        }

        public String getNote() {
            return note;
        }

        public String getEvidenceRef() {
            return evidenceRef;
        }
    }

    public void ensureDefaultTemplateExists() {
        AiWorkflowTemplate existing = workflowTemplateMapper.selectByKey(DEFAULT_TEMPLATE_KEY);
        if (existing != null) {
            return;
        }

        AiWorkflowTemplate template = new AiWorkflowTemplate();
        template.setTemplateKey(DEFAULT_TEMPLATE_KEY);
        template.setName("默认圆桌讨论");
        template.setDescription("兼容现有多轮辩论、投票、共识与结论流程的系统内置模板。");
        template.setActorSlotsConfig(null);
        template.setBuiltIn(1);
        template.setActive(1);
        workflowTemplateMapper.insert(template);

        List<AiWorkflowStageTemplate> stages = new ArrayList<>();
        stages.add(buildStageTemplate(template.getId(), STAGE_DISCUSSION, "圆桌讨论", 1,
                "DEBATE", "ALL_MEMBERS", "MEETING_CONCLUDED", null, null, "OFF", STAGE_CONCLUSION, null, STAGE_DISCUSSION, 1));
        stages.add(buildStageTemplate(template.getId(), STAGE_CONCLUSION, "结论归档", 2,
                "CONCLUDE", "SYSTEM", "AUTO_COMPLETE", null, null, "OFF", STAGE_TRACKING, null, STAGE_DISCUSSION, 0));
        stages.add(buildStageTemplate(template.getId(), STAGE_TRACKING, "后续追踪", 3,
                "TRACKING", "HUMAN", "TRACKING_OPTIONAL", null, null, "OFF", null, null, STAGE_DISCUSSION, 0));
        workflowStageTemplateMapper.batchInsert(stages);
        log.info("Seeded default workflow template {}", DEFAULT_TEMPLATE_KEY);
    }

    public String normalizeTemplateKey(String templateKey) {
        if (!StringUtils.hasText(templateKey)) {
            return DEFAULT_TEMPLATE_KEY;
        }
        return templateKey.trim().toLowerCase();
    }

    public AiWorkflowRun ensureWorkflowForMeeting(AiMeetingRoom room, String requestedTemplateKey) {
        AiWorkflowRun existing = workflowRunMapper.selectByMeetingId(room.getId());
        if (existing != null) {
            return existing;
        }

        AiWorkflowTemplate template = resolveTemplate(requestedTemplateKey);
        List<AiWorkflowStageTemplate> stageTemplates = workflowStageTemplateMapper.selectByTemplateId(template.getId());
        if (stageTemplates == null || stageTemplates.isEmpty()) {
            throw new IllegalStateException("工作流模板未配置任何阶段: " + template.getTemplateKey());
        }
        stageTemplates = stageTemplates.stream()
                .sorted(Comparator.comparing(AiWorkflowStageTemplate::getStageOrder))
                .collect(Collectors.toList());
        AiWorkflowStageTemplate firstStage = stageTemplates.get(0);

        AiWorkflowRun run = new AiWorkflowRun();
        run.setMeetingId(room.getId());
        run.setTemplateId(template.getId());
        run.setTemplateKey(template.getTemplateKey());
        run.setCurrentStageKey(firstStage.getStageKey());
        run.setCurrentStageOrder(firstStage.getStageOrder());
        run.setStatus(WorkflowRunStatusEnum.PENDING.getCode());
        run.setBlockedReason(null);
        run.setReopenCount(0);
        workflowRunMapper.insert(run);

        List<AiWorkflowStageRun> stageRuns = new ArrayList<>();
        for (AiWorkflowStageTemplate stageTemplate : stageTemplates) {
            AiWorkflowStageRun stageRun = new AiWorkflowStageRun();
            stageRun.setWorkflowRunId(run.getId());
            stageRun.setMeetingId(room.getId());
            stageRun.setStageTemplateId(stageTemplate.getId());
            stageRun.setStageKey(stageTemplate.getStageKey());
            stageRun.setStageName(stageTemplate.getStageName());
            stageRun.setStageOrder(stageTemplate.getStageOrder());
            stageRun.setStageType(stageTemplate.getStageType());
            stageRun.setActorType(stageTemplate.getActorType());
            stageRun.setAttemptNo(1);
            stageRun.setStatus(WorkflowStageStatusEnum.PENDING.getCode());
            stageRun.setOutcome(null);
            stageRun.setRejectReason(null);
            stageRun.setStartedAt(null);
            stageRun.setCompletedAt(null);
            stageRuns.add(stageRun);
        }
        workflowStageRunMapper.batchInsert(stageRuns);
        return run;
    }

    public String resolveWorkflowTemplateKey(Long meetingId) {
        if (meetingId == null) {
            return null;
        }
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        return run != null ? run.getTemplateKey() : null;
    }

    public List<AiWorkflowTemplate> listActiveTemplates() {
        ensureDefaultTemplateExists();
        return workflowTemplateMapper.selectActiveTemplates();
    }

    public List<WorkflowTemplateVO> listTemplateDetails() {
        ensureDefaultTemplateExists();
        return workflowTemplateMapper.selectAll().stream()
                .map(this::toTemplateVO)
                .collect(Collectors.toList());
    }

    public WorkflowTemplateVO createTemplate(WorkflowTemplateRequest request) {
        ensureDefaultTemplateExists();
        String key = normalizeTemplateKey(request.getTemplateKey());
        if (DEFAULT_TEMPLATE_KEY.equals(key)) {
            throw new IllegalArgumentException("默认模板标识已被系统占用");
        }
        if (workflowTemplateMapper.selectByKey(key) != null) {
            throw new IllegalArgumentException("模板标识已存在: " + key);
        }
        validateStageRequests(request);

        AiWorkflowTemplate template = new AiWorkflowTemplate();
        template.setTemplateKey(key);
        template.setName(request.getName().trim());
        template.setDescription(StringUtils.hasText(request.getDescription()) ? request.getDescription().trim() : null);
        template.setLayoutConfig(StringUtils.hasText(request.getLayoutConfig()) ? request.getLayoutConfig().trim() : null);
        template.setActorSlotsConfig(buildActorSlotsConfig(request.getActorSlots()));
        template.setBuiltIn(0);
        template.setActive(1);
        workflowTemplateMapper.insert(template);

        List<AiWorkflowStageTemplate> stages = new ArrayList<>();
        for (int i = 0; i < request.getStages().size(); i++) {
            WorkflowTemplateRequest.StageRequest stageRequest = request.getStages().get(i);
            AiWorkflowStageTemplate stage = new AiWorkflowStageTemplate();
            stage.setTemplateId(template.getId());
            stage.setStageKey(stageRequest.getStageKey().trim().toLowerCase());
            stage.setStageName(stageRequest.getStageName().trim());
            stage.setStageOrder(i + 1);
            stage.setStageType(stageRequest.getStageType().trim().toUpperCase());
            stage.setActorType(stageRequest.getActorType().trim().toUpperCase());
            stage.setActorBindingMode(defaultActorBindingMode(stageRequest));
            stage.setActorSlotKey(StringUtils.hasText(stageRequest.getActorSlotKey()) ? stageRequest.getActorSlotKey().trim().toLowerCase() : null);
            stage.setCompletionRule(StringUtils.hasText(stageRequest.getCompletionRule()) ? stageRequest.getCompletionRule().trim().toUpperCase() : null);
            stage.setSkillKey(StringUtils.hasText(stageRequest.getSkillKey()) ? stageRequest.getSkillKey().trim() : null);
            stage.setSkillPrompt(StringUtils.hasText(stageRequest.getSkillPrompt()) ? stageRequest.getSkillPrompt().trim() : null);
            stage.setSkillFileIds(buildSkillFileIds(stageRequest.getSkillFileIds()));
            stage.setSearchStrategy(normalizeSearchStrategy(stageRequest.getSearchStrategy(), stage.getStageType()));
            stage.setInputStageKeys(buildInputStageKeys(stageRequest.getInputStageKeys()));
            stage.setPassTarget(normalizeNullableKey(stageRequest.getPassTarget()));
            stage.setRejectTarget(normalizeNullableKey(stageRequest.getRejectTarget()));
            stage.setReopenTarget(normalizeNullableKey(stageRequest.getReopenTarget()));
            stage.setAutoStart(stageRequest.getAutoStart() != null ? stageRequest.getAutoStart() : (i == 0 ? 1 : 0));
            stage.setReviewRequired(defaultReviewRequired(stageRequest));
            stage.setMaxReviewAttempts(defaultMaxReviewAttempts(stageRequest));
            stages.add(stage);
        }
        workflowStageTemplateMapper.batchInsert(stages);
        return toTemplateVO(template);
    }

    public WorkflowTemplateVO updateTemplate(Long templateId, WorkflowTemplateRequest request) {
        AiWorkflowTemplate template = workflowTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("流程模板不存在: " + templateId);
        }
        if (template.getBuiltIn() != null && template.getBuiltIn() == 1) {
            throw new IllegalStateException("系统内置模板不允许编辑");
        }
        String key = request.getTemplateKey().trim().toLowerCase();
        AiWorkflowTemplate existed = workflowTemplateMapper.selectByKey(key);
        if (existed != null && !Objects.equals(existed.getId(), templateId)) {
            throw new IllegalArgumentException("模板标识已存在: " + key);
        }
        validateStageRequests(request);

        template.setTemplateKey(key);
        template.setName(request.getName().trim());
        template.setDescription(StringUtils.hasText(request.getDescription()) ? request.getDescription().trim() : null);
        template.setLayoutConfig(StringUtils.hasText(request.getLayoutConfig()) ? request.getLayoutConfig().trim() : null);
        template.setActorSlotsConfig(buildActorSlotsConfig(request.getActorSlots()));
        workflowTemplateMapper.update(template);

        workflowStageTemplateMapper.deleteByTemplateId(templateId);
        List<AiWorkflowStageTemplate> stages = new ArrayList<>();
        for (int i = 0; i < request.getStages().size(); i++) {
            WorkflowTemplateRequest.StageRequest stageRequest = request.getStages().get(i);
            AiWorkflowStageTemplate stage = new AiWorkflowStageTemplate();
            stage.setTemplateId(templateId);
            stage.setStageKey(stageRequest.getStageKey().trim().toLowerCase());
            stage.setStageName(stageRequest.getStageName().trim());
            stage.setStageOrder(i + 1);
            stage.setStageType(stageRequest.getStageType().trim().toUpperCase());
            stage.setActorType(stageRequest.getActorType().trim().toUpperCase());
            stage.setActorBindingMode(defaultActorBindingMode(stageRequest));
            stage.setActorSlotKey(StringUtils.hasText(stageRequest.getActorSlotKey()) ? stageRequest.getActorSlotKey().trim().toLowerCase() : null);
            stage.setCompletionRule(StringUtils.hasText(stageRequest.getCompletionRule()) ? stageRequest.getCompletionRule().trim().toUpperCase() : null);
            stage.setSkillKey(StringUtils.hasText(stageRequest.getSkillKey()) ? stageRequest.getSkillKey().trim() : null);
            stage.setSkillPrompt(StringUtils.hasText(stageRequest.getSkillPrompt()) ? stageRequest.getSkillPrompt().trim() : null);
            stage.setSkillFileIds(buildSkillFileIds(stageRequest.getSkillFileIds()));
            stage.setSearchStrategy(normalizeSearchStrategy(stageRequest.getSearchStrategy(), stage.getStageType()));
            stage.setInputStageKeys(buildInputStageKeys(stageRequest.getInputStageKeys()));
            stage.setPassTarget(normalizeNullableKey(stageRequest.getPassTarget()));
            stage.setRejectTarget(normalizeNullableKey(stageRequest.getRejectTarget()));
            stage.setReopenTarget(normalizeNullableKey(stageRequest.getReopenTarget()));
            stage.setAutoStart(stageRequest.getAutoStart() != null ? stageRequest.getAutoStart() : (i == 0 ? 1 : 0));
            stage.setReviewRequired(defaultReviewRequired(stageRequest));
            stage.setMaxReviewAttempts(defaultMaxReviewAttempts(stageRequest));
            stages.add(stage);
        }
        workflowStageTemplateMapper.batchInsert(stages);
        return toTemplateVO(template);
    }

    public void deleteTemplate(Long templateId) {
        AiWorkflowTemplate template = workflowTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("流程模板不存在: " + templateId);
        }
        if (template.getBuiltIn() != null && template.getBuiltIn() == 1) {
            throw new IllegalStateException("系统内置模板不允许删除");
        }
        workflowStageTemplateMapper.deleteByTemplateId(templateId);
        workflowTemplateMapper.deleteById(templateId);
    }

    /**
     * 删除某会议关联的工作流运行与追踪数据（供会议级联删除调用）。
     * 按子→父顺序删除，兼容数据库存在外键约束的情况：
     * tracking_update(引用 tracking_item) → tracking_item(引用 workflow_run/stage_run)
     * → stage_submission(引用 stage_run/workflow_run) → stage_run(引用 workflow_run) → workflow_run。
     */
    public void purgeWorkflowAndTrackingData(Long meetingId) {
        trackingUpdateMapper.deleteByMeetingId(meetingId);
        trackingItemMapper.deleteByMeetingId(meetingId);
        workflowStageSubmissionMapper.deleteByMeetingId(meetingId);
        workflowStageRunMapper.deleteByMeetingId(meetingId);
        workflowRunMapper.deleteByMeetingId(meetingId);
    }

    public boolean isDefaultRoundtableWorkflow(Long meetingId) {
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        return run == null || DEFAULT_TEMPLATE_KEY.equals(run.getTemplateKey());
    }

    public void validateMeetingMembers(String requestedTemplateKey, List<MeetingCreateRequest.MemberRequest> members) {
        AiWorkflowTemplate template = resolveTemplateByRequestedKey(requestedTemplateKey);
        List<AiWorkflowStageTemplate> stages = workflowStageTemplateMapper.selectByTemplateId(template.getId());
        List<WorkflowTemplateVO.ActorSlotVO> actorSlots = parseActorSlots(template.getActorSlotsConfig());
        List<MeetingCreateRequest.MemberRequest> safeMembers = members != null ? members : new ArrayList<>();

        if (!actorSlots.isEmpty()) {
            Map<String, Long> countBySlot = safeMembers.stream()
                    .filter(member -> StringUtils.hasText(member.getActorSlotKey()))
                    .collect(Collectors.groupingBy(member -> member.getActorSlotKey().trim().toLowerCase(), Collectors.counting()));
            for (WorkflowTemplateVO.ActorSlotVO slot : actorSlots) {
                boolean required = slot.getRequired() == null || slot.getRequired() == 1;
                if (!required) {
                    continue;
                }
                long configuredCount = countBySlot.getOrDefault(slot.getSlotKey(), 0L);
                if (configuredCount <= 0) {
                    throw new IllegalArgumentException("流程模板要求执行槽位「" + slot.getSlotName() + "」至少配置一位议员。");
                }
            }
        }

        boolean requiresAiMember = stages.stream().anyMatch(stage -> {
            String actorType = stage.getActorType();
            return !("HUMAN".equalsIgnoreCase(actorType) || "SYSTEM".equalsIgnoreCase(actorType));
        });
        if (requiresAiMember && safeMembers.isEmpty()) {
            throw new IllegalArgumentException("当前流程模板至少需要配置一位 AI 议员。");
        }
    }

    public void activateMeetingWorkflow(Long meetingId) {
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        if (run == null) {
            return;
        }
        workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(), null);
        if (DEFAULT_TEMPLATE_KEY.equals(run.getTemplateKey())) {
            activateDefaultRoundtableDiscussion(run, WorkflowRunStatusEnum.ACTIVE.getCode(), null);
            return;
        }
        activateOrBypassStage(run, run.getCurrentStageKey());
    }

    public List<AiMeetingMember> resolveMembersForCurrentStage(Long meetingId, List<AiMeetingMember> members) {
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        if (run == null || DEFAULT_TEMPLATE_KEY.equals(run.getTemplateKey())) {
            return members;
        }
        AiWorkflowStageRun currentStage = workflowStageRunMapper.selectLatestByMeetingAndStageKey(meetingId, run.getCurrentStageKey());
        if (currentStage == null) {
            return members;
        }
        AiWorkflowStageTemplate stageTemplate = resolveStageTemplate(run, currentStage);
        if (!"BY_SLOT".equalsIgnoreCase(stageTemplate.getActorBindingMode()) || !StringUtils.hasText(stageTemplate.getActorSlotKey())) {
            return members;
        }
        List<AiMeetingMember> filtered = resolveMembersForSlot(members, stageTemplate.getActorSlotKey());
        return filtered.isEmpty() ? members : filtered;
    }

    public void markMeetingConcluded(AiMeetingRoom room, MeetingDetailVO.DecisionArtifactVO artifact) {
        AiWorkflowRun run = ensureWorkflowForMeeting(room, DEFAULT_TEMPLATE_KEY);
        publishRoundtableConclusionArtifacts(room, artifact);
        completeStage(room.getId(), STAGE_DISCUSSION, "CONCLUDED", null);
        completeStage(room.getId(), STAGE_CONCLUSION, "ARCHIVED", null);

        List<AiMeetingTrackingItem> createdItems = createTrackingItemsFromArtifact(room, run, artifact);
        if (!createdItems.isEmpty()) {
            activateStage(room.getId(), STAGE_TRACKING);
            updateRunCurrentStage(run.getId(), STAGE_TRACKING, 3);
            workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.WAITING_TRACKING.getCode(), "等待追踪项完成");
        } else {
            updateRunCurrentStage(run.getId(), STAGE_CONCLUSION, 2);
            workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.CONCLUDED.getCode(), null);
        }
    }

    private void publishRoundtableConclusionArtifacts(AiMeetingRoom room, MeetingDetailVO.DecisionArtifactVO artifact) {
        String finalConclusion = room.getFinalConclusion();
        if (!StringUtils.hasText(finalConclusion)) {
            return;
        }
        AiWorkflowStageRun conclusionStageRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(room.getId(), STAGE_CONCLUSION);
        if (conclusionStageRun != null) {
            String summary = finalConclusion.trim();
            String payload = buildStageArtifactPayload("ARCHIVED", summary);
            workflowStageRunMapper.updateHandoff(conclusionStageRun.getId(), summary, payload);
        }
        AiWorkflowStageRun trackingStageRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(room.getId(), STAGE_TRACKING);
        if (trackingStageRun != null && artifact != null) {
            java.util.List<String> nextActions = artifact.getNextActions() != null ? artifact.getNextActions() : new java.util.ArrayList<>();
            java.util.List<String> openQuestions = artifact.getOpenQuestions() != null ? artifact.getOpenQuestions() : new java.util.ArrayList<>();
            String trackingSummary = !nextActions.isEmpty()
                    ? String.join("；", nextActions)
                    : (!openQuestions.isEmpty() ? String.join("；", openQuestions) : finalConclusion.trim());
            String payload = buildStageArtifactPayload("HANDOFF", trackingSummary);
            workflowStageRunMapper.updateHandoff(trackingStageRun.getId(), trackingSummary, payload);
        }
    }

    public void reopenMeetingWorkflow(Long meetingId) {
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        if (run == null) {
            return;
        }
        if (DEFAULT_TEMPLATE_KEY.equals(run.getTemplateKey())) {
            workflowRunMapper.incrementReopenCount(run.getId());
            activateDefaultRoundtableDiscussion(run, WorkflowRunStatusEnum.REOPENED.getCode(), null);
            return;
        }
        AiWorkflowStageTemplate reopenStage = resolveReopenStageTemplate(run);
        workflowRunMapper.incrementReopenCount(run.getId());
        workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.REOPENED.getCode(), null);
        updateRunCurrentStage(run.getId(), reopenStage.getStageKey(), reopenStage.getStageOrder());
        activateOrBypassStage(run, reopenStage.getStageKey());
    }

    public void submitCurrentStage(Long meetingId, WorkflowStageSubmitRequest request) {
        AiWorkflowRun run = requireWorkflowRun(meetingId);
        if (DEFAULT_TEMPLATE_KEY.equals(run.getTemplateKey())) {
            throw new IllegalStateException("默认圆桌会议不使用手工阶段提交接口");
        }
        AiWorkflowStageRun currentStage = requireCurrentStageRun(meetingId, run.getCurrentStageKey());
        ensureStageOperable(run, currentStage);
        AiWorkflowStageTemplate stageTemplate = resolveStageTemplate(run, currentStage);
        String actorRef = normalizeActorRef(meetingId, stageTemplate, request.getActorRef(), false);
        persistSubmission(currentStage, actorRef, "SUBMIT", request.getContent(), null);
        completeStageRun(currentStage, "SUBMITTED", null);
        moveToNextStageOrConclude(run, currentStage, stageTemplate, request.getContent());
    }

    public void reviewCurrentStage(Long meetingId, WorkflowStageReviewRequest request) {
        AiWorkflowRun run = requireWorkflowRun(meetingId);
        AiWorkflowStageRun currentStage = requireCurrentStageRun(meetingId, run.getCurrentStageKey());
        ensureStageOperable(run, currentStage);
        AiWorkflowStageTemplate stageTemplate = resolveStageTemplate(run, currentStage);
        if (!isReviewStage(stageTemplate)) {
            throw new IllegalStateException("当前阶段不是审核节点");
        }
        if (stageTemplate.getReviewRequired() != null && stageTemplate.getReviewRequired() == 0) {
            throw new IllegalStateException("当前审核节点已配置为可跳过，不需要人工审核");
        }
        String actionType = Boolean.TRUE.equals(request.getApproved()) ? "APPROVE" : "REJECT";
        String content = StringUtils.hasText(request.getComment()) ? request.getComment().trim() : (Boolean.TRUE.equals(request.getApproved()) ? "审核通过" : "审核驳回");
        String actorRef = normalizeActorRef(meetingId, stageTemplate, request.getActorRef(), true);
        persistSubmission(currentStage, actorRef, actionType, content, null);
        if (Boolean.TRUE.equals(request.getApproved())) {
            completeStageRun(currentStage, "APPROVED", null);
            if (StringUtils.hasText(stageTemplate.getPassTarget())) {
                moveToNextStageOrConclude(run, currentStage, stageTemplate, content);
            } else {
                concludeManualWorkflow(run, buildApprovedReviewConclusion(currentStage, content));
            }
            return;
        }
        if (shouldStopAfterReviewReject(currentStage, stageTemplate)) {
            completeStageRun(currentStage, "REJECT_LIMIT", content);
            concludeManualWorkflow(run, buildReviewLimitConclusion(currentStage, stageTemplate, content));
            return;
        }
        rejectStageRun(currentStage, content);
        activateTransitionStage(run, currentStage, stageTemplate.getRejectTarget(), content);
    }

    public void retryCurrentAutoStage(Long meetingId) {
        AiWorkflowRun run = requireWorkflowRun(meetingId);
        AiWorkflowStageRun currentStage = requireCurrentStageRun(meetingId, run.getCurrentStageKey());
        ensureStageOperable(run, currentStage);
        AiWorkflowStageTemplate stageTemplate = resolveStageTemplate(run, currentStage);
        if (!shouldAutoExecuteStage(stageTemplate)) {
            throw new IllegalStateException("当前阶段不是自动执行节点");
        }
        workflowRunMapper.updateStatus(run.getId(), run.getStatus(), null);
        autoExecuteStage(run, currentStage, stageTemplate);
    }

    public MeetingDetailVO.WorkflowSummaryVO buildWorkflowSummary(Long meetingId) {
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        if (run == null) {
            return null;
        }
        AiWorkflowTemplate template = workflowTemplateMapper.selectById(run.getTemplateId());
        List<AiWorkflowStageTemplate> templateStages = workflowStageTemplateMapper.selectByTemplateId(run.getTemplateId());
        List<AiWorkflowStageRun> stageRuns = workflowStageRunMapper.selectByWorkflowRunId(run.getId());
        Map<String, List<MeetingDetailVO.StageSubmissionVO>> submissionMap = buildSubmissionMap(meetingId);

        MeetingDetailVO.WorkflowSummaryVO summary = new MeetingDetailVO.WorkflowSummaryVO();
        summary.setTemplateKey(run.getTemplateKey());
        summary.setTemplateName(template != null ? template.getName() : run.getTemplateKey());
        summary.setLayoutConfig(template != null ? template.getLayoutConfig() : null);
        summary.setRunStatus(run.getStatus());
        WorkflowRunStatusEnum runStatusEnum = WorkflowRunStatusEnum.of(run.getStatus());
        summary.setRunStatusName(runStatusEnum != null ? runStatusEnum.getDesc() : run.getStatus());
        summary.setBlockedReason(run.getBlockedReason());
        summary.setReopenCount(run.getReopenCount());

        List<MeetingDetailVO.StageSummaryVO> histories = new ArrayList<>();
        Map<String, MeetingDetailVO.WorkflowNodeVO> nodeByStageKey = new HashMap<>();
        Map<String, MeetingDetailVO.HandoffArtifactVO> previousHandoffByStageKey = new HashMap<>();
        for (AiWorkflowStageRun stageRun : stageRuns) {
            AiWorkflowStageTemplate templateDef = resolveStageTemplate(run, stageRun);
            MeetingDetailVO.StageSummaryVO vo = new MeetingDetailVO.StageSummaryVO();
            vo.setStageRunId(stageRun.getId());
            vo.setStageKey(stageRun.getStageKey());
            vo.setStageName(stageRun.getStageName());
            vo.setStageOrder(stageRun.getStageOrder());
            vo.setStageType(stageRun.getStageType());
            vo.setActorType(stageRun.getActorType());
            vo.setActorBindingMode(templateDef.getActorBindingMode());
            vo.setActorSlotKey(templateDef.getActorSlotKey());
            vo.setSkillKey(templateDef.getSkillKey());
            vo.setSkillPrompt(templateDef.getSkillPrompt());
            vo.setSkillFiles(resolveSkillFiles(parseSkillFileIds(templateDef.getSkillFileIds())));
            vo.setExecutionGuide(buildExecutionGuide(templateDef));
            vo.setAutoStage(shouldAutoExecuteStage(templateDef));
            vo.setSearchStrategy(resolveSearchStrategy(templateDef));
            vo.setSearchStrategyLabel(resolveSearchStrategyLabel(templateDef));
            vo.setInputStageKeys(parseInputStageKeys(templateDef.getInputStageKeys()));
            vo.setAttemptNo(stageRun.getAttemptNo());
            vo.setStatus(stageRun.getStatus());
            WorkflowStageStatusEnum stageStatusEnum = WorkflowStageStatusEnum.of(stageRun.getStatus());
            vo.setStatusName(stageStatusEnum != null ? stageStatusEnum.getDesc() : stageRun.getStatus());
            vo.setReviewRequired(templateDef.getReviewRequired());
            vo.setMaxReviewAttempts(templateDef.getMaxReviewAttempts());
            vo.setHandoffSummary(stageRun.getHandoffSummary());
            MeetingDetailVO.HandoffArtifactVO currentHandoffArtifact = parseHandoffArtifact(stageRun.getHandoffPayload());
            vo.setHandoffArtifact(currentHandoffArtifact);
            vo.setHandoffDiffFromPrevious(buildHandoffArtifactDiff(previousHandoffByStageKey.get(stageRun.getStageKey()), currentHandoffArtifact));
            vo.setOutcome(stageRun.getOutcome());
            vo.setRejectReason(stageRun.getRejectReason());
            vo.setStartedAt(stageRun.getStartedAt());
            vo.setCompletedAt(stageRun.getCompletedAt());
            vo.setSubmissions(submissionMap.getOrDefault(submissionGroupKey(stageRun.getStageKey(), stageRun.getAttemptNo()), new ArrayList<>()));
            enrichReviewAlignment(vo);
            histories.add(vo);
            MeetingDetailVO.WorkflowNodeVO node = nodeByStageKey.computeIfAbsent(stageRun.getStageKey(), key ->
                    buildWorkflowNode(templateDef, Objects.equals(stageRun.getStageKey(), run.getCurrentStageKey())));
            node.setLatestStatus(stageRun.getStatus());
            node.setLatestStatusName(vo.getStatusName());
            node.setLatestAttemptNo(stageRun.getAttemptNo());
            node.setActiveNode(Objects.equals(stageRun.getStageKey(), run.getCurrentStageKey()));
            node.getAttempts().add(vo);
            if (currentHandoffArtifact != null) {
                previousHandoffByStageKey.put(stageRun.getStageKey(), currentHandoffArtifact);
            }
            if (Objects.equals(stageRun.getStageKey(), run.getCurrentStageKey())) {
                summary.setCurrentStageKey(stageRun.getStageKey());
                summary.setCurrentStageName(stageRun.getStageName());
                summary.setCurrentStageType(stageRun.getStageType());
                summary.setCurrentActorType(stageRun.getActorType());
                summary.setCurrentActorBindingMode(templateDef.getActorBindingMode());
                summary.setCurrentActorSlotKey(templateDef.getActorSlotKey());
                summary.setCurrentActorCandidates(resolveActorCandidates(meetingId, templateDef));
                summary.setCurrentSkillKey(templateDef.getSkillKey());
                summary.setCurrentSkillPrompt(templateDef.getSkillPrompt());
                summary.setCurrentSkillFiles(resolveSkillFiles(parseSkillFileIds(templateDef.getSkillFileIds())));
                summary.setCurrentStageGuide(buildExecutionGuide(templateDef));
                summary.setCurrentStageStatus(stageRun.getStatus());
                summary.setCurrentStageStatusName(vo.getStatusName());
                summary.setCurrentStageReviewRequired(templateDef.getReviewRequired());
                summary.setCurrentStageMaxReviewAttempts(templateDef.getMaxReviewAttempts());
                summary.setCurrentStageAutoExecutable(shouldAutoExecuteStage(templateDef));
                summary.setCurrentStageRetryable(shouldAutoExecuteStage(templateDef)
                        && WorkflowStageStatusEnum.BLOCKED.getCode().equals(stageRun.getStatus()));
            }
        }
        histories.sort(Comparator
                .comparing(MeetingDetailVO.StageSummaryVO::getStageOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MeetingDetailVO.StageSummaryVO::getAttemptNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MeetingDetailVO.StageSummaryVO::getStartedAt, Comparator.nullsLast(Date::compareTo)));
        List<MeetingDetailVO.WorkflowNodeVO> nodes = new ArrayList<>();
        for (AiWorkflowStageTemplate templateStage : templateStages) {
            MeetingDetailVO.WorkflowNodeVO node = nodeByStageKey.get(templateStage.getStageKey());
            if (node == null) {
                node = buildWorkflowNode(templateStage, Objects.equals(templateStage.getStageKey(), run.getCurrentStageKey()));
                node.setLatestStatus(WorkflowStageStatusEnum.PENDING.getCode());
                node.setLatestStatusName(WorkflowStageStatusEnum.PENDING.getDesc());
                node.setLatestAttemptNo(0);
            }
            node.getAttempts().sort(Comparator
                    .comparing(MeetingDetailVO.StageSummaryVO::getAttemptNo, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(MeetingDetailVO.StageSummaryVO::getStartedAt, Comparator.nullsLast(Date::compareTo)));
            applyWorkflowNodeSearchSummary(node, templateStage);
            nodes.add(node);
        }
        nodes.sort(Comparator
                .comparing(MeetingDetailVO.WorkflowNodeVO::getStageOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MeetingDetailVO.WorkflowNodeVO::getStageName, Comparator.nullsLast(String::compareTo)));
        summary.setNodes(nodes);
        MeetingDetailVO.WorkflowNodeVO currentNode = nodeByStageKey.get(run.getCurrentStageKey());
        if (currentNode != null) {
            summary.setCurrentSearchAttempted(currentNode.getSearchAttempted());
            summary.setCurrentSearchStatus(currentNode.getSearchStatus());
            summary.setCurrentSearchProvider(currentNode.getSearchProvider());
            summary.setCurrentSearchQueryCount(currentNode.getSearchQueryCount());
            summary.setCurrentSearchResultCount(currentNode.getSearchResultCount());
            summary.setCurrentSearchNote(currentNode.getSearchNote());
            summary.setCurrentSearchDegraded(currentNode.getSearchDegraded());
            summary.setCurrentSearchDiagnostics(new ArrayList<>(currentNode.getSearchDiagnostics()));
        }
        summary.setStageHistory(histories);
        return summary;
    }

    private MeetingDetailVO.WorkflowNodeVO buildWorkflowNode(AiWorkflowStageTemplate templateDef, boolean activeNode) {
        MeetingDetailVO.WorkflowNodeVO node = new MeetingDetailVO.WorkflowNodeVO();
        node.setStageKey(templateDef.getStageKey());
        node.setStageName(templateDef.getStageName());
        node.setStageOrder(templateDef.getStageOrder());
        node.setStageType(templateDef.getStageType());
        node.setActorType(templateDef.getActorType());
        node.setActorBindingMode(templateDef.getActorBindingMode());
        node.setActorSlotKey(templateDef.getActorSlotKey());
        node.setSkillKey(templateDef.getSkillKey());
        node.setSkillPrompt(templateDef.getSkillPrompt());
        node.setSkillFiles(resolveSkillFiles(parseSkillFileIds(templateDef.getSkillFileIds())));
        node.setExecutionGuide(buildExecutionGuide(templateDef));
        node.setAutoStage(shouldAutoExecuteStage(templateDef));
        node.setReviewRequired(templateDef.getReviewRequired());
        node.setMaxReviewAttempts(templateDef.getMaxReviewAttempts());
        node.setPassTarget(templateDef.getPassTarget());
        node.setRejectTarget(templateDef.getRejectTarget());
        node.setReopenTarget(templateDef.getReopenTarget());
        node.setSearchStrategy(resolveSearchStrategy(templateDef));
        node.setSearchStrategyLabel(resolveSearchStrategyLabel(templateDef));
        node.setInputStageKeys(parseInputStageKeys(templateDef.getInputStageKeys()));
        node.setActiveNode(activeNode);
        return node;
    }

    private void applyWorkflowNodeSearchSummary(MeetingDetailVO.WorkflowNodeVO node, AiWorkflowStageTemplate templateDef) {
        WorkflowNodeSearchSummary summary = summarizeWorkflowNodeSearch(node, templateDef);
        node.setSearchAttempted(summary.attempted);
        node.setSearchStatus(summary.status);
        node.setSearchProvider(summary.provider);
        node.setSearchQueryCount(summary.attempted || summary.queryCount > 0 ? summary.queryCount : null);
        node.setSearchResultCount(summary.attempted || summary.resultCount > 0 ? summary.resultCount : null);
        node.setSearchNote(summary.note);
        node.setSearchDegraded(summary.degraded);
        node.setSearchDiagnostics(summary.diagnostics);
    }

    private WorkflowNodeSearchSummary summarizeWorkflowNodeSearch(MeetingDetailVO.WorkflowNodeVO node, AiWorkflowStageTemplate templateDef) {
        WorkflowNodeSearchSummary summary = new WorkflowNodeSearchSummary();
        String strategy = resolveSearchStrategy(templateDef);
        MeetingDetailVO.StageSummaryVO latestAttempt = node.getAttempts().stream()
                .max(Comparator
                        .comparing(MeetingDetailVO.StageSummaryVO::getAttemptNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(MeetingDetailVO.StageSummaryVO::getStartedAt, Comparator.nullsLast(Date::compareTo)))
                .orElse(null);
        if (latestAttempt == null) {
            if ("REQUIRED".equalsIgnoreCase(strategy)) {
                summary.status = "missing_required_search";
                summary.note = "该节点要求联网搜索，但当前还没有产生搜索记录。";
                summary.degraded = true;
            }
            return summary;
        }

        List<MeetingDetailVO.WorkflowSearchTraceVO> traces = new ArrayList<>();
        for (MeetingDetailVO.StageSubmissionVO submission : latestAttempt.getSubmissions()) {
            if (submission.getSearchTraces() != null && !submission.getSearchTraces().isEmpty()) {
                traces.addAll(submission.getSearchTraces());
            }
        }
        if (traces.isEmpty()) {
            if ("OFF".equalsIgnoreCase(strategy)) {
                summary.note = "该节点未启用联网搜索。";
            } else if ("REQUIRED".equalsIgnoreCase(strategy)) {
                summary.status = "missing_required_search";
                summary.note = "该节点要求联网搜索，但本轮未产生搜索记录。";
                summary.degraded = true;
            } else {
                summary.note = "该节点本轮未执行联网搜索。";
            }
            return summary;
        }

        LinkedHashSet<String> providers = new LinkedHashSet<>();
        LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
        boolean anyAttempted = false;
        boolean anySuccess = false;
        String firstStatus = null;
        String firstNote = null;
        for (MeetingDetailVO.WorkflowSearchTraceVO trace : traces) {
            if (trace == null) {
                continue;
            }
            if (Boolean.TRUE.equals(trace.getAttempted())) {
                anyAttempted = true;
            }
            if (StringUtils.hasText(trace.getProvider())) {
                providers.add(trace.getProvider().trim());
            }
            if (StringUtils.hasText(trace.getStatus()) && firstStatus == null) {
                firstStatus = trace.getStatus().trim();
            }
            if (StringUtils.hasText(trace.getNote()) && firstNote == null) {
                firstNote = trace.getNote().trim();
            }
            if (trace.getProviderDiagnostics() != null) {
                for (String item : trace.getProviderDiagnostics()) {
                    if (StringUtils.hasText(item)) {
                        diagnostics.add(item.trim());
                    }
                }
            }
            summary.queryCount += trace.getQueryCount() != null ? trace.getQueryCount() : 0;
            summary.resultCount += trace.getResultCount() != null ? trace.getResultCount() : 0;
            if ("success".equalsIgnoreCase(trace.getStatus())
                    || ((!StringUtils.hasText(trace.getStatus()) || "no_results".equalsIgnoreCase(trace.getStatus()))
                    && trace.getResultCount() != null && trace.getResultCount() > 0)) {
                anySuccess = true;
            }
        }
        summary.attempted = anyAttempted;
        summary.provider = providers.isEmpty() ? null : String.join(" / ", providers);
        summary.status = anySuccess ? "success" : firstNonBlank(firstStatus, anyAttempted ? "attempted_no_results" : "not_attempted");
        summary.note = firstNote;
        summary.diagnostics.addAll(diagnostics);
        summary.degraded = "REQUIRED".equalsIgnoreCase(strategy) && !anySuccess;
        if (summary.degraded && !StringUtils.hasText(summary.note)) {
            summary.note = "该节点要求联网搜索，但本轮未拿到可用搜索结果。";
        }
        return summary;
    }

    private List<String> resolveActorCandidates(Long meetingId, AiWorkflowStageTemplate stageTemplate) {
        if (!"BY_SLOT".equalsIgnoreCase(stageTemplate.getActorBindingMode()) || !StringUtils.hasText(stageTemplate.getActorSlotKey())) {
            return new ArrayList<>();
        }
        return resolveMembersForSlot(memberMapper.selectByMeetingId(meetingId), stageTemplate.getActorSlotKey()).stream()
                .map(member -> StringUtils.hasText(member.getMemberName()) ? member.getMemberName() : member.getActorSlotName())
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    private String normalizeActorRef(Long meetingId, AiWorkflowStageTemplate stageTemplate, String rawActorRef, boolean reviewStage) {
        if (!"BY_SLOT".equalsIgnoreCase(stageTemplate.getActorBindingMode()) || !StringUtils.hasText(stageTemplate.getActorSlotKey())) {
            return StringUtils.hasText(rawActorRef) ? rawActorRef.trim() : (reviewStage ? "HUMAN_REVIEW" : "HUMAN");
        }
        List<AiMeetingMember> matchedMembers = resolveMembersForSlot(memberMapper.selectByMeetingId(meetingId), stageTemplate.getActorSlotKey());
        if (matchedMembers.isEmpty()) {
            throw new IllegalStateException("当前节点绑定槽位 " + stageTemplate.getActorSlotKey() + "，但会议里没有对应成员");
        }
        if (StringUtils.hasText(rawActorRef)) {
            String actorRef = rawActorRef.trim();
            boolean matched = matchedMembers.stream().anyMatch(member ->
                    actorRef.equalsIgnoreCase(member.getMemberName())
                            || actorRef.equalsIgnoreCase(member.getActorSlotKey())
                            || actorRef.equalsIgnoreCase(member.getActorSlotName()));
            if (!matched) {
                throw new IllegalStateException("当前阶段只允许槽位 " + stageTemplate.getActorSlotKey() + " 的成员操作，可选：" +
                        matchedMembers.stream().map(AiMeetingMember::getMemberName).filter(StringUtils::hasText).collect(Collectors.joining("、")));
            }
            return actorRef;
        }
        if (matchedMembers.size() == 1 && StringUtils.hasText(matchedMembers.get(0).getMemberName())) {
            return matchedMembers.get(0).getMemberName();
        }
        return stageTemplate.getActorSlotKey();
    }

    private List<AiMeetingMember> resolveMembersForSlot(List<AiMeetingMember> members, String actorSlotKey) {
        return members.stream()
                .filter(member -> StringUtils.hasText(member.getActorSlotKey()) && actorSlotKey.equalsIgnoreCase(member.getActorSlotKey()))
                .collect(Collectors.toList());
    }

    private Map<String, List<MeetingDetailVO.StageSubmissionVO>> buildSubmissionMap(Long meetingId) {
        List<AiWorkflowStageSubmission> submissions = workflowStageSubmissionMapper.selectByMeetingId(meetingId);
        Map<String, List<MeetingDetailVO.StageSubmissionVO>> grouped = new HashMap<>();
        Map<String, MeetingDetailVO.StageSubmissionVO> previousByStageKey = new HashMap<>();
        for (AiWorkflowStageSubmission submission : submissions) {
            MeetingDetailVO.StageSubmissionVO vo = new MeetingDetailVO.StageSubmissionVO();
            vo.setId(submission.getId());
            vo.setStageKey(submission.getStageKey());
            vo.setAttemptNo(submission.getAttemptNo());
            vo.setActionType(submission.getActionType());
            vo.setActorRef(submission.getActorRef());
            vo.setContent(submission.getContent());
            vo.setArtifact(parseStageArtifact(submission.getPayloadJson(), submission.getActionType(), submission.getContent()));
            vo.setExecutionSource(parseSubmissionPayloadString(submission.getPayloadJson(), "executionSource"));
            vo.setExecutionSourceLabel(resolveExecutionSourceLabel(vo.getExecutionSource()));
            vo.setMergedOutput(parseSubmissionPayloadBoolean(submission.getPayloadJson(), "mergedOutput"));
            vo.setSourceActorRefs(parseSubmissionPayloadStringList(submission.getPayloadJson(), "sourceActorRefs"));
            vo.setSearchQueries(parseSubmissionPayloadStringList(submission.getPayloadJson(), "searchQueries"));
            vo.setSearchTraces(parseSubmissionSearchTraces(submission.getPayloadJson()));
            vo.setDiffFromPrevious(buildArtifactDiff(previousByStageKey.get(submission.getStageKey()), vo));
            vo.setGmtCreate(submission.getGmtCreate());
            grouped.computeIfAbsent(submissionGroupKey(submission.getStageKey(), submission.getAttemptNo()), k -> new ArrayList<>())
                    .add(vo);
            previousByStageKey.put(submission.getStageKey(), vo);
        }
        grouped.values().forEach(list -> list.sort(Comparator
                .comparing(MeetingDetailVO.StageSubmissionVO::getGmtCreate, Comparator.nullsLast(Date::compareTo))
                .thenComparing(MeetingDetailVO.StageSubmissionVO::getId, Comparator.nullsLast(Long::compareTo))));
        return grouped;
    }

    private String submissionGroupKey(String stageKey, Integer attemptNo) {
        return stageKey + "#" + attemptNo;
    }

    private void applyHandoffSummary(AiWorkflowRun run,
                                      AiWorkflowStageRun sourceStageRun,
                                      AiWorkflowStageTemplate targetTemplate,
                                      String transitionNote) {
        // 关键修正：sourceStageRun 必须由调用方显式传入。
        // 旧实现用 run.getCurrentStageKey() 反查源 stage，但 run 是 in-memory 长寿命对象，
        // 多次 review↔analysis 反复跳转后 currentStageKey 已过期，会指错源 stage，
        // 导致 review 看到的"待审核交付"变成上一轮 review 自己的 REJECT 文本。
        if (!StringUtils.hasText(transitionNote) && sourceStageRun == null) {
            return;
        }
        AiWorkflowStageRun targetRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(run.getMeetingId(), targetTemplate.getStageKey());
        if (targetRun == null) {
            targetRun = createStageRun(run, targetTemplate, 1);
        } else if (!WorkflowStageStatusEnum.PENDING.getCode().equals(targetRun.getStatus())
                && !WorkflowStageStatusEnum.ACTIVE.getCode().equals(targetRun.getStatus())) {
            targetRun = createStageRun(run, targetTemplate, targetRun.getAttemptNo() + 1);
        }
        String handoffSummary = transitionNote != null ? transitionNote.trim() : null;
        if (handoffSummary != null && handoffSummary.length() > 600) {
            handoffSummary = handoffSummary.substring(0, 600) + "…";
        }
        String handoffPayload = buildHandoffPayload(sourceStageRun, handoffSummary);
        workflowStageRunMapper.updateHandoff(targetRun.getId(), handoffSummary, handoffPayload);
    }

    public MeetingDetailVO.TrackingSummaryVO buildTrackingSummary(Long meetingId) {
        List<AiMeetingTrackingItem> items = trackingItemMapper.selectByMeetingId(meetingId);
        MeetingDetailVO.TrackingSummaryVO summary = new MeetingDetailVO.TrackingSummaryVO();
        if (items == null || items.isEmpty()) {
            summary.setResumePolicy(buildResumePolicy(new ArrayList<>()));
            return summary;
        }
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        List<AiWorkflowStageRun> stageRuns = run != null
                ? workflowStageRunMapper.selectByWorkflowRunId(run.getId())
                : new ArrayList<>();
        summary.setTotalCount(items.size());

        List<MeetingDetailVO.TrackingItemVO> itemVOs = new ArrayList<>();
        int openCount = 0;
        int blockedCount = 0;
        int triggerCount = 0;
        int requiredCount = 0;

        for (AiMeetingTrackingItem item : items) {
            MeetingDetailVO.TrackingItemVO vo = new MeetingDetailVO.TrackingItemVO();
            vo.setId(item.getId());
            vo.setCreatorId(item.getCreatorId());
            vo.setMeetingId(item.getMeetingId());
            vo.setSourceType(item.getSourceType());
            vo.setItemType(item.getItemType());
            vo.setTitle(item.getTitle());
            vo.setDescription(item.getDescription());
            vo.setStatus(item.getStatus());
            TrackingItemStatusEnum statusEnum = TrackingItemStatusEnum.of(item.getStatus());
            vo.setStatusName(statusEnum != null ? statusEnum.getDesc() : item.getStatus());
            vo.setOwnerType(item.getOwnerType());
            vo.setOwnerRef(item.getOwnerRef());
            vo.setReopenTrigger(item.getReopenTrigger() != null && item.getReopenTrigger() == 1);
            vo.setRequiredForContinue(item.getRequiredForContinue() != null && item.getRequiredForContinue() == 1);
            vo.setReopenPolicy(item.getReopenPolicy());
            vo.setAutoRefreshEnabled(item.getAutoRefreshEnabled() != null && item.getAutoRefreshEnabled() == 1);
            vo.setRefreshMode(item.getRefreshMode());
            vo.setRefreshIntervalHours(item.getRefreshIntervalHours());
            vo.setWatchQuery(item.getWatchQuery());
            vo.setWatchScope(item.getWatchScope());
            vo.setNextCheckAt(item.getNextCheckAt());
            vo.setLastCheckedAt(item.getLastCheckedAt());
            vo.setLastCheckStatus(item.getLastCheckStatus());
            vo.setLastCheckNote(item.getLastCheckNote());
            vo.setChangeSeverity(item.getChangeSeverity());
            if (item.getStageRunId() != null) {
                AiWorkflowStageRun sourceStageRun = workflowStageRunMapper.selectById(item.getStageRunId());
                if (sourceStageRun != null) {
                    vo.setSourceStageRunId(sourceStageRun.getId());
                    vo.setSourceStageKey(sourceStageRun.getStageKey());
                    vo.setSourceStageName(sourceStageRun.getStageName());
                    vo.setSourceStageOrder(sourceStageRun.getStageOrder());
                    MeetingDetailVO.HandoffArtifactVO artifact = parseHandoffArtifact(sourceStageRun.getHandoffPayload());
                    if (artifact == null) {
                        artifact = buildTrackingSourceArtifact(sourceStageRun, item);
                    }
                    vo.setSourceHandoffArtifact(artifact);
                }
            }
            vo.setDueAt(item.getDueAt());
            vo.setLastUpdateAt(item.getLastUpdateAt());
            vo.setClosedAt(item.getClosedAt());
            List<MeetingDetailVO.TrackingUpdateVO> updates = buildTrackingUpdates(item.getId());
            vo.setUpdates(updates);
            if (!updates.isEmpty()) {
                MeetingDetailVO.TrackingUpdateVO latest = updates.get(0);
                vo.setLatestUpdateSummary(buildTrackingUpdateSummary(latest));
            }
            List<MeetingDetailVO.TrackingUpdateVO> todayUpdates = filterTodayTrackingUpdates(updates);
            vo.setTodayUpdates(todayUpdates);
            vo.setTodayUpdateCount(todayUpdates.size());
            applyTrackingReviewResolution(vo, item, sourceStageRunForItem(item, stageRuns), updates, stageRuns);
            applyConclusionResolution(vo, item, room, updates);
            itemVOs.add(vo);

            if (!TrackingItemStatusEnum.CLOSED.getCode().equals(item.getStatus())
                    && !TrackingItemStatusEnum.VERIFIED.getCode().equals(item.getStatus())) {
                openCount++;
            }
            if (TrackingItemStatusEnum.BLOCKED.getCode().equals(item.getStatus())) {
                blockedCount++;
            }
            if (item.getReopenTrigger() != null && item.getReopenTrigger() == 1) {
                triggerCount++;
            }
            if (item.getRequiredForContinue() != null && item.getRequiredForContinue() == 1) {
                requiredCount++;
            }
        }

        summary.setOpenCount(openCount);
        summary.setBlockedCount(blockedCount);
        summary.setTriggerCount(triggerCount);
        summary.setRequiredCount(requiredCount);
        summary.setResumePolicy(buildResumePolicy(items));
        summary.setItems(itemVOs);
        return summary;
    }

    private AiWorkflowStageRun sourceStageRunForItem(AiMeetingTrackingItem item, List<AiWorkflowStageRun> stageRuns) {
        if (item == null || item.getStageRunId() == null || stageRuns == null) {
            return null;
        }
        return stageRuns.stream()
                .filter(run -> item.getStageRunId().equals(run.getId()))
                .findFirst()
                .orElse(null);
    }

    private MeetingDetailVO.ResumePolicyVO buildResumePolicy(List<AiMeetingTrackingItem> items) {
        MeetingDetailVO.ResumePolicyVO policy = new MeetingDetailVO.ResumePolicyVO();
        List<String> pendingRequiredItems = new ArrayList<>();
        List<String> readyTriggerItems = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            policy.setAllRequiredReady(false);
            policy.setAnyTriggerReady(false);
            policy.setCanResumeMeeting(false);
            policy.setPendingRequiredItems(pendingRequiredItems);
            policy.setReadyTriggerItems(readyTriggerItems);
            policy.setEvaluationNote("当前会议暂无追踪项，无法触发继续重开。");
            return policy;
        }
        boolean hasRequiredItem = false;

        for (AiMeetingTrackingItem item : items) {
            boolean done = isTrackingDone(item.getStatus());
            if (item.getRequiredForContinue() != null && item.getRequiredForContinue() == 1) {
                hasRequiredItem = true;
                if (!done) {
                    pendingRequiredItems.add(item.getTitle());
                }
            }
            if (item.getReopenTrigger() != null && item.getReopenTrigger() == 1 && done) {
                readyTriggerItems.add(item.getTitle());
            }
        }

        boolean allRequiredReady = !hasRequiredItem || pendingRequiredItems.isEmpty();
        boolean anyTriggerReady = !readyTriggerItems.isEmpty();
        boolean canResumeMeeting = allRequiredReady && anyTriggerReady;

        policy.setAllRequiredReady(allRequiredReady);
        policy.setAnyTriggerReady(anyTriggerReady);
        policy.setCanResumeMeeting(canResumeMeeting);
        policy.setPendingRequiredItems(pendingRequiredItems);
        policy.setReadyTriggerItems(readyTriggerItems);
        policy.setEvaluationNote(buildResumePolicyNote(allRequiredReady, anyTriggerReady, pendingRequiredItems, readyTriggerItems));
        return policy;
    }

    private List<MeetingDetailVO.TrackingUpdateVO> filterTodayTrackingUpdates(List<MeetingDetailVO.TrackingUpdateVO> updates) {
        List<MeetingDetailVO.TrackingUpdateVO> result = new ArrayList<>();
        if (updates == null || updates.isEmpty()) {
            return result;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.ZoneId zoneId = java.time.ZoneId.systemDefault();
        for (MeetingDetailVO.TrackingUpdateVO update : updates) {
            if (update == null || update.getGmtCreate() == null) {
                continue;
            }
            java.time.LocalDate updateDate = update.getGmtCreate().toInstant().atZone(zoneId).toLocalDate();
            if (today.equals(updateDate)) {
                result.add(update);
            }
        }
        return result;
    }

    private boolean isTrackingDone(String status) {
        return TrackingItemStatusEnum.VERIFIED.getCode().equals(status)
                || TrackingItemStatusEnum.CLOSED.getCode().equals(status);
    }

    private String buildResumePolicyNote(boolean allRequiredReady,
                                         boolean anyTriggerReady,
                                         List<String> pendingRequiredItems,
                                         List<String> readyTriggerItems) {
        if (allRequiredReady && anyTriggerReady) {
            return "所有必需追踪项已完成，且至少一条触发项已就绪，会议可继续重开。";
        }
        if (!allRequiredReady && anyTriggerReady) {
            return "已有触发项完成，但仍有必需追踪项未完成，当前不能继续会议。";
        }
        if (allRequiredReady) {
            return "必需追踪项已满足，但还没有任何触发重开的追踪项完成。";
        }
        if (!pendingRequiredItems.isEmpty()) {
            return "仍有必需追踪项待完成：" + String.join("、", pendingRequiredItems);
        }
        if (!readyTriggerItems.isEmpty()) {
            return "已有可用触发项：" + String.join("、", readyTriggerItems);
        }
        return "当前还不满足继续会议的条件。";
    }

    private List<String> toStringList(com.alibaba.fastjson.JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (Object item : array) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                list.add(text);
            }
        }
        return list;
    }

    private List<MeetingDetailVO.TrackingUpdateVO> buildTrackingUpdates(Long trackingItemId) {
        List<AiMeetingTrackingUpdate> updates = trackingUpdateMapper.selectByTrackingItemId(trackingItemId);
        List<MeetingDetailVO.TrackingUpdateVO> result = new ArrayList<>();
        for (AiMeetingTrackingUpdate update : updates) {
            MeetingDetailVO.TrackingUpdateVO vo = new MeetingDetailVO.TrackingUpdateVO();
            vo.setId(update.getId());
            vo.setUpdateType(update.getUpdateType());
            vo.setStatusBefore(update.getStatusBefore());
            vo.setStatusAfter(update.getStatusAfter());
            vo.setContent(update.getContent());
            vo.setEvidenceRef(update.getEvidenceRef());
            vo.setGmtCreate(update.getGmtCreate());
            result.add(vo);
        }
        return result;
    }

    private String buildTrackingUpdateSummary(MeetingDetailVO.TrackingUpdateVO update) {
        if (update == null) {
            return null;
        }
        if (StringUtils.hasText(update.getStatusAfter())) {
            TrackingItemStatusEnum statusEnum = TrackingItemStatusEnum.of(update.getStatusAfter());
            String statusLabel = statusEnum != null ? statusEnum.getDesc() : update.getStatusAfter();
            if (StringUtils.hasText(update.getContent())) {
                return "最新状态：" + statusLabel + "；" + update.getContent();
            }
            return "最新状态：" + statusLabel;
        }
        return update.getContent();
    }

    private void applyTrackingReviewResolution(MeetingDetailVO.TrackingItemVO vo,
                                               AiMeetingTrackingItem item,
                                               AiWorkflowStageRun sourceStageRun,
                                               List<MeetingDetailVO.TrackingUpdateVO> updates,
                                               List<AiWorkflowStageRun> stageRuns) {
        String reviewFeedback = resolveTrackingReviewFeedback(item, sourceStageRun, stageRuns);
        if (!StringUtils.hasText(reviewFeedback)) {
            return;
        }
        List<String> reviewPoints = extractReviewPoints(reviewFeedback);
        if (reviewPoints.isEmpty()) {
            return;
        }
        String trackingText = buildTrackingResolutionText(vo, item, updates);
        List<String> matched = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String point : reviewPoints) {
            if (matchesReviewPoint(point, trackingText)) {
                matched.add(point);
            } else {
                unresolved.add(point);
            }
        }
        vo.setMatchedReviewPoints(matched);
        vo.setUnresolvedReviewPoints(unresolved);
        if (unresolved.isEmpty()) {
            vo.setReviewResolutionNote("该追踪项的当前内容已覆盖相关审核意见。");
        } else if (!matched.isEmpty()) {
            vo.setReviewResolutionNote("该追踪项已部分消解审核意见，但仍有未覆盖的审核点。");
        } else {
            vo.setReviewResolutionNote("该追踪项尚未体现对相关审核意见的明确消解。");
        }
    }

    private String resolveTrackingReviewFeedback(AiMeetingTrackingItem item,
                                                 AiWorkflowStageRun sourceStageRun,
                                                 List<AiWorkflowStageRun> stageRuns) {
        String directFeedback = extractStageReviewFeedback(sourceStageRun);
        if (StringUtils.hasText(directFeedback)) {
            return directFeedback;
        }
        if (stageRuns == null || stageRuns.isEmpty()) {
            return null;
        }
        Date itemTime = item != null ? item.getGmtCreate() : null;
        AiWorkflowStageRun candidate = null;
        for (AiWorkflowStageRun stageRun : stageRuns) {
            String feedback = extractStageReviewFeedback(stageRun);
            if (!StringUtils.hasText(feedback)) {
                continue;
            }
            if (itemTime != null && stageRun.getCompletedAt() != null && stageRun.getCompletedAt().after(itemTime)) {
                continue;
            }
            candidate = stageRun;
        }
        return extractStageReviewFeedback(candidate);
    }

    private String extractStageReviewFeedback(AiWorkflowStageRun stageRun) {
        if (stageRun == null) {
            return null;
        }
        MeetingDetailVO.HandoffArtifactVO artifact = parseHandoffArtifact(stageRun.getHandoffPayload());
        if (artifact != null && "REJECT".equalsIgnoreCase(artifact.getActionType()) && StringUtils.hasText(artifact.getContent())) {
            return artifact.getContent();
        }
        if (StringUtils.hasText(stageRun.getRejectReason())) {
            return stageRun.getRejectReason();
        }
        return null;
    }

    private String buildTrackingResolutionText(MeetingDetailVO.TrackingItemVO vo,
                                               AiMeetingTrackingItem item,
                                               List<MeetingDetailVO.TrackingUpdateVO> updates) {
        StringBuilder sb = new StringBuilder();
        if (item != null) {
            appendText(sb, item.getTitle());
            appendText(sb, item.getDescription());
        }
        if (vo != null) {
            appendText(sb, vo.getLatestUpdateSummary());
            if (vo.getSourceHandoffArtifact() != null) {
                appendText(sb, vo.getSourceHandoffArtifact().getContent());
            }
        }
        if (updates != null) {
            for (MeetingDetailVO.TrackingUpdateVO update : updates) {
                appendText(sb, update.getContent());
                appendText(sb, update.getEvidenceRef());
            }
        }
        return sb.toString().toLowerCase();
    }

    private void applyConclusionResolution(MeetingDetailVO.TrackingItemVO vo,
                                           AiMeetingTrackingItem item,
                                           AiMeetingRoom room,
                                           List<MeetingDetailVO.TrackingUpdateVO> updates) {
        if (vo == null || item == null || room == null || !isTrackingDone(item.getStatus())) {
            return;
        }
        List<String> conclusionPoints = extractConclusionPoints(room);
        MeetingDetailVO.TrackingUpdateVO reopenUpdate = latestReopenUpdate(updates);
        String trackingText = buildTrackingConclusionText(vo, item);
        List<String> matched = new ArrayList<>();
        for (String point : conclusionPoints) {
            if (matchesReviewPoint(point, trackingText) || matchesReviewPoint(item.getTitle(), point.toLowerCase())) {
                matched.add(point);
            }
        }
        vo.setMatchedConclusionPoints(matched);
        if (!matched.isEmpty()) {
            vo.setResolvedByLatestConclusion(true);
            vo.setConclusionResolutionNote("该追踪项已在最新结论中得到吸收或明确回应。");
        } else if (reopenUpdate != null
                && room.getConclusionTime() != null
                && reopenUpdate.getGmtCreate() != null
                && !room.getConclusionTime().before(reopenUpdate.getGmtCreate())) {
            vo.setResolvedByLatestConclusion(true);
            vo.setConclusionResolutionNote("该追踪项触发了会议重开，并在随后形成的新结论中完成闭环。");
        } else if (TrackingItemStatusEnum.VERIFIED.getCode().equals(item.getStatus())
                || TrackingItemStatusEnum.CLOSED.getCode().equals(item.getStatus())) {
            vo.setResolvedByLatestConclusion(false);
            vo.setConclusionResolutionNote("该追踪项已关闭，但最新结论里未检出明显对应表述。");
        }
    }

    private MeetingDetailVO.TrackingUpdateVO latestReopenUpdate(List<MeetingDetailVO.TrackingUpdateVO> updates) {
        if (updates == null || updates.isEmpty()) {
            return null;
        }
        for (MeetingDetailVO.TrackingUpdateVO update : updates) {
            if (update != null && "TRIGGER_REOPEN".equalsIgnoreCase(update.getUpdateType())) {
                return update;
            }
        }
        return null;
    }

    private List<String> extractConclusionPoints(AiMeetingRoom room) {
        List<String> points = new ArrayList<>();
        if (room == null) {
            return points;
        }
        appendConclusionPoint(points, room.getFinalConclusion());
        if (StringUtils.hasText(room.getDecisionArtifact())) {
            try {
                com.alibaba.fastjson.JSONObject artifact = com.alibaba.fastjson.JSON.parseObject(room.getDecisionArtifact());
                appendConclusionPoint(points, artifact.getString("finalDecision"));
                appendConclusionPoints(points, artifact.getJSONArray("decisionBasis"));
                appendConclusionPoints(points, artifact.getJSONArray("keyViewpoints"));
                appendConclusionPoints(points, artifact.getJSONArray("nextActions"));
            } catch (Exception ignored) {
            }
        }
        return points;
    }

    private void appendConclusionPoints(List<String> points, com.alibaba.fastjson.JSONArray array) {
        if (array == null) {
            return;
        }
        for (Object item : array) {
            appendConclusionPoint(points, item != null ? String.valueOf(item) : null);
        }
    }

    private void appendConclusionPoint(List<String> points, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String normalized = text.trim();
        if (!points.contains(normalized)) {
            points.add(normalized);
        }
    }

    private String buildTrackingConclusionText(MeetingDetailVO.TrackingItemVO vo, AiMeetingTrackingItem item) {
        StringBuilder sb = new StringBuilder();
        appendText(sb, item.getTitle());
        appendText(sb, item.getDescription());
        appendText(sb, vo.getLatestUpdateSummary());
        appendText(sb, vo.getConclusionResolutionNote());
        return sb.toString().toLowerCase();
    }

    private void appendText(StringBuilder sb, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(text.trim());
    }

    private MeetingDetailVO.HandoffArtifactVO buildTrackingSourceArtifact(AiWorkflowStageRun sourceStageRun,
                                                                          AiMeetingTrackingItem item) {
        MeetingDetailVO.HandoffArtifactVO vo = new MeetingDetailVO.HandoffArtifactVO();
        vo.setSourceStageRunId(sourceStageRun.getId());
        vo.setSourceStageKey(sourceStageRun.getStageKey());
        vo.setSourceStageName(sourceStageRun.getStageName());
        vo.setSourceAttemptNo(sourceStageRun.getAttemptNo());
        vo.setActionType("GENERATED");
        vo.setActorRef("SYSTEM");
        vo.setContent(StringUtils.hasText(item.getDescription()) ? item.getDescription() + "：" + item.getTitle() : item.getTitle());
        vo.setCreatedAt(item.getGmtCreate());
        return vo;
    }

    public TrackingProgressResult updateTrackingItem(Long trackingItemId,
                                                     String targetStatus,
                                                     String note,
                                                     String evidenceRef) {
        AiMeetingTrackingItem item = trackingItemMapper.selectById(trackingItemId);
        if (item == null) {
            throw new IllegalArgumentException("追踪项不存在: " + trackingItemId);
        }
        TrackingItemStatusEnum statusEnum = TrackingItemStatusEnum.of(targetStatus);
        if (statusEnum == null) {
            throw new IllegalArgumentException("不支持的追踪项状态: " + targetStatus);
        }

        String before = item.getStatus();
        Date now = new Date();
        Date closedAt = (statusEnum == TrackingItemStatusEnum.CLOSED || statusEnum == TrackingItemStatusEnum.VERIFIED)
                ? now : null;
        trackingItemMapper.updateStatus(item.getId(), statusEnum.getCode(), now, closedAt);

        AiMeetingTrackingUpdate update = new AiMeetingTrackingUpdate();
        update.setTrackingItemId(item.getId());
        update.setMeetingId(item.getMeetingId());
        update.setUpdateType("STATUS_CHANGE");
        update.setStatusBefore(before);
        update.setStatusAfter(statusEnum.getCode());
        update.setContent(StringUtils.hasText(note) ? note.trim() : "状态更新为" + statusEnum.getDesc());
        update.setEvidenceRef(StringUtils.hasText(evidenceRef) ? evidenceRef.trim() : null);
        trackingUpdateMapper.insert(update);

        boolean shouldResume = false;
        String resumeReason = null;
        if (item.getMeetingId() != null
                && (statusEnum == TrackingItemStatusEnum.VERIFIED || statusEnum == TrackingItemStatusEnum.CLOSED)) {
            List<AiMeetingTrackingItem> items = trackingItemMapper.selectByMeetingId(item.getMeetingId());
            boolean allRequiredReady = items.stream()
                    .filter(it -> it.getRequiredForContinue() != null && it.getRequiredForContinue() == 1)
                    .allMatch(it -> {
                        String status = Objects.equals(it.getId(), item.getId()) ? statusEnum.getCode() : it.getStatus();
                        return TrackingItemStatusEnum.VERIFIED.getCode().equals(status)
                                || TrackingItemStatusEnum.CLOSED.getCode().equals(status);
                    });
            if (allRequiredReady && hasResumeTrigger(items, item.getId(), statusEnum.getCode())) {
                shouldResume = true;
                resumeReason = "关键追踪项已完成，可继续会议";
            }
        }
        return new TrackingProgressResult(
                item.getMeetingId(),
                item.getId(),
                item.getTitle(),
                statusEnum.getCode(),
                shouldResume,
                resumeReason,
                update.getContent(),
                update.getEvidenceRef()
        );
    }

    public void appendTrackingReopenUpdate(Long trackingItemId,
                                           Long meetingId,
                                           Long opinionId,
                                           String content,
                                           String evidenceRef) {
        AiMeetingTrackingUpdate update = new AiMeetingTrackingUpdate();
        update.setTrackingItemId(trackingItemId);
        update.setMeetingId(meetingId);
        update.setUpdateType("TRIGGER_REOPEN");
        update.setStatusBefore(null);
        update.setStatusAfter(null);
        update.setContent(content);
        String opinionRef = opinionId != null ? "opinion:" + opinionId : null;
        if (StringUtils.hasText(opinionRef) && StringUtils.hasText(evidenceRef)) {
            update.setEvidenceRef(opinionRef + " | " + evidenceRef.trim());
        } else if (StringUtils.hasText(opinionRef)) {
            update.setEvidenceRef(opinionRef);
        } else {
            update.setEvidenceRef(StringUtils.hasText(evidenceRef) ? evidenceRef.trim() : null);
        }
        trackingUpdateMapper.insert(update);
    }

    public void markWorkflowWaitingTracking(Long meetingId, String blockedReason) {
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        if (run == null) {
            return;
        }
        workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.WAITING_TRACKING.getCode(), blockedReason);
        updateRunCurrentStage(run.getId(), STAGE_TRACKING, 3);
        activateStage(meetingId, STAGE_TRACKING);
    }

    public void markWorkflowActiveDiscussion(Long meetingId, String blockedReason) {
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        if (run == null) {
            return;
        }
        if (DEFAULT_TEMPLATE_KEY.equals(run.getTemplateKey())) {
            activateDefaultRoundtableDiscussion(run, WorkflowRunStatusEnum.REOPENED.getCode(), blockedReason);
            return;
        }
        AiWorkflowStageTemplate reopenStage = resolveReopenStageTemplate(run);
        workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.REOPENED.getCode(), blockedReason);
        updateRunCurrentStage(run.getId(), reopenStage.getStageKey(), reopenStage.getStageOrder());
        activateOrBypassStage(run, reopenStage.getStageKey());
    }

    private void activateDefaultRoundtableDiscussion(AiWorkflowRun run, String runStatus, String blockedReason) {
        if (run == null) {
            return;
        }
        updateRunCurrentStage(run.getId(), STAGE_DISCUSSION, 1);
        workflowRunMapper.updateStatus(run.getId(), runStatus, blockedReason);
        activateStage(run.getMeetingId(), STAGE_DISCUSSION);
    }

    private AiWorkflowTemplate resolveTemplate(String requestedTemplateKey) {
        ensureDefaultTemplateExists();
        String key = normalizeTemplateKey(requestedTemplateKey);
        AiWorkflowTemplate template = workflowTemplateMapper.selectByKey(key);
        if (template != null) {
            return template;
        }
        if (StringUtils.hasText(requestedTemplateKey)) {
            throw new IllegalArgumentException("流程模板不存在: " + requestedTemplateKey);
        }
        AiWorkflowTemplate fallback = workflowTemplateMapper.selectByKey(DEFAULT_TEMPLATE_KEY);
        if (fallback == null) {
            throw new IllegalStateException("默认工作流模板不存在");
        }
        return fallback;
    }

    private WorkflowTemplateVO toTemplateVO(AiWorkflowTemplate template) {
        WorkflowTemplateVO vo = new WorkflowTemplateVO();
        vo.setId(template.getId());
        vo.setTemplateKey(template.getTemplateKey());
        vo.setName(template.getName());
        vo.setDescription(template.getDescription());
        vo.setLayoutConfig(template.getLayoutConfig());
        vo.setActorSlots(parseActorSlots(template.getActorSlotsConfig()));
        vo.setBuiltIn(template.getBuiltIn());
        vo.setActive(template.getActive());
        vo.setGmtCreate(template.getGmtCreate());
        vo.setGmtModify(template.getGmtModify());
        List<AiWorkflowStageTemplate> stages = workflowStageTemplateMapper.selectByTemplateId(template.getId());
        List<WorkflowTemplateVO.StageVO> stageVOs = new ArrayList<>();
        for (AiWorkflowStageTemplate stage : stages) {
            WorkflowTemplateVO.StageVO stageVO = new WorkflowTemplateVO.StageVO();
            stageVO.setId(stage.getId());
            stageVO.setStageKey(stage.getStageKey());
            stageVO.setStageName(stage.getStageName());
            stageVO.setStageOrder(stage.getStageOrder());
            stageVO.setStageType(stage.getStageType());
            stageVO.setActorType(stage.getActorType());
            stageVO.setActorBindingMode(stage.getActorBindingMode());
            stageVO.setActorSlotKey(stage.getActorSlotKey());
            stageVO.setCompletionRule(stage.getCompletionRule());
            stageVO.setSkillKey(stage.getSkillKey());
            stageVO.setSkillPrompt(stage.getSkillPrompt());
            List<Long> skillFileIds = parseSkillFileIds(stage.getSkillFileIds());
            stageVO.setSkillFileIds(skillFileIds);
            stageVO.setSkillFiles(resolveSkillFiles(skillFileIds));
            stageVO.setSearchStrategy(resolveSearchStrategy(stage));
            stageVO.setSearchStrategyLabel(resolveSearchStrategyLabel(stage));
            stageVO.setInputStageKeys(parseInputStageKeys(stage.getInputStageKeys()));
            stageVO.setPassTarget(stage.getPassTarget());
            stageVO.setRejectTarget(stage.getRejectTarget());
            stageVO.setReopenTarget(stage.getReopenTarget());
            stageVO.setAutoStart(stage.getAutoStart());
            stageVO.setReviewRequired(stage.getReviewRequired());
            stageVO.setMaxReviewAttempts(stage.getMaxReviewAttempts());
            stageVOs.add(stageVO);
        }
        vo.setStages(stageVOs);
        return vo;
    }

    private void validateStageRequests(WorkflowTemplateRequest request) {
        if (request.getStages() == null || request.getStages().isEmpty()) {
            throw new IllegalArgumentException("至少需要一个阶段");
        }
        Set<String> keys = new HashSet<>();
        Set<String> knownStageKeys = new HashSet<>();
        Set<String> slotKeys = normalizeActorSlotKeys(request.getActorSlots());
        for (WorkflowTemplateRequest.StageRequest stage : request.getStages()) {
            String key = stage.getStageKey().trim().toLowerCase();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("阶段标识重复: " + key);
            }
            knownStageKeys.add(key);
        }
        for (int i = 0; i < request.getStages().size(); i++) {
            WorkflowTemplateRequest.StageRequest stage = request.getStages().get(i);
            if (i == 0 && stage.getAutoStart() != null && stage.getAutoStart() != 1) {
                throw new IllegalArgumentException("首阶段必须为自动启动");
            }
            validateTarget(knownStageKeys, stage.getPassTarget(), "passTarget");
            validateTarget(knownStageKeys, stage.getRejectTarget(), "rejectTarget");
            validateTarget(knownStageKeys, stage.getReopenTarget(), "reopenTarget");
            validateActorBinding(stage, slotKeys);
            validateSkillFileIds(stage.getSkillFileIds(), stage.getStageKey());
            validateMaxReviewAttempts(stage);
            validateSearchStrategy(stage);
            validateAllReadyInputs(stage, i, request.getStages());
        }
    }

    private void validateSearchStrategy(WorkflowTemplateRequest.StageRequest stage) {
        if (!StringUtils.hasText(stage.getSearchStrategy())) {
            return;
        }
        String normalized = stage.getSearchStrategy().trim().toUpperCase();
        if (!Arrays.asList("OFF", "OPTIONAL", "REQUIRED").contains(normalized)) {
            throw new IllegalArgumentException("节点 " + stage.getStageKey() + " 的 searchStrategy 仅支持 OFF / OPTIONAL / REQUIRED");
        }
    }

    private void validateAllReadyInputs(WorkflowTemplateRequest.StageRequest stage,
                                        int stageIndex,
                                        List<WorkflowTemplateRequest.StageRequest> stages) {
        List<String> inputStageKeys = normalizeInputStageKeys(stage.getInputStageKeys());
        boolean allReady = "ALL_READY".equalsIgnoreCase(StringUtils.hasText(stage.getCompletionRule()) ? stage.getCompletionRule().trim() : "");
        if (inputStageKeys.isEmpty()) {
            if (allReady) {
                throw new IllegalArgumentException("节点 " + stage.getStageKey() + " 使用 ALL_READY 时必须配置输入节点");
            }
            return;
        }
        if (!allReady) {
            throw new IllegalArgumentException("节点 " + stage.getStageKey() + " 配置了输入节点，completionRule 必须设为 ALL_READY");
        }
        Map<String, Integer> stageIndexByKey = new HashMap<>();
        for (int i = 0; i < stages.size(); i++) {
            stageIndexByKey.put(stages.get(i).getStageKey().trim().toLowerCase(), i);
        }
        String currentKey = stage.getStageKey().trim().toLowerCase();
        for (String inputKey : inputStageKeys) {
            Integer inputIndex = stageIndexByKey.get(inputKey);
            if (inputIndex == null) {
                throw new IllegalArgumentException("节点 " + currentKey + " 的 ALL_READY 输入不存在: " + inputKey);
            }
            if (Objects.equals(inputKey, currentKey)) {
                throw new IllegalArgumentException("节点 " + currentKey + " 不能把自己作为 ALL_READY 输入");
            }
            if (inputIndex >= stageIndex) {
                throw new IllegalArgumentException("节点 " + currentKey + " 的 ALL_READY 输入必须排在当前节点之前: " + inputKey);
            }
        }
    }

    private void validateMaxReviewAttempts(WorkflowTemplateRequest.StageRequest stage) {
        if (stage.getMaxReviewAttempts() == null) {
            return;
        }
        if (stage.getMaxReviewAttempts() < 0) {
            throw new IllegalArgumentException("节点 " + stage.getStageKey() + " 的 maxReviewAttempts 不能小于 0");
        }
        if (!isReviewStageType(stage.getStageType()) && stage.getMaxReviewAttempts() > 0) {
            throw new IllegalArgumentException("只有审核节点才能配置 maxReviewAttempts");
        }
    }

    private void validateSkillFileIds(List<Long> skillFileIds, String stageKey) {
        if (skillFileIds == null || skillFileIds.isEmpty()) {
            return;
        }
        Set<Long> existingIds = skillFileMapper.selectByIds(skillFileIds).stream()
                .map(AiSkillFile::getId)
                .collect(Collectors.toSet());
        for (Long id : skillFileIds) {
            if (id == null || !existingIds.contains(id)) {
                throw new IllegalArgumentException("节点 " + stageKey + " 引用了不存在的 skill 文件: " + id);
            }
        }
    }

    private Set<String> normalizeActorSlotKeys(List<WorkflowTemplateRequest.ActorSlotRequest> actorSlots) {
        Set<String> slotKeys = new HashSet<>();
        if (actorSlots == null) {
            return slotKeys;
        }
        for (WorkflowTemplateRequest.ActorSlotRequest actorSlot : actorSlots) {
            String key = actorSlot.getSlotKey().trim().toLowerCase();
            if (!slotKeys.add(key)) {
                throw new IllegalArgumentException("执行槽位标识重复: " + key);
            }
        }
        return slotKeys;
    }

    private void validateActorBinding(WorkflowTemplateRequest.StageRequest stage, Set<String> slotKeys) {
        String bindingMode = defaultActorBindingMode(stage);
        if ("BY_SLOT".equals(bindingMode)) {
            if (!StringUtils.hasText(stage.getActorSlotKey())) {
                throw new IllegalArgumentException("节点 " + stage.getStageKey() + " 选择了指定槽位，但未配置 actorSlotKey");
            }
            String slotKey = stage.getActorSlotKey().trim().toLowerCase();
            if (!slotKeys.contains(slotKey)) {
                throw new IllegalArgumentException("节点 " + stage.getStageKey() + " 引用了不存在的执行槽位: " + slotKey);
            }
        }
    }

    private String defaultActorBindingMode(WorkflowTemplateRequest.StageRequest stageRequest) {
        if (StringUtils.hasText(stageRequest.getActorBindingMode())) {
            return stageRequest.getActorBindingMode().trim().toUpperCase();
        }
        return "BY_SLOT".equalsIgnoreCase(stageRequest.getActorType()) ? "BY_SLOT" : "ALL_MEMBERS";
    }

    private String buildActorSlotsConfig(List<WorkflowTemplateRequest.ActorSlotRequest> actorSlots) {
        if (actorSlots == null || actorSlots.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (WorkflowTemplateRequest.ActorSlotRequest actorSlot : actorSlots) {
            Map<String, Object> item = new HashMap<>();
            item.put("slotKey", actorSlot.getSlotKey().trim().toLowerCase());
            item.put("slotName", actorSlot.getSlotName().trim());
            item.put("description", StringUtils.hasText(actorSlot.getDescription()) ? actorSlot.getDescription().trim() : null);
            item.put("defaultModelType", StringUtils.hasText(actorSlot.getDefaultModelType()) ? actorSlot.getDefaultModelType().trim() : null);
            item.put("defaultPersona", StringUtils.hasText(actorSlot.getDefaultPersona()) ? actorSlot.getDefaultPersona().trim() : null);
            item.put("required", actorSlot.getRequired() != null ? actorSlot.getRequired() : 1);
            item.put("allowMultiple", actorSlot.getAllowMultiple() != null ? actorSlot.getAllowMultiple() : 0);
            item.put("defaultMembers", buildDefaultMembersConfig(actorSlot.getDefaultMembers()));
            normalized.add(item);
        }
        return com.alibaba.fastjson.JSON.toJSONString(normalized);
    }

    private List<Map<String, Object>> buildDefaultMembersConfig(List<WorkflowTemplateRequest.DefaultMemberRequest> defaultMembers) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (defaultMembers == null) {
            return normalized;
        }
        for (WorkflowTemplateRequest.DefaultMemberRequest dm : defaultMembers) {
            if (dm == null) {
                continue;
            }
            boolean blank = !StringUtils.hasText(dm.getMemberName())
                    && !StringUtils.hasText(dm.getModelType())
                    && dm.getMemberTemplateId() == null;
            if (blank) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("memberTemplateId", dm.getMemberTemplateId());
            item.put("memberName", StringUtils.hasText(dm.getMemberName()) ? dm.getMemberName().trim() : null);
            item.put("modelType", StringUtils.hasText(dm.getModelType()) ? dm.getModelType().trim() : null);
            item.put("persona", StringUtils.hasText(dm.getPersona()) ? dm.getPersona().trim() : null);
            normalized.add(item);
        }
        return normalized;
    }

    private List<WorkflowTemplateVO.ActorSlotVO> parseActorSlots(String actorSlotsConfig) {
        List<WorkflowTemplateVO.ActorSlotVO> actorSlots = new ArrayList<>();
        if (!StringUtils.hasText(actorSlotsConfig)) {
            return actorSlots;
        }
        try {
            List<com.alibaba.fastjson.JSONObject> parsed = com.alibaba.fastjson.JSON.parseArray(actorSlotsConfig, com.alibaba.fastjson.JSONObject.class);
            if (parsed == null) {
                return actorSlots;
            }
            for (com.alibaba.fastjson.JSONObject item : parsed) {
                WorkflowTemplateVO.ActorSlotVO slot = new WorkflowTemplateVO.ActorSlotVO();
                slot.setSlotKey(item.getString("slotKey"));
                slot.setSlotName(item.getString("slotName"));
                slot.setDescription(item.getString("description"));
                slot.setDefaultModelType(item.getString("defaultModelType"));
                slot.setDefaultPersona(item.getString("defaultPersona"));
                slot.setRequired(item.getInteger("required"));
                slot.setAllowMultiple(item.getInteger("allowMultiple"));
                slot.setDefaultMembers(parseDefaultMembers(item.getJSONArray("defaultMembers")));
                actorSlots.add(slot);
            }
        } catch (Exception e) {
            log.warn("Failed to parse actorSlotsConfig", e);
        }
        return actorSlots;
    }

    private List<WorkflowTemplateVO.DefaultMemberVO> parseDefaultMembers(com.alibaba.fastjson.JSONArray raw) {
        List<WorkflowTemplateVO.DefaultMemberVO> members = new ArrayList<>();
        if (raw == null) {
            return members;
        }
        for (int i = 0; i < raw.size(); i++) {
            com.alibaba.fastjson.JSONObject item = raw.getJSONObject(i);
            if (item == null) {
                continue;
            }
            WorkflowTemplateVO.DefaultMemberVO dm = new WorkflowTemplateVO.DefaultMemberVO();
            Long templateId = item.getLong("memberTemplateId");
            dm.setMemberTemplateId(templateId);
            dm.setMemberName(item.getString("memberName"));
            dm.setModelType(item.getString("modelType"));
            dm.setPersona(item.getString("persona"));
            // 软引用：模版仍在则用最新名称/模型/人设，已删则保留快照并标记。
            if (templateId != null) {
                AiMemberTemplate tpl = memberTemplateMapper.selectById(templateId);
                if (tpl != null) {
                    dm.setTemplateExists(true);
                    if (StringUtils.hasText(tpl.getName())) dm.setMemberName(tpl.getName());
                    if (StringUtils.hasText(tpl.getModelType())) dm.setModelType(tpl.getModelType());
                    if (StringUtils.hasText(tpl.getPersona())) dm.setPersona(tpl.getPersona());
                } else {
                    dm.setTemplateExists(false);
                }
            } else {
                dm.setTemplateExists(true);
            }
            members.add(dm);
        }
        return members;
    }

    private void validateTarget(Set<String> knownStageKeys, String target, String fieldName) {
        if (!StringUtils.hasText(target)) {
            return;
        }
        String normalized = target.trim().toLowerCase();
        if (!knownStageKeys.contains(normalized)) {
            throw new IllegalArgumentException(fieldName + " 指向了不存在的阶段: " + normalized);
        }
    }

    private String normalizeNullableKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase();
    }

    private String normalizeSearchStrategy(String raw, String stageType) {
        if (StringUtils.hasText(raw)) {
            return raw.trim().toUpperCase();
        }
        return defaultSearchStrategy(stageType);
    }

    private String resolveSearchStrategy(AiWorkflowStageTemplate stageTemplate) {
        if (stageTemplate == null) {
            return "OFF";
        }
        return normalizeSearchStrategy(stageTemplate.getSearchStrategy(), stageTemplate.getStageType());
    }

    private String resolveSearchStrategyLabel(AiWorkflowStageTemplate stageTemplate) {
        return resolveSearchStrategyLabel(resolveSearchStrategy(stageTemplate));
    }

    private String resolveSearchStrategyLabel(String strategy) {
        if (!StringUtils.hasText(strategy)) {
            return "关闭搜索";
        }
        switch (strategy.toUpperCase()) {
            case "REQUIRED":
                return "强制搜索";
            case "OPTIONAL":
                return "可选搜索";
            default:
                return "关闭搜索";
        }
    }

    private String defaultSearchStrategy(String stageType) {
        if ("ANALYSIS".equalsIgnoreCase(stageType) || "TRACKING".equalsIgnoreCase(stageType)) {
            return "REQUIRED";
        }
        if ("DEBATE".equalsIgnoreCase(stageType)) {
            return "OPTIONAL";
        }
        return "OFF";
    }

    private AiWorkflowTemplate resolveTemplateByRequestedKey(String requestedTemplateKey) {
        ensureDefaultTemplateExists();
        if (StringUtils.hasText(requestedTemplateKey)) {
            AiWorkflowTemplate requested = workflowTemplateMapper.selectByKey(requestedTemplateKey.trim().toLowerCase());
            if (requested == null) {
                throw new IllegalArgumentException("流程模板不存在: " + requestedTemplateKey);
            }
            return requested;
        }
        AiWorkflowTemplate fallback = workflowTemplateMapper.selectByKey(DEFAULT_TEMPLATE_KEY);
        if (fallback == null) {
            throw new IllegalStateException("默认工作流模板不存在");
        }
        return fallback;
    }

    private String buildSkillFileIds(List<Long> skillFileIds) {
        if (skillFileIds == null || skillFileIds.isEmpty()) {
            return null;
        }
        List<Long> normalized = new ArrayList<>();
        for (Long id : skillFileIds) {
            if (id != null && !normalized.contains(id)) {
                normalized.add(id);
            }
        }
        return normalized.isEmpty() ? null : com.alibaba.fastjson.JSON.toJSONString(normalized);
    }

    private List<Long> parseSkillFileIds(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new ArrayList<>();
        }
        try {
            List<Long> parsed = com.alibaba.fastjson.JSON.parseArray(raw, Long.class);
            return parsed != null ? parsed : new ArrayList<>();
        } catch (Exception e) {
            log.warn("Failed to parse skillFileIds", e);
            return new ArrayList<>();
        }
    }

    private String buildInputStageKeys(List<String> inputStageKeys) {
        List<String> normalized = normalizeInputStageKeys(inputStageKeys);
        return normalized.isEmpty() ? null : com.alibaba.fastjson.JSON.toJSONString(normalized);
    }

    private List<String> parseInputStageKeys(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new ArrayList<>();
        }
        try {
            List<String> parsed = com.alibaba.fastjson.JSON.parseArray(raw, String.class);
            return normalizeInputStageKeys(parsed);
        } catch (Exception e) {
            return Arrays.stream(raw.split("[,，\\s]+"))
                    .filter(StringUtils::hasText)
                    .map(item -> item.trim().toLowerCase())
                    .distinct()
                    .collect(Collectors.toList());
        }
    }

    private List<String> normalizeInputStageKeys(List<String> inputStageKeys) {
        if (inputStageKeys == null || inputStageKeys.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> normalized = new ArrayList<>();
        for (String key : inputStageKeys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String normalizedKey = key.trim().toLowerCase();
            if (!normalized.contains(normalizedKey)) {
                normalized.add(normalizedKey);
            }
        }
        return normalized;
    }

    private List<SkillFileVO> resolveSkillFiles(List<Long> ids) {
        List<SkillFileVO> files = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return files;
        }
        Map<Long, AiSkillFile> byId = skillFileMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(AiSkillFile::getId, item -> item));
        for (Long id : ids) {
            AiSkillFile file = byId.get(id);
            if (file == null) {
                continue;
            }
            SkillFileVO vo = new SkillFileVO();
            vo.setId(file.getId());
            vo.setFileName(file.getFileName());
            vo.setContentType(file.getContentType());
            vo.setFileSize(file.getFileSize());
            vo.setGmtCreate(file.getGmtCreate());
            files.add(vo);
        }
        return files;
    }

    private boolean hasResumeTrigger(List<AiMeetingTrackingItem> items, Long updatedItemId, String updatedStatus) {
        for (AiMeetingTrackingItem item : items) {
            if (item.getReopenTrigger() == null || item.getReopenTrigger() != 1) {
                continue;
            }
            String status = Objects.equals(item.getId(), updatedItemId) ? updatedStatus : item.getStatus();
            if (TrackingItemStatusEnum.VERIFIED.getCode().equals(status)
                    || TrackingItemStatusEnum.CLOSED.getCode().equals(status)) {
                return true;
            }
        }
        return false;
    }

    private AiWorkflowStageTemplate buildStageTemplate(Long templateId,
                                                       String stageKey,
                                                       String stageName,
                                                       int stageOrder,
                                                       String stageType,
                                                       String actorType,
                                                       String completionRule,
                                                       String skillKey,
                                                       String skillPrompt,
                                                       String searchStrategy,
                                                       String passTarget,
                                                       String rejectTarget,
                                                       String reopenTarget,
                                                       int autoStart) {
        AiWorkflowStageTemplate stage = new AiWorkflowStageTemplate();
        stage.setTemplateId(templateId);
        stage.setStageKey(stageKey);
        stage.setStageName(stageName);
        stage.setStageOrder(stageOrder);
        stage.setStageType(stageType);
        stage.setActorType(actorType);
        stage.setCompletionRule(completionRule);
        stage.setSkillKey(skillKey);
        stage.setSkillPrompt(skillPrompt);
        stage.setSearchStrategy(searchStrategy);
        stage.setPassTarget(passTarget);
        stage.setRejectTarget(rejectTarget);
        stage.setReopenTarget(reopenTarget);
        stage.setAutoStart(autoStart);
        stage.setReviewRequired(isReviewStageType(stageType) ? 1 : 0);
        stage.setMaxReviewAttempts(0);
        return stage;
    }

    private void completeStage(Long meetingId, String stageKey, String outcome, String rejectReason) {
        AiWorkflowStageRun stageRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(meetingId, stageKey);
        if (stageRun == null) {
            return;
        }
        Date startedAt = stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date();
        workflowStageRunMapper.updateExecution(stageRun.getId(),
                    WorkflowStageStatusEnum.COMPLETED.getCode(),
                    stageRun.getHandoffSummary(),
                    stageRun.getHandoffPayload(),
                    outcome,
                rejectReason,
                startedAt,
                new Date());
    }

    private void activateStage(Long meetingId, String stageKey) {
        AiWorkflowStageRun stageRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(meetingId, stageKey);
        if (stageRun == null) {
            return;
        }
        workflowStageRunMapper.updateExecution(stageRun.getId(),
                WorkflowStageStatusEnum.ACTIVE.getCode(),
                stageRun.getHandoffSummary(),
                stageRun.getHandoffPayload(),
                stageRun.getOutcome(),
                stageRun.getRejectReason(),
                stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date(),
                null);
    }

    private void activateOrBypassStage(AiWorkflowRun run, String stageKey) {
        AiWorkflowStageRun stageRun = ensureActiveStageRun(run, stageKey);
        AiWorkflowStageTemplate stageTemplate = resolveStageTemplate(run, stageRun);
        if (isReviewStage(stageTemplate) && stageTemplate.getReviewRequired() != null && stageTemplate.getReviewRequired() == 0) {
            persistSubmission(stageRun, "SYSTEM", "BYPASS", "审核已关闭，自动透传到下一节点", null);
            completeStageRun(stageRun, "BYPASSED", null);
            moveToNextStageOrConclude(run, stageRun, stageTemplate, "审核已跳过，沿用上一节点信息");
            return;
        }
        if (isReviewStage(stageTemplate) && !"HUMAN".equalsIgnoreCase(stageTemplate.getActorType())) {
            autoReviewStage(run, stageRun, stageTemplate);
            return;
        }
        if (shouldAutoConcludeStage(stageTemplate)) {
            String finalContent = deriveAutoStageTransitionContent(stageRun, "系统归档结论");
            persistSubmission(stageRun, "SYSTEM", "SUBMIT", finalContent, null);
            completeStageRun(stageRun, "ARCHIVED", null);
            concludeManualWorkflow(run, finalContent);
            return;
        }
        if (shouldAutoExecuteStage(stageTemplate)) {
            autoExecuteStage(run, stageRun, stageTemplate);
        }
    }

    private void autoReviewStage(AiWorkflowRun run, AiWorkflowStageRun stageRun, AiWorkflowStageTemplate stageTemplate) {
        List<AiMeetingMember> candidates = resolveMembersForCurrentStage(stageRun.getMeetingId(), memberMapper.selectByMeetingId(stageRun.getMeetingId()));
        if (candidates.isEmpty()) {
            workflowStageRunMapper.updateExecution(stageRun.getId(),
                    WorkflowStageStatusEnum.BLOCKED.getCode(),
                    stageRun.getHandoffSummary(),
                    stageRun.getHandoffPayload(),
                    stageRun.getOutcome(),
                    "当前审核节点没有可执行成员",
                    stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date(),
                    null);
            workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(), "当前审核节点没有可执行成员");
            return;
        }
        AutoReviewDecision decision = buildAutomatedReviewDecision(stageRun, stageTemplate, candidates);
        if (decision == null || !StringUtils.hasText(decision.comment)) {
            workflowStageRunMapper.updateExecution(stageRun.getId(),
                    WorkflowStageStatusEnum.BLOCKED.getCode(),
                    stageRun.getHandoffSummary(),
                    stageRun.getHandoffPayload(),
                    stageRun.getOutcome(),
                    "自动审核节点未生成有效输出",
                    stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date(),
                    null);
            workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(), "自动审核节点未生成有效输出");
            return;
        }
        String actorRef = candidates.size() == 1 ? candidates.get(0).getMemberName() : stageTemplate.getActorSlotKey();
        String actionType = decision.approved ? "APPROVE" : "REJECT";
        persistSubmission(stageRun,
                StringUtils.hasText(actorRef) ? actorRef : "SYSTEM_REVIEW",
                actionType,
                decision.comment,
                buildStageArtifactPayload(actionType, decision.comment, "AUTO_REVIEW", candidates.size() > 1, decision.sourceActorRefs, null));
        if (decision.approved) {
            completeStageRun(stageRun, "APPROVED", null);
            if (StringUtils.hasText(stageTemplate.getPassTarget())) {
                moveToNextStageOrConclude(run, stageRun, stageTemplate, decision.comment);
            } else {
                concludeManualWorkflow(run, buildApprovedReviewConclusion(stageRun, decision.comment));
            }
            return;
        }
        if (shouldStopAfterReviewReject(stageRun, stageTemplate)) {
            completeStageRun(stageRun, "REJECT_LIMIT", decision.comment);
            concludeManualWorkflow(run, buildReviewLimitConclusion(stageRun, stageTemplate, decision.comment));
            return;
        }
        if (isRejectionLoopStagnant(stageRun, decision.comment)) {
            log.info("Rejection loop stagnant on stage={} attempt={} — fail-fast to save tokens",
                    stageRun.getStageKey(), stageRun.getAttemptNo());
            completeStageRun(stageRun, "REJECT_STAGNANT", decision.comment);
            concludeManualWorkflow(run, buildRejectionStagnantConclusion(stageRun, stageTemplate, decision.comment));
            return;
        }
        rejectStageRun(stageRun, decision.comment);
        activateTransitionStage(run, stageRun, stageTemplate.getRejectTarget(), decision.comment);
    }

    private boolean shouldAutoExecuteStage(AiWorkflowStageTemplate stageTemplate) {
        if (stageTemplate == null) {
            return false;
        }
        if ("HUMAN".equalsIgnoreCase(stageTemplate.getActorType())) {
            return false;
        }
        String stageType = stageTemplate.getStageType();
        return "ANALYSIS".equalsIgnoreCase(stageType)
                || "DEBATE".equalsIgnoreCase(stageType)
                || "TRACKING".equalsIgnoreCase(stageType);
    }

    private boolean shouldAutoConcludeStage(AiWorkflowStageTemplate stageTemplate) {
        return stageTemplate != null
                && "CONCLUDE".equalsIgnoreCase(stageTemplate.getStageType())
                && "SYSTEM".equalsIgnoreCase(stageTemplate.getActorType());
    }

    private void autoExecuteStage(AiWorkflowRun run, AiWorkflowStageRun stageRun, AiWorkflowStageTemplate stageTemplate) {
        List<AiMeetingMember> candidates = resolveMembersForCurrentStage(stageRun.getMeetingId(), memberMapper.selectByMeetingId(stageRun.getMeetingId()));
        if (candidates.isEmpty()) {
            workflowStageRunMapper.updateExecution(stageRun.getId(),
                    WorkflowStageStatusEnum.BLOCKED.getCode(),
                    stageRun.getHandoffSummary(),
                    stageRun.getHandoffPayload(),
                    stageRun.getOutcome(),
                    "当前自动节点没有可执行成员",
                    stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date(),
                    null);
            workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(), "当前自动节点没有可执行成员");
            return;
        }
        AutoStageExecutionResult result = buildAutomatedStageContent(stageRun, stageTemplate, candidates);
        if (result == null || !StringUtils.hasText(result.finalContent)) {
            String blockedReason = result != null && StringUtils.hasText(result.blockedReason)
                    ? result.blockedReason
                    : "自动节点未生成有效输出";
            if (result != null && hasAttemptedSearchTrace(result.searchTraces)) {
                String actorRef = candidates.size() == 1 ? candidates.get(0).getMemberName() : stageTemplate.getActorSlotKey();
                persistSubmission(stageRun,
                        StringUtils.hasText(actorRef) ? actorRef : "SYSTEM_AUTO",
                        "SEARCH_FAIL",
                        blockedReason,
                        buildStageArtifactPayload("SEARCH_FAIL", blockedReason, "AUTO_SEARCH_FAIL", false, result.sourceActorRefs, result.searchTraces));
            }
            workflowStageRunMapper.updateExecution(stageRun.getId(),
                    WorkflowStageStatusEnum.BLOCKED.getCode(),
                    stageRun.getHandoffSummary(),
                    stageRun.getHandoffPayload(),
                    stageRun.getOutcome(),
                    blockedReason,
                    stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date(),
                    null);
            workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(), blockedReason);
            return;
        }
        if (shouldTreatAutomatedStageOutputAsBlocked(stageTemplate, result)) {
            String blockedReason = firstNonBlank(result.blockedReason, summarizeBlockingStageOutput(result.finalContent), "自动节点未产出可交付内容");
            String actorRef = candidates.size() == 1 ? candidates.get(0).getMemberName() : stageTemplate.getActorSlotKey();
            persistSubmission(stageRun,
                    StringUtils.hasText(actorRef) ? actorRef : "SYSTEM_AUTO",
                    "SEARCH_FAIL",
                    blockedReason,
                    buildStageArtifactPayload("SEARCH_FAIL", blockedReason, "AUTO_SEARCH_FAIL", false, result.sourceActorRefs, result.searchTraces));
            workflowStageRunMapper.updateExecution(stageRun.getId(),
                    WorkflowStageStatusEnum.BLOCKED.getCode(),
                    stageRun.getHandoffSummary(),
                    stageRun.getHandoffPayload(),
                    stageRun.getOutcome(),
                    blockedReason,
                    stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date(),
                    null);
            workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(), blockedReason);
            return;
        }
        String actorRef = candidates.size() == 1 ? candidates.get(0).getMemberName() : stageTemplate.getActorSlotKey();
        persistSubmission(stageRun,
                StringUtils.hasText(actorRef) ? actorRef : "SYSTEM_AUTO",
                "SUBMIT",
                result.finalContent,
                buildStageArtifactPayload("SUBMIT", result.finalContent, result.mergedOutput ? "AUTO_MERGED" : "AUTO", result.mergedOutput, result.sourceActorRefs, result.searchTraces));
        completeStageRun(stageRun, "SUBMITTED", null);
        moveToNextStageOrConclude(run, stageRun, stageTemplate, result.finalContent);
    }

    private AutoStageExecutionResult buildAutomatedStageContent(AiWorkflowStageRun stageRun,
                                                                AiWorkflowStageTemplate stageTemplate,
                                                                List<AiMeetingMember> candidates) {
        AiMeetingRoom room = roomMapper.selectById(stageRun.getMeetingId());
        String skillFileContext = buildSkillFileContext(stageTemplate);
        List<String> outputs = new ArrayList<>();
        List<String> sourceActorRefs = new ArrayList<>();
        List<WorkflowStageSearchTrace> searchTraces = new ArrayList<>();
        List<String> requiredSearchFailures = new ArrayList<>();
        for (AiMeetingMember member : candidates) {
            String systemPrompt = buildAutomatedStageSystemPrompt(member);
            WorkflowStageSearchTrace searchTrace = executeWorkflowStageSearch(room, stageRun, stageTemplate, member);
            searchTraces.add(searchTrace);
            if (isRequiredWorkflowSearchFailure(stageTemplate, searchTrace)) {
                requiredSearchFailures.add(buildWorkflowRequiredSearchFailureReason(member, searchTrace));
                continue;
            }
            String webFullResearchContent = extractWorkflowWebFullResearchContent(searchTrace);
            if (StringUtils.hasText(webFullResearchContent) && shouldUseWorkflowWebFullResearchOutput(stageTemplate, searchTrace)) {
                NodeDeliverableContract deliverableContract = buildNodeDeliverableContract(room, stageRun, stageTemplate, member);
                DeliverableJudgement judgement = judgeDeliverable(deliverableContract, webFullResearchContent, member);
                searchTrace.judgementAcceptedAsDeliverable = judgement.acceptAsDeliverable();
                searchTrace.judgementIsSearchTraceOnly = judgement.isSearchTraceOnly;
                searchTrace.judgementReason = judgement.reason;
                searchTrace.judgementMissing = judgement.missing;
                if (judgement.acceptAsDeliverable()) {
                    sourceActorRefs.add(member.getMemberName());
                    outputs.add(candidates.size() == 1
                            ? webFullResearchContent.trim()
                            : "【" + member.getMemberName() + "】\n" + webFullResearchContent.trim());
                    continue;
                }
                log.info("Deliverable judge rejected DeepSeek Web output for stageRun={} member={} isTraceOnly={} reason={}",
                        stageRun.getId(), member.getMemberName(), judgement.isSearchTraceOnly, judgement.reason);
            }
            String userPrompt = buildAutomatedStageUserPrompt(room, stageRun, stageTemplate, member, skillFileContext, searchTrace);
            // maxTokens=4000：deliverable 契约要求 5 段输出（结论/依据/不确定性/下一步/appendix），
            // 2500 tokens 在带 appendix 的完整报告下仍会断在最后一两段，导致 reviewer 一直抱怨"内容截断"。
            String content = sanitizeWorkflowModelResponse(orchestrator.callModel(member.getModelType(), systemPrompt, userPrompt, 4000));
            if (StringUtils.hasText(content)) {
                sourceActorRefs.add(member.getMemberName());
                outputs.add(candidates.size() == 1 ? content.trim() : "【" + member.getMemberName() + "】\n" + content.trim());
            }
        }
        if (!requiredSearchFailures.isEmpty()) {
            String blockedReason = String.join("；", requiredSearchFailures);
            return AutoStageExecutionResult.blocked(blockedReason, searchTraces);
        }
        if (outputs.isEmpty()) {
            return AutoStageExecutionResult.blocked(buildAutomatedStageGenerationFailureReason(searchTraces), searchTraces);
        }
        if (outputs.size() == 1) {
            return AutoStageExecutionResult.success(outputs.get(0), false, sourceActorRefs, searchTraces);
        }
        String merged = mergeAutomatedStageOutputs(room, stageTemplate, candidates, outputs, skillFileContext);
        boolean mergedOutput = StringUtils.hasText(merged);
        return AutoStageExecutionResult.success(
                mergedOutput ? sanitizeWorkflowModelResponse(merged) : String.join("\n\n", outputs),
                mergedOutput,
                sourceActorRefs,
                searchTraces
        );
    }

    private AutoReviewDecision buildAutomatedReviewDecision(AiWorkflowStageRun stageRun,
                                                            AiWorkflowStageTemplate stageTemplate,
                                                            List<AiMeetingMember> candidates) {
        AiMeetingRoom room = roomMapper.selectById(stageRun.getMeetingId());
        String skillFileContext = buildSkillFileContext(stageTemplate);
        List<String> sourceActorRefs = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        int approvedCount = 0;
        int rejectedCount = 0;
        for (AiMeetingMember member : candidates) {
            String reviewRaw = orchestrator.callModel(
                    member.getModelType(),
                    buildAutomatedReviewSystemPrompt(member),
                    buildAutomatedReviewUserPrompt(room, stageRun, stageTemplate, member, skillFileContext),
                    1500
            );
            if (!StringUtils.hasText(reviewRaw)) {
                log.warn("Automated review returned empty for stageRun={} member={}", stageRun.getId(), member.getMemberName());
                continue;
            }
            com.alibaba.fastjson.JSONObject parsed = parseReviewDecision(reviewRaw);
            if (parsed == null) {
                log.warn("Automated review unparseable for stageRun={} member={} raw='{}'", stageRun.getId(), member.getMemberName(),
                        reviewRaw.length() > 300 ? reviewRaw.substring(0, 300) + "…" : reviewRaw);
                continue;
            }
            Boolean approved = parsed.getBoolean("approved");
            String comment = parsed.getString("comment");
            if (approved == null || !StringUtils.hasText(comment)) {
                log.warn("Automated review missing approved/comment for stageRun={} member={} parsed={}",
                        stageRun.getId(), member.getMemberName(), parsed);
                continue;
            }
            sourceActorRefs.add(member.getMemberName());
            comments.add("【" + member.getMemberName() + "】" + comment.trim());
            if (approved) {
                approvedCount++;
            } else {
                rejectedCount++;
            }
        }
        if (comments.isEmpty()) {
            // 所有审核成员都未返回有效 JSON → 不再让 stage 死锁 BLOCKED，
            // 转为软性 REJECT 让流程进入下一轮 analysis；stagnation 检测器或 maxReviewAttempts 会自然兜底。
            log.warn("All automated reviewers returned no valid decision on stageRun={}, falling back to soft REJECT", stageRun.getId());
            String fallbackActor = candidates.get(0).getMemberName();
            return new AutoReviewDecision(false,
                    "审核环节本轮未生成有效 JSON 结论（可能因输入过长或模型限流）。视为打回，要求下一轮重新提交可被结构化审核的交付。",
                    new ArrayList<>(java.util.Collections.singletonList(fallbackActor)));
        }
        boolean finalApproved = approvedCount > 0 && rejectedCount == 0;
        if (approvedCount > 0 && rejectedCount > 0) {
            finalApproved = false;
        }
        String finalComment = comments.size() == 1
                ? comments.get(0)
                : (finalApproved ? "多名审核成员均认为可通过，综合意见如下：\n" : "审核节点综合判断仍需修改，综合意见如下：\n")
                + String.join("\n\n", comments);
        return new AutoReviewDecision(finalApproved, finalComment, sourceActorRefs);
    }

    private String mergeAutomatedStageOutputs(AiMeetingRoom room,
                                              AiWorkflowStageTemplate stageTemplate,
                                              List<AiMeetingMember> candidates,
                                              List<String> outputs,
                                              String skillFileContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你需要汇总多个流程节点执行者的输出，形成一份统一的节点产出。\n");
        prompt.append("【会议主题】").append(room != null ? room.getTitle() : "").append("\n");
        prompt.append("【当前节点】").append(stageTemplate.getStageName()).append(" (").append(stageTemplate.getStageType()).append(")\n");
        if (StringUtils.hasText(stageTemplate.getSkillPrompt())) {
            prompt.append("【节点要求】").append(stageTemplate.getSkillPrompt().trim()).append("\n");
        }
        if (StringUtils.hasText(skillFileContext)) {
            prompt.append("【Skill 文件上下文】\n").append(skillFileContext).append("\n");
        }
        prompt.append("【候选输出】\n");
        for (String output : outputs) {
            prompt.append(output).append("\n\n");
        }
        prompt.append("请合并相同观点、保留分歧点，输出一份统一结果，至少包含：\n");
        prompt.append("1. 综合结论\n2. 关键依据\n3. 保留分歧\n4. 下一步建议\n");
        String modelType = candidates.get(0).getModelType();
        return orchestrator.callModel(modelType, "你是一个严格的流程结果汇总器。", prompt.toString(), 2200);
    }

    private String buildAutomatedReviewSystemPrompt(AiMeetingMember member) {
        String persona = StringUtils.hasText(member.getPersona()) ? member.getPersona().trim() : "你是一个严格的审核员。";
        return persona + "\n你当前在会议流程中担任【审核员】，职责是审核上一节点的交付。"
                + "你只评判该交付是否合格（通过 / 打回修改），绝不替它重做工作——不要自己写分析、研究或结论。"
                + "你必须给出明确结论：通过，或打回修改并给出理由。输出必须是 JSON：{\"approved\":true/false,\"comment\":\"...\"}。";
    }

    private String buildAutomatedReviewUserPrompt(AiMeetingRoom room,
                                                  AiWorkflowStageRun stageRun,
                                                  AiWorkflowStageTemplate stageTemplate,
                                                  AiMeetingMember member,
                                                  String skillFileContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("【会议主题】").append(room != null ? room.getTitle() : "").append("\n");
        if (room != null && StringUtils.hasText(room.getDescription())) {
            sb.append("【会议背景】").append(room.getDescription().trim()).append("\n");
        }
        sb.append("【当前审核节点】").append(stageTemplate.getStageName()).append(" (").append(stageTemplate.getStageType()).append(")\n");
        if (StringUtils.hasText(stageTemplate.getSkillKey())) {
            sb.append("【Skill】").append(stageTemplate.getSkillKey()).append("\n");
        }
        // 审核节点要评判的是【上游节点】交付的产出，而不是审核节点自身的产出。
        // 因此契约必须基于产出该交付的源节点（如分析节点），否则会要求被审节点变成审核意见。
        AiWorkflowStageTemplate reviewedStageTemplate = resolveReviewedSourceStageTemplate(stageRun, stageTemplate);
        if (reviewedStageTemplate != null && !Objects.equals(reviewedStageTemplate.getStageKey(), stageTemplate.getStageKey())) {
            sb.append("【被审核节点】").append(reviewedStageTemplate.getStageName())
                    .append(" (").append(reviewedStageTemplate.getStageType()).append(")\n");
        }
        NodeDeliverableContract deliverableContract = buildNodeDeliverableContract(room, stageRun,
                reviewedStageTemplate != null ? reviewedStageTemplate : stageTemplate, member);
        sb.append("【被审核节点的产出契约】（你审核的对象应满足以下契约，而不是变成一份审核意见）\n");
        sb.append(deliverableContract.renderForPrompt());
        if (StringUtils.hasText(skillFileContext)) {
            sb.append("【Skill文件上下文】\n").append(skillFileContext).append("\n");
        }
        String handoff = deriveAutoStageTransitionContent(stageRun, null);
        if (StringUtils.hasText(handoff)) {
            sb.append("【待审核交付】\n").append(handoff).append("\n");
        }
        sb.append("【审核要求】\n");
        sb.append("1. 你的任务：判断上面【待审核交付】是否合格地完成了【被审核节点的产出契约】。你输出的是审核结论，绝不要自己重做被审核节点的工作（例如不要自己写一份分析报告）。\n");
        sb.append("2. 唯一通过条件：待审核交付的【正文】真正完成了上述\"产出契约 > 产出内容\"中定义的节点交付物。\n");
        sb.append("3. 如果待审核交付主体是搜索计划、命中来源、字段表、缺失信息、证据表等过程性内容（即使来源充分），approved 必须为 false，comment 必须明确指出\"主体是搜索/研究跟踪，缺少节点交付本身\"。\n");
        sb.append("4. 来源数量、覆盖广度、是否触达指定网站等\"过程指标\"不可作为单独的通过依据。\n");
        sb.append("5. approved=false 时，comment 必须给出明确、可执行的修改方向，且不要继续要求更多搜索来源。\n");
        sb.append("6. comment 必须是\"通过/不通过 + 理由\"形式的审核意见，不得是对议题本身的分析。\n");
        sb.append("7. 只输出 JSON，不要输出 Markdown。\n");
        sb.append("8. JSON 结构固定为：{\"approved\":true/false,\"comment\":\"...\"}\n");
        sb.append("【你的身份】").append(member.getMemberName()).append("\n");
        return sb.toString();
    }

    private com.alibaba.fastjson.JSONObject parseReviewDecision(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return com.alibaba.fastjson.JSON.parseObject(text);
        } catch (Exception e) {
            log.warn("Failed to parse automated review decision: {}", raw);
            return null;
        }
    }

    private String buildSkillFileContext(AiWorkflowStageTemplate stageTemplate) {
        List<Long> ids = parseSkillFileIds(stageTemplate.getSkillFileIds());
        if (ids.isEmpty()) {
            return null;
        }
        List<AiSkillFile> files = skillFileMapper.selectByIds(ids);
        if (files.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (AiSkillFile file : files) {
            String summary = readSkillFileSummary(file);
            if (!StringUtils.hasText(summary)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("文件：").append(file.getFileName()).append("\n").append(summary);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String readSkillFileSummary(AiSkillFile file) {
        if (file == null || !StringUtils.hasText(file.getStoragePath())) {
            return null;
        }
        try {
            Path path = Paths.get(file.getStoragePath());
            if (!Files.exists(path)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return null;
            }
            String content = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!StringUtils.hasText(content)) {
                return null;
            }
            content = content.replace("\r\n", "\n");
            if (content.length() > 1200) {
                return content.substring(0, 1200) + "\n...[文件内容已截断]";
            }
            return content;
        } catch (IOException e) {
            log.warn("Failed to read skill file content id={}", file.getId(), e);
            return null;
        }
    }

    private String buildAutomatedStageSystemPrompt(AiMeetingMember member) {
        String persona = StringUtils.hasText(member.getPersona()) ? member.getPersona().trim() : "你是一个严谨的流程节点执行者。";
        return persona + "\n你当前不是自由聊天，而是在会议流程中完成一个明确节点任务。输出必须围绕节点目标，避免空泛。";
    }

    private NodeDeliverableContract buildNodeDeliverableContract(AiMeetingRoom room,
                                                                  AiWorkflowStageRun stageRun,
                                                                  AiWorkflowStageTemplate stageTemplate,
                                                                  AiMeetingMember member) {
        String stageType = firstNonBlank(stageTemplate != null ? stageTemplate.getStageType() : null, "STAGE");
        String stageName = firstNonBlank(stageTemplate != null ? stageTemplate.getStageName() : null, "当前节点");
        String skillPrompt = stageTemplate != null && StringUtils.hasText(stageTemplate.getSkillPrompt())
                ? stageTemplate.getSkillPrompt().trim()
                : null;
        String identityTitle = "本工作流中【" + stageName + "】(" + stageType + ") 节点的执行者";
        String deliverableWhat = StringUtils.hasText(skillPrompt)
                ? compactWorkflowSearchText(skillPrompt, 700)
                : "请按节点名称\"" + stageName + "\"完成该节点的核心交付物本身（不是研究/搜索过程报告）";
        String deliverableShape = "正文必须直接回答节点任务：1) 结论或决策；2) 关键依据与推导（标注信源）；3) 不确定性与分歧；4) 下一步建议。搜索计划、命中来源、缺失字段、引用清单等过程性内容只能放在末尾 <appendix> 块内。";
        String deliverableNotDoing = "不要把\"搜索计划/命中来源/字段表/缺失信息/证据表\"当成正文主体；如果证据不足，依然要先给出基于现有证据的结论与假设，然后在不确定性章节标注待复核项。绝不允许只输出搜索摘要或来源清单。";
        return new NodeDeliverableContract(identityTitle, deliverableWhat, deliverableShape, deliverableNotDoing, skillPrompt);
    }

    private DeliverableJudgement judgeDeliverable(NodeDeliverableContract contract,
                                                  String candidate,
                                                  AiMeetingMember member) {
        if (contract == null || !StringUtils.hasText(candidate)) {
            return new DeliverableJudgement(false, true, "候选输出为空", new ArrayList<>());
        }
        String system = "你是流程节点产出审计器。判断给定候选输出是否真正完成了节点产出契约，而不是只产出了搜索/研究过程报告。只输出 JSON，不要 Markdown。";
        StringBuilder user = new StringBuilder();
        user.append(contract.renderForPrompt());
        user.append("\n【候选输出】\n").append(compactWorkflowSearchText(candidate, 3500)).append("\n");
        user.append("\n【判断要求】\n");
        user.append("1. satisfiesDeliverable: 必须同时满足三点才能为 true：\n");
        user.append("   (a) 正文回答了\"产出契约 > 产出内容\"定义的节点交付物；\n");
        user.append("   (b) 产出结构中要求的所有必备章节都已出现并写完（包括\"不确定性/分歧\"和\"下一步建议\"等）；\n");
        user.append("   (c) 内容没有在句子中间被截断（如句尾停在逗号/未闭合括号/未完成列表项）。\n");
        user.append("   任一缺失/截断 → satisfiesDeliverable 必须为 false，并把缺失项列入 missing。\n");
        user.append("2. isSearchTraceOnly: 候选输出的主体是否主要是搜索计划/命中来源/字段表/缺失信息/证据表等过程性内容，而缺少节点本身的结论与推导。\n");
        user.append("3. missing: 最多 3 条具体缺失项，要具体（如\"未给出'下一步建议'章节\"、\"末段在'公司基本面处于'后截断\"），不要泛泛。\n");
        user.append("4. reason: 一句话总结。\n");
        user.append("严格输出 JSON：{\"satisfiesDeliverable\":bool,\"isSearchTraceOnly\":bool,\"missing\":[\"...\"],\"reason\":\"...\"}");
        String raw;
        try {
            raw = orchestrator.callModel(member.getModelType(), system, user.toString(), 900);
        } catch (Exception e) {
            log.warn("Deliverable judge model call failed: {}", e.getMessage());
            return new DeliverableJudgement(false, false, "评判模型调用失败，触发兜底合成: " + e.getMessage(), new ArrayList<>());
        }
        com.alibaba.fastjson.JSONObject parsed = parseReviewDecision(raw);
        if (parsed == null) {
            log.warn("Deliverable judge produced unparseable response: {}", raw);
            return new DeliverableJudgement(false, false, "评判结果无法解析，触发兜底合成", new ArrayList<>());
        }
        Boolean sat = parsed.getBoolean("satisfiesDeliverable");
        Boolean only = parsed.getBoolean("isSearchTraceOnly");
        String reason = parsed.getString("reason");
        List<String> missing = toStringList(parsed.getJSONArray("missing"));
        // 自洽校验：如果 missing 非空，强制 satisfiesDeliverable=false。
        // 这是因为 judge 模型有时会列出"必备章节缺失"但仍投通过票（自相矛盾），
        // 我们用客观的 missing 字段做最终仲裁，避免半截内容流入下游。
        boolean satisfies = sat != null && sat && (missing == null || missing.isEmpty());
        return new DeliverableJudgement(
                satisfies,
                only != null && only,
                StringUtils.hasText(reason) ? reason : null,
                missing
        );
    }

    private WorkflowStageSearchTrace executeWorkflowStageSearch(AiMeetingRoom room,
                                                                AiWorkflowStageRun stageRun,
                                                                AiWorkflowStageTemplate stageTemplate,
                                                                AiMeetingMember member) {
        String strategy = resolveSearchStrategy(stageTemplate);
        long executedAt = System.currentTimeMillis();
        WorkflowSearchContract contract = buildWorkflowSearchContract(room, stageTemplate, member, strategy);
        String webParityGapNote = resolveWorkflowWebParityGapNote(contract);
        if ("OFF".equalsIgnoreCase(strategy)) {
            return new WorkflowStageSearchTrace(strategy, false, contract, null, new ArrayList<>(), new ArrayList<>(), null, null, executedAt,
                    "当前节点关闭了外部联网搜索，仅基于模板指令与上游交付执行。");
        }
        if (!webSearch.isEnabled()) {
            WebSearchService.SearchMeta meta = new WebSearchService.SearchMeta();
            meta.provider = "none";
            meta.status = "disabled";
            meta.note = "当前环境未启用外部联网搜索，自动节点无法执行网页检索。";
            return new WorkflowStageSearchTrace(strategy, false, contract, null, new ArrayList<>(), new ArrayList<>(), meta, null, executedAt, meta.note);
        }
        WorkflowSearchPlan agentPlan = null;
        List<String> queries = buildWorkflowStageSearchQueries(room, stageTemplate, member, contract, null);
        if (queries.isEmpty()) {
            WebSearchService.SearchMeta meta = new WebSearchService.SearchMeta();
            meta.provider = "none";
            meta.status = "REQUIRED".equalsIgnoreCase(strategy) ? "missing_required_search" : "no_queries";
            meta.note = "REQUIRED".equalsIgnoreCase(strategy)
                    ? "当前节点要求联网搜索，但未生成有效搜索词。"
                    : "当前节点未生成有效搜索词，跳过联网搜索。";
            appendAgentPlanDiagnostics(meta, agentPlan);
            return new WorkflowStageSearchTrace(strategy, false, contract, agentPlan, queries, new ArrayList<>(), meta, null, executedAt, meta.note);
        }
        WorkflowStageSearchTrace builtInTrace = null;
        List<WebSearchService.SearchResult> aggregatedResults = new ArrayList<>();
        List<String> aggregatedQueries = new ArrayList<>();
        if (shouldPreferWorkflowBuiltInSearch(strategy, contract)) {
            builtInTrace = executeWorkflowBuiltInSearch(room, stageRun, stageTemplate, member, contract, null, executedAt);
            if (builtInTrace != null) {
                agentPlan = builtInTrace.agentPlan;
                appendWorkflowSearchDiagnostic(builtInTrace.meta, webParityGapNote);
                appendAgentPlanDiagnostics(builtInTrace.meta, agentPlan);
                aggregatedQueries.addAll(builtInTrace.queries);
                aggregatedResults.addAll(builtInTrace.results);
                if (isWorkflowSearchSatisfied(strategy, contract, builtInTrace.results)) {
                    return builtInTrace;
                }
                queries = buildWorkflowStageSearchQueries(room, stageTemplate, member, contract, agentPlan);
            }
        }
        webSearch.beginSession();
        List<WebSearchService.SearchResult> externalResults = new ArrayList<>();
        if (shouldUseSingleWorkflowDeepSeekWebSearch(strategy, contract)) {
            String deepSeekWebQuery = buildWorkflowDeepSeekWebSearchTask(room, stageRun, stageTemplate, member, contract, agentPlan, queries);
            if (StringUtils.hasText(deepSeekWebQuery)) {
                try {
                    aggregatedQueries.add(deepSeekWebQuery);
                    externalResults.addAll(webSearch.search(deepSeekWebQuery, 5, Collections.singletonList("deepseek-web"), true));
                } catch (Exception e) {
                    log.warn("Workflow DeepSeek web search failed, stageKey={}, member={}", stageTemplate.getStageKey(), member.getMemberName(), e);
                }
            }
        }
        List<WebSearchService.SearchResult> interimResults = new ArrayList<>(aggregatedResults);
        interimResults.addAll(externalResults);
        if (!isWorkflowSearchSatisfied(strategy, contract, interimResults)) {
            List<String> fallbackProviders = buildWorkflowFallbackProviderOrder(contract);
            for (String query : queries) {
                try {
                    externalResults.addAll(webSearch.search(query, 3, fallbackProviders));
                } catch (Exception e) {
                    log.warn("Workflow stage search failed, stageKey={}, member={}, query={}", stageTemplate.getStageKey(), member.getMemberName(), query, e);
                }
            }
        }
        aggregatedQueries.addAll(queries);
        aggregatedResults.addAll(externalResults);
        List<WebSearchService.SearchResult> ranked = rankWorkflowSearchResults(room, stageTemplate, member, aggregatedResults, agentPlan);
        List<WebSearchService.SearchResult> deduped = dedupeWorkflowSearchResults(ranked, 5);
        WebSearchService.SearchMeta meta = copySearchMeta(webSearch.consumeSessionMeta());
        if (meta == null) {
            meta = new WebSearchService.SearchMeta();
        }
        appendWorkflowSearchDiagnostic(meta, webParityGapNote);
        appendAgentPlanDiagnostics(meta, agentPlan);
        mergeBuiltInTraceIntoMeta(builtInTrace, meta);
        meta.queryCount = dedupeOrderedStrings(aggregatedQueries).size();
        meta.providerResultCount = deduped.size();
        boolean success = isWorkflowSearchSatisfied(strategy, contract, deduped);
        String requiredSourceFailure = null;
        if (!success && contract != null && contract.strict) {
            requiredSourceFailure = validateWorkflowRequiredSearchCoverage(contract, deduped);
        }
        if (success && !deduped.isEmpty()) {
            meta.status = "success";
            if (!StringUtils.hasText(meta.note)) {
                meta.note = builtInTrace != null
                        ? "本轮已先使用 DeepSeek 内置联网搜索，再结合外部搜索补充结果，并将可用证据注入当前流程节点。"
                        : "本轮已执行联网搜索，并将结果注入当前流程节点。";
            }
        } else if (success) {
            meta.status = firstNonBlank(meta.status, "no_results");
            if (!StringUtils.hasText(meta.note)) {
                meta.note = "本轮已按 REQUIRED 策略尝试联网搜索，但未获得可用结果；由于当前 prompt 未设置硬性阻塞条件，节点继续执行。";
            }
        } else if (contract != null && contract.strict) {
            meta.status = "missing_required_search";
            meta.note = StringUtils.hasText(requiredSourceFailure)
                    ? requiredSourceFailure
                    : "当前节点要求联网搜索，但本轮未获得可用网页结果。";
        } else {
            if (!StringUtils.hasText(meta.status) || "success".equalsIgnoreCase(meta.status)) {
                meta.status = "no_results";
            }
            if (!StringUtils.hasText(meta.note)) {
                meta.note = "本轮已执行联网搜索，但未获得可用网页结果。";
            }
        }
        Integer targetYear = extractTargetYear(room != null ? room.getTitle() : null);
        WebSearchService.ResearchDigest digest = webSearch.buildResearchDigest(
                room != null ? room.getTitle() : stageTemplate.getStageName(),
                "workflow_" + stageTemplate.getStageType().toLowerCase(),
                targetYear,
                dedupeOrderedStrings(aggregatedQueries),
                deduped
        );
        return new WorkflowStageSearchTrace(strategy, true, contract, agentPlan, dedupeOrderedStrings(aggregatedQueries), deduped, meta, digest, executedAt, meta.note);
    }

    private boolean shouldPreferWorkflowBuiltInSearch(String strategy, WorkflowSearchContract contract) {
        if ("OFF".equalsIgnoreCase(strategy)) {
            return false;
        }
        if (shouldUseSingleWorkflowDeepSeekWebSearch(strategy, contract)) {
            return false;
        }
        if (contract == null) {
            return false;
        }
        return contract.strict || !contract.sourceRequirements.isEmpty();
    }

    private boolean shouldUseSingleWorkflowDeepSeekWebSearch(String strategy, WorkflowSearchContract contract) {
        if ("OFF".equalsIgnoreCase(strategy)) {
            return false;
        }
        return webSearch.isDeepSeekWebSearchEnabled() && webSearch.isDeepSeekWebBridgeReachable();
    }

    private List<String> buildWorkflowPreferredProviderOrder(WorkflowSearchContract contract) {
        if (contract == null) {
            return null;
        }
        if (contract.strict || !contract.sourceRequirements.isEmpty()) {
            return Arrays.asList("deepseek-web", "zhipu", "serper");
        }
        return null;
    }

    private List<String> buildWorkflowFallbackProviderOrder(WorkflowSearchContract contract) {
        List<String> providers = buildWorkflowPreferredProviderOrder(contract);
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        return providers.stream()
                .filter(item -> !"deepseek-web".equalsIgnoreCase(item)
                        && !"deepseek_web".equalsIgnoreCase(item)
                        && !"deepseek".equalsIgnoreCase(item))
                .collect(Collectors.toList());
    }

    private String buildWorkflowDeepSeekWebSearchTask(AiMeetingRoom room,
                                                     AiWorkflowStageRun stageRun,
                                                     AiWorkflowStageTemplate stageTemplate,
                                                     AiMeetingMember member,
                                                     WorkflowSearchContract contract,
                                                     WorkflowSearchPlan agentPlan,
                                                     List<String> candidateQueries) {
        NodeDeliverableContract deliverableContract = buildNodeDeliverableContract(room, stageRun, stageTemplate, member);
        StringBuilder sb = new StringBuilder();
        sb.append("你是").append(deliverableContract.identityTitle).append("，不是搜索报告写手。\n");
        sb.append("你的首要任务是产出本节点的交付物本身。联网搜索只是支持工具，不是你的交付物。\n");
        sb.append("请在一个全新会话中独立完成本节点，不要依赖网页端历史对话；自行制定搜索计划，但搜索结果只用于支撑交付，不可替代交付。\n");
        sb.append("\n");
        sb.append(deliverableContract.renderForPrompt());
        sb.append("\n");
        sb.append("【任务】").append(room != null ? firstNonBlank(room.getTitle(), "") : "").append("\n");
        if (room != null && StringUtils.hasText(room.getProjectContext())) {
            sb.append("【背景】").append(compactWorkflowSearchText(room.getProjectContext(), 260)).append("\n");
        }
        String handoff = deriveAutoStageTransitionContent(stageRun, null);
        if (StringUtils.hasText(handoff)) {
            sb.append("【上游交付/打回意见】").append(compactWorkflowSearchText(handoff, 240)).append("\n");
        }
        sb.append("【执行者】").append(firstNonBlank(member.getMemberName(), "")).append("\n");
        if (StringUtils.hasText(member.getPersona())) {
            sb.append("【角色边界】").append(compactWorkflowSearchText(member.getPersona(), 700)).append("\n");
        }
        String meetingState = buildWorkflowMeetingStatePacket(room, stageRun, stageTemplate, member);
        if (StringUtils.hasText(meetingState)) {
            sb.append(meetingState).append("\n");
        }
        sb.append("\n");
        sb.append("【搜索方式】你完全自主决定搜什么、用什么关键词、综合哪些来源。系统不限定来源域名、不预设字段清单。\n");
        sb.append("\n");
        sb.append("【输出格式（严格遵守）】\n");
        sb.append("先输出节点交付物本身（正文），结构示例：\n");
        sb.append("  - 结论 / 决策\n");
        sb.append("  - 关键依据与推导（每条事实结论后必须用 [src: 完整可点击URL] 标注真实命中链接）\n");
        sb.append("  - 不确定性 / 分歧 / 风险\n");
        sb.append("  - 下一步建议\n");
        sb.append("正文写完后，再追加一个 <appendix> 块，里面写：\n");
        sb.append("  - 实际搜索关键词\n");
        sb.append("  - 命中来源清单（编号 + 完整URL，编号要与正文 [src: ...] 对应）\n");
        sb.append("  - 冲突信息 / 缺失字段\n");
        sb.append("【硬规则】\n");
        sb.append("- 正文不得以\"搜索计划\"\"命中来源\"\"证据表\"\"缺失信息\"开头或为主体；这些只能进 <appendix>。\n");
        sb.append("- 即便证据不足，正文也必须先给出基于现有证据的结论与假设，并在\"不确定性\"中标注待复核项。\n");
        sb.append("- 前序观点只能作为参考；事实结论必须标明是你本轮复核确认、存在冲突，还是仅来自前序未复核观点。\n");
        sb.append("- 绝不允许虚构链接：如果某条事实没有真实命中的可点击 URL，请把它写进\"不确定性\"或\"缺失字段\"，不要给它编造 [src: ...]。\n");
        return sb.toString();
    }

    private String buildWorkflowMeetingStatePacket(AiMeetingRoom room,
                                                   AiWorkflowStageRun stageRun,
                                                   AiWorkflowStageTemplate stageTemplate,
                                                   AiMeetingMember currentMember) {
        if (stageRun == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【会议状态包】\n");
        sb.append("- 当前轮次/尝试：")
                .append(stageRun.getStageKey())
                .append(" #")
                .append(stageRun.getAttemptNo() != null ? stageRun.getAttemptNo() : 1)
                .append("\n");
        if (stageTemplate != null && StringUtils.hasText(stageTemplate.getSkillPrompt())) {
            sb.append("- 当前节点指令：")
                    .append(compactWorkflowSearchText(stageTemplate.getSkillPrompt(), 450))
                    .append("\n");
        }
        if (room != null && StringUtils.hasText(room.getDescription())) {
            sb.append("- 会议说明：")
                    .append(compactWorkflowSearchText(room.getDescription(), 320))
                    .append("\n");
        }
        String handoff = deriveAutoStageTransitionContent(stageRun, null);
        if (StringUtils.hasText(handoff)) {
            sb.append("- 上游交付/打回意见：")
                    .append(compactWorkflowSearchText(handoff, 900))
                    .append("\n");
        }
        List<AiWorkflowStageSubmission> submissions = workflowStageSubmissionMapper.selectByMeetingId(stageRun.getMeetingId());
        if (submissions != null && !submissions.isEmpty()) {
            sb.append("- 截至当前的会议记录摘要：\n");
            int appended = 0;
            int start = Math.max(0, submissions.size() - 8);
            for (int i = start; i < submissions.size(); i++) {
                AiWorkflowStageSubmission submission = submissions.get(i);
                if (submission == null || submission.getStageRunId() == null) {
                    continue;
                }
                if (Objects.equals(submission.getStageRunId(), stageRun.getId())) {
                    continue;
                }
                String content = firstNonBlank(
                        parseSubmissionPayloadString(submission.getPayloadJson(), "summary"),
                        parseSubmissionPayloadString(submission.getPayloadJson(), "detailText"),
                        submission.getContent()
                );
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                sb.append("  - ")
                        .append(firstNonBlank(submission.getStageKey(), "stage"))
                        .append("/")
                        .append(firstNonBlank(submission.getActionType(), "SUBMIT"))
                        .append("/")
                        .append(firstNonBlank(submission.getActorRef(), "unknown"))
                        .append("：")
                        .append(compactWorkflowSearchText(content, 360))
                        .append("\n");
                appended++;
            }
            if (appended == 0) {
                sb.append("  - 暂无前序提交。\n");
            }
        }
        sb.append("- 使用规则：前序记录是上下文，不是事实锚。你必须独立核验关键事实；发现冲突时列入冲突表。\n");
        return sb.toString();
    }

    private String compactWorkflowSearchText(String text, int limit) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String compacted = text.replaceAll("\\s+", " ").trim();
        if (compacted.length() <= limit) {
            return compacted;
        }
        return compacted.substring(0, Math.max(0, limit)) + "…";
    }

    private String resolveWorkflowWebParityGapNote(WorkflowSearchContract contract) {
        if (contract == null || contract.sourceRequirements.isEmpty()) {
            return null;
        }
        if (!webSearch.isDeepSeekWebSearchEnabled()) {
            return "当前节点设置了来源偏好并希望逼近 DeepSeek 网页版的自主搜索效果，但 deepseek-web provider 未启用，当前只能退化为 API 内置搜索与外部搜索补搜。";
        }
        if (!webSearch.isDeepSeekWebBridgeReachable()) {
            return "当前节点设置了来源偏好并希望逼近 DeepSeek 网页版的自主搜索效果，但 deepseek-web bridge 当前不可达，当前只能退化为 API 内置搜索与外部搜索补搜。";
        }
        return null;
    }

    private void appendWorkflowSearchDiagnostic(WebSearchService.SearchMeta meta, String note) {
        if (meta == null || !StringUtils.hasText(note)) {
            return;
        }
        String entry = "workflow_search_contract： " + note.trim();
        if (!meta.providerDiagnostics.contains(entry)) {
            meta.providerDiagnostics.add(entry);
        }
    }

    private void appendAgentPlanDiagnostics(WebSearchService.SearchMeta meta, WorkflowSearchPlan agentPlan) {
        if (meta == null || agentPlan == null || !agentPlan.hasQueries()) {
            return;
        }
        String entry = "agent_search_plan： 模型已生成搜索计划，queries="
                + agentPlan.queries.size()
                + (agentPlan.preferredSources.isEmpty() ? "" : "，sources=" + String.join("/", agentPlan.preferredSources));
        if (!meta.providerDiagnostics.contains(entry)) {
            meta.providerDiagnostics.add(entry);
        }
    }

    private WorkflowSearchPlan deriveWorkflowSearchPlanFromQueries(List<String> queries,
                                                                   WorkflowSearchContract contract,
                                                                   String note) {
        List<String> normalizedQueries = sanitizeWorkflowPlanList(queries, 6, true);
        List<String> sources = contract != null && contract.sourceRequirements != null
                ? contract.sourceRequirements.stream()
                .map(item -> StringUtils.hasText(item.domain) ? item.domain : item.label)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList())
                : new ArrayList<>();
        List<String> fields = contract != null && contract.requiredFields != null
                ? new ArrayList<>(contract.requiredFields)
                : new ArrayList<>();
        if (normalizedQueries.isEmpty() && sources.isEmpty() && fields.isEmpty()) {
            return null;
        }
        String normalizedNote = firstNonBlank(note, "DeepSeek 内置搜索已根据当前节点约束生成搜索计划。");
        long requiredSourceCount = contract != null && contract.sourceRequirements != null
                ? contract.sourceRequirements.stream().filter(item -> item != null && item.required).count()
                : 0L;
        if (requiredSourceCount > 1 && normalizedQueries.size() < requiredSourceCount) {
            normalizedNote = normalizedNote + " 内置搜索本轮未逐一覆盖所有必需来源，后续将按契约补搜。";
        }
        return new WorkflowSearchPlan("deepseek_builtin_search", normalizedNote, normalizedQueries, sources, fields);
    }

    private WorkflowStageSearchTrace executeWorkflowBuiltInSearch(AiMeetingRoom room,
                                                                  AiWorkflowStageRun stageRun,
                                                                  AiWorkflowStageTemplate stageTemplate,
                                                                  AiMeetingMember member,
                                                                  WorkflowSearchContract contract,
                                                                  WorkflowSearchPlan agentPlan,
                                                                  long executedAt) {
        String searchModelType = orchestrator.resolveExperimentalSearchModelType(member.getModelType());
        if (!StringUtils.hasText(searchModelType)) {
            log.info("Workflow built-in search skipped: no experimental search model available for member={}", member.getMemberName());
            return null;
        }
        log.info("Workflow built-in search attempting stageKey={} member={} modelType={}",
                stageTemplate.getStageKey(), member.getMemberName(), searchModelType);
        String systemPrompt = "你是会议流程里的联网搜索执行器。你只负责执行网页搜索并返回检索证据，不负责撰写分析结论。"
                + "必须尽量命中用户明确指定的网站和字段，优先返回可核验的原始来源。";
        String userPrompt = buildWorkflowBuiltInSearchPrompt(room, stageRun, stageTemplate, member, contract, agentPlan);
        ChatResult chatResult = orchestrator.callModelEx(searchModelType, systemPrompt, userPrompt);
        if (chatResult == null || !chatResult.hasSearchEvidence()) {
            log.warn("Workflow built-in search returned no evidence stageKey={} member={} searchStatus={} modelStatus={}",
                    stageTemplate.getStageKey(), member.getMemberName(),
                    chatResult != null ? chatResult.searchStatus : "null",
                    chatResult != null ? chatResult.modelStatus : "null");
            return null;
        }
        List<String> queries = dedupeOrderedStrings(chatResult.searchQueries);
        WorkflowSearchPlan derivedPlan = deriveWorkflowSearchPlanFromQueries(
                queries,
                contract,
                firstNonBlank(chatResult.searchNote, "DeepSeek 内置搜索根据用户约束生成了搜索计划。")
        );
        List<WebSearchService.SearchResult> ranked = rankWorkflowSearchResults(room, stageTemplate, member, chatResult.searchResults, derivedPlan);
        List<WebSearchService.SearchResult> deduped = dedupeWorkflowSearchResults(ranked, 5);
        WebSearchService.SearchMeta meta = new WebSearchService.SearchMeta();
        meta.provider = "deepseek_builtin_search";
        meta.status = isWorkflowSearchSatisfied(resolveSearchStrategy(stageTemplate), contract, deduped)
                ? "success"
                : firstNonBlank(chatResult.searchStatus, "missing_required_search");
        meta.note = firstNonBlank(chatResult.searchNote,
                deduped.isEmpty() ? "DeepSeek 内置联网搜索未返回可用结果。" : "DeepSeek 内置联网搜索已返回候选结果。");
        meta.queryCount = queries.size();
        meta.providerResultCount = deduped.size();
        if (StringUtils.hasText(chatResult.modelStatus) && !"success".equalsIgnoreCase(chatResult.modelStatus)) {
            meta.providerDiagnostics.add("deepseek_builtin_search正文状态：" + firstNonBlank(chatResult.modelNote, chatResult.modelStatus));
        }
        log.info("Workflow built-in search produced evidence stageKey={} member={} queries={} results={}",
                stageTemplate.getStageKey(), member.getMemberName(), queries.size(), deduped.size());
        Integer targetYear = extractTargetYear(room != null ? room.getTitle() : null);
        WebSearchService.ResearchDigest digest = webSearch.buildResearchDigest(
                room != null ? room.getTitle() : stageTemplate.getStageName(),
                "workflow_" + stageTemplate.getStageType().toLowerCase(),
                targetYear,
                queries,
                deduped
        );
        return new WorkflowStageSearchTrace(resolveSearchStrategy(stageTemplate), true, contract, derivedPlan, queries, deduped, meta, digest, executedAt, meta.note);
    }

    private String buildWorkflowBuiltInSearchPrompt(AiMeetingRoom room,
                                                    AiWorkflowStageRun stageRun,
                                                    AiWorkflowStageTemplate stageTemplate,
                                                    AiMeetingMember member,
                                                    WorkflowSearchContract contract,
                                                    WorkflowSearchPlan agentPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append("你现在只执行联网搜索，不做分析，不输出价格区间结论。\n");
        sb.append("【会议主题】").append(room != null ? firstNonBlank(room.getTitle(), "") : "").append("\n");
        if (room != null && StringUtils.hasText(room.getProjectContext())) {
            sb.append("【项目背景】").append(room.getProjectContext().trim()).append("\n");
        }
        if (room != null && StringUtils.hasText(room.getDescription())) {
            sb.append("【会议背景】").append(room.getDescription().trim()).append("\n");
        }
        String handoff = deriveAutoStageTransitionContent(stageRun, null);
        if (StringUtils.hasText(handoff)) {
            sb.append("【上游交付】").append(handoff).append("\n");
        }
        sb.append("【当前节点】").append(stageTemplate.getStageName()).append(" / ").append(stageTemplate.getStageType()).append("\n");
        sb.append("【执行者】").append(firstNonBlank(member.getMemberName(), "")).append("\n");
        if (contract != null && !contract.sourceRequirements.isEmpty()) {
            sb.append("【必须优先命中的来源】\n");
            for (WorkflowSearchSourceRequirement requirement : contract.sourceRequirements) {
                sb.append("- ").append(requirement.label);
                if (StringUtils.hasText(requirement.domain)) {
                    sb.append(" (").append(requirement.domain).append(")");
                }
                if (requirement.required) {
                    sb.append(" [必需]");
                }
                if (requirement.focusTerms != null && !requirement.focusTerms.isEmpty()) {
                    sb.append("：").append(String.join("、", requirement.focusTerms));
                }
                sb.append("\n");
            }
        }
        if (contract != null && !contract.requiredFields.isEmpty()) {
            sb.append(contract.strict ? "【必须采集的字段】" : "【重点关注字段】")
                    .append(String.join("、", contract.requiredFields))
                    .append("\n");
        }
        if (contract != null && StringUtils.hasText(contract.note)) {
            sb.append("【搜索契约说明】").append(contract.note).append("\n");
        }
        if (agentPlan != null && agentPlan.hasQueries()) {
            sb.append("【模型搜索计划】\n");
            for (String query : agentPlan.queries) {
                sb.append("- ").append(query).append("\n");
            }
            if (!agentPlan.preferredSources.isEmpty()) {
                sb.append("计划优先来源：").append(String.join("、", agentPlan.preferredSources)).append("\n");
            }
            if (StringUtils.hasText(agentPlan.note)) {
                sb.append("计划说明：").append(agentPlan.note).append("\n");
            }
        }
        sb.append("【执行要求】\n");
        if (contract != null && contract.strict) {
            sb.append("1. 当前 prompt 明确设置了硬性搜索约束，必须优先覆盖上面标注的必需来源和字段。\n");
            sb.append("2. 如果存在多个必需来源，请尽量分别搜索；每次 query 最好只聚焦一个来源或一组非常接近的字段。\n");
            sb.append("3. 如必需来源无法命中，继续换一种贴近该来源的搜索词再试，不要直接跳过该来源。\n");
            sb.append("4. 最多使用3次 web_search，但应优先覆盖全部必需来源。\n");
        } else {
            sb.append("1. 当前 prompt 未设置硬性命中约束，请自行制定搜索计划，优先参考上面的来源和字段偏好。\n");
            sb.append("2. 未命中偏好来源或字段时只需如实记录缺口，不要因此阻塞节点，也不要编造。\n");
            sb.append("3. 最多使用3次 web_search，优先让 query 服务于当前节点要回答的问题。\n");
        }
        sb.append("5. 仅返回极简总结：命中了哪些来源、缺了哪些来源、还缺哪些字段。\n");
        sb.append("6. 不要给投资建议，不要输出长分析，不要编造未命中的来源。\n");
        return sb.toString();
    }

    private boolean isWorkflowSearchSatisfied(String strategy,
                                              WorkflowSearchContract contract,
                                              List<WebSearchService.SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "REQUIRED".equalsIgnoreCase(strategy)
                    && (contract == null || !contract.strict);
        }
        if (!"REQUIRED".equalsIgnoreCase(strategy)) {
            return true;
        }
        if (contract == null || !contract.strict) {
            return true;
        }
        return !StringUtils.hasText(validateWorkflowRequiredSearchCoverage(contract, results));
    }

    private void mergeBuiltInTraceIntoMeta(WorkflowStageSearchTrace builtInTrace, WebSearchService.SearchMeta meta) {
        if (builtInTrace == null || meta == null) {
            return;
        }
        meta.provider = StringUtils.hasText(meta.provider)
                ? "deepseek_builtin_search+" + meta.provider
                : "deepseek_builtin_search";
        if (builtInTrace.meta != null && builtInTrace.meta.providerDiagnostics != null) {
            meta.providerDiagnostics.addAll(0, builtInTrace.meta.providerDiagnostics);
        }
        meta.providerDiagnostics.add(0, "已先使用 DeepSeek built-in search 做定向搜索规划与执行。");
    }

    private List<String> dedupeOrderedStrings(List<String> values) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    seen.add(value.trim());
                }
            }
        }
        return new ArrayList<>(seen);
    }

    private void appendWorkflowSearchContext(StringBuilder sb, WorkflowStageSearchTrace trace) {
        if (trace == null) {
            return;
        }
        sb.append("【联网搜索策略】").append(resolveSearchStrategyLabel(trace.strategy)).append("\n");
        if (trace.contract != null) {
            if (!trace.contract.sourceRequirements.isEmpty()) {
                sb.append(trace.contract.strict ? "【硬性来源】" : "【来源偏好】")
                        .append(trace.contract.sourceRequirements.stream()
                                .map(item -> item.required ? item.label + "(必需)" : item.label)
                                .collect(Collectors.joining("；")))
                        .append("\n");
            }
            if (!trace.contract.requiredFields.isEmpty()) {
                sb.append(trace.contract.strict ? "【要求字段】" : "【关注字段】")
                        .append(String.join("；", trace.contract.requiredFields))
                        .append("\n");
            }
            if (StringUtils.hasText(trace.contract.note)) {
                sb.append("【搜索契约说明】").append(trace.contract.note).append("\n");
            }
        }
        if (!trace.attempted) {
            if (StringUtils.hasText(trace.note)) {
                sb.append("【联网搜索状态】").append(trace.note).append("\n");
            }
            return;
        }
        if (!trace.queries.isEmpty()) {
            sb.append("【本轮搜索词】").append(String.join("；", trace.queries)).append("\n");
        }
        if (trace.meta != null) {
            sb.append("【联网搜索状态】")
                    .append(firstNonBlank(trace.meta.status, "unknown"));
            if (StringUtils.hasText(trace.meta.provider)) {
                sb.append(" / ").append(trace.meta.provider);
            }
            if (StringUtils.hasText(trace.meta.note)) {
                sb.append("：").append(trace.meta.note.trim());
            }
            sb.append("\n");
            if (trace.meta.providerDiagnostics != null && !trace.meta.providerDiagnostics.isEmpty()) {
                sb.append("【Provider 诊断】").append(String.join("；", trace.meta.providerDiagnostics)).append("\n");
            }
        }
        if (trace.digest != null && StringUtils.hasText(trace.digest.summary)) {
            sb.append("【联网研究摘要】").append(trace.digest.summary.trim()).append("\n");
            if (StringUtils.hasText(trace.digest.verificationNote)) {
                sb.append("【交叉验证】").append(trace.digest.verificationNote.trim()).append("\n");
            }
            if (StringUtils.hasText(trace.digest.freshnessNote)) {
                sb.append("【时效说明】").append(trace.digest.freshnessNote.trim()).append("\n");
            }
        } else if (!trace.results.isEmpty()) {
            sb.append("【联网搜索结果】\n").append(webSearch.formatResultsForPrompt(trace.results)).append("\n");
        }
    }

    private List<String> buildWorkflowStageSearchQueries(AiMeetingRoom room,
                                                         AiWorkflowStageTemplate stageTemplate,
                                                         AiMeetingMember member,
                                                         WorkflowSearchContract contract,
                                                         WorkflowSearchPlan agentPlan) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (agentPlan != null && agentPlan.hasQueries()) {
            queries.addAll(agentPlan.queries.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .collect(Collectors.toList()));
        }
        String topic = room != null && StringUtils.hasText(room.getTitle()) ? room.getTitle().trim() : stageTemplate.getStageName();
        String subject = extractWorkflowPrimarySubject(topic);
        String slot = StringUtils.hasText(stageTemplate.getActorSlotKey()) ? stageTemplate.getActorSlotKey().trim() : null;
        String persona = StringUtils.hasText(member.getPersona()) ? member.getPersona().trim() : "";
        String focus = buildWorkflowSearchFocusTerms(stageTemplate, slot, persona, member.getMemberName());
        if (contract != null && !contract.sourceRequirements.isEmpty()) {
            queries.addAll(buildWorkflowContractQueries(subject, topic, focus, contract, 4));
            return limitWorkflowQueries(queries, 6);
        }
        queries.add((topic + " 最新").trim());
        if (StringUtils.hasText(focus)) {
            queries.add((topic + " " + focus + " 最新").trim());
        }
        if ("ANALYSIS".equalsIgnoreCase(stageTemplate.getStageType())) {
            queries.add((topic + " 核心数据 机构观点 风险点").trim());
        } else if ("TRACKING".equalsIgnoreCase(stageTemplate.getStageType())) {
            queries.add((topic + " 最新进展 公告 风险").trim());
        } else if ("DEBATE".equalsIgnoreCase(stageTemplate.getStageType())) {
            queries.add((topic + " 反方 风险 证据").trim());
        }
        List<String> baseQueries = queries.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(item -> item.length() > 1)
                .collect(Collectors.toList());
        List<String> sourceFocus = agentPlan != null && !agentPlan.preferredSources.isEmpty()
                ? agentPlan.preferredSources
                : contract != null && !contract.sourceRequirements.isEmpty()
                ? contract.sourceRequirements.stream().map(item -> item.domain).collect(Collectors.toList())
                : new ArrayList<>();
        queries.addAll(buildWorkflowSourceAwareQueries(baseQueries, sourceFocus, 3));
        return limitWorkflowQueries(queries, 6);
    }

    private List<String> limitWorkflowQueries(LinkedHashSet<String> queries, int maxQueries) {
        List<String> result = queries.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(item -> item.length() > 1)
                .distinct()
                .collect(Collectors.toList());
        return result.size() > maxQueries ? new ArrayList<>(result.subList(0, maxQueries)) : result;
    }

    private List<String> buildWorkflowContractQueries(String subject,
                                                      String topic,
                                                      String focus,
                                                      WorkflowSearchContract contract,
                                                      int maxQueries) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String normalizedSubject = StringUtils.hasText(subject) ? subject.trim() : firstNonBlank(topic, "目标主题");
        List<String> fieldTerms = contract.requiredFields.stream()
                .filter(StringUtils::hasText)
                .limit(6)
                .collect(Collectors.toList());
        List<String> genericFallbacks = new ArrayList<>();
        for (WorkflowSearchSourceRequirement requirement : contract.sourceRequirements) {
            List<String> terms = new ArrayList<>();
            terms.add(normalizedSubject);
            if (StringUtils.hasText(requirement.label)) {
                terms.add(requirement.label);
            }
            if (!fieldTerms.isEmpty()) {
                terms.add(String.join(" ", fieldTerms));
            }
            if (requirement.focusTerms != null && !requirement.focusTerms.isEmpty()) {
                terms.add(String.join(" ", requirement.focusTerms.stream().limit(4).collect(Collectors.toList())));
            }
            if (StringUtils.hasText(focus)) {
                terms.add(focus);
            }
            String base = terms.stream().filter(StringUtils::hasText).collect(Collectors.joining(" ")).trim();
            if (StringUtils.hasText(requirement.domain)) {
                ordered.add(base + " site:" + requirement.domain);
            }
            if (StringUtils.hasText(base)) {
                genericFallbacks.add(base);
            }
        }
        for (String base : genericFallbacks) {
            if (ordered.size() >= maxQueries) {
                break;
            }
            ordered.add(base);
        }
        if (ordered.isEmpty()) {
            ordered.add(normalizedSubject + " 最新");
        }
        List<String> result = new ArrayList<>(ordered);
        return result.size() > maxQueries ? new ArrayList<>(result.subList(0, maxQueries)) : result;
    }

    private String buildWorkflowSearchFocusTerms(AiWorkflowStageTemplate stageTemplate,
                                                 String slot,
                                                 String persona,
                                                 String memberName) {
        List<String> tokens = new ArrayList<>();
        String combined = String.join(" ",
                firstNonBlank(slot, ""),
                firstNonBlank(memberName, ""),
                firstNonBlank(persona, ""),
                firstNonBlank(stageTemplate.getSkillKey(), ""),
                firstNonBlank(stageTemplate.getStageName(), "")
        ).toLowerCase();
        if (combined.contains("技术") || combined.contains("quant")) {
            tokens.add("技术面");
            tokens.add("趋势");
            tokens.add("波动率");
        }
        if (combined.contains("价值") || combined.contains("财务") || combined.contains("fundamental")) {
            tokens.add("财报");
            tokens.add("估值");
            tokens.add("现金流");
        }
        if (combined.contains("行业") || combined.contains("轮动")) {
            tokens.add("行业景气");
            tokens.add("资金流向");
            tokens.add("需求");
        }
        if (combined.contains("审核") || combined.contains("review")) {
            tokens.add("证据");
            tokens.add("数据核验");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>(tokens);
        return String.join(" ", normalized);
    }

    private List<String> buildWorkflowSourceAwareQueries(List<String> baseQueries,
                                                         List<String> sourceFocus,
                                                         int maxQueries) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        List<String> normalizedSources = normalizeWorkflowSourceFocus(sourceFocus);
        int effectiveMax = Math.max(1, maxQueries);
        for (String rawQuery : baseQueries) {
            if (!StringUtils.hasText(rawQuery)) {
                continue;
            }
            String query = rawQuery.trim();
            if (!normalizedSources.isEmpty()) {
                ordered.add(query + " site:" + normalizedSources.get(0));
            }
            ordered.add(query);
            for (int i = 1; i < normalizedSources.size() && ordered.size() < effectiveMax; i++) {
                ordered.add(query + " site:" + normalizedSources.get(i));
            }
            if (ordered.size() >= effectiveMax) {
                break;
            }
        }
        List<String> result = new ArrayList<>(ordered);
        return result.size() > effectiveMax ? new ArrayList<>(result.subList(0, effectiveMax)) : result;
    }

    private List<String> normalizeWorkflowSourceFocus(List<String> sourceFocus) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (sourceFocus == null) {
            return new ArrayList<>();
        }
        for (String source : sourceFocus) {
            if (!StringUtils.hasText(source)) {
                continue;
            }
            String item = source.trim().toLowerCase();
            if (item.startsWith("site:")) {
                item = item.substring(5);
            }
            if (item.startsWith("http://")) {
                item = item.substring(7);
            } else if (item.startsWith("https://")) {
                item = item.substring(8);
            }
            while (item.startsWith("www.")) {
                item = item.substring(4);
            }
            int slash = item.indexOf('/');
            if (slash > 0) {
                item = item.substring(0, slash);
            }
            if (item.contains(".")) {
                normalized.add(item);
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<WebSearchService.SearchResult> rankWorkflowSearchResults(AiMeetingRoom room,
                                                                          AiWorkflowStageTemplate stageTemplate,
                                                                          AiMeetingMember member,
                                                                          List<WebSearchService.SearchResult> results,
                                                                          WorkflowSearchPlan agentPlan) {
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> preferredSources = new ArrayList<>();
        if (agentPlan != null && !agentPlan.preferredSources.isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(agentPlan.preferredSources);
            merged.addAll(preferredSources);
            preferredSources = new ArrayList<>(merged);
        }
        final List<String> rankedPreferredSources = preferredSources;
        List<WebSearchService.SearchResult> ranked = new ArrayList<>(results);
        ranked.sort(Comparator
                .comparingInt((WebSearchService.SearchResult result) ->
                        workflowSearchResultScore(result, room, stageTemplate, rankedPreferredSources))
                .reversed()
                .thenComparing(result -> firstNonBlank(result.source, result.url, ""), Comparator.nullsLast(String::compareTo)));
        return ranked;
    }

    private List<WebSearchService.SearchResult> dedupeWorkflowSearchResults(List<WebSearchService.SearchResult> results, int maxResults) {
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<WebSearchService.SearchResult> deduped = new ArrayList<>();
        for (WebSearchService.SearchResult result : results) {
            if (result == null) continue;
            String key = firstNonBlank(result.url, result.source + "|" + result.snippet);
            if (!seen.add(key)) continue;
            deduped.add(result);
            if (deduped.size() >= maxResults) {
                break;
            }
        }
        return deduped;
    }

    private int workflowSearchResultScore(WebSearchService.SearchResult result,
                                          AiMeetingRoom room,
                                          AiWorkflowStageTemplate stageTemplate,
                                          List<String> preferredSources) {
        if (result == null) {
            return Integer.MIN_VALUE;
        }
        int score = workflowSourceQualityScore(result);
        String domain = extractDomain(result.url);
        String subject = extractWorkflowPrimarySubject(room != null ? room.getTitle() : null);
        String text = String.join(" ",
                firstNonBlank(result.source, ""),
                firstNonBlank(result.snippet, ""),
                firstNonBlank(result.url, ""),
                firstNonBlank(room != null ? room.getTitle() : null, ""),
                firstNonBlank(stageTemplate != null ? stageTemplate.getStageName() : null, "")
        ).toLowerCase();
        if (matchesWorkflowPreferredSource(domain, preferredSources)) {
            score += 5;
        }
        if (StringUtils.hasText(subject)) {
            if (text.contains(subject.toLowerCase())) {
                score += 8;
            }
        }
        if (StringUtils.hasText(result.url)) {
            score += 1;
        }
        if (StringUtils.hasText(result.snippet) && result.snippet.trim().length() >= 40) {
            score += 1;
        }
        if (containsAny(text, "公告", "财报", "年报", "季报", "官方", "数据", "research", "report", "forecast", "机构")) {
            score += 2;
        }
        if (containsAny(text, "百家号", "彻夜难眠", "公众号", "博客", "论坛", "股民")) {
            score -= 4;
        }
        return score;
    }

    private int workflowSourceQualityScore(WebSearchService.SearchResult result) {
        if (result == null) {
            return 0;
        }
        String domain = extractDomain(result.url);
        String sourceText = String.join(" ",
                firstNonBlank(result.source, ""),
                firstNonBlank(domain, "")
        ).toLowerCase();
        if (domain != null && containsAny(domain, "gov.cn", "stats.gov.cn", "pbc.gov.cn", "mofcom.gov.cn", "cninfo.com.cn")) {
            return 6;
        }
        if (domain != null && containsAny(domain, "reuters.com", "bloomberg.com", "wsj.com", "gold.org", "cmegroup.com", "lbma.org.uk", "who.int", "fda.gov")) {
            return 5;
        }
        if (domain != null && containsAny(domain, "eastmoney.com", "10jqka.com.cn", "finance.sina.com.cn", "stcn.com", "cls.cn", "investing.com", "tradingeconomics.com")) {
            return 4;
        }
        if (containsAny(sourceText, "搜狐", "百家号", "小红书", "股民", "彻夜难眠", "公众号", "博客", "论坛")) {
            return -3;
        }
        if (domain != null && containsAny(domain, "sohu.com", "weibo.com", "zhihu.com")) {
            return -2;
        }
        return 0;
    }

    private boolean matchesWorkflowPreferredSource(String domain, List<String> preferredSources) {
        if (!StringUtils.hasText(domain) || preferredSources == null || preferredSources.isEmpty()) {
            return false;
        }
        for (String preferred : normalizeWorkflowSourceFocus(preferredSources)) {
            if (domain.equals(preferred) || domain.endsWith("." + preferred)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRequiredWorkflowSearchFailure(AiWorkflowStageTemplate stageTemplate, WorkflowStageSearchTrace trace) {
        return trace != null
                && "REQUIRED".equalsIgnoreCase(resolveSearchStrategy(stageTemplate))
                && trace.contract != null
                && trace.contract.strict
                && StringUtils.hasText(validateWorkflowRequiredSearchCoverage(trace.contract, trace.results));
    }

    private String buildWorkflowRequiredSearchFailureReason(AiMeetingMember member, WorkflowStageSearchTrace trace) {
        String actor = member != null && StringUtils.hasText(member.getMemberName()) ? member.getMemberName() : "当前节点";
        String reason = trace != null
                ? firstNonBlank(trace.note, trace.meta != null ? trace.meta.note : null, trace.meta != null ? trace.meta.status : null)
                : null;
        if (!StringUtils.hasText(reason)) {
            reason = "未获得可用联网搜索结果";
        }
        return "【" + actor + "】当前节点要求联网搜索，但" + reason;
    }

    private String buildAutomatedStageGenerationFailureReason(List<WorkflowStageSearchTrace> searchTraces) {
        if (!hasAttemptedSearchTrace(searchTraces)) {
            return "自动节点未生成有效输出";
        }
        List<String> diagnostics = new ArrayList<>();
        for (WorkflowStageSearchTrace trace : searchTraces) {
            if (trace == null) {
                continue;
            }
            if (trace.meta != null && StringUtils.hasText(trace.meta.note)) {
                diagnostics.add(trace.meta.note);
            }
            if (trace.meta != null && trace.meta.providerDiagnostics != null) {
                diagnostics.addAll(trace.meta.providerDiagnostics.stream()
                        .filter(StringUtils::hasText)
                        .collect(Collectors.toList()));
            }
        }
        if (!diagnostics.isEmpty()) {
            return "当前节点已执行联网搜索，但后续生成阶段未返回稳定正文：" + diagnostics.get(0);
        }
        return "当前节点已执行联网搜索，但后续生成阶段未返回稳定正文。";
    }

    private boolean hasAttemptedSearchTrace(List<WorkflowStageSearchTrace> searchTraces) {
        return searchTraces != null && searchTraces.stream().anyMatch(trace -> trace != null && trace.attempted);
    }

    private boolean shouldUseWorkflowWebFullResearchOutput(AiWorkflowStageTemplate stageTemplate,
                                                           WorkflowStageSearchTrace searchTrace) {
        if (stageTemplate == null || searchTrace == null || searchTrace.meta == null) {
            return false;
        }
        if (!"deepseek_web_bridge".equalsIgnoreCase(searchTrace.meta.provider)) {
            return false;
        }
        String stageType = stageTemplate.getStageType();
        if (!("ANALYSIS".equalsIgnoreCase(stageType)
                || "DEBATE".equalsIgnoreCase(stageType)
                || "TRACKING".equalsIgnoreCase(stageType))) {
            return false;
        }
        String contentStatus = firstNonBlank(searchTrace.meta.contentStatus, searchTrace.meta.status);
        if ("link_only".equalsIgnoreCase(contentStatus) || "empty".equalsIgnoreCase(contentStatus)) {
            return false;
        }
        String content = extractWorkflowWebFullResearchContent(searchTrace);
        return content != null && content.trim().length() >= 160;
    }

    private String extractWorkflowWebFullResearchContent(WorkflowStageSearchTrace searchTrace) {
        if (searchTrace == null || searchTrace.results == null || searchTrace.results.isEmpty()) {
            return null;
        }
        for (WebSearchService.SearchResult result : searchTrace.results) {
            if (result == null || !StringUtils.hasText(result.snippet)) {
                continue;
            }
            if ("DeepSeek 网页智能搜索摘要".equals(result.source)) {
                return sanitizeWorkflowModelResponse(result.snippet);
            }
        }
        WebSearchService.SearchResult first = searchTrace.results.get(0);
        return first != null && StringUtils.hasText(first.snippet)
                ? sanitizeWorkflowModelResponse(first.snippet)
                : null;
    }

    private boolean shouldTreatAutomatedStageOutputAsBlocked(AiWorkflowStageTemplate stageTemplate,
                                                             AutoStageExecutionResult result) {
        if (stageTemplate == null || result == null || !StringUtils.hasText(result.finalContent)) {
            return false;
        }
        if (!"REQUIRED".equalsIgnoreCase(resolveSearchStrategy(stageTemplate))) {
            return false;
        }
        boolean hasSearchTrace = result.searchTraces != null && !result.searchTraces.isEmpty();
        if (!hasSearchTrace) {
            return false;
        }
        boolean hasStrictSearchContract = result.searchTraces.stream().anyMatch(trace -> trace != null
                && trace.contract != null
                && trace.contract.strict);
        if (!hasStrictSearchContract) {
            return false;
        }
        boolean looksBlocked = looksLikeBlockingStageOutput(result.finalContent);
        boolean hasSearchIssue = result.searchTraces.stream().anyMatch(trace -> trace != null
                && trace.contract != null
                && trace.contract.strict
                && StringUtils.hasText(validateWorkflowRequiredSearchCoverage(trace.contract, trace.results)));
        return looksBlocked || hasSearchIssue;
    }

    private boolean looksLikeBlockingStageOutput(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String normalized = content.replace('\u3000', ' ')
                .replace("\r", "")
                .replace("**", "")
                .replace("：", ":")
                .toLowerCase();
        if (normalized.contains("阻塞")
                && (normalized.contains("缺失")
                || normalized.contains("无法生成")
                || normalized.contains("无法完成")
                || normalized.contains("未完成"))) {
            return true;
        }
        List<String> keywords = Arrays.asList(
                "节点阻塞",
                "当前无法生成",
                "无法完成",
                "无法对",
                "无法给出",
                "待实时数据补充",
                "需人工补采",
                "数据核验声明",
                "关键字段缺失",
                "来源核验未通过",
                "字段核验未通过"
        );
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String summarizeBlockingStageOutput(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = content.replace("\r", "").trim();
        int split = normalized.indexOf('\n');
        if (split > 0) {
            normalized = normalized.substring(0, split).trim();
        }
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    private String extractDomain(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return null;
            }
            String normalized = host.toLowerCase();
            while (normalized.startsWith("www.")) {
                normalized = normalized.substring(4);
            }
            return normalized;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text) || needles == null) {
            return false;
        }
        String normalized = text.toLowerCase();
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && normalized.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String buildWorkflowSearchBackground(AiMeetingRoom room) {
        if (room == null) {
            return null;
        }
        return String.join("\n",
                firstNonBlank(room.getDescription(), ""),
                firstNonBlank(room.getProjectContext(), ""),
                firstNonBlank(room.getInheritedContext(), "")
        ).trim();
    }

    private WorkflowSearchContract buildWorkflowSearchContract(AiMeetingRoom room,
                                                               AiWorkflowStageTemplate stageTemplate,
                                                               AiMeetingMember member,
                                                               String strategy) {
        String promptText = String.join("\n",
                firstNonBlank(room != null ? room.getTitle() : null, ""),
                firstNonBlank(room != null ? room.getDescription() : null, ""),
                firstNonBlank(room != null ? room.getProjectContext() : null, ""),
                firstNonBlank(room != null ? room.getInheritedContext() : null, ""),
                firstNonBlank(stageTemplate != null ? stageTemplate.getStageName() : null, ""),
                firstNonBlank(stageTemplate != null ? stageTemplate.getSkillPrompt() : null, "")
        ).trim();
        String guidanceText = String.join("\n",
                promptText,
                firstNonBlank(member != null ? member.getMemberName() : null, ""),
                firstNonBlank(member != null ? member.getPersona() : null, "")
        ).trim();
        boolean strict = hasExplicitWorkflowSearchHardConstraint(promptText);
        List<WorkflowSearchSourceRequirement> sources = extractWorkflowSearchSources(promptText, strict);
        List<String> fields = extractWorkflowRequiredFields(guidanceText);
        String note = null;
        if (!sources.isEmpty() || !fields.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (!sources.isEmpty()) {
                parts.add((strict ? "必需来源：" : "优先来源：")
                        + sources.stream().map(item -> item.label).collect(Collectors.joining("、")));
            }
            if (!fields.isEmpty()) {
                parts.add("重点字段：" + String.join("、", fields));
            }
            if (strict) {
                parts.add("未满足要求时阻塞节点");
            }
            note = String.join("；", parts);
        }
        return new WorkflowSearchContract(strict, note, sources, fields);
    }

    private boolean hasExplicitWorkflowSearchHardConstraint(String text) {
        return containsAny(text,
                "必须命中", "必须采集", "必须获取", "必须包含", "必须引用",
                "强制实时", "强制采集", "强制搜索", "缺失则阻塞",
                "未满足要求时阻塞", "不得输出", "否则打回", "否则阻塞",
                "绝对红线");
    }

    private List<WorkflowSearchSourceRequirement> extractWorkflowSearchSources(String text, boolean strict) {
        LinkedHashMap<String, WorkflowSearchSourceRequirement> sources = new LinkedHashMap<>();
        registerWorkflowSourceRequirement(sources, text, strict,
                "百度财经", "finance.baidu.com",
                Arrays.asList("百度财经", "finance.baidu.com"),
                Arrays.asList("股价", "PE", "PB", "总市值", "换手率"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "东方财富", "eastmoney.com",
                Arrays.asList("东方财富", "东财", "eastmoney"),
                Arrays.asList("股价", "PE", "PB", "股息率", "北向资金", "融资余额"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "同花顺", "10jqka.com.cn",
                Arrays.asList("同花顺", "10jqka"),
                Arrays.asList("股价", "估值", "资金流向", "技术面"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "新浪财经", "finance.sina.com.cn",
                Arrays.asList("新浪财经", "finance.sina.com.cn"),
                Arrays.asList("股价", "PE", "PB", "公告"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "巨潮资讯", "cninfo.com.cn",
                Arrays.asList("巨潮资讯", "cninfo", "cninfo.com.cn"),
                Arrays.asList("年报", "季报", "公告", "PDF"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "国家统计局", "stats.gov.cn",
                Arrays.asList("国家统计局", "stats.gov.cn", "统计局"),
                Arrays.asList("CPI", "PPI", "PMI", "工业增加值"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "人民银行", "pbc.gov.cn",
                Arrays.asList("人民银行", "央行", "pbc.gov.cn"),
                Arrays.asList("利率", "社融", "汇率"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "商务部", "mofcom.gov.cn",
                Arrays.asList("商务部", "mofcom.gov.cn"),
                Arrays.asList("出口", "进口", "关税"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "世界黄金协会", "gold.org",
                Arrays.asList("世界黄金协会", "gold.org"),
                Arrays.asList("黄金", "ETF", "储备"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "LBMA", "lbma.org.uk",
                Arrays.asList("lbma", "伦敦金银市场协会"),
                Arrays.asList("金价", "现货金", "定盘价"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "CME", "cmegroup.com",
                Arrays.asList("cme", "comex", "cmegroup"),
                Arrays.asList("期货", "持仓", "金价"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "路透", "reuters.com",
                Arrays.asList("reuters", "路透"),
                Arrays.asList("新闻", "数据", "事件"));
        registerWorkflowSourceRequirement(sources, text, strict,
                "彭博", "bloomberg.com",
                Arrays.asList("bloomberg", "彭博"),
                Arrays.asList("估值", "市场", "新闻"));
        return new ArrayList<>(sources.values());
    }

    private void registerWorkflowSourceRequirement(Map<String, WorkflowSearchSourceRequirement> sink,
                                                   String text,
                                                   boolean strict,
                                                   String label,
                                                   String domain,
                                                   List<String> aliases,
                                                   List<String> focusTerms) {
        if (!containsAny(text, aliases.toArray(new String[0]))) {
            return;
        }
        sink.putIfAbsent(domain, new WorkflowSearchSourceRequirement(label, domain, strict, focusTerms));
    }


    private List<String> sanitizeWorkflowPlanList(List<String> items, int maxItems, boolean queries) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : items) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String text = item.trim().replaceAll("\\s+", " ");
            if (text.length() < 2) {
                continue;
            }
            if (queries && text.length() > 160) {
                text = text.substring(0, 160).trim();
            }
            normalized.add(text);
            if (normalized.size() >= maxItems) {
                break;
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> extractWorkflowRequiredFields(String text) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        registerWorkflowField(fields, text, "当前股价", "股价", "现价", "最新价", "收盘价");
        registerWorkflowField(fields, text, "PE(TTM)", "pe(ttm)", "pe ttm", "市盈率", "pe");
        registerWorkflowField(fields, text, "PB", "市净率", "pb");
        registerWorkflowField(fields, text, "股息率", "股息率", "分红率");
        registerWorkflowField(fields, text, "总市值", "总市值", "流通市值", "市值");
        registerWorkflowField(fields, text, "北向资金", "北向资金", "陆股通", "沪股通", "深股通");
        registerWorkflowField(fields, text, "融资余额", "融资余额", "两融", "融资买入");
        registerWorkflowField(fields, text, "现金流", "现金流", "自由现金流", "经营现金流");
        registerWorkflowField(fields, text, "分红", "分红", "派息", "回购");
        registerWorkflowField(fields, text, "订单/装机/市占率", "订单", "装机", "市占率", "渗透率");
        registerWorkflowField(fields, text, "公告/年报/季报", "公告", "年报", "季报", "pdf");
        return new ArrayList<>(fields);
    }

    private void registerWorkflowField(Set<String> fields, String text, String canonical, String... aliases) {
        if (containsAny(text, aliases)) {
            fields.add(canonical);
        }
    }

    private String validateWorkflowRequiredSearchCoverage(WorkflowSearchContract contract,
                                                          List<WebSearchService.SearchResult> results) {
        if (contract == null || !contract.strict) {
            return null;
        }
        if (results == null || results.isEmpty()) {
            return "当前节点要求强制实时数据采集，但本轮未获得任何可用搜索结果。";
        }
        if (!contract.sourceRequirements.isEmpty()) {
            List<String> missingSources = new ArrayList<>();
            for (WorkflowSearchSourceRequirement requirement : contract.sourceRequirements) {
                if (!requirement.required) {
                    continue;
                }
                boolean matched = results.stream().anyMatch(result -> matchesWorkflowSearchSource(result, requirement));
                if (!matched) {
                    missingSources.add(requirement.label);
                }
            }
            if (!missingSources.isEmpty()) {
                return "当前节点要求命中指定来源，但以下来源未命中：" + String.join("、", missingSources) + "。";
            }
        }
        if (!contract.requiredFields.isEmpty()) {
            List<WebSearchService.SearchResult> fieldCandidateResults = results;
            if (!contract.sourceRequirements.isEmpty()) {
                fieldCandidateResults = results.stream()
                        .filter(result -> contract.sourceRequirements.stream()
                                .filter(requirement -> requirement.required)
                                .anyMatch(requirement -> matchesWorkflowSearchSource(result, requirement)))
                        .collect(Collectors.toList());
                if (fieldCandidateResults.isEmpty()) {
                    return "当前节点要求采集关键字段，但命中必需来源的结果中未发现任何可用字段载体。";
                }
            }
            List<String> missingFields = new ArrayList<>();
            for (String field : contract.requiredFields) {
                if (!matchesWorkflowRequiredField(field, fieldCandidateResults)) {
                    missingFields.add(field);
                }
            }
            if (!missingFields.isEmpty()) {
                return "当前节点要求采集关键字段，但本轮结果仍缺失这些字段：" + String.join("、", missingFields) + "。";
            }
        }
        return null;
    }

    private boolean matchesWorkflowRequiredField(String field,
                                                List<WebSearchService.SearchResult> results) {
        if (!StringUtils.hasText(field) || results == null || results.isEmpty()) {
            return false;
        }
        List<String> aliases = workflowFieldAliases(field);
        for (WebSearchService.SearchResult result : results) {
            String text = String.join(" ",
                    firstNonBlank(result.source, ""),
                    firstNonBlank(result.snippet, ""),
                    firstNonBlank(result.url, "")).toLowerCase();
            boolean matched = aliases.stream().anyMatch(alias -> StringUtils.hasText(alias) && text.contains(alias.toLowerCase()));
            if (!matched) {
                continue;
            }
            if (requiresNumericPayload(field) && !containsFieldNumericPayload(text, aliases)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private List<String> workflowFieldAliases(String field) {
        String normalized = field.trim().toLowerCase();
        if (normalized.contains("当前股价") || normalized.contains("最新价")) {
            return Arrays.asList("当前股价", "最新价", "现价", "股价", "行情");
        }
        if (normalized.contains("pe")) {
            return Arrays.asList("pe(ttm)", "市盈率", "pe");
        }
        if ("pb".equalsIgnoreCase(field) || normalized.contains("pb")) {
            return Arrays.asList("pb", "市净率");
        }
        if (normalized.contains("股息率")) {
            return Arrays.asList("股息率", "dividend yield");
        }
        if (normalized.contains("总市值")) {
            return Arrays.asList("总市值", "市值", "market cap");
        }
        if (normalized.contains("北向资金")) {
            return Arrays.asList("北向", "北向资金", "陆股通", "沪股通", "深股通");
        }
        if (normalized.contains("融资余额")) {
            return Arrays.asList("融资余额", "融资融券", "融资");
        }
        if (normalized.contains("现金流")) {
            return Arrays.asList("现金流", "经营现金流", "自由现金流");
        }
        if (normalized.contains("分红")) {
            return Arrays.asList("分红", "派息", "股利", "现金分红");
        }
        if (normalized.contains("公告") || normalized.contains("年报") || normalized.contains("季报")) {
            return Arrays.asList("公告", "年报", "季报", "pdf", "cninfo");
        }
        return Arrays.asList(field, field.replace("(TTM)", ""), field.replace("当前", ""));
    }

    private boolean requiresNumericPayload(String field) {
        String normalized = field == null ? "" : field.trim().toLowerCase();
        return containsAny(normalized,
                "当前股价", "最新价", "pe", "pb", "股息率", "总市值", "北向资金", "融资余额", "现金流");
    }

    private boolean containsNumericPayload(String text) {
        return text != null && text.matches(".*\\d+(?:[.,]\\d+)?(?:%|元|亿|万|倍)?(?:\\s|$).*");
    }

    private boolean containsFieldNumericPayload(String text, List<String> aliases) {
        if (!StringUtils.hasText(text) || aliases == null || aliases.isEmpty()) {
            return false;
        }
        for (String alias : aliases) {
            if (!StringUtils.hasText(alias)) {
                continue;
            }
            String escaped = Pattern.quote(alias.toLowerCase());
            if (text.matches("(?s).*" + escaped + "[^\\d]{0,12}\\d+(?:[.,]\\d+)?(?:%|元|亿|万|倍)?.*")) {
                return true;
            }
            if (text.matches("(?s).*\\d+(?:[.,]\\d+)?(?:%|元|亿|万|倍)?[^\\d]{0,12}" + escaped + ".*")) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesWorkflowSearchSource(WebSearchService.SearchResult result,
                                                WorkflowSearchSourceRequirement requirement) {
        if (result == null || requirement == null) {
            return false;
        }
        String domain = extractDomain(result.url);
        String sourceText = String.join(" ",
                firstNonBlank(result.source, ""),
                firstNonBlank(result.url, "")
        ).toLowerCase();
        if (StringUtils.hasText(domain) && domain.contains(requirement.domain)) {
            return true;
        }
        return containsAny(sourceText, requirement.label, requirement.domain);
    }

    private String extractWorkflowPrimarySubject(String title) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        String text = title.trim();
        text = text.replaceAll("（副本）", "").replaceAll("\\(副本\\)", "").trim();
        String stripped = text.replaceAll("(20\\d{2}年?)?(上半年|下半年|全年|年度|一季报|二季报|三季报|四季报)?(价格区间概率分析|价格区间|股价走势预测|股价走势|价格走势|价格预测|走势预测|概率分析|分析|预测|展望|验证|测试).*", "").trim();
        if (StringUtils.hasText(stripped)) {
            return stripped;
        }
        Matcher matcher = Pattern.compile("^([\\u4e00-\\u9fa5A-Za-z0-9·]+)").matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text;
    }

    private Integer extractTargetYear(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = YEAR_PATTERN.matcher(text);
        while (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private WebSearchService.SearchMeta copySearchMeta(WebSearchService.SearchMeta source) {
        if (source == null) {
            return null;
        }
        WebSearchService.SearchMeta copy = new WebSearchService.SearchMeta();
        copy.provider = source.provider;
        copy.status = source.status;
        copy.note = source.note;
        copy.providerDiagnostics.addAll(source.providerDiagnostics);
        copy.digestMode = source.digestMode;
        copy.digestStatus = source.digestStatus;
        copy.digestNote = source.digestNote;
        copy.digestSummaryLength = source.digestSummaryLength;
        copy.digestClaimCount = source.digestClaimCount;
        copy.digestSourceCount = source.digestSourceCount;
        copy.digestTimingMs = source.digestTimingMs;
        copy.queryCount = source.queryCount;
        copy.providerResultCount = source.providerResultCount;
        copy.providerTimingMs = source.providerTimingMs;
        copy.bridgeRoundTripMs = source.bridgeRoundTripMs;
        copy.expertMode = source.expertMode;
        copy.deepThinkEnabled = source.deepThinkEnabled;
        copy.searchEnabled = source.searchEnabled;
        copy.contentStatus = source.contentStatus;
        copy.contentNote = source.contentNote;
        copy.answerSource = source.answerSource;
        copy.waitDurationMs = source.waitDurationMs;
        copy.completionReason = source.completionReason;
        copy.isolatedSession = source.isolatedSession;
        copy.targetId = source.targetId;
        copy.answerLength = source.answerLength;
        copy.newLinkCount = source.newLinkCount;
        copy.candidateBlockCount = source.candidateBlockCount;
        copy.assistantBlockDeltaCount = source.assistantBlockDeltaCount;
        copy.bodyDeltaLength = source.bodyDeltaLength;
        return copy;
    }

    private String sanitizeWorkflowModelResponse(String rawResponse) {
        if (!StringUtils.hasText(rawResponse)) {
            return rawResponse;
        }
        return rawResponse
                .replaceAll("(?s)<｜｜DSML｜｜tool_calls>.*?</｜｜DSML｜｜tool_calls>", " ")
                .replaceAll("(?s)<\\|\\|DSML\\|\\|tool_calls>.*?</\\|\\|DSML\\|\\|tool_calls>", " ")
                .replaceAll("(?s)<｜｜DSML｜｜invoke[^>]*>.*?</｜｜DSML｜｜invoke>", " ")
                .replaceAll("(?s)<\\|\\|DSML\\|\\|invoke[^>]*>.*?</\\|\\|DSML\\|\\|invoke>", " ")
                .replaceAll("(?s)<｜｜DSML｜｜parameter[^>]*>.*?</｜｜DSML｜｜parameter>", " ")
                .replaceAll("(?s)<\\|\\|DSML\\|\\|parameter[^>]*>.*?</\\|\\|DSML\\|\\|parameter>", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String buildAutomatedStageUserPrompt(AiMeetingRoom room,
                                                 AiWorkflowStageRun stageRun,
                                                 AiWorkflowStageTemplate stageTemplate,
                                                 AiMeetingMember member,
                                                 String skillFileContext,
                                                 WorkflowStageSearchTrace searchTrace) {
        StringBuilder sb = new StringBuilder();
        NodeDeliverableContract deliverableContract = buildNodeDeliverableContract(room, stageRun, stageTemplate, member);
        sb.append(deliverableContract.renderForPrompt()).append("\n");
        if (searchTrace != null && Boolean.FALSE.equals(searchTrace.judgementAcceptedAsDeliverable)) {
            sb.append("【上次联网研究产出未通过 deliverable 评判】\n")
                    .append("原因：").append(firstNonBlank(searchTrace.judgementReason, "主体是搜索过程而非节点交付")).append("\n");
            if (searchTrace.judgementMissing != null && !searchTrace.judgementMissing.isEmpty()) {
                sb.append("缺失项：").append(String.join("；", searchTrace.judgementMissing)).append("\n");
            }
            sb.append("你这次必须以\"节点交付\"为正文主体；上一段联网研究只能作为支撑材料引用，不得作为主体复述。\n");
        }
        sb.append("【会议主题】").append(room != null ? room.getTitle() : "").append("\n");
        if (room != null && StringUtils.hasText(room.getDescription())) {
            sb.append("【会议背景】").append(room.getDescription().trim()).append("\n");
        }
        if (room != null && StringUtils.hasText(room.getProjectContext())) {
            sb.append("【项目背景】").append(room.getProjectContext().trim()).append("\n");
        }
        if (room != null && StringUtils.hasText(room.getInheritedContext())) {
            sb.append("【历史项目记忆】").append(room.getInheritedContext().trim()).append("\n");
        }
        sb.append("【当前节点】").append(stageTemplate.getStageName()).append(" (").append(stageTemplate.getStageType()).append(")\n");
        if (StringUtils.hasText(stageTemplate.getActorSlotKey())) {
            sb.append("【执行槽位】").append(stageTemplate.getActorSlotKey()).append("\n");
        }
        if (StringUtils.hasText(stageTemplate.getSkillKey())) {
            sb.append("【Skill】").append(stageTemplate.getSkillKey()).append("\n");
        }
        if (StringUtils.hasText(stageTemplate.getSkillPrompt())) {
            sb.append("【Skill指令】").append(stageTemplate.getSkillPrompt().trim()).append("\n");
        }
        if (StringUtils.hasText(skillFileContext)) {
            sb.append("【Skill文件上下文】\n").append(skillFileContext).append("\n");
        }
        String handoff = deriveAutoStageTransitionContent(stageRun, null);
        if (StringUtils.hasText(handoff)) {
            sb.append("【上一节点交付】").append(handoff).append("\n");
        }
        appendWorkflowSearchContext(sb, searchTrace);
        sb.append("【你的身份】").append(member.getMemberName()).append("\n");
        sb.append("【上下文使用规则】前序观点只是参考，不得直接继承其中的事实结论；关键事实必须基于本轮搜索结果或明确标注为待复核/冲突。\n");
        sb.append("如果没有找到现成答案，不要直接拒绝任务；请转为使用可验证的中间变量、历史区间、相邻事实和情景假设完成推导，并把假设写清楚。\n");
        sb.append("请直接完成当前节点输出，尽量结构化，至少包含：\n");
        sb.append("1. 结论\n2. 关键依据\n3. 风险或分歧\n4. 建议下一步\n");
        sb.append("5. 如本轮联网核验失败或证据不足，必须明确标注未经充分联网核验的部分\n");
        sb.append("不要输出JSON，不要解释你是谁，不要写多余寒暄。");
        return sb.toString();
    }

    private String deriveAutoStageTransitionContent(AiWorkflowStageRun stageRun, String fallback) {
        MeetingDetailVO.HandoffArtifactVO handoff = parseHandoffArtifact(stageRun.getHandoffPayload());
        if (handoff != null) {
            if (StringUtils.hasText(handoff.getContent())) {
                return handoff.getContent();
            }
            if (handoff.getArtifact() != null && StringUtils.hasText(handoff.getArtifact().getDetailText())) {
                return handoff.getArtifact().getDetailText();
            }
            if (handoff.getArtifact() != null && StringUtils.hasText(handoff.getArtifact().getSummary())) {
                return handoff.getArtifact().getSummary();
            }
        }
        if (StringUtils.hasText(stageRun.getHandoffSummary())) {
            return stageRun.getHandoffSummary();
        }
        return fallback;
    }

    // 审核节点的 handoff payload 记录了产出该交付的源节点 stageKey。
    // 解析它并回到模板里取源节点（如分析节点），用于构建"被审核节点的产出契约"。
    private AiWorkflowStageTemplate resolveReviewedSourceStageTemplate(AiWorkflowStageRun reviewStageRun,
                                                                       AiWorkflowStageTemplate reviewStageTemplate) {
        if (reviewStageRun == null) {
            return null;
        }
        MeetingDetailVO.HandoffArtifactVO handoff = parseHandoffArtifact(reviewStageRun.getHandoffPayload());
        String sourceStageKey = handoff != null ? handoff.getSourceStageKey() : null;
        if (!StringUtils.hasText(sourceStageKey)) {
            return null;
        }
        if (reviewStageTemplate != null && Objects.equals(sourceStageKey, reviewStageTemplate.getStageKey())) {
            return null;
        }
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(reviewStageRun.getMeetingId());
        if (run == null || run.getTemplateId() == null) {
            return null;
        }
        return findStageTemplate(run.getTemplateId(), sourceStageKey);
    }

    private boolean shouldStopAfterReviewReject(AiWorkflowStageRun stageRun, AiWorkflowStageTemplate stageTemplate) {
        return stageTemplate.getMaxReviewAttempts() != null
                && stageTemplate.getMaxReviewAttempts() > 0
                && stageRun.getAttemptNo() > stageTemplate.getMaxReviewAttempts();
    }

    private static final int STAGNATION_WINDOW = 3;
    private static final double STAGNATION_JACCARD_THRESHOLD = 0.55;

    // 当前 reject 与前两次的语义距离都很近 → 流程已陷入复读，提前止损。
    // currentRejectComment 是这一轮刚生成、尚未落库的 reject 文本。
    private boolean isRejectionLoopStagnant(AiWorkflowStageRun stageRun, String currentRejectComment) {
        if (stageRun == null || !StringUtils.hasText(currentRejectComment)) {
            return false;
        }
        List<AiWorkflowStageSubmission> all = workflowStageSubmissionMapper.selectByMeetingId(stageRun.getMeetingId());
        if (all == null || all.isEmpty()) {
            return false;
        }
        String stageKey = stageRun.getStageKey();
        List<String> recentRejects = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && recentRejects.size() < STAGNATION_WINDOW - 1; i--) {
            AiWorkflowStageSubmission s = all.get(i);
            if (s == null) continue;
            if (!"REJECT".equalsIgnoreCase(s.getActionType())) continue;
            if (!Objects.equals(stageKey, s.getStageKey())) continue;
            if (!StringUtils.hasText(s.getContent())) continue;
            recentRejects.add(s.getContent());
        }
        if (recentRejects.size() < STAGNATION_WINDOW - 1) {
            return false;
        }
        List<String> window = new ArrayList<>();
        window.add(currentRejectComment);
        window.addAll(recentRejects);
        // 两两比较窗口内的 reject 文本：所有配对都高于阈值才认定复读。
        for (int i = 0; i < window.size(); i++) {
            for (int j = i + 1; j < window.size(); j++) {
                if (bigramJaccard(window.get(i), window.get(j)) < STAGNATION_JACCARD_THRESHOLD) {
                    return false;
                }
            }
        }
        return true;
    }

    private double bigramJaccard(String a, String b) {
        Set<String> ga = charBigrams(a);
        Set<String> gb = charBigrams(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0.0;
        }
        Set<String> intersect = new HashSet<>(ga);
        intersect.retainAll(gb);
        Set<String> union = new HashSet<>(ga);
        union.addAll(gb);
        return union.isEmpty() ? 0.0 : (double) intersect.size() / (double) union.size();
    }

    private Set<String> charBigrams(String text) {
        Set<String> out = new HashSet<>();
        if (!StringUtils.hasText(text)) return out;
        // 归一：去空白、去常见标点后做字符 bigram。
        String normalized = text.replaceAll("[\\s\\p{Punct}，。；：、！？“”‘’【】（）《》—\\-]", "");
        if (normalized.length() < 2) return out;
        for (int i = 0; i + 1 < normalized.length(); i++) {
            out.add(normalized.substring(i, i + 2));
        }
        return out;
    }

    private String buildRejectionStagnantConclusion(AiWorkflowStageRun stageRun,
                                                     AiWorkflowStageTemplate stageTemplate,
                                                     String latestRejectComment) {
        StringBuilder sb = new StringBuilder();
        sb.append("流程在审核节点【").append(stageTemplate.getStageName())
                .append("】检测到连续 ").append(STAGNATION_WINDOW)
                .append(" 次审核意见高度重复，已停止反复打回以避免无效 token 消耗。\n");
        sb.append("当前以最近一次分析输出作为最终交付，需人工复核或调整节点 prompt 后重启。");
        String lastDelivery = deriveAutoStageTransitionContent(stageRun, null);
        if (StringUtils.hasText(latestRejectComment)) {
            sb.append("\n\n最近一次审核意见：\n").append(latestRejectComment.trim());
        }
        if (StringUtils.hasText(lastDelivery)) {
            sb.append("\n\n最近一次待审核版本：\n").append(lastDelivery.trim());
        }
        return sb.toString();
    }

    private String buildReviewLimitConclusion(AiWorkflowStageRun stageRun,
                                              AiWorkflowStageTemplate stageTemplate,
                                              String rejectComment) {
        StringBuilder sb = new StringBuilder();
        sb.append("流程在审核节点【").append(stageTemplate.getStageName()).append("】未能收敛，已超过允许的最大打回次数");
        sb.append(stageTemplate.getMaxReviewAttempts()).append("次，系统自动截止以避免继续消耗 token。");
        String lastDelivery = deriveAutoStageTransitionContent(stageRun, null);
        if (StringUtils.hasText(rejectComment)) {
            sb.append("\n\n最后一次审核意见：\n").append(rejectComment.trim());
        }
        if (StringUtils.hasText(lastDelivery)) {
            sb.append("\n\n最近一次待审核版本：\n").append(lastDelivery.trim());
        }
        return sb.toString();
    }

    private String buildApprovedReviewConclusion(AiWorkflowStageRun stageRun, String reviewComment) {
        String approvedContent = deriveAutoStageTransitionContent(stageRun, null);
        if (!StringUtils.hasText(approvedContent)) {
            return StringUtils.hasText(reviewComment) ? reviewComment.trim() : "流程审核通过并归档完成。";
        }
        if (!StringUtils.hasText(reviewComment)) {
            return approvedContent;
        }
        return approvedContent + "\n\n---\n\n审核通过意见：\n" + reviewComment.trim();
    }

    private AiWorkflowStageRun ensureActiveStageRun(AiWorkflowRun run, String stageKey) {
        AiWorkflowStageRun latest = workflowStageRunMapper.selectLatestByMeetingAndStageKey(run.getMeetingId(), stageKey);
        if (latest == null) {
            AiWorkflowStageTemplate template = findStageTemplate(run.getTemplateId(), stageKey);
            if (template == null) {
                throw new IllegalStateException("流程模板缺少阶段: " + stageKey);
            }
            latest = createStageRun(run, template, 1);
        } else if (!WorkflowStageStatusEnum.PENDING.getCode().equals(latest.getStatus())
                && !WorkflowStageStatusEnum.ACTIVE.getCode().equals(latest.getStatus())) {
            AiWorkflowStageTemplate template = resolveStageTemplate(run, latest);
            latest = createStageRun(run, template, latest.getAttemptNo() + 1);
        }

        if (!WorkflowStageStatusEnum.ACTIVE.getCode().equals(latest.getStatus())) {
            Date startedAt = latest.getStartedAt() != null ? latest.getStartedAt() : new Date();
            workflowStageRunMapper.updateExecution(latest.getId(),
                    WorkflowStageStatusEnum.ACTIVE.getCode(),
                    latest.getHandoffSummary(),
                    latest.getHandoffPayload(),
                    latest.getOutcome(),
                    latest.getRejectReason(),
                    startedAt,
                    null);
            latest.setStatus(WorkflowStageStatusEnum.ACTIVE.getCode());
            latest.setStartedAt(startedAt);
        }
        return latest;
    }

    private AiWorkflowStageRun createStageRun(AiWorkflowRun run, AiWorkflowStageTemplate template, int attemptNo) {
        AiWorkflowStageRun stageRun = new AiWorkflowStageRun();
        stageRun.setWorkflowRunId(run.getId());
        stageRun.setMeetingId(run.getMeetingId());
        stageRun.setStageTemplateId(template.getId());
        stageRun.setStageKey(template.getStageKey());
        stageRun.setStageName(template.getStageName());
        stageRun.setStageOrder(template.getStageOrder());
        stageRun.setStageType(template.getStageType());
        stageRun.setActorType(template.getActorType());
        stageRun.setAttemptNo(attemptNo);
        stageRun.setStatus(WorkflowStageStatusEnum.PENDING.getCode());
        stageRun.setHandoffSummary(null);
        stageRun.setHandoffPayload(null);
        workflowStageRunMapper.insert(stageRun);
        return stageRun;
    }

    private void persistSubmission(AiWorkflowStageRun stageRun,
                                   String actorRef,
                                   String actionType,
                                   String content,
                                   String payloadJson) {
        AiWorkflowStageSubmission submission = new AiWorkflowStageSubmission();
        submission.setWorkflowRunId(stageRun.getWorkflowRunId());
        submission.setStageRunId(stageRun.getId());
        submission.setMeetingId(stageRun.getMeetingId());
        submission.setStageKey(stageRun.getStageKey());
        submission.setAttemptNo(stageRun.getAttemptNo());
        submission.setActionType(actionType);
        submission.setActorRef(StringUtils.hasText(actorRef) ? actorRef.trim() : "HUMAN");
        submission.setContent(content);
        submission.setPayloadJson(StringUtils.hasText(payloadJson)
                ? payloadJson
                : buildStageArtifactPayload(actionType, content));
        workflowStageSubmissionMapper.insert(submission);
    }

    private void completeStageRun(AiWorkflowStageRun stageRun, String outcome, String rejectReason) {
        Date startedAt = stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date();
        workflowStageRunMapper.updateExecution(stageRun.getId(),
                WorkflowStageStatusEnum.COMPLETED.getCode(),
                stageRun.getHandoffSummary(),
                stageRun.getHandoffPayload(),
                outcome,
                rejectReason,
                startedAt,
                new Date());
    }

    private void rejectStageRun(AiWorkflowStageRun stageRun, String rejectReason) {
        Date startedAt = stageRun.getStartedAt() != null ? stageRun.getStartedAt() : new Date();
        workflowStageRunMapper.updateExecution(stageRun.getId(),
                WorkflowStageStatusEnum.REJECTED.getCode(),
                stageRun.getHandoffSummary(),
                stageRun.getHandoffPayload(),
                "REJECTED",
                rejectReason,
                startedAt,
                new Date());
    }

    private void moveToNextStageOrConclude(AiWorkflowRun run,
                                            AiWorkflowStageRun sourceStageRun,
                                            AiWorkflowStageTemplate currentStageTemplate,
                                            String finalContent) {
        if (activateAllReadyDependentOrNextInput(run, sourceStageRun, finalContent, false)) {
            return;
        }
        if (StringUtils.hasText(currentStageTemplate.getPassTarget())) {
            activateTransitionStage(run, sourceStageRun, currentStageTemplate.getPassTarget(), finalContent);
            return;
        }
        if (activateAllReadyDependentOrNextInput(run, sourceStageRun, finalContent, true)) {
            return;
        }
        concludeManualWorkflow(run, finalContent);
    }

    private void activateTransitionStage(AiWorkflowRun run,
                                          AiWorkflowStageRun sourceStageRun,
                                          String targetStageKey,
                                          String transitionNote) {
        if (!StringUtils.hasText(targetStageKey)) {
            concludeManualWorkflow(run, transitionNote);
            return;
        }
        AiWorkflowStageTemplate targetTemplate = findStageTemplate(run.getTemplateId(), targetStageKey);
        if (targetTemplate == null) {
            throw new IllegalStateException("阶段跳转目标不存在: " + targetStageKey);
        }
        if (isAllReadyStage(targetTemplate) && !areAllReadyInputsComplete(run, targetTemplate)) {
            AiWorkflowStageTemplate nextMissingInput = findNextMissingAllReadyInputTemplate(run, targetTemplate);
            if (nextMissingInput != null) {
                updateRunCurrentStage(run.getId(), nextMissingInput.getStageKey(), nextMissingInput.getStageOrder());
                workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(),
                        "ALL_READY 节点 " + targetTemplate.getStageName() + " 等待输入，先执行缺失输入 " + nextMissingInput.getStageName());
                applyHandoffSummary(run, sourceStageRun, nextMissingInput,
                        buildAllReadyWaitingNote(targetTemplate, nextMissingInput, transitionNote));
                activateOrBypassStage(run, nextMissingInput.getStageKey());
                return;
            }
            updateRunCurrentStage(run.getId(), targetTemplate.getStageKey(), targetTemplate.getStageOrder());
            workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(),
                    buildAllReadyWaitingReason(run, targetTemplate));
            applyHandoffSummary(run, sourceStageRun, targetTemplate, buildAllReadyWaitingReason(run, targetTemplate));
            return;
        }
        updateRunCurrentStage(run.getId(), targetTemplate.getStageKey(), targetTemplate.getStageOrder());
        workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(), null);
        if (isAllReadyStage(targetTemplate)) {
            applyAllReadyHandoff(run, targetTemplate);
        } else {
            applyHandoffSummary(run, sourceStageRun, targetTemplate, transitionNote);
        }
        activateOrBypassStage(run, targetTemplate.getStageKey());
    }

    private boolean activateAllReadyDependentOrNextInput(AiWorkflowRun run,
                                                         AiWorkflowStageRun sourceStageRun,
                                                         String transitionNote,
                                                         boolean allowMissingInputActivation) {
        if (run == null || sourceStageRun == null || !StringUtils.hasText(sourceStageRun.getStageKey())) {
            return false;
        }
        List<AiWorkflowStageTemplate> stages = workflowStageTemplateMapper.selectByTemplateId(run.getTemplateId());
        for (AiWorkflowStageTemplate targetTemplate : stages) {
            if (!isAllReadyStage(targetTemplate)) {
                continue;
            }
            List<String> inputKeys = parseInputStageKeys(targetTemplate.getInputStageKeys());
            if (!inputKeys.contains(sourceStageRun.getStageKey())) {
                continue;
            }
            if (isStageCompleted(run, targetTemplate.getStageKey())) {
                continue;
            }
            if (areAllReadyInputsComplete(run, targetTemplate)) {
                activateReadyAllReadyStage(run, targetTemplate);
                return true;
            }
            if (!allowMissingInputActivation) {
                continue;
            }
            AiWorkflowStageTemplate nextMissingInput = findNextMissingAllReadyInputTemplate(run, targetTemplate);
            if (nextMissingInput != null) {
                updateRunCurrentStage(run.getId(), nextMissingInput.getStageKey(), nextMissingInput.getStageOrder());
                workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(),
                        "ALL_READY 节点 " + targetTemplate.getStageName() + " 等待输入，先执行缺失输入 " + nextMissingInput.getStageName());
                applyHandoffSummary(run, sourceStageRun, nextMissingInput,
                        buildAllReadyWaitingNote(targetTemplate, nextMissingInput, transitionNote));
                activateOrBypassStage(run, nextMissingInput.getStageKey());
                return true;
            }
        }
        return false;
    }

    private void activateReadyAllReadyStage(AiWorkflowRun run, AiWorkflowStageTemplate targetTemplate) {
        updateRunCurrentStage(run.getId(), targetTemplate.getStageKey(), targetTemplate.getStageOrder());
        workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.ACTIVE.getCode(), null);
        applyAllReadyHandoff(run, targetTemplate);
        activateOrBypassStage(run, targetTemplate.getStageKey());
    }

    private boolean isAllReadyStage(AiWorkflowStageTemplate stageTemplate) {
        return stageTemplate != null
                && "ALL_READY".equalsIgnoreCase(stageTemplate.getCompletionRule())
                && !parseInputStageKeys(stageTemplate.getInputStageKeys()).isEmpty();
    }

    private boolean areAllReadyInputsComplete(AiWorkflowRun run, AiWorkflowStageTemplate targetTemplate) {
        List<String> inputKeys = parseInputStageKeys(targetTemplate.getInputStageKeys());
        if (inputKeys.isEmpty()) {
            return false;
        }
        for (String inputKey : inputKeys) {
            if (!isStageCompleted(run, inputKey)) {
                return false;
            }
        }
        return true;
    }

    private boolean isStageCompleted(AiWorkflowRun run, String stageKey) {
        if (run == null || !StringUtils.hasText(stageKey)) {
            return false;
        }
        AiWorkflowStageRun latest = workflowStageRunMapper.selectLatestByMeetingAndStageKey(run.getMeetingId(), stageKey);
        return latest != null && WorkflowStageStatusEnum.COMPLETED.getCode().equals(latest.getStatus());
    }

    private AiWorkflowStageTemplate findNextMissingAllReadyInputTemplate(AiWorkflowRun run, AiWorkflowStageTemplate targetTemplate) {
        List<String> inputKeys = parseInputStageKeys(targetTemplate.getInputStageKeys());
        if (inputKeys.isEmpty()) {
            return null;
        }
        for (String inputKey : inputKeys) {
            if (isStageCompleted(run, inputKey)) {
                continue;
            }
            AiWorkflowStageTemplate inputTemplate = findStageTemplate(run.getTemplateId(), inputKey);
            if (inputTemplate != null) {
                return inputTemplate;
            }
        }
        return null;
    }

    private String buildAllReadyWaitingReason(AiWorkflowRun run, AiWorkflowStageTemplate targetTemplate) {
        List<String> missing = parseInputStageKeys(targetTemplate.getInputStageKeys()).stream()
                .filter(inputKey -> !isStageCompleted(run, inputKey))
                .collect(Collectors.toList());
        return "ALL_READY 节点等待输入完成：" + String.join("、", missing);
    }

    private String buildAllReadyWaitingNote(AiWorkflowStageTemplate targetTemplate,
                                            AiWorkflowStageTemplate nextMissingInput,
                                            String transitionNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("ALL_READY 节点【").append(targetTemplate.getStageName()).append("】尚未满足全部输入。");
        sb.append("当前先执行缺失输入【").append(nextMissingInput.getStageName()).append("】。");
        if (StringUtils.hasText(transitionNote)) {
            sb.append("\n最近已完成输入摘要：").append(compactWorkflowSearchText(transitionNote, 600));
        }
        return sb.toString();
    }

    private void applyAllReadyHandoff(AiWorkflowRun run, AiWorkflowStageTemplate targetTemplate) {
        AiWorkflowStageRun targetRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(run.getMeetingId(), targetTemplate.getStageKey());
        if (targetRun == null) {
            targetRun = createStageRun(run, targetTemplate, 1);
        } else if (!WorkflowStageStatusEnum.PENDING.getCode().equals(targetRun.getStatus())
                && !WorkflowStageStatusEnum.ACTIVE.getCode().equals(targetRun.getStatus())) {
            targetRun = createStageRun(run, targetTemplate, targetRun.getAttemptNo() + 1);
        }
        List<AiWorkflowStageRun> inputRuns = collectAllReadyInputRuns(run, targetTemplate);
        String content = buildAllReadyInputContent(inputRuns);
        String summary = buildAllReadyInputSummary(inputRuns);
        String payload = buildAllReadyHandoffPayload(targetTemplate, inputRuns, content, summary);
        workflowStageRunMapper.updateHandoff(targetRun.getId(), summary, payload);
    }

    private List<AiWorkflowStageRun> collectAllReadyInputRuns(AiWorkflowRun run, AiWorkflowStageTemplate targetTemplate) {
        List<AiWorkflowStageRun> runs = new ArrayList<>();
        for (String inputKey : parseInputStageKeys(targetTemplate.getInputStageKeys())) {
            AiWorkflowStageRun latest = workflowStageRunMapper.selectLatestByMeetingAndStageKey(run.getMeetingId(), inputKey);
            if (latest != null && WorkflowStageStatusEnum.COMPLETED.getCode().equals(latest.getStatus())) {
                runs.add(latest);
            }
        }
        return runs;
    }

    private String buildAllReadyInputSummary(List<AiWorkflowStageRun> inputRuns) {
        String joined = inputRuns.stream()
                .map(run -> firstNonBlank(run.getStageName(), run.getStageKey()))
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("、"));
        String summary = "ALL_READY 输入已汇聚：" + joined;
        return summary.length() > 600 ? summary.substring(0, 600) + "…" : summary;
    }

    private String buildAllReadyInputContent(List<AiWorkflowStageRun> inputRuns) {
        StringBuilder sb = new StringBuilder();
        sb.append("【ALL_READY 输入汇总】\n");
        for (AiWorkflowStageRun inputRun : inputRuns) {
            AiWorkflowStageSubmission latestSubmission = workflowStageSubmissionMapper.selectLatestByStageRunId(inputRun.getId());
            String content = latestSubmission != null && StringUtils.hasText(latestSubmission.getContent())
                    ? latestSubmission.getContent()
                    : deriveAutoStageTransitionContent(inputRun, inputRun.getHandoffSummary());
            sb.append("\n## ")
                    .append(firstNonBlank(inputRun.getStageName(), inputRun.getStageKey()))
                    .append(" (").append(inputRun.getStageKey()).append(")");
            if (latestSubmission != null) {
                sb.append(" / ")
                        .append(firstNonBlank(latestSubmission.getActionType(), "SUBMIT"))
                        .append(" / ")
                        .append(firstNonBlank(latestSubmission.getActorRef(), "unknown"));
            }
            sb.append("\n")
                    .append(compactWorkflowSearchText(content, 2600))
                    .append("\n");
            if (sb.length() > 14000) {
                sb.append("\n...[ALL_READY 输入内容过长，后续输入已截断]");
                break;
            }
        }
        return sb.toString();
    }

    private String buildAllReadyHandoffPayload(AiWorkflowStageTemplate targetTemplate,
                                               List<AiWorkflowStageRun> inputRuns,
                                               String content,
                                               String summary) {
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        payload.put("sourceMode", "ALL_READY");
        payload.put("targetStageKey", targetTemplate.getStageKey());
        payload.put("targetStageName", targetTemplate.getStageName());
        payload.put("sourceStageKeys", inputRuns.stream().map(AiWorkflowStageRun::getStageKey).collect(Collectors.toList()));
        payload.put("content", content);
        payload.put("artifact", buildStageArtifact("ALL_READY", summary));
        com.alibaba.fastjson.JSONArray inputs = new com.alibaba.fastjson.JSONArray();
        for (AiWorkflowStageRun inputRun : inputRuns) {
            AiWorkflowStageSubmission latestSubmission = workflowStageSubmissionMapper.selectLatestByStageRunId(inputRun.getId());
            com.alibaba.fastjson.JSONObject item = new com.alibaba.fastjson.JSONObject(true);
            item.put("sourceStageRunId", inputRun.getId());
            item.put("sourceStageKey", inputRun.getStageKey());
            item.put("sourceStageName", inputRun.getStageName());
            item.put("sourceAttemptNo", inputRun.getAttemptNo());
            if (latestSubmission != null) {
                item.put("actionType", latestSubmission.getActionType());
                item.put("actorRef", latestSubmission.getActorRef());
                item.put("content", latestSubmission.getContent());
                item.put("artifact", parseStageArtifact(latestSubmission.getPayloadJson(), latestSubmission.getActionType(), latestSubmission.getContent()));
                item.put("createdAt", latestSubmission.getGmtCreate() != null ? latestSubmission.getGmtCreate().getTime() : null);
            }
            inputs.add(item);
        }
        payload.put("inputs", inputs);
        return payload.toJSONString();
    }

    private void concludeManualWorkflow(AiWorkflowRun run, String finalContent) {
        AiMeetingRoom room = roomMapper.selectById(run.getMeetingId());
        if (room == null) {
            return;
        }
        String finalConclusion = StringUtils.hasText(finalContent) ? finalContent.trim() : "流程已完成。";
        Date conclusionTime = new Date();
        AiMeetingRoom update = new AiMeetingRoom();
        update.setId(room.getId());
        update.setFinalConclusion(finalConclusion);
        update.setConclusionTime(conclusionTime);
        update.setStatus(MeetingStatusEnum.CONCLUDED.getCode());
        roomMapper.updateConclusion(update);
        roomMapper.updateConclusionHistory(room.getId(), appendConclusionHistoryEvent(
                room,
                "CONCLUDED",
                finalConclusion,
                MeetingStatusEnum.REOPENED.getCode().equals(room.getStatus())
                        ? "自定义流程重开后归档形成新结论"
                        : "自定义流程归档完成，形成结论",
                room.getCurrentRound(),
                conclusionTime
        ));
        workflowRunMapper.updateStatus(run.getId(), WorkflowRunStatusEnum.CONCLUDED.getCode(), null);
    }

    private String appendConclusionHistoryEvent(AiMeetingRoom room,
                                                String eventType,
                                                String conclusion,
                                                String triggerReason,
                                                Integer roundNumber,
                                                Date eventTime) {
        com.alibaba.fastjson.JSONArray array;
        try {
            array = room != null && StringUtils.hasText(room.getConclusionHistory())
                    ? com.alibaba.fastjson.JSON.parseArray(room.getConclusionHistory())
                    : new com.alibaba.fastjson.JSONArray();
        } catch (Exception e) {
            array = new com.alibaba.fastjson.JSONArray();
        }
        if (array.isEmpty()
                && room != null
                && StringUtils.hasText(room.getFinalConclusion())
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

    private String buildHandoffPayload(AiWorkflowStageRun sourceStageRun, String handoffSummary) {
        if (sourceStageRun == null && !StringUtils.hasText(handoffSummary)) {
            return null;
        }
        AiWorkflowStageSubmission latestSubmission = sourceStageRun != null
                ? workflowStageSubmissionMapper.selectLatestByStageRunId(sourceStageRun.getId())
                : null;
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        if (sourceStageRun != null) {
            payload.put("sourceStageRunId", sourceStageRun.getId());
            payload.put("sourceStageKey", sourceStageRun.getStageKey());
            payload.put("sourceStageName", sourceStageRun.getStageName());
            payload.put("sourceAttemptNo", sourceStageRun.getAttemptNo());
        }
        if (latestSubmission != null) {
            payload.put("actionType", latestSubmission.getActionType());
            payload.put("actorRef", latestSubmission.getActorRef());
            payload.put("content", StringUtils.hasText(latestSubmission.getContent()) ? latestSubmission.getContent() : handoffSummary);
            payload.put("artifact", parseStageArtifact(latestSubmission.getPayloadJson(), latestSubmission.getActionType(), latestSubmission.getContent()));
            payload.put("createdAt", latestSubmission.getGmtCreate() != null ? latestSubmission.getGmtCreate().getTime() : null);
        } else if (StringUtils.hasText(handoffSummary)) {
            payload.put("content", handoffSummary);
            payload.put("artifact", buildStageArtifact("HANDOFF", handoffSummary));
        }
        return payload.isEmpty() ? null : payload.toJSONString();
    }

    private MeetingDetailVO.HandoffArtifactVO parseHandoffArtifact(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            com.alibaba.fastjson.JSONObject payload = com.alibaba.fastjson.JSON.parseObject(raw);
            MeetingDetailVO.HandoffArtifactVO vo = new MeetingDetailVO.HandoffArtifactVO();
            vo.setSourceStageRunId(payload.getLong("sourceStageRunId"));
            vo.setSourceStageKey(payload.getString("sourceStageKey"));
            vo.setSourceStageName(payload.getString("sourceStageName"));
            vo.setSourceAttemptNo(payload.getInteger("sourceAttemptNo"));
            vo.setSourceMode(payload.getString("sourceMode"));
            com.alibaba.fastjson.JSONArray sourceStageKeys = payload.getJSONArray("sourceStageKeys");
            if (sourceStageKeys != null) {
                List<String> keys = new ArrayList<>();
                for (Object item : sourceStageKeys) {
                    if (item != null && StringUtils.hasText(String.valueOf(item))) {
                        keys.add(String.valueOf(item));
                    }
                }
                vo.setSourceStageKeys(keys);
            }
            vo.setActionType(payload.getString("actionType"));
            vo.setActorRef(payload.getString("actorRef"));
            vo.setContent(payload.getString("content"));
            vo.setArtifact(parseStageArtifactObject(payload.getJSONObject("artifact")));
            Long createdAt = payload.getLong("createdAt");
            vo.setCreatedAt(createdAt != null ? new Date(createdAt) : null);
            return vo;
        } catch (Exception ignored) {
            return null;
        }
    }

    private AiWorkflowRun requireWorkflowRun(Long meetingId) {
        AiWorkflowRun run = workflowRunMapper.selectByMeetingId(meetingId);
        if (run == null) {
            throw new IllegalStateException("会议尚未初始化工作流: " + meetingId);
        }
        return run;
    }

    private AiWorkflowStageRun requireCurrentStageRun(Long meetingId, String stageKey) {
        AiWorkflowStageRun stageRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(meetingId, stageKey);
        if (stageRun == null) {
            throw new IllegalStateException("当前阶段不存在: " + stageKey);
        }
        return stageRun;
    }

    private void ensureStageOperable(AiWorkflowRun run, AiWorkflowStageRun stageRun) {
        if (!WorkflowRunStatusEnum.ACTIVE.getCode().equals(run.getStatus())
                && !WorkflowRunStatusEnum.REOPENED.getCode().equals(run.getStatus())) {
            throw new IllegalStateException("当前工作流不处于可操作状态: " + run.getStatus());
        }
        if (!WorkflowStageStatusEnum.ACTIVE.getCode().equals(stageRun.getStatus())
                && !WorkflowStageStatusEnum.BLOCKED.getCode().equals(stageRun.getStatus())) {
            throw new IllegalStateException("当前阶段不可操作: " + stageRun.getStatus());
        }
    }

    private AiWorkflowStageTemplate requireStageTemplate(Long stageTemplateId) {
        AiWorkflowStageTemplate template = workflowStageTemplateMapper.selectById(stageTemplateId);
        if (template == null) {
            throw new IllegalStateException("阶段模板不存在: " + stageTemplateId);
        }
        return template;
    }

    private AiWorkflowStageTemplate resolveStageTemplate(AiWorkflowRun run, AiWorkflowStageRun stageRun) {
        if (stageRun == null) {
            throw new IllegalStateException("阶段运行记录不存在");
        }
        AiWorkflowStageTemplate template = stageRun.getStageTemplateId() != null
                ? workflowStageTemplateMapper.selectById(stageRun.getStageTemplateId())
                : null;
        if (template != null) {
            return template;
        }
        if (run != null && run.getTemplateId() != null && StringUtils.hasText(stageRun.getStageKey())) {
            template = findStageTemplate(run.getTemplateId(), stageRun.getStageKey());
            if (template != null) {
                log.warn("Workflow stage template id {} missing, fallback by stageKey={} for meetingId={}",
                        stageRun.getStageTemplateId(), stageRun.getStageKey(), stageRun.getMeetingId());
                return template;
            }
        }
        log.warn("Workflow stage template id {} missing and no fallback found, using historical stage snapshot for meetingId={}, stageKey={}",
                stageRun.getStageTemplateId(), stageRun.getMeetingId(), stageRun.getStageKey());
        return buildHistoricalStageTemplateSnapshot(run, stageRun);
    }

    private AiWorkflowStageTemplate buildHistoricalStageTemplateSnapshot(AiWorkflowRun run, AiWorkflowStageRun stageRun) {
        AiWorkflowStageTemplate template = new AiWorkflowStageTemplate();
        template.setId(stageRun.getStageTemplateId());
        template.setTemplateId(run != null ? run.getTemplateId() : null);
        template.setStageKey(stageRun.getStageKey());
        template.setStageName(stageRun.getStageName());
        template.setStageOrder(stageRun.getStageOrder());
        template.setStageType(stageRun.getStageType());
        template.setActorType(stageRun.getActorType());
        template.setActorBindingMode("ALL");
        template.setCompletionRule("HISTORICAL");
        template.setSearchStrategy("OFF");
        template.setReviewRequired("REVIEW".equalsIgnoreCase(stageRun.getStageType()) ? 1 : 0);
        template.setMaxReviewAttempts(0);
        return template;
    }

    private AiWorkflowStageTemplate findStageTemplate(Long templateId, String stageKey) {
        return workflowStageTemplateMapper.selectByTemplateId(templateId).stream()
                .filter(stage -> stageKey.equals(stage.getStageKey()))
                .findFirst()
                .orElse(null);
    }

    private AiWorkflowStageTemplate resolveReopenStageTemplate(AiWorkflowRun run) {
        List<AiWorkflowStageTemplate> stages = workflowStageTemplateMapper.selectByTemplateId(run.getTemplateId());
        if (stages == null || stages.isEmpty()) {
            throw new IllegalStateException("流程模板未定义任何阶段");
        }
        AiWorkflowStageTemplate currentStage = StringUtils.hasText(run.getCurrentStageKey())
                ? stages.stream().filter(stage -> run.getCurrentStageKey().equals(stage.getStageKey())).findFirst().orElse(null)
                : null;
        if (currentStage != null && StringUtils.hasText(currentStage.getReopenTarget())) {
            AiWorkflowStageTemplate target = stages.stream()
                    .filter(stage -> currentStage.getReopenTarget().equals(stage.getStageKey()))
                    .findFirst()
                    .orElse(null);
            if (target != null) {
                return target;
            }
        }
        return stages.stream()
                .sorted(Comparator.comparing(AiWorkflowStageTemplate::getStageOrder).thenComparing(AiWorkflowStageTemplate::getId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("流程模板未定义可重开阶段"));
    }

    private boolean isReviewStage(AiWorkflowStageTemplate stageTemplate) {
        return stageTemplate != null && isReviewStageType(stageTemplate.getStageType());
    }

    private boolean isReviewStageType(String stageType) {
        return "REVIEW".equalsIgnoreCase(stageType) || "APPROVAL".equalsIgnoreCase(stageType);
    }

    private String buildStageArtifactPayload(String actionType, String content) {
        return buildStageArtifactPayload(actionType, content, null, null, null, null);
    }

    private String buildStageArtifactPayload(String actionType,
                                             String content,
                                             String executionSource,
                                             Boolean mergedOutput,
                                             List<String> sourceActorRefs,
                                             List<WorkflowStageSearchTrace> searchTraces) {
        MeetingDetailVO.StageArtifactVO artifact = buildStageArtifact(actionType, content);
        com.alibaba.fastjson.JSONObject json = toStageArtifactJson(artifact);
        if (json == null) {
            json = new com.alibaba.fastjson.JSONObject(true);
        }
        if (StringUtils.hasText(executionSource)) {
            json.put("executionSource", executionSource);
        }
        if (mergedOutput != null) {
            json.put("mergedOutput", mergedOutput);
        }
        if (sourceActorRefs != null && !sourceActorRefs.isEmpty()) {
            json.put("sourceActorRefs", sourceActorRefs);
        }
        if (searchTraces != null && !searchTraces.isEmpty()) {
            com.alibaba.fastjson.JSONArray traceArray = new com.alibaba.fastjson.JSONArray();
            com.alibaba.fastjson.JSONArray queryArray = new com.alibaba.fastjson.JSONArray();
            for (WorkflowStageSearchTrace trace : searchTraces) {
                if (trace == null) continue;
                traceArray.add(toWorkflowSearchTraceJson(trace));
                if (trace.queries != null) {
                    for (String query : trace.queries) {
                        if (StringUtils.hasText(query) && !queryArray.contains(query)) {
                            queryArray.add(query);
                        }
                    }
                }
            }
            json.put("searchTraces", traceArray);
            if (!queryArray.isEmpty()) {
                json.put("searchQueries", queryArray);
            }
        }
        return json == null ? null : json.toJSONString();
    }

    private MeetingDetailVO.StageArtifactVO parseStageArtifact(String payloadJson, String actionType, String content) {
        MeetingDetailVO.StageArtifactVO fromPayload = parseStageArtifactObject(payloadJson);
        return fromPayload != null ? fromPayload : buildStageArtifact(actionType, content);
    }

    private MeetingDetailVO.StageArtifactVO parseStageArtifactObject(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            com.alibaba.fastjson.JSONObject json;
            if (raw instanceof String) {
                json = com.alibaba.fastjson.JSON.parseObject((String) raw);
            } else if (raw instanceof com.alibaba.fastjson.JSONObject) {
                json = (com.alibaba.fastjson.JSONObject) raw;
            } else {
                return null;
            }
            MeetingDetailVO.StageArtifactVO artifact = new MeetingDetailVO.StageArtifactVO();
            artifact.setArtifactType(json.getString("artifactType"));
            artifact.setSummary(json.getString("summary"));
            artifact.setHighlights(toStringList(json.getJSONArray("highlights")));
            artifact.setDetailText(json.getString("detailText"));
            return artifact;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String parseSubmissionPayloadString(String payloadJson, String fieldName) {
        if (!StringUtils.hasText(payloadJson)) {
            return null;
        }
        try {
            return com.alibaba.fastjson.JSON.parseObject(payloadJson).getString(fieldName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean parseSubmissionPayloadBoolean(String payloadJson, String fieldName) {
        if (!StringUtils.hasText(payloadJson)) {
            return null;
        }
        try {
            return com.alibaba.fastjson.JSON.parseObject(payloadJson).getBoolean(fieldName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> parseSubmissionPayloadStringList(String payloadJson, String fieldName) {
        if (!StringUtils.hasText(payloadJson)) {
            return new ArrayList<>();
        }
        try {
            return toStringList(com.alibaba.fastjson.JSON.parseObject(payloadJson).getJSONArray(fieldName));
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private List<MeetingDetailVO.WorkflowSearchTraceVO> parseSubmissionSearchTraces(String payloadJson) {
        List<MeetingDetailVO.WorkflowSearchTraceVO> traces = new ArrayList<>();
        if (!StringUtils.hasText(payloadJson)) {
            return traces;
        }
        try {
            com.alibaba.fastjson.JSONArray array = com.alibaba.fastjson.JSON.parseObject(payloadJson).getJSONArray("searchTraces");
            if (array == null) {
                return traces;
            }
            for (Object item : array) {
                if (!(item instanceof com.alibaba.fastjson.JSONObject)) {
                    continue;
                }
                traces.add(parseWorkflowSearchTrace((com.alibaba.fastjson.JSONObject) item));
            }
        } catch (Exception ignored) {
        }
        return traces;
    }

    private MeetingDetailVO.WorkflowSearchTraceVO parseWorkflowSearchTrace(com.alibaba.fastjson.JSONObject json) {
        MeetingDetailVO.WorkflowSearchTraceVO vo = new MeetingDetailVO.WorkflowSearchTraceVO();
        vo.setStrategy(json.getString("strategy"));
        vo.setStrategyLabel(resolveSearchStrategyLabel(json.getString("strategy")));
        vo.setAttempted(json.getBoolean("attempted"));
        vo.setContractStrict(json.getBoolean("contractStrict"));
        vo.setContractNote(json.getString("contractNote"));
        vo.setContractSources(toStringList(json.getJSONArray("contractSources")));
        vo.setContractFields(toStringList(json.getJSONArray("contractFields")));
        vo.setAgentPlanMode(json.getString("agentPlanMode"));
        vo.setAgentPlanNote(json.getString("agentPlanNote"));
        vo.setAgentPlanQueries(toStringList(json.getJSONArray("agentPlanQueries")));
        vo.setAgentPlanSources(toStringList(json.getJSONArray("agentPlanSources")));
        vo.setAgentPlanFields(toStringList(json.getJSONArray("agentPlanFields")));
        vo.setProvider(json.getString("provider"));
        vo.setStatus(json.getString("status"));
        vo.setNote(json.getString("note"));
        vo.setQueryCount(json.getInteger("queryCount"));
        vo.setResultCount(json.getInteger("resultCount"));
        vo.setProviderTimingMs(json.getLong("providerTimingMs"));
        vo.setDigestTimingMs(json.getLong("digestTimingMs"));
        vo.setIsolatedSession(json.getBoolean("isolatedSession"));
        vo.setDigestSummary(json.getString("digestSummary"));
        vo.setDigestVerificationLevel(json.getString("digestVerificationLevel"));
        vo.setDigestVerificationNote(json.getString("digestVerificationNote"));
        vo.setDigestFreshnessNote(json.getString("digestFreshnessNote"));
        vo.setProviderDiagnostics(toStringList(json.getJSONArray("providerDiagnostics")));
        Long executedAt = json.getLong("executedAt");
        vo.setExecutedAt(executedAt != null ? new Date(executedAt) : null);
        List<MeetingDetailVO.WorkflowSearchResultVO> results = new ArrayList<>();
        com.alibaba.fastjson.JSONArray array = json.getJSONArray("results");
        if (array != null) {
            for (Object item : array) {
                if (!(item instanceof com.alibaba.fastjson.JSONObject)) {
                    continue;
                }
                com.alibaba.fastjson.JSONObject obj = (com.alibaba.fastjson.JSONObject) item;
                MeetingDetailVO.WorkflowSearchResultVO result = new MeetingDetailVO.WorkflowSearchResultVO();
                result.setSource(obj.getString("source"));
                result.setSnippet(obj.getString("snippet"));
                result.setUrl(obj.getString("url"));
                results.add(result);
            }
        }
        vo.setResults(results);
        return vo;
    }

    private com.alibaba.fastjson.JSONObject toWorkflowSearchTraceJson(WorkflowStageSearchTrace trace) {
        com.alibaba.fastjson.JSONObject json = new com.alibaba.fastjson.JSONObject(true);
        json.put("strategy", trace.strategy);
        json.put("attempted", trace.attempted);
        json.put("executedAt", trace.executedAt);
        json.put("note", trace.note);
        if (trace.contract != null) {
            json.put("contractStrict", trace.contract.strict);
            json.put("contractNote", trace.contract.note);
            json.put("contractSources", trace.contract.sourceRequirements.stream()
                    .map(item -> item.label + (item.required ? "（必需）" : ""))
                    .collect(Collectors.toList()));
            json.put("contractFields", trace.contract.requiredFields);
        }
        if (trace.agentPlan != null) {
            json.put("agentPlanMode", trace.agentPlan.mode);
            json.put("agentPlanNote", trace.agentPlan.note);
            json.put("agentPlanQueries", trace.agentPlan.queries);
            json.put("agentPlanSources", trace.agentPlan.preferredSources);
            json.put("agentPlanFields", trace.agentPlan.requiredFields);
        }
        if (trace.meta != null) {
            json.put("provider", trace.meta.provider);
            json.put("status", trace.meta.status);
            json.put("queryCount", trace.meta.queryCount);
            json.put("resultCount", trace.meta.providerResultCount);
            json.put("providerTimingMs", trace.meta.providerTimingMs);
            json.put("digestTimingMs", trace.meta.digestTimingMs);
            json.put("isolatedSession", trace.meta.isolatedSession);
            json.put("providerDiagnostics", trace.meta.providerDiagnostics);
            json.put("bridgeContentStatus", trace.meta.contentStatus);
            json.put("bridgeContentNote", trace.meta.contentNote);
            json.put("bridgeCompletionReason", trace.meta.completionReason);
            json.put("bridgeAnswerSource", trace.meta.answerSource);
            json.put("bridgeTargetId", trace.meta.targetId);
            json.put("bridgeAnswerLength", trace.meta.answerLength);
            json.put("bridgeMode", trace.meta.bridgeMode);
            json.put("expertMode", trace.meta.expertMode);
            json.put("quickMode", trace.meta.quickMode);
            json.put("deepThinkEnabled", trace.meta.deepThinkEnabled);
            json.put("searchEnabled", trace.meta.searchEnabled);
            json.put("newLinkCount", trace.meta.newLinkCount);
            json.put("candidateBlockCount", trace.meta.candidateBlockCount);
            json.put("bodyDeltaLength", trace.meta.bodyDeltaLength);
            json.put("waitDurationMs", trace.meta.waitDurationMs);
        } else {
            json.put("queryCount", trace.queries != null ? trace.queries.size() : 0);
            json.put("resultCount", trace.results != null ? trace.results.size() : 0);
        }
        if (trace.judgementAcceptedAsDeliverable != null) {
            json.put("judgementAcceptedAsDeliverable", trace.judgementAcceptedAsDeliverable);
        }
        if (trace.judgementIsSearchTraceOnly != null) {
            json.put("judgementIsSearchTraceOnly", trace.judgementIsSearchTraceOnly);
        }
        if (StringUtils.hasText(trace.judgementReason)) {
            json.put("judgementReason", trace.judgementReason);
        }
        if (trace.judgementMissing != null && !trace.judgementMissing.isEmpty()) {
            json.put("judgementMissing", trace.judgementMissing);
        }
        if (trace.digest != null) {
            json.put("digestSummary", trace.digest.summary);
            json.put("digestVerificationLevel", trace.digest.verificationLevel);
            json.put("digestVerificationNote", trace.digest.verificationNote);
            json.put("digestFreshnessNote", trace.digest.freshnessNote);
        }
        com.alibaba.fastjson.JSONArray results = new com.alibaba.fastjson.JSONArray();
        if (trace.results != null) {
            for (WebSearchService.SearchResult item : trace.results) {
                if (item == null) continue;
                com.alibaba.fastjson.JSONObject result = new com.alibaba.fastjson.JSONObject(true);
                result.put("source", item.source);
                result.put("snippet", item.snippet);
                result.put("url", item.url);
                results.add(result);
            }
        }
        json.put("results", results);
        return json;
    }

    private String resolveExecutionSourceLabel(String executionSource) {
        if (!StringUtils.hasText(executionSource)) {
            return "人工提交";
        }
        if ("AUTO".equalsIgnoreCase(executionSource)) {
            return "自动执行";
        }
        if ("AUTO_MERGED".equalsIgnoreCase(executionSource)) {
            return "自动汇总";
        }
        return executionSource;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private MeetingDetailVO.StageArtifactVO buildStageArtifact(String actionType, String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = content.trim();
        List<String> lines = Arrays.stream(normalized.split("\\r?\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        List<String> highlights = new ArrayList<>();
        String summary = null;
        List<String> details = new ArrayList<>();
        for (String line : lines) {
            if (isBulletLine(line)) {
                highlights.add(normalizeBulletLine(line));
                continue;
            }
            if (summary == null) {
                summary = line;
            } else {
                details.add(line);
            }
        }
        if (summary == null) {
            summary = normalized.length() > 80 ? normalized.substring(0, 80) + "…" : normalized;
        }
        if (highlights.isEmpty() && details.isEmpty()) {
            details.add(summary);
        }
        MeetingDetailVO.StageArtifactVO artifact = new MeetingDetailVO.StageArtifactVO();
        artifact.setArtifactType(resolveArtifactType(actionType));
        artifact.setSummary(summary);
        artifact.setHighlights(highlights);
        artifact.setDetailText(details.isEmpty() ? null : String.join("\n", details));
        return artifact;
    }

    private boolean isBulletLine(String line) {
        return line.startsWith("-")
                || line.startsWith("*")
                || line.startsWith("•")
                || line.matches("^\\d+[.、].*")
                || line.matches("^[一二三四五六七八九十][、.].*");
    }

    private String normalizeBulletLine(String line) {
        return line.replaceFirst("^[-*•]\\s*", "")
                .replaceFirst("^\\d+[.、]\\s*", "")
                .replaceFirst("^[一二三四五六七八九十][、.]\\s*", "")
                .trim();
    }

    private String resolveArtifactType(String actionType) {
        if ("APPROVE".equalsIgnoreCase(actionType)) {
            return "APPROVAL";
        }
        if ("REJECT".equalsIgnoreCase(actionType)) {
            return "REJECTION";
        }
        if ("BYPASS".equalsIgnoreCase(actionType)) {
            return "BYPASS";
        }
        if ("HANDOFF".equalsIgnoreCase(actionType)) {
            return "HANDOFF";
        }
        return "SUBMISSION";
    }

    private com.alibaba.fastjson.JSONObject toStageArtifactJson(MeetingDetailVO.StageArtifactVO artifact) {
        if (artifact == null) {
            return null;
        }
        com.alibaba.fastjson.JSONObject json = new com.alibaba.fastjson.JSONObject(true);
        json.put("artifactType", artifact.getArtifactType());
        json.put("summary", artifact.getSummary());
        json.put("highlights", artifact.getHighlights());
        json.put("detailText", artifact.getDetailText());
        return json;
    }

    private MeetingDetailVO.StageArtifactDiffVO buildArtifactDiff(MeetingDetailVO.StageSubmissionVO previous,
                                                                  MeetingDetailVO.StageSubmissionVO current) {
        if (previous == null || previous.getArtifact() == null || current == null || current.getArtifact() == null) {
            return null;
        }
        return buildArtifactDiff(
                previous.getArtifact(),
                current.getArtifact(),
                previous.getArtifact().getSummary(),
                current.getArtifact().getSummary()
        );
    }

    private MeetingDetailVO.StageArtifactDiffVO buildHandoffArtifactDiff(MeetingDetailVO.HandoffArtifactVO previous,
                                                                         MeetingDetailVO.HandoffArtifactVO current) {
        if (previous == null || previous.getArtifact() == null || current == null || current.getArtifact() == null) {
            return null;
        }
        return buildArtifactDiff(
                previous.getArtifact(),
                current.getArtifact(),
                previous.getContent(),
                current.getContent()
        );
    }

    private MeetingDetailVO.StageArtifactDiffVO buildArtifactDiff(MeetingDetailVO.StageArtifactVO prevArtifact,
                                                                  MeetingDetailVO.StageArtifactVO currentArtifact,
                                                                  String previousSummaryText,
                                                                  String currentSummaryText) {
        List<String> addedHighlights = currentArtifact.getHighlights().stream()
                .filter(item -> !prevArtifact.getHighlights().contains(item))
                .collect(Collectors.toList());
        List<String> removedHighlights = prevArtifact.getHighlights().stream()
                .filter(item -> !currentArtifact.getHighlights().contains(item))
                .collect(Collectors.toList());
        boolean summaryChanged = !Objects.equals(prevArtifact.getSummary(), currentArtifact.getSummary());
        boolean detailChanged = !Objects.equals(normalizeDiffText(prevArtifact.getDetailText()), normalizeDiffText(currentArtifact.getDetailText()));
        boolean changed = summaryChanged || detailChanged || !addedHighlights.isEmpty() || !removedHighlights.isEmpty();
        if (!changed) {
            return null;
        }

        MeetingDetailVO.StageArtifactDiffVO diff = new MeetingDetailVO.StageArtifactDiffVO();
        diff.setChanged(true);
        diff.setPreviousSummary(previousSummaryText != null ? previousSummaryText : prevArtifact.getSummary());
        diff.setCurrentSummary(currentSummaryText != null ? currentSummaryText : currentArtifact.getSummary());
        diff.setAddedHighlights(addedHighlights);
        diff.setRemovedHighlights(removedHighlights);
        diff.setDetailChanged(detailChanged);
        diff.setChangeNote(buildArtifactChangeNote(summaryChanged, detailChanged, addedHighlights, removedHighlights));
        return diff;
    }

    private String normalizeDiffText(String text) {
        return text == null ? null : text.trim();
    }

    private String buildArtifactChangeNote(boolean summaryChanged,
                                           boolean detailChanged,
                                           List<String> addedHighlights,
                                           List<String> removedHighlights) {
        List<String> notes = new ArrayList<>();
        if (summaryChanged) {
            notes.add("摘要已调整");
        }
        if (!addedHighlights.isEmpty()) {
            notes.add("新增 " + addedHighlights.size() + " 条要点");
        }
        if (!removedHighlights.isEmpty()) {
            notes.add("移除 " + removedHighlights.size() + " 条要点");
        }
        if (detailChanged) {
            notes.add("明细说明已更新");
        }
        return String.join("；", notes);
    }

    private void enrichReviewAlignment(MeetingDetailVO.StageSummaryVO stageSummary) {
        if (stageSummary == null
                || stageSummary.getSubmissions() == null
                || stageSummary.getSubmissions().isEmpty()
                || stageSummary.getHandoffArtifact() == null
                || !"REJECT".equalsIgnoreCase(stageSummary.getHandoffArtifact().getActionType())
                || !StringUtils.hasText(stageSummary.getHandoffArtifact().getContent())) {
            return;
        }
        String reviewFeedback = stageSummary.getHandoffArtifact().getContent();
        for (MeetingDetailVO.StageSubmissionVO submission : stageSummary.getSubmissions()) {
            if (submission.getDiffFromPrevious() == null) {
                continue;
            }
            applyReviewAlignment(submission.getDiffFromPrevious(), reviewFeedback);
        }
    }

    private void applyReviewAlignment(MeetingDetailVO.StageArtifactDiffVO diff, String reviewFeedback) {
        List<String> reviewPoints = extractReviewPoints(reviewFeedback);
        if (reviewPoints.isEmpty()) {
            return;
        }
        String currentText = buildCurrentArtifactText(diff);
        List<String> matched = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (String point : reviewPoints) {
            if (matchesReviewPoint(point, currentText)) {
                matched.add(point);
            } else {
                unmatched.add(point);
            }
        }
        diff.setMatchedReviewPoints(matched);
        diff.setUnmatchedReviewPoints(unmatched);
        if (unmatched.isEmpty()) {
            diff.setReviewAlignmentNote("本次修改已覆盖审核意见中的主要问题。");
        } else if (!matched.isEmpty()) {
            diff.setReviewAlignmentNote("本次修改已部分响应审核意见，但仍有未明确覆盖的点。");
        } else {
            diff.setReviewAlignmentNote("本次修改尚未体现对审核意见的明确响应。");
        }
    }

    private List<String> extractReviewPoints(String reviewFeedback) {
        if (!StringUtils.hasText(reviewFeedback)) {
            return new ArrayList<>();
        }
        String normalized = reviewFeedback
                .replace('：', '\n')
                .replace('；', '\n')
                .replace('。', '\n')
                .replace('，', '\n')
                .replace(',', '\n');
        String[] parts = normalized.split("\\r?\\n");
        LinkedHashSet<String> points = new LinkedHashSet<>();
        for (String part : parts) {
            String[] subParts = part.split("(并且|以及|同时|并)");
            for (String subPart : subParts) {
                String text = normalizeReviewPoint(normalizeBulletLine(subPart.trim()));
                if (text.length() >= 2) {
                    points.add(text);
                }
            }
        }
        return new ArrayList<>(points);
    }

    private String buildCurrentArtifactText(MeetingDetailVO.StageArtifactDiffVO diff) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(diff.getCurrentSummary())) {
            sb.append(diff.getCurrentSummary()).append('\n');
        }
        for (String item : diff.getAddedHighlights()) {
            sb.append(item).append('\n');
        }
        return sb.toString();
    }

    private boolean matchesReviewPoint(String reviewPoint, String currentText) {
        if (!StringUtils.hasText(reviewPoint) || !StringUtils.hasText(currentText)) {
            return false;
        }
        String normalizedPoint = normalizeSearchText(reviewPoint);
        String normalizedCurrent = normalizeSearchText(currentText);
        if (normalizedCurrent.contains(normalizedPoint)) {
            return true;
        }
        List<String> tokens = splitReviewTokens(normalizedPoint);
        int matched = 0;
        for (String token : tokens) {
            if (normalizedCurrent.contains(token)) {
                matched++;
            }
        }
        if (tokens.isEmpty()) {
            return normalizedCurrent.contains(normalizedPoint);
        }
        return matched >= Math.max(1, Math.min(2, tokens.size()));
    }

    private List<String> splitReviewTokens(String reviewPoint) {
        String normalized = normalizeSearchText(reviewPoint);
        if (normalized.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> phraseTokens = new ArrayList<>();
        for (String token : Arrays.asList("回退方案", "上线窗口", "灰度发布", "补充", "明确", "验证", "证据", "数据", "风险", "成本", "时间")) {
            if (normalized.contains(token)) {
                phraseTokens.add(token);
            }
        }
        if (!phraseTokens.isEmpty()) {
            return phraseTokens;
        }
        List<String> grams = new ArrayList<>();
        for (int i = 0; i < normalized.length() - 1; i++) {
            String gram = normalized.substring(i, i + 2).trim();
            if (gram.length() == 2) {
                grams.add(gram);
            }
        }
        return grams;
    }

    private String normalizeReviewPoint(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.trim()
                .replaceFirst("^(请|需要|请先|请继续|建议先)", "")
                .replaceFirst("^(补充|明确|说明|核实|确认)一下", "$1");
        return normalized.trim();
    }

    private String normalizeSearchText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.toLowerCase()
                .replaceAll("[\\s，,；;：:。!！?？()（）\\[\\]【】/\\\\-]", "");
    }

    private Integer defaultReviewRequired(WorkflowTemplateRequest.StageRequest stageRequest) {
        if (stageRequest.getReviewRequired() != null) {
            return stageRequest.getReviewRequired();
        }
        return isReviewStageType(stageRequest.getStageType()) ? 1 : 0;
    }

    private Integer defaultMaxReviewAttempts(WorkflowTemplateRequest.StageRequest stageRequest) {
        if (stageRequest.getMaxReviewAttempts() != null) {
            return Math.max(stageRequest.getMaxReviewAttempts(), 0);
        }
        return 0;
    }

    private MeetingDetailVO.StageExecutionGuideVO buildExecutionGuide(AiWorkflowStageTemplate stageTemplate) {
        MeetingDetailVO.StageExecutionGuideVO guide = new MeetingDetailVO.StageExecutionGuideVO();
        guide.setSkillKey(stageTemplate.getSkillKey());
        guide.setSkillPrompt(stageTemplate.getSkillPrompt());

        String stageType = stageTemplate.getStageType();
        if ("REVIEW".equals(stageType) || "APPROVAL".equals(stageType)) {
            guide.setActionTitle("审核该节点输出");
            guide.setActionPlaceholder("结合该节点的 Skill 要求填写审核意见；通过可简短说明，打回请明确指出问题点和修改方向");
            guide.setOutputExpectation("输出审核意见，并明确通过或打回的依据");
            guide.setSubmitLabel("提交审核意见");
            guide.setReviewPassLabel("审核通过");
            guide.setReviewRejectLabel("打回修改");
            guide.getCheckpoints().addAll(defaultGuidePoints("review"));
        } else if ("TRACKING".equals(stageType)) {
            guide.setActionTitle("更新追踪结果");
            guide.setActionPlaceholder("填写追踪结论、补充证据、是否满足继续开会条件，以及需要谁继续跟进");
            guide.setOutputExpectation("输出追踪结论、证据补充和后续动作");
            guide.setSubmitLabel("提交追踪结果");
            guide.setReviewPassLabel("确认完成");
            guide.setReviewRejectLabel("继续跟踪");
            guide.getCheckpoints().addAll(defaultGuidePoints("tracking"));
        } else {
            guide.setActionTitle("提交该节点输出");
            guide.setActionPlaceholder("围绕当前 Skill 要求填写本节点产出，例如分析结论、核对结果、分歧点和建议下一步");
            guide.setOutputExpectation("输出本节点的结构化结论，供后续节点继续处理");
            guide.setSubmitLabel("提交节点产出");
            guide.setReviewPassLabel("继续流转");
            guide.setReviewRejectLabel("退回重做");
            guide.getCheckpoints().addAll(defaultGuidePoints("analysis"));
        }

        if (StringUtils.hasText(stageTemplate.getSkillPrompt())) {
            List<String> promptPoints = Arrays.stream(stageTemplate.getSkillPrompt().split("[\\n；;]+"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .limit(4)
                    .collect(Collectors.toList());
            if (!promptPoints.isEmpty()) {
                guide.setOutputExpectation(stageTemplate.getSkillPrompt().trim());
                guide.getCheckpoints().clear();
                guide.getCheckpoints().addAll(promptPoints);
            }
        }
        return guide;
    }

    private List<String> defaultGuidePoints(String mode) {
        if ("review".equals(mode)) {
            return new ArrayList<>(Arrays.asList("审核结论", "命中的问题点", "遗漏风险", "通过或打回理由"));
        }
        if ("tracking".equals(mode)) {
            return new ArrayList<>(Arrays.asList("进展结论", "新增证据", "是否满足继续条件", "下一步责任人"));
        }
        return new ArrayList<>(Arrays.asList("结论摘要", "关键依据", "主要风险/分歧", "建议下一步"));
    }

    private void updateRunCurrentStage(Long workflowRunId, String stageKey, int stageOrder) {
        workflowRunMapper.updateCurrentStage(workflowRunId, stageKey, stageOrder);
    }

    private List<AiMeetingTrackingItem> createTrackingItemsFromArtifact(AiMeetingRoom room,
                                                                        AiWorkflowRun run,
                                                                        MeetingDetailVO.DecisionArtifactVO artifact) {
        List<AiMeetingTrackingItem> created = new ArrayList<>();
        if (artifact == null) {
            return created;
        }
        AiWorkflowStageRun sourceStageRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(room.getId(), STAGE_CONCLUSION);
        if (sourceStageRun == null) {
            sourceStageRun = workflowStageRunMapper.selectLatestByMeetingAndStageKey(room.getId(), STAGE_TRACKING);
        }
        Long stageRunId = sourceStageRun != null ? sourceStageRun.getId() : null;

        created.addAll(createTrackingItems(room, run, stageRunId, artifact.getOpenQuestions(), "OPEN_QUESTION", 1, 1));
        created.addAll(createTrackingItems(room, run, stageRunId, artifact.getNextActions(), "NEXT_ACTION", 0, 0));
        return created;
    }

    private List<AiMeetingTrackingItem> createTrackingItems(AiMeetingRoom room,
                                                            AiWorkflowRun run,
                                                            Long stageRunId,
                                                            List<String> sourceItems,
                                                            String itemType,
                                                            int reopenTrigger,
                                                            int requiredForContinue) {
        List<AiMeetingTrackingItem> created = new ArrayList<>();
        if (sourceItems == null || sourceItems.isEmpty()) {
            return created;
        }
        Date now = new Date();
        for (String itemText : sourceItems) {
            if (!StringUtils.hasText(itemText)) {
                continue;
            }
            String title = itemText.trim();
            if (trackingItemMapper.selectByMeetingIdAndTitle(room.getId(), title) != null) {
                continue;
            }
            AiMeetingTrackingItem item = new AiMeetingTrackingItem();
            item.setMeetingId(room.getId());
            item.setWorkflowRunId(run.getId());
            item.setStageRunId(stageRunId);
            item.setCreatorId(room.getCreatorId());
            item.setSourceType("MEETING");
            item.setItemType(itemType);
            item.setTitle(title);
            item.setDescription("自动从会议决策卡片生成");
            item.setStatus(TrackingItemStatusEnum.OPEN.getCode());
            item.setOwnerType("HUMAN");
            item.setOwnerRef(null);
            item.setReopenTrigger(reopenTrigger);
            item.setRequiredForContinue(requiredForContinue);
            item.setReopenPolicy("SUGGEST");
            item.setAutoRefreshEnabled(1);
            item.setRefreshMode("DAILY");
            item.setRefreshIntervalHours(24);
            item.setWatchQuery(title);
            item.setWatchScope(room.getTitle());
            item.setNextCheckAt(new Date(now.getTime() + java.util.concurrent.TimeUnit.HOURS.toMillis(24)));
            item.setLastCheckedAt(null);
            item.setLastCheckStatus(null);
            item.setLastCheckNote(null);
            item.setLastCheckFingerprint(null);
            item.setChangeSeverity("NONE");
            item.setDueAt(null);
            item.setLastUpdateAt(now);
            item.setClosedAt(null);
            trackingItemMapper.insert(item);

            AiMeetingTrackingUpdate update = new AiMeetingTrackingUpdate();
            update.setTrackingItemId(item.getId());
            update.setMeetingId(room.getId());
            update.setUpdateType("CREATED");
            update.setStatusBefore(null);
            update.setStatusAfter(TrackingItemStatusEnum.OPEN.getCode());
            update.setContent("由会议结论自动生成追踪项");
            update.setEvidenceRef(null);
            trackingUpdateMapper.insert(update);
            created.add(item);
        }
        return created;
    }
}
