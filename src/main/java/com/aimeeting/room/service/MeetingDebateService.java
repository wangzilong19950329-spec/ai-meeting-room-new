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
import com.aimeeting.room.entity.StockWatchItem;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import java.util.Set;
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

    private static final String REPLY_MARKER = "==REPLY==";
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");
    private static final Pattern FACT_PAIR_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9_ /()%.,+\\-]{0,24})[：:]\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*([%A-Za-z\\u4e00-\\u9fa5]{0,8})");
    private static final Pattern STOCK_CODE_WITH_MARKET_PATTERN = Pattern.compile("(?i)(?:\\b(?:sh|sz|bj)[._-]?(\\d{6})\\b|\\b(\\d{6})[._-]?(?:sh|sz|bj)\\b)");
    private static final Pattern GROUNDED_NUMERIC_CLAIM_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?\\s*(?:%|亿|万元|元|倍|bp|bps)");
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
        AiMeetingMember member = memberMapper.selectById(memberId);
        if (member == null || !meetingId.equals(member.getMeetingId())) {
            throw new IllegalArgumentException("指定议员不存在或不属于当前会议: " + memberId);
        }
        return generateOpinionWithRetry(meetingId, member, roundNumber);
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
        if ((chatResult == null || chatResult.content == null || chatResult.content.isBlank()) && !searchResults.isEmpty()) {
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
            if (conflictRetry != null && conflictRetry.content != null && !conflictRetry.content.isBlank()) {
                chatResult = conflictRetry;
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
                        }
                    }
                }
            }
        }

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
        parts.add(label + " " + formatFactNumber(value) + firstNonBlank(unit, ""));
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
        String content = synthesizeExternalSearchFallbackOpinion(member, searchPlan, searchResults, current);
        return new ChatResult(
                content,
                searchQueries,
                searchResults,
                current != null ? current.searchMode : "external_search",
                "strict_grounding_fallback",
                "检测到正文包含未在本轮证据中出现的股票/量化/财务主张，已降级为仅依据 Provider 原始结果的保守整理稿。",
                current != null ? current.modelStatus : null,
                current != null ? current.modelNote : null
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
            if (result == null) {
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
        }
        return sb.toString();
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
        String normalizedContent = normalizeGroundingText(chatResult.content);
        String normalizedEvidence = normalizeGroundingText(buildSearchEvidenceCorpus(searchResults));
        if (normalizedContent.isBlank()) {
            return null;
        }

        JSONArray unsupportedNumericClaims = findUnsupportedNumericClaims(normalizedContent, normalizedEvidence, searchPlan);
        JSONArray guardedTermViolations = findUnsupportedGuardedTerms(normalizedContent, normalizedEvidence);
        JSONArray stockCodeViolations = findUnsupportedStockCodeClaims(normalizedContent, normalizedEvidence, searchPlan);
        JSONArray targetMismatchViolations = findTargetMismatchViolations(searchResults, searchPlan);
        int totalViolations = unsupportedNumericClaims.size()
                + guardedTermViolations.size()
                + stockCodeViolations.size()
                + targetMismatchViolations.size();

        JSONObject audit = new JSONObject(true);
        audit.put("strictEvidenceRequired", true);
        audit.put("status", totalViolations > 0 ? "unsupported_claims" : "ok");
        audit.put("numericClaimCount", countNumericClaims(normalizedContent, searchPlan));
        audit.put("unsupportedNumericClaimCount", unsupportedNumericClaims.size());
        audit.put("guardedTermViolationCount", guardedTermViolations.size());
        audit.put("stockCodeViolationCount", stockCodeViolations.size());
        audit.put("targetMismatchViolationCount", targetMismatchViolations.size());
        audit.put("unsupportedNumericClaims", unsupportedNumericClaims);
        audit.put("guardedTermViolations", guardedTermViolations);
        audit.put("stockCodeViolations", stockCodeViolations);
        audit.put("targetMismatchViolations", targetMismatchViolations);
        audit.put("note", totalViolations > 0
                ? "正文存在未在本轮 Provider/东方财富证据中定位到的数字或受控事实，不能直接当作已核验结论。"
                : "正文中的受控事实与硬数字未发现明显脱离本轮证据。");
        return audit;
    }

    private boolean isBlockingClaimAudit(JSONObject audit) {
        return audit != null && "unsupported_claims".equalsIgnoreCase(audit.getString("status"));
    }

    private JSONArray findUnsupportedGuardedTerms(String normalizedContent, String normalizedEvidence) {
        JSONArray violations = new JSONArray();
        for (String[] guard : GUARDED_STOCK_TERMS) {
            String trigger = guard[0];
            if (!containsGuardedTerm(normalizedContent, trigger)) {
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
            String numberOnly = normalizedRaw.replaceAll("(?:%|亿|万元|元|倍|bp|bps)$", "");
            if (numberOnly.isBlank()) {
                continue;
            }
            if (searchPlan != null && searchPlan.eastmoneySymbol != null && numberOnly.equals(searchPlan.eastmoneySymbol)) {
                continue;
            }
            if (normalizedEvidence.contains(normalizedRaw)
                    || evidenceContainsEquivalentNumericClaim(normalizedEvidence, normalizedRaw, numberOnly)) {
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
        Double target = parseNumericValue(numberOnly);
        if (target == null || !Double.isFinite(target)) {
            return false;
        }
        String targetUnit = normalizedRaw == null ? "" : normalizedRaw.replace(numberOnly, "");
        Matcher matcher = GROUNDED_NUMERIC_CLAIM_PATTERN.matcher(normalizedEvidence);
        while (matcher.find()) {
            String candidateRaw = matcher.group().replaceAll("\\s+", "");
            String candidateNumber = candidateRaw.replaceAll("(?:%|亿|万元|元|倍|bp|bps)$", "");
            String candidateUnit = candidateRaw.replace(candidateNumber, "");
            if (!targetUnit.equals(candidateUnit)) {
                continue;
            }
            Double candidate = parseNumericValue(candidateNumber);
            if (candidate == null || !Double.isFinite(candidate)) {
                continue;
            }
            double tolerance = Math.max(1e-6d, Math.abs(target) * 1e-6d);
            if (Math.abs(candidate - target) <= tolerance) {
                return true;
            }
        }
        return false;
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
            String numberOnly = normalizedRaw.replaceAll("(?:%|亿|万元|元|倍|bp|bps)$", "");
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
            result.snippet = limitText(rawPayload, 7000);
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
        Map<String, Object> compact = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty() || fields == null || fields.isEmpty()) {
            return compact;
        }
        copyIfPresent(raw, compact, "asOf");
        copyIfPresent(raw, compact, "symbol");
        copyIfPresent(raw, compact, "market");
        copyIfPresent(raw, compact, "periodType");
        for (String field : fields) {
            if (!raw.containsKey(field)) {
                continue;
            }
            compact.put(field, compactEastmoneyValue(field, raw.get(field)));
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

    private Object compactEastmoneyValue(String field, Object value) {
        List<Object> values = toObjectList(value);
        if (values.isEmpty()) {
            return value;
        }
        if ("dailyKline".equals(field)) return tailObjects(values, 40);
        if ("minuteTrends5d".equals(field)) return tailObjects(values, 25);
        if ("intradayFundFlow".equals(field)) return tailObjects(values, 25);
        if ("financials".equals(field)) return headObjects(values, 4);
        if ("announcements".equals(field)) return headObjects(values, 6);
        if ("latestNews".equals(field)) return headObjects(values, 6);
        if ("shareholderCounts".equals(field)) return headObjects(values, 4);
        return values;
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
