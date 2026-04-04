package com.aimeeting.room.dto;

import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingVote;
import lombok.Data;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Data
public class MeetingDetailVO {
    private Long meetingId;
    private String title;
    private String description;
    private String projectKey;
    private String projectContext;
    private String inheritedContext;
    private Integer status;
    private String statusName;
    private Integer currentRound;
    private Integer totalRounds;
    private String finalConclusion;
    private Date conclusionTime;
    private String summary;
    private DecisionArtifactVO decisionArtifact;
    private String roundSummaries;  // JSON: [{"round":1,"summary":"..."}]
    private List<ConclusionHistoryVO> conclusionHistory = new ArrayList<>();
    private WorkflowSummaryVO workflow;
    private TrackingSummaryVO tracking;
    private List<AiMeetingMember> members;
    private List<OpinionVO> opinions;
    private List<VoteVO> votes;

    @Data
    public static class DecisionArtifactVO {
        private String topicOverview;
        private String projectKey;
        private String projectContext;
        private String consensusPath;
        private String finalDecision;
        private List<String> keyViewpoints = new ArrayList<>();
        private List<String> majorDisputes = new ArrayList<>();
        private List<String> decisionBasis = new ArrayList<>();
        private List<String> openQuestions = new ArrayList<>();
        private List<String> nextActions = new ArrayList<>();
    }

    @Data
    public static class ConclusionHistoryVO {
        private Integer versionNo;
        private String eventType;
        private String eventLabel;
        private String conclusion;
        private String triggerReason;
        private Integer roundNumber;
        private Date eventTime;
        private Long triggerTrackingItemId;
        private String triggerTrackingItemTitle;
        private Long triggerTrackingUpdateId;
        private Long triggerOpinionId;
        private List<ConclusionTrackingLinkVO> relatedTrackingItems = new ArrayList<>();
    }

    @Data
    public static class ConclusionTrackingLinkVO {
        private Long trackingItemId;
        private String trackingItemTitle;
    }

    @Data
    public static class WorkflowSummaryVO {
        private String templateKey;
        private String templateName;
        private String runStatus;
        private String runStatusName;
        private String currentStageKey;
        private String currentStageName;
        private String currentStageType;
        private String currentActorType;
        private String currentActorBindingMode;
        private String currentActorSlotKey;
        private List<String> currentActorCandidates = new ArrayList<>();
        private String currentSkillKey;
        private String currentSkillPrompt;
        private List<SkillFileVO> currentSkillFiles = new ArrayList<>();
        private StageExecutionGuideVO currentStageGuide;
        private String currentStageStatus;
        private String currentStageStatusName;
        private Integer currentStageReviewRequired;
        private Integer currentStageMaxReviewAttempts;
        private Boolean currentStageAutoExecutable;
        private Boolean currentStageRetryable;
        private Boolean currentSearchAttempted;
        private String currentSearchStatus;
        private String currentSearchProvider;
        private Integer currentSearchQueryCount;
        private Integer currentSearchResultCount;
        private String currentSearchNote;
        private Boolean currentSearchDegraded;
        private List<String> currentSearchDiagnostics = new ArrayList<>();
        private String blockedReason;
        private Integer reopenCount;
        private List<WorkflowNodeVO> nodes = new ArrayList<>();
        private List<StageSummaryVO> stageHistory = new ArrayList<>();
    }

    @Data
    public static class WorkflowNodeVO {
        private String stageKey;
        private String stageName;
        private Integer stageOrder;
        private String stageType;
        private String actorType;
        private String actorBindingMode;
        private String actorSlotKey;
        private String skillKey;
        private String skillPrompt;
        private List<SkillFileVO> skillFiles = new ArrayList<>();
        private StageExecutionGuideVO executionGuide;
        private Boolean autoStage;
        private Integer reviewRequired;
        private Integer maxReviewAttempts;
        private String passTarget;
        private String rejectTarget;
        private String reopenTarget;
        private String searchStrategy;
        private String searchStrategyLabel;
        private String latestStatus;
        private String latestStatusName;
        private Integer latestAttemptNo;
        private Boolean activeNode;
        private Boolean searchAttempted;
        private String searchStatus;
        private String searchProvider;
        private Integer searchQueryCount;
        private Integer searchResultCount;
        private String searchNote;
        private Boolean searchDegraded;
        private List<String> searchDiagnostics = new ArrayList<>();
        private List<StageSummaryVO> attempts = new ArrayList<>();
    }

    @Data
    public static class StageSummaryVO {
        private Long stageRunId;
        private String stageKey;
        private String stageName;
        private Integer stageOrder;
        private String stageType;
        private String actorType;
        private String actorBindingMode;
        private String actorSlotKey;
        private String skillKey;
        private String skillPrompt;
        private List<SkillFileVO> skillFiles = new ArrayList<>();
        private StageExecutionGuideVO executionGuide;
        private Boolean autoStage;
        private String searchStrategy;
        private String searchStrategyLabel;
        private Integer attemptNo;
        private String status;
        private String statusName;
        private Integer reviewRequired;
        private Integer maxReviewAttempts;
        private String handoffSummary;
        private HandoffArtifactVO handoffArtifact;
        private StageArtifactDiffVO handoffDiffFromPrevious;
        private String outcome;
        private String rejectReason;
        private Date startedAt;
        private Date completedAt;
        private List<StageSubmissionVO> submissions = new ArrayList<>();
    }

    @Data
    public static class StageSubmissionVO {
        private Long id;
        private String stageKey;
        private Integer attemptNo;
        private String actionType;
        private String actorRef;
        private String content;
        private StageArtifactVO artifact;
        private String executionSource;
        private String executionSourceLabel;
        private Boolean mergedOutput;
        private List<String> sourceActorRefs = new ArrayList<>();
        private List<String> searchQueries = new ArrayList<>();
        private List<WorkflowSearchTraceVO> searchTraces = new ArrayList<>();
        private StageArtifactDiffVO diffFromPrevious;
        private Date gmtCreate;
    }

    @Data
    public static class WorkflowSearchTraceVO {
        private String strategy;
        private String strategyLabel;
        private Boolean attempted;
        private Boolean contractStrict;
        private String contractNote;
        private List<String> contractSources = new ArrayList<>();
        private List<String> contractFields = new ArrayList<>();
        private String agentPlanMode;
        private String agentPlanNote;
        private List<String> agentPlanQueries = new ArrayList<>();
        private List<String> agentPlanSources = new ArrayList<>();
        private List<String> agentPlanFields = new ArrayList<>();
        private String provider;
        private String status;
        private String note;
        private Integer queryCount;
        private Integer resultCount;
        private Long providerTimingMs;
        private Long digestTimingMs;
        private Boolean isolatedSession;
        private String digestSummary;
        private String digestVerificationLevel;
        private String digestVerificationNote;
        private String digestFreshnessNote;
        private List<String> providerDiagnostics = new ArrayList<>();
        private List<WorkflowSearchResultVO> results = new ArrayList<>();
        private Date executedAt;
    }

    @Data
    public static class WorkflowSearchResultVO {
        private String source;
        private String snippet;
        private String url;
    }

    @Data
    public static class HandoffArtifactVO {
        private Long sourceStageRunId;
        private String sourceStageKey;
        private String sourceStageName;
        private Integer sourceAttemptNo;
        private String actionType;
        private String actorRef;
        private String content;
        private StageArtifactVO artifact;
        private Date createdAt;
    }

    @Data
    public static class StageArtifactVO {
        private String artifactType;
        private String summary;
        private List<String> highlights = new ArrayList<>();
        private String detailText;
    }

    @Data
    public static class StageArtifactDiffVO {
        private Boolean changed;
        private String previousSummary;
        private String currentSummary;
        private List<String> addedHighlights = new ArrayList<>();
        private List<String> removedHighlights = new ArrayList<>();
        private Boolean detailChanged;
        private String changeNote;
        private List<String> matchedReviewPoints = new ArrayList<>();
        private List<String> unmatchedReviewPoints = new ArrayList<>();
        private String reviewAlignmentNote;
    }

    @Data
    public static class StageExecutionGuideVO {
        private String skillKey;
        private String skillPrompt;
        private String actionTitle;
        private String actionPlaceholder;
        private String outputExpectation;
        private String submitLabel;
        private String reviewPassLabel;
        private String reviewRejectLabel;
        private List<String> checkpoints = new ArrayList<>();
    }

    @Data
    public static class TrackingSummaryVO {
        private Integer totalCount = 0;
        private Integer openCount = 0;
        private Integer blockedCount = 0;
        private Integer triggerCount = 0;
        private Integer requiredCount = 0;
        private ResumePolicyVO resumePolicy;
        private List<TrackingItemVO> items = new ArrayList<>();
    }

    @Data
    public static class ResumePolicyVO {
        private Boolean canResumeMeeting;
        private Boolean allRequiredReady;
        private Boolean anyTriggerReady;
        private List<String> pendingRequiredItems = new ArrayList<>();
        private List<String> readyTriggerItems = new ArrayList<>();
        private String evaluationNote;
    }

    @Data
    public static class TrackingItemVO {
        private Long id;
        private Long creatorId;
        private Long meetingId;
        private String sourceType;
        private String itemType;
        private String title;
        private String description;
        private String status;
        private String statusName;
        private String ownerType;
        private String ownerRef;
        private Boolean reopenTrigger;
        private Boolean requiredForContinue;
        private String reopenPolicy;
        private Boolean autoRefreshEnabled;
        private String refreshMode;
        private Integer refreshIntervalHours;
        private String watchQuery;
        private String watchScope;
        private Date nextCheckAt;
        private Date lastCheckedAt;
        private String lastCheckStatus;
        private String lastCheckNote;
        private String changeSeverity;
        private Long sourceStageRunId;
        private String sourceStageKey;
        private String sourceStageName;
        private Integer sourceStageOrder;
        private HandoffArtifactVO sourceHandoffArtifact;
        private Date dueAt;
        private Date lastUpdateAt;
        private Date closedAt;
        private String latestUpdateSummary;
        private List<String> matchedReviewPoints = new ArrayList<>();
        private List<String> unresolvedReviewPoints = new ArrayList<>();
        private String reviewResolutionNote;
        private Boolean resolvedByLatestConclusion;
        private List<String> matchedConclusionPoints = new ArrayList<>();
        private String conclusionResolutionNote;
        private Integer todayUpdateCount;
        private List<TrackingUpdateVO> todayUpdates = new ArrayList<>();
        private List<TrackingUpdateVO> updates = new ArrayList<>();
    }

    @Data
    public static class TrackingUpdateVO {
        private Long id;
        private String updateType;
        private String statusBefore;
        private String statusAfter;
        private String content;
        private String evidenceRef;
        private Date gmtCreate;
        private Integer relatedConclusionVersionNo;
    }

    @Data
    public static class OpinionVO {
        private Long id;
        private Long memberId;
        private String memberName;
        private Long targetMemberId;
        private String targetMemberName;
        private Integer roundNumber;
        private Long parentOpinionId;
        private Long replyToOpinionId;
        private String replyToLabel;
        private Boolean responseToTargetedQuestion;
        private String content;
        private String evidence;
        private Integer opinionType;
        private String opinionTypeName;
        private Date gmtCreate;
    }

    @Data
    public static class VoteVO {
        private Long id;
        private Long memberId;
        private String memberName;
        private Integer roundNumber;
        private String votePosition;
        private java.math.BigDecimal confidence;
        private String reasoning;
        private Date gmtCreate;
    }
}
