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
        return aiModelRouter.chat(modelType, systemPrompt, userPrompt);
    }

    public String callModel(String modelType, String systemPrompt, String userPrompt, Integer maxTokens) {
        return aiModelRouter.chat(modelType, systemPrompt, userPrompt, maxTokens);
    }

    public ChatResult callModelEx(String modelType, String systemPrompt, String userPrompt) {
        return aiModelRouter.chatEx(modelType, systemPrompt, userPrompt);
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
        sb.append("深度分析内容（充分展开论证，引用具体数据和来源，不限字数）");

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
        sb.append("若需要，返回：{\"need\":true,\"intent\":\"forecast_outlook\",\"freshnessDays\":30,\"targetYear\":2026,\"sourceFocus\":[\"reuters.com\",\"stats.gov.cn\"],\"q\":[\"搜索词1\",\"搜索词2\"]}\n");
        sb.append("若不需要，返回：{\"need\":false}\n");
        sb.append("只返回JSON，不要其他内容。");
        return sb.toString();
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
        sb.append("【最终结论】").append(room.getFinalConclusion() != null ? room.getFinalConclusion() : "未达成").append("\n\n");
        sb.append("【完整记录】\n");
        for (AiMeetingOpinion op : opinions) {
            sb.append(String.format("[第%d轮] %s: %s\n", op.getRoundNumber(), nameMap.getOrDefault(op.getMemberId(), "未知"), op.getContent()));
        }
        sb.append("\n请生成包含以下部分的总结：\n1.议题概述  2.各方核心观点  3.主要争议点  4.共识形成过程  5.最终结论及依据  6.遗留问题（如有）");

        return sb.toString();
    }

    public String buildDecisionArtifactPrompt(Long meetingId) {
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
        sb.append("【最终结论】").append(room.getFinalConclusion() != null ? room.getFinalConclusion() : "未达成").append("\n");
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
