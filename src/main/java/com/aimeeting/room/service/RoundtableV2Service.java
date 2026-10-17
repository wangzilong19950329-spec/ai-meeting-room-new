package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingV2RecordMapper;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.dto.RoundtableV2ViewVO;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiMeetingV2Record;
import com.aimeeting.room.entity.MeetingReviewedSearchGap;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Roundtable v2 skeleton: the moderator writes an issue card with sub-questions and options, the
 * fact base is gathered once through reviewed search, every member answers back-to-back, and the
 * program — not a model — sorts sub-questions into conclusions and disputes. Each stage is stored
 * as a checkpoint so a resumed run continues without repeating model calls or searches.
 */
@Slf4j
@Service
public class RoundtableV2Service {

    static final String ISSUE_CARD = "ISSUE_CARD";
    static final String FACT_BASE = "FACT_BASE";
    static final String ANSWER = "ANSWER";
    static final String RESULT = "RESULT";

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long SEARCH_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(13);
    /** Pre-meeting facts live in round 0 of the reviewed-search ledger. */
    private static final int FACT_BASE_ROUND = 0;
    private static final long MEETING_LEVEL = 0L;

    @Autowired private AiMeetingRoomMapper roomMapper;
    @Autowired private AiMeetingMemberMapper memberMapper;
    @Autowired private MeetingOrchestratorService orchestrator;
    @Autowired private MeetingReviewedEvidenceService reviewedEvidenceService;
    @Autowired private MeetingReviewedSearchLedgerService reviewedSearchLedgerService;
    @Autowired private AiMeetingV2RecordMapper recordMapper;
    @Autowired private MeetingReviewedFinalizationWriter reviewedFinalizationWriter;
    @Autowired private MeetingWorkflowService workflowService;
    @Autowired private AiModelProperties aiModelProperties;

    public void run(Long meetingId, Long expectedEpoch) {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, 1, null, MeetingCallContext.OTHER)) {
            runStages(meetingId, expectedEpoch);
        } catch (Blocked blocked) {
            pause(meetingId, expectedEpoch, blocked.getMessage());
        } catch (RuntimeException failure) {
            log.error("Roundtable v2 run failed, meetingId={}", meetingId, failure);
            pause(meetingId, expectedEpoch, "圆桌 v2 执行失败：" + failure.getMessage());
        }
    }

    public RoundtableV2ViewVO view(Long meetingId) {
        RoundtableV2ViewVO view = new RoundtableV2ViewVO();
        List<AiMeetingV2Record> stored = recordMapper.selectByMeetingId(meetingId);
        if (stored == null) return view;
        for (AiMeetingV2Record record : stored) {
            if (ISSUE_CARD.equals(record.getKind())) view.setIssueCard(JSON.parseObject(record.getPayload()));
            else if (FACT_BASE.equals(record.getKind())) view.setFacts(JSON.parseArray(record.getPayload()));
            else if (ANSWER.equals(record.getKind())) view.getAnswers().add(JSON.parseObject(record.getPayload()));
            else if (RESULT.equals(record.getKind())) view.setResult(JSON.parseObject(record.getPayload()));
        }
        return view;
    }

    public void deleteByMeetingId(Long meetingId) {
        recordMapper.deleteByMeetingId(meetingId);
    }

    private void runStages(Long meetingId, Long epoch) {
        AiMeetingRoom room = activeRoom(meetingId, epoch);
        if (room == null) return;
        JSONObject card = issueCard(meetingId, epoch, room);
        if (activeRoom(meetingId, epoch) == null) return;
        JSONArray facts = factBase(meetingId, epoch, card);
        if (facts == null) return;
        JSONArray answers = new JSONArray();
        for (AiMeetingMember member : memberMapper.selectByMeetingId(meetingId)) {
            if (activeRoom(meetingId, epoch) == null) return;
            answers.add(answer(meetingId, epoch, card, facts, member));
        }
        JSONObject result = result(meetingId, epoch, card, facts, answers);
        conclude(meetingId, epoch, card, facts, result);
    }

    // ---- P0 issue card ----

    private JSONObject issueCard(Long meetingId, Long epoch, AiMeetingRoom room) {
        JSONObject stored = loadObject(meetingId, ISSUE_CARD, MEETING_LEVEL);
        if (stored != null) return stored;
        String model = orchestrator.resolveMeetingModelType(meetingId);
        String system = "你是中立的会议主持人，只负责把议题拆成可以选边的子问题并列出取证题，"
                + "不表达立场、不打分。只输出 JSON。";
        String asOf = LocalDate.now(MARKET_ZONE).toString();
        String prompt = issueCardPrompt(room, asOf);
        String error;
        JSONObject card;
        try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.ISSUE_CARD)) {
            String reply = orchestrator.callModelStrict(model, system, prompt, null);
            card = parseObject(reply);
            error = validateIssueCard(card);
            if (error != null) {
                reply = orchestrator.callModelStrict(model, system, prompt + "\n\n【上一版问题卡不合格】" + error
                        + "。请按要求重新输出完整 JSON。", null);
                card = parseObject(reply);
                error = validateIssueCard(card);
            }
        }
        if (error != null) throw new Blocked("问题卡不合格：" + error);
        JSONObject normalized = new JSONObject(true);
        normalized.put("topic", firstNonBlank(card.getString("topic"), room.getTitle()));
        normalized.put("horizon", card.getString("horizon"));
        normalized.put("asOf", asOf);
        normalized.put("subQuestions", card.getJSONArray("subQuestions"));
        JSONArray evidenceQuestions = card.getJSONArray("evidenceQuestions");
        normalized.put("evidenceQuestions", evidenceQuestions == null ? new JSONArray() : evidenceQuestions);
        normalized.put("referenceClass", card.get("referenceClass"));
        return save(meetingId, ISSUE_CARD, MEETING_LEVEL, epoch, normalized);
    }

    private static String issueCardPrompt(AiMeetingRoom room, String asOf) {
        StringBuilder sb = new StringBuilder();
        sb.append("【议题】").append(room.getTitle()).append("\n");
        if (room.getDescription() != null && !room.getDescription().isBlank()) {
            sb.append("【背景】").append(room.getDescription()).append("\n");
        }
        if (room.getProjectContext() != null && !room.getProjectContext().isBlank()) {
            sb.append("【项目背景】").append(room.getProjectContext()).append("\n");
        }
        sb.append("【截至日期】").append(asOf).append("\n\n");
        sb.append("请把议题拆成 2–4 个子问题（推动结论的关键驱动因素），每个子问题给 2–4 个互斥选项，")
                .append("选项要能让议员明确选边。\n");
        sb.append("再列出不超过 8 道需要联网核实的取证题，每题只问一个可以核实的事实，多空两方面都要覆盖。\n");
        sb.append("如果有合适的参照类（过去的类似情况），写在 referenceClass，否则写 null。\n");
        sb.append("只输出 JSON：{\"topic\":\"\",\"horizon\":\"\",\"subQuestions\":[{\"id\":\"Q1\",\"title\":\"\",")
                .append("\"options\":[{\"id\":\"A\",\"label\":\"\"}]}],\"evidenceQuestions\":[\"\"],\"referenceClass\":null}");
        return sb.toString();
    }

    static String validateIssueCard(JSONObject card) {
        if (card == null) return "不是 JSON 对象";
        JSONArray subQuestions = card.getJSONArray("subQuestions");
        if (subQuestions == null || subQuestions.size() < 2 || subQuestions.size() > 4) {
            return "子问题必须是 2–4 个";
        }
        Set<String> questionIds = new HashSet<>();
        for (int i = 0; i < subQuestions.size(); i++) {
            JSONObject question = subQuestions.getJSONObject(i);
            if (question == null || blank(question.getString("id")) || blank(question.getString("title"))
                    || !questionIds.add(question.getString("id"))) {
                return "第 " + (i + 1) + " 个子问题缺少唯一的 id 或标题";
            }
            JSONArray options = question.getJSONArray("options");
            if (options == null || options.size() < 2 || options.size() > 4) {
                return "子问题 " + question.getString("id") + " 的选项必须是 2–4 个";
            }
            Set<String> optionIds = new HashSet<>();
            for (int j = 0; j < options.size(); j++) {
                JSONObject option = options.getJSONObject(j);
                if (option == null || blank(option.getString("id")) || blank(option.getString("label"))
                        || !optionIds.add(option.getString("id"))) {
                    return "子问题 " + question.getString("id") + " 的选项缺少唯一的 id 或说明";
                }
            }
        }
        JSONArray evidenceQuestions = card.getJSONArray("evidenceQuestions");
        if (evidenceQuestions != null) {
            if (evidenceQuestions.size() > 8) return "取证题不能超过 8 道";
            for (Object question : evidenceQuestions) {
                if (!(question instanceof String) || blank((String) question)) return "取证题不能为空";
            }
        }
        return null;
    }

    // ---- P1 fact base ----

    /** Returns null when the meeting stopped while facts were being gathered. */
    private JSONArray factBase(Long meetingId, Long epoch, JSONObject card) {
        AiMeetingV2Record stored = recordMapper.selectOne(meetingId, FACT_BASE, MEETING_LEVEL);
        if (stored != null) return JSON.parseArray(stored.getPayload());
        String asOf = card.getString("asOf");
        JSONArray facts = new JSONArray();
        int verified = 0;
        int provisional = 0;
        int gaps = 0;
        for (Object item : card.getJSONArray("evidenceQuestions")) {
            if (activeRoom(meetingId, epoch) == null) return null;
            String question = (String) item;
            MeetingReviewedEvidenceService.ReviewedEvidence evidence;
            try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.EVIDENCE_SEARCH)) {
                evidence = resolveEvidence(meetingId, asOf, question);
            } catch (MeetingReviewedEvidenceService.EvidenceRejected rejected) {
                facts.add(gapFact(++gaps, question, rejected.getMessage()));
                continue;
            }
            JSONArray claims = evidence.packet.getJSONArray("claims");
            JSONArray candidates = evidence.packet.getJSONArray("provisionalClaims");
            boolean any = false;
            for (int i = 0; claims != null && i < claims.size(); i++) {
                facts.add(fact("F-" + (++verified), "F", question, claims.getJSONObject(i)));
                any = true;
            }
            for (int i = 0; candidates != null && i < candidates.size(); i++) {
                facts.add(fact("L-" + (++provisional), "L", question, candidates.getJSONObject(i)));
                any = true;
            }
            if (!any) facts.add(gapFact(++gaps, question, "N8N_" + evidence.packet.getString("status")));
        }
        return save(meetingId, FACT_BASE, MEETING_LEVEL, epoch, facts);
    }

    private MeetingReviewedEvidenceService.ReviewedEvidence resolveEvidence(Long meetingId, String asOf,
                                                                            String question) {
        MeetingReviewedSearchGap gap = reviewedSearchLedgerService.findOrCreateGap(
                meetingId, FACT_BASE_ROUND, asOf, question);
        if (isAccepted(gap)) return accepted(gap, question);
        if (!MeetingReviewedSearchLedgerService.PENDING.equals(gap.getStatus())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_GAP_" + gap.getStatus());
        }
        if (!reviewedSearchLedgerService.claimPendingGap(gap.getId())) {
            MeetingReviewedSearchGap latest = reviewedSearchLedgerService.findGapById(gap.getId());
            if (isAccepted(latest)) return accepted(latest, question);
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_GAP_ALREADY_CLAIMED");
        }
        MeetingReviewedEvidenceService.ReviewedEvidence reviewed;
        try {
            reviewed = reviewedEvidenceService.search(gap.getRequestId(), question, asOf, SEARCH_TIMEOUT_MS);
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
        if (!persisted) throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_PACKET_NOT_PERSISTED");
        return reviewed;
    }

    private static boolean isAccepted(MeetingReviewedSearchGap gap) {
        return gap != null && (MeetingReviewedSearchLedgerService.CROSS_CHECKED.equals(gap.getStatus())
                || MeetingReviewedSearchLedgerService.LOW_CONFIDENCE.equals(gap.getStatus()));
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence accepted(MeetingReviewedSearchGap gap,
                                                                            String question) {
        return MeetingReviewedEvidenceService.acceptPacket(JSON.parseObject(gap.getPacketJson()),
                gap.getRequestId(), question);
    }

    private static JSONObject fact(String id, String tier, String question, JSONObject claim) {
        JSONObject fact = new JSONObject(true);
        fact.put("id", id);
        fact.put("tier", tier);
        fact.put("question", question);
        fact.put("statement", claim.getString("statement"));
        for (String key : List.of("value", "unit", "period", "scope", "limitation", "confidenceScore")) {
            if (claim.get(key) != null) fact.put(key, claim.get(key));
        }
        JSONArray sources = claim.getJSONArray("sourceUrls");
        if (sources != null && !sources.isEmpty()) fact.put("source", sources.getString(0));
        return fact;
    }

    private static JSONObject gapFact(int index, String question, String reason) {
        JSONObject fact = new JSONObject(true);
        fact.put("id", "G-" + index);
        fact.put("tier", "G");
        fact.put("question", question);
        fact.put("reason", reason);
        return fact;
    }

    // ---- P2 back-to-back answers ----

    private JSONObject answer(Long meetingId, Long epoch, JSONObject card, JSONArray facts, AiMeetingMember member) {
        JSONObject stored = loadObject(meetingId, ANSWER, member.getId());
        if (stored != null) return stored;
        String system = "你是「" + member.getMemberName() + "」，参加一场圆桌研判。你的分析视角："
                + firstNonBlank(member.getPersona(), "独立分析") + "\n"
                + "你只能依据问题卡和事实底稿独立作答，看不到其他议员的观点。只输出 JSON。";
        String prompt = answerPrompt(card, facts);
        String[] corpora = corpora(facts);
        Set<String> factIds = factIds(facts);
        Answer parsed;
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, 1, member.getId(), MeetingCallContext.DIVERGENCE)) {
            parsed = parseAnswer(callQuietly(member.getModelType(), system, prompt), card, factIds, corpora);
        }
        if (parsed.error != null) {
            try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                    meetingId, 1, member.getId(), MeetingCallContext.DIVERGENCE_REWRITE)) {
                parsed = parseAnswer(callQuietly(member.getModelType(), system,
                        prompt + "\n\n【上一版作答不合格】" + parsed.error + "。请按要求重新输出完整 JSON。"),
                        card, factIds, corpora);
            }
        }
        JSONObject record = new JSONObject(true);
        record.put("memberId", member.getId());
        record.put("memberName", member.getMemberName());
        if (parsed.error != null) {
            record.put("skipped", true);
            record.put("reason", parsed.error);
        } else {
            record.put("skipped", false);
            record.put("answers", parsed.answers);
        }
        return save(meetingId, ANSWER, member.getId(), epoch, record);
    }

    private String callQuietly(String model, String system, String prompt) {
        try {
            return orchestrator.callModelStrict(model, system, prompt, null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static String answerPrompt(JSONObject card, JSONArray facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【问题卡】\n议题：").append(card.getString("topic"));
        if (!blank(card.getString("horizon"))) sb.append("；展望期：").append(card.getString("horizon"));
        sb.append("；截至：").append(card.getString("asOf")).append("\n子问题：\n");
        for (Object item : card.getJSONArray("subQuestions")) {
            JSONObject question = (JSONObject) item;
            sb.append(question.getString("id")).append(" ").append(question.getString("title")).append("：");
            List<String> options = new ArrayList<>();
            for (Object option : question.getJSONArray("options")) {
                JSONObject json = (JSONObject) option;
                options.add(json.getString("id") + " " + json.getString("label"));
            }
            sb.append(String.join(" / ", options)).append("\n");
        }
        sb.append("\n【事实底稿】只能引用这里的事实；L 为低置信资料，G 为没能核实的缺口。\n");
        for (Object item : facts) {
            JSONObject fact = (JSONObject) item;
            String tier = fact.getString("tier");
            sb.append(fact.getString("id")).append(" [")
                    .append("F".equals(tier) ? "已核实" : "L".equals(tier) ? "低置信" : "缺口").append("] ");
            if ("G".equals(tier)) {
                sb.append(fact.getString("question")).append("（未能核实）");
            } else {
                sb.append(fact.getString("statement"));
                if (!blank(fact.getString("limitation"))) sb.append("（限制：").append(fact.getString("limitation")).append("）");
            }
            sb.append("\n");
        }
        sb.append("\n【作答要求】对每个子问题各选一个选项；每个选择给 1–3 条理由，每条理由注明引用的事实编号；")
                .append("再写一个会推翻你判断的信号（falsifier）。理由里的数字只能来自上面的事实，")
                .append("低置信事实可以用，但它没有经过交叉核实。\n");
        sb.append("只输出 JSON：{\"answers\":[{\"questionId\":\"Q1\",\"optionId\":\"A\",")
                .append("\"reasons\":[{\"text\":\"\",\"factIds\":[\"F-1\"]}],\"falsifier\":\"\"}]}");
        return sb.toString();
    }

    private static Answer parseAnswer(String reply, JSONObject card, Set<String> factIds, String[] corpora) {
        JSONObject root = parseObject(reply);
        if (root == null || root.getJSONArray("answers") == null) return Answer.invalid("没有 answers 数组");
        Map<String, JSONObject> byQuestion = new LinkedHashMap<>();
        for (Object item : root.getJSONArray("answers")) {
            if (!(item instanceof JSONObject)) return Answer.invalid("answers 里有非对象条目");
            JSONObject answer = (JSONObject) item;
            if (byQuestion.put(answer.getString("questionId"), answer) != null) {
                return Answer.invalid("子问题 " + answer.getString("questionId") + " 回答了不止一次");
            }
        }
        JSONArray normalized = new JSONArray();
        for (Object item : card.getJSONArray("subQuestions")) {
            JSONObject question = (JSONObject) item;
            String questionId = question.getString("id");
            JSONObject answer = byQuestion.remove(questionId);
            if (answer == null) return Answer.invalid("没有回答子问题 " + questionId);
            String optionId = answer.getString("optionId");
            if (optionLabel(question, optionId) == null) {
                return Answer.invalid("子问题 " + questionId + " 没有选项 " + optionId);
            }
            JSONArray reasons = answer.getJSONArray("reasons");
            if (reasons == null || reasons.isEmpty() || reasons.size() > 3) {
                return Answer.invalid("子问题 " + questionId + " 的理由必须是 1–3 条");
            }
            JSONArray cleanReasons = new JSONArray();
            for (Object reasonItem : reasons) {
                if (!(reasonItem instanceof JSONObject)) return Answer.invalid("理由格式不对");
                JSONObject reason = (JSONObject) reasonItem;
                String text = reason.getString("text");
                if (blank(text)) return Answer.invalid("子问题 " + questionId + " 有空理由");
                JSONArray cited = reason.getJSONArray("factIds") == null ? new JSONArray() : reason.getJSONArray("factIds");
                for (Object id : cited) {
                    if (!factIds.contains(String.valueOf(id))) {
                        return Answer.invalid("引用了不存在的事实编号 " + id);
                    }
                }
                String gated = gateNumbers(text, corpora);
                if (gated == null) return Answer.invalid(unsupportedNumbers(text, corpora));
                JSONObject cleanReason = new JSONObject(true);
                cleanReason.put("text", gated);
                cleanReason.put("factIds", cited);
                cleanReasons.add(cleanReason);
            }
            String falsifier = answer.getString("falsifier");
            String gatedFalsifier = blank(falsifier) ? null : gateNumbers(falsifier, corpora);
            if (!blank(falsifier) && gatedFalsifier == null) return Answer.invalid(unsupportedNumbers(falsifier, corpora));
            JSONObject clean = new JSONObject(true);
            clean.put("questionId", questionId);
            clean.put("optionId", optionId);
            clean.put("reasons", cleanReasons);
            clean.put("falsifier", gatedFalsifier);
            normalized.add(clean);
        }
        if (!byQuestion.isEmpty()) return Answer.invalid("回答了问题卡里没有的子问题 " + byQuestion.keySet());
        return new Answer(normalized, null);
    }

    /** Low-confidence numbers are labelled; returns null when a number has no source in the fact base. */
    private static String gateNumbers(String text, String[] corpora) {
        ReviewedNumberGate.Audit audit = ReviewedNumberGate.audit(text, corpora[0], corpora[1], null);
        return audit.hasUnsupported() ? null : audit.label(text);
    }

    private static String unsupportedNumbers(String text, String[] corpora) {
        return ReviewedNumberGate.rewriteInstruction(ReviewedNumberGate.audit(text, corpora[0], corpora[1], null));
    }

    private static String[] corpora(JSONArray facts) {
        StringBuilder verified = new StringBuilder();
        StringBuilder provisional = new StringBuilder();
        for (Object item : facts) {
            JSONObject fact = (JSONObject) item;
            StringBuilder target = "F".equals(fact.getString("tier")) ? verified
                    : "L".equals(fact.getString("tier")) ? provisional : null;
            if (target == null) continue;
            target.append(fact.getString("statement")).append('\n');
            if (fact.get("value") != null) {
                target.append(fact.get("value")).append(fact.getString("unit") == null ? "" : fact.getString("unit"))
                        .append('\n');
            }
        }
        return new String[]{verified.toString(), provisional.toString()};
    }

    private static Set<String> factIds(JSONArray facts) {
        Set<String> ids = new HashSet<>();
        for (Object item : facts) ids.add(((JSONObject) item).getString("id"));
        return ids;
    }

    // ---- P7 program summary ----

    private JSONObject result(Long meetingId, Long epoch, JSONObject card, JSONArray facts, JSONArray answers) {
        JSONObject stored = loadObject(meetingId, RESULT, MEETING_LEVEL);
        if (stored != null) return stored;
        List<JSONObject> answered = new ArrayList<>();
        int skipped = 0;
        for (Object item : answers) {
            JSONObject answer = (JSONObject) item;
            if (answer.getBooleanValue("skipped")) skipped++;
            else answered.add(answer);
        }
        int total = answered.size();
        double threshold = aiModelProperties.getMeeting().getConsensusThreshold();
        JSONArray conclusions = new JSONArray();
        JSONArray disputes = new JSONArray();
        JSONArray insufficient = new JSONArray();
        for (Object item : card.getJSONArray("subQuestions")) {
            JSONObject question = (JSONObject) item;
            String questionId = question.getString("id");
            Map<String, JSONArray> byOption = new LinkedHashMap<>();
            for (Object option : question.getJSONArray("options")) {
                byOption.put(((JSONObject) option).getString("id"), new JSONArray());
            }
            for (JSONObject answer : answered) {
                for (Object choiceItem : answer.getJSONArray("answers")) {
                    JSONObject choice = (JSONObject) choiceItem;
                    if (!questionId.equals(choice.getString("questionId"))) continue;
                    JSONObject supporter = new JSONObject(true);
                    supporter.put("memberId", answer.get("memberId"));
                    supporter.put("memberName", answer.getString("memberName"));
                    supporter.put("reasons", choice.getJSONArray("reasons"));
                    supporter.put("falsifier", choice.getString("falsifier"));
                    byOption.get(choice.getString("optionId")).add(supporter);
                }
            }
            String topOption = null;
            for (Map.Entry<String, JSONArray> entry : byOption.entrySet()) {
                if (topOption == null || entry.getValue().size() > byOption.get(topOption).size()) {
                    topOption = entry.getKey();
                }
            }
            int support = topOption == null ? 0 : byOption.get(topOption).size();
            if (total < 2) {
                JSONObject line = questionLine(question, total);
                line.put("reason", "有效作答少于 2 人，无法判断是否形成共识");
                insufficient.add(line);
            } else if (support >= Math.ceil(threshold * total)) {
                JSONObject line = questionLine(question, total);
                line.put("optionId", topOption);
                line.put("optionLabel", optionLabel(question, topOption));
                line.put("support", support);
                line.put("members", byOption.get(topOption));
                conclusions.add(line);
            } else {
                JSONObject line = questionLine(question, total);
                JSONArray positions = new JSONArray();
                for (Map.Entry<String, JSONArray> entry : byOption.entrySet()) {
                    if (entry.getValue().isEmpty()) continue;
                    JSONObject position = new JSONObject(true);
                    position.put("optionId", entry.getKey());
                    position.put("optionLabel", optionLabel(question, entry.getKey()));
                    position.put("members", entry.getValue());
                    positions.add(position);
                }
                line.put("positions", positions);
                disputes.add(line);
            }
        }
        JSONArray openQuestions = new JSONArray();
        for (Object item : facts) {
            JSONObject fact = (JSONObject) item;
            if ("G".equals(fact.getString("tier"))) openQuestions.add(fact.getString("question"));
        }
        JSONObject result = new JSONObject(true);
        result.put("answeredCount", total);
        result.put("skippedCount", skipped);
        result.put("conclusions", conclusions);
        result.put("disputes", disputes);
        result.put("insufficient", insufficient);
        result.put("openQuestions", openQuestions);
        return save(meetingId, RESULT, MEETING_LEVEL, epoch, result);
    }

    private static JSONObject questionLine(JSONObject question, int total) {
        JSONObject line = new JSONObject(true);
        line.put("questionId", question.getString("id"));
        line.put("title", question.getString("title"));
        line.put("total", total);
        return line;
    }

    private static String optionLabel(JSONObject question, String optionId) {
        for (Object item : question.getJSONArray("options")) {
            JSONObject option = (JSONObject) item;
            if (Objects.equals(option.getString("id"), optionId)) return option.getString("label");
        }
        return null;
    }

    private void conclude(Long meetingId, Long epoch, JSONObject card, JSONArray facts, JSONObject result) {
        AiMeetingRoom room = activeRoom(meetingId, epoch);
        if (room == null) return;
        List<String> conclusionLines = new ArrayList<>();
        for (Object item : result.getJSONArray("conclusions")) {
            JSONObject line = (JSONObject) item;
            conclusionLines.add(line.getString("title") + " → " + line.getString("optionLabel")
                    + "（" + line.getIntValue("support") + "/" + line.getIntValue("total") + "）");
        }
        List<String> disputeLines = new ArrayList<>();
        for (Object item : result.getJSONArray("disputes")) {
            JSONObject line = (JSONObject) item;
            List<String> sides = new ArrayList<>();
            for (Object position : line.getJSONArray("positions")) {
                JSONObject json = (JSONObject) position;
                sides.add(json.getString("optionLabel") + " " + json.getJSONArray("members").size() + " 人");
            }
            disputeLines.add(line.getString("title") + "（" + String.join("，", sides) + "）");
        }
        String conclusion = (conclusionLines.isEmpty() ? "未形成多方一致的结论。" : "结论：" + String.join("；", conclusionLines) + "。")
                + (disputeLines.isEmpty() ? "" : "分歧：" + String.join("；", disputeLines) + "。");
        List<String> basis = new ArrayList<>();
        for (Object item : facts) {
            JSONObject fact = (JSONObject) item;
            if ("F".equals(fact.getString("tier"))) basis.add(fact.getString("id") + " " + fact.getString("statement"));
        }
        List<String> openQuestions = result.getJSONArray("openQuestions").toJavaList(String.class);

        JSONObject artifact = new JSONObject(true);
        artifact.put("topicOverview", card.getString("topic"));
        artifact.put("projectKey", room.getProjectKey());
        artifact.put("projectContext", room.getProjectContext());
        artifact.put("consensusPath", "圆桌 v2：问题卡 → 事实底稿 → 议员背靠背作答 → 程序汇总");
        artifact.put("finalDecision", conclusion);
        artifact.put("keyViewpoints", conclusionLines);
        artifact.put("majorDisputes", disputeLines);
        artifact.put("decisionBasis", basis);
        artifact.put("openQuestions", openQuestions);
        artifact.put("nextActions", new JSONArray());

        Date concludedAt = new Date();
        int round = room.getCurrentRound() == null ? 0 : room.getCurrentRound();
        reviewedFinalizationWriter.concludeIfCurrent(meetingId, epoch, room.getStatus(), round,
                room.getConclusionHistory(), artifact.toJSONString(),
                conclusionHistory(room, conclusion, round, concludedAt), conclusion, concludedAt);

        MeetingDetailVO.DecisionArtifactVO trackingCard = new MeetingDetailVO.DecisionArtifactVO();
        trackingCard.setTopicOverview(card.getString("topic"));
        trackingCard.setFinalDecision(conclusion);
        trackingCard.setConsensusPath(artifact.getString("consensusPath"));
        trackingCard.setKeyViewpoints(conclusionLines);
        trackingCard.setMajorDisputes(disputeLines);
        trackingCard.setDecisionBasis(basis);
        trackingCard.setOpenQuestions(openQuestions);
        room.setFinalConclusion(conclusion);
        room.setConclusionTime(concludedAt);
        room.setStatus(MeetingStatusEnum.CONCLUDED.getCode());
        workflowService.markMeetingConcluded(room, trackingCard);
    }

    private static String conclusionHistory(AiMeetingRoom room, String conclusion, int round, Date at) {
        JSONArray history;
        try {
            history = room.getConclusionHistory() == null ? new JSONArray() : JSON.parseArray(room.getConclusionHistory());
        } catch (RuntimeException invalid) {
            history = new JSONArray();
        }
        JSONObject event = new JSONObject(true);
        event.put("versionNo", history.size() + 1);
        event.put("eventType", "CONCLUDED");
        event.put("conclusion", conclusion);
        event.put("triggerReason", "圆桌 v2 程序汇总");
        event.put("roundNumber", round);
        event.put("eventTime", at.getTime());
        history.add(event);
        return history.toJSONString();
    }

    // ---- run state and checkpoints ----

    private AiMeetingRoom activeRoom(Long meetingId, Long epoch) {
        AiMeetingRoom room = roomMapper.selectById(meetingId);
        if (room == null) return null;
        boolean active = MeetingStatusEnum.IN_PROGRESS.getCode().equals(room.getStatus())
                || MeetingStatusEnum.REOPENED.getCode().equals(room.getStatus());
        long current = room.getRunEpoch() == null ? 0L : room.getRunEpoch();
        return active && (epoch == null || current == epoch) ? room : null;
    }

    private void pause(Long meetingId, Long epoch, String reason) {
        AiMeetingRoom room = activeRoom(meetingId, epoch);
        if (room == null || epoch == null) return;
        try {
            if (roomMapper.updateStatusIfCurrentAndEpoch(meetingId, room.getStatus(), epoch,
                    MeetingStatusEnum.PAUSED.getCode()) == 1) {
                workflowService.markReviewedEvidenceBlocked(meetingId, reason);
            }
        } catch (RuntimeException pauseFailure) {
            log.warn("Roundtable v2 meeting {} could not be paused: {}", meetingId, pauseFailure.getMessage());
        }
    }

    private JSONObject loadObject(Long meetingId, String kind, long memberId) {
        AiMeetingV2Record stored = recordMapper.selectOne(meetingId, kind, memberId);
        return stored == null ? null : JSON.parseObject(stored.getPayload());
    }

    /** First writer wins; a concurrent duplicate returns what is already stored. */
    @SuppressWarnings("unchecked")
    private <T> T save(Long meetingId, String kind, long memberId, Long epoch, T payload) {
        AiMeetingV2Record record = new AiMeetingV2Record();
        record.setMeetingId(meetingId);
        record.setKind(kind);
        record.setMemberId(memberId);
        record.setPayload(JSON.toJSONString(payload));
        record.setRunEpoch(epoch);
        try {
            recordMapper.insert(record);
            return payload;
        } catch (DuplicateKeyException alreadyStored) {
            String existing = recordMapper.selectOne(meetingId, kind, memberId).getPayload();
            return (T) (payload instanceof JSONArray ? JSON.parseArray(existing) : JSON.parseObject(existing));
        }
    }

    private static JSONObject parseObject(String reply) {
        if (reply == null) return null;
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return JSON.parseObject(reply.substring(start, end + 1));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }

    private static final class Answer {
        private final JSONArray answers;
        private final String error;

        private Answer(JSONArray answers, String error) {
            this.answers = answers;
            this.error = error;
        }

        private static Answer invalid(String error) {
            return new Answer(null, error);
        }
    }

    /** A stage cannot continue without the user, so the meeting is paused with a visible reason. */
    private static final class Blocked extends RuntimeException {
        private Blocked(String reason) {
            super(reason);
        }
    }
}
