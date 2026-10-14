package com.aimeeting.room.service;

import com.aimeeting.room.dao.*;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.entity.*;
import com.aimeeting.room.service.ai.AiModelRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI编排服务：负责构建提示词并调用 AiModelRouter
 */
@Slf4j
@Service
public class MeetingOrchestratorService {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern NUMERIC_CLAIM_PATTERN = Pattern.compile("(?<![A-Za-z])[+-]?\\d{1,4}(?:,\\d{3})*(?:\\.\\d+)?\\s*(?:%|元|亿元|万股|万套|套|台|倍|年|月|日|点|亿|万|股|条)?");

    @Autowired
    private AiModelRouter aiModelRouter;

    @Autowired
    private MeetingDeepSeekWebBridgeService meetingDeepSeekWebBridgeService;

    @Autowired
    private AiMeetingRoomMapper roomMapper;

    @Autowired
    private AiMeetingMemberMapper memberMapper;

    @Autowired
    private AiMeetingOpinionMapper opinionMapper;

    @Autowired
    private AiMeetingVoteMapper voteMapper;

    @Autowired
    private ModelRegistryStore modelRegistryStore;

    public String resolveMeetingModelType(Long meetingId) {
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        for (AiMeetingMember member : members) {
            if (member.getModelType() != null && !member.getModelType().isBlank()) {
                return member.getModelType().trim();
            }
        }
        return null;
    }

    public String resolveNonSearchMeetingModelType(Long meetingId) {
        return resolveNonSearchModelType(resolveMeetingModelType(meetingId));
    }

    public String resolveOperationalMeetingModelType(Long meetingId) {
        return resolveOperationalModelType(resolveMeetingModelType(meetingId));
    }

    public String resolveNonSearchModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return null;
        }
        ModelEntry entry = modelRegistryStore.getByModelId(modelType);
        if (entry == null || !isExperimentalSearchEntry(entry)) {
            return modelType;
        }
        ModelEntry stable = findStableSibling(entry);
        return stable != null ? stable.getId() : modelType;
    }

    public String resolveOperationalModelType(String fallbackModelType) {
        ModelEntry preferred = findPreferredOperationalEntry();
        if (preferred != null) {
            return preferred.getId();
        }
        String resolvedFallback = resolveNonSearchModelType(fallbackModelType);
        if (resolvedFallback != null && !resolvedFallback.isBlank()) {
            return resolvedFallback;
        }
        for (ModelEntry candidate : modelRegistryStore.getEnabled()) {
            if (candidate == null || isExperimentalSearchEntry(candidate)) {
                continue;
            }
            return candidate.getId();
        }
        return fallbackModelType;
    }

    public String resolveExperimentalSearchModelType(String fallbackModelType) {
        ModelEntry fallback = modelRegistryStore.getByModelId(fallbackModelType);
        if (fallback != null && isExperimentalSearchEntry(fallback) && fallback.isEnabled()) {
            return fallback.getId();
        }
        ModelEntry explicitPreferred = modelRegistryStore.getById("9fb32460");
        if (explicitPreferred != null && explicitPreferred.isEnabled() && isExperimentalSearchEntry(explicitPreferred)) {
            return explicitPreferred.getId();
        }
        for (ModelEntry candidate : modelRegistryStore.getEnabled()) {
            if (candidate != null && isExperimentalSearchEntry(candidate)) {
                return candidate.getId();
            }
        }
        return null;
    }

    public String callModel(String modelType, String systemPrompt, String userPrompt) {
        ChatResult bridgeResult = routeMeetingDeepSeekBridge(modelType, systemPrompt, userPrompt, null);
        if (bridgeResult != null) {
            return bridgeContent(bridgeResult);
        }
        return aiModelRouter.chat(modelType, systemPrompt, userPrompt);
    }

    public String callModel(String modelType, String systemPrompt, String userPrompt, Integer maxTokens) {
        ChatResult bridgeResult = routeMeetingDeepSeekBridge(modelType, systemPrompt, userPrompt, maxTokens);
        if (bridgeResult != null) {
            return bridgeContent(bridgeResult);
        }
        return aiModelRouter.chat(modelType, systemPrompt, userPrompt, maxTokens);
    }

    public ChatResult callModelEx(String modelType, String systemPrompt, String userPrompt) {
        ChatResult bridgeResult = routeMeetingDeepSeekBridge(modelType, systemPrompt, userPrompt, null);
        if (bridgeResult != null) {
            return bridgeResult;
        }
        return aiModelRouter.chatEx(modelType, systemPrompt, userPrompt);
    }

    public ChatResult callModelExStrict(String modelType, String systemPrompt, String userPrompt) {
        return callModelExStrict(modelType, systemPrompt, userPrompt, null);
    }

    public ChatResult callModelExStrict(String modelType, String systemPrompt, String userPrompt,
                                        Integer maxTokens) {
        ModelEntry selected = modelType == null ? null : modelRegistryStore.getByModelId(modelType);
        if (selected == null || !selected.isEnabled()) return new ChatResult(null);
        if (meetingDeepSeekWebBridgeService != null) {
            MeetingDeepSeekWebBridgeService.RouteDecision decision =
                    meetingDeepSeekWebBridgeService.routeDecision(modelType);
            if (decision == MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE) {
                return meetingDeepSeekWebBridgeService.chatWithoutSearch(
                        modelType, systemPrompt, userPrompt, maxTokens);
            }
            if (decision == MeetingDeepSeekWebBridgeService.RouteDecision.FAIL_CLOSED) {
                return meetingDeepSeekWebBridgeService.failClosedResult(modelType);
            }
        }
        return aiModelRouter.chatExStrict(modelType, systemPrompt, userPrompt, maxTokens);
    }

    public String callModelStrict(String modelType, String systemPrompt, String userPrompt,
                                  Integer maxTokens) {
        ChatResult result = callModelExStrict(modelType, systemPrompt, userPrompt, maxTokens);
        if (result == null || result.content == null || result.content.isBlank()
                || result.hasSearchEvidence()
                || (result.modelStatus != null && !"success".equalsIgnoreCase(result.modelStatus))) {
            throw new IllegalStateException("meeting_selected_model_unavailable");
        }
        return result.content;
    }

    private ChatResult routeMeetingDeepSeekBridge(String modelType,
                                                  String systemPrompt,
                                                  String userPrompt,
                                                  Integer maxTokens) {
        if (meetingDeepSeekWebBridgeService == null) {
            return null;
        }
        MeetingDeepSeekWebBridgeService.RouteDecision decision =
                meetingDeepSeekWebBridgeService.routeDecision(modelType);
        if (decision == MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE) {
            return meetingDeepSeekWebBridgeService.chat(modelType, systemPrompt, userPrompt, maxTokens);
        }
        if (decision == MeetingDeepSeekWebBridgeService.RouteDecision.FAIL_CLOSED) {
            return meetingDeepSeekWebBridgeService.failClosedResult(modelType);
        }
        return null;
    }

    private String bridgeContent(ChatResult bridgeResult) {
        if (bridgeResult.blocksAutomaticRetry() && (bridgeResult.content == null || bridgeResult.content.isBlank())) {
            throw new IllegalStateException("meeting_provider_outcome_not_retriable");
        }
        return bridgeResult.content;
    }

    public String buildDebateContext(Long meetingId, Long memberId, int roundNumber) {
        return buildDebateContext(meetingId, memberId, roundNumber, null);
    }

    public String buildDebateContext(Long meetingId, Long memberId, int roundNumber, String searchContext) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        List<AiMeetingOpinion> allOpinions = opinionMapper.selectByMeetingId(meetingId);

        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("【会议主题】").append(room.getTitle()).append("\n");
        if (room.getDescription() != null && !room.getDescription().isEmpty()) {
            sb.append("【会议背景】").append(room.getDescription()).append("\n");
        }
        appendProjectMemory(sb, room);
        if (room.getFinalConclusion() != null && !room.getFinalConclusion().isBlank()) {
            sb.append("【上一版结论】").append(room.getFinalConclusion()).append("\n");
            sb.append("【当前任务】你不是从零开始讨论，而是要基于上一版结论进行修订、补充或反驳，直到形成新的稳定结论。\n");
        }
        sb.append("【当前轮次】第").append(roundNumber).append("轮\n\n");

        // 注入真实搜索结果（如果有）
        if (searchContext != null && !searchContext.isBlank()) {
            sb.append("【最新实时资讯（来自网络搜索）】\n").append(searchContext).append("\n\n");
            sb.append("【实时资讯使用硬规则】")
                    .append("你只能把上方 Provider 原始结果、结构化数据中能定位到的内容当作最新事实。")
                    .append("不得引用未在上方出现的公司、股票代码、财务数字、估值、Beta、IC值、因子排名、Wind/万得一致预期、自有量化池或未来季度预测。")
                    .append("如果证据不足，必须明确写“本轮证据未提供/无法核验”，不要补数。\n\n");
        }
        sb.append("【事实核验规则】其他议员的发言、论据摘要、历史结论都只能视为待核验主张，不得直接当作事实继承。")
                .append("你必须以自己本轮搜索拿到的结果独立判断；若你的搜索结果与前文主张冲突，必须明确指出冲突并优先采用你本轮已核验的信息。\n\n");

        // 收集最近的人类指令（最高优先级）
        List<String> humanDirectives = new ArrayList<>();
        List<String> targetedQuestions = new ArrayList<>();
        // 收集本轮已有发言（用于防重复）
        List<String> currentRoundSummary = new ArrayList<>();

        // 滑动窗口：最近2轮完整内容，更早的轮次压缩为80字摘要
        int fullWindowStart = roundNumber - 2;  // 第 fullWindowStart 轮开始显示完整内容

        if (!allOpinions.isEmpty()) {
            sb.append("【历史发言】\n");
            for (AiMeetingOpinion op : allOpinions) {
                boolean isHuman = op.getOpinionType() != null && op.getOpinionType() == 3;
                boolean isDirective = op.getOpinionType() != null && op.getOpinionType() == 4;
                boolean isTargeted = op.getOpinionType() != null && op.getOpinionType() == 5;
                if (isDirective) {
                    humanDirectives.add(op.getContent());
                    sb.append(String.format("[第%d轮] 🚨【主持人指令】: %s\n", op.getRoundNumber(), op.getContent()));
                } else if (isTargeted) {
                    TargetedQuestionMeta meta = parseTargetedQuestionMeta(op.getEvidence());
                    String targetName = meta != null && meta.targetMemberName != null ? meta.targetMemberName : "指定议员";
                    sb.append(String.format("[第%d轮] 🎯【点名追问->%s】: %s\n", op.getRoundNumber(), targetName, op.getContent()));
                    if (meta != null && memberId.equals(meta.targetMemberId)) {
                        targetedQuestions.add(op.getContent());
                    }
                } else if (isHuman) {
                    sb.append(String.format("[第%d轮] 观察员补充: %s\n", op.getRoundNumber(), op.getContent()));
                } else {
                    String name = nameMap.getOrDefault(op.getMemberId(), "未知");
                    String content = softenPriorFactClaims(op.getContent());
                    // 早期轮次压缩：截断到80字
                    if (op.getRoundNumber() < fullWindowStart && content != null && content.length() > 80) {
                        content = content.substring(0, 80) + "…";
                    }
                    sb.append(String.format("[第%d轮] %s: %s\n", op.getRoundNumber(), name, content));
                    // 近期轮次才展示论据（节约token）
                    if (op.getRoundNumber() >= fullWindowStart && op.getEvidence() != null && !op.getMemberId().equals(memberId)) {
                        String evidenceSummary = summarizeEvidenceForContext(op.getEvidence());
                        if (evidenceSummary != null) {
                            sb.append("  └─ 待核验论据主张: ").append(evidenceSummary).append("\n");
                        }
                    }
                    // 记录本轮其他人的发言要点，用于防重复
                    if (op.getRoundNumber() == roundNumber && !op.getMemberId().equals(memberId)) {
                        currentRoundSummary.add(name + "：" + softenPriorFactClaims(op.getContent()));
                    }
                }
            }
            sb.append("\n");
        }

        // 本轮已有发言摘要（防止重复）
        if (!currentRoundSummary.isEmpty()) {
            sb.append("【本轮已有发言（禁止重复这些观点，必须提出新论点、新数据或新角度）】\n");
            for (String s : currentRoundSummary) {
                sb.append("• ").append(s).append("\n");
            }
            sb.append("\n");
        }

        // 若有主持人指令，单独置顶强调
        if (!humanDirectives.isEmpty()) {
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("🚨 主持人最新指令（最高优先级，必须以此为核心调整你的整个发言方向）：\n");
            String lastDirective = humanDirectives.get(humanDirectives.size() - 1);
            sb.append("「").append(lastDirective).append("」\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
        }

        if (!targetedQuestions.isEmpty()) {
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("🎯 你被点名追问，当前轮次必须优先正面回应以下问题：\n");
            for (String question : targetedQuestions) {
                sb.append("「").append(question).append("」\n");
            }
            sb.append("请直接回答问题，并给出可验证的论据或修正说明。\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
        }

        if (roundNumber == 1) {
            sb.append("请对这个议题发表你的深度分析和观点。");
        } else {
            sb.append("请基于上面的讨论深入发言。可以同意、补充或反驳他人，针对对方的具体论据和数据发起挑战。");
        }
        if (searchContext != null && !searchContext.isBlank()) {
            sb.append("请优先引用上方的实时资讯支持你的观点。");
        }
        sb.append("\n\n请严格按以下格式回复（两部分都要有）：\n");
        sb.append("{\"q\":[\"搜索词1\",\"搜索词2\"],\"e\":[{\"s\":\"来源\",\"p\":\"核心论据\"}]}\n");
        sb.append("==REPLY==\n");
        sb.append("深度分析内容（只引用上方实时资讯/结构化数据中可定位的信息；证据不足时明确说明，不得补数）");

        return sb.toString();
    }

    private TargetedQuestionMeta parseTargetedQuestionMeta(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            com.alibaba.fastjson.JSONObject meta = com.alibaba.fastjson.JSON.parseObject(raw);
            if (meta == null || !meta.containsKey("targetMemberId")) {
                return null;
            }
            TargetedQuestionMeta result = new TargetedQuestionMeta();
            result.targetMemberId = meta.getLong("targetMemberId");
            result.targetMemberName = meta.getString("targetMemberName");
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static class TargetedQuestionMeta {
        private Long targetMemberId;
        private String targetMemberName;
    }

    private boolean isExperimentalSearchEntry(ModelEntry entry) {
        if (entry == null) {
            return false;
        }
        String modelId = entry.getModelId() != null ? entry.getModelId().toLowerCase() : "";
        String apiFormat = entry.getApiFormat() != null ? entry.getApiFormat().toLowerCase() : "";
        String apiUrl = entry.getApiUrl() != null ? entry.getApiUrl().toLowerCase() : "";
        return modelId.startsWith("deepseek-v4")
                && "claude".equals(apiFormat)
                && apiUrl.contains("/anthropic/");
    }

    private ModelEntry findStableSibling(ModelEntry experimental) {
        for (ModelEntry candidate : modelRegistryStore.getEnabled()) {
            if (candidate == null || candidate == experimental) {
                continue;
            }
            if (!candidate.getModelId().equals(experimental.getModelId())) {
                continue;
            }
            if (isExperimentalSearchEntry(candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private ModelEntry findPreferredOperationalEntry() {
        ModelEntry explicitPreferred = modelRegistryStore.getById("b6465a2d");
        if (explicitPreferred != null && explicitPreferred.isEnabled() && !isExperimentalSearchEntry(explicitPreferred)) {
            return explicitPreferred;
        }
        for (ModelEntry candidate : modelRegistryStore.getEnabled()) {
            if (candidate == null || isExperimentalSearchEntry(candidate)) {
                continue;
            }
            String modelId = candidate.getModelId() != null ? candidate.getModelId().toLowerCase() : "";
            String apiFormat = candidate.getApiFormat() != null ? candidate.getApiFormat().toLowerCase() : "";
            if (modelId.startsWith("deepseek-v4") && "openai".equals(apiFormat)) {
                return candidate;
            }
        }
        return null;
    }

    /** 将 evidence JSON 提炼为可注入上下文的简短摘要 */
    private String summarizeEvidenceForContext(String evidenceJson) {
        if (evidenceJson == null) return null;
        try {
            com.alibaba.fastjson.JSONObject ev = com.alibaba.fastjson.JSON.parseObject(evidenceJson);
            StringBuilder sb = new StringBuilder();
            com.alibaba.fastjson.JSONArray eArr = ev.getJSONArray("e");
            if (eArr != null && !eArr.isEmpty()) {
                for (int i = 0; i < Math.min(2, eArr.size()); i++) {
                    com.alibaba.fastjson.JSONObject e = eArr.getJSONObject(i);
                    String s = e.getString("s"), p = e.getString("p");
                    if (p != null) sb.append("[待核验-").append(s != null ? s : "来源").append("] ").append(softenPriorFactClaims(p)).append("；");
                }
            }
            com.alibaba.fastjson.JSONArray results = ev.getJSONArray("results");
            if (results != null && !results.isEmpty() && sb.length() == 0) {
                com.alibaba.fastjson.JSONObject first = results.getJSONObject(0);
                String snippet = first.getString("snippet");
                sb.append("[待核验-").append(first.getString("source")).append("] ")
                  .append(softenPriorFactClaims(snippet != null && snippet.length() > 50 ? snippet.substring(0, 50) + "…" : snippet));
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception ignored) { return null; }
    }

    private String softenPriorFactClaims(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String softened = URL_PATTERN.matcher(text).replaceAll("[链接略]");
        softened = NUMERIC_CLAIM_PATTERN.matcher(softened).replaceAll("[数值略]");
        return softened.replaceAll("\\s{2,}", " ").trim();
    }

    public String buildSearchDecisionPrompt(Long meetingId, Long memberId, int roundNumber) {
        StringBuilder sb = buildSearchDecisionContext(meetingId, memberId, roundNumber);
        sb.append("若需要，返回：{\"need\":true,\"intent\":\"forecast_outlook\",\"freshnessDays\":30,\"targetYear\":2026,\"sourceFocus\":[\"eastmoney.com\",\"cninfo.com.cn\"],\"q\":[\"搜索词1\",\"搜索词2\"],\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\",\"eastmoneyFields\":[\"quote\",\"dailyKline\",\"technicalIndicators\",\"valuation\",\"financials\",\"latestNews\",\"shareholderCounts\"]}\n");
        sb.append("若不需要，返回：{\"need\":false}\n");
        sb.append("只返回JSON，不要其他内容。");
        return sb.toString();
    }

    public String buildReviewedEvidenceNeedsPrompt(Long meetingId, Long memberId, int roundNumber) {
        StringBuilder sb = buildSearchDecisionContext(meetingId, memberId, roundNumber);
        sb.append("本轮只规划资料需求，不要自行搜索或给出事实答案。每个需求必须是一个可以单独核验的具体问题，最多三个。\n");
        sb.append("对于A股行情、K线、技术指标、估值、财报、公告等可由东方财富结构化接口获取的资料，在该需求的 eastmoneyFields 中列出字段；只需外部搜索的需求填空数组。\n");
        sb.append("eastmoneyFields 只允许：quote、stockDetail、dailyKline、technicalIndicators、valuation、financials、announcements、latestNews、shareholderCounts、marketBenchmark、minuteTrends5d、intradayFundFlow。MA20/MACD/RSI 等单项归入 technicalIndicators；成交量/换手率归入 dailyKline 或 quote。MA120、融资余额、跨20日主力资金汇总等接口未提供的项目必须标记 webSearch:true，不能编造字段名。\n");
        sb.append("对具体数值需求，增加 requiredFieldPaths 数组，明确需要交付的字段，不能只填分组名。只允许 section.field 或 section[].field，例如 financials[].netProfit、valuation.pePercentile1y、technicalIndicators.rsi14；section 必须属于本项 eastmoneyFields。最多8项，不使用索引、通配符或表达式。系统会逐项核对实际响应和最终交付资料，字段缺失会转为待搜索缺口。\n");
        sb.append("technicalIndicators 常用实际字段：ma5、ma10、ma20、ma60、macdDif、macdDea、macdHistogram、rsi14、atr14、bollMiddle、bollUpper、bollLower、historicalVolatility20Pct、return5Pct、return20Pct、support20、resistance20、obv、priceVsMa20Pct。MACD需求填写 macdDif、macdDea、macdHistogram 三个路径；没有单一的 macd 字段。只申请本轮问题需要的指标，不要把全部字段塞入计划。\n");
        sb.append("quote 常用实际字段：price、previousClose、open、high、low、changeAmount、changePercent、volume、amount、turnover、volumeRatio、mainInflow、mainInflowRatio。换手率路径为 quote.turnover，不是 quote.turnoverRate。\n");
        sb.append("financials 当前提供 revenue、netProfit、netProfitExNonRecurring、grossMarginPct、netMarginPct、cashToRevenuePct 等；cashToRevenuePct 是经营现金流营收比，不能替代经营活动现金流量净额 netOperatingCashFlow。valuation 仅提供 pePercentile1y/pbPercentile1y，不能替代五年分位。公告和新闻接口主要提供标题目录（新闻摘要可能截断），具体公告内容、风险与数字需 webSearch:true 并写明 webQuestion。明确要求已收盘数据时，盘中日K及由它计算的指标不能算满足。\n");
        sb.append("每个需求还要给出 webSearch 布尔值：东方财富字段足以回答时为 false；还需要公开网页资料时为 true。没有 eastmoneyFields 的需求必须为 true。若一个需求同时要结构化字段和网页事实，必须单列 webQuestion，明确网页独有的一个待核验问题。\n");
        sb.append("若某项资料属于另一只股票，在该 needs 项内写 eastmoneySymbol 和 eastmoneyMarket；不要把会议主股票的数据用于对手股票。每个问题尽量只问一个事实或指标。\n");
        sb.append("不要在一个 question 中罗列多个数字、指标或事件。每项 needs 最多填写一个 eastmoneyFields 字段，或一个独立的网页问题；整份计划优先选最关键的三个原子缺口，避免把十几个指标塞进三项需求。\n");
        sb.append("每个需求的 sourceFocus 只能填写按重要性排序的 0 到 3 个来源域名，不要填写完整 URL、描述或超过三个域名。这里是优先搜索站点提示，不代表只有这些站点才可用于交叉核验。\n");
        sb.append("返回：{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\",\"needs\":[{\"id\":\"price\",\"question\":\"宁德时代最近一个交易日的收盘价是多少？\",\"eastmoneyFields\":[\"quote\"],\"requiredFieldPaths\":[\"quote.price\"],\"webSearch\":false,\"sourceFocus\":[]},{\"id\":\"industry\",\"question\":\"最近的储能行业需求数据是什么？\",\"eastmoneyFields\":[],\"webSearch\":true,\"sourceFocus\":[\"gov.cn\"]}]}\n");
        sb.append("如果本轮只表达不新增事实的逻辑意见，可返回：{\"need\":false,\"needs\":[]}。只返回JSON，不要其他内容。");
        return sb.toString();
    }

    private StringBuilder buildSearchDecisionContext(Long meetingId, Long memberId, int roundNumber) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        AiMeetingMember member = memberMapper.selectById(memberId);
        List<AiMeetingOpinion> allOpinions = opinionMapper.selectByMeetingId(meetingId);

        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("议题：").append(room.getTitle()).append("\n");
        if (room.getProjectContext() != null && !room.getProjectContext().isBlank()) {
            sb.append("项目背景：").append(room.getProjectContext()).append("\n");
        }
        if (room.getInheritedContext() != null && !room.getInheritedContext().isBlank()) {
            sb.append("历史项目记忆：").append(room.getInheritedContext()).append("\n");
        }
        sb.append("你的立场/背景：").append(member.getPersona() != null ? member.getPersona() : "").append("\n");

        // 注入最近3条发言作为上下文
        if (!allOpinions.isEmpty()) {
            sb.append("最近讨论片段：\n");
            int start = Math.max(0, allOpinions.size() - 3);
            for (int i = start; i < allOpinions.size(); i++) {
                AiMeetingOpinion op = allOpinions.get(i);
                boolean isHuman = op.getOpinionType() != null && op.getOpinionType() == 3;
                boolean isDirective = op.getOpinionType() != null && op.getOpinionType() == 4;
                boolean isTargeted = op.getOpinionType() != null && op.getOpinionType() == 5;
                String name;
                if (isDirective) {
                    name = "主持人指令";
                } else if (isTargeted) {
                    TargetedQuestionMeta meta = parseTargetedQuestionMeta(op.getEvidence());
                    String targetName = meta != null && meta.targetMemberName != null ? meta.targetMemberName : "指定议员";
                    name = "点名追问->" + targetName;
                } else {
                    name = isHuman ? "观察员" : nameMap.getOrDefault(op.getMemberId(), "未知");
                }
                sb.append("  ").append(name).append("：").append(op.getContent()).append("\n");
            }
        }

        sb.append("\n你现在要发言，是否需要查询最新网络资讯来支持你的观点或反驳对方？\n");
        sb.append("今天日期：").append(LocalDate.now()).append("。\n");
        sb.append("最近讨论片段仅用于识别争议点与待核验主张，不代表事实本身。你必须独立决定本轮要搜索什么，不能因为前面议员提到某个数字或结论，就直接沿用它。\n");
        sb.append("请先判断信息需求类型，例如：realtime_price（实时价格）、latest_news（最新新闻）、forecast_outlook（最新预测）、background_fact（背景事实）、policy_event（政策/事件）。\n");
        sb.append("如果需要搜索，请先决定你最需要核验的事实类型，再指定优先来源域名 sourceFocus，例如路透、彭博、监管网站、交易所公告、统计局、行业协会、公司公告站点。\n");
        sb.append("查询词要体现“最新时间窗口 + 目标年份 + 关键指标 + 优先来源”。sourceFocus 里放域名，不要写自然语言描述。\n");
        sb.append("当你确定某个事实只该从特定站点取证（如交易所/统计局/公司公告/指定财经媒体，例如 eastmoney.com、stats.gov.cn），就把该域名放进 sourceFocus，系统会定向只检索这些站点；也可直接在查询词里写 site:域名。不确定来源时 sourceFocus 留空即可。\n");
        sb.append("如果议题是A股个股，你还可以要求系统调用东方财富结构化数据。可选 eastmoneyFields：quote（实时行情/资金流）、stockDetail（估值与市值）、dailyKline（日K）、technicalIndicators（均线/MACD/RSI/波动）、valuation（估值分位）、financials（财报）、announcements（公告）、latestNews（个股新闻）、shareholderCounts（股东户数）、marketBenchmark（相对指数强弱）。\n");
        sb.append("使用东方财富时请尽量给出股票代码和市场，例如宁德时代为 300750.sz；如果不确定代码，可以只返回网络搜索计划。\n");
        return sb;
    }

    public String buildVotePrompt(Long meetingId, Long memberId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("【会议主题】").append(room.getTitle()).append("\n");
        appendProjectMemory(sb, room);
        sb.append("\n【讨论记录】\n");
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

    /** 为 deepseek-reasoner 等不支持 tool_call 的模型，生成预搜索提示词（由 deepseek-chat 执行） */
    public String buildPreSearchPrompt(Long meetingId) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        String topic = room.getTitle();
        String desc = room.getDescription();
        return "议题：" + topic +
                (desc != null && !desc.isEmpty() ? "\n背景：" + desc : "") +
                "\n\n请搜索该议题的最新资讯（新闻、数据、研究报告），整理为简洁的背景资料摘要（300字以内）。";
    }

    /** 生成单轮摘要提示词 */
    public String buildRoundSummaryPrompt(Long meetingId, int roundNumber) {
        List<AiMeetingOpinion> roundOps = opinionMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("以下是第").append(roundNumber).append("轮的讨论发言：\n\n");
        for (AiMeetingOpinion op : roundOps) {
            if (op.getOpinionType() != null && (op.getOpinionType() == 3 || op.getOpinionType() == 4)) continue; // 跳过人类介入
            String name = nameMap.getOrDefault(op.getMemberId(), "未知");
            String content = op.getContent();
            // 只取前200字避免prompt过长
            if (content != null && content.length() > 200) content = content.substring(0, 200) + "…";
            sb.append(name).append("：").append(content).append("\n\n");
        }
        sb.append("请用一到两句话（100字以内）概括本轮的核心分歧和主要共识，输出纯文本。");
        return sb.toString();
    }

    public String buildSummaryPrompt(Long meetingId) {
        return buildSummaryPrompt(meetingId, null);
    }

    /** The reviewed finalization passes its voted conclusion before that value is committed to the room. */
    public String buildSummaryPrompt(Long meetingId, String pendingConclusion) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("请为以下AI多成员会议生成结构化总结。\n\n");
        sb.append("【主题】").append(room.getTitle()).append("\n");
        appendProjectMemory(sb, room);
        sb.append("【参与成员】").append(members.stream().map(AiMeetingMember::getMemberName).collect(Collectors.joining("、"))).append("\n");
        String conclusion = pendingConclusion != null && !pendingConclusion.isBlank()
                ? pendingConclusion : room.getFinalConclusion();
        sb.append("【最终结论】").append(conclusion != null ? conclusion : "未达成").append("\n\n");
        sb.append("【完整记录】\n");
        for (AiMeetingOpinion op : opinions) {
            sb.append(String.format("[第%d轮] %s: %s\n", op.getRoundNumber(), nameMap.getOrDefault(op.getMemberId(), "未知"), op.getContent()));
        }
        sb.append("\n请生成包含以下部分的总结：\n1.议题概述  2.各方核心观点  3.主要争议点  4.共识形成过程  5.最终结论及依据  6.遗留问题（如有）");

        return sb.toString();
    }

    public String buildDecisionArtifactPrompt(Long meetingId) {
        return buildDecisionArtifactPrompt(meetingId, null);
    }

    /** The reviewed finalization passes its voted conclusion before that value is committed to the room. */
    public String buildDecisionArtifactPrompt(Long meetingId, String pendingConclusion) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingId(meetingId);
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        java.util.Map<Long, String> nameMap = members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName));

        StringBuilder sb = new StringBuilder();
        sb.append("请将下面会议内容整理为严格 JSON，对后续同项目会议复用最有价值。\n");
        sb.append("输出字段必须包含：");
        sb.append("topicOverview, projectKey, projectContext, keyViewpoints, majorDisputes, consensusPath, finalDecision, decisionBasis, openQuestions, nextActions。\n");
        sb.append("其中 keyViewpoints / majorDisputes / decisionBasis / openQuestions / nextActions 必须是字符串数组。\n\n");
        sb.append("【主题】").append(room.getTitle()).append("\n");
        appendProjectMemory(sb, room);
        String conclusion = pendingConclusion != null && !pendingConclusion.isBlank()
                ? pendingConclusion : room.getFinalConclusion();
        sb.append("【最终结论】").append(conclusion != null ? conclusion : "未达成").append("\n");
        if (room.getSummary() != null && !room.getSummary().isBlank()) {
            sb.append("【会议总结】").append(room.getSummary()).append("\n");
        }
        sb.append("\n【关键讨论记录】\n");
        for (AiMeetingOpinion op : opinions) {
            String speaker;
            if (op.getOpinionType() != null && op.getOpinionType() == 4) {
                speaker = "主持人指令";
            } else if (op.getOpinionType() != null && op.getOpinionType() == 3) {
                speaker = "观察员";
            } else {
                speaker = nameMap.getOrDefault(op.getMemberId(), "未知");
            }
            sb.append(String.format("[第%d轮] %s: %s\n", op.getRoundNumber(), speaker, op.getContent()));
        }
        sb.append("\n只输出 JSON，不要 Markdown，不要代码块。");
        return sb.toString();
    }

    private void appendProjectMemory(StringBuilder sb, AiMeetingRoom room) {
        if (room.getProjectKey() != null && !room.getProjectKey().isBlank()) {
            sb.append("【项目标识】").append(room.getProjectKey()).append("\n");
        }
        if (room.getProjectContext() != null && !room.getProjectContext().isBlank()) {
            sb.append("【项目背景】").append(room.getProjectContext()).append("\n");
        }
        if (room.getInheritedContext() != null && !room.getInheritedContext().isBlank()) {
            sb.append("【项目历史记忆】").append(room.getInheritedContext()).append("\n");
        }
    }
}
