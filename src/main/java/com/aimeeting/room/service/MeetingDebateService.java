package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.StockWatchItemMapper;
import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.MeetingReviewedSearchGap;
import com.aimeeting.room.entity.MeetingReviewedSearchPlan;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.aimeeting.room.entity.StockWatchItem;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class MeetingDebateService {

    public static final String FALLBACK_OPINION_TEXT = "本轮自动发言生成失败（网络超时），该议员暂未回复，可继续观察其余议员是否形成收敛结论。";

    @Autowired private AiMeetingRoomMapper roomMapper;
    @Autowired private AiMeetingMemberMapper memberMapper;
    @Autowired private AiMeetingOpinionMapper opinionMapper;
    @Autowired private MeetingOrchestratorService orchestrator;
    @Autowired private MeetingWorkflowService workflowService;
    @Autowired private WebSearchService webSearch;
    @Autowired private ModelRegistryStore modelRegistryStore;
    @Autowired private EastmoneyMarketDataService eastmoneyMarketDataService;
    @Autowired private StockWatchItemMapper stockWatchItemMapper;
    @Autowired private MeetingReviewedEvidenceService reviewedEvidenceService;
    @Autowired private MeetingReviewedSearchLedgerService reviewedSearchLedgerService;
    @Autowired private MeetingReviewedOpinionWriter reviewedOpinionWriter;
    @Autowired private MeetingReviewedTurnSkipService reviewedTurnSkipService;
    @Value("${AI_MEETING_REVIEWED_ROUND_BUDGET_MINUTES:30}")
    private long reviewedRoundBudgetMinutes = 30;
    private Supplier<String> reviewedAsOfSupplier = () -> LocalDate.now(REVIEWED_MARKET_ZONE).toString();

    private static final String REPLY_MARKER = "==REPLY==";
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");
    private static final Pattern FACT_PAIR_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9_ /()%.,+\\-]{0,24})[：:]\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*([%A-Za-z\\u4e00-\\u9fa5]{0,8})");
    private static final Pattern REVIEWED_DATED_FACT_PATTERN = Pattern.compile(
            "(20\\d{2}年(?:上半年|下半年|一季度|二季度|三季度|四季度|全年)?[\\u4e00-\\u9fa5A-Za-z]{1,16})[：:]\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*(%|亿元|万元|元|亿|万|倍|股|户|次)");
    private static final Pattern STOCK_CODE_WITH_MARKET_PATTERN = Pattern.compile("(?i)(?:\\b(?:sh|sz|bj)[._-]?(\\d{6})\\b|\\b(\\d{6})[._-]?(?:sh|sz|bj)\\b)");
    private static final Pattern GROUNDED_NUMERIC_CLAIM_PATTERN = Pattern.compile(
            "(?<![\\d.eE])([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\s*(亿元|万元|亿|元|%|倍|bps|bp)");
    private static final Pattern REVIEWED_MA_PATTERN = Pattern.compile("(?i)ma(5|10|20|60|120)(?!\\d)");
    private static final Pattern REVIEWED_TRADING_DAYS_PATTERN = Pattern.compile("(?:最近|近)(\\d{1,3})(?:个)?交易日");
    private static final ZoneId REVIEWED_MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern MODEL_EVIDENCE_ONLY_PATTERN = Pattern.compile("(?s)^\\s*\\{\\s*\"(?:q|e)\"\\s*:");
    private static final String[][] GUARDED_STOCK_TERMS = {
            {"全a量化", "全a量化"},
            {"量化多头备选池", "量化多头备选池"},
            {"自有数据库", "自有数据库"},
            {"我自己的", "我自己的"},
            {"我当下的", "我当下的"},
            {"wind", "wind"},
            {"万得", "万得"},
            {"一致预期", "一致预期"},
            {"ic值", "ic值"},
            {"标准化暴露", "标准化暴露"},
            {"因子综合得分", "因子综合得分"},
            {"排名前", "排名前"},
            {"z≈", "z≈"},
            {"z=", "z="},
            {"beta", "beta"},
            {"贝塔", "贝塔"},
            {"中证1000波动率", "中证1000波动率"},
            {"业绩预告", "业绩预告"},
            {"eps", "eps"},
            {"roe", "roe"}
    };

    public List<AiMeetingOpinion> executeDebateRound(Long meetingId, int roundNumber) {
        return executeDebateRound(meetingId, roundNumber, null);
    }

    public List<AiMeetingOpinion> executeDebateRound(Long meetingId, int roundNumber,
                                                      Long expectedRunEpoch) {
        List<AiMeetingMember> members = workflowService.resolveMembersForCurrentStage(
                meetingId,
                memberMapper.selectByMeetingId(meetingId)
        );
        if (!isMeetingActiveForDebate(meetingId)) {
            log.info("Meeting {} no longer active before round {} debate starts, skip member opinions",
                    meetingId, roundNumber);
            return Collections.emptyList();
        }
        Long pendingTargetMemberId = findPendingTargetMemberId(meetingId);
        if (pendingTargetMemberId != null) {
            List<AiMeetingMember> targetedMembers = members.stream()
                    .filter(member -> pendingTargetMemberId.equals(member.getId()))
                    .collect(Collectors.toList());
            if (!targetedMembers.isEmpty()) {
                members = targetedMembers;
                log.info("Meeting {} round {} enters targeted reply mode, only member {} will answer",
                        meetingId, roundNumber, pendingTargetMemberId);
            }
        }
        if (reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()) {
            return executeReviewedDebateRound(meetingId, roundNumber, members, expectedRunEpoch);
        }
        List<AiMeetingOpinion> roundOpinions = new ArrayList<>();

        // 每个成员发表一次综合发言（包含对他人观点的回应）
        // context 中已注入本轮已有发言，后发言者自然能看到先发言者的内容
        for (AiMeetingMember member : members) {
            if (!isMeetingActiveForDebate(meetingId)) {
                log.info("Meeting {} no longer active during round {}, stop before member {}",
                        meetingId, roundNumber, member != null ? member.getId() : null);
                break;
            }
            AiMeetingOpinion opinion = generateOpinionWithRetry(meetingId, member, roundNumber);
            if (opinion != null) roundOpinions.add(opinion);
        }

        return roundOpinions;
    }

    private boolean isMeetingActiveForDebate(Long meetingId) {
        if (meetingId == null) {
            return true;
        }
        try {
            AiMeetingRoom room = roomMapper.selectById(meetingId);
            if (room == null) {
                return false;
            }
            Integer status = room.getStatus();
            return MeetingStatusEnum.IN_PROGRESS.getCode().equals(status)
                    || MeetingStatusEnum.REOPENED.getCode().equals(status);
        } catch (Exception e) {
            log.warn("Failed to check meeting active status. meetingId={} error={}", meetingId, e.getMessage());
            return true;
        }
    }

    public AiMeetingOpinion generateSingleTargetReply(Long meetingId, Long memberId, int roundNumber) {
        return generateSingleTargetReply(meetingId, memberId, roundNumber, null);
    }

    public AiMeetingOpinion generateSingleTargetReply(Long meetingId, Long memberId, int roundNumber,
                                                       Long expectedRunEpoch) {
        AiMeetingMember member = memberMapper.selectById(memberId);
        if (member == null || !meetingId.equals(member.getMeetingId())) {
            throw new IllegalArgumentException("指定议员不存在或不属于当前会议: " + memberId);
        }
        if (reviewedEvidenceService != null && reviewedEvidenceService.isEnabled()) {
            ReviewedRoundEvidence evidence = prepareReviewedRoundEvidence(meetingId, member, roundNumber,
                    new HashMap<>(), new HashMap<>(), expectedRunEpoch, new ReviewedAttemptTrace());
            assertReviewedMeetingState(meetingId, evidence.meetingStatusAtStart, expectedRunEpoch);
            return generateReviewedOpinion(meetingId, member, roundNumber, evidence);
        }
        return generateOpinionWithRetry(meetingId, member, roundNumber);
    }

    private List<AiMeetingOpinion> executeReviewedDebateRound(Long meetingId, int roundNumber,
                                                               List<AiMeetingMember> members,
                                                               Long expectedRunEpoch) {
        if (members == null || members.isEmpty()) return Collections.emptyList();
        List<AiMeetingOpinion> existing = opinionMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        List<AiMeetingMember> pending = members.stream().filter(member -> {
                    AiMeetingOpinion targeted = findLatestTargetedQuestion(meetingId, member.getId());
                    Long targetId = targeted == null ? null : targeted.getId();
                    boolean spoken = existing != null && existing.stream().anyMatch(opinion ->
                            member.getId().equals(opinion.getMemberId())
                                    && OpinionTypeEnum.STATEMENT.getCode().equals(opinion.getOpinionType())
                                    && Objects.equals(targetId, opinion.getParentOpinionId()));
                    boolean skipped = reviewedTurnSkipService != null &&
                            reviewedTurnSkipService.findSkip(meetingId, roundNumber,
                                    member.getId(), targetId) != null;
                    return !spoken && !skipped;
                })
                .collect(Collectors.toList());
        if (pending.isEmpty()) return existing == null ? Collections.emptyList() : existing;
        Map<String, EastmoneyMarketDataService.MarketDataBundle> eastmoneyCache = new HashMap<>();
        Map<String, MeetingReviewedEvidenceService.ReviewedEvidence> reviewedCache = new HashMap<>();
        List<AiMeetingOpinion> generated = new ArrayList<>();
        for (AiMeetingMember member : pending) {
            if (!isMeetingActiveForDebate(meetingId)
                    || !reviewedRunMatches(meetingId, expectedRunEpoch)) break;
            Integer statusAtStart = requireReviewedMeetingStatus(meetingId);
            String asOf = reviewedAsOfSupplier.get();
            Long contextOpinionId = latestRoundHumanOpinionId(meetingId, roundNumber);
            AiMeetingOpinion targeted = findLatestTargetedQuestion(meetingId, member.getId());
            Long targetQuestionId = targeted == null ? null : targeted.getId();
            ReviewedAttemptTrace attemptTrace = new ReviewedAttemptTrace();
            try {
                assertReviewedContext(meetingId, statusAtStart, expectedRunEpoch,
                        roundNumber, asOf, contextOpinionId);
                ReviewedRoundEvidence evidence = prepareReviewedRoundEvidence(meetingId, member, roundNumber,
                        eastmoneyCache, reviewedCache, expectedRunEpoch, attemptTrace);
                assertReviewedMeetingState(meetingId, evidence.meetingStatusAtStart, expectedRunEpoch);
                generated.add(generateReviewedOpinion(meetingId, member, roundNumber, evidence));
            } catch (MeetingReviewedEvidenceService.EvidenceRejected e) {
                if (!isMemberLocalReviewedFailure(e.getMessage()) || reviewedTurnSkipService == null) {
                    pauseForReviewedEvidence(meetingId, expectedRunEpoch,
                            "议员 " + member.getMemberName() + " 的资料或发言失败：" + e.getMessage());
                    break;
                }
                try {
                    assertReviewedContext(meetingId, statusAtStart, expectedRunEpoch,
                            roundNumber, asOf, contextOpinionId);
                    MeetingReviewedTurnSkip skip = new MeetingReviewedTurnSkip();
                    skip.setMeetingId(meetingId);
                    skip.setRoundNumber(roundNumber);
                    skip.setMemberId(member.getId());
                    skip.setTargetQuestionId(targetQuestionId);
                    skip.setReasonCode(e.getMessage());
                    skip.setRunEpoch(expectedRunEpoch);
                    skip.setAsOf(asOf);
                    skip.setGapId(attemptTrace.failedGapId);
                    if (reviewedSearchLedgerService != null) {
                        MeetingReviewedSearchPlan plan = reviewedSearchLedgerService.findPlanVersion(meetingId,
                                roundNumber, member.getId(), targetQuestionId, asOf, contextOpinionId);
                        skip.setPlanId(plan == null ? null : plan.getId());
                    }
                    reviewedTurnSkipService.recordIfCurrent(skip, statusAtStart, contextOpinionId);
                    log.warn("Meeting {} round {} member {} abstained after reviewed turn failure: {}",
                            meetingId, roundNumber, member.getId(), e.getMessage());
                } catch (RuntimeException staleOrPersistenceFailure) {
                    pauseForReviewedEvidence(meetingId, expectedRunEpoch,
                            "议员弃权状态无法安全提交：" + staleOrPersistenceFailure.getMessage());
                    break;
                }
            } catch (RuntimeException e) {
                pauseForReviewedEvidence(meetingId, expectedRunEpoch,
                        "议员 " + member.getMemberName() + " 的执行状态异常");
                break;
            }
        }
        return generated;
    }

    private boolean isMemberLocalReviewedFailure(String reason) {
        if (reason == null || !reason.matches("[A-Z][A-Z0-9_:-]{0,127}")) return false;
        if (reason.startsWith("MEETING_") || reason.startsWith("ROUND_SEARCH_")
                || reason.startsWith("MEMBER_SEARCH_PLAN_STALE")
                || reason.startsWith("MEMBER_SEARCH_PLAN_CONTEXT_STALE")
                || reason.equals("N8N_GAP_ALREADY_CLAIMED")
                || reason.equals("N8N_GAP_IN_FLIGHT")
                || reason.equals("N8N_PACKET_NOT_PERSISTED")) return false;
        return reason.startsWith("MODEL_") || reason.startsWith("MEMBER_")
                || reason.startsWith("N8N_") || reason.equals("PRIOR_SEARCH_OUTCOME_REQUIRES_REVIEW");
    }

    private ReviewedRoundEvidence prepareReviewedRoundEvidence(Long meetingId, AiMeetingMember member,
                                                               int roundNumber,
                                                               Map<String, EastmoneyMarketDataService.MarketDataBundle> eastmoneyCache,
                                                               Map<String, MeetingReviewedEvidenceService.ReviewedEvidence> reviewedCache,
                                                               Long expectedRunEpoch,
                                                               ReviewedAttemptTrace attemptTrace) {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, roundNumber, member.getId(), MeetingCallContext.EVIDENCE_PLAN)) {
            return prepareReviewedRoundEvidenceInScope(meetingId, member, roundNumber, eastmoneyCache,
                    reviewedCache, expectedRunEpoch, attemptTrace);
        }
    }

    private ReviewedRoundEvidence prepareReviewedRoundEvidenceInScope(Long meetingId, AiMeetingMember member,
                                                                      int roundNumber,
                                                                      Map<String, EastmoneyMarketDataService.MarketDataBundle> eastmoneyCache,
                                                                      Map<String, MeetingReviewedEvidenceService.ReviewedEvidence> reviewedCache,
                                                                      Long expectedRunEpoch,
                                                                      ReviewedAttemptTrace attemptTrace) {
        Integer meetingStatusAtStart = requireReviewedMeetingStatus(meetingId);
        if (!reviewedRunMatches(meetingId, expectedRunEpoch)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE");
        }
        Long contextOpinionId = latestRoundHumanOpinionId(meetingId, roundNumber);
        AiMeetingOpinion targetedQuestion = findLatestTargetedQuestion(meetingId, member.getId());
        Long targetQuestionId = targetedQuestion == null ? null : targetedQuestion.getId();
        String asOf = reviewedAsOfSupplier.get();
        assertReviewedContext(meetingId, meetingStatusAtStart, expectedRunEpoch,
                roundNumber, asOf, contextOpinionId);
        ReviewedSearchDecision decision;
        Long savedPlanId = null;
        Long supersedesPlanId = null;
        if (reviewedSearchLedgerService == null) {
            // Directly constructed unit fixtures have no Spring wiring; runtime requires the ledger bean.
            decision = decideReviewedSearchNeeds(meetingId, member, roundNumber);
        } else {
            MeetingReviewedSearchPlan saved = reviewedSearchLedgerService.findPlanVersion(meetingId,
                    roundNumber, member.getId(), targetQuestionId, asOf, contextOpinionId);
            if (saved == null) {
                ReviewedSearchDecision proposed = decideReviewedSearchNeeds(meetingId, member, roundNumber);
                assertReviewedContext(meetingId, meetingStatusAtStart, expectedRunEpoch,
                        roundNumber, asOf, contextOpinionId);
                proposed.raw.put("contextOpinionId", contextOpinionId == null ? 0L : contextOpinionId);
                saved = reviewedSearchLedgerService.savePlanIfAbsent(meetingId, roundNumber, member.getId(),
                        targetQuestionId, asOf, contextOpinionId, proposed.raw.toJSONString());
            }
            if (!asOf.equals(saved.getAsOf())
                    || !Objects.equals(contextOpinionId == null ? 0L : contextOpinionId,
                        saved.getContextOpinionId())) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_PLAN_CONTEXT_STALE");
            }
            assertReviewedContext(meetingId, meetingStatusAtStart, expectedRunEpoch,
                    roundNumber, asOf, contextOpinionId);
            decision = parseReviewedSearchDecision(meetingId, saved.getNormalizedPlanJson());
            savedPlanId = saved.getId();
            supersedesPlanId = saved.getSupersedesPlanId();
        }
        decision.raw.put("contextOpinionId", contextOpinionId == null ? 0L : contextOpinionId);
        SearchPlan plan = decision.plan;
        List<WebSearchService.SearchResult> results = new ArrayList<>();
        List<JSONObject> packets = new ArrayList<>();
        List<String> executedQueries = new ArrayList<>();
        List<String> reviewQuestions = new ArrayList<>();
        List<List<String>> questionsByNeed = new ArrayList<>();
        List<String> unsearchedGaps = new ArrayList<>();
        List<String> unverifiedGaps = new ArrayList<>();
        List<String> partiallyReviewedGaps = new ArrayList<>();
        List<String> noUsableReviewedGaps = new ArrayList<>();
        JSONArray unverifiedGapFailures = new JSONArray();
        BigDecimal evidenceConfidenceScore = BigDecimal.ONE;
        String title = resolveMeetingTitle(meetingId);
        if (title.isBlank()) throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_TITLE_MISSING");
        for (ReviewedNeed need : decision.needs) {
            List<String> needQuestions = new ArrayList<>();
            ReviewedEastmoneyEvidence eastmoney = fetchReviewedEastmoneyEvidence(meetingId, plan,
                    need, asOf, eastmoneyCache);
            results.addAll(eastmoney.results);
            Set<String> residuals = new LinkedHashSet<>();
            residuals.addAll(eastmoney.missingFields);
            // A missing whole section already covers its missing properties; do not spend two calls.
            residuals.removeIf(residual -> residual.contains(".") && eastmoney.missingFields.contains(
                    residual.substring(0, residual.indexOf('.')).replace("[]", "")));
            residuals.addAll(need.unsupportedFields);
            for (String residual : residuals) {
                needQuestions.add(buildReviewedGapQuestion(title, need, eastmoney, residual));
            }
            if (need.webSearch && need.webQuestion != null && !need.webQuestion.isBlank()) {
                needQuestions.add(buildReviewedGapQuestion(title, need, eastmoney, need.webQuestion));
            } else if (need.webSearch && residuals.isEmpty()) {
                needQuestions.add(buildReviewedGapQuestion(title, need, eastmoney, need.question));
            }
            questionsByNeed.add(new ArrayList<>(new LinkedHashSet<>(needQuestions)));
        }
        LinkedHashSet<String> prioritizedQuestions = new LinkedHashSet<>();
        for (List<String> needQuestions : questionsByNeed) {
            if (!needQuestions.isEmpty()) prioritizedQuestions.add(needQuestions.get(0));
        }
        for (List<String> needQuestions : questionsByNeed) prioritizedQuestions.addAll(needQuestions);
        reviewQuestions = new ArrayList<>(prioritizedQuestions);
        if (reviewQuestions.size() > 4) {
            unsearchedGaps.addAll(reviewQuestions.subList(4, reviewQuestions.size()));
            reviewQuestions = new ArrayList<>(reviewQuestions.subList(0, 4));
        }
        for (String gap : reviewQuestions) {
            assertReviewedContext(meetingId, meetingStatusAtStart, expectedRunEpoch,
                    roundNumber, asOf, contextOpinionId);
            String cacheKey = asOf + "\n" + gap;
            MeetingReviewedEvidenceService.ReviewedEvidence reviewed = reviewedCache.get(cacheKey);
            if (reviewed == null) {
                if (reviewedCache.size() >= 8) {
                    unsearchedGaps.add(gap);
                    continue;
                }
                try {
                    reviewed = resolveReviewedGap(meetingId, roundNumber, asOf, gap,
                            meetingStatusAtStart, expectedRunEpoch, contextOpinionId, attemptTrace);
                } catch (MeetingReviewedEvidenceService.EvidenceRejected failedGap) {
                    if (!"N8N_REVIEW_ERROR".equals(failedGap.getMessage())
                            && !"N8N_GAP_REJECTED".equals(failedGap.getMessage())) throw failedGap;
                    // The ledger has a terminal rejection. No candidate from this gap enters
                    // the prompt, but other reviewed evidence can still support this speaker.
                    assertReviewedContext(meetingId, meetingStatusAtStart, expectedRunEpoch,
                            roundNumber, asOf, contextOpinionId);
                    executedQueries.add(gap);
                    unverifiedGaps.add(gap);
                    noUsableReviewedGaps.add(gap);
                    JSONObject failure = new JSONObject();
                    failure.put("question", gap);
                    failure.put("reason", failedGap.getMessage());
                    unverifiedGapFailures.add(failure);
                    evidenceConfidenceScore = evidenceConfidenceScore.min(new BigDecimal("0.25"));
                    attemptTrace.failedGapId = null;
                    continue;
                }
                reviewedCache.put(cacheKey, reviewed);
            }
            assertReviewedContext(meetingId, meetingStatusAtStart, expectedRunEpoch,
                    roundNumber, asOf, contextOpinionId);
            executedQueries.add(gap);
            packets.add(reviewed.packet);
            results.addAll(reviewed.results);
            evidenceConfidenceScore = evidenceConfidenceScore.min(reviewed.confidenceScore);
            JSONArray limitations = reviewed.packet.getJSONArray("limitations");
            if ("ERROR".equals(reviewed.packet.getString("status"))
                    || "UNVERIFIED".equals(reviewed.packet.getString("status"))
                    || (limitations != null && (limitations.contains("CLAIM_CITATION_NOT_CAPTURED")
                        || limitations.contains("CLAIM_CONTRADICTED")
                        || limitations.contains("REVIEW_INCOMPLETE")
                        || limitations.contains("SEARCH_INCOMPLETE")))) {
                unverifiedGaps.add(gap);
                if (reviewed.results.stream().anyMatch(result -> result != null && !isProvisionalResult(result))) {
                    partiallyReviewedGaps.add(gap);
                } else {
                    noUsableReviewedGaps.add(gap);
                }
                evidenceConfidenceScore = evidenceConfidenceScore.min(new BigDecimal("0.25"));
            }
        }
        if (!unsearchedGaps.isEmpty()) {
            decision.raw.put("unsearchedGaps", unsearchedGaps);
            decision.raw.put("unsearchedGapCount", unsearchedGaps.size());
            decision.raw.put("unsearchedGapReason", "SEARCH_BUDGET_EXHAUSTED");
            evidenceConfidenceScore = evidenceConfidenceScore.min(new BigDecimal("0.25"));
        }
        if (!unverifiedGaps.isEmpty()) {
            decision.raw.put("unverifiedGaps", unverifiedGaps);
            decision.raw.put("unverifiedGapCount", unverifiedGaps.size());
            decision.raw.put("unverifiedGapReason", partiallyReviewedGaps.isEmpty()
                    ? "NO_USABLE_REVIEWED_FACT" : "INCOMPLETE_REVIEW");
            decision.raw.put("partiallyReviewedGaps", partiallyReviewedGaps);
            decision.raw.put("noUsableReviewedGaps", noUsableReviewedGaps);
            if (!unverifiedGapFailures.isEmpty()) {
                decision.raw.put("unverifiedGapFailures", unverifiedGapFailures);
            }
        }
        String status = !unsearchedGaps.isEmpty() || !unverifiedGaps.isEmpty() ? "LOW_CONFIDENCE" : packets.isEmpty()
                ? (decision.needs.isEmpty() ? "NO_SEARCH_NEEDED" : "STRUCTURED_ONLY")
                : packets.stream().allMatch(packet -> "CROSS_CHECKED".equals(packet.getString("status")))
                    ? "CROSS_CHECKED" : "LOW_CONFIDENCE";
        return new ReviewedRoundEvidence(plan, dedupeSearchResults(results), packets,
                executedQueries, decision.raw, status, evidenceConfidenceScore,
                meetingStatusAtStart, targetQuestionId,
                expectedRunEpoch, roundNumber, asOf, contextOpinionId, savedPlanId, supersedesPlanId);
    }

    private MeetingReviewedEvidenceService.ReviewedEvidence resolveReviewedGap(Long meetingId,
                                                                                int roundNumber, String asOf,
                                                                                String exactQuestion,
                                                                                Integer expectedStatus,
                                                                                Long expectedRunEpoch,
                                                                                Long contextOpinionId,
                                                                                ReviewedAttemptTrace attemptTrace) {
        assertReviewedContext(meetingId, expectedStatus, expectedRunEpoch,
                roundNumber, asOf, contextOpinionId);
        if (reviewedSearchLedgerService == null) {
            try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.EVIDENCE_SEARCH)) {
                return reviewedEvidenceService.search(exactQuestion);
            }
        }
        MeetingReviewedSearchGap gap = reviewedSearchLedgerService.findOrCreateGap(meetingId, roundNumber,
                asOf, exactQuestion);
        attemptTrace.failedGapId = gap.getId();
        if (MeetingReviewedSearchLedgerService.CROSS_CHECKED.equals(gap.getStatus())
                || MeetingReviewedSearchLedgerService.LOW_CONFIDENCE.equals(gap.getStatus())) {
            MeetingReviewedEvidenceService.ReviewedEvidence accepted =
                    MeetingReviewedEvidenceService.acceptPacket(JSON.parseObject(gap.getPacketJson()),
                            gap.getRequestId(), exactQuestion);
            attemptTrace.failedGapId = null;
            return accepted;
        }
        if (!MeetingReviewedSearchLedgerService.PENDING.equals(gap.getStatus())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_GAP_" + gap.getStatus());
        }
        assertReviewedContext(meetingId, expectedStatus, expectedRunEpoch,
                roundNumber, asOf, contextOpinionId);
        if (!reviewedSearchLedgerService.claimPendingGap(gap.getId())) {
            MeetingReviewedSearchGap latest = reviewedSearchLedgerService.findGapById(gap.getId());
            if (latest != null && (MeetingReviewedSearchLedgerService.CROSS_CHECKED.equals(latest.getStatus())
                    || MeetingReviewedSearchLedgerService.LOW_CONFIDENCE.equals(latest.getStatus()))) {
                MeetingReviewedEvidenceService.ReviewedEvidence accepted =
                        MeetingReviewedEvidenceService.acceptPacket(JSON.parseObject(latest.getPacketJson()),
                        latest.getRequestId(), exactQuestion);
                attemptTrace.failedGapId = null;
                return accepted;
            }
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_GAP_ALREADY_CLAIMED");
        }
        long timeoutMs;
        try {
            assertReviewedContext(meetingId, expectedStatus, expectedRunEpoch,
                    roundNumber, asOf, contextOpinionId);
            timeoutMs = remainingReviewedSearchTimeoutMs(meetingId, roundNumber, asOf,
                    contextOpinionId);
        } catch (MeetingReviewedEvidenceService.EvidenceRejected notSubmitted) {
            reviewedSearchLedgerService.releaseUnsubmittedGap(gap.getId());
            throw notSubmitted;
        }
        MeetingReviewedEvidenceService.ReviewedEvidence reviewed;
        try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.EVIDENCE_SEARCH)) {
            reviewed = reviewedEvidenceService.search(gap.getRequestId(), exactQuestion, asOf,
                    timeoutMs);
        } catch (MeetingReviewedEvidenceService.EvidenceRejected rejected) {
            if ("N8N_OUTCOME_UNKNOWN".equals(rejected.getMessage())) {
                reviewedSearchLedgerService.markUnknown(gap.getId(), rejected.getMessage());
            } else {
                reviewedSearchLedgerService.markRejected(gap.getId(), rejected.getMessage());
            }
            throw rejected;
        } catch (RuntimeException unknown) {
            reviewedSearchLedgerService.markUnknown(gap.getId(), "PROVIDER_OUTCOME_UNKNOWN");
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_OUTCOME_UNKNOWN", unknown);
        }
        boolean persisted = "CROSS_CHECKED".equals(reviewed.packet.getString("status"))
                ? reviewedSearchLedgerService.markCrossChecked(gap.getId(), reviewed.packet.toJSONString())
                : reviewedSearchLedgerService.markLowConfidence(gap.getId(), reviewed.packet.toJSONString());
        if (!persisted) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_PACKET_NOT_PERSISTED");
        }
        assertReviewedContext(meetingId, expectedStatus, expectedRunEpoch,
                roundNumber, asOf, contextOpinionId);
        attemptTrace.failedGapId = null;
        return reviewed;
    }

    private static final class ReviewedAttemptTrace {
        private Long failedGapId;
    }

    private long remainingReviewedSearchTimeoutMs(Long meetingId, int roundNumber,
                                                  String asOf, Long contextOpinionId) {
        long context = contextOpinionId == null ? 0L : contextOpinionId;
        long oldestPlanMs = reviewedSearchLedgerService.listPlansByMeetingId(meetingId).stream()
                .filter(plan -> plan.getRoundNumber() != null && plan.getRoundNumber() == roundNumber)
                .filter(plan -> asOf.equals(plan.getAsOf())
                        && Objects.equals(context, plan.getContextOpinionId()))
                .filter(plan -> plan.getGmtCreate() != null)
                .mapToLong(plan -> plan.getGmtCreate().getTime())
                .min().orElseThrow(() -> new MeetingReviewedEvidenceService.EvidenceRejected(
                        "ROUND_SEARCH_BUDGET_ANCHOR_MISSING"));
        long remaining = oldestPlanMs + TimeUnit.MINUTES.toMillis(Math.max(1, reviewedRoundBudgetMinutes))
                - System.currentTimeMillis();
        if (remaining < 1000) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("ROUND_SEARCH_DEADLINE_EXCEEDED");
        }
        return Math.min(TimeUnit.MINUTES.toMillis(13), remaining);
    }

    private Integer requireReviewedMeetingStatus(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null || room.getStatus() == null) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_STATE_UNAVAILABLE");
        }
        return room.getStatus();
    }

    private boolean reviewedRunMatches(Long meetingId, Long expectedRunEpoch) {
        if (expectedRunEpoch == null) return true; // Direct unit fixture and legacy caller.
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        return room != null && Objects.equals(room.getRunEpoch(), expectedRunEpoch);
    }

    private void assertReviewedMeetingState(Long meetingId, Integer expectedStatus,
                                            Long expectedRunEpoch) {
        if (!Objects.equals(expectedStatus, requireReviewedMeetingStatus(meetingId))
                || !reviewedRunMatches(meetingId, expectedRunEpoch)
                || (!MeetingStatusEnum.IN_PROGRESS.getCode().equals(expectedStatus)
                    && !MeetingStatusEnum.REOPENED.getCode().equals(expectedStatus))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_STATE_CHANGED_DURING_SEARCH");
        }
    }

    private Long latestRoundHumanOpinionId(Long meetingId, int roundNumber) {
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        if (opinions == null) return null;
        return opinions.stream()
                .filter(opinion -> Objects.equals(opinion.getMemberId(), 0L))
                .map(AiMeetingOpinion::getId).filter(Objects::nonNull)
                .max(Long::compareTo).orElse(null);
    }

    private void assertReviewedContext(Long meetingId, Integer expectedStatus, Long expectedRunEpoch,
                                       int roundNumber, String asOf, Long contextOpinionId) {
        assertReviewedMeetingState(meetingId, expectedStatus, expectedRunEpoch);
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null || (room.getCurrentRound() != null && room.getCurrentRound() != roundNumber)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_ROUND_CHANGED_DURING_SEARCH");
        }
        if (!Objects.equals(contextOpinionId, latestRoundHumanOpinionId(meetingId, roundNumber))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_HUMAN_CONTEXT_CHANGED");
        }
        if (!Objects.equals(asOf, reviewedAsOfSupplier.get())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_PLAN_STALE");
        }
    }

    private AiMeetingOpinion generateReviewedOpinion(Long meetingId, AiMeetingMember member,
                                                     int roundNumber, ReviewedRoundEvidence evidence) {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, roundNumber, member.getId(), MeetingCallContext.SPEECH)) {
            return generateReviewedOpinionInScope(meetingId, member, roundNumber, evidence);
        }
    }

    private AiMeetingOpinion generateReviewedOpinionInScope(Long meetingId, AiMeetingMember member,
                                                            int roundNumber, ReviewedRoundEvidence evidence) {
        if (supportsBuiltInSearch(member.getModelType())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_SEARCH_CANNOT_BE_DISABLED");
        }
        String systemPrompt = buildSystemPrompt(member) + "\n资料规划和搜索已在模型外完成。禁止自行联网或引用本轮资料包之外的新事实。"
                + "标为待核验的资料只可用作明确标注的条件假设，不得写成已证实事实；与复核结果冲突的候选不得引用。"
                + "只对已审核通过的事实和可用结构化数据作确定性推理。若没有已核验的事实，只能指出缺口或作不含新增事实的条件分析。"
                + "数值只能引用资料包直接提供的值或明确标注为系统已校验的派生值；不得自行新增差值、比率、利润率、目标价等数字。"
                + "引用数值请保留来源精度及正负号，资金净流入为负数时保留负数，不改写为正数净流出金额。"
                + "来源涨跌幅为负值时，写带负号的涨跌幅，不改写为正数跌幅。"
                + "归母净利润与合并净利润、累计期间与单季期间等口径未经校验时不得混用。可作不新增数字的定性或条件分析。"
                + "若本轮已审核数字与前序议员同口径数字不同，须在发言中明确指出这一差异，不能静默替换。";
        assertReviewedContext(meetingId, evidence.meetingStatusAtStart, evidence.expectedRunEpoch,
                evidence.roundNumber, evidence.asOf, evidence.contextOpinionId);
        String searchContext = ("LOW_CONFIDENCE".equals(evidence.status)
                ? "【本轮资料未充分交叉核验】候选数字仅是待核验假设；如引用必须紧邻写明待核验及其限制。不能把证据评分当作事实概率。\n"
                : "") + buildSearchContext(evidence.results, null, evidence.plan);
        JSONArray unsearchedGaps = evidence.decision.getJSONArray("unsearchedGaps");
        if (unsearchedGaps != null && !unsearchedGaps.isEmpty()) {
            searchContext += "\n【资料预算未覆盖的缺口】以下问题本轮没有执行搜索，也没有通过审核；"
                    + "不能把这些问题的数字或结论写成事实："
                    + unsearchedGaps.toJSONString() + "\n";
        }
        JSONArray partiallyReviewedGaps = evidence.decision.getJSONArray("partiallyReviewedGaps");
        if (partiallyReviewedGaps != null && !partiallyReviewedGaps.isEmpty()) {
            searchContext += "\n【部分资料已审核通过的缺口】以下问题尚未完整核验，但资料包中标为已审核的事实仍可引用；"
                    + "未核验部分只能按各条资料的状态处理，已拒绝的候选不得引用。"
                    + "未读取链接原文是审核方法的限制，不会将双会话搜索审核已通过的事实改为待核验："
                    + partiallyReviewedGaps.toJSONString() + "\n";
        }
        JSONArray noUsableReviewedGaps = evidence.decision.getJSONArray("noUsableReviewedGaps");
        if (noUsableReviewedGaps != null && !noUsableReviewedGaps.isEmpty()) {
            searchContext += "\n【本轮没有可引用事实的缺口】以下问题没有得到可引用的已审核事实；"
                    + "不得引用相关候选数字或结论，只能基于其他已审核资料分析："
                    + noUsableReviewedGaps.toJSONString() + "\n";
        }
        String context = orchestrator.buildDebateContext(meetingId, member.getId(), roundNumber, searchContext);
        ChatResult result = callReviewedSpeaker(member, systemPrompt, context);
        String[] parsed = parseReviewedSpeech(result, evidence);
        String[] numberCorpora = buildReviewedNumberCorpora(meetingId, roundNumber, evidence);
        String stockSymbol = evidence.plan == null ? null : evidence.plan.eastmoneySymbol;
        ReviewedNumberGate.Audit numbers = ReviewedNumberGate.audit(
                parsed[1], numberCorpora[0], numberCorpora[1], stockSymbol);
        int numberRewrites = 0;
        if (numbers.hasUnsupported()) {
            assertReviewedContext(meetingId, evidence.meetingStatusAtStart, evidence.expectedRunEpoch,
                    evidence.roundNumber, evidence.asOf, evidence.contextOpinionId);
            numberRewrites = 1;
            try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.SPEECH_REWRITE)) {
                result = callReviewedSpeaker(member, systemPrompt,
                        context + "\n\n" + ReviewedNumberGate.rewriteInstruction(numbers));
            }
            parsed = parseReviewedSpeech(result, evidence);
            numbers = ReviewedNumberGate.audit(parsed[1], numberCorpora[0], numberCorpora[1], stockSymbol);
            if (numbers.hasUnsupported()) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_UNSUPPORTED_CLAIMS");
            }
        }
        JSONObject crossMemberConflict = findReviewedCrossMemberNumericConflict(
                meetingId, member.getId(), roundNumber, evidence, parsed[1]);
        if (crossMemberConflict != null && crossMemberConflict.getIntValue("undisclosedCount") > 0) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_CROSS_MEMBER_NUMERIC_CONFLICT");
        }
        JSONObject stored = JSON.parseObject(buildEvidenceJson(meetingId, member.getId(), roundNumber,
                parsed[0], evidence.executedQueries,
                evidence.results, evidence.plan, result, null, null, null));
        if (crossMemberConflict != null) {
            stored.put("reviewedCrossMemberConflict", crossMemberConflict);
        }
        stored.put("reviewedSearchDecision", evidence.decision);
        if (!evidence.packets.isEmpty()) {
            stored.put("reviewedSearchPacket", evidence.packets.get(0));
            stored.put("reviewedSearchPackets", evidence.packets);
        }
        stored.put("reviewedSearchStatus", evidence.status);
        stored.put("reviewedNumberAudit", numbers.toJson(numberRewrites));
        if (evidence.decision.getIntValue("unsearchedGapCount") > 0) {
            stored.put("reviewedSearchCoverageStatus", "UNSEARCHED_BUDGET");
        } else if (evidence.decision.getIntValue("unverifiedGapCount") > 0) {
            stored.put("reviewedSearchCoverageStatus", "UNVERIFIED_GAP");
        }
        stored.put("reviewedSearchConfidenceScore", evidence.confidenceScore);
        stored.put("reviewedSearchConfidenceBasis", "UNCALIBRATED_POLICY_SCORE");
        stored.put("reviewedSearchAsOf", evidence.asOf);
        stored.put("reviewedSearchPlanId", evidence.planId);
        stored.put("reviewedSearchSupersedesPlanId", evidence.supersedesPlanId);
        stored.put("reviewedSearchContextOpinionId", evidence.contextOpinionId);
        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(meetingId);
        opinion.setMemberId(member.getId());
        opinion.setRoundNumber(roundNumber);
        opinion.setParentOpinionId(evidence.targetQuestionId);
        opinion.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
        String published = numbers.label(parsed[1]);
        opinion.setContent("LOW_CONFIDENCE".equals(evidence.status)
                ? "【本轮资料未充分交叉核验：待核验数字仅作条件分析】\n" + published
                : published);
        opinion.setEvidence(stored.toJSONString());
        assertReviewedContext(meetingId, evidence.meetingStatusAtStart, evidence.expectedRunEpoch,
                evidence.roundNumber, evidence.asOf, evidence.contextOpinionId);
        if (evidence.expectedRunEpoch != null) {
            reviewedOpinionWriter.insertIfCurrent(opinion, evidence.expectedRunEpoch,
                    evidence.meetingStatusAtStart, evidence.contextOpinionId);
        } else {
            opinionMapper.insert(opinion);
        }
        return opinion;
    }

    private ChatResult callReviewedSpeaker(AiMeetingMember member, String systemPrompt, String prompt) {
        ChatResult result;
        try {
            result = orchestrator.callModelExStrict(member.getModelType(), systemPrompt, prompt);
        } catch (RuntimeException providerFailure) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_PROVIDER_ERROR", providerFailure);
        }
        if (result != null && result.modelStatus != null
                && !"success".equalsIgnoreCase(result.modelStatus)) {
            String status = result.modelStatus.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
            if (status.length() > 64) status = "FAILED";
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_" + status);
        }
        if (result == null || result.content == null || result.content.isBlank()
                || result.hasSearchEvidence()) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_OUTPUT_UNAVAILABLE");
        }
        return result;
    }

    /** Numbers are judged by {@link ReviewedNumberGate}; the remaining claim audit still blocks the turn. */
    private String[] parseReviewedSpeech(ChatResult result, ReviewedRoundEvidence evidence) {
        String[] parsed = parseEvidenceAndContent(result.content);
        if (parsed[1] == null || parsed[1].isBlank()) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_CONTENT_EMPTY");
        }
        JSONObject audit = buildClaimAudit(result, evidence.results, evidence.plan);
        if (audit != null && audit.getIntValue("guardedTermViolationCount")
                + audit.getIntValue("stockCodeViolationCount")
                + audit.getIntValue("targetMismatchViolationCount")
                + audit.getIntValue("formatViolationCount") > 0) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_UNSUPPORTED_CLAIMS");
        }
        return parsed;
    }

    /**
     * Returns {verified, low-confidence} evidence text for the number gate. Earlier reviewed speeches in
     * this meeting contribute their stored evidence, so a speaker can cite or dispute another member's
     * number and that number keeps the confidence of the evidence it came from.
     */
    private String[] buildReviewedNumberCorpora(Long meetingId, int roundNumber, ReviewedRoundEvidence evidence) {
        StringBuilder verified = new StringBuilder(buildSearchEvidenceCorpus(evidence.results)).append('\n');
        StringBuilder provisional = new StringBuilder();
        for (WebSearchService.SearchResult result : evidence.results) {
            if (result != null && isProvisionalResult(result)) provisional.append(result.snippet).append('\n');
        }
        ReviewedNumberGate.StoredEvidence earlier = ReviewedNumberGate.fromStoredOpinions(
                opinionMapper.selectByMeetingId(meetingId), roundNumber);
        verified.append(earlier.verified);
        provisional.append(earlier.provisional);
        return new String[]{verified.toString(), provisional.toString()};
    }

    /**
     * A reviewed speaker may disagree with a previous speaker, but may not quietly replace an
     * earlier verified number for the same stock, metric and observation period. Ambiguous scope
     * is deliberately left to human review rather than compared as if it were the same fact.
     */
    private JSONObject findReviewedCrossMemberNumericConflict(Long meetingId, Long memberId,
                                                               int roundNumber, ReviewedRoundEvidence evidence,
                                                               String speech) {
        String symbol = evidence.plan == null ? null : evidence.plan.eastmoneySymbol;
        if (symbol == null || symbol.isBlank()) return null;
        List<ReviewedComparableNumber> current = reviewedNumbersFromLiveResults(evidence.results, evidence.asOf);
        if (current.isEmpty()) return null;
        List<AiMeetingOpinion> earlier = opinionMapper.selectByMeetingId(meetingId);
        if (earlier == null || earlier.isEmpty()) return null;

        JSONArray conflicts = new JSONArray();
        int undisclosed = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (AiMeetingOpinion prior : earlier) {
            if (prior == null || prior.getMemberId() == null
                    || prior.getMemberId().equals(memberId)
                    || !OpinionTypeEnum.STATEMENT.getCode().equals(prior.getOpinionType())
                    || (prior.getRoundNumber() != null && prior.getRoundNumber() > roundNumber)
                    || prior.getEvidence() == null) continue;
            JSONObject priorEvidence;
            try { priorEvidence = JSON.parseObject(prior.getEvidence()); }
            catch (RuntimeException invalid) { continue; }
            if (priorEvidence == null || priorEvidence.getString("reviewedSearchStatus") == null) continue;
            JSONObject priorPlan = priorEvidence.getJSONObject("searchPlan");
            if (priorPlan == null || !symbol.equals(priorPlan.getString("eastmoneySymbol"))) continue;
            List<ReviewedComparableNumber> previous = reviewedNumbersFromStoredResults(
                    priorEvidence.getJSONArray("results"), priorEvidence.getString("reviewedSearchAsOf"));
            for (ReviewedComparableNumber now : current) {
                if (!reviewedSpeechStatesNumber(speech, now)) continue;
                for (ReviewedComparableNumber then : previous) {
                    if (!now.comparableTo(then) || !isConflictingFact(now.fact, then.fact)
                            || !reviewedSpeechStatesNumber(prior.getContent(), then)) continue;
                    String key = prior.getId() + "|" + now.key + "|" + now.fact.rawValue;
                    if (!seen.add(key)) continue;
                    boolean disclosed = reviewedSpeechDisclosesDifference(speech, then);
                    JSONObject item = new JSONObject(true);
                    item.put("previousOpinionId", prior.getId());
                    item.put("metric", now.fact.label);
                    item.put("period", now.period);
                    item.put("previousValue", then.fact.rawValue);
                    item.put("currentValue", now.fact.rawValue);
                    item.put("disclosed", disclosed);
                    conflicts.add(item);
                    if (!disclosed) undisclosed++;
                }
            }
        }
        if (conflicts.isEmpty()) return null;
        JSONObject result = new JSONObject(true);
        result.put("status", undisclosed > 0 ? "UNDISCLOSED" : "DISCLOSED");
        result.put("conflictCount", conflicts.size());
        result.put("undisclosedCount", undisclosed);
        result.put("conflicts", conflicts);
        return result;
    }

    private List<ReviewedComparableNumber> reviewedNumbersFromLiveResults(
            List<WebSearchService.SearchResult> results, String asOf) {
        List<ReviewedComparableNumber> numbers = new ArrayList<>();
        if (results == null) return numbers;
        for (WebSearchService.SearchResult result : results) {
            if (result == null || isProvisionalResult(result)) continue;
            if ("STRUCTURED_DATA".equals(result.sourceType)) {
                JSONObject payload = parseReviewedFactPayload(result.snippet);
                for (NumericFact fact : extractFactsFromStructuredResult(result, null, result.source)) {
                    addReviewedComparableNumber(numbers, fact, payload, asOf);
                }
            } else if ("CROSS_CHECKED_SEARCH".equals(result.sourceType)) {
                for (NumericFact fact : extractReviewedDatedFacts(result.snippet, result.source)) {
                    addReviewedComparableNumber(numbers, fact, null, asOf);
                }
            }
        }
        return numbers;
    }

    private List<ReviewedComparableNumber> reviewedNumbersFromStoredResults(JSONArray results, String asOf) {
        List<ReviewedComparableNumber> numbers = new ArrayList<>();
        if (results == null) return numbers;
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            if (item == null) continue;
            String sourceType = item.getString("sourceType");
            if ("STRUCTURED_DATA".equals(sourceType)) {
                JSONObject payload = parseReviewedFactPayload(item.getString("snippet"));
                for (NumericFact fact : extractFactsFromFactJsonArray(
                        item.getJSONArray("facts"), null, item.getString("source"))) {
                    addReviewedComparableNumber(numbers, fact, payload, asOf);
                }
            } else if ("CROSS_CHECKED_SEARCH".equals(sourceType)) {
                for (NumericFact fact : extractReviewedDatedFacts(item.getString("snippet"),
                        item.getString("source"))) {
                    addReviewedComparableNumber(numbers, fact, null, asOf);
                }
            }
        }
        return numbers;
    }

    private JSONObject parseReviewedFactPayload(String snippet) {
        if (snippet == null || !snippet.trim().startsWith("{")) return null;
        try { return JSON.parseObject(snippet); }
        catch (RuntimeException invalid) { return null; }
    }

    private List<NumericFact> extractReviewedDatedFacts(String snippet, String source) {
        List<NumericFact> facts = new ArrayList<>();
        if (snippet == null) return facts;
        Matcher matcher = REVIEWED_DATED_FACT_PATTERN.matcher(snippet);
        while (matcher.find()) {
            NumericFact fact = new NumericFact();
            fact.label = matcher.group(1);
            fact.labelKey = normalizeFactLabel(fact.label);
            fact.value = Double.parseDouble(matcher.group(2));
            fact.unit = matcher.group(3);
            fact.rawValue = matcher.group(2) + fact.unit;
            fact.source = source;
            facts.add(fact);
        }
        return facts;
    }

    private void addReviewedComparableNumber(List<ReviewedComparableNumber> numbers, NumericFact fact,
                                              JSONObject payload, String asOf) {
        if (fact == null || fact.labelKey == null || !Double.isFinite(fact.value)) return;
        String period;
        String key;
        if (fact.path != null && !fact.path.isBlank()) {
            key = fact.path;
            if (key.startsWith("financials[0].")) {
                Object reportDate = readJsonPath(payload, "financials[0].reportDate");
                period = reportDate == null ? null : String.valueOf(reportDate);
                if (period == null || !period.matches("20\\d{2}-\\d{2}-\\d{2}")) return;
            } else if (key.startsWith("quote.") || key.startsWith("stockDetail.")
                    || key.startsWith("technicalIndicators.") || key.startsWith("valuation.")
                    || key.startsWith("anchorFacts.")) {
                period = asOf;
            } else {
                return;
            }
        } else {
            if (YEAR_PATTERN.matcher(fact.label).find()) {
                key = fact.labelKey;
                period = key;
            } else {
                return;
            }
        }
        if (period == null || period.isBlank()) return;
        numbers.add(new ReviewedComparableNumber(fact, key, period));
    }

    private boolean reviewedSpeechStatesNumber(String speech, ReviewedComparableNumber number) {
        if (speech == null || speech.isBlank() || number.fact.label == null) return false;
        Pattern assertion = Pattern.compile(Pattern.quote(number.fact.label)
                + "(?:\\s|[:：,，]|为|是|约|达|到|报|在){0,8}([-+]?\\d[\\d,]*(?:\\.\\d+)?)\\s*(%|亿元|万元|元|亿|万|倍|股|户|次)?",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = assertion.matcher(speech);
        while (matcher.find()) {
            Double stated = parseNumericValue(matcher.group(1));
            String statedUnit = matcher.group(2) == null ? "" : matcher.group(2);
            if (stated == null || !statedUnit.equals(number.fact.unit)
                    || Math.abs(stated - number.fact.value) > Math.max(0.000001,
                    Math.abs(number.fact.value) * 0.00001)) continue;
            String prefix = speech.substring(Math.max(0, matcher.start() - 12), matcher.start());
            if (!containsAny(prefix, "假设", "如果", "若", "预计", "预测", "目标", "情景", "可能")) return true;
        }
        return false;
    }

    private boolean reviewedSpeechDisclosesDifference(String speech, ReviewedComparableNumber previous) {
        if (speech == null || previous.fact.label == null) return false;
        int labelAt = speech.indexOf(previous.fact.label);
        if (labelAt < 0) return false;
        String vicinity = speech.substring(Math.max(0, labelAt - 35),
                Math.min(speech.length(), labelAt + previous.fact.label.length() + 35));
        return containsAny(vicinity, "前序", "前述", "此前", "其他议员", "上一位")
                && containsAny(vicinity, "冲突", "不同", "分歧", "修正", "更正");
    }

    private static final class ReviewedComparableNumber {
        private final NumericFact fact;
        private final String key;
        private final String period;

        private ReviewedComparableNumber(NumericFact fact, String key, String period) {
            this.fact = fact;
            this.key = key;
            this.period = period;
        }

        private boolean comparableTo(ReviewedComparableNumber other) {
            return other != null && key.equals(other.key) && period.equals(other.period)
                    && Objects.equals(fact.unit, other.fact.unit);
        }
    }

    private void pauseForReviewedEvidence(Long meetingId, Long expectedRunEpoch, String reason) {
        log.warn("Meeting {} paused before voting: {}", meetingId, reason);
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room != null && (MeetingStatusEnum.IN_PROGRESS.getCode().equals(room.getStatus())
                || MeetingStatusEnum.REOPENED.getCode().equals(room.getStatus()))) {
            if (expectedRunEpoch == null) {
                roomMapper.updateStatus(meetingId, MeetingStatusEnum.PAUSED.getCode());
                workflowService.markReviewedEvidenceBlocked(meetingId, reason);
            } else if (roomMapper.updateStatusIfCurrentAndEpoch(meetingId, room.getStatus(),
                    expectedRunEpoch, MeetingStatusEnum.PAUSED.getCode()) == 1) {
                workflowService.markReviewedEvidenceBlocked(meetingId, reason);
            }
        }
    }

    private static final class ReviewedRoundEvidence {
        private final SearchPlan plan;
        private final List<WebSearchService.SearchResult> results;
        private final List<JSONObject> packets;
        private final List<String> executedQueries;
        private final JSONObject decision;
        private final String status;
        private final BigDecimal confidenceScore;
        private final Integer meetingStatusAtStart;
        private final Long targetQuestionId;
        private final Long expectedRunEpoch;
        private final int roundNumber;
        private final String asOf;
        private final Long contextOpinionId;
        private final Long planId;
        private final Long supersedesPlanId;

        private ReviewedRoundEvidence(SearchPlan plan, List<WebSearchService.SearchResult> results,
                                      List<JSONObject> packets, List<String> executedQueries,
                                      JSONObject decision, String status, BigDecimal confidenceScore,
                                      Integer meetingStatusAtStart,
                                      Long targetQuestionId, Long expectedRunEpoch,
                                      int roundNumber, String asOf, Long contextOpinionId,
                                      Long planId, Long supersedesPlanId) {
            this.plan = plan;
            this.results = results;
            this.packets = packets;
            this.executedQueries = executedQueries;
            this.decision = decision;
            this.status = status;
            this.confidenceScore = confidenceScore;
            this.meetingStatusAtStart = meetingStatusAtStart;
            this.targetQuestionId = targetQuestionId;
            this.expectedRunEpoch = expectedRunEpoch;
            this.roundNumber = roundNumber;
            this.asOf = asOf;
            this.contextOpinionId = contextOpinionId;
            this.planId = planId;
            this.supersedesPlanId = supersedesPlanId;
        }
    }

    private ReviewedSearchDecision decideReviewedSearchNeeds(Long meetingId, AiMeetingMember member,
                                                              int roundNumber) {
        if (supportsBuiltInSearch(member.getModelType())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_SEARCH_CANNOT_BE_DISABLED");
        }
        String prompt = orchestrator.buildReviewedEvidenceNeedsPrompt(meetingId, member.getId(), roundNumber);
        String raw;
        try {
            raw = orchestrator.callModelStrict(member.getModelType(),
                    systemPromptForDecision(member), prompt, 500);
        } catch (RuntimeException providerFailure) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_PLAN_PROVIDER_ERROR",
                    providerFailure);
        }
        return parseReviewedSearchDecision(meetingId, raw);
    }

    private ReviewedSearchDecision parseReviewedSearchDecision(Long meetingId, String raw) {
        JSONObject answer;
        try {
            String value = raw == null ? "" : raw.trim();
            if (value.startsWith("```")) {
                value = value.replaceFirst("^\\x60{3}(?:json)?\\s*", "")
                        .replaceFirst("\\s*\\x60{3}$", "").trim();
            }
            if (!value.startsWith("{") || !value.endsWith("}")) throw new IllegalArgumentException("not_json");
            answer = JSON.parseObject(value);
        } catch (Exception e) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_PLAN_INVALID", e);
        }
        if (answer == null || !(answer.get("need") instanceof Boolean)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_PLAN_NEED_MISSING");
        }
        SearchPlan plan = defaultSearchPlan(meetingId);
        StockRef declaredStock = parseStockRef(answer.getString("eastmoneySymbol"),
                answer.getString("eastmoneyMarket"));
        if (declaredStock != null) {
            plan.eastmoneySymbol = declaredStock.symbol;
            plan.eastmoneyMarket = declaredStock.market;
        }
        Object needsValue = answer.get("needs");
        if (needsValue == null) needsValue = answer.get("items");
        JSONArray rawNeeds = needsValue instanceof JSONArray ? (JSONArray) needsValue : null;
        if (!answer.getBooleanValue("need")) {
            if (rawNeeds != null && !rawNeeds.isEmpty()) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_PLAN_CONFLICT");
            }
            JSONObject normalized = new JSONObject(true);
            normalized.put("need", false);
            normalized.put("needs", new JSONArray());
            return new ReviewedSearchDecision(plan, Collections.emptyList(), normalized);
        }
        if (rawNeeds == null || rawNeeds.isEmpty() || rawNeeds.size() > 3) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_NEEDS_INVALID");
        }
        List<ReviewedNeed> needs = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        JSONArray normalizedNeeds = new JSONArray();
        for (int i = 0; i < rawNeeds.size(); i++) {
            JSONObject item = rawNeeds.getJSONObject(i);
            if (item == null || !(item.get("webSearch") instanceof Boolean)) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_NEED_SCHEMA_INVALID");
            }
            String id = item.getString("id");
            String question = item.getString("question");
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)
                    || question == null || question.isBlank() || question.length() > 400) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_NEED_ID_OR_QUESTION_INVALID");
            }
            StockRef itemStock = parseStockRef(item.getString("eastmoneySymbol"),
                    item.getString("eastmoneyMarket"));
            if (itemStock == null) itemStock = parseStockRef(question, null);
            if (itemStock == null) itemStock = resolveKnownAshareRef(question);
            if (itemStock == null) itemStock = declaredStock;
            JSONArray requested = item.getJSONArray("eastmoneyFields");
            List<String> fields = new ArrayList<>();
            List<String> unsupportedFields = new ArrayList<>();
            List<String> granularFields = new ArrayList<>();
            JSONArray restoredUnsupported = item.getJSONArray("unsupportedEastmoneyFields");
            if (restoredUnsupported != null) {
                for (Object unsupported : restoredUnsupported) {
                    if (!(unsupported instanceof String) || ((String) unsupported).isBlank()
                            || ((String) unsupported).length() > 64) {
                        throw new MeetingReviewedEvidenceService.EvidenceRejected(
                                "MEMBER_EASTMONEY_FIELD_INVALID");
                    }
                    addDistinctIgnoreCase(unsupportedFields, (String) unsupported);
                }
            }
            if (requested != null) {
                for (Object field : requested) {
                    if (!(field instanceof String)) {
                        throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_EASTMONEY_FIELD_INVALID");
                    }
                    String rawField = ((String) field).trim();
                    if (!rawField.matches("[\\p{L}\\p{N}_-]{1,64}")) {
                        throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_EASTMONEY_FIELD_INVALID");
                    }
                    String normalizedField = rawField.toLowerCase(Locale.ROOT)
                            .replace("-", "").replace("_", "");
                    String canonical = reviewedEastmoneyFieldAlias(normalizedField);
                    if (canonical == null) {
                        String unsupported = "fundflow".equals(normalizedField)
                                && containsAny(question, "20个交易日", "近20日", "最近20日", "20日")
                                ? "20日主力资金汇总"
                                : containsAny(normalizedField, "margin", "financingbalance")
                                && question.contains("融资余额") ? "融资余额" : rawField;
                        addDistinctIgnoreCase(unsupportedFields, unsupported);
                        continue;
                    }
                    if (!fields.contains(canonical)) fields.add(canonical);
                    if (reviewedGranularFieldKey(normalizedField) != null
                            && !granularFields.contains(normalizedField)) granularFields.add(normalizedField);
                }
            }
            for (String unsupported : inferUnsupportedEastmoneyMetrics(question)) {
                addDistinctIgnoreCase(unsupportedFields, unsupported);
            }
            if (itemStock != null) {
                for (String inferred : inferReviewedEastmoneyFields(question)) {
                    if (!fields.contains(inferred)) fields.add(inferred);
                }
                for (String inferred : inferReviewedGranularFields(question)) {
                    if ("ma120".equals(inferred)) continue;
                    String canonical = reviewedEastmoneyFieldAlias(inferred);
                    if (canonical != null && !fields.contains(canonical)) fields.add(canonical);
                    if (canonical != null && !granularFields.contains(inferred)) granularFields.add(inferred);
                }
            }
            boolean webSearchRequired = item.getBooleanValue("webSearch");
            if (fields.isEmpty() && unsupportedFields.isEmpty() && !webSearchRequired) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_NEED_NO_SOURCE");
            }
            List<String> sourceFocus = new ArrayList<>();
            Object focusValue = item.get("sourceFocus");
            JSONArray requestedDomains = focusValue instanceof JSONArray ? (JSONArray) focusValue : null;
            if (requestedDomains != null) {
                for (Object domain : requestedDomains) {
                    if (!(domain instanceof String)) {
                        throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_SOURCE_INVALID");
                    }
                    sourceFocus.add((String) domain);
                }
            } else if (focusValue instanceof String
                    && ((String) focusValue).trim().matches("[a-zA-Z0-9.-]{3,120}")
                    && ((String) focusValue).contains(".")) {
                sourceFocus.add(((String) focusValue).trim());
            }
            sourceFocus = normalizeSourceFocus(sourceFocus);
            if (sourceFocus.stream().anyMatch(domain -> !domain.matches("[a-z0-9.-]{3,120}")
                            || !domain.contains(".") || domain.startsWith(".") || domain.endsWith("."))) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_SOURCE_LIMIT");
            }
            int sourceFocusCount = sourceFocus.size();
            if (sourceFocusCount > 3) {
                log.warn("Meeting {} reviewed need {} proposed {} source domains; retaining first three",
                        meetingId, id, sourceFocusCount);
                sourceFocus = new ArrayList<>(sourceFocus.subList(0, 3));
            }
            String webQuestion = item.getString("webQuestion");
            if (webQuestion != null) webQuestion = webQuestion.trim();
            if (webSearchRequired && itemStock != null && !fields.isEmpty() && unsupportedFields.isEmpty()
                    && (webQuestion == null || webQuestion.isBlank())) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_WEB_GAP_MISSING");
            }
            if (webQuestion != null && webQuestion.length() > 400) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_WEB_GAP_INVALID");
            }
            List<String> requiredFieldPaths = reviewedRequiredFieldPaths(item, fields, question);
            ReviewedNeed need = new ReviewedNeed(id, question.trim(), fields, granularFields, unsupportedFields,
                    requiredFieldPaths, webSearchRequired, webQuestion, sourceFocus, itemStock);
            needs.add(need);
            plan.queries.add(need.question);
            plan.sourceFocus.addAll(sourceFocus);
            for (String field : fields) {
                if (!plan.eastmoneyFields.contains(field)) plan.eastmoneyFields.add(field);
            }
            JSONObject normalizedNeed = new JSONObject(true);
            normalizedNeed.put("id", id);
            normalizedNeed.put("question", need.question);
            normalizedNeed.put("eastmoneyFields", fields);
            if (!requiredFieldPaths.isEmpty()) normalizedNeed.put("requiredFieldPaths", requiredFieldPaths);
            if (!unsupportedFields.isEmpty()) normalizedNeed.put("unsupportedEastmoneyFields", unsupportedFields);
            normalizedNeed.put("webSearch", webSearchRequired);
            if (webQuestion != null && !webQuestion.isBlank()) normalizedNeed.put("webQuestion", webQuestion);
            normalizedNeed.put("sourceFocus", sourceFocus);
            if (sourceFocusCount > 3) {
                normalizedNeed.put("sourceFocusTruncated", true);
                normalizedNeed.put("sourceFocusDiscardedCount", sourceFocusCount - sourceFocus.size());
            }
            if (itemStock != null) {
                normalizedNeed.put("eastmoneySymbol", itemStock.symbol);
                normalizedNeed.put("eastmoneyMarket", itemStock.market);
            }
            normalizedNeeds.add(normalizedNeed);
        }
        List<StockRef> referencedStocks = needs.stream().map(need -> need.stockRef)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Set<String> distinctStocks = referencedStocks.stream()
                .map(ref -> ref.symbol + "." + ref.market)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctStocks.size() == 1) {
            StockRef ref = referencedStocks.get(0);
            plan.eastmoneySymbol = ref.symbol;
            plan.eastmoneyMarket = ref.market;
        } else if (distinctStocks.size() > 1) {
            plan.eastmoneySymbol = null;
            plan.eastmoneyMarket = null;
        }
        JSONObject normalized = new JSONObject(true);
        normalized.put("need", true);
        normalized.put("needs", normalizedNeeds);
        if (plan.eastmoneySymbol != null) {
            normalized.put("eastmoneySymbol", plan.eastmoneySymbol);
            normalized.put("eastmoneyMarket", plan.eastmoneyMarket);
        }
        return new ReviewedSearchDecision(plan, needs, normalized);
    }

    /** A field group is a provider capability, not proof that a specific requested value exists. */
    private List<String> reviewedRequiredFieldPaths(JSONObject item, List<String> fields, String question) {
        List<String> paths = new ArrayList<>();
        Object declared = item.get("requiredFieldPaths");
        if (declared != null) {
            if (!(declared instanceof JSONArray)) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_REQUIRED_FIELD_PATH_INVALID");
            }
            JSONArray declaredPaths = (JSONArray) declared;
            // A persisted eight-entry plan may contain ten paths after expanding the MACD bundle.
            boolean expandedMacd = declaredPaths.contains("technicalIndicators.macdDif")
                    && declaredPaths.contains("technicalIndicators.macdDea")
                    && declaredPaths.contains("technicalIndicators.macdHistogram");
            if (declaredPaths.size() > 8 && (!expandedMacd || declaredPaths.size() > 10)) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_REQUIRED_FIELD_PATH_INVALID");
            }
            for (Object value : declaredPaths) {
                if (!(value instanceof String) || !((String) value).matches(
                        "[A-Za-z][A-Za-z0-9]{0,39}(?:\\[\\])?\\.[A-Za-z][A-Za-z0-9]{0,63}")) {
                    throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_REQUIRED_FIELD_PATH_INVALID");
                }
                String path = (String) value;
                String root = path.substring(0, path.indexOf('.')).replace("[]", "");
                if (!fields.contains(root)) {
                    throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_REQUIRED_FIELD_PATH_INVALID");
                }
                if ("technicalIndicators.macd".equalsIgnoreCase(path)) {
                    addReviewedRequiredPath(paths, "technicalIndicators.macdDif");
                    addReviewedRequiredPath(paths, "technicalIndicators.macdDea");
                    addReviewedRequiredPath(paths, "technicalIndicators.macdHistogram");
                } else if ("quote.turnoverRate".equals(path)) {
                    addReviewedRequiredPath(paths, "quote.turnover");
                } else {
                    addReviewedRequiredPath(paths, path);
                }
            }
        }
        // Older persisted plans predate requiredFieldPaths. Preserve their concrete metric semantics.
        String lower = question.toLowerCase(Locale.ROOT);
        if (fields.contains("financials") && containsAny(lower, "经营现金流", "经营活动现金流", "经营活动产生的现金流")) {
            String path = containsAny(lower, "营收比", "收入比", "占营业收入", "占营收")
                    ? "financials[].cashToRevenuePct" : "financials[].netOperatingCashFlow";
            if (!paths.contains(path)) paths.add(path);
        }
        if (fields.contains("quote") && containsAny(lower, "最新价", "收盘价", "股价", "价格")) {
            addReviewedRequiredPath(paths, "quote.price");
        }
        if (fields.contains("financials")) {
            if (containsAny(lower, "营收", "营业收入") && !containsAny(lower, "营收比", "收入比", "占营业收入", "占营收")) addReviewedRequiredPath(paths,
                    "financials[]." + (lower.contains("同比") ? "revenueYoyPct" : "revenue"));
            if (containsAny(lower, "扣非", "非经常性损益后")) {
                addReviewedRequiredPath(paths, "financials[].netProfitExNonRecurring");
            } else if (lower.contains("净利润") && !lower.contains("净利润率")) {
                addReviewedRequiredPath(paths, "financials[]." + (lower.contains("同比") ? "netProfitYoyPct" : "netProfit"));
            }
            String[][] metrics = {{"毛利率", "grossMarginPct"}, {"净利率", "netMarginPct"},
                    {"净利润率", "netMarginPct"}, {"每股收益", "eps"}, {"eps", "eps"},
                    {"roe", "roePct"}, {"净资产收益率", "roePct"}, {"资产负债率", "debtRatioPct"},
                    {"应收账款周转", "receivableTurnover"}, {"存货周转", "inventoryTurnover"}};
            for (String[] metric : metrics) if (lower.contains(metric[0])) {
                addReviewedRequiredPath(paths, "financials[]." + metric[1]);
            }
        }
        if (fields.contains("valuation") && !lower.contains("分位")) {
            if (containsAny(lower, "pe", "市盈率")) addReviewedRequiredPath(paths, "valuation.peTtm");
            if (containsAny(lower, "pb", "市净率")) addReviewedRequiredPath(paths, "valuation.pbMrq");
        }
        if (fields.contains("valuation") && lower.contains("分位")) {
            Matcher years = Pattern.compile("(?<![0-9])([1-9]|10|一|二|两|三|四|五|六|七|八|九|十)年").matcher(lower);
            if (years.find()) {
                String number = years.group(1);
                int horizon = Character.isDigit(number.charAt(0)) ? Integer.parseInt(number)
                        : "两".equals(number) ? 2 : "一二三四五六七八九十".indexOf(number) + 1;
                String metric = containsAny(lower, "pb", "市净率") ? "pb"
                        : containsAny(lower, "pe", "市盈率") ? "pe" : null;
                if (metric != null) {
                    String path = "valuation." + metric + "Percentile" + horizon + "y";
                    if (!paths.contains(path)) paths.add(path);
                }
            }
        }
        return paths;
    }

    private void addReviewedRequiredPath(List<String> paths, String path) {
        if (!paths.contains(path)) paths.add(path);
    }

    private boolean reviewedRequiredPathAvailable(Map<String, Object> raw, String path, ReviewedNeed need) {
        if (raw == null) return false;
        int dot = path.indexOf('.');
        String section = path.substring(0, dot).replace("[]", "");
        String field = path.substring(dot + 1);
        Object content = raw.get(section);
        if (path.substring(0, dot).endsWith("[]")) {
            if (!(content instanceof List) || ((List<?>) content).isEmpty()) return false;
            List<?> rows = (List<?>) content;
            if ("financials".equals(section)) {
                String period = reviewedRequestedFinancialPeriod(need.question);
                if (period == null) {
                    // An old report's populated value cannot cover a missing value in the latest report.
                    return rows.get(0) instanceof Map && reviewedConcreteValue(((Map<?, ?>) rows.get(0)).get(field));
                }
                return rows.stream().anyMatch(row -> row instanceof Map
                        && String.valueOf(((Map<?, ?>) row).get("reportDate")).startsWith(period)
                        && reviewedConcreteValue(((Map<?, ?>) row).get(field)));
            }
            return rows.stream().anyMatch(row -> row instanceof Map
                    && reviewedConcreteValue(((Map<?, ?>) row).get(field)));
        }
        return content instanceof Map && reviewedConcreteValue(((Map<?, ?>) content).get(field));
    }

    private boolean reviewedConcreteValue(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                || value instanceof String && !((String) value).isBlank()
                    && !Arrays.asList("null", "n/a", "unknown", "--", "-", "未提供", "暂无")
                        .contains(((String) value).trim().toLowerCase(Locale.ROOT));
    }

    private String reviewedRequestedFinancialPeriod(String question) {
        Matcher explicitPeriod = Pattern.compile("(20\\d{2})年?(上半年|半年度报告|半年报|中报|第一季度|一季报|一季度|第二季度|二季度|第三季度|三季报|三季度|第四季度|四季度|年度报告|年报|全年)").matcher(question);
        if (explicitPeriod.find()) {
            String year = explicitPeriod.group(1);
            String kind = explicitPeriod.group(2);
            if (containsAny(kind, "上半年", "半年度报告", "半年报", "中报", "二季度", "第二季度")) return year + "-06-30";
            if (containsAny(kind, "一季度", "第一季度", "一季报")) return year + "-03-31";
            if (containsAny(kind, "三季度", "第三季度", "三季报")) return year + "-09-30";
            return year + "-12-31";
        }
        Matcher exactDate = Pattern.compile("(20\\d{2})[-/](0?[1-9]|1[0-2])[-/](0?[1-9]|[12][0-9]|3[01])").matcher(question);
        if (exactDate.find()) {
            try {
                return LocalDate.of(Integer.parseInt(exactDate.group(1)), Integer.parseInt(exactDate.group(2)),
                        Integer.parseInt(exactDate.group(3))).toString();
            } catch (RuntimeException invalidDate) { return "INVALID_REPORT_DATE"; }
        }
        Matcher year = YEAR_PATTERN.matcher(question);
        return year.find() ? year.group(1) : null;
    }

    private boolean reviewedRequiresSingleQuarterValue(String question) {
        if (containsAny(question, "单季", "单季度")) return true;
        if (containsAny(question, "累计", "年初至", "期末", "季度末")) return false;
        return containsAny(question, "第二季度", "二季度", "第三季度", "三季度", "第四季度", "四季度")
                && containsAny(question, "净利润", "营收", "营业收入", "现金流", "每股收益", "EPS", "eps", "毛利率", "净利率");
    }

    private boolean reviewedRequiresClosedObservation(String question) {
        return containsAny(question, "已收盘", "收盘价", "已完成", "已结束", "完整交易日", "完整日K");
    }

    private List<String> inferReviewedEastmoneyFields(String question) {
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>();
        if (containsAny(value, "最新价", "收盘价", "股价")) fields.add("quote");
        if (containsAny(value, "换手率", "成交量")) fields.add("dailyKline");
        if (containsAny(value, "量比")) fields.add("stockDetail");
        if (containsAny(value, "日k", "k线")) fields.add("dailyKline");
        if (containsAny(value, "均线", "macd", "rsi", "技术指标")) {
            if (!fields.contains("dailyKline")) fields.add("dailyKline");
            fields.add("technicalIndicators");
        }
        if (containsAny(value, "市盈率", "市净率", "估值", "pe", "pb")) fields.add("valuation");
        if (containsAny(value, "财报", "营收", "净利润", "毛利率")) fields.add("financials");
        if (containsAny(value, "公司公告", "交易所公告")) fields.add("announcements");
        if (containsAny(value, "股东户数")) fields.add("shareholderCounts");
        return fields;
    }

    private List<String> inferReviewedGranularFields(String question) {
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>();
        Matcher ma = REVIEWED_MA_PATTERN.matcher(value);
        while (ma.find()) {
            String field = "ma" + ma.group(1);
            if (!fields.contains(field)) fields.add(field);
        }
        for (String field : Arrays.asList("macd", "rsi", "boll", "atr", "obv")) {
            if (value.contains(field)) fields.add(field);
        }
        if (value.contains("布林") && !fields.contains("boll")) fields.add("boll");
        if (value.contains("成交量")) fields.add("volume");
        if (value.contains("换手率")) fields.add("turnover");
        if (value.contains("量比")) fields.add("volumeratio");
        return fields;
    }

    private String reviewedEastmoneyFieldAlias(String normalized) {
        if (containsAny(normalized, "ma120", "margin", "fundflow", "financingbalance")
                && !"intradayfundflow".equals(normalized)) return null;
        if ("volumeratio".equals(normalized)) return "stockDetail";
        if (containsAny(normalized, "volume", "turnover") && !"volumeratio".equals(normalized)) {
            return "dailyKline";
        }
        if (containsAny(normalized, "ma5", "ma10", "ma20", "ma60", "macd", "rsi", "boll", "atr", "obv")) {
            return "technicalIndicators";
        }
        return canonicalEastmoneyField(normalized);
    }

    private List<String> inferUnsupportedEastmoneyMetrics(String question) {
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<String> unsupported = new ArrayList<>();
        if (value.contains("ma120")) unsupported.add("MA120");
        if (value.contains("融资余额")) unsupported.add("融资余额");
        if (value.contains("主力资金") && containsAny(value, "20个交易日", "近20日", "最近20日", "20日")) {
            unsupported.add("20日主力资金汇总");
        }
        return unsupported;
    }

    private void addDistinctIgnoreCase(List<String> values, String candidate) {
        if (values.stream().noneMatch(value -> value.equalsIgnoreCase(candidate))) values.add(candidate);
    }

    private String reviewedGranularFieldKey(String normalized) {
        switch (normalized) {
            case "ma5": case "ma10": case "ma20": case "ma60":
            case "macd": case "rsi": case "boll": case "atr": case "obv":
            case "volumeratio": case "volume": case "turnover":
                return normalized;
            default:
                return null;
        }
    }

    private static final class ReviewedSearchDecision {
        private final SearchPlan plan;
        private final List<ReviewedNeed> needs;
        private final JSONObject raw;

        private ReviewedSearchDecision(SearchPlan plan, List<ReviewedNeed> needs, JSONObject raw) {
            this.plan = plan;
            this.needs = needs;
            this.raw = raw;
        }
    }

    private static final class ReviewedNeed {
        private final String id;
        private final String question;
        private final List<String> eastmoneyFields;
        private final List<String> granularFields;
        private final List<String> unsupportedFields;
        private final List<String> requiredFieldPaths;
        private final boolean webSearch;
        private final String webQuestion;
        private final List<String> sourceFocus;
        private final StockRef stockRef;

        private ReviewedNeed(String id, String question, List<String> eastmoneyFields,
                             List<String> granularFields,
                             List<String> unsupportedFields, List<String> requiredFieldPaths,
                             boolean webSearch, String webQuestion, List<String> sourceFocus,
                             StockRef stockRef) {
            this.id = id;
            this.question = question;
            this.eastmoneyFields = eastmoneyFields;
            this.granularFields = granularFields;
            this.unsupportedFields = unsupportedFields;
            this.requiredFieldPaths = requiredFieldPaths;
            this.webSearch = webSearch;
            this.webQuestion = webQuestion;
            this.sourceFocus = sourceFocus;
            this.stockRef = stockRef;
        }
    }

    private ReviewedEastmoneyEvidence fetchReviewedEastmoneyEvidence(Long meetingId, SearchPlan plan,
                                                                     ReviewedNeed need,
                                                                     String asOf,
                                                                     Map<String, EastmoneyMarketDataService.MarketDataBundle> cache) {
        List<String> requestedFields = need.eastmoneyFields;
        if (requestedFields.isEmpty()) return new ReviewedEastmoneyEvidence(null,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        StockRef ref = need.stockRef;
        if (ref == null) return new ReviewedEastmoneyEvidence(null, Collections.emptyList(),
                Collections.emptyList(), requestedFields);
        String key = ref.symbol + "." + ref.market;
        String cacheKey = asOf + ":" + key;
        EastmoneyMarketDataService.MarketDataBundle bundle = cache.get(cacheKey);
        if (bundle == null) {
            try {
                bundle = eastmoneyMarketDataService.buildBundle(ref.symbol, ref.market, "DAY");
                cache.put(cacheKey, bundle);
            } catch (Exception e) {
                log.warn("Reviewed Eastmoney lookup failed meetingId={} stock={} error={}",
                        meetingId, key, e.getMessage());
                return new ReviewedEastmoneyEvidence(ref, Collections.emptyList(),
                        Collections.emptyList(), requestedFields);
            }
        }
        Map<String, Object> raw = bundle == null ? null : bundle.getPayload();
        if (!reviewedBundleMatchesStock(raw, ref)) {
            log.warn("Reviewed Eastmoney bundle identity mismatch meetingId={} expected={}", meetingId, key);
            return new ReviewedEastmoneyEvidence(ref, Collections.emptyList(),
                    Collections.emptyList(), requestedFields);
        }
        List<String> available = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String field : requestedFields) {
            (reviewedEastmoneyFieldAvailable(raw, field)
                    && reviewedFieldCoversNeed(raw, field, need) ? available : missing).add(field);
        }
        if (available.isEmpty()) {
            missing.addAll(need.granularFields);
            missing.addAll(need.requiredFieldPaths);
            return new ReviewedEastmoneyEvidence(ref, Collections.emptyList(), available, missing);
        }
        Map<String, Object> compact = compactEastmoneyPayload(raw, available, need);
        Map<String, Object> promptPayload = new LinkedHashMap<>(compact);
        // Provider health is checked above and retained in the trace. Keep the prompt budget for the facts.
        promptPayload.remove("dataQuality");
        String snippet = serializeStructuredSearchSnippet(promptPayload, 14000);
        JSONObject delivered = JSON.parseObject(snippet);
        List<String> deliveredFields = new ArrayList<>();
        for (String field : available) {
            if (delivered.get(field) != null && reviewedFieldCoversNeed(delivered, field, need)) {
                deliveredFields.add(field);
            } else {
                missing.add(field);
            }
        }
        for (String granular : need.granularFields) {
            if (!reviewedGranularFieldAvailable(raw, granular)
                    || !reviewedProjectedGranularFieldAvailable(delivered, granular)) missing.add(granular);
        }
        for (String requiredPath : need.requiredFieldPaths) {
            if (!reviewedRequiredPathAvailable(raw, requiredPath, need)
                    || !reviewedRequiredPathAvailable(delivered, requiredPath, need)) {
                missing.add(requiredPath);
            }
        }
        if (deliveredFields.isEmpty()) return new ReviewedEastmoneyEvidence(ref, Collections.emptyList(),
                deliveredFields, missing);
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富优先结构化数据 " + key + " " + String.join(",", deliveredFields);
        result.url = "https://quote.eastmoney.com/" + ref.market + ref.symbol + ".html";
        result.snippet = snippet;
        result.withTrace("eastmoney", "STRUCTURED_DATA",
                "EASTMONEY:" + key + ":" + String.join(",", deliveredFields), JSON.toJSONString(compact));
        return new ReviewedEastmoneyEvidence(ref, Collections.singletonList(result), deliveredFields, missing);
    }

    private boolean reviewedEastmoneyFieldAvailable(Map<String, Object> raw, String field) {
        if (raw == null) return false;
        Object value = raw.get(field);
        if (value == null) return false;
        if (value instanceof List && ((List<?>) value).isEmpty()) return false;
        if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) return false;
        if ("dailyKline".equals(field) || "technicalIndicators".equals(field)) {
            Object quality = raw.get("sourceTradeDateQuality");
            if (quality instanceof Map && Boolean.FALSE.equals(((Map<?, ?>) quality).get("consistent"))) return false;
        }
        if (!reviewedSourceStatusOk(raw, field)) return false;
        if ("quote".equals(field)) {
            if (!(value instanceof Map)) return false;
            Object price = ((Map<?, ?>) value).get("price");
            Object source = ((Map<?, ?>) value).get("source");
            if (source != null && !String.valueOf(source).toLowerCase(Locale.ROOT).contains("eastmoney")) return false;
            return price instanceof Number && ((Number) price).doubleValue() > 0
                    && reviewedRecentTradeDate(raw, "quoteTradeDate", "quote", "timestamp");
        }
        if ("dailyKline".equals(field)) {
            return value instanceof List && ((List<?>) value).size() >= 30
                    && reviewedRecentTradeDate(raw, "dailyKlineTradeDate", "dailyKline", "date");
        }
        if ("technicalIndicators".equals(field)) {
            return reviewedEastmoneyFieldAvailable(raw, "dailyKline")
                    && value instanceof Map && ((Map<?, ?>) value).get("ma20") != null;
        }
        return true;
    }

    private boolean reviewedBundleMatchesStock(Map<String, Object> raw, StockRef ref) {
        if (raw == null || ref == null) return false;
        return ref.symbol.equals(String.valueOf(raw.get("symbol")))
                && ref.market.equalsIgnoreCase(String.valueOf(raw.get("market")));
    }

    private boolean reviewedFieldCoversNeed(Map<String, Object> raw, String field, ReviewedNeed need) {
        if (("dailyKline".equals(field) || "technicalIndicators".equals(field) || "quote".equals(field))
                && reviewedRequiresClosedObservation(need.question)
                && !reviewedObservationIsClosed(raw, field)) return false;
        if (Arrays.asList("financials", "valuation", "quote", "stockDetail", "technicalIndicators").contains(field)) {
            boolean mappedRequirement = need.requiredFieldPaths.stream().anyMatch(path ->
                    path.startsWith(field + ".") || path.startsWith(field + "[]."))
                    || need.granularFields.stream().anyMatch(metric -> field.equals(reviewedEastmoneyFieldAlias(metric)));
            // A broad group cannot prove an unrecognized concrete need, including old plans without paths.
            if (!mappedRequirement) return false;
        }
        if ("dailyKline".equals(field)) {
            Matcher days = REVIEWED_TRADING_DAYS_PATTERN.matcher(need.question);
            if (days.find()) {
                Object bars = raw.get("dailyKline");
                return bars instanceof List && ((List<?>) bars).size() >= Integer.parseInt(days.group(1));
            }
        }
        if ("financials".equals(field)) {
            if (reviewedRequiresSingleQuarterValue(need.question)) return false;
            String period = reviewedRequestedFinancialPeriod(need.question);
            if (period != null) {
                Object rows = raw.get("financials");
                return rows instanceof List && ((List<?>) rows).stream().anyMatch(row -> row instanceof Map
                        && String.valueOf(((Map<?, ?>) row).get("reportDate")).startsWith(period));
            }
        }
        if ("announcements".equals(field) || "latestNews".equals(field)) {
            // These adapters provide an index (news summaries are truncated), not event-level evidence.
            String kind = "announcements".equals(field) ? "公告" : "新闻";
            boolean indexOnly = containsAny(need.question, "最新" + kind + "标题", "最近" + kind + "标题",
                    "最新" + kind + "列表", "最近" + kind + "列表");
            if (!indexOnly) return reviewedExactIndexEntryCoversNeed(raw, field, need.question);
            return !containsAny(need.question, "内容", "风险", "影响", "金额", "产能", "原因", "详情");
        }
        return true;
    }

    private boolean reviewedExactIndexEntryCoversNeed(Map<String, Object> raw, String field, String question) {
        // An explicitly named document's existence/publication date is provable from the index.
        Matcher title = Pattern.compile("《([^《》]{2,160})》").matcher(question);
        if (!title.find() || !containsAny(question, "发布日期", "发布时间", "公告日期", "是否发布")) return false;
        String request = question.substring(0, title.start()) + question.substring(title.end());
        if (containsAny(request, "内容", "风险", "影响", "金额", "原因", "详情", "披露了")) return false;
        Object value = raw.get(field);
        if (!(value instanceof List)) return false;
        String expected = title.group(1);
        String dateField = "announcements".equals(field) ? "noticeDate" : "showTime";
        return ((List<?>) value).stream().anyMatch(row -> row instanceof Map
                && expected.equals(String.valueOf(((Map<?, ?>) row).get("title")))
                && reviewedConcreteValue(((Map<?, ?>) row).get(dateField)));
    }

    private boolean reviewedObservationIsClosed(Map<String, Object> raw, String field) {
        try {
            Object asOf = raw.get("asOf");
            if (asOf == null) return false;
            java.time.ZonedDateTime captured = java.time.ZonedDateTime.parse(String.valueOf(asOf))
                    .withZoneSameInstant(REVIEWED_MARKET_ZONE);
            LocalDate observationDate;
            if ("quote".equals(field)) {
                Object quote = raw.get("quote");
                if (!(quote instanceof Map) || !(((Map<?, ?>) quote).get("timestamp") instanceof Number)) return false;
                java.time.ZonedDateTime quotedAt = Instant.ofEpochSecond(
                        ((Number) ((Map<?, ?>) quote).get("timestamp")).longValue()).atZone(REVIEWED_MARKET_ZONE);
                // Reading a stale intraday quote after close does not turn it into a closing quote.
                if (quotedAt.toLocalTime().isBefore(java.time.LocalTime.of(15, 0))) return false;
                observationDate = quotedAt.toLocalDate();
            } else {
                Object bars = raw.get("dailyKline");
                if (!(bars instanceof List) || ((List<?>) bars).isEmpty()) return false;
                Object latest = ((List<?>) bars).get(((List<?>) bars).size() - 1);
                if (!(latest instanceof Map)) return false;
                observationDate = LocalDate.parse(String.valueOf(((Map<?, ?>) latest).get("date")).substring(0, 10));
            }
            return observationDate.isBefore(captured.toLocalDate())
                    || observationDate.equals(captured.toLocalDate())
                        && !captured.toLocalTime().isBefore(java.time.LocalTime.of(15, 5));
        } catch (RuntimeException invalidTimestamp) {
            return false;
        }
    }

    private boolean reviewedSourceStatusOk(Map<String, Object> raw, String field) {
        String source = "technicalIndicators".equals(field) || "anchorFacts".equals(field)
                ? "dailyKline" : field;
        Object quality = raw.get("dataQuality");
        if (!(quality instanceof Map)) return false;
        Object sources = ((Map<?, ?>) quality).get("sourceStatus");
        if (!(sources instanceof Map)) return false;
        Object status = ((Map<?, ?>) sources).get(source);
        if (!(status instanceof Map) || !Boolean.TRUE.equals(((Map<?, ?>) status).get("ok"))) return false;
        Object provider = ((Map<?, ?>) status).get("selectedProvider");
        return provider == null || "eastmoney".equalsIgnoreCase(String.valueOf(provider));
    }

    private boolean reviewedRecentTradeDate(Map<String, Object> raw, String qualityKey,
                                            String section, String valueKey) {
        LocalDate date = null;
        Object quality = raw.get("sourceTradeDateQuality");
        if (quality instanceof Map) {
            Object value = ((Map<?, ?>) quality).get(qualityKey);
            if (value != null) {
                try { date = LocalDate.parse(String.valueOf(value).substring(0, 10)); }
                catch (Exception ignored) { }
            }
        }
        if (date == null) {
            Object content = raw.get(section);
            if ("quote".equals(section) && content instanceof Map) {
                Object timestamp = ((Map<?, ?>) content).get(valueKey);
                if (timestamp instanceof Number) {
                    date = Instant.ofEpochSecond(((Number) timestamp).longValue())
                            .atZone(REVIEWED_MARKET_ZONE).toLocalDate();
                }
            } else if (content instanceof List && !((List<?>) content).isEmpty()) {
                Object latest = ((List<?>) content).get(((List<?>) content).size() - 1);
                if (latest instanceof Map) {
                    Object value = ((Map<?, ?>) latest).get(valueKey);
                    if (value != null) {
                        try { date = LocalDate.parse(String.valueOf(value).substring(0, 10)); }
                        catch (Exception ignored) { }
                    }
                }
            }
        }
        LocalDate today = LocalDate.now(REVIEWED_MARKET_ZONE);
        return date != null && !date.isAfter(today)
                && ChronoUnit.DAYS.between(date, today) <= 14;
    }

    private boolean reviewedGranularFieldAvailable(Map<String, Object> raw, String field) {
        if (raw == null) return false;
        if ("volumeratio".equals(field)) return nestedNumberPresent(raw, "stockDetail", "volumeRatio");
        if ("volume".equals(field) || "turnover".equals(field)) {
            Object bars = raw.get("dailyKline");
            if (!reviewedEastmoneyFieldAvailable(raw, "dailyKline") || !(bars instanceof List)) return false;
            Object last = ((List<?>) bars).get(((List<?>) bars).size() - 1);
            return last instanceof Map && ((Map<?, ?>) last).get("volume".equals(field) ? "volume" : "turnoverPct") instanceof Number;
        }
        if (!reviewedEastmoneyFieldAvailable(raw, "technicalIndicators")) return false;
        switch (field) {
            case "macd":
                return nestedNumberPresent(raw, "technicalIndicators", "macdDif")
                        && nestedNumberPresent(raw, "technicalIndicators", "macdDea")
                        && nestedNumberPresent(raw, "technicalIndicators", "macdHistogram");
            case "rsi": return nestedNumberPresent(raw, "technicalIndicators", "rsi14");
            case "boll":
                return nestedNumberPresent(raw, "technicalIndicators", "bollMiddle")
                        && nestedNumberPresent(raw, "technicalIndicators", "bollUpper")
                        && nestedNumberPresent(raw, "technicalIndicators", "bollLower");
            case "atr": return nestedNumberPresent(raw, "technicalIndicators", "atr14");
            default: return nestedNumberPresent(raw, "technicalIndicators", field);
        }
    }

    private boolean reviewedProjectedGranularFieldAvailable(Map<String, Object> delivered, String field) {
        if (delivered == null) return false;
        if ("volumeratio".equals(field)) return nestedNumberPresent(delivered, "stockDetail", "volumeRatio");
        if ("volume".equals(field) || "turnover".equals(field)) {
            Object bars = delivered.get("dailyKline");
            if (!(bars instanceof List) || ((List<?>) bars).isEmpty()) return false;
            Object last = ((List<?>) bars).get(((List<?>) bars).size() - 1);
            return last instanceof Map && ((Map<?, ?>) last).get(
                    "volume".equals(field) ? "volume" : "turnoverPct") instanceof Number;
        }
        switch (field) {
            case "macd":
                return nestedNumberPresent(delivered, "technicalIndicators", "macdDif")
                        && nestedNumberPresent(delivered, "technicalIndicators", "macdDea")
                        && nestedNumberPresent(delivered, "technicalIndicators", "macdHistogram");
            case "rsi": return nestedNumberPresent(delivered, "technicalIndicators", "rsi14");
            case "boll":
                return nestedNumberPresent(delivered, "technicalIndicators", "bollMiddle")
                        && nestedNumberPresent(delivered, "technicalIndicators", "bollUpper")
                        && nestedNumberPresent(delivered, "technicalIndicators", "bollLower");
            case "atr": return nestedNumberPresent(delivered, "technicalIndicators", "atr14");
            default: return nestedNumberPresent(delivered, "technicalIndicators", field);
        }
    }

    private boolean nestedNumberPresent(Map<String, Object> raw, String section, String field) {
        Object value = raw.get(section);
        return value instanceof Map && ((Map<?, ?>) value).get(field) instanceof Number;
    }

    private String buildReviewedGapQuestion(String title, ReviewedNeed need,
                                            ReviewedEastmoneyEvidence eastmoney, String focus) {
        StringBuilder question = new StringBuilder("会议主题：").append(title)
                .append("。议员资料需求：").append(need.question)
                .append("。本次唯一待核验缺口：").append(focus).append("。");
        List<String> sectionRequirements = need.requiredFieldPaths.stream()
                .filter(path -> path.startsWith(focus + ".") || path.startsWith(focus + "[]."))
                .collect(Collectors.toList());
        if (!sectionRequirements.isEmpty()) {
            question.append("该缺口必须包含字段：").append(String.join("、", sectionRequirements)).append("。");
        }
        if (eastmoney.ref != null) {
            question.append("标的：").append(eastmoney.ref.symbol).append(".")
                    .append(eastmoney.ref.market).append("。");
        }
        // Do not include live Eastmoney availability here: the exact question is the
        // persisted n8n identity and must remain stable when a paused meeting resumes.
        question.append("只对本次唯一缺口返回可核验事实；其他字段不算本次审核通过。");
        if (!need.sourceFocus.isEmpty()) {
            question.append("优先搜索站点：")
                    .append(need.sourceFocus.stream().map(domain -> "site:" + domain)
                            .collect(Collectors.joining(" "))).append("。");
        }
        return question.append("只核验本项缺口，给出对应时间、单位和来源；不要延伸到其他议题。").toString();
    }

    private static final class ReviewedEastmoneyEvidence {
        private final StockRef ref;
        private final List<WebSearchService.SearchResult> results;
        private final List<String> availableFields;
        private final List<String> missingFields;

        private ReviewedEastmoneyEvidence(StockRef ref, List<WebSearchService.SearchResult> results,
                                          List<String> availableFields, List<String> missingFields) {
            this.ref = ref;
            this.results = results;
            this.availableFields = availableFields;
            this.missingFields = missingFields;
        }
    }

    /**
     * 两阶段发言生成：
     * 阶段1 - 议员自主决策：是否需要搜索 + 搜索什么
     * 阶段2 - 基于搜索结果（若有）生成发言，并将论据作为聊天记录的一部分
     */
    private AiMeetingOpinion generateOpinion(Long meetingId, AiMeetingMember member, int roundNumber) {
        String systemPrompt = buildSystemPrompt(member);
        AiMeetingOpinion targetedQuestion = findLatestTargetedQuestion(meetingId, member.getId());

        // ── 阶段1：让议员决策是否需要搜索（Serper 方案，仅非 DeepSeek 模型使用）──
        // DeepSeek Chat 有内置 $web_search，在阶段2原生触发，无需 Serper，跳过阶段1
        List<WebSearchService.SearchResult> searchResults = new ArrayList<>();
        List<String> searchQueries = new ArrayList<>();
        boolean isReasonerModel = member.getModelType() != null && member.getModelType().contains("reasoner");
        SearchPlan searchPlan = defaultSearchPlan(meetingId);
        boolean preferWebSearchProvider = preferWebSearchProvider(member.getModelType());

        // ── 阶段1：Phase 1 搜索规划（所有模型统一走此路径）──
        // 对支持内置搜索的模型，这一阶段先只做规划，作为 built-in search 失败后的外部搜索兜底。
        if (webSearch.isEnabled()) {
            webSearch.beginSession();
            if (preferWebSearchProvider) {
                searchPlan = buildHeuristicSearchPlan(meetingId, member, roundNumber);
                searchQueries = limitWebBridgeQueries(searchPlan.queries);
                if (!searchQueries.isEmpty()) {
                    log.info("Member {} will search via web bridge: {}", member.getMemberName(), searchQueries);
                    for (String q : searchQueries) {
                        searchResults.addAll(webSearch.search(q, 3, null, true));
                    }
                }
            } else {
                String decisionModel = orchestrator.resolveOperationalModelType(
                        isReasonerModel
                                ? member.getModelType()
                                : resolveDecisionModelType(member.getModelType(), false)
                );
                String decisionPrompt = orchestrator.buildSearchDecisionPrompt(meetingId, member.getId(), roundNumber);
                String decisionRaw = orchestrator.callModel(decisionModel, systemPromptForDecision(member), decisionPrompt, 220);
                if (decisionRaw != null) {
                    searchPlan = parseSearchPlan(decisionRaw, meetingId);
                    searchQueries = searchPlan.queries;
                }
                if (!supportsBuiltInSearch(member.getModelType()) && (searchQueries == null || searchQueries.isEmpty())) {
                    SearchPlan heuristicPlan = buildHeuristicSearchPlan(meetingId, member, roundNumber);
                    List<String> heuristicQueries = heuristicPlan != null ? heuristicPlan.queries : null;
                    if (heuristicQueries != null && !heuristicQueries.isEmpty()) {
                        searchPlan = heuristicPlan;
                        searchQueries = heuristicQueries;
                        log.warn("Search decision model unavailable or returned empty. Falling back to heuristic queries for member {}: {}",
                                member.getMemberName(), searchQueries);
                    }
                }
                if (!supportsBuiltInSearch(member.getModelType()) && !searchQueries.isEmpty()) {
                    log.info("Member {} will search: {}", member.getMemberName(), searchQueries);
                    for (String q : searchQueries) {
                        searchResults.addAll(webSearch.search(q, 3));
                    }
                }
            }
        }
        List<WebSearchService.SearchResult> eastmoneyResults = fetchEastmoneyResults(meetingId, searchPlan);
        if (!eastmoneyResults.isEmpty()) {
            searchResults.addAll(eastmoneyResults);
            searchQueries.add(buildEastmoneyQueryLabel(searchPlan, eastmoneyResults));
        }
        searchResults = prioritizeSearchResults(searchResults, searchPlan, meetingId);
        JSONObject conflictValidation = buildConflictValidation(meetingId, member.getId(), roundNumber, searchResults);
        WebSearchService.SearchMeta searchMeta = webSearch.peekSessionMeta();

        if (shouldUseBridgeDirectOpinion(preferWebSearchProvider, searchMeta, searchResults)) {
            searchMeta = webSearch.consumeSessionMeta();
            String content = synthesizeBridgeBackedOpinion(member, searchPlan, searchResults);
            ChatResult bridgeChatResult = new ChatResult(
                    content,
                    searchQueries,
                    searchResults,
                    "deepseek_web_bridge",
                    "success_direct_answer",
                    "已直接使用 DeepSeek 网页搜索摘要生成本轮发言，未额外调用二次模型生成。"
            );
            String[] parsed = parseEvidenceAndContent(bridgeChatResult.content);
            String evidence = buildEvidenceJson(meetingId, member.getId(), roundNumber, parsed[0], searchQueries, searchResults, searchPlan, bridgeChatResult, searchMeta, null, conflictValidation);

            AiMeetingOpinion opinion = new AiMeetingOpinion();
            opinion.setMeetingId(meetingId);
            opinion.setMemberId(member.getId());
            opinion.setRoundNumber(roundNumber);
            opinion.setParentOpinionId(targetedQuestion != null ? targetedQuestion.getId() : null);
            opinion.setContent(parsed[1]);
            opinion.setEvidence(evidence);
            opinion.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
            opinionMapper.insert(opinion);
            return opinion;
        }

        // ── 阶段2：生成发言 ──
        WebSearchService.ResearchDigest researchDigest = buildResearchDigest(meetingId, searchPlan, searchQueries, searchResults);
        searchMeta = webSearch.consumeSessionMeta();
        String searchContext = buildSearchContext(searchResults, researchDigest, searchPlan);
        String conflictPrompt = buildConflictPrompt(conflictValidation);
        if (conflictPrompt != null && !conflictPrompt.isBlank()) {
            searchContext = searchContext == null || searchContext.isBlank()
                    ? conflictPrompt
                    : searchContext + "\n\n" + conflictPrompt;
        }
        String context = orchestrator.buildDebateContext(meetingId, member.getId(), roundNumber, searchContext);
        String generationModelType = resolveGenerationModelType(member.getModelType(), preferWebSearchProvider, searchResults);
        ChatResult chatResult = orchestrator.callModelEx(generationModelType, systemPrompt, context);
        boolean automaticRetryBlocked = chatResult != null && chatResult.blocksAutomaticRetry();
        if ((chatResult == null || chatResult.content == null || chatResult.content.isBlank())
                && !automaticRetryBlocked
                && !searchResults.isEmpty()) {
            ChatResult compactRetry = retryWithCompactSearchContext(
                    meetingId,
                    member,
                    roundNumber,
                    systemPrompt,
                    searchPlan,
                    searchQueries,
                    searchResults,
                    targetedQuestion,
                    generationModelType,
                    chatResult
            );
            if (compactRetry != null && compactRetry.content != null && !compactRetry.content.isBlank()) {
                chatResult = compactRetry;
                automaticRetryBlocked = compactRetry.blocksAutomaticRetry();
            }
        }
        if (shouldRetryForUnresolvedConflict(chatResult, conflictValidation)) {
            ChatResult conflictRetry = retryWithConflictResolution(
                    systemPrompt,
                    context,
                    generationModelType,
                    searchQueries,
                    searchResults,
                    chatResult,
                    conflictValidation
            );
            if (conflictRetry != null && (conflictRetry.blocksAutomaticRetry()
                    || (conflictRetry.content != null && !conflictRetry.content.isBlank()))) {
                chatResult = conflictRetry;
                automaticRetryBlocked = conflictRetry.blocksAutomaticRetry();
            }
        }
        if ((chatResult == null || chatResult.content == null || chatResult.content.isBlank()) && !searchResults.isEmpty()) {
            String content = synthesizeExternalSearchFallbackOpinion(member, searchPlan, searchResults, chatResult);
            ChatResult fallbackResult = new ChatResult(
                    content,
                    searchQueries,
                    searchResults,
                    searchMeta != null ? searchMeta.provider : "external_search",
                    "search_backed_fallback",
                    "主模型调用失败，已直接根据联网搜索结果生成保守发言。",
                    chatResult != null ? chatResult.modelStatus : "missing_model_output",
                    resolveModelFailureNote(chatResult)
            );
            String[] parsed = parseEvidenceAndContent(fallbackResult.content);
            String evidence = buildEvidenceJson(meetingId, member.getId(), roundNumber, parsed[0], searchQueries, searchResults, searchPlan, fallbackResult, searchMeta, researchDigest, conflictValidation);
            AiMeetingOpinion fallbackOpinion = new AiMeetingOpinion();
            fallbackOpinion.setMeetingId(meetingId);
            fallbackOpinion.setMemberId(member.getId());
            fallbackOpinion.setRoundNumber(roundNumber);
            fallbackOpinion.setParentOpinionId(targetedQuestion != null ? targetedQuestion.getId() : null);
            fallbackOpinion.setContent(parsed[1]);
            fallbackOpinion.setEvidence(evidence);
            fallbackOpinion.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
            opinionMapper.insert(fallbackOpinion);
            return fallbackOpinion;
        }
        if (chatResult == null || chatResult.content == null) return null;

        if (shouldRunExternalFallbackSearch(member, chatResult, searchResults)) {
            List<String> fallbackQueries = buildFallbackQueries(meetingId, searchPlan, searchQueries);
            if (!fallbackQueries.isEmpty()) {
                log.info("Built-in search fallback triggers external search for member {}: {}",
                        member.getMemberName(), fallbackQueries);
                List<WebSearchService.SearchResult> fallbackResults = new ArrayList<>();
                for (String q : fallbackQueries) {
                    fallbackResults.addAll(webSearch.search(q, 3));
                }
                fallbackResults = prioritizeSearchResults(fallbackResults, searchPlan, meetingId);
                if (!fallbackResults.isEmpty()) {
                    searchQueries = mergeQueries(searchQueries, fallbackQueries);
                    searchResults = fallbackResults;
                    String fallbackModelType = resolveStableFallbackModelType(member.getModelType());
                    if (fallbackModelType != null) {
                        WebSearchService.ResearchDigest fallbackDigest = buildResearchDigest(meetingId, searchPlan, searchQueries, searchResults);
                        researchDigest = fallbackDigest;
                        String fallbackSearchContext = buildSearchContext(searchResults, fallbackDigest, searchPlan);
                        String fallbackContext = orchestrator.buildDebateContext(meetingId, member.getId(), roundNumber, fallbackSearchContext);
                        ChatResult rerun = orchestrator.callModelEx(fallbackModelType, systemPrompt, fallbackContext);
                        if (rerun != null && rerun.content != null && !rerun.content.isBlank()) {
                            chatResult = new ChatResult(
                                    rerun.content,
                                    searchQueries,
                                    searchResults,
                                    "external_search_fallback",
                                    "success_after_builtin_timeout",
                                    "内置联网搜索失败后，已自动切换到外部搜索结果并重新生成发言。"
                            );
                            context = fallbackContext;
                            generationModelType = fallbackModelType;
                        } else if (rerun != null && rerun.blocksAutomaticRetry()) {
                            automaticRetryBlocked = true;
                        }
                    }
                }
            }
        }

        if (!automaticRetryBlocked) {
            chatResult = enforceSearchEvidenceGrounding(
                    member,
                    systemPrompt,
                    context,
                    generationModelType,
                    searchQueries,
                    searchResults,
                    searchPlan,
                    chatResult
            );
        }

        // 合并外部搜索 + tool_call 搜索证据；strict fallback 会把同一批证据随 ChatResult 带回，必须去重后再入库。
        searchQueries = mergeSearchQueries(searchQueries, chatResult.searchQueries);
        searchResults = mergeSearchResults(searchResults, chatResult.searchResults);

        String[] parsed = parseEvidenceAndContent(chatResult.content);
        String evidence = buildEvidenceJson(meetingId, member.getId(), roundNumber, parsed[0], searchQueries, searchResults, searchPlan, chatResult, searchMeta, researchDigest, conflictValidation);
        String content  = parsed[1];

        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(meetingId);
        opinion.setMemberId(member.getId());
        opinion.setRoundNumber(roundNumber);
        opinion.setParentOpinionId(targetedQuestion != null ? targetedQuestion.getId() : null);
        opinion.setContent(content);
        opinion.setEvidence(evidence);
        opinion.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
        opinionMapper.insert(opinion);
        return opinion;
    }

    private AiMeetingOpinion generateOpinionWithRetry(Long meetingId, AiMeetingMember member, int roundNumber) {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, roundNumber, member.getId(), MeetingCallContext.SPEECH)) {
            return generateOpinionWithRetryInScope(meetingId, member, roundNumber);
        }
    }

    private AiMeetingOpinion generateOpinionWithRetryInScope(Long meetingId, AiMeetingMember member, int roundNumber) {
        AiMeetingOpinion opinion = generateOpinion(meetingId, member, roundNumber);
        if (opinion != null && opinion.getContent() != null && !opinion.getContent().isBlank()) {
            return opinion;
        }
        if (supportsBuiltInSearch(member.getModelType())) {
            log.warn("Opinion generation returned empty for experimental-search model, skipping retry to avoid blocking round. meetingId={}, memberId={}, round={}",
                    meetingId, member.getId(), roundNumber);
            return insertFallbackOpinion(meetingId, member, roundNumber);
        }
        log.warn("Opinion generation returned empty, retrying once. meetingId={}, memberId={}, round={}",
                meetingId, member.getId(), roundNumber);
        opinion = generateOpinion(meetingId, member, roundNumber);
        if (opinion != null && opinion.getContent() != null && !opinion.getContent().isBlank()) {
            return opinion;
        }
        return insertFallbackOpinion(meetingId, member, roundNumber);
    }

    private AiMeetingOpinion insertFallbackOpinion(Long meetingId, AiMeetingMember member, int roundNumber) {
        AiMeetingOpinion fallback = new AiMeetingOpinion();
        fallback.setMeetingId(meetingId);
        fallback.setMemberId(member.getId());
        fallback.setRoundNumber(roundNumber);
        fallback.setContent(FALLBACK_OPINION_TEXT);
        fallback.setEvidence(null);
        fallback.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
        opinionMapper.insert(fallback);
        log.warn("Inserted fallback opinion for missing speaker. meetingId={}, memberId={}, round={}",
                meetingId, member.getId(), roundNumber);
        return fallback;
    }

    private boolean shouldRunExternalFallbackSearch(AiMeetingMember member,
                                                    ChatResult chatResult,
                                                    List<WebSearchService.SearchResult> existingResults) {
        if (member == null || chatResult == null || !webSearch.isEnabled()) {
            return false;
        }
        if (!supportsBuiltInSearch(member.getModelType())) {
            return false;
        }
        if (existingResults != null && !existingResults.isEmpty()) {
            return false;
        }
        if (chatResult.searchResults != null && !chatResult.searchResults.isEmpty()) {
            return false;
        }
        return "built_in_search".equalsIgnoreCase(chatResult.searchMode)
                && ("fallback_to_stable".equalsIgnoreCase(chatResult.searchStatus)
                || "timeout_without_fallback".equalsIgnoreCase(chatResult.searchStatus));
    }

    private List<String> buildFallbackQueries(Long meetingId, SearchPlan searchPlan, List<String> plannedQueries) {
        List<String> queries = mergeQueries(plannedQueries, searchPlan != null ? searchPlan.queries : null);
        if (!queries.isEmpty()) {
            return queries;
        }
        List<String> fallback = new ArrayList<>();
        String title = "";
        try {
            if (meetingId != null) {
                com.aimeeting.room.entity.AiMeetingRoom room = roomMapper.selectById(meetingId);
                if (room != null && room.getTitle() != null) {
                    title = room.getTitle().trim();
                }
            }
        } catch (Exception ignored) {
        }
        if (title.isBlank()) {
            return fallback;
        }
        String intent = searchPlan != null && searchPlan.intent != null ? searchPlan.intent : "";
        String suffix;
        switch (intent) {
            case "realtime_price":
                suffix = " 最新价格 现货 报价";
                break;
            case "forecast_outlook":
                suffix = " 预测 展望 最新 机构";
                break;
            case "latest_news":
                suffix = " 最新 新闻";
                break;
            default:
                suffix = " 最新";
                break;
        }
        fallback.add((title + suffix).trim());
        if (searchPlan != null && searchPlan.targetYear != null) {
            fallback.add((title + " " + searchPlan.targetYear + " 预测 最新").trim());
        }
        return buildSourceAwareQueries(mergeQueries(fallback, null), searchPlan != null ? searchPlan.sourceFocus : null, 3);
    }

    private SearchPlan buildHeuristicSearchPlan(Long meetingId, AiMeetingMember member, int roundNumber) {
        SearchPlan plan = defaultSearchPlan(meetingId);
        String title = "";
        try {
            if (meetingId != null) {
                com.aimeeting.room.entity.AiMeetingRoom room = roomMapper.selectById(meetingId);
                if (room != null && room.getTitle() != null) {
                    title = room.getTitle().trim();
                }
            }
        } catch (Exception ignored) {
        }
        String persona = member != null && member.getPersona() != null ? member.getPersona().trim() : "";
        String memberName = member != null && member.getMemberName() != null ? member.getMemberName().trim() : "";

        plan.intent = inferIntentFromTitleAndPersona(title, persona);
        plan.targetYear = inferTargetYearFromTitle(title);
        plan.freshnessDays = "realtime_price".equals(plan.intent) ? 7 : 30;
        plan.sourceFocus = buildPreferredSourceFocus(title, memberName, persona, plan.intent);
        if (isStockTopic(title, persona, memberName)) {
            plan.eastmoneyFields = defaultEastmoneyFields(plan.intent);
            StockRef ref = resolveEastmoneyStockRef(meetingId, plan);
            if (ref != null) {
                plan.eastmoneySymbol = ref.symbol;
                plan.eastmoneyMarket = ref.market;
            }
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (String roleSpecificQuery : buildRoleSpecificQueries(title, memberName, persona, plan.intent)) {
            if (roleSpecificQuery != null && !roleSpecificQuery.isBlank()) {
                queries.add(roleSpecificQuery.trim());
            }
        }
        if (queries.isEmpty() && !title.isBlank()) {
            queries.add((title + " 最新").trim());
        } else if (!title.isBlank()) {
            if ("forecast_outlook".equals(plan.intent)) {
                queries.add((title + " 业绩指引 预期差 最新").trim());
            } else if ("realtime_price".equals(plan.intent)) {
                queries.add((title + " 最新价格 最新数据").trim());
            } else if ("technical_indicator_research".equals(plan.intent)) {
                queries.add((title + " 技术指标 有效性 回测").trim());
            } else if ("latest_news".equals(plan.intent)) {
                queries.add((title + " 最新新闻").trim());
            } else {
                queries.add((title + " 最新").trim());
            }
        }
        plan.queries = buildSourceAwareQueries(new ArrayList<>(queries), plan.sourceFocus, 3);
        return plan;
    }

    private List<String> limitWebBridgeQueries(List<String> original) {
        if (original == null || original.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> limited = new ArrayList<>(original);
        if (limited.size() > 2) {
            limited = new ArrayList<>(limited.subList(0, 2));
        }
        return limited;
    }

    private String inferIntentFromTitleAndPersona(String title, String persona) {
        String titleText = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        String personaText = persona == null ? "" : persona.trim().toLowerCase(Locale.ROOT);
        String combined = (titleText + " " + personaText).trim();
        if (containsAny(titleText,
                "技术指标", "技术面指标", "指标有效", "指标误导", "防误导",
                "假突破", "背离", "量价", "均线", "macd", "rsi", "kdj")) {
            return "technical_indicator_research";
        }
        if (containsAny(titleText, "未来", "三个月", "走势", "预测", "展望", "目标价")
                || containsAny(combined, "预测", "展望")) {
            return "forecast_outlook";
        }
        if (combined.contains("价格") || combined.contains("金价") || combined.contains("现货") || combined.contains("报价")) {
            return "realtime_price";
        }
        if (combined.contains("新闻") || combined.contains("事件")) {
            return "latest_news";
        }
        return "background_fact";
    }

    private List<String> buildRoleSpecificQueries(String title,
                                                  String memberName,
                                                  String persona,
                                                  String intent) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String combined = ((memberName == null ? "" : memberName) + " " + (persona == null ? "" : persona)).toLowerCase();
        String normalizedTitle = title == null ? "" : title.trim();
        if (normalizedTitle.isBlank()) {
            return new ArrayList<>();
        }
        if ("technical_indicator_research".equalsIgnoreCase(intent)) {
            queries.add((normalizedTitle + " 技术指标 有效性 回测 胜率 A股").trim());
            queries.add((normalizedTitle + " 均线 MACD RSI KDJ 假突破 背离 量价").trim());
            queries.add((normalizedTitle + " 趋势 震荡 市场 技术指标 失效").trim());
            return new ArrayList<>(queries);
        }
        if (containsAny(combined, "量化", "因子", "ic", "夏普")) {
            queries.add((normalizedTitle + " 盈利惊喜 波动率 ROE 最新").trim());
            queries.add((normalizedTitle + " 财报 预告 超预期 因子 最新").trim());
            queries.add((normalizedTitle + " Beta 波动 回撤 估值 最新").trim());
        } else if (containsAny(combined, "行业", "轮动", "景气", "资金流向", "超配", "低配")) {
            queries.add((normalizedTitle + " 北向资金 行业资金流向 最新").trim());
            queries.add((normalizedTitle + " 招标 订单 需求 景气 最新").trim());
            queries.add((normalizedTitle + " 集采 政策 渠道 库存 最新").trim());
        } else if (containsAny(combined, "技术", "趋势", "k线", "均线", "量能", "背离")) {
            queries.add((normalizedTitle + " K线 均线 成交量 MACD RSI 最新").trim());
            queries.add((normalizedTitle + " 支撑 阻力 趋势 量能 最新").trim());
            queries.add((normalizedTitle + " 波动 区间 回撤 最新").trim());
        } else if (containsAny(combined, "成长", "渗透率", "订单", "国产替代", "增速")) {
            queries.add((normalizedTitle + " 订单 增速 渗透率 最新").trim());
            queries.add((normalizedTitle + " 新产品 出海 装机 最新").trim());
            queries.add((normalizedTitle + " AI 软件 流水线 市占率 最新").trim());
        } else if (containsAny(combined, "价值", "股息", "pb", "现金流", "自由现金流")) {
            queries.add((normalizedTitle + " 股息率 自由现金流 PB PE 最新").trim());
            queries.add((normalizedTitle + " 分红 回购 现金流 最新").trim());
            queries.add((normalizedTitle + " 估值 安全边际 财报 最新").trim());
        } else if (containsAny(combined, "主席", "决策")) {
            queries.add((normalizedTitle + " 关键分歧 风险 催化 最新").trim());
            queries.add((normalizedTitle + " 业绩指引 估值 预期差 最新").trim());
            queries.add((normalizedTitle + " 机构预测 目标价 最新").trim());
        }
        if (queries.isEmpty() && "forecast_outlook".equalsIgnoreCase(intent)) {
            queries.add((normalizedTitle + " 机构预测 目标价 最新").trim());
            queries.add((normalizedTitle + " 业绩指引 预期差 最新").trim());
        }
        if (queries.isEmpty() && "realtime_price".equalsIgnoreCase(intent)) {
            queries.add((normalizedTitle + " 最新价格 最新数据").trim());
            queries.add((normalizedTitle + " 最新股价 成交额 振幅").trim());
        }
        if (queries.isEmpty()) {
            queries.add((normalizedTitle + " 最新").trim());
        }
        return new ArrayList<>(queries);
    }

    private List<String> buildPreferredSourceFocus(String title,
                                                   String memberName,
                                                   String persona,
                                                   String intent) {
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        String combined = ((title == null ? "" : title) + " "
                + (memberName == null ? "" : memberName) + " "
                + (persona == null ? "" : persona)).toLowerCase();

        if (containsAny(combined, "行业", "轮动", "资金流向", "北向", "超配", "低配")) {
            domains.add("eastmoney.com");
            domains.add("stcn.com");
            domains.add("cls.cn");
        }
        if (containsAny(combined, "技术", "趋势", "k线", "均线", "量能", "背离")) {
            domains.add("10jqka.com.cn");
            domains.add("eastmoney.com");
            domains.add("finance.sina.com.cn");
        }
        if (containsAny(combined, "价值", "股息", "pb", "现金流", "分红", "回购")) {
            domains.add("cninfo.com.cn");
            domains.add("eastmoney.com");
            domains.add("finance.sina.com.cn");
        }
        if (containsAny(combined, "成长", "渗透率", "订单", "国产替代", "装机", "市占率")) {
            domains.add("cls.cn");
            domains.add("eastmoney.com");
            domains.add("cninfo.com.cn");
        }

        boolean stockTopic = isStockTopic(title, persona, memberName);
        if (stockTopic) {
            domains.add("cninfo.com.cn");
            domains.add("eastmoney.com");
            domains.add("10jqka.com.cn");
            domains.add("finance.sina.com.cn");
        } else if ("realtime_price".equalsIgnoreCase(intent)) {
            domains.add("investing.com");
            domains.add("tradingeconomics.com");
            domains.add("reuters.com");
        } else if ("forecast_outlook".equalsIgnoreCase(intent)) {
            domains.add("reuters.com");
            domains.add("bloomberg.com");
            domains.add("wsj.com");
        } else if ("latest_news".equalsIgnoreCase(intent)) {
            domains.add("reuters.com");
            domains.add("apnews.com");
            domains.add("bloomberg.com");
        } else {
            domains.add("reuters.com");
            domains.add("bloomberg.com");
        }

        if (containsAny(combined, "金价", "黄金", "贵金属", "原油", "铜", "铝", "大宗", "商品", "comex", "lbma")) {
            domains.add("gold.org");
            domains.add("lbma.org.uk");
            domains.add("cmegroup.com");
        }
        if (containsAny(combined, "政策", "监管", "央行", "财政", "社融", "cpi", "ppi", "pmi", "出口", "进口", "关税",
                "宏观", "利率", "汇率", "就业", "制造业")) {
            domains.add("gov.cn");
            domains.add("stats.gov.cn");
            domains.add("pbc.gov.cn");
            domains.add("mofcom.gov.cn");
        }
        if (containsAny(combined, "医疗", "医药", "器械", "药品", "医院", "临床", "医保", "卫健")) {
            domains.add("who.int");
            domains.add("fda.gov");
            domains.add("nhc.gov.cn");
        }
        if (containsAny(combined, "科技", "芯片", "半导体", "ai", "人工智能", "云", "软件", "互联网")) {
            domains.add("sec.gov");
            domains.add("techcrunch.com");
        }

        List<String> limited = new ArrayList<>(domains);
        if (limited.size() > 4) {
            limited = new ArrayList<>(limited.subList(0, 4));
        }
        return limited;
    }

    private List<String> buildSourceAwareQueries(List<String> baseQueries,
                                                 List<String> sourceFocus,
                                                 int maxQueries) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        List<String> normalizedSources = normalizeSourceFocus(sourceFocus);
        int effectiveMax = Math.max(1, maxQueries);
        for (String rawQuery : baseQueries) {
            if (rawQuery == null || rawQuery.isBlank()) {
                continue;
            }
            String query = rawQuery.trim();
            if (!normalizedSources.isEmpty()) {
                ordered.add(query + " site:" + normalizedSources.get(0));
            }
            ordered.add(query);
            for (int i = 1; i < normalizedSources.size(); i++) {
                ordered.add(query + " site:" + normalizedSources.get(i));
                if (ordered.size() >= effectiveMax) {
                    break;
                }
            }
            if (ordered.size() >= effectiveMax) {
                break;
            }
        }
        List<String> limited = new ArrayList<>(ordered);
        if (limited.size() > effectiveMax) {
            limited = new ArrayList<>(limited.subList(0, effectiveMax));
        }
        return limited;
    }

    private List<String> mergeSearchQueries(List<String> primary, List<String> secondary) {
        List<String> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return dedupeSearchQueries(merged);
    }

    private List<String> dedupeSearchQueries(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            seen.add(query.trim());
        }
        return new ArrayList<>(seen);
    }

    private List<WebSearchService.SearchResult> mergeSearchResults(List<WebSearchService.SearchResult> primary,
                                                                   List<WebSearchService.SearchResult> secondary) {
        List<WebSearchService.SearchResult> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return dedupeSearchResults(merged);
    }

    private List<WebSearchService.SearchResult> dedupeSearchResults(List<WebSearchService.SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }
        List<WebSearchService.SearchResult> deduped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WebSearchService.SearchResult result : results) {
            if (result == null) {
                continue;
            }
            if (!seen.add(buildSearchResultKey(result))) {
                continue;
            }
            deduped.add(result);
        }
        return deduped;
    }

    private List<String> normalizeSourceFocus(List<String> sourceFocus) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (sourceFocus == null) {
            return new ArrayList<>();
        }
        for (String source : sourceFocus) {
            if (source == null || source.isBlank()) {
                continue;
            }
            String value = source.trim().toLowerCase()
                    .replace("https://", "")
                    .replace("http://", "");
            int slashIndex = value.indexOf('/');
            if (slashIndex >= 0) {
                value = value.substring(0, slashIndex);
            }
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private boolean isGoldTopic(String title, String persona, String memberName) {
        String combined = ((title == null ? "" : title) + " " +
                (persona == null ? "" : persona) + " " +
                (memberName == null ? "" : memberName)).toLowerCase();
        return containsAny(combined, "金价", "黄金", "现货黄金", "comex", "lbma", "贵金属");
    }

    private boolean isStockTopic(String title, String persona, String memberName) {
        String combined = ((title == null ? "" : title) + " " +
                (persona == null ? "" : persona) + " " +
                (memberName == null ? "" : memberName)).toLowerCase();
        return containsAny(combined, "股价", "a股", "股票", "个股", "指数", "沪深", "港股", "纳斯达克", "标普");
    }

    private Integer inferTargetYearFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        Matcher matcher = YEAR_PATTERN.matcher(title);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private boolean shouldUseBridgeDirectOpinion(boolean preferWebSearchProvider,
                                                 WebSearchService.SearchMeta searchMeta,
                                                 List<WebSearchService.SearchResult> searchResults) {
        return preferWebSearchProvider
                && searchMeta != null
                && "success".equalsIgnoreCase(searchMeta.contentStatus)
                && searchResults != null
                && !searchResults.isEmpty();
    }

    private String synthesizeBridgeBackedOpinion(AiMeetingMember member,
                                                 SearchPlan searchPlan,
                                                 List<WebSearchService.SearchResult> searchResults) {
        String memberName = member != null && member.getMemberName() != null ? member.getMemberName().trim() : "议员";
        String persona = member != null && member.getPersona() != null ? member.getPersona().trim() : "";
        String summary = "";
        if (!searchResults.isEmpty() && searchResults.get(0).snippet != null) {
            summary = searchResults.get(0).snippet.trim();
        }
        if (summary.isBlank()) {
            summary = "网页搜索已返回相关结果，但摘要信息不足。";
        }
        if (summary.length() >= 500 && !summary.contains("来自 DeepSeek 网页智能搜索引用来源")) {
            return summary;
        }

        String stanceLead;
        if (memberName.contains("多头") || persona.contains("避险") || persona.contains("央行")) {
            stanceLead = "以下判断严格依据本轮网页搜索结果整理，多头立场仅在这些结果可支持的范围内成立。";
        } else if (memberName.contains("空头") || persona.contains("美元") || persona.contains("实际利率")) {
            stanceLead = "以下判断严格依据本轮网页搜索结果整理，空头或谨慎立场仅在这些结果可支持的范围内成立。";
        } else {
            stanceLead = "以下判断严格依据本轮网页搜索结果整理，不做超出搜索结果范围的延伸。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(stanceLead).append("\n");
        builder.append("网页搜索摘要：").append(summary).append("\n");
        int limit = Math.min(searchResults.size(), 3);
        if (limit > 0) {
            builder.append("本轮抓到的关键来源：").append("\n");
            for (int i = 0; i < limit; i++) {
                WebSearchService.SearchResult result = searchResults.get(i);
                builder.append(i + 1).append(". ");
                builder.append(result.source != null ? result.source : "网页来源");
                if (result.url != null && !result.url.isBlank()) {
                    builder.append(" - ").append(result.url);
                }
                builder.append("\n");
            }
        }
        if (searchPlan != null && searchPlan.targetYear != null) {
            builder.append("本轮关注目标年份：").append(searchPlan.targetYear).append("。");
        }
        builder.append("最终判断仍需结合原始引用链接做进一步交叉验证，不基于未出现在搜索结果中的额外推演。");
        return builder.toString();
    }

    private String synthesizeExternalSearchFallbackOpinion(AiMeetingMember member,
                                                           SearchPlan searchPlan,
                                                           List<WebSearchService.SearchResult> searchResults,
                                                           ChatResult chatResult) {
        String memberName = member != null && member.getMemberName() != null ? member.getMemberName().trim() : "议员";
        String persona = member != null && member.getPersona() != null ? member.getPersona().trim() : "";
        String failureNote = resolveModelFailureNote(chatResult);
        StringBuilder builder = new StringBuilder();
        if (memberName.contains("多头") || persona.contains("避险") || persona.contains("央行")) {
            builder.append("以下判断基于本轮联网搜索结果整理，为偏多立场的保守表述。").append("\n");
        } else if (memberName.contains("空头") || persona.contains("美元") || persona.contains("实际利率")) {
            builder.append("以下判断基于本轮联网搜索结果整理，为偏谨慎或偏空立场的保守表述。").append("\n");
        } else {
            builder.append("以下判断基于本轮联网搜索结果整理，不做超出证据范围的延伸。").append("\n");
        }
        if (!searchResults.isEmpty()) {
            builder.append(buildFallbackResultSummary(searchResults.get(0))).append("\n");
            builder.append("关键来源：").append("\n");
            int limit = Math.min(searchResults.size(), 3);
            for (int i = 0; i < limit; i++) {
                WebSearchService.SearchResult result = searchResults.get(i);
                builder.append(i + 1).append(". ")
                        .append(result.source != null ? result.source : "来源未标记");
                if (result.url != null && !result.url.isBlank()) {
                    builder.append(" - ").append(result.url);
                }
                builder.append("\n");
            }
        }
        if (searchPlan != null && searchPlan.targetYear != null) {
            builder.append("本轮优先关注目标年份：").append(searchPlan.targetYear).append("。").append("\n");
        }
        builder.append(resolveFallbackReasonLead(chatResult));
        if (failureNote != null && !failureNote.isBlank()) {
            builder.append("（").append(failureNote).append("）");
        }
        builder.append("，当前结论仅为联网结果整理稿，后续仍应继续复核原始来源。");
        return builder.toString();
    }

    private String resolveFallbackReasonLead(ChatResult chatResult) {
        if (chatResult == null) {
            return "由于主模型本轮未返回可用正文";
        }
        String status = chatResult.searchStatus != null ? chatResult.searchStatus.toLowerCase(Locale.ROOT) : "";
        if (status.contains("strict_grounding_fallback")) {
            return "由于主模型正文未通过事实边界审计";
        }
        if (status.contains("search_backed_fallback")) {
            return "由于主模型调用失败，已改用联网结果兜底";
        }
        if (isGenerationDegraded(chatResult)) {
            return "由于主模型结果未满足本轮证据边界";
        }
        String modelStatus = chatResult.modelStatus != null ? chatResult.modelStatus.toLowerCase(Locale.ROOT) : "";
        if ("success".equals(modelStatus) || "ok".equals(modelStatus)) {
            return "由于主模型正文需要进一步事实复核";
        }
        return "由于主模型本轮未返回稳定答案";
    }

    private String buildFallbackResultSummary(WebSearchService.SearchResult result) {
        if (result == null) {
            return "搜索摘要：已抓到联网结果，但摘要信息有限。";
        }
        if ("STRUCTURED_DATA".equalsIgnoreCase(result.sourceType)) {
            String summary = summarizeStructuredSearchResult(result);
            if (summary != null && !summary.isBlank()) {
                return "结构化行情摘要：" + summary;
            }
            return "结构化行情摘要：本轮数据无法完整解析，请查看原始来源链接复核。";
        }
        String snippet = result.snippet != null ? result.snippet.trim() : "";
        if (snippet.isBlank()) {
            return "搜索摘要：已抓到联网结果，但摘要信息有限。";
        }
        return "搜索摘要：" + limitText(snippet, 360);
    }

    private String summarizeStructuredSearchResult(WebSearchService.SearchResult result) {
        if (result == null || result.snippet == null || result.snippet.isBlank()) {
            return null;
        }
        try {
            JSONObject payload = JSON.parseObject(result.snippet);
            if (payload == null || payload.isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            StockRef ref = parseStockRef(firstNonBlank(result.source, result.url, ""), null);
            String symbol = firstNonBlank(payload.getString("symbol"), ref != null ? ref.symbol : null);
            String market = firstNonBlank(payload.getString("market"), "");
            if (symbol != null && !symbol.isBlank()) {
                parts.add("标的 " + symbol + (market.isBlank() ? "" : "." + market));
            }
            addStructuredSummaryNumber(parts, payload, "quote.price", "最新价", "元");
            addStructuredSummaryNumber(parts, payload, "quote.changePercent", "涨跌幅", "%");
            addStructuredSummaryNumber(parts, payload, "quote.volumeRatio", "量比", "");
            addStructuredSummaryNumber(parts, payload, "quote.turnover", "换手率", "%");
            addStructuredSummaryNumber(parts, payload, "technicalIndicators.ma20", "MA20", "");
            addStructuredSummaryNumber(parts, payload, "technicalIndicators.rsi6", "RSI6", "");
            addStructuredSummaryNumber(parts, payload, "technicalIndicators.rsi", "RSI", "");
            addStructuredSummaryNumber(parts, payload, "technicalIndicators.macd", "MACD", "");
            addStructuredSummaryNumber(parts, payload, "anchorFacts.technicalIndicators.ma20", "MA20", "");
            addStructuredSummaryNumber(parts, payload, "anchorFacts.technicalIndicators.rsi14", "RSI14", "");
            addStructuredSummaryNumber(parts, payload, "anchorFacts.technicalIndicators.macdDif", "MACD_DIF", "");
            addStructuredSummaryNumber(parts, payload, "anchorFacts.technicalIndicators.macdHistogram", "MACD柱", "");
            addStructuredSummaryNumber(parts, payload, "anchorFacts.technicalIndicators.priceVsMa20Pct", "相对MA20偏离", "%");
            addStructuredSummaryNumber(parts, payload, "stockDetail.dynamicPe", "动态PE", "倍");
            addStructuredSummaryNumber(parts, payload, "stockDetail.roe", "ROE", "%");
            return parts.isEmpty() ? null : String.join("，", parts) + "。";
        } catch (Exception ignored) {
            return null;
        }
    }

    private void addStructuredSummaryNumber(List<String> parts,
                                            JSONObject payload,
                                            String path,
                                            String label,
                                            String unit) {
        Object raw = readJsonPath(payload, path);
        Double value = parseNumericValue(raw);
        if (value == null) {
            return;
        }
        parts.add(label + " " + formatFactNumber(value) + (unit != null ? unit : ""));
    }

    private ChatResult retryWithCompactSearchContext(Long meetingId,
                                                     AiMeetingMember member,
                                                     int roundNumber,
                                                     String systemPrompt,
                                                     SearchPlan searchPlan,
                                                     List<String> searchQueries,
                                                     List<WebSearchService.SearchResult> searchResults,
                                                     AiMeetingOpinion targetedQuestion,
                                                     String primaryModelType,
                                                     ChatResult previousResult) {
        List<String> retryModelTypes = new ArrayList<>();
        if (primaryModelType != null && !primaryModelType.isBlank()) {
            retryModelTypes.add(primaryModelType);
        }
        String operationalModelType = orchestrator.resolveOperationalModelType(member != null ? member.getModelType() : null);
        if (operationalModelType != null && !operationalModelType.isBlank() && !retryModelTypes.contains(operationalModelType)) {
            retryModelTypes.add(operationalModelType);
        }
        if (retryModelTypes.isEmpty()) {
            return previousResult;
        }

        String compactPrompt = buildCompactSearchGroundedPrompt(meetingId, member, roundNumber, searchPlan, searchResults, targetedQuestion);
        for (String retryModelType : retryModelTypes) {
            String retryContent = orchestrator.callModel(retryModelType, systemPrompt, compactPrompt, 1200);
            if (retryContent != null && !retryContent.isBlank()) {
                log.info("Compact search-grounded retry succeeded for member {} with model {}",
                        member != null ? member.getMemberName() : "unknown", retryModelType);
                return new ChatResult(
                        retryContent,
                        searchQueries,
                        searchResults,
                        "external_search_compact_retry",
                        "success_after_compact_retry",
                        "主模型首次生成失败后，已使用压缩检索上下文重试并成功返回正文。",
                        "success",
                        "压缩检索上下文重试成功。"
                );
            }
            log.warn("Compact search-grounded retry returned blank for member {} with model {}",
                    member != null ? member.getMemberName() : "unknown", retryModelType);
        }
        return previousResult;
    }

    private ChatResult retryWithConflictResolution(String systemPrompt,
                                                   String context,
                                                   String modelType,
                                                   List<String> searchQueries,
                                                   List<WebSearchService.SearchResult> searchResults,
                                                   ChatResult previousResult,
                                                   JSONObject conflictValidation) {
        if (modelType == null || modelType.isBlank()) {
            return previousResult;
        }
        String revisionPrompt = buildConflictRevisionPrompt(conflictValidation);
        if (revisionPrompt == null || revisionPrompt.isBlank()) {
            return previousResult;
        }
        ChatResult revised = orchestrator.callModelEx(modelType, systemPrompt, context + "\n\n" + revisionPrompt);
        if (revised != null && revised.blocksAutomaticRetry()) {
            return revised;
        }
        if (revised == null || revised.content == null || revised.content.isBlank()) {
            return previousResult;
        }
        return new ChatResult(
                revised.content,
                searchQueries,
                searchResults,
                previousResult != null ? previousResult.searchMode : revised.searchMode,
                "success_after_conflict_retry",
                "首次生成未正面处理与前序主张的冲突，已自动重试生成修正版。",
                revised.modelStatus,
                revised.modelNote
        );
    }

    private String retryWithConflictResolution(String systemPrompt,
                                               String context,
                                               String modelType,
                                               String previousResponse,
                                               JSONObject conflictValidation) {
        if (modelType == null || modelType.isBlank()) {
            return previousResponse;
        }
        String revisionPrompt = buildConflictRevisionPrompt(conflictValidation);
        if (revisionPrompt == null || revisionPrompt.isBlank()) {
            return previousResponse;
        }
        String revised = orchestrator.callModel(modelType, systemPrompt, context + "\n\n" + revisionPrompt, 1400);
        return revised != null && !revised.isBlank() ? revised : previousResponse;
    }

    private boolean shouldRetryForUnresolvedConflict(ChatResult chatResult, JSONObject conflictValidation) {
        return chatResult != null && shouldRetryForUnresolvedConflict(chatResult.content, conflictValidation);
    }

    private boolean shouldRetryForUnresolvedConflict(String content, JSONObject conflictValidation) {
        if (content == null || content.isBlank() || conflictValidation == null || !conflictValidation.getBooleanValue("hasConflict")) {
            return false;
        }
        String normalized = content.toLowerCase();
        boolean hasConflictCue = containsAny(normalized, "冲突", "不一致", "复核", "核验", "更正", "修正", "本轮搜索", "重新搜索");
        JSONArray conflicts = conflictValidation.getJSONArray("conflicts");
        if (conflicts == null || conflicts.isEmpty()) {
            return false;
        }
        for (int i = 0; i < conflicts.size(); i++) {
            JSONObject item = conflicts.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String currentValue = item.getString("currentValue");
            String previousValue = item.getString("previousValue");
            boolean mentionsCurrent = currentValue != null && content.contains(currentValue);
            boolean mentionsPrevious = previousValue != null && content.contains(previousValue);
            if (mentionsPrevious && !mentionsCurrent) {
                return true;
            }
            if ((mentionsCurrent || mentionsPrevious) && !hasConflictCue) {
                return true;
            }
        }
        return !hasConflictCue;
    }

    private String buildConflictRevisionPrompt(JSONObject conflictValidation) {
        String conflictPrompt = buildConflictPrompt(conflictValidation);
        if (conflictPrompt == null || conflictPrompt.isBlank()) {
            return null;
        }
        return conflictPrompt + "\n【强制修正要求】你上一版回答没有充分处理这些冲突。请重写整段发言：必须明确指出冲突，若引用相关数字，只能采用你本轮搜索核验到的值，并说明为何不采纳前序主张。";
    }

    private ChatResult enforceSearchEvidenceGrounding(AiMeetingMember member,
                                                      String systemPrompt,
                                                      String context,
                                                      String modelType,
                                                      List<String> searchQueries,
                                                      List<WebSearchService.SearchResult> searchResults,
                                                      SearchPlan searchPlan,
                                                      ChatResult current) {
        if (!shouldRetryForUnsupportedStockClaims(current, searchResults, searchPlan)) {
            return current;
        }
        log.warn("Generated opinion contains unsupported stock claims. member={}, model={}, fields={}",
                member != null ? member.getMemberName() : "unknown",
                modelType,
                searchPlan != null ? searchPlan.eastmoneyFields : null);
        ChatResult revised = retryWithStrictEvidenceGrounding(
                systemPrompt,
                context,
                modelType,
                searchQueries,
                searchResults,
                current
        );
        if (revised != null
                && revised.content != null
                && !revised.content.isBlank()
                && !shouldRetryForUnsupportedStockClaims(revised, searchResults, searchPlan)) {
            return revised;
        }
        ChatResult rejected = revised != null && revised.content != null && !revised.content.isBlank()
                ? revised : current;
        String rejectedReply = rejected == null || isEvidenceOnlyModelResponse(rejected.content)
                ? null : parseEvidenceAndContent(rejected.content)[1];
        JSONObject rejectedAudit = buildClaimAudit(rejected, searchResults, searchPlan);
        String content = synthesizeExternalSearchFallbackOpinion(member, searchPlan, searchResults, current);
        return new ChatResult(
                content,
                searchQueries,
                searchResults,
                current != null ? current.searchMode : "external_search",
                "strict_grounding_fallback",
                "检测到正文包含未在本轮证据中出现的股票/量化/财务主张，已降级为仅依据 Provider 原始结果的保守整理稿。",
                current != null ? current.modelStatus : null,
                current != null ? current.modelNote : null,
                false,
                null,
                rejectedReply != null && !rejectedReply.isBlank() ? limitText(rejectedReply, 10000) : null,
                rejectedAudit != null ? rejectedAudit.toJSONString() : null
        );
    }

    private ChatResult retryWithStrictEvidenceGrounding(String systemPrompt,
                                                        String context,
                                                        String modelType,
                                                        List<String> searchQueries,
                                                        List<WebSearchService.SearchResult> searchResults,
                                                        ChatResult previousResult) {
        if (modelType == null || modelType.isBlank() || context == null || context.isBlank()) {
            return previousResult;
        }
        String revisionPrompt = buildStrictGroundingRevisionPrompt();
        ChatResult revised = orchestrator.callModelEx(modelType, systemPrompt, context + "\n\n" + revisionPrompt);
        if (revised != null && revised.blocksAutomaticRetry()) {
            return revised;
        }
        if (revised == null || revised.content == null || revised.content.isBlank()) {
            return previousResult;
        }
        return new ChatResult(
                revised.content,
                searchQueries,
                searchResults,
                previousResult != null ? previousResult.searchMode : revised.searchMode,
                "success_after_strict_grounding_retry",
                "首次正文包含未在本轮证据中出现的股票/量化/财务主张，已按严格证据边界重写。",
                revised.modelStatus,
                revised.modelNote
        );
    }

    private String buildStrictGroundingRevisionPrompt() {
        return "【强制事实边界重写】\n"
                + "你上一版回答包含本轮证据中没有出现的股票/量化/财务主张。请重写整段发言：\n"
                + "1. 只能引用【Provider原始结果】或【东方财富结构化数据】中逐字可定位的信息。\n"
                + "2. 不得引用未出现的股票代码、公司、EPS、ROE、Beta、IC值、因子排名、Wind/万得一致预期、自有量化池、业绩预告或未来季度财务预测。\n"
                + "3. 如果证据不足，必须写“本轮证据未提供/无法核验”，不要为了显得专业而补数。\n"
                + "4. 可以保留你的角色立场，但所有数字和事实必须有上方证据支撑。\n"
                + "5. 先输出 JSON 论据，再用 ==REPLY== 分隔正文。";
    }

    private boolean shouldRetryForUnsupportedStockClaims(ChatResult chatResult,
                                                         List<WebSearchService.SearchResult> searchResults,
                                                         SearchPlan searchPlan) {
        if (chatResult == null || chatResult.content == null || chatResult.content.isBlank()) {
            return false;
        }
        if (!isStockGroundedSearch(searchPlan, searchResults)) {
            return false;
        }
        JSONObject audit = buildClaimAudit(chatResult, searchResults, searchPlan);
        return isBlockingClaimAudit(audit);
    }

    private boolean isStockGroundedSearch(SearchPlan searchPlan, List<WebSearchService.SearchResult> searchResults) {
        if (searchPlan != null) {
            if (searchPlan.eastmoneySymbol != null && !searchPlan.eastmoneySymbol.isBlank()) {
                return true;
            }
            if (searchPlan.eastmoneyFields != null && !searchPlan.eastmoneyFields.isEmpty()) {
                return true;
            }
        }
        return hasEastmoneyStructuredResult(searchResults);
    }

    private boolean hasEastmoneyStructuredResult(List<WebSearchService.SearchResult> searchResults) {
        if (searchResults == null) {
            return false;
        }
        for (WebSearchService.SearchResult result : searchResults) {
            if (result != null && result.source != null && result.source.startsWith("东方财富结构化数据")) {
                return true;
            }
        }
        return false;
    }

    private String buildSearchEvidenceCorpus(List<WebSearchService.SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (WebSearchService.SearchResult result : searchResults) {
            if (result == null || isProvisionalResult(result)) {
                continue;
            }
            sb.append(result.source).append('\n')
                    .append(result.snippet).append('\n')
                    .append(result.url).append('\n');
            List<NumericFact> structuredFacts = extractFactsFromStructuredResult(
                    result,
                    null,
                    firstNonBlank(result.source, "东方财富结构化数据")
            );
            for (NumericFact fact : structuredFacts) {
                sb.append(fact.label).append(':').append(fact.rawValue).append('\n');
                if (fact.path != null) {
                    sb.append(fact.path).append('=').append(fact.rawValue).append('\n');
                }
            }
            appendSuppliedStructuredEvidence(sb, result);
        }
        return sb.toString();
    }

    private void appendSuppliedStructuredEvidence(StringBuilder corpus, WebSearchService.SearchResult result) {
        if (!"STRUCTURED_DATA".equals(result.sourceType) || result.snippet == null) return;
        try {
            JSONObject payload = JSON.parseObject(result.snippet);
            JSONObject quote = payload.getJSONObject("quote");
            for (String field : List.of("price", "previousClose", "open", "high", "low", "changeAmount",
                    "amount", "mainInflow", "superLargeInflow", "largeInflow", "mediumInflow", "smallInflow")) {
                appendExactNumericEvidence(corpus, quote, field, "元");
            }
            for (String field : List.of("mainInflowRatio", "superLargeInflowRatio", "largeInflowRatio",
                    "mediumInflowRatio", "smallInflowRatio")) {
                appendExactNumericEvidence(corpus, quote, field, "%");
            }
            JSONArray dailyRows = payload.getJSONArray("dailyKline");
            if (dailyRows != null) {
                for (int i = 0; i < dailyRows.size(); i++) {
                    JSONObject row = dailyRows.getJSONObject(i);
                    for (String field : List.of("open", "high", "low", "close", "change", "amount")) {
                        appendExactNumericEvidence(corpus, row, field, "元");
                    }
                    for (String field : List.of("changePct", "amplitudePct", "turnoverPct")) {
                        appendExactNumericEvidence(corpus, row, field, "%");
                    }
                }
            }
            JSONArray rows = payload.getJSONArray("financials");
            if (rows == null) return;
            // Every supplied row is available to the speaker. Preserve raw decimal precision
            // and units here; the general fact cards intentionally summarize only the latest row.
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                for (String field : List.of("eps", "revenue", "netProfit", "netProfitExNonRecurring")) {
                    appendExactNumericEvidence(corpus, row, field, "元");
                }
                for (String field : List.of("revenueYoyPct", "netProfitYoyPct", "roePct", "grossMarginPct",
                        "netMarginPct", "cashToRevenuePct", "debtRatioPct")) {
                    appendExactNumericEvidence(corpus, row, field, "%");
                }
            }
        } catch (RuntimeException invalidStructuredPayload) {
            // No source fields can be inferred from malformed JSON.
        }
    }

    private void appendExactNumericEvidence(StringBuilder corpus, JSONObject row, String field, String unit) {
        if (row == null || row.get(field) == null) return;
        try {
            BigDecimal value = new BigDecimal(row.get(field).toString());
            corpus.append(field).append(':').append(value.toPlainString()).append(unit).append('\n');
        } catch (NumberFormatException invalidNumber) {
            // Missing/non-numeric provider values do not become evidence.
        }
    }

    private boolean isProvisionalResult(WebSearchService.SearchResult result) {
        return "n8n-deepseek-provisional".equals(result.provider)
                || "LOW_CONFIDENCE_SEARCH".equals(result.sourceType);
    }

    private String normalizeGroundingText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("Ａ", "a")
                .replace("ａ", "a")
                .replace("Ｂ", "b")
                .replace("ｂ", "b")
                .replace("Ｒ", "r")
                .replace("ｒ", "r")
                .replace("Ｏ", "o")
                .replace("ｏ", "o")
                .replace("Ｅ", "e")
                .replace("ｅ", "e")
                .replace("Ｉ", "i")
                .replace("ｉ", "i")
                .replace("Ｃ", "c")
                .replace("ｃ", "c")
                .replace("＝", "=");
    }

    private Set<String> extractStockCodesWithMarket(String text) {
        Set<String> codes = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return codes;
        }
        Matcher matcher = STOCK_CODE_WITH_MARKET_PATTERN.matcher(text);
        while (matcher.find()) {
            String code = firstNonBlank(matcher.group(1), matcher.group(2));
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        return codes;
    }

    private String buildCompactSearchGroundedPrompt(Long meetingId,
                                                    AiMeetingMember member,
                                                    int roundNumber,
                                                    SearchPlan searchPlan,
                                                    List<WebSearchService.SearchResult> searchResults,
                                                    AiMeetingOpinion targetedQuestion) {
        String title = "";
        try {
            if (meetingId != null) {
                com.aimeeting.room.entity.AiMeetingRoom room = roomMapper.selectById(meetingId);
                if (room != null && room.getTitle() != null) {
                    title = room.getTitle().trim();
                }
            }
        } catch (Exception ignored) {
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("【会议主题】").append(title.isBlank() ? "未命名议题" : title).append("\n");
        prompt.append("【当前轮次】第").append(roundNumber).append("轮\n");
        if (member != null && member.getMemberName() != null) {
            prompt.append("【你的身份】").append(member.getMemberName()).append("\n");
        }
        if (member != null && member.getPersona() != null && !member.getPersona().isBlank()) {
            prompt.append("【角色倾向】").append(member.getPersona().trim()).append("\n");
        }
        if (searchPlan != null) {
            if (searchPlan.intent != null && !searchPlan.intent.isBlank()) {
                prompt.append("【检索意图】").append(searchPlan.intent).append("\n");
            }
            if (searchPlan.targetYear != null) {
                prompt.append("【目标年份】").append(searchPlan.targetYear).append("\n");
            }
        }
        if (targetedQuestion != null && targetedQuestion.getContent() != null && !targetedQuestion.getContent().isBlank()) {
            prompt.append("【必须优先回应的问题】").append(targetedQuestion.getContent().trim()).append("\n");
        }
        prompt.append("【联网结果】\n");
        int limit = Math.min(searchResults != null ? searchResults.size() : 0, 4);
        for (int i = 0; i < limit; i++) {
            WebSearchService.SearchResult result = searchResults.get(i);
            prompt.append("[").append(i + 1).append("] ")
                    .append(result.source != null ? result.source : "来源未标记").append("\n");
            if (result.snippet != null && !result.snippet.isBlank()) {
                prompt.append("摘要：").append(truncateForRetry(result.snippet.trim(), 220)).append("\n");
            }
            if (result.url != null && !result.url.isBlank()) {
                prompt.append("链接：").append(result.url).append("\n");
            }
        }
        prompt.append("【输出要求】请基于以上联网结果给出一段完整发言：")
                .append("先明确立场，再给出2-3条最关键依据；只使用上面的联网结果，不得伪造实时数据；")
                .append("不得引用未在上面出现的股票代码、公司、财务指标、估值、Beta、IC值、因子排名或自有量化池；")
                .append("若证据不足，请明确写出保留意见。输出纯文本正文，不要 JSON。");
        return prompt.toString();
    }

    private String truncateForRetry(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "…";
    }

    private String resolveModelFailureNote(ChatResult chatResult) {
        if (chatResult == null) {
            return "模型未返回任何结果";
        }
        if (chatResult.modelNote != null && !chatResult.modelNote.isBlank()) {
            return chatResult.modelNote.trim();
        }
        if (chatResult.modelStatus == null || chatResult.modelStatus.isBlank()) {
            return "模型未返回可用正文";
        }
        switch (chatResult.modelStatus) {
            case "missing_model":
                return "当前模型未启用或不存在";
            case "api_call_failed":
                return "模型 API 调用失败、超时或返回非 2xx 状态";
            case "empty_choices":
                return "模型响应中没有候选答案";
            case "response_parse_failed":
                return "模型响应解析失败";
            case "blank_content":
                return "模型返回正文为空";
            case "timeout_without_fallback":
                return "内置联网搜索超时且没有稳定模型可回退";
            case "fallback_model_failed":
                return "稳定回退模型也未返回可用正文";
            default:
                return "模型未返回可用正文";
        }
    }

    private List<String> mergeQueries(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) {
            first.stream().filter(q -> q != null && !q.isBlank()).map(String::trim).forEach(merged::add);
        }
        if (second != null) {
            second.stream().filter(q -> q != null && !q.isBlank()).map(String::trim).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private String resolveStableFallbackModelType(String experimentalModelType) {
        if (experimentalModelType == null || experimentalModelType.isBlank()) {
            return null;
        }
        ModelEntry experimental = modelRegistryStore.getByModelId(experimentalModelType);
        if (experimental == null || experimental.getModelId() == null) {
            return null;
        }
        for (ModelEntry candidate : modelRegistryStore.getEnabled()) {
            if (candidate == null || candidate == experimental) {
                continue;
            }
            if (!experimental.getModelId().equals(candidate.getModelId())) {
                continue;
            }
            if ("claude".equalsIgnoreCase(candidate.getApiFormat())
                    && candidate.getApiUrl() != null
                    && candidate.getApiUrl().contains("/anthropic/")) {
                continue;
            }
            return candidate.getId();
        }
        return null;
    }

    private String resolveGenerationModelType(String originalModelType,
                                              boolean preferWebSearchProvider,
                                              List<WebSearchService.SearchResult> searchResults) {
        if (!preferWebSearchProvider || searchResults == null || searchResults.isEmpty()) {
            return originalModelType;
        }
        String stableFallbackModelType = resolveStableFallbackModelType(originalModelType);
        return stableFallbackModelType != null ? stableFallbackModelType : originalModelType;
    }

    private String resolveDecisionModelType(String originalModelType, boolean preferWebSearchProvider) {
        if (!preferWebSearchProvider) {
            return originalModelType;
        }
        String stableFallbackModelType = resolveStableFallbackModelType(originalModelType);
        return stableFallbackModelType != null ? stableFallbackModelType : originalModelType;
    }

    private boolean preferWebSearchProvider(String modelType) {
        return supportsBuiltInSearch(modelType) && webSearch.isDeepSeekWebSearchEnabled();
    }

    private boolean supportsBuiltInSearch(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return false;
        }
        ModelEntry entry = modelRegistryStore.getByModelId(modelType);
        if (entry == null || !entry.isEnabled()) {
            return false;
        }
        String format = entry.getApiFormat();
        String apiUrl = entry.getApiUrl();
        String normalizedModel = entry.getModelId() != null ? entry.getModelId().toLowerCase() : "";
        return "claude".equalsIgnoreCase(format)
                && apiUrl != null
                && apiUrl.contains("/anthropic/")
                && (normalizedModel.startsWith("deepseek-v4") || normalizedModel.startsWith("deepseek-v4-flash"));
    }

    private AiMeetingOpinion generateRebuttal(Long meetingId, AiMeetingMember member,
                                               AiMeetingOpinion target, int roundNumber) {
        String systemPrompt = buildSystemPrompt(member);

        // 反驳时也允许议员自行决定是否补充搜索
        String decisionPrompt = orchestrator.buildSearchDecisionPrompt(meetingId, member.getId(), roundNumber);
        boolean preferWebSearchProvider = preferWebSearchProvider(member.getModelType());
        String decisionModelType = resolveDecisionModelType(member.getModelType(), preferWebSearchProvider);
        String decisionRaw = orchestrator.callModel(decisionModelType, systemPromptForDecision(member), decisionPrompt, 220);

        List<WebSearchService.SearchResult> searchResults = new ArrayList<>();
        List<String> searchQueries = new ArrayList<>();
        SearchPlan searchPlan = defaultSearchPlan(meetingId);
        if (decisionRaw != null && webSearch.isEnabled()) {
            webSearch.beginSession();
            searchPlan = parseSearchPlan(decisionRaw, meetingId);
            searchQueries = searchPlan.queries;
            if (preferWebSearchProvider || !supportsBuiltInSearch(member.getModelType())) {
                for (String q : searchQueries) {
                    searchResults.addAll(webSearch.search(q, 2, null, preferWebSearchProvider));
                }
            }
        }
        searchResults = prioritizeSearchResults(searchResults, searchPlan, meetingId);
        JSONObject conflictValidation = buildConflictValidation(meetingId, member.getId(), roundNumber, searchResults);
        WebSearchService.ResearchDigest researchDigest = buildResearchDigest(meetingId, searchPlan, searchQueries, searchResults);
        WebSearchService.SearchMeta searchMeta = webSearch.consumeSessionMeta();

        List<AiMeetingOpinion> currentRound = opinionMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        StringBuilder prompt = new StringBuilder();
        prompt.append("群聊中其他人刚才说：\n\n");
        for (AiMeetingOpinion op : currentRound) {
            if (!op.getMemberId().equals(member.getId())) {
                AiMeetingMember speaker = memberMapper.selectById(op.getMemberId());
                String speakerName = speaker != null ? speaker.getMemberName() : "某成员";
                prompt.append(speakerName).append("：").append(op.getContent()).append("\n");
                if (op.getEvidence() != null) {
                    prompt.append("  (他引用的论据：").append(summarizeEvidence(op.getEvidence())).append(")\n");
                }
                prompt.append("\n");
            }
        }
        if (!searchResults.isEmpty()) {
            prompt.append("【你刚查到的最新资讯】\n")
                  .append(buildSearchContext(searchResults, researchDigest, searchPlan)).append("\n");
        }
        String conflictPrompt = buildConflictPrompt(conflictValidation);
        if (conflictPrompt != null && !conflictPrompt.isBlank()) {
            prompt.append(conflictPrompt).append("\n");
        }
        prompt.append("请针对对方的论据或逻辑进行深度回应，可以反驳、补充或引用你查到的最新依据。如果主持人有最新指令，必须以此为核心调整你的立场。\n\n");
        prompt.append("请严格按以下格式回复：\n");
        prompt.append("{\"q\":[\"搜索词\"],\"e\":[{\"s\":\"来源\",\"p\":\"你引用的核心论据\"}]}\n");
        prompt.append("==REPLY==\n");
        prompt.append("深度回应内容（只引用上方资讯中可定位的数据和来源；证据不足时明确说明，不得补数）");

        String generationModelType = resolveGenerationModelType(member.getModelType(), preferWebSearchProvider, searchResults);
        String rawResponse = orchestrator.callModel(generationModelType, systemPrompt, prompt.toString());
        if (rawResponse == null) return null;
        if (shouldRetryForUnresolvedConflict(rawResponse, conflictValidation)) {
            String revised = retryWithConflictResolution(systemPrompt, prompt.toString(), generationModelType, rawResponse, conflictValidation);
            if (revised != null && !revised.isBlank()) {
                rawResponse = revised;
            }
        }

        String[] parsed = parseEvidenceAndContent(rawResponse);
        String evidence = buildEvidenceJson(meetingId, member.getId(), roundNumber, parsed[0], searchQueries, searchResults, searchPlan, new ChatResult(rawResponse), searchMeta, researchDigest, conflictValidation);
        String content  = parsed[1];

        int opType = content.contains("同意") || content.contains("赞同") || content.contains("没错") || content.contains("对的")
                ? OpinionTypeEnum.CONCESSION.getCode()
                : OpinionTypeEnum.REBUTTAL.getCode();

        AiMeetingOpinion rebuttal = new AiMeetingOpinion();
        rebuttal.setMeetingId(meetingId);
        rebuttal.setMemberId(member.getId());
        rebuttal.setRoundNumber(roundNumber);
        rebuttal.setParentOpinionId(target.getId());
        rebuttal.setContent(content);
        rebuttal.setEvidence(evidence);
        rebuttal.setOpinionType(opType);
        opinionMapper.insert(rebuttal);
        return rebuttal;
    }

    // ── 解析阶段1的决策输出 ──

    private SearchPlan parseSearchPlan(String decisionRaw, Long meetingId) {
        SearchPlan plan = defaultSearchPlan(meetingId);
        try {
            int s = decisionRaw.indexOf('{'), e = decisionRaw.lastIndexOf('}');
            if (s < 0 || e <= s) return plan;
            JSONObject obj = JSON.parseObject(decisionRaw.substring(s, e + 1));
            if (!obj.getBooleanValue("need")) return new SearchPlan();
            String intent = obj.getString("intent");
            if (intent != null && !intent.isBlank()) {
                plan.intent = intent.trim();
            }
            Integer freshnessDays = obj.getInteger("freshnessDays");
            if (freshnessDays != null && freshnessDays > 0) {
                plan.freshnessDays = Math.min(freshnessDays, 365);
            }
            Integer targetYear = obj.getInteger("targetYear");
            if (targetYear != null && targetYear >= 2000 && targetYear <= 2100) {
                plan.targetYear = targetYear;
            }
            JSONArray sourceFocus = obj.getJSONArray("sourceFocus");
            if (sourceFocus != null) {
                sourceFocus.forEach(item -> {
                    if (item != null) {
                        plan.sourceFocus.add(item.toString());
                    }
                });
                plan.sourceFocus = normalizeSourceFocus(plan.sourceFocus);
            }
            JSONArray q = obj.getJSONArray("q");
            if (q != null) {
                q.forEach(item -> {
                    if (item != null) {
                        plan.queries.add(item.toString());
                    }
                });
            }
            applyEastmoneyPlanFields(plan, obj);
            if (plan.queries.isEmpty() && !plan.sourceFocus.isEmpty()) {
                List<String> fallbackQueries = new ArrayList<>();
                String title = resolveMeetingTitle(meetingId);
                if (!title.isBlank()) {
                    fallbackQueries.add((title + " 最新").trim());
                }
                plan.queries = buildSourceAwareQueries(fallbackQueries, plan.sourceFocus, 3);
            } else if (!plan.queries.isEmpty() && !plan.sourceFocus.isEmpty()) {
                plan.queries = buildSourceAwareQueries(plan.queries, plan.sourceFocus, 3);
            }
        } catch (Exception ex) {
            log.debug("parseSearchPlan failed: {}", ex.getMessage());
        }
        return plan;
    }

    private void applyEastmoneyPlanFields(SearchPlan plan, JSONObject obj) {
        if (plan == null || obj == null) {
            return;
        }
        String symbol = obj.getString("eastmoneySymbol");
        String market = obj.getString("eastmoneyMarket");
        JSONArray fields = obj.getJSONArray("eastmoneyFields");

        JSONObject nested = obj.getJSONObject("eastmoney");
        if (nested != null && (nested.getBooleanValue("need") || nested.containsKey("fields"))) {
            symbol = firstNonBlank(nested.getString("symbol"), symbol);
            market = firstNonBlank(nested.getString("market"), market);
            JSONArray nestedFields = nested.getJSONArray("fields");
            if (nestedFields != null) {
                fields = nestedFields;
            }
        }
        if (symbol != null && !symbol.isBlank()) {
            StockRef ref = parseStockRef(symbol, market);
            if (ref != null) {
                plan.eastmoneySymbol = ref.symbol;
                plan.eastmoneyMarket = ref.market;
            }
        }
        if (fields != null) {
            for (Object item : fields) {
                if (item != null) {
                    plan.eastmoneyFields.add(String.valueOf(item));
                }
            }
            plan.eastmoneyFields = normalizeEastmoneyFields(plan.eastmoneyFields, plan.intent);
        } else if (nested != null && nested.getBooleanValue("need")) {
            plan.eastmoneyFields = defaultEastmoneyFields(plan.intent);
        }
    }

    // ── 构建最终存储的 evidence JSON ──

    private String buildEvidenceJson(Long meetingId,
                                     Long memberId,
                                     int roundNumber,
                                     String aiEvidencePart,
                                     List<String> queries,
                                     List<WebSearchService.SearchResult> realResults,
                                     SearchPlan searchPlan,
                                     ChatResult chatResult,
                                     WebSearchService.SearchMeta webSearchMeta,
                                     WebSearchService.ResearchDigest researchDigest,
                                     JSONObject conflictValidation) {
        JSONObject ev = new JSONObject();
        List<String> storedQueries = dedupeSearchQueries(queries);
        List<WebSearchService.SearchResult> storedResults = dedupeSearchResults(realResults);
        boolean keepAiEvidence = shouldKeepAiEvidence(chatResult, storedResults, searchPlan);
        boolean storedAiEvidence = false;

        // AI 自己提炼的论据不是 provider 原始结果；只在没有真实搜索结果时作为未验证自述保留。
        if (aiEvidencePart != null && keepAiEvidence) {
            try {
                int s = aiEvidencePart.indexOf('{'), e = aiEvidencePart.lastIndexOf('}');
                if (s >= 0 && e > s) {
                    JSONObject parsed = JSON.parseObject(aiEvidencePart.substring(s, e + 1));
                    if (parsed.containsKey("q")) ev.put("q", parsed.get("q"));
                    if (parsed.containsKey("e")) {
                        ev.put("e", parsed.get("e"));
                        storedAiEvidence = true;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (storedAiEvidence) {
            ev.put("aiEvidenceUnverified", true);
        }

        // 实际搜索的关键词（覆盖 AI 自述的搜索词）
        if (!storedQueries.isEmpty()) {
            JSONArray qArr = new JSONArray();
            storedQueries.forEach(qArr::add);
            ev.put("q", qArr);
        }
        if (searchPlan != null && !searchPlan.isEmpty()) {
            JSONObject planObj = new JSONObject();
            planObj.put("intent", searchPlan.intent);
            planObj.put("freshnessDays", searchPlan.freshnessDays);
            planObj.put("targetYear", searchPlan.targetYear);
            if (searchPlan.sourceFocus != null && !searchPlan.sourceFocus.isEmpty()) {
                JSONArray sourceArr = new JSONArray();
                searchPlan.sourceFocus.forEach(sourceArr::add);
                planObj.put("sourceFocus", sourceArr);
            }
            if (searchPlan.eastmoneySymbol != null && !searchPlan.eastmoneySymbol.isBlank()) {
                planObj.put("eastmoneySymbol", searchPlan.eastmoneySymbol);
                planObj.put("eastmoneyMarket", searchPlan.eastmoneyMarket);
            }
            if (searchPlan.eastmoneyFields != null && !searchPlan.eastmoneyFields.isEmpty()) {
                JSONArray fieldsArr = new JSONArray();
                searchPlan.eastmoneyFields.forEach(fieldsArr::add);
                planObj.put("eastmoneyFields", fieldsArr);
            }
            ev.put("searchPlan", planObj);
        }
        JSONObject searchMeta = buildStoredSearchMeta(storedQueries, storedResults, chatResult, webSearchMeta);
        if (searchMeta != null) {
            ev.put("searchMeta", searchMeta);
        }
        if (researchDigest != null) {
            ev.put("researchDigest", researchDigest.toJson());
        }
        if (conflictValidation == null && !storedResults.isEmpty()) {
            conflictValidation = buildConflictValidation(meetingId, memberId, roundNumber, storedResults);
        }
        if (conflictValidation != null && !conflictValidation.isEmpty()) {
            ev.put("conflictValidation", conflictValidation);
        }
        JSONObject claimAudit = buildClaimAudit(chatResult, storedResults, searchPlan);
        if (claimAudit != null && !claimAudit.isEmpty()) {
            ev.put("claimAudit", claimAudit);
        }
        if (chatResult != null && chatResult.unverifiedReply != null && !chatResult.unverifiedReply.isBlank()) {
            ev.put("unverifiedModelReply", chatResult.unverifiedReply);
        }
        if (chatResult != null && chatResult.rejectedClaimAuditJson != null) {
            try {
                ev.put("rejectedClaimAudit", JSON.parseObject(chatResult.rejectedClaimAuditJson));
            } catch (Exception ignored) {
            }
        }
        JSONObject factCatalog = buildFactCatalog(storedResults);
        if (factCatalog != null && !factCatalog.isEmpty()) {
            ev.put("factCatalog", factCatalog);
        }

        // 真实搜索结果
        if (!storedResults.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (WebSearchService.SearchResult r : storedResults) {
                JSONObject item = new JSONObject();
                item.put("source", r.source);
                item.put("snippet", limitText(r.snippet,
                        "STRUCTURED_DATA".equalsIgnoreCase(r.sourceType) ? 1200 : 240));
                item.put("url", r.url);
                item.put("provider", r.provider);
                item.put("sourceType", r.sourceType);
                item.put("query", r.query);
                item.put("fetchedAt", r.fetchedAt);
                item.put("rawHash", r.rawHash);
                JSONObject sourceTrace = buildStructuredDataSourceTrace(r);
                if (sourceTrace != null && !sourceTrace.isEmpty()) {
                    item.put("sourceTrace", sourceTrace);
                }
                JSONArray facts = buildResultFacts(r, 12);
                if (!facts.isEmpty()) {
                    item.put("facts", facts);
                }
                item.put("score", scoreSearchResult(r, searchPlan));
                item.put("year", extractYear(r));
                arr.add(item);
            }
            ev.put("results", arr);
            ev.put("validation", buildValidationSummary(storedResults, searchPlan));
        } else if (chatResult != null) {
            JSONObject validation = buildMissingLiveValidation(searchPlan, chatResult);
            if (!validation.isEmpty()) {
                ev.put("validation", validation);
            }
        }

        if (ev.isEmpty()) return null;
        return ev.toJSONString();
    }

    private JSONObject buildClaimAudit(ChatResult chatResult,
                                       List<WebSearchService.SearchResult> searchResults,
                                       SearchPlan searchPlan) {
        if (chatResult == null || chatResult.content == null || chatResult.content.isBlank()) {
            return null;
        }
        boolean strictEvidenceRequired = isStockGroundedSearch(searchPlan, searchResults);
        if (!strictEvidenceRequired) {
            return null;
        }
        // The JSON before ==REPLY== is the model's own evidence claim, not the
        // published opinion. Auditing it as prose creates false violations.
        String normalizedContent = normalizeGroundingText(parseEvidenceAndContent(chatResult.content)[1]);
        String normalizedEvidence = normalizeGroundingText(buildSearchEvidenceCorpus(searchResults));
        if (normalizedContent.isBlank()) {
            return null;
        }

        JSONArray unsupportedNumericClaims = findUnsupportedNumericClaims(normalizedContent, normalizedEvidence, searchPlan);
        JSONArray guardedTermViolations = findUnsupportedGuardedTerms(normalizedContent, normalizedEvidence);
        JSONArray stockCodeViolations = findUnsupportedStockCodeClaims(normalizedContent, normalizedEvidence, searchPlan);
        JSONArray targetMismatchViolations = findTargetMismatchViolations(searchResults, searchPlan);
        boolean missingReply = isEvidenceOnlyModelResponse(chatResult.content);
        int totalViolations = unsupportedNumericClaims.size()
                + guardedTermViolations.size()
                + stockCodeViolations.size()
                + targetMismatchViolations.size()
                + (missingReply ? 1 : 0);

        JSONObject audit = new JSONObject(true);
        audit.put("strictEvidenceRequired", true);
        audit.put("status", totalViolations > 0 ? "unsupported_claims" : "ok");
        audit.put("numericClaimCount", countNumericClaims(normalizedContent, searchPlan));
        audit.put("unsupportedNumericClaimCount", unsupportedNumericClaims.size());
        audit.put("guardedTermViolationCount", guardedTermViolations.size());
        audit.put("stockCodeViolationCount", stockCodeViolations.size());
        audit.put("targetMismatchViolationCount", targetMismatchViolations.size());
        audit.put("formatViolationCount", missingReply ? 1 : 0);
        audit.put("unsupportedNumericClaims", unsupportedNumericClaims);
        audit.put("guardedTermViolations", guardedTermViolations);
        audit.put("stockCodeViolations", stockCodeViolations);
        audit.put("targetMismatchViolations", targetMismatchViolations);
        audit.put("note", missingReply
                ? "模型仅返回论据 JSON，未提供 ==REPLY== 后的发言正文。"
                : totalViolations > 0
                ? "正文存在未在本轮 Provider/东方财富证据中定位到的数字或受控事实，不能直接当作已核验结论。"
                : "正文中的受控事实与硬数字未发现明显脱离本轮证据。");
        return audit;
    }

    private boolean isEvidenceOnlyModelResponse(String content) {
        return content != null
                && !content.contains(REPLY_MARKER)
                && MODEL_EVIDENCE_ONLY_PATTERN.matcher(content).find();
    }

    private boolean isBlockingClaimAudit(JSONObject audit) {
        return audit != null && "unsupported_claims".equalsIgnoreCase(audit.getString("status"));
    }

    private JSONArray findUnsupportedGuardedTerms(String normalizedContent, String normalizedEvidence) {
        JSONArray violations = new JSONArray();
        for (String[] guard : GUARDED_STOCK_TERMS) {
            String trigger = guard[0];
            if (!containsAssertedGuardedTerm(normalizedContent, trigger)) {
                continue;
            }
            boolean supported = false;
            for (int i = 1; i < guard.length; i++) {
                if (containsGuardedTerm(normalizedEvidence, guard[i])) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                JSONObject item = new JSONObject(true);
                item.put("term", trigger);
                item.put("reason", "not_found_in_provider_evidence");
                violations.add(item);
            }
        }
        return violations;
    }

    private boolean containsAssertedGuardedTerm(String content, String trigger) {
        for (String clause : content.split("[。！？!?；;\\n，,]")) {
            if (containsGuardedTerm(clause, trigger) && !isExplicitEvidenceDenial(clause, trigger)) return true;
        }
        return false;
    }

    private boolean isExplicitEvidenceDenial(String clause, String trigger) {
        String text = clause.replaceAll("[\\s“”\"‘’]", "");
        String term = Pattern.quote(normalizeGroundingText(trigger));
        String subject = "(?:机构)?" + term + "(?:数据|信息|数值|指标|结论)?";
        String denial = "(?:本轮)?(?:证据|资料)?(?:未提供|缺失|不足|无法核验|无法验证)"
                + "(?:/(?:无法核验|无法验证))?";
        // Only an entire omission clause is exempt. A positive assertion followed by
        // a caveat remains an assertion, as does another occurrence elsewhere.
        if (text.matches("(?:本轮(?:证据|资料)?[：:]?)?" + subject + denial)
                || text.matches("(?:本轮)?(?:证据|资料)?(?:未提供|缺少)" + subject)
                || text.matches(denial + "[：:]" + subject)) return true;
        Matcher listDenial = Pattern.compile("^(.+?)均?(" + denial + ")$").matcher(text);
        if (!listDenial.matches()) return false;
        for (String item : listDenial.group(1).split("、")) {
            boolean noun = item.matches("估值|当前市价|自由现金流|资本开支|有息负债结构|未来季度预测");
            for (String[] guard : GUARDED_STOCK_TERMS) {
                noun |= item.matches("(?:机构)?" + Pattern.quote(normalizeGroundingText(guard[0]))
                        + "(?:数据|信息|数值|指标|结论)?");
            }
            if (!noun) return false;
        }
        return true;
    }

    private boolean containsGuardedTerm(String normalizedText, String rawTerm) {
        if (normalizedText == null || normalizedText.isBlank() || rawTerm == null || rawTerm.isBlank()) {
            return false;
        }
        String term = normalizeGroundingText(rawTerm);
        if (term.isBlank()) {
            return false;
        }
        if (isAsciiLettersOnly(term)) {
            Pattern pattern = Pattern.compile("(?<![a-z])" + Pattern.quote(term) + "(?![a-z])");
            return pattern.matcher(normalizedText).find();
        }
        return normalizedText.contains(term);
    }

    private boolean isAsciiLettersOnly(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 'a' || ch > 'z') {
                return false;
            }
        }
        return true;
    }

    private JSONArray findUnsupportedNumericClaims(String normalizedContent,
                                                   String normalizedEvidence,
                                                   SearchPlan searchPlan) {
        JSONArray claims = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = GROUNDED_NUMERIC_CLAIM_PATTERN.matcher(normalizedContent);
        while (matcher.find()) {
            String raw = matcher.group();
            String normalizedRaw = raw.replaceAll("\\s+", "");
            String numberOnly = matcher.group(1);
            if (numberOnly.isBlank()) {
                continue;
            }
            if (searchPlan != null && searchPlan.eastmoneySymbol != null && numberOnly.equals(searchPlan.eastmoneySymbol)) {
                continue;
            }
            if (evidenceContainsEquivalentNumericClaim(normalizedEvidence, normalizedRaw, numberOnly)) {
                continue;
            }
            if (!seen.add(normalizedRaw)) {
                continue;
            }
            JSONObject item = new JSONObject(true);
            item.put("claim", normalizedRaw);
            item.put("number", numberOnly);
            item.put("reason", "not_found_in_provider_evidence");
            claims.add(item);
            if (claims.size() >= 10) {
                break;
            }
        }
        return claims;
    }

    private boolean evidenceContainsEquivalentNumericClaim(String normalizedEvidence, String normalizedRaw, String numberOnly) {
        if (normalizedEvidence == null || normalizedEvidence.isBlank() || numberOnly == null || numberOnly.isBlank()) {
            return false;
        }
        Matcher targetMatcher = GROUNDED_NUMERIC_CLAIM_PATTERN.matcher(normalizedRaw);
        if (!targetMatcher.matches()) return false;
        BigDecimal target;
        try {
            target = new BigDecimal(numberOnly).multiply(numericUnitScale(targetMatcher.group(2)));
        } catch (NumberFormatException invalidNumber) {
            return false;
        }
        String targetUnit = canonicalNumericUnit(targetMatcher.group(2));
        Matcher matcher = GROUNDED_NUMERIC_CLAIM_PATTERN.matcher(normalizedEvidence);
        while (matcher.find()) {
            if (!targetUnit.equals(canonicalNumericUnit(matcher.group(2)))) continue;
            try {
                BigDecimal candidate = new BigDecimal(matcher.group(1)).multiply(numericUnitScale(matcher.group(2)));
                if (candidate.compareTo(target) == 0) return true;
                // Only ordinary decimal display rounding is allowed; a unit, sign,
                // exponent or metric change is never covered by a relative epsilon.
                BigDecimal displayed = new BigDecimal(numberOnly);
                BigDecimal sourceInDisplayUnits = candidate.divide(numericUnitScale(targetMatcher.group(2)));
                if (!numberOnly.toLowerCase(Locale.ROOT).contains("e")
                        && candidate.signum() == target.signum()
                        && sourceInDisplayUnits.setScale(displayed.scale(), RoundingMode.HALF_UP)
                            .compareTo(displayed) == 0) return true;
            } catch (NumberFormatException invalidNumber) {
                // Continue looking for a valid, exact source value.
            }
        }
        return false;
    }

    private BigDecimal numericUnitScale(String unit) {
        return "亿元".equals(unit) || "亿".equals(unit) ? new BigDecimal("100000000")
                : "万元".equals(unit) ? new BigDecimal("10000") : BigDecimal.ONE;
    }

    private String canonicalNumericUnit(String unit) {
        return "亿元".equals(unit) || "亿".equals(unit) || "万元".equals(unit) ? "元"
                : "bps".equals(unit) ? "bp" : unit;
    }

    private JSONArray findUnsupportedStockCodeClaims(String normalizedContent,
                                                     String normalizedEvidence,
                                                     SearchPlan searchPlan) {
        JSONArray claims = new JSONArray();
        if (searchPlan == null || searchPlan.eastmoneySymbol == null || searchPlan.eastmoneySymbol.isBlank()) {
            return claims;
        }
        Set<String> mentionedCodes = extractStockCodesWithMarket(normalizedContent);
        if (mentionedCodes.isEmpty()) {
            return claims;
        }
        String target = searchPlan.eastmoneySymbol.trim();
        boolean correctionCue = normalizedContent.contains("不符")
                || normalizedContent.contains("无关")
                || normalizedContent.contains("错");
        for (String code : mentionedCodes) {
            if (target.equals(code) || correctionCue) {
                continue;
            }
            if (!normalizedEvidence.contains(code)) {
                continue;
            }
            JSONObject item = new JSONObject(true);
            item.put("claim", code);
            item.put("target", target);
            item.put("reason", "different_stock_code_mentioned");
            claims.add(item);
        }
        return claims;
    }

    private int countNumericClaims(String normalizedContent, SearchPlan searchPlan) {
        int count = 0;
        Matcher matcher = GROUNDED_NUMERIC_CLAIM_PATTERN.matcher(normalizedContent);
        while (matcher.find()) {
            String normalizedRaw = matcher.group().replaceAll("\\s+", "");
            String numberOnly = matcher.group(1);
            if (numberOnly.isBlank()) {
                continue;
            }
            if (searchPlan != null && searchPlan.eastmoneySymbol != null && numberOnly.equals(searchPlan.eastmoneySymbol)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private JSONObject buildStructuredDataSourceTrace(WebSearchService.SearchResult result) {
        if (result == null
                || !"STRUCTURED_DATA".equalsIgnoreCase(result.sourceType)
                || result.snippet == null
                || result.snippet.isBlank()) {
            return null;
        }
        try {
            JSONObject payload = JSON.parseObject(result.snippet);
            JSONObject dataQuality = payload == null ? null : payload.getJSONObject("dataQuality");
            JSONObject sourceStatus = dataQuality == null ? null : dataQuality.getJSONObject("sourceStatus");
            if (dataQuality == null && sourceStatus == null) {
                return null;
            }
            JSONObject trace = new JSONObject(true);
            if (dataQuality != null) {
                trace.put("canPredict", dataQuality.get("canPredict"));
                trace.put("requiredMissing", dataQuality.get("requiredMissing"));
                trace.put("optionalMissing", dataQuality.get("optionalMissing"));
            }
            if (sourceStatus != null && !sourceStatus.isEmpty()) {
                JSONArray sources = new JSONArray();
                for (String name : sourceStatus.keySet()) {
                    JSONObject status = sourceStatus.getJSONObject(name);
                    if (status == null) {
                        continue;
                    }
                    JSONObject item = new JSONObject(true);
                    item.put("name", name);
                    item.put("ok", status.get("ok"));
                    item.put("sourceType", status.get("sourceType"));
                    item.put("durationMs", status.get("durationMs"));
                    item.put("count", status.get("count"));
                    item.put("requestCount", status.get("requestCount"));
                    item.put("lastUrl", status.get("lastUrl"));
                    item.put("lastParamsHash", status.get("lastParamsHash"));
                    item.put("lastResponseHash", status.get("lastResponseHash"));
                    item.put("reason", status.get("reason"));
                    sources.add(item);
                }
                trace.put("sources", sources);
            }
            return trace;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject buildFactCatalog(List<WebSearchService.SearchResult> results) {
        List<NumericFact> facts = extractFactsFromSearchResults(results, "current_search", null);
        if (facts.isEmpty()) {
            return null;
        }
        JSONObject catalog = new JSONObject(true);
        JSONArray factArr = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        int structuredCount = 0;
        for (NumericFact fact : facts) {
            if (fact == null || fact.label == null || fact.rawValue == null) {
                continue;
            }
            if (fact.path != null && !fact.path.isBlank()) {
                structuredCount++;
            }
            String key = String.join("|",
                    firstNonBlank(fact.labelKey, fact.label),
                    firstNonBlank(fact.rawValue, ""),
                    firstNonBlank(fact.path, ""),
                    firstNonBlank(fact.source, "")
            );
            if (!seen.add(key)) {
                continue;
            }
            factArr.add(toFactJson(fact));
            if (factArr.size() >= 60) {
                break;
            }
        }
        if (factArr.isEmpty()) {
            return null;
        }
        catalog.put("factCount", factArr.size());
        catalog.put("structuredFactCount", structuredCount);
        catalog.put("facts", factArr);
        catalog.put("note", "结构化事实来自 Provider/东方财富字段路径，供正文事实审计与冲突检测复用。");
        return catalog;
    }

    private JSONArray buildResultFacts(WebSearchService.SearchResult result, int limit) {
        JSONArray arr = new JSONArray();
        List<NumericFact> facts = extractFactsFromStructuredResult(
                result,
                null,
                result == null ? null : result.source
        );
        for (NumericFact fact : facts) {
            arr.add(toFactJson(fact));
            if (arr.size() >= limit) {
                break;
            }
        }
        return arr;
    }

    private JSONObject toFactJson(NumericFact fact) {
        JSONObject item = new JSONObject(true);
        item.put("label", fact.label);
        item.put("path", fact.path);
        item.put("value", fact.value);
        item.put("unit", fact.unit);
        item.put("rawValue", fact.rawValue);
        item.put("owner", fact.owner);
        item.put("source", fact.source);
        item.put("sourceType", fact.sourceType);
        item.put("rawHash", fact.rawHash);
        return item;
    }

    private JSONObject buildStoredSearchMeta(List<String> queries,
                                             List<WebSearchService.SearchResult> realResults,
                                             ChatResult chatResult,
                                             WebSearchService.SearchMeta webSearchMeta) {
        boolean hasChatMeta = chatResult != null
                && (chatResult.searchMode != null || chatResult.searchStatus != null || chatResult.searchNote != null);
        boolean hasWebMeta = webSearchMeta != null
                && (webSearchMeta.provider != null
                || webSearchMeta.status != null
                || webSearchMeta.note != null
                || (webSearchMeta.providerDiagnostics != null && !webSearchMeta.providerDiagnostics.isEmpty()));
        if (!hasChatMeta && !hasWebMeta) {
            return null;
        }

        boolean hasRealResults = realResults != null && !realResults.isEmpty();
        boolean preferWebMeta = hasRealResults
                && hasWebMeta
                && webSearchMeta.provider != null
                && !webSearchMeta.provider.isBlank()
                && !"none".equalsIgnoreCase(webSearchMeta.provider);

        JSONObject meta = new JSONObject();
        meta.put("mode", preferWebMeta ? webSearchMeta.provider : firstNonBlank(hasChatMeta ? chatResult.searchMode : null, hasWebMeta ? webSearchMeta.provider : null));
        meta.put("status", preferWebMeta ? webSearchMeta.status : firstNonBlank(hasChatMeta ? chatResult.searchStatus : null, hasWebMeta ? webSearchMeta.status : null));
        meta.put("note", preferWebMeta ? firstNonBlank(hasWebMeta ? webSearchMeta.note : null, hasChatMeta ? chatResult.searchNote : null)
                : firstNonBlank(hasChatMeta ? chatResult.searchNote : null, hasWebMeta ? webSearchMeta.note : null));
        meta.put("queryCount", queries != null ? queries.size() : hasWebMeta ? webSearchMeta.queryCount : null);
        if (hasChatMeta) {
            meta.put("generationMode", chatResult.searchMode);
            meta.put("generationStatus", chatResult.searchStatus);
            meta.put("generationNote", chatResult.searchNote);
            meta.put("modelStatus", chatResult.modelStatus);
            meta.put("modelNote", chatResult.modelNote);
            boolean generationDegraded = isGenerationDegraded(chatResult);
            meta.put("generationDegraded", generationDegraded);
            if (generationDegraded) {
                meta.put("degraded", true);
                meta.put("degradedReason", firstNonBlank(chatResult.searchNote, chatResult.modelNote, chatResult.searchStatus));
            }
        }
        if (hasWebMeta) {
            meta.put("provider", webSearchMeta.provider);
            meta.put("providerStatus", webSearchMeta.status);
            meta.put("providerNote", webSearchMeta.note);
            meta.put("providerDiagnostics", webSearchMeta.providerDiagnostics);
            meta.put("digestMode", webSearchMeta.digestMode);
            meta.put("digestStatus", webSearchMeta.digestStatus);
            meta.put("digestNote", webSearchMeta.digestNote);
            meta.put("digestSummaryLength", webSearchMeta.digestSummaryLength);
            meta.put("digestClaimCount", webSearchMeta.digestClaimCount);
            meta.put("digestSourceCount", webSearchMeta.digestSourceCount);
            meta.put("digestTimingMs", webSearchMeta.digestTimingMs);
            meta.put("providerResultCount", webSearchMeta.providerResultCount);
            meta.put("providerTimingMs", webSearchMeta.providerTimingMs);
            meta.put("bridgeRoundTripMs", webSearchMeta.bridgeRoundTripMs);
            meta.put("expertMode", webSearchMeta.expertMode);
            meta.put("deepThinkEnabled", webSearchMeta.deepThinkEnabled);
            meta.put("searchEnabled", webSearchMeta.searchEnabled);
            meta.put("contentStatus", webSearchMeta.contentStatus);
            meta.put("contentNote", webSearchMeta.contentNote);
            meta.put("answerSource", webSearchMeta.answerSource);
            meta.put("waitDurationMs", webSearchMeta.waitDurationMs);
            meta.put("completionReason", webSearchMeta.completionReason);
            meta.put("answerLength", webSearchMeta.answerLength);
            meta.put("newLinkCount", webSearchMeta.newLinkCount);
            meta.put("candidateBlockCount", webSearchMeta.candidateBlockCount);
            meta.put("assistantBlockDeltaCount", webSearchMeta.assistantBlockDeltaCount);
            meta.put("bodyDeltaLength", webSearchMeta.bodyDeltaLength);
        }
        meta.put("hasRealResults", hasRealResults);
        return meta;
    }

    private boolean isGenerationDegraded(ChatResult chatResult) {
        if (chatResult == null) {
            return false;
        }
        String status = chatResult.searchStatus != null ? chatResult.searchStatus.toLowerCase(Locale.ROOT) : "";
        if (status.contains("strict_grounding_fallback") || status.contains("search_backed_fallback")) {
            return true;
        }
        if (status.contains("fallback") && !status.startsWith("success_after")) {
            return true;
        }
        String modelStatus = chatResult.modelStatus != null ? chatResult.modelStatus.toLowerCase(Locale.ROOT) : "";
        return !modelStatus.isBlank()
                && !containsAny(modelStatus, "success", "ok")
                && containsAny(modelStatus, "timeout", "error", "failed", "incomplete", "fallback");
    }

    private List<WebSearchService.SearchResult> fetchEastmoneyResults(Long meetingId, SearchPlan searchPlan) {
        if (searchPlan == null) {
            return Collections.emptyList();
        }
        List<String> fields = normalizeEastmoneyFields(searchPlan.eastmoneyFields, searchPlan.intent);
        if (fields.isEmpty()) {
            return Collections.emptyList();
        }
        StockRef ref = resolveEastmoneyStockRef(meetingId, searchPlan);
        if (ref == null) {
            log.warn("Eastmoney structured data requested but stock ref is missing. meetingId={}, fields={}",
                    meetingId, fields);
            return Collections.emptyList();
        }
        try {
            EastmoneyMarketDataService.MarketDataBundle bundle =
                    eastmoneyMarketDataService.buildBundle(ref.symbol, ref.market, "DAY");
            Map<String, Object> compact = compactEastmoneyPayload(bundle.getPayload(), fields);
            if (compact.isEmpty()) {
                return Collections.emptyList();
            }
            WebSearchService.SearchResult result = new WebSearchService.SearchResult();
            result.source = "东方财富结构化数据 " + ref.symbol + "." + ref.market + " " + String.join(",", fields);
            result.url = "https://quote.eastmoney.com/" + ref.market + ref.symbol + ".html";
            String rawPayload = JSON.toJSONString(compact);
            result.snippet = serializeStructuredSearchSnippet(compact, 7000);
            result.withTrace("eastmoney", "STRUCTURED_DATA",
                    buildEastmoneyQueryLabel(searchPlan, Collections.singletonList(result)), rawPayload);
            log.info("Eastmoney structured data fetched for meetingId={} stock={}.{} fields={}",
                    meetingId, ref.symbol, ref.market, fields);
            return Collections.singletonList(result);
        } catch (Exception e) {
            log.warn("Eastmoney structured data fetch failed. meetingId={} stock={}.{} fields={} error={}",
                    meetingId, ref.symbol, ref.market, fields, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildEastmoneyQueryLabel(SearchPlan searchPlan, List<WebSearchService.SearchResult> results) {
        String ref = "";
        if (searchPlan != null && searchPlan.eastmoneySymbol != null) {
            ref = searchPlan.eastmoneySymbol + "." + firstNonBlank(searchPlan.eastmoneyMarket, inferMarket(searchPlan.eastmoneySymbol));
        } else if (results != null && !results.isEmpty() && results.get(0).source != null) {
            ref = results.get(0).source;
        }
        List<String> fields = searchPlan != null ? normalizeEastmoneyFields(searchPlan.eastmoneyFields, searchPlan.intent) : Collections.emptyList();
        return "EASTMONEY:" + firstNonBlank(ref, "stock") + ":" + String.join(",", fields);
    }

    private StockRef resolveEastmoneyStockRef(Long meetingId, SearchPlan searchPlan) {
        boolean allowProjectMetadataTarget = shouldAllowProjectMetadataTarget(searchPlan);
        StockRef meetingTarget = resolveMeetingTargetStockRef(meetingId, allowProjectMetadataTarget);
        if (meetingTarget != null) {
            if (searchPlan != null) {
                searchPlan.eastmoneySymbol = meetingTarget.symbol;
                searchPlan.eastmoneyMarket = meetingTarget.market;
            }
            return meetingTarget;
        }
        if (searchPlan != null && searchPlan.eastmoneySymbol != null && !searchPlan.eastmoneySymbol.isBlank()) {
            StockRef ref = parseStockRef(searchPlan.eastmoneySymbol, searchPlan.eastmoneyMarket);
            if (ref != null) {
                searchPlan.eastmoneySymbol = ref.symbol;
                searchPlan.eastmoneyMarket = ref.market;
                return ref;
            }
        }
        String searchText = resolveMeetingSearchText(meetingId, allowProjectMetadataTarget);
        StockRef fromText = parseStockRef(searchText, null);
        if (fromText != null) {
            if (searchPlan != null) {
                searchPlan.eastmoneySymbol = fromText.symbol;
                searchPlan.eastmoneyMarket = fromText.market;
            }
            return fromText;
        }
        StockRef known = resolveKnownAshareRef(searchText);
        if (known != null) {
            if (searchPlan != null) {
                searchPlan.eastmoneySymbol = known.symbol;
                searchPlan.eastmoneyMarket = known.market;
            }
            return known;
        }
        StockRef watched = resolveWatchedStockRef(searchText);
        if (watched != null) {
            if (searchPlan != null) {
                searchPlan.eastmoneySymbol = watched.symbol;
                searchPlan.eastmoneyMarket = watched.market;
            }
            return watched;
        }
        return null;
    }

    private StockRef resolveMeetingTargetStockRef(Long meetingId) {
        return resolveMeetingTargetStockRef(meetingId, true);
    }

    private StockRef resolveMeetingTargetStockRef(Long meetingId, boolean allowProjectMetadataTarget) {
        if (meetingId == null) {
            return null;
        }
        try {
            AiMeetingRoom room = roomMapper.selectById(meetingId);
            if (room == null) {
                return null;
            }
            StockRef fromDescription = parseStockRefFromDescriptionJson(room.getDescription());
            if (fromDescription != null) {
                return fromDescription;
            }
            if (allowProjectMetadataTarget) {
                StockRef fromProjectKey = parseStockRef(room.getProjectKey(), null);
                if (fromProjectKey != null) {
                    return fromProjectKey;
                }
            }
            return parseStockRef(room.getTitle(), null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean shouldAllowProjectMetadataTarget(SearchPlan searchPlan) {
        return searchPlan == null || !"technical_indicator_research".equalsIgnoreCase(searchPlan.intent);
    }

    private StockRef parseStockRefFromDescriptionJson(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(description);
            String symbol = firstNonBlank(
                    obj.getString("symbol"),
                    obj.getString("stockSymbol"),
                    obj.getString("eastmoneySymbol")
            );
            String market = firstNonBlank(
                    obj.getString("market"),
                    obj.getString("stockMarket"),
                    obj.getString("eastmoneyMarket")
            );
            StockRef ref = parseStockRef(symbol, market);
            if (ref != null) {
                return ref;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private StockRef resolveWatchedStockRef(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String normalized = text.toLowerCase(Locale.ROOT);
            List<StockWatchItem> items = stockWatchItemMapper.selectEnabled();
            if (items == null) {
                return null;
            }
            for (StockWatchItem item : items) {
                if (item == null || item.getSymbol() == null) {
                    continue;
                }
                String symbol = item.getSymbol();
                String market = firstNonBlank(item.getMarket(), inferMarket(symbol));
                String name = firstNonBlank(item.getStockName(), "");
                if (normalized.contains(symbol.toLowerCase(Locale.ROOT))
                        || (!name.isBlank() && normalized.contains(name.toLowerCase(Locale.ROOT)))) {
                    return new StockRef(symbol, market);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private StockRef resolveKnownAshareRef(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.toLowerCase(Locale.ROOT);
        if (value.contains("宁德时代") || value.contains("catl")) return new StockRef("300750", "sz");
        if (value.contains("昊华科技")) return new StockRef("600378", "sh");
        if (value.contains("绿的谐波")) return new StockRef("688017", "sh");
        if (value.contains("迈瑞医疗")) return new StockRef("300760", "sz");
        if (value.contains("比亚迪")) return new StockRef("002594", "sz");
        if (value.contains("雷赛智能")) return new StockRef("002979", "sz");
        return null;
    }

    private StockRef parseStockRef(String rawSymbol, String rawMarket) {
        if (rawSymbol == null) {
            return null;
        }
        String value = rawSymbol.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (value.isEmpty()) {
            return null;
        }
        Matcher prefixed = Pattern.compile(".*?(sh|sz|bj)[._-]?(\\d{6}).*").matcher(value);
        if (prefixed.matches()) {
            return new StockRef(prefixed.group(2), prefixed.group(1));
        }
        Matcher suffixed = Pattern.compile(".*?(\\d{6})(?:[._-]?(sh|sz|bj))?.*").matcher(value);
        if (!suffixed.matches()) {
            return null;
        }
        String symbol = suffixed.group(1);
        String market = firstNonBlank(rawMarket, suffixed.group(2), inferMarket(symbol)).toLowerCase(Locale.ROOT);
        return new StockRef(symbol, market);
    }

    private String inferMarket(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "sz";
        }
        if (symbol.startsWith("6") || symbol.startsWith("9")) return "sh";
        if (symbol.startsWith("4") || symbol.startsWith("8")) return "bj";
        return "sz";
    }

    private List<String> normalizeEastmoneyFields(List<String> rawFields, String intent) {
        if (rawFields == null || rawFields.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> fields = new LinkedHashSet<>();
        for (String raw : rawFields) {
            String field = raw == null ? "" : raw.trim();
            if (field.isBlank()) {
                continue;
            }
            String normalized = field.toLowerCase(Locale.ROOT)
                    .replace("-", "")
                    .replace("_", "");
            if ("all".equals(normalized) || "full".equals(normalized)) {
                fields.addAll(defaultEastmoneyFields(intent));
                continue;
            }
            String canonical = canonicalEastmoneyField(normalized);
            if (canonical != null) {
                fields.add(canonical);
            }
        }
        return new ArrayList<>(fields);
    }

    private String canonicalEastmoneyField(String normalized) {
        switch (normalized) {
            case "quote":
            case "realtime":
            case "price":
            case "fundflow":
                return "quote";
            case "stockdetail":
            case "detail":
            case "marketcap":
                return "stockDetail";
            case "anchorfacts":
            case "anchor":
                return "anchorFacts";
            case "technicalindicators":
            case "technical":
            case "indicator":
            case "indicators":
                return "technicalIndicators";
            case "dailykline":
            case "kline":
            case "daily":
            case "bars":
                return "dailyKline";
            case "minutetrends5d":
            case "minutetrends":
            case "intraday":
                return "minuteTrends5d";
            case "intradayfundflow":
            case "flow":
            case "capitalflow":
                return "intradayFundFlow";
            case "valuation":
            case "pe":
            case "pb":
                return "valuation";
            case "financials":
            case "financial":
            case "fundamental":
                return "financials";
            case "announcements":
            case "announcement":
            case "notice":
                return "announcements";
            case "latestnews":
            case "news":
                return "latestNews";
            case "shareholdercounts":
            case "shareholder":
            case "holders":
                return "shareholderCounts";
            case "marketbenchmark":
            case "benchmark":
                return "marketBenchmark";
            default:
                return null;
        }
    }

    private List<String> defaultEastmoneyFields(String intent) {
        List<String> fields = new ArrayList<>();
        fields.add("quote");
        fields.add("stockDetail");
        fields.add("anchorFacts");
        fields.add("technicalIndicators");
        fields.add("dailyKline");
        if ("forecast_outlook".equalsIgnoreCase(intent)) {
            fields.addAll(Arrays.asList("valuation", "financials", "latestNews", "shareholderCounts"));
        } else if ("latest_news".equalsIgnoreCase(intent)) {
            fields.addAll(Arrays.asList("latestNews", "announcements"));
        } else if ("realtime_price".equalsIgnoreCase(intent)) {
            fields.addAll(Arrays.asList("minuteTrends5d", "intradayFundFlow", "marketBenchmark"));
        }
        return fields;
    }

    private Map<String, Object> compactEastmoneyPayload(Map<String, Object> raw, List<String> fields) {
        return compactEastmoneyPayload(raw, fields, null);
    }

    private Map<String, Object> compactEastmoneyPayload(Map<String, Object> raw, List<String> fields,
                                                        ReviewedNeed need) {
        Map<String, Object> compact = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty() || fields == null || fields.isEmpty()) {
            return compact;
        }
        copyIfPresent(raw, compact, "asOf");
        copyIfPresent(raw, compact, "symbol");
        copyIfPresent(raw, compact, "market");
        copyIfPresent(raw, compact, "periodType");
        if (fields.contains("technicalIndicators") && !fields.contains("dailyKline") && need != null
                && reviewedRequiresClosedObservation(need.question)) {
            compact.put("dailyKline", tailObjects(toObjectList(raw.get("dailyKline")), 1));
        }
        for (String field : fields) {
            if (!raw.containsKey(field)) {
                continue;
            }
            compact.put(field, compactEastmoneyValue(field, raw.get(field), need));
        }
        if (raw.containsKey("dataQuality")) {
            compact.put("dataQuality", raw.get("dataQuality"));
        }
        Map<String, Object> packing = new LinkedHashMap<>();
        packing.put("mode", "compact_for_meeting_search_context");
        packing.put("requestedFields", fields);
        compact.put("dataPacking", packing);
        return compact;
    }

    private Object compactEastmoneyValue(String field, Object value, ReviewedNeed need) {
        List<Object> values = toObjectList(value);
        if (values.isEmpty()) {
            return value;
        }
        if ("dailyKline".equals(field)) {
            int bars = 40;
            if (need != null) {
                Matcher requested = REVIEWED_TRADING_DAYS_PATTERN.matcher(need.question);
                if (requested.find()) bars = Math.max(bars, Integer.parseInt(requested.group(1)));
            }
            return tailObjects(values, bars);
        }
        if ("minuteTrends5d".equals(field)) return tailObjects(values, 25);
        if ("intradayFundFlow".equals(field)) return tailObjects(values, 25);
        if ("financials".equals(field)) {
            List<Object> selected = headObjects(values, 4);
            if (need != null) {
                String requiredPeriod = reviewedRequestedFinancialPeriod(need.question);
                if (requiredPeriod != null) {
                    for (Object row : values) {
                        if (row instanceof Map && String.valueOf(((Map<?, ?>) row).get("reportDate"))
                                .startsWith(requiredPeriod) && !selected.contains(row)) {
                            selected.add(0, row);
                            if (selected.size() > 4) selected.remove(selected.size() - 1);
                            break;
                        }
                    }
                }
            }
            return selected;
        }
        if ("announcements".equals(field)) return headObjects(values, 6);
        if ("latestNews".equals(field)) return headObjects(values, 6);
        if ("shareholderCounts".equals(field)) return headObjects(values, 4);
        return values;
    }

    private String serializeStructuredSearchSnippet(Map<String, Object> compact, int limit) {
        Map<String, Object> projected = new LinkedHashMap<>(compact);
        String serialized = JSON.toJSONString(projected);
        if (limit <= 0 || serialized.length() <= limit) {
            return serialized;
        }

        // Keep a parseable projection. Cutting serialized JSON by character count
        // makes every downstream fact extractor and fallback summarizer lose it.
        while (serialized.length() > limit) {
            String largestListField = null;
            int largestListSize = 0;
            for (Map.Entry<String, Object> entry : projected.entrySet()) {
                List<Object> values = toObjectList(entry.getValue());
                if (values.size() > 1 && JSON.toJSONString(values).length() > largestListSize) {
                    largestListField = entry.getKey();
                    largestListSize = JSON.toJSONString(values).length();
                }
            }
            if (largestListField == null) {
                break;
            }
            List<Object> reduced = toObjectList(projected.get(largestListField));
            if ("dailyKline".equals(largestListField)
                    || "minuteTrends5d".equals(largestListField)
                    || "intradayFundFlow".equals(largestListField)) {
                reduced.remove(0); // retain the newest market observations
            } else {
                reduced.remove(reduced.size() - 1); // retain the newest reports/news first
            }
            projected.put(largestListField, reduced);
            serialized = JSON.toJSONString(projected);
        }

        while (serialized.length() > limit) {
            String removable = null;
            int largestSize = 0;
            for (Map.Entry<String, Object> entry : projected.entrySet()) {
                String key = entry.getKey();
                if ("symbol".equals(key) || "market".equals(key) || "asOf".equals(key)
                        || "periodType".equals(key) || "quote".equals(key)) {
                    continue;
                }
                int size = JSON.toJSONString(entry.getValue()).length();
                if (size > largestSize) {
                    removable = key;
                    largestSize = size;
                }
            }
            if (removable == null) {
                break;
            }
            projected.remove(removable);
            serialized = JSON.toJSONString(projected);
        }
        if (serialized.length() <= limit) {
            return serialized;
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        copyIfPresent(projected, identity, "symbol");
        copyIfPresent(projected, identity, "market");
        copyIfPresent(projected, identity, "quote");
        serialized = JSON.toJSONString(identity);
        return serialized.length() <= limit ? serialized : "{}";
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private List<Object> toObjectList(Object value) {
        if (value instanceof List) {
            return new ArrayList<>((List<?>) value);
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                values.add(array.get(i));
            }
            return values;
        }
        return Collections.emptyList();
    }

    private List<Object> headObjects(List<Object> values, int count) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(values.subList(0, Math.min(count, values.size())));
    }

    private List<Object> tailObjects(List<Object> values, int count) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(values.subList(Math.max(0, values.size() - count), values.size()));
    }

    private String limitText(String text, int limit) {
        if (text == null || limit <= 0 || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "…";
    }

    private WebSearchService.ResearchDigest buildResearchDigest(Long meetingId,
                                                                SearchPlan searchPlan,
                                                                List<String> searchQueries,
                                                                List<WebSearchService.SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return null;
        }
        if (isStockGroundedSearch(searchPlan, searchResults)) {
            // 股票场景里原始结构化数据比“摘要代理”更可靠。摘要代理一旦串公司，
            // 下游主模型会把错误摘要当事实扩写，所以这里直接跳过。
            return null;
        }
        com.aimeeting.room.entity.AiMeetingRoom room = roomMapper.selectById(meetingId);
        String topic = room != null ? room.getTitle() : null;
        return webSearch.buildResearchDigest(
                topic,
                searchPlan != null ? searchPlan.intent : null,
                searchPlan != null ? searchPlan.targetYear : null,
                searchQueries,
                searchResults
        );
    }

    private String buildSearchContext(List<WebSearchService.SearchResult> searchResults,
                                      WebSearchService.ResearchDigest researchDigest,
                                      SearchPlan searchPlan) {
        String rawContext = searchResults == null || searchResults.isEmpty()
                ? null
                : webSearch.formatResultsForPrompt(searchResults);
        boolean hasRawContext = rawContext != null && !rawContext.isBlank();
        String digestContext = null;
        if (researchDigest != null) {
            digestContext = webSearch.formatResearchDigestForPrompt(researchDigest);
        }
        boolean hasDigestContext = digestContext != null && !digestContext.isBlank();
        if (!hasRawContext && !hasDigestContext) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        if (isStockGroundedSearch(searchPlan, searchResults)) {
            sb.append("【股票事实来源边界】\n")
                    .append("- 下方 Provider 原始结果和东方财富结构化数据是唯一可直接引用的事实来源。\n")
                    .append("- 不得引用未在下方出现的股票代码、公司、财务数字、估值、Beta、IC值、因子排名、Wind/万得一致预期或自有量化池结论。\n")
                    .append("- 如果某个网页结果明显属于其他公司或其他股票代码，必须判为无效，不能基于它继续推演。\n")
                    .append("- 缺少的数据请写“本轮证据未提供/无法核验”，不要补数。\n\n");
        } else {
            sb.append("【事实来源边界】优先使用下方 Provider 原始结果；没有在原始结果中出现的最新数据，不得自行补充。\n\n");
        }

        if (hasRawContext) {
            sb.append("【Provider原始结果】\n").append(rawContext.trim()).append("\n");
        }
        if (hasDigestContext) {
            sb.append("\n【研究摘要代理线索（不可直接当作事实）】\n")
                    .append("下面内容只能帮助理解检索结果，不得直接引用其中未能在 Provider 原始结果定位到的数字或结论。\n")
                    .append(digestContext.trim()).append("\n");
        }
        return sb.toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean shouldKeepAiEvidence(ChatResult chatResult,
                                         List<WebSearchService.SearchResult> realResults,
                                         SearchPlan searchPlan) {
        if (chatResult == null) {
            return true;
        }
        if (realResults != null && !realResults.isEmpty()) {
            return false;
        }
        boolean freshnessSensitive = searchPlan != null
                && ("realtime_price".equalsIgnoreCase(searchPlan.intent)
                || "latest_news".equalsIgnoreCase(searchPlan.intent)
                || "forecast_outlook".equalsIgnoreCase(searchPlan.intent));
        if (freshnessSensitive && "built_in_search".equalsIgnoreCase(chatResult.searchMode)) {
            return false;
        }
        return true;
    }

    // ── evidence 摘要（用于注入其他成员的上下文）──

    private String summarizeEvidence(String evidenceJson) {
        try {
            JSONObject ev = JSON.parseObject(evidenceJson);
            JSONArray e = ev.getJSONArray("e");
            if (e != null && !e.isEmpty()) {
                JSONObject first = e.getJSONObject(0);
                return first.getString("s") + ":" + first.getString("p");
            }
            JSONArray results = ev.getJSONArray("results");
            if (results != null && !results.isEmpty()) {
                JSONObject first = results.getJSONObject(0);
                String snippet = first.getString("snippet");
                return first.getString("source") + ":" + (snippet != null && snippet.length() > 40 ? snippet.substring(0, 40) + "…" : snippet);
            }
        } catch (Exception ignored) {}
        return "（见论据卡）";
    }

    private List<WebSearchService.SearchResult> prioritizeSearchResults(List<WebSearchService.SearchResult> rawResults,
                                                                        SearchPlan searchPlan,
                                                                        Long meetingId) {
        if (rawResults == null || rawResults.isEmpty()) {
            return rawResults;
        }
        SearchPlan effectivePlan = searchPlan != null ? searchPlan : defaultSearchPlan(meetingId);
        StockRef targetRef = resolveEastmoneyStockRef(meetingId, effectivePlan);
        List<WebSearchService.SearchResult> sorted = rawResults.stream()
                .filter(result -> !isClearlyWrongStockResult(result, targetRef))
                .collect(Collectors.toCollection(ArrayList::new));
        if (sorted.isEmpty()) {
            sorted = new ArrayList<>(rawResults);
        }
        sorted.sort(Comparator.comparingInt((WebSearchService.SearchResult r) -> scoreSearchResult(r, effectivePlan)).reversed());
        List<WebSearchService.SearchResult> deduped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WebSearchService.SearchResult result : sorted) {
            if (!seen.add(buildSearchResultKey(result))) {
                continue;
            }
            deduped.add(result);
            if (deduped.size() >= 4) {
                break;
            }
        }
        return deduped;
    }

    private boolean isClearlyWrongStockResult(WebSearchService.SearchResult result, StockRef targetRef) {
        if (result == null || targetRef == null || targetRef.symbol == null || targetRef.symbol.isBlank()) {
            return false;
        }
        String text = normalizeGroundingText(firstNonBlank(result.source, "") + " "
                + firstNonBlank(result.snippet, "") + " "
                + firstNonBlank(result.url, ""));
        Set<String> codes = extractStockCodesWithMarket(text);
        if (codes.isEmpty()) {
            return false;
        }
        return !codes.contains(targetRef.symbol);
    }

    private String buildSearchResultKey(WebSearchService.SearchResult result) {
        if (result == null) {
            return "null";
        }
        if (result.provider != null && result.provider.startsWith("n8n-deepseek-")) {
            // One page may support multiple claims with different review outcomes.
            // Collapsing these by URL would hide an UNKNOWN claim behind a PASS claim.
            return result.provider + "|" + firstNonBlank(result.rawHash,
                    result.source + "|" + result.snippet + "|" + result.url);
        }
        if ("STRUCTURED_DATA".equals(result.sourceType)) {
            // Different requested fields/windows share a quote URL. Preserve each
            // supplied projection while still collapsing an identical projection.
            return result.sourceType + "|" + result.provider + "|" + result.query + "|"
                    + firstNonBlank(result.rawHash, result.snippet) + "|" + result.url;
        }
        String url = result.url != null ? result.url.trim().toLowerCase() : "";
        if (!url.isBlank()) {
            return url;
        }
        String source = result.source != null ? result.source.trim().toLowerCase() : "";
        String snippet = result.snippet != null ? result.snippet.trim().toLowerCase() : "";
        if (snippet.length() > 120) {
            snippet = snippet.substring(0, 120);
        }
        return source + "|" + snippet;
    }

    private JSONObject buildValidationSummary(List<WebSearchService.SearchResult> results, SearchPlan searchPlan) {
        JSONObject validation = new JSONObject();
        List<WebSearchService.SearchResult> effectiveResults = dedupeSearchResults(results);
        Set<String> domains = new LinkedHashSet<>();
        int targetYearHits = 0;
        int freshHits = 0;
        int highQualityHits = 0;
        int weakSourceHits = 0;
        int topicRelevantHits = 0;
        for (WebSearchService.SearchResult result : effectiveResults) {
            String domain = extractDomain(result.url);
            if (domain != null) {
                domains.add(domain);
            }
            if (isHighQualitySource(result)) {
                highQualityHits++;
            }
            if (isWeakSource(result)) {
                weakSourceHits++;
            }
            Integer year = extractYear(result);
            if (searchPlan != null && searchPlan.targetYear != null && year != null && year.equals(searchPlan.targetYear)) {
                targetYearHits++;
            }
            if (isFreshEnough(result, searchPlan)) {
                freshHits++;
            }
            if (isTopicRelevantResult(result, searchPlan)) {
                topicRelevantHits++;
            }
        }
        JSONObject factValidation = buildFactValidation(effectiveResults);
        int verifiedFactCount = factValidation.getIntValue("verifiedFactCount");
        int conflictingFactCount = factValidation.getIntValue("conflictingFactCount");
        int singleSourceFactCount = factValidation.getIntValue("singleSourceFactCount");
        JSONArray targetMismatchViolations = findTargetMismatchViolations(effectiveResults, searchPlan);
        boolean targetMismatch = !targetMismatchViolations.isEmpty();
        boolean requiresTopicRelevance = requiresTopicRelevance(searchPlan);
        boolean topicRelevant = !requiresTopicRelevance || topicRelevantHits > 0;
        boolean topicRelevanceEnough = !requiresTopicRelevance || topicRelevantHits >= Math.min(2, Math.max(1, effectiveResults.size()));
        boolean crossVerified = !targetMismatch && topicRelevanceEnough && ((verifiedFactCount > 0 && conflictingFactCount == 0)
                || (domains.size() >= 2 && freshHits >= 2 && highQualityHits >= 1));
        int preferredSourceHits = countPreferredSourceHits(effectiveResults, searchPlan);
        validation.put("crossVerified", crossVerified);
        validation.put("targetMismatch", targetMismatch);
        if (targetMismatch) {
            validation.put("targetMismatchViolations", targetMismatchViolations);
        }
        validation.put("distinctSources", domains.size());
        validation.put("freshResultCount", freshHits);
        validation.put("targetYearHitCount", targetYearHits);
        validation.put("preferredSourceHitCount", preferredSourceHits);
        validation.put("highQualitySourceCount", highQualityHits);
        validation.put("weakSourceCount", weakSourceHits);
        validation.put("topicRelevanceRequired", requiresTopicRelevance);
        validation.put("topicRelevant", topicRelevant);
        validation.put("topicRelevantResultCount", topicRelevantHits);
        validation.put("factValidation", factValidation);
        validation.put("note", targetMismatch
                ? "搜索结果中存在明确异股证据，当前不能视为目标股票的交叉验证结果。"
                : !topicRelevant
                ? "搜索结果来源质量可能较高，但与本轮问题主题不匹配，不能视为有效交叉验证。"
                : buildValidationNote(crossVerified, preferredSourceHits, factValidation, weakSourceHits));
        return validation;
    }

    private boolean requiresTopicRelevance(SearchPlan searchPlan) {
        return searchPlan != null && "technical_indicator_research".equalsIgnoreCase(searchPlan.intent);
    }

    private boolean isTopicRelevantResult(WebSearchService.SearchResult result, SearchPlan searchPlan) {
        if (!requiresTopicRelevance(searchPlan)) {
            return true;
        }
        if (result == null) {
            return false;
        }
        String text = ((result.source != null ? result.source : "") + " "
                + (result.snippet != null ? result.snippet : "") + " "
                + (result.url != null ? result.url : "")).toLowerCase(Locale.ROOT);
        return containsAny(text,
                "技术指标", "技术面", "technicalindicators", "indicator", "macd", "rsi", "kdj",
                "均线", "ma20", "ma5", "量价", "背离", "假突破", "趋势", "支撑", "阻力",
                "回测", "胜率", "有效性", "失效", "震荡");
    }

    private JSONArray findTargetMismatchViolations(List<WebSearchService.SearchResult> results, SearchPlan searchPlan) {
        JSONArray violations = new JSONArray();
        if (results == null || results.isEmpty()
                || searchPlan == null
                || searchPlan.eastmoneySymbol == null
                || searchPlan.eastmoneySymbol.isBlank()) {
            return violations;
        }
        String target = searchPlan.eastmoneySymbol.trim();
        Set<String> seen = new LinkedHashSet<>();
        for (WebSearchService.SearchResult result : results) {
            if (result == null) {
                continue;
            }
            String text = normalizeGroundingText(firstNonBlank(result.source, "") + " "
                    + firstNonBlank(result.snippet, "") + " "
                    + firstNonBlank(result.url, ""));
            Set<String> codes = extractStockCodesWithMarket(text);
            if (codes.isEmpty() || codes.contains(target)) {
                continue;
            }
            String key = firstNonBlank(result.url, result.source, result.query, codes.toString());
            if (!seen.add(key)) {
                continue;
            }
            JSONObject item = new JSONObject(true);
            item.put("target", target);
            JSONArray codeArr = new JSONArray();
            codes.forEach(codeArr::add);
            item.put("codes", codeArr);
            item.put("source", result.source);
            item.put("url", result.url);
            item.put("sourceType", result.sourceType);
            item.put("query", result.query);
            item.put("reason", "result_points_to_different_stock");
            violations.add(item);
            if (violations.size() >= 6) {
                break;
            }
        }
        return violations;
    }

    private JSONObject buildConflictValidation(Long meetingId,
                                               Long memberId,
                                               int roundNumber,
                                               List<WebSearchService.SearchResult> currentResults) {
        if (meetingId == null || memberId == null || currentResults == null || currentResults.isEmpty()) {
            return null;
        }
        List<NumericFact> currentFacts = extractFactsFromSearchResults(currentResults, null, null);
        if (currentFacts.isEmpty()) {
            return null;
        }

        List<AiMeetingOpinion> priorOpinions = opinionMapper.selectByMeetingId(meetingId);
        if (priorOpinions == null || priorOpinions.isEmpty()) {
            return null;
        }
        Map<Long, String> memberNameMap = new HashMap<>();
        for (AiMeetingMember candidate : memberMapper.selectByMeetingId(meetingId)) {
            if (candidate != null) {
                memberNameMap.put(candidate.getId(), candidate.getMemberName());
            }
        }

        Map<String, List<NumericFact>> previousFactMap = new HashMap<>();
        for (AiMeetingOpinion opinion : priorOpinions) {
            if (opinion == null || opinion.getMemberId() == null || opinion.getMemberId().equals(memberId)) {
                continue;
            }
            if (opinion.getRoundNumber() != null && opinion.getRoundNumber() > roundNumber) {
                continue;
            }
            String speakerName = memberNameMap.getOrDefault(opinion.getMemberId(), "其他议员");
            for (NumericFact fact : extractFactsFromEvidence(opinion.getEvidence(), speakerName)) {
                previousFactMap.computeIfAbsent(fact.labelKey, key -> new ArrayList<>()).add(fact);
            }
        }

        JSONArray conflicts = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (NumericFact current : currentFacts) {
            List<NumericFact> previousList = previousFactMap.get(current.labelKey);
            if (previousList == null || previousList.isEmpty()) {
                continue;
            }
            for (NumericFact previous : previousList) {
                if (!isConflictingFact(current, previous)) {
                    continue;
                }
                String dedupeKey = current.labelKey + "|" + previous.owner + "|" + previous.rawValue + "|" + current.rawValue;
                if (!seen.add(dedupeKey)) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("label", current.label);
                item.put("currentValue", current.rawValue);
                item.put("currentSource", current.source);
                item.put("previousValue", previous.rawValue);
                item.put("previousSource", previous.source);
                item.put("previousSpeaker", previous.owner);
                conflicts.add(item);
                if (conflicts.size() >= 4) {
                    break;
                }
            }
            if (conflicts.size() >= 4) {
                break;
            }
        }
        if (conflicts.isEmpty()) {
            return null;
        }

        JSONObject conflictValidation = new JSONObject();
        conflictValidation.put("hasConflict", true);
        conflictValidation.put("conflictCount", conflicts.size());
        conflictValidation.put("conflicts", conflicts);
        JSONObject first = conflicts.getJSONObject(0);
        conflictValidation.put("note", String.format("本轮独立搜索与前序议员主张存在数字冲突：%s 当前为 %s，而 %s 曾引用 %s。后续发言应明确说明冲突并以本轮核验结果为准。",
                first.getString("label"),
                first.getString("currentValue"),
                first.getString("previousSpeaker"),
                first.getString("previousValue")));
        return conflictValidation;
    }

    private String buildConflictPrompt(JSONObject conflictValidation) {
        if (conflictValidation == null || !conflictValidation.getBooleanValue("hasConflict")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【与前序主张的自动冲突提示】\n");
        sb.append(firstNonBlank(conflictValidation.getString("note"), "本轮独立搜索与前序主张存在冲突，请明确指出。")).append("\n");
        JSONArray conflicts = conflictValidation.getJSONArray("conflicts");
        if (conflicts != null) {
            for (int i = 0; i < Math.min(3, conflicts.size()); i++) {
                JSONObject item = conflicts.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                sb.append("- ").append(item.getString("label"))
                        .append("：你本轮搜索=").append(item.getString("currentValue"))
                        .append("；前序议员").append(item.getString("previousSpeaker"))
                        .append(" 曾引用=").append(item.getString("previousValue"))
                        .append("\n");
            }
        }
        sb.append("不得直接继承前序数字；若决定采用某一侧，必须说明依据来源。\n");
        return sb.toString().trim();
    }

    private JSONObject buildMissingLiveValidation(SearchPlan searchPlan, ChatResult chatResult) {
        JSONObject validation = new JSONObject();
        if (chatResult == null) {
            return validation;
        }
        boolean freshnessSensitive = searchPlan != null
                && ("realtime_price".equalsIgnoreCase(searchPlan.intent)
                || "latest_news".equalsIgnoreCase(searchPlan.intent)
                || "forecast_outlook".equalsIgnoreCase(searchPlan.intent));
        if (!freshnessSensitive) {
            return validation;
        }
        validation.put("crossVerified", false);
        validation.put("distinctSources", 0);
        validation.put("freshResultCount", 0);
        validation.put("targetYearHitCount", 0);
        String note = "本轮未获得可核验的联网结果，未完成最新数据核验与交叉验证。页面中的判断仅可视为模型趋势分析，不应当作实时数据引用。";
        if (!webSearch.isEnabled() && "built_in_search".equalsIgnoreCase(chatResult.searchMode)) {
            note += " 当前未配置外部搜索API兜底，因此内置联网搜索失败后无法继续抓取最新网页数据。";
        }
        validation.put("note", note);
        return validation;
    }

    private int scoreSearchResult(WebSearchService.SearchResult result, SearchPlan searchPlan) {
        int score = 0;
        String domain = extractDomain(result.url);
        String text = ((result.source != null ? result.source : "") + " " +
                (result.snippet != null ? result.snippet : "") + " " +
                (result.url != null ? result.url : "")).toLowerCase();
        if (result.source != null && result.source.startsWith("东方财富结构化数据")) {
            score += 30;
        }
        if (containsAny(text, "tavily 智能摘要")) {
            score -= 30;
        }
        if (searchPlan != null) {
            if ("realtime_price".equalsIgnoreCase(searchPlan.intent)) {
                if (containsAny(text, "price", "spot", "future", "comex", "lbma", "现货", "期货", "报价")) score += 4;
            } else if ("forecast_outlook".equalsIgnoreCase(searchPlan.intent)) {
                if (containsAny(text, "forecast", "outlook", "预测", "展望", "target")) score += 4;
            } else if ("latest_news".equalsIgnoreCase(searchPlan.intent)) {
                if (containsAny(text, "today", "latest", "breaking", "最新", "今日")) score += 3;
            }
            if (searchPlan.targetYear != null) {
                Integer year = extractYear(result);
                if (year != null && year.equals(searchPlan.targetYear)) score += 5;
                else if (year != null && year < searchPlan.targetYear - 1) score -= 3;
            }
            if (isFreshEnough(result, searchPlan)) score += 3;
            if (matchesPreferredSource(domain, searchPlan)) score += 4;
        }
        score += sourceQualityScore(result);
        if (hasExtractableNumericFacts(result)) score += 2;
        return score;
    }

    private JSONObject buildFactValidation(List<WebSearchService.SearchResult> results) {
        JSONObject factValidation = new JSONObject();
        JSONArray checks = new JSONArray();
        Map<String, List<NumericFact>> factMap = new HashMap<>();
        for (NumericFact fact : extractFactsFromSearchResults(results, "current_search", null)) {
            factMap.computeIfAbsent(fact.labelKey, key -> new ArrayList<>()).add(fact);
        }

        int verified = 0;
        int conflicting = 0;
        int singleSource = 0;
        for (Map.Entry<String, List<NumericFact>> entry : factMap.entrySet()) {
            List<NumericFact> facts = entry.getValue();
            if (facts == null || facts.isEmpty()) {
                continue;
            }
            Map<String, Set<String>> valueToSources = new HashMap<>();
            String label = facts.get(0).label;
            for (NumericFact fact : facts) {
                valueToSources.computeIfAbsent(fact.rawValue, key -> new LinkedHashSet<>())
                        .add(firstNonBlank(fact.source, "unknown"));
            }
            JSONObject item = new JSONObject();
            item.put("label", label);
            item.put("valueCount", valueToSources.size());
            JSONArray values = new JSONArray();
            valueToSources.keySet().forEach(values::add);
            item.put("values", values);
            int maxSupport = valueToSources.values().stream().mapToInt(Set::size).max().orElse(0);
            item.put("maxSupport", maxSupport);
            if (valueToSources.size() > 1) {
                item.put("status", "conflict");
                conflicting++;
            } else if (maxSupport >= 2) {
                item.put("status", "verified");
                verified++;
            } else {
                item.put("status", "single_source");
                singleSource++;
            }
            checks.add(item);
            if (checks.size() >= 6) {
                break;
            }
        }
        factValidation.put("verifiedFactCount", verified);
        factValidation.put("conflictingFactCount", conflicting);
        factValidation.put("singleSourceFactCount", singleSource);
        factValidation.put("checks", checks);
        return factValidation;
    }

    private String buildValidationNote(boolean crossVerified,
                                       int preferredSourceHits,
                                       JSONObject factValidation,
                                       int weakSourceHits) {
        int verifiedFactCount = factValidation.getIntValue("verifiedFactCount");
        int conflictingFactCount = factValidation.getIntValue("conflictingFactCount");
        int singleSourceFactCount = factValidation.getIntValue("singleSourceFactCount");
        if (conflictingFactCount > 0) {
            return "搜索结果存在硬数字冲突，当前不能直接引用为确定事实，应先核对原始来源。";
        }
        if (verifiedFactCount > 0 && crossVerified) {
            return "已获得较高质量来源支持，且部分硬数字已完成多源核验。";
        }
        if (singleSourceFactCount > 0) {
            return "当前包含单源硬数字断言，尚未完成事实检验，引用时应保留谨慎措辞。";
        }
        if (preferredSourceHits > 0) {
            return weakSourceHits > 0
                    ? "已命中部分优先来源，但仍混有弱来源结果，且未完成充分交叉验证。"
                    : "已命中部分优先来源，但搜索结果尚未充分完成交叉验证，引用时应保留谨慎措辞。";
        }
        return "搜索结果尚未充分完成交叉验证与事实检验，引用时应保留谨慎措辞。";
    }

    private boolean hasExtractableNumericFacts(WebSearchService.SearchResult result) {
        if (result == null) {
            return false;
        }
        return !extractFactsFromStructuredResult(result, null, result.source).isEmpty()
                || !extractFactsFromText(result.snippet, null, result.source).isEmpty();
    }

    private int sourceQualityScore(WebSearchService.SearchResult result) {
        if (result == null) {
            return 0;
        }
        String domain = extractDomain(result.url);
        String sourceText = ((result.source != null ? result.source : "") + " " + (domain != null ? domain : "")).toLowerCase();
        if (domain != null && containsAny(domain, "gov.cn", "stats.gov.cn", "pbc.gov.cn", "mofcom.gov.cn", "cninfo.com.cn")) {
            return 6;
        }
        if (domain != null && containsAny(domain, "reuters.com", "bloomberg.com", "wsj.com", "gold.org", "cmegroup.com", "lbma.org.uk", "who.int", "fda.gov")) {
            return 5;
        }
        if (domain != null && containsAny(domain, "caifuhao.eastmoney.com", "guba.eastmoney.com")) {
            return -4;
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

    private boolean isHighQualitySource(WebSearchService.SearchResult result) {
        return sourceQualityScore(result) >= 4;
    }

    private boolean isWeakSource(WebSearchService.SearchResult result) {
        return sourceQualityScore(result) < 0;
    }

    private int countPreferredSourceHits(List<WebSearchService.SearchResult> results, SearchPlan searchPlan) {
        if (results == null || results.isEmpty() || searchPlan == null || searchPlan.sourceFocus == null || searchPlan.sourceFocus.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (WebSearchService.SearchResult result : results) {
            if (matchesPreferredSource(extractDomain(result.url), searchPlan)) {
                hits++;
            }
        }
        return hits;
    }

    private boolean matchesPreferredSource(String domain, SearchPlan searchPlan) {
        if (domain == null || searchPlan == null || searchPlan.sourceFocus == null || searchPlan.sourceFocus.isEmpty()) {
            return false;
        }
        for (String preferred : normalizeSourceFocus(searchPlan.sourceFocus)) {
            if (domain.equals(preferred) || domain.endsWith("." + preferred)) {
                return true;
            }
        }
        return false;
    }

    private List<NumericFact> extractFactsFromSearchResults(List<WebSearchService.SearchResult> results,
                                                            String owner,
                                                            String fallbackSource) {
        List<NumericFact> facts = new ArrayList<>();
        if (results == null) {
            return facts;
        }
        for (WebSearchService.SearchResult result : results) {
            if (result == null) {
                continue;
            }
            String source = firstNonBlank(result.source, fallbackSource);
            List<NumericFact> structuredFacts = extractFactsFromStructuredResult(result, owner, source);
            if (!structuredFacts.isEmpty()) {
                facts.addAll(structuredFacts);
                continue;
            }
            facts.addAll(extractFactsFromText(result.snippet, owner, source));
        }
        return facts;
    }

    private List<NumericFact> extractFactsFromEvidence(String evidenceJson, String owner) {
        List<NumericFact> facts = new ArrayList<>();
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return facts;
        }
        try {
            JSONObject evidence = JSON.parseObject(evidenceJson);
            JSONArray eArr = evidence.getJSONArray("e");
            if (eArr != null) {
                for (int i = 0; i < eArr.size(); i++) {
                    JSONObject item = eArr.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    facts.addAll(extractFactsFromText(item.getString("p"), owner, item.getString("s")));
                }
            }
            JSONArray results = evidence.getJSONArray("results");
            int factCountBeforeResults = facts.size();
            if (results != null) {
                for (int i = 0; i < results.size(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    JSONArray itemFacts = item.getJSONArray("facts");
                    if (itemFacts != null && !itemFacts.isEmpty()) {
                        facts.addAll(extractFactsFromFactJsonArray(itemFacts, owner, item.getString("source")));
                        continue;
                    }
                    facts.addAll(extractFactsFromText(item.getString("snippet"), owner, item.getString("source")));
                }
            }
            if (facts.size() == factCountBeforeResults) {
                JSONObject factCatalog = evidence.getJSONObject("factCatalog");
                JSONArray catalogFacts = factCatalog == null ? null : factCatalog.getJSONArray("facts");
                if (catalogFacts != null && !catalogFacts.isEmpty()) {
                    facts.addAll(extractFactsFromFactJsonArray(catalogFacts, owner, null));
                }
            }
        } catch (Exception ignored) {
        }
        return facts;
    }

    private List<NumericFact> extractFactsFromStructuredResult(WebSearchService.SearchResult result,
                                                               String owner,
                                                               String fallbackSource) {
        List<NumericFact> facts = new ArrayList<>();
        if (result == null
                || !"STRUCTURED_DATA".equalsIgnoreCase(result.sourceType)
                || result.snippet == null
                || result.snippet.isBlank()) {
            return facts;
        }
        try {
            JSONObject payload = JSON.parseObject(result.snippet);
            if (payload == null || payload.isEmpty()) {
                return facts;
            }
            String source = firstNonBlank(result.source, fallbackSource, "东方财富结构化数据");

            addStructuredFact(facts, payload, "quote.price", "最新价", "元", owner, source, result);
            addStructuredFact(facts, payload, "quote.changePercent", "涨跌幅", "%", owner, source, result);
            addStructuredFact(facts, payload, "quote.changeAmount", "涨跌额", "元", owner, source, result);
            addStructuredFact(facts, payload, "quote.volumeRatio", "量比", "倍", owner, source, result);
            addStructuredFact(facts, payload, "quote.turnover", "换手率", "%", owner, source, result);
            addStructuredFact(facts, payload, "quote.amount", "成交额", "元", owner, source, result);
            addStructuredFact(facts, payload, "quote.mainInflow", "主力净流入", "元", owner, source, result);
            addStructuredFact(facts, payload, "quote.mainInflowRatio", "主力净流入占比", "%", owner, source, result);

            addStructuredFact(facts, payload, "anchorFacts.priceYuan", "最新价", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.changePercent", "涨跌幅", "%", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.amountYuan", "成交额", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.turnoverPercent", "换手率", "%", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.volumeRatio", "量比", "倍", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.mainInflowYuan", "主力净流入", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.mainInflowRatioPercent", "主力净流入占比", "%", owner, source, result);

            addStructuredFact(facts, payload, "stockDetail.price", "盘口价", "元", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.open", "今开", "元", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.high", "最高价", "元", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.low", "最低价", "元", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.previousClose", "昨收", "元", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.dynamicPe", "动态PE", "倍", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.pb", "PB", "倍", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.turnover", "盘口换手率", "%", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.changePercent", "盘口涨跌幅", "%", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.amplitude", "振幅", "%", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.roe", "最新ROE", "%", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.marketCap", "总市值", "元", owner, source, result);
            addStructuredFact(facts, payload, "stockDetail.freeFloatMarketCap", "流通市值", "元", owner, source, result);

            addStructuredFact(facts, payload, "valuation.close", "估值收盘价", "元", owner, source, result);
            addStructuredFact(facts, payload, "valuation.peTtm", "PE_TTM", "倍", owner, source, result);
            addStructuredFact(facts, payload, "valuation.pbMrq", "PB_MRQ", "倍", owner, source, result);
            addStructuredFact(facts, payload, "valuation.peg", "PEG", "倍", owner, source, result);
            addStructuredFact(facts, payload, "valuation.psTtm", "PS_TTM", "倍", owner, source, result);
            addStructuredFact(facts, payload, "valuation.pcfTtm", "PCF_TTM", "倍", owner, source, result);
            addStructuredFact(facts, payload, "valuation.pePercentile1y", "PE一年分位", "%", owner, source, result);
            addStructuredFact(facts, payload, "valuation.pbPercentile1y", "PB一年分位", "%", owner, source, result);

            addStructuredFact(facts, payload, "financials[0].eps", "最新财报EPS", "元", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].revenue", "最新财报营收", "元", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].revenueYoyPct", "最新财报营收同比", "%", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].netProfit", "最新财报归母净利润", "元", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].netProfitYoyPct", "最新财报归母净利润同比", "%", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].netProfitExNonRecurring", "最新财报扣非净利润", "元", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].roePct", "最新财报ROE", "%", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].grossMarginPct", "最新财报毛利率", "%", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].netMarginPct", "最新财报净利率", "%", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].cashToRevenuePct", "经营现金流营收比", "%", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].debtRatioPct", "资产负债率", "%", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].receivableTurnover", "应收账款周转率", "次", owner, source, result);
            addStructuredFact(facts, payload, "financials[0].inventoryTurnover", "存货周转率", "次", owner, source, result);

            addStructuredFact(facts, payload, "shareholderCounts[0].holderNum", "最新股东户数", "户", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].preHolderNum", "上期股东户数", "户", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].holderNumChange", "股东户数变化", "户", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].holderNumRatioPct", "股东户数变化率", "%", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].intervalPriceChangePct", "股东户数区间涨跌幅", "%", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].avgHoldNum", "户均持股", "股", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].avgMarketCap", "户均持仓市值", "元", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].totalMarketCap", "股东户数期末总市值", "元", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].totalAShares", "A股总股本", "股", owner, source, result);
            addStructuredFact(facts, payload, "shareholderCounts[0].closePrice", "股东户数期末收盘价", "元", owner, source, result);

            addStructuredFact(facts, payload, "technicalIndicators.close", "技术收盘价", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.ma5", "MA5", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.ma10", "MA10", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.ma20", "MA20", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.ma60", "MA60", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.macdDif", "MACD_DIF", "", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.macdDea", "MACD_DEA", "", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.macdHistogram", "MACD柱", "", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.rsi14", "RSI14", "", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.atr14", "ATR14", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.bollMiddle", "BOLL中轨", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.bollUpper", "BOLL上轨", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.bollLower", "BOLL下轨", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.historicalVolatility20Pct", "20日历史波动率", "%", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.return5Pct", "5日收益率", "%", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.return20Pct", "20日收益率", "%", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.support20", "20日支撑位", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.resistance20", "20日压力位", "元", owner, source, result);
            addStructuredFact(facts, payload, "technicalIndicators.priceVsMa20Pct", "相对MA20偏离", "%", owner, source, result);

            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.close", "技术收盘价", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.ma5", "MA5", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.ma10", "MA10", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.ma20", "MA20", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.ma60", "MA60", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.macdDif", "MACD_DIF", "", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.macdDea", "MACD_DEA", "", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.macdHistogram", "MACD柱", "", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.rsi14", "RSI14", "", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.atr14", "ATR14", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.historicalVolatility20Pct", "20日历史波动率", "%", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.return5Pct", "5日收益率", "%", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.return20Pct", "20日收益率", "%", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.support20", "20日支撑位", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.resistance20", "20日压力位", "元", owner, source, result);
            addStructuredFact(facts, payload, "anchorFacts.technicalIndicators.priceVsMa20Pct", "相对MA20偏离", "%", owner, source, result);

            addStructuredFact(facts, payload, "marketBenchmark.return5Pct", "基准5日收益率", "%", owner, source, result);
            addStructuredFact(facts, payload, "marketBenchmark.return20Pct", "基准20日收益率", "%", owner, source, result);
            addStructuredFact(facts, payload, "marketBenchmark.relativeStrength20Pct", "20日相对强弱", "%", owner, source, result);
        } catch (Exception ignored) {
        }
        return facts;
    }

    private void addStructuredFact(List<NumericFact> facts,
                                   JSONObject payload,
                                   String path,
                                   String label,
                                   String unit,
                                   String owner,
                                   String source,
                                   WebSearchService.SearchResult result) {
        Double value = parseNumericValue(readJsonPath(payload, path));
        if (value == null || !Double.isFinite(value)) {
            return;
        }
        NumericFact fact = new NumericFact();
        fact.label = label;
        fact.labelKey = normalizeFactLabel(label);
        fact.value = value;
        fact.unit = unit == null ? "" : unit;
        fact.rawValue = formatFactNumber(value) + fact.unit;
        fact.owner = owner;
        fact.source = source;
        fact.path = path;
        fact.sourceType = result == null ? null : result.sourceType;
        fact.rawHash = result == null ? null : result.rawHash;
        facts.add(fact);
    }

    private Object readJsonPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || segment == null || segment.isBlank()) {
                return null;
            }
            String key = segment;
            Integer index = null;
            int bracket = segment.indexOf('[');
            if (bracket >= 0 && segment.endsWith("]")) {
                key = segment.substring(0, bracket);
                try {
                    index = Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            if (!key.isBlank()) {
                if (current instanceof JSONObject) {
                    current = ((JSONObject) current).get(key);
                } else if (current instanceof Map<?, ?>) {
                    current = ((Map<?, ?>) current).get(key);
                } else {
                    return null;
                }
            }
            if (index != null) {
                if (current instanceof JSONArray) {
                    JSONArray arr = (JSONArray) current;
                    current = index >= 0 && index < arr.size() ? arr.get(index) : null;
                } else if (current instanceof List<?>) {
                    List<?> list = (List<?>) current;
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } else {
                    return null;
                }
            }
        }
        return current;
    }

    private List<NumericFact> extractFactsFromFactJsonArray(JSONArray arr, String owner, String fallbackSource) {
        List<NumericFact> facts = new ArrayList<>();
        if (arr == null || arr.isEmpty()) {
            return facts;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String label = item.getString("label");
            if (label == null || label.isBlank()) {
                continue;
            }
            Double value = parseNumericValue(item.get("value"));
            if (value == null) {
                value = parseNumericValue(item.getString("rawValue"));
            }
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            NumericFact fact = new NumericFact();
            fact.label = label;
            fact.labelKey = normalizeFactLabel(label);
            fact.value = value;
            fact.unit = firstNonBlank(item.getString("unit"), "");
            fact.rawValue = firstNonBlank(item.getString("rawValue"), formatFactNumber(value) + fact.unit);
            fact.owner = firstNonBlank(owner, item.getString("owner"));
            fact.source = firstNonBlank(item.getString("source"), fallbackSource);
            fact.path = item.getString("path");
            fact.sourceType = item.getString("sourceType");
            fact.rawHash = item.getString("rawHash");
            facts.add(fact);
        }
        return facts;
    }

    private Double parseNumericValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "-".equals(text) || "--".equals(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        text = text.replace(",", "")
                .replace("%", "")
                .replace("元", "")
                .replace("倍", "")
                .replace("户", "")
                .replace("股", "")
                .replace("次", "")
                .replace("万元", "")
                .replace("亿", "");
        Matcher matcher = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatFactNumber(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        if (decimal.scale() < 0) {
            decimal = decimal.setScale(0);
        }
        return decimal.toPlainString();
    }

    private List<NumericFact> extractFactsFromText(String text, String owner, String source) {
        List<NumericFact> facts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return facts;
        }
        Matcher matcher = FACT_PAIR_PATTERN.matcher(text);
        while (matcher.find()) {
            String label = matcher.group(1) != null ? matcher.group(1).trim() : null;
            String numberPart = matcher.group(2);
            String unit = matcher.group(3) != null ? matcher.group(3).trim() : "";
            if (label == null || label.isBlank() || numberPart == null || shouldIgnoreFactLabel(label)) {
                continue;
            }
            try {
                NumericFact fact = new NumericFact();
                fact.label = label;
                fact.labelKey = normalizeFactLabel(label);
                fact.value = Double.parseDouble(numberPart);
                fact.unit = unit;
                fact.rawValue = numberPart + (unit.isBlank() ? "" : unit);
                fact.owner = owner;
                fact.source = source;
                facts.add(fact);
            } catch (NumberFormatException ignored) {
            }
        }
        return facts;
    }

    private boolean shouldIgnoreFactLabel(String label) {
        String normalized = normalizeFactLabel(label);
        return normalized.isBlank()
                || containsAny(normalized,
                "日期", "时间", "年份", "year", "round", "来源", "链接", "作者", "评分",
                "电话", "热线", "邮箱", "字号", "字体", "应用", "客户端", "浏览量");
    }

    private String normalizeFactLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.toLowerCase()
                .replaceAll("[\\s\\[\\]【】()（）:：;；,，。\\.]", "");
    }

    private boolean isConflictingFact(NumericFact current, NumericFact previous) {
        if (current == null || previous == null || current.labelKey == null || previous.labelKey == null) {
            return false;
        }
        if (!current.labelKey.equals(previous.labelKey)) {
            return false;
        }
        if (current.rawValue == null || previous.rawValue == null || current.rawValue.equals(previous.rawValue)) {
            return false;
        }
        boolean percentage = current.unit.contains("%") || previous.unit.contains("%");
        double tolerance = percentage
                ? 0.05d
                : Math.max(0.01d, Math.max(Math.abs(current.value), Math.abs(previous.value)) * 0.005d);
        return Math.abs(current.value - previous.value) > tolerance;
    }

    private boolean isFreshEnough(WebSearchService.SearchResult result, SearchPlan searchPlan) {
        if (searchPlan == null || searchPlan.freshnessDays == null) {
            return false;
        }
        Integer year = extractYear(result);
        if (year == null) {
            return false;
        }
        int currentYear = LocalDate.now().getYear();
        if (searchPlan.targetYear != null) {
            return year >= Math.max(currentYear - 1, searchPlan.targetYear - 1);
        }
        return year >= currentYear - 1;
    }

    private Integer extractYear(WebSearchService.SearchResult result) {
        String text = ((result.source != null ? result.source : "") + " " +
                (result.snippet != null ? result.snippet : "") + " " +
                (result.url != null ? result.url : ""));
        Matcher matcher = YEAR_PATTERN.matcher(text);
        Integer latest = null;
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (latest == null || year > latest) {
                latest = year;
            }
        }
        return latest;
    }

    private String extractDomain(String url) {
        if (url == null || url.isBlank()) return null;
        String normalized = url.replaceFirst("^https?://", "");
        int slash = normalized.indexOf('/');
        return slash >= 0 ? normalized.substring(0, slash).toLowerCase() : normalized.toLowerCase();
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null) return false;
        for (String token : tokens) {
            if (text.contains(token)) return true;
        }
        return false;
    }

    private SearchPlan defaultSearchPlan(Long meetingId) {
        SearchPlan plan = new SearchPlan();
        try {
            String title = resolveMeetingTitle(meetingId);
            Integer year = extractYear(title);
            if (year != null) {
                plan.targetYear = year;
            }
            String lower = title.toLowerCase();
            if (containsAny(lower, "预测", "展望", "outlook", "forecast")) {
                plan.intent = "forecast_outlook";
                plan.freshnessDays = 90;
            } else if (containsAny(lower, "金价", "油价", "股价", "汇率", "价格", "price", "gold")) {
                plan.intent = "realtime_price";
                plan.freshnessDays = 30;
            } else {
                plan.intent = "latest_news";
                plan.freshnessDays = 30;
            }
        } catch (Exception ignored) {
            plan.intent = "latest_news";
            plan.freshnessDays = 30;
        }
        return plan;
    }

    private String resolveMeetingTitle(Long meetingId) {
        try {
            if (meetingId != null) {
                AiMeetingRoom room = roomMapper.selectById(meetingId);
                if (room != null && room.getTitle() != null) {
                    return room.getTitle().trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String resolveMeetingSearchText(Long meetingId) {
        return resolveMeetingSearchText(meetingId, true);
    }

    private String resolveMeetingSearchText(Long meetingId, boolean includeProjectMetadata) {
        try {
            if (meetingId == null) {
                return "";
            }
            AiMeetingRoom room = roomMapper.selectById(meetingId);
            if (room == null) {
                return "";
            }
            return String.join(" ",
                    firstNonBlank(room.getTitle(), ""),
                    firstNonBlank(room.getDescription(), ""),
                    includeProjectMetadata ? firstNonBlank(room.getProjectKey(), "") : "",
                    includeProjectMetadata ? firstNonBlank(room.getProjectContext(), "") : ""
            ).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private Integer extractYear(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = YEAR_PATTERN.matcher(text);
        Integer latest = null;
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (latest == null || year > latest) {
                latest = year;
            }
        }
        return latest;
    }

    private static class SearchPlan {
        private String intent;
        private Integer freshnessDays;
        private Integer targetYear;
        private List<String> sourceFocus = new ArrayList<>();
        private List<String> queries = new ArrayList<>();
        private String eastmoneySymbol;
        private String eastmoneyMarket;
        private List<String> eastmoneyFields = new ArrayList<>();

        private boolean isEmpty() {
            return (intent == null || intent.isBlank())
                    && freshnessDays == null
                    && targetYear == null
                    && (sourceFocus == null || sourceFocus.isEmpty())
                    && (eastmoneySymbol == null || eastmoneySymbol.isBlank())
                    && (eastmoneyFields == null || eastmoneyFields.isEmpty());
        }
    }

    private static class StockRef {
        private final String symbol;
        private final String market;

        private StockRef(String symbol, String market) {
            this.symbol = symbol;
            this.market = market;
        }
    }

    private static class NumericFact {
        private String label;
        private String labelKey;
        private double value;
        private String unit = "";
        private String rawValue;
        private String owner;
        private String source;
        private String path;
        private String sourceType;
        private String rawHash;
    }

    // ── 解析 ==REPLY== 分隔符 ──

    private String[] parseEvidenceAndContent(String rawResponse) {
        if (rawResponse == null) {
            return new String[]{ null, "" };
        }
        String normalizedResponse = sanitizeModelResponse(rawResponse);
        int markerIdx = normalizedResponse.indexOf(REPLY_MARKER);
        if (markerIdx < 0) return new String[]{ null, normalizedResponse.trim() };

        String evidencePart = normalizedResponse.substring(0, markerIdx).trim();
        String contentPart  = normalizedResponse.substring(markerIdx + REPLY_MARKER.length()).trim();

        String evidence = null;
        int jsonStart = evidencePart.indexOf('{');
        int jsonEnd   = evidencePart.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            String json = evidencePart.substring(jsonStart, jsonEnd + 1);
            evidence = json.length() > 500 ? json.substring(0, 500) : json;
        }

        return new String[]{ evidence, contentPart.isEmpty() ? normalizedResponse.trim() : contentPart };
    }

    private String sanitizeModelResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "";
        }
        String sanitized = rawResponse
                .replaceAll("(?s)<｜｜DSML｜｜tool_calls>.*?</｜｜DSML｜｜tool_calls>", " ")
                .replaceAll("(?s)<\\|\\|DSML\\|\\|tool_calls>.*?</\\|\\|DSML\\|\\|tool_calls>", " ")
                .replaceAll("(?s)<｜｜DSML｜｜invoke[^>]*>.*?</｜｜DSML｜｜invoke>", " ")
                .replaceAll("(?s)<\\|\\|DSML\\|\\|invoke[^>]*>.*?</\\|\\|DSML\\|\\|invoke>", " ")
                .replaceAll("(?s)<｜｜DSML｜｜parameter[^>]*>.*?</｜｜DSML｜｜parameter>", " ")
                .replaceAll("(?s)<\\|\\|DSML\\|\\|parameter[^>]*>.*?</\\|\\|DSML\\|\\|parameter>", " ")
                .replaceAll("(?m)^\\s*<｜｜DSML｜｜.*$", " ")
                .replaceAll("(?m)^\\s*<\\|\\|DSML\\|\\|.*$", " ")
                .replaceAll("\\n{3,}", "\n\n");
        return sanitized.trim();
    }

    private AiMeetingOpinion findLatestTargetedQuestion(Long meetingId, Long memberId) {
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        if (opinions == null || opinions.isEmpty()) return null;
        AiMeetingOpinion latestUnanswered = null;
        List<AiMeetingOpinion> directedReplies = new ArrayList<>();
        for (AiMeetingOpinion opinion : opinions) {
            if (opinion.getOpinionType() != null
                    && opinion.getOpinionType() != OpinionTypeEnum.HUMAN.getCode()
                    && opinion.getOpinionType() != OpinionTypeEnum.HUMAN_DIRECTIVE.getCode()
                    && opinion.getOpinionType() != OpinionTypeEnum.HUMAN_TARGETED.getCode()
                    && memberId.equals(opinion.getMemberId())) {
                directedReplies.add(opinion);
            }
        }
        for (AiMeetingOpinion opinion : opinions) {
            if (opinion.getOpinionType() == null || opinion.getOpinionType() != OpinionTypeEnum.HUMAN_TARGETED.getCode()) {
                continue;
            }
            Long targetMemberId = parseTargetMemberId(opinion.getEvidence());
            if (!memberId.equals(targetMemberId)) {
                continue;
            }
            if (hasReplyForTargetedQuestion(opinion, directedReplies)) {
                continue;
            }
            latestUnanswered = opinion;
        }
        return latestUnanswered;
    }

    private Long findPendingTargetMemberId(Long meetingId) {
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        if (opinions == null || opinions.isEmpty()) return null;
        AiMeetingOpinion latestUnanswered = null;
        List<AiMeetingOpinion> directedReplies = new ArrayList<>();
        for (AiMeetingOpinion opinion : opinions) {
            if (opinion.getOpinionType() != null
                    && opinion.getOpinionType() != OpinionTypeEnum.HUMAN.getCode()
                    && opinion.getOpinionType() != OpinionTypeEnum.HUMAN_DIRECTIVE.getCode()
                    && opinion.getOpinionType() != OpinionTypeEnum.HUMAN_TARGETED.getCode()) {
                directedReplies.add(opinion);
            }
        }
        for (AiMeetingOpinion opinion : opinions) {
            if (opinion.getOpinionType() == null || opinion.getOpinionType() != OpinionTypeEnum.HUMAN_TARGETED.getCode()) {
                continue;
            }
            if (hasReplyForTargetedQuestion(opinion, directedReplies)) {
                continue;
            }
            latestUnanswered = opinion;
        }
        return latestUnanswered == null ? null : parseTargetMemberId(latestUnanswered.getEvidence());
    }

    private boolean hasReplyForTargetedQuestion(AiMeetingOpinion question, List<AiMeetingOpinion> directedReplies) {
        for (AiMeetingOpinion reply : directedReplies) {
            if (question.getId().equals(reply.getParentOpinionId())) {
                return true;
            }
        }
        return false;
    }

    private Long parseTargetMemberId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JSONObject meta = JSON.parseObject(raw);
            return meta.getLong("targetMemberId");
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── System prompts ──

    /** 决策阶段：简短 system prompt，专注于判断 */
    private String systemPromptForDecision(AiMeetingMember member) {
        return String.format(
                "你是「%s」，背景：%s\n你需要判断是否要查阅最新网络资讯来支持你的观点。前文中其他议员提到的数字、事实、结论都只能视为待核验主张，不能直接继承；你必须独立决定本轮需要搜索的内容。",
                member.getMemberName(), member.getPersona());
    }

    /** 发言阶段：完整 system prompt */
    private String buildSystemPrompt(AiMeetingMember member) {
        return String.format(
                "你是「%s」，正在参与一场多方深度讨论。你的立场/背景：%s\n\n" +
                "发言要求：\n" +
                "1. 【必须引用真实信息】优先引用上方【最新实时资讯】中的内容（如果有）；如无实时资讯，只能引用你能明确标注时间边界的稳定背景信息\n" +
                "2. 只有当上方证据明确出现时，才可以引用具体数字、百分比、机构名称、事件名称；没有证据时必须写“本轮证据未提供/无法核验”，不得为了显得专业而补数\n" +
                "3. 其他成员的发言和论据只能视为待核验主张，不能直接当作事实复述；若你本轮搜索结果与前文冲突，必须明确指出冲突并说明你采用哪一侧及原因\n" +
                "4. 对其他成员的具体论据发起有针对性的挑战或补充，不得泛泛而谈\n" +
                "5. 【最高优先级】如果主持人发布了指令或矫正，必须以此为核心重新定向你的整个发言方向\n" +
                "6. 回复格式：先输出JSON格式的参考依据，再用==REPLY==分隔，最后写发言正文",
                member.getMemberName(), member.getPersona());
    }
}
