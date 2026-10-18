package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingV2RecordMapper;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.dto.ProgramTrackingItemSpec;
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
import java.util.LinkedHashSet;
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
    static final String AGENDA = "AGENDA";
    static final String CROSS_EXAM = "CROSS_EXAM_";
    static final String RED_TEAM = "RED_TEAM";
    static final String FINAL_ANSWER = "FINAL_ANSWER";
    static final String SECOND_ROUND = "_2";
    static final String SECOND_CROSS_EXAM = "CROSS_EXAM_R2_";
    static final String CLASSIFY = "CLASSIFY";
    static final String TOP_UP = "TOP_UP";
    static final String OUTCOME = "OUTCOME_Q";
    static final String SOLO = "SOLO";
    static final String RESEARCH = "RESEARCH";
    /** A safety stop against endless follow-ups; the number of questions per round is not limited. */
    private static final int MAX_RESEARCH_ROUNDS = 3;
    /**
     * Research still running, per meeting and member, so the page can follow it question by question. Only
     * a view aid: the stored RESEARCH record is the truth and replaces it once the member is done.
     */
    private final Map<Long, Map<Long, JSONObject>> liveResearch = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern NON_ANSWER_OPTION =
            java.util.regex.Pattern.compile("信息不足|难以判断|无法判断|无法确定");
    /** Marks a skipped answer that is not stored until all members have been asked. */
    private static final String UNSETTLED = "unsettled";
    private static final String PROBABILITY = "PROBABILITY";
    private static final int DEFAULT_HORIZON_TRADING_DAYS = 60;
    /** A member is in the minority when any interval differs from the pooled probability by this much. */
    private static final double MINORITY_DEVIATION = 0.25;
    /** Evidence top-up for fact disputes is capped per meeting. */
    private static final int TOP_UP_BUDGET = 3;
    private static final Set<String> DISPUTE_TYPES = Set.of("FACT", "INTERPRETATION", "ASSUMPTION", "RISK_PREFERENCE");
    /** At most this many of the most split sub-questions are cross-examined. */
    private static final int MAX_CROSS_EXAMS = 2;

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
    @Autowired(required = false) private List<MeetingDomain> domains;

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

    /**
     * On request, a single analyst answers the same card and pre-meeting fact base in one pass with the
     * moderator's model: a control for whether the roundtable adds anything. It never changes the meeting.
     * Only a valid answer is stored; failures are reported so the request can simply be repeated.
     */
    public JSONObject solo(Long meetingId) {
        JSONObject stored = loadObject(meetingId, SOLO, MEETING_LEVEL);
        if (stored != null) return stored;
        AiMeetingV2Record summary = recordMapper.selectOne(meetingId, RESULT, MEETING_LEVEL);
        AiMeetingV2Record factBase = recordMapper.selectOne(meetingId, FACT_BASE, MEETING_LEVEL);
        if (summary == null || factBase == null) throw new IllegalStateException("会议还没有结束，暂不能生成单人对照");
        JSONObject card = withOutcome(loadObject(meetingId, ISSUE_CARD, MEETING_LEVEL),
                loadObject(meetingId, OUTCOME, MEETING_LEVEL));
        JSONArray facts = JSON.parseArray(factBase.getPayload());
        String model = orchestrator.resolveMeetingModelType(meetingId);
        String system = "你是一位综合分析师，独立完成这份研判：同时考虑基本面、估值、资金与技术、行业与宏观和主要风险，"
                + "自己权衡正反两面后给出结论。你只能依据问题卡和事实底稿作答。只输出 JSON。";
        String prompt = answerPrompt(card, facts);
        String[] corpora = corpora(facts, card);
        Set<String> factIds = factIds(facts);
        Answer parsed;
        String rewriteReason = null;
        try {
            try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, null, MeetingCallContext.SOLO)) {
                parsed = parseAnswer(call(model, "单人分析", system, prompt), card, factIds, corpora);
            }
            if (parsed.error != null) {
                rewriteReason = parsed.error;
                try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                        meetingId, 1, null, MeetingCallContext.SOLO_REWRITE)) {
                    parsed = parseAnswer(call(model, "单人分析", system,
                            prompt + "\n\n【上一版作答不合格】" + parsed.error + "。请按要求重新输出完整 JSON。"),
                            card, factIds, corpora);
                }
            }
        } catch (ModelUnavailable unavailable) {
            throw new IllegalStateException(unavailable.getMessage());
        }
        if (parsed.error != null) throw new IllegalStateException("单人答卷重写后仍不合格：" + parsed.error);
        JSONObject record = new JSONObject(true);
        record.put("memberName", "单人分析");
        record.put("modelType", model);
        record.put("skipped", false);
        record.put("answers", parsed.answers);
        if (rewriteReason != null) record.put("rewriteReason", rewriteReason);
        record.put("generatedAt", java.time.Instant.now().toString());
        return save(meetingId, SOLO, MEETING_LEVEL, summary.getRunEpoch(), record);
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
            else if (AGENDA.equals(record.getKind())) view.setAgenda(JSON.parseObject(record.getPayload()));
            else if (record.getKind().startsWith(SECOND_CROSS_EXAM)) view.getSecondCrossExams().add(JSON.parseObject(record.getPayload()));
            else if (record.getKind().startsWith(CROSS_EXAM)) view.getCrossExams().add(JSON.parseObject(record.getPayload()));
            else if (RED_TEAM.equals(record.getKind())) view.setRedTeam(JSON.parseObject(record.getPayload()));
            else if ((FINAL_ANSWER + SECOND_ROUND).equals(record.getKind())) view.getSecondFinalAnswers().add(JSON.parseObject(record.getPayload()));
            else if (FINAL_ANSWER.equals(record.getKind())) view.getFinalAnswers().add(JSON.parseObject(record.getPayload()));
            else if (CLASSIFY.equals(record.getKind())) view.setClassification(JSON.parseObject(record.getPayload()));
            else if (TOP_UP.equals(record.getKind())) view.setTopUp(JSON.parseObject(record.getPayload()));
            else if (OUTCOME.equals(record.getKind())) view.setOutcomeQuestion(JSON.parseObject(record.getPayload()));
            else if (SOLO.equals(record.getKind())) view.setSolo(JSON.parseObject(record.getPayload()));
            else if (RESEARCH.equals(record.getKind())) view.getResearch().add(JSON.parseObject(record.getPayload()));
            else if (RoundtableV2OutcomeJudge.OUTCOME_JUDGEMENT.equals(record.getKind())) {
                view.setOutcomeJudgement(JSON.parseObject(record.getPayload()));
            }
        }
        // A member still researching is shown from the live progress until its record is stored.
        Map<Long, JSONObject> live = liveResearch.get(meetingId);
        if (live != null) {
            Set<Long> done = new HashSet<>();
            for (Object item : view.getResearch()) done.add(((JSONObject) item).getLong("memberId"));
            for (Map.Entry<Long, JSONObject> entry : live.entrySet()) {
                if (!done.contains(entry.getKey())) view.getResearch().add(entry.getValue());
            }
        }
        // The fact list shows every fact: the shared base followed by each member's own research.
        for (Object item : view.getResearch()) {
            JSONArray own = ((JSONObject) item).getJSONArray("facts");
            if (own != null) view.getFacts().addAll(own);
        }
        return view;
    }

    public void deleteByMeetingId(Long meetingId) {
        liveResearch.remove(meetingId);
        recordMapper.deleteByMeetingId(meetingId);
    }

    private void runStages(Long meetingId, Long epoch) {
        AiMeetingRoom room = activeRoom(meetingId, epoch);
        if (room == null) return;
        JSONObject card = issueCard(meetingId, epoch, room);
        if (activeRoom(meetingId, epoch) == null) return;
        JSONArray facts = factBase(meetingId, epoch, card);
        if (facts == null) return;
        card = withOutcome(card, loadObject(meetingId, OUTCOME, MEETING_LEVEL));
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        Map<Long, AiMeetingMember> membersById = new LinkedHashMap<>();
        JSONArray answers = new JSONArray();
        List<JSONObject> unsettled = new ArrayList<>();
        // Every fact found so far: the shared base, then each member's own research in member order.
        JSONArray pool = new JSONArray();
        pool.addAll(facts);
        for (AiMeetingMember member : members) {
            if (activeRoom(meetingId, epoch) == null) return;
            membersById.put(member.getId(), member);
            JSONObject research = research(meetingId, epoch, card, facts, pool, member);
            if (research == null) return;
            pool.addAll(research.getJSONArray("facts"));
            JSONObject answer = answer(meetingId, epoch, card, visibleFacts(facts, pool, research), member);
            if (answer.getBooleanValue(UNSETTLED)) unsettled.add(answer);
            answers.add(answer);
        }
        settleSkipped(meetingId, epoch, answers, unsettled);
        // From the cross-examination on, everyone works from every fact anyone found.
        facts = pool;
        JSONArray firstCrossExams = new JSONArray();
        JSONArray finalAnswers = deliberate(meetingId, epoch, 1, card, facts, answers, membersById, null, firstCrossExams);
        if (finalAnswers == null) return;

        JSONArray remaining = summarize(card, finalAnswers).getJSONArray("disputes");
        JSONObject classification = null;
        JSONObject topUp = null;
        JSONArray allFacts = facts;
        int loops = 1;
        if (!remaining.isEmpty()) {
            if (activeRoom(meetingId, epoch) == null) return;
            classification = classify(meetingId, epoch, card, facts, remaining, firstCrossExams);
            topUp = topUp(meetingId, epoch, card, facts, classification);
            if (topUp == null) return;
            allFacts = new JSONArray();
            allFacts.addAll(facts);
            allFacts.addAll(topUp.getJSONArray("added"));
            Set<String> reopened = reopenedByEvidence(topUp);
            if (!reopened.isEmpty()) {
                JSONArray secondRound = deliberate(meetingId, epoch, 2, card, allFacts, finalAnswers, membersById,
                        reopened, new JSONArray());
                if (secondRound == null) return;
                finalAnswers = secondRound;
                loops = 2;
            }
        }
        JSONObject result = result(meetingId, epoch, card, allFacts, finalAnswers, answers, classification, topUp, loops);
        conclude(meetingId, epoch, card, allFacts, result);
    }

    /**
     * One deliberation round: agenda, paired cross-examination, (first round only) the consensus red team,
     * then the anonymous re-estimate. Returns the answers after the round, or null if the meeting stopped.
     */
    private JSONArray deliberate(Long meetingId, Long epoch, int round, JSONObject card, JSONArray facts,
                                 JSONArray previous, Map<Long, AiMeetingMember> membersById,
                                 Set<String> onlyQuestions, JSONArray crossExamsOut) {
        JSONObject agenda = agenda(meetingId, epoch, round, card, previous, onlyQuestions);
        JSONArray disputes = agenda.getJSONArray("disputes");
        for (int i = 0; i < disputes.size(); i++) {
            if (activeRoom(meetingId, epoch) == null) return null;
            String kind = (round == 1 ? CROSS_EXAM : SECOND_CROSS_EXAM) + (i + 1);
            crossExamsOut.add(crossExam(meetingId, epoch, kind, card, facts, disputes.getJSONObject(i), membersById));
        }
        JSONObject redTeam = null;
        if (agenda.getJSONObject("redTeam") != null) {
            if (activeRoom(meetingId, epoch) == null) return null;
            redTeam = redTeam(meetingId, epoch, card, facts, agenda.getJSONObject("redTeam"), membersById);
        }
        if (disputes.isEmpty() && redTeam == null) return previous;
        JSONArray after = new JSONArray();
        String kind = round == 1 ? FINAL_ANSWER : FINAL_ANSWER + SECOND_ROUND;
        for (Object item : previous) {
            if (activeRoom(meetingId, epoch) == null) return null;
            JSONObject before = (JSONObject) item;
            after.add(finalAnswer(meetingId, epoch, kind, card, facts, previous, crossExamsOut, redTeam,
                    before, membersById.get(before.getLong("memberId"))));
        }
        return after;
    }

    // ---- P0 issue card ----

    private JSONObject issueCard(Long meetingId, Long epoch, AiMeetingRoom room) {
        JSONObject stored = loadObject(meetingId, ISSUE_CARD, MEETING_LEVEL);
        if (stored != null) return stored;
        String model = orchestrator.resolveMeetingModelType(meetingId);
        String system = "你是中立的会议主持人，只负责把议题拆成可以选边的子问题并列出取证题，"
                + "不表达立场、不打分。只输出 JSON。";
        String asOf = LocalDate.now(MARKET_ZONE).toString();
        String prompt = issueCardPrompt(room, asOf, domainHints(), domainFields());
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
        int horizonDays = card.getIntValue("horizonTradingDays");
        normalized.put("horizonTradingDays", horizonDays >= 20 && horizonDays <= 250
                ? horizonDays : DEFAULT_HORIZON_TRADING_DAYS);
        normalized.put("asOf", asOf);
        normalized.put("subQuestions", card.getJSONArray("subQuestions"));
        JSONArray evidenceQuestions = card.getJSONArray("evidenceQuestions");
        normalized.put("evidenceQuestions", evidenceQuestions == null ? new JSONArray() : evidenceQuestions);
        normalized.put("referenceClass", card.get("referenceClass"));
        for (MeetingDomain domain : domains()) domain.normalizeIssueCard(card, normalized);
        return save(meetingId, ISSUE_CARD, MEETING_LEVEL, epoch, normalized);
    }

    private static String issueCardPrompt(AiMeetingRoom room, String asOf, String domainHints, String domainFields) {
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
                .append("选项要能让议员明确选边。不要设置“信息不足”“难以判断”之类的选项：")
                .append("证据不足时由议员在理由里说明，程序会标出证据不足的共识。\n");
        sb.append("再列出不超过 8 道需要联网核实的取证题，每题只问一个可以核实的事实，多空两方面都要覆盖；")
                .append("只问截至日期前已经公开的信息，不要问尚未发布的数据（例如还没披露的财报）。\n");
        sb.append("如果有合适的参照类（过去的类似情况），写在 referenceClass，否则写 null。\n");
        sb.append(domainHints);
        sb.append("把展望期折算成交易日数写在 horizonTradingDays（20–250，三个月约 60）。\n");
        sb.append("只输出 JSON：{\"topic\":\"\",\"horizon\":\"\",\"horizonTradingDays\":60,\"subQuestions\":[{\"id\":\"Q1\",\"title\":\"\",")
                .append("\"options\":[{\"id\":\"A\",\"label\":\"\"}]}],\"evidenceQuestions\":[\"\"],\"referenceClass\":null")
                .append(domainFields).append("}");
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
                if (NON_ANSWER_OPTION.matcher(option.getString("label")).find()) {
                    return "子问题 " + question.getString("id") + " 的选项「" + option.getString("label")
                            + "」不是一个可以选边的判断，不要设置信息不足、难以判断之类的选项";
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
        // The domain's structured source comes first; reviewed search covers what it cannot.
        MeetingDomain domain = domainFor(card);
        if (domain != null) {
            MeetingDomain.DomainEvidence evidence = domain.evidence(card);
            if (evidence.outcomeQuestion != null) save(meetingId, OUTCOME, MEETING_LEVEL, epoch, evidence.outcomeQuestion);
            for (JSONObject structured : evidence.facts) {
                String tier = structured.getString("tier");
                JSONObject fact = new JSONObject(true);
                fact.put("id", "G".equals(tier) ? "G-" + (++gaps) : "F-" + (++verified));
                fact.putAll(structured);
                facts.add(fact);
            }
        }
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

    private List<MeetingDomain> domains() {
        return domains == null ? List.of() : domains;
    }

    private MeetingDomain domainFor(JSONObject card) {
        for (MeetingDomain domain : domains()) {
            if (domain.appliesTo(card)) return domain;
        }
        return null;
    }

    /** Extra output fields the domains read, shown in the JSON template so models do not drop them. */
    private String domainFields() {
        StringBuilder fields = new StringBuilder();
        for (MeetingDomain domain : domains()) {
            String field = domain.issueCardField();
            if (!blank(field)) fields.append(",").append(field);
        }
        return fields.toString();
    }

    private String domainHints() {
        StringBuilder hints = new StringBuilder();
        for (MeetingDomain domain : domains()) {
            String hint = domain.issueCardHint();
            if (!blank(hint)) hints.append(hint).append("\n");
        }
        return hints.toString();
    }

    // ---- P2 back-to-back answers ----

    // ---- P2a each member's own research ----

    /**
     * Before answering, a member asks for whatever its lens still needs, as many questions as it wants, for up
     * to three rounds. Questions already answered by an existing fact (any member's) reuse it; only the rest
     * go through reviewed search. Returns null when the meeting stopped.
     */
    private JSONObject research(Long meetingId, Long epoch, JSONObject card, JSONArray shared, JSONArray pool,
                                AiMeetingMember member) {
        JSONObject stored = loadObject(meetingId, RESEARCH, member.getId());
        if (stored != null) return stored;
        JSONArray own = new JSONArray();
        List<String> visible = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        JSONArray rounds = new JSONArray();
        int[] next = {maxIndex(pool, "F"), maxIndex(pool, "L"), maxIndex(pool, "G")};
        for (int round = 1; round <= MAX_RESEARCH_ROUNDS; round++) {
            if (activeRoom(meetingId, epoch) == null) return null;
            JSONArray seen = new JSONArray();
            seen.addAll(shared);
            for (Object item : concat(pool, own)) {
                JSONObject fact = (JSONObject) item;
                if (visible.contains(fact.getString("id")) && !seen.contains(fact)) seen.add(fact);
            }
            List<String> questions;
            try {
                questions = researchQuestions(meetingId, card, seen, asked, member);
            } catch (ModelUnavailable unavailable) {
                // Not stored: answering will hit the same failure and the meeting decides there; a resume retries.
                return researchRecord(member, rounds, own, visible);
            }
            if (questions.isEmpty()) break;
            asked.addAll(questions);
            Map<Integer, List<String>> existing = matchExisting(meetingId, concat(pool, own), questions);
            JSONArray lines = new JSONArray();
            JSONObject roundRecord = new JSONObject(true);
            roundRecord.put("round", round);
            roundRecord.put("questions", lines);
            for (int i = 0; i < questions.size(); i++) {
                String question = questions.get(i);
                JSONObject line = new JSONObject(true);
                line.put("question", question);
                List<String> reused = existing.get(i + 1);
                if (reused != null) {
                    line.put("reused", reused);
                    for (String id : reused) if (!visible.contains(id)) visible.add(id);
                } else {
                    if (activeRoom(meetingId, epoch) == null) return null;
                    JSONArray found = searchFor(meetingId, card, question, member, next);
                    own.addAll(found);
                    JSONArray ids = new JSONArray();
                    for (Object item : found) {
                        ids.add(((JSONObject) item).getString("id"));
                        visible.add(((JSONObject) item).getString("id"));
                    }
                    line.put("searched", ids);
                }
                lines.add(line);
                publishLive(meetingId, member, rounds, roundRecord, own, visible);
            }
            rounds.add(roundRecord);
        }
        JSONObject saved = save(meetingId, RESEARCH, member.getId(), epoch, researchRecord(member, rounds, own, visible));
        Map<Long, JSONObject> live = liveResearch.get(meetingId);
        if (live != null) live.remove(member.getId());
        return saved;
    }

    private void publishLive(Long meetingId, AiMeetingMember member, JSONArray rounds, JSONObject current,
                             JSONArray own, List<String> visible) {
        JSONArray soFar = new JSONArray();
        soFar.addAll(rounds);
        soFar.add(current);
        JSONObject snapshot = JSON.parseObject(JSON.toJSONString(researchRecord(member, soFar, own, visible)));
        snapshot.put("inProgress", true);
        liveResearch.computeIfAbsent(meetingId, id -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(member.getId(), snapshot);
    }

    private static JSONObject researchRecord(AiMeetingMember member, JSONArray rounds, JSONArray own, List<String> visible) {
        JSONObject record = new JSONObject(true);
        record.put("memberId", member.getId());
        record.put("memberName", member.getMemberName());
        record.put("rounds", rounds);
        record.put("facts", own);
        record.put("visibleFactIds", visible);
        return record;
    }

    private List<String> researchQuestions(Long meetingId, JSONObject card, JSONArray seen, List<String> asked,
                                           AiMeetingMember member) {
        String system = memberSystem(member) + "作答之前，你可以按自己的分析视角自行查资料。只输出 JSON。";
        StringBuilder prompt = new StringBuilder(cardAndFacts(card, seen));
        if (!asked.isEmpty()) prompt.append("\n【你已经查过的问题】\n").append(String.join("\n", asked)).append("\n");
        prompt.append("\n【查资料】从你的分析视角出发，为了回答上面的子问题，你还需要查哪些资料？需要多少就列多少；")
                .append("每题只问一个可以核实的事实，只问截至日期前已经公开的信息，不要重复上面已有的资料和你查过的问题。")
                .append("已经足够就返回空数组。只输出 JSON：{\"questions\":[\"\"]}");
        String reply;
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, 1, member.getId(), MeetingCallContext.RESEARCH_PLAN)) {
            reply = call(member.getModelType(), member.getMemberName(), system, prompt.toString());
        }
        JSONObject root = parseObject(reply);
        JSONArray list = root == null ? null : root.getJSONArray("questions");
        List<String> questions = new ArrayList<>();
        for (int i = 0; list != null && i < list.size(); i++) {
            String question = list.getString(i);
            if (!blank(question) && !asked.contains(question.trim()) && !questions.contains(question.trim())) {
                questions.add(question.trim());
            }
        }
        return questions;
    }

    /**
     * Which new questions an existing verified or low-confidence fact already answers, by 1-based index. The
     * judgment is strict and only accepts ids of real F/L facts; anything doubtful is searched.
     */
    private Map<Integer, List<String>> matchExisting(Long meetingId, JSONArray pool, List<String> questions) {
        Map<String, JSONObject> usable = new LinkedHashMap<>();
        for (Object item : pool) {
            JSONObject fact = (JSONObject) item;
            String tier = fact.getString("tier");
            if (("F".equals(tier) || "L".equals(tier)) && !blank(fact.getString("statement"))) {
                usable.put(fact.getString("id"), fact);
            }
        }
        Map<Integer, List<String>> matches = new LinkedHashMap<>();
        if (usable.isEmpty()) return matches;
        StringBuilder prompt = new StringBuilder("【现有资料】\n");
        for (JSONObject fact : usable.values()) {
            prompt.append(fact.getString("id")).append("：").append(fact.getString("statement")).append("\n");
        }
        prompt.append("\n【新问题】\n");
        for (int i = 0; i < questions.size(); i++) prompt.append(i + 1).append(". ").append(questions.get(i)).append("\n");
        prompt.append("\n对每个新问题，判断现有资料里是否已经有能直接回答它的事实：必须包含所问的具体数值、日期或名称，")
                .append("而且时间口径一致。只有完全能回答才列出编号；拿不准就不要列。只输出 JSON：")
                .append("{\"matches\":[{\"index\":1,\"factIds\":[\"F-1\"]}]}");
        String system = "你是本场会议的资料管理员，只判断现有资料是否已经能直接回答新问题，不回答问题本身。只输出 JSON。";
        String reply;
        try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.RESEARCH_MATCH)) {
            reply = orchestrator.callModelStrict(orchestrator.resolveMeetingModelType(meetingId), system,
                    prompt.toString(), null);
        } catch (RuntimeException unavailable) {
            return matches;  // Without a judgment everything is searched: slower, never less accurate.
        }
        JSONObject root = parseObject(reply);
        JSONArray list = root == null ? null : root.getJSONArray("matches");
        for (int i = 0; list != null && i < list.size(); i++) {
            JSONObject match = list.getJSONObject(i);
            if (match == null) continue;
            int index = match.getIntValue("index");
            JSONArray ids = match.getJSONArray("factIds");
            if (index < 1 || index > questions.size() || ids == null || ids.isEmpty()) continue;
            List<String> valid = new ArrayList<>();
            boolean allReal = true;
            for (Object id : ids) {
                if (usable.containsKey(String.valueOf(id))) valid.add(String.valueOf(id));
                else allReal = false;
            }
            if (allReal && !valid.isEmpty()) matches.put(index, valid);
        }
        return matches;
    }

    /** Reviewed search for one member question; new facts continue the meeting's F/L/G numbering. */
    private JSONArray searchFor(Long meetingId, JSONObject card, String question, AiMeetingMember member, int[] next) {
        JSONArray found = new JSONArray();
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, 1, member.getId(), MeetingCallContext.EVIDENCE_SEARCH)) {
            MeetingReviewedEvidenceService.ReviewedEvidence evidence = resolveEvidence(meetingId, card.getString("asOf"), question);
            JSONArray claims = evidence.packet.getJSONArray("claims");
            JSONArray candidates = evidence.packet.getJSONArray("provisionalClaims");
            for (int i = 0; claims != null && i < claims.size(); i++) {
                found.add(fact("F-" + (++next[0]), "F", question, claims.getJSONObject(i)));
            }
            for (int i = 0; candidates != null && i < candidates.size(); i++) {
                found.add(fact("L-" + (++next[1]), "L", question, candidates.getJSONObject(i)));
            }
            if (found.isEmpty()) found.add(gapFact(++next[2], question, "N8N_" + evidence.packet.getString("status")));
        } catch (MeetingReviewedEvidenceService.EvidenceRejected rejected) {
            found.add(gapFact(++next[2], question, rejected.getMessage()));
        }
        for (Object item : found) ((JSONObject) item).put("askedBy", member.getMemberName());
        return found;
    }

    /** What a member answers from: the shared base, its own research and the facts reused for its questions. */
    private static JSONArray visibleFacts(JSONArray shared, JSONArray pool, JSONObject research) {
        JSONArray visible = new JSONArray();
        visible.addAll(shared);
        JSONArray ids = research.getJSONArray("visibleFactIds");
        for (Object item : pool) {
            JSONObject fact = (JSONObject) item;
            if (ids != null && ids.contains(fact.getString("id")) && !visible.contains(fact)) visible.add(fact);
        }
        return visible;
    }

    private static JSONArray concat(JSONArray first, JSONArray second) {
        JSONArray all = new JSONArray();
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    /**
     * Returns the stored answer, or a new one. A member whose model cannot be reached gets an unsaved record
     * marked unreachable, so the decision to skip them or pause the meeting is made once all members were asked.
     */
    private JSONObject answer(Long meetingId, Long epoch, JSONObject card, JSONArray facts, AiMeetingMember member) {
        JSONObject stored = loadObject(meetingId, ANSWER, member.getId());
        if (stored != null) return stored;
        String system = memberSystem(member) + "你只能依据问题卡和事实底稿独立作答，看不到其他议员的观点。只输出 JSON。";
        String prompt = answerPrompt(card, facts);
        String[] corpora = corpora(facts, card);
        Set<String> factIds = factIds(facts);
        Answer parsed;
        String rewriteReason = null;
        try {
            try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                    meetingId, 1, member.getId(), MeetingCallContext.DIVERGENCE)) {
                parsed = parseAnswer(call(member.getModelType(), member.getMemberName(), system, prompt),
                        card, factIds, corpora);
            }
            if (parsed.error != null) {
                rewriteReason = parsed.error;
                try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                        meetingId, 1, member.getId(), MeetingCallContext.DIVERGENCE_REWRITE)) {
                    parsed = parseAnswer(call(member.getModelType(), member.getMemberName(), system,
                            prompt + "\n\n【上一版作答不合格】" + parsed.error + "。请按要求重新输出完整 JSON。"),
                            card, factIds, corpora);
                }
            }
        } catch (ModelUnavailable unavailable) {
            JSONObject record = new JSONObject(true);
            record.put("memberId", member.getId());
            record.put("memberName", member.getMemberName());
            record.put("skipped", true);
            record.put("unreachable", true);
            record.put("reason", unavailable.getMessage());
            record.put(UNSETTLED, true);
            return record;
        }
        JSONObject record = new JSONObject(true);
        record.put("memberId", member.getId());
        record.put("memberName", member.getMemberName());
        if (parsed.error != null) {
            record.put("skipped", true);
            record.put("reason", "「" + member.getMemberName() + "」作答不合格：" + parsed.error);
        } else {
            record.put("skipped", false);
            record.put("answers", parsed.answers);
        }
        // Why the first version was rejected, so a rewrite can be traced afterwards.
        if (rewriteReason != null) record.put("rewriteReason", rewriteReason);
        if (parsed.error != null) {
            record.put(UNSETTLED, true);
            return record;
        }
        return save(meetingId, ANSWER, member.getId(), epoch, record);
    }

    private static String memberSystem(AiMeetingMember member) {
        return "你是「" + member.getMemberName() + "」，参加一场圆桌研判。你的分析视角："
                + firstNonBlank(member.getPersona(), "独立分析") + "\n";
    }

    /**
     * Members without a valid answer (model unreachable, or still invalid after the rewrite) are skipped for
     * good only if at least two others answered. Otherwise the meeting pauses without storing them, so a
     * summary is never built from one person, and resuming asks them again.
     */
    private void settleSkipped(Long meetingId, Long epoch, JSONArray answers, List<JSONObject> unsettled) {
        if (unsettled.isEmpty()) return;
        long answered = answers.stream().filter(item -> !((JSONObject) item).getBooleanValue("skipped")).count();
        if (answered < 2) {
            StringBuilder reasons = new StringBuilder();
            for (JSONObject record : unsettled) {
                if (reasons.length() > 0) reasons.append("；");
                String reason = record.getString("reason");
                reasons.append(reason.length() > 160 ? reason.substring(0, 160) + "…" : reason);
            }
            throw new Blocked("有效作答不足 2 人，会议暂停：" + reasons + "。请检查后继续会议");
        }
        for (JSONObject record : unsettled) {
            record.remove(UNSETTLED);
            save(meetingId, ANSWER, record.getLong("memberId"), epoch, record);
        }
    }

    /** A provider failure is not a bad answer: it surfaces as ModelUnavailable instead of an empty reply. */
    private String call(String model, String who, String system, String prompt) {
        try {
            return orchestrator.callModelStrict(model, system, prompt, null);
        } catch (RuntimeException failure) {
            throw new ModelUnavailable(who, failure.getMessage());
        }
    }

    private static String answerPrompt(JSONObject card, JSONArray facts) {
        StringBuilder sb = new StringBuilder(cardAndFacts(card, facts));
        if (outcomeOf(card) != null) {
            sb.append("\n结果题 OUTCOME 不选单个选项，而是在 probabilities 里给每个区间打概率（0–1，合计为 1），")
                    .append("例如 {\"questionId\":\"OUTCOME\",\"probabilities\":{\"A\":0.1,\"B\":0.2,\"C\":0.4,\"D\":0.2,\"E\":0.1},")
                    .append("\"reasons\":[...],\"falsifier\":\"\"}；理由里不要写概率数字。");
        }
        sb.append("\n【作答要求】对每个子问题各选一个选项；每个选择给 1–3 条理由，每条理由注明引用的事实编号；")
                .append("再写一个会推翻你判断的信号（falsifier）。理由里的数字只能来自上面的事实，")
                .append("低置信事实可以用，但它没有经过交叉核实。\n");
        sb.append("只输出 JSON：{\"answers\":[{\"questionId\":\"Q1\",\"optionId\":\"A\",")
                .append("\"reasons\":[{\"text\":\"\",\"factIds\":[\"F-1\"]}],\"falsifier\":\"\"}]}");
        return sb.toString();
    }

    private static String cardAndFacts(JSONObject card, JSONArray facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【问题卡】\n议题：").append(card.getString("topic"));
        if (!blank(card.getString("horizon"))) sb.append("；展望期：").append(card.getString("horizon"));
        sb.append("；截至：").append(card.getString("asOf")).append("\n子问题：\n");
        for (Object item : card.getJSONArray("subQuestions")) {
            JSONObject question = (JSONObject) item;
            sb.append(question.getString("id")).append(" ").append(question.getString("title"))
                    .append(isProbability(question) ? "（结果题：给每个区间打概率，合计为 1）" : "").append("：");
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
        // Every problem is collected, so the one rewrite can fix all of them instead of tripping the next check.
        List<String> problems = new ArrayList<>();
        List<String> unsourced = new ArrayList<>();
        JSONArray normalized = new JSONArray();
        for (Object item : card.getJSONArray("subQuestions")) {
            JSONObject question = (JSONObject) item;
            String questionId = question.getString("id");
            JSONObject answer = byQuestion.remove(questionId);
            if (answer == null) {
                problems.add("没有回答子问题 " + questionId);
                continue;
            }
            String optionId = null;
            JSONObject probabilities = null;
            if (isProbability(question)) {
                probabilities = parseProbabilities(question, answer.getJSONObject("probabilities"));
                if (probabilities == null) problems.add("结果题 " + questionId + " 必须给每个区间一个 0–1 的概率，且合计为 1");
            } else {
                optionId = answer.getString("optionId");
                if (optionLabel(question, optionId) == null) problems.add("子问题 " + questionId + " 没有选项 " + optionId);
            }
            JSONArray reasons = answer.getJSONArray("reasons");
            if (reasons == null || reasons.isEmpty()) {
                problems.add("子问题 " + questionId + " 至少要有 1 条理由");
                continue;
            }
            // More than three reasons is a length issue, not a reasoning one: keep the first three.
            List<Object> kept = reasons.size() > 3 ? reasons.subList(0, 3) : reasons;
            JSONArray cleanReasons = new JSONArray();
            for (Object reasonItem : kept) {
                if (!(reasonItem instanceof JSONObject)) {
                    problems.add("子问题 " + questionId + " 的理由格式不对");
                    continue;
                }
                JSONObject reason = (JSONObject) reasonItem;
                String text = reason.getString("text");
                if (blank(text)) {
                    problems.add("子问题 " + questionId + " 有空理由");
                    continue;
                }
                JSONArray cited = reason.getJSONArray("factIds") == null ? new JSONArray() : reason.getJSONArray("factIds");
                for (Object id : cited) {
                    if (!factIds.contains(String.valueOf(id))) problems.add("引用了不存在的事实编号 " + id);
                }
                String gated = gateNumbers(text, corpora);
                if (gated == null) unsourced.add(text);
                JSONObject cleanReason = new JSONObject(true);
                cleanReason.put("text", gated);
                cleanReason.put("factIds", cited);
                cleanReasons.add(cleanReason);
            }
            String falsifier = answer.getString("falsifier");
            String gatedFalsifier = blank(falsifier) ? null : gateNumbers(falsifier, corpora);
            if (!blank(falsifier) && gatedFalsifier == null) unsourced.add(falsifier);
            String changeReason = answer.getString("changeReason");
            String gatedChange = blank(changeReason) ? null : gateNumbers(changeReason, corpora);
            if (!blank(changeReason) && gatedChange == null) unsourced.add(changeReason);
            JSONObject clean = new JSONObject(true);
            clean.put("questionId", questionId);
            if (probabilities != null) clean.put("probabilities", probabilities);
            else clean.put("optionId", optionId);
            clean.put("reasons", cleanReasons);
            clean.put("falsifier", gatedFalsifier);
            if (gatedChange != null) clean.put("changeReason", gatedChange);
            normalized.add(clean);
        }
        if (!byQuestion.isEmpty()) problems.add("回答了问题卡里没有的子问题 " + byQuestion.keySet());
        if (problems.isEmpty() && unsourced.isEmpty()) return new Answer(normalized, null);
        StringBuilder error = new StringBuilder(String.join("；", problems));
        if (!problems.isEmpty()) error.append("。");
        if (!unsourced.isEmpty()) error.append(unsupportedNumbers(String.join("\n", unsourced), corpora));
        return Answer.invalid(error.toString());
    }

    /** Low-confidence numbers are labelled; returns null when a number has no source in the fact base. */
    private static String gateNumbers(String text, String[] corpora) {
        ReviewedNumberGate.Audit audit = ReviewedNumberGate.audit(text, corpora[0], corpora[1], null);
        return audit.hasUnsupported() ? null : audit.label(text);
    }

    private static String unsupportedNumbers(String text, String[] corpora) {
        return ReviewedNumberGate.rewriteInstruction(ReviewedNumberGate.audit(text, corpora[0], corpora[1], null));
    }

    /** Interval bounds of the outcome question are definitions, so members may cite them like facts. */
    private static String[] corpora(JSONArray facts, JSONObject card) {
        StringBuilder verified = new StringBuilder();
        StringBuilder provisional = new StringBuilder();
        JSONObject outcome = outcomeOf(card);
        if (outcome != null) {
            for (Object option : outcome.getJSONArray("options")) {
                verified.append(((JSONObject) option).getString("label")).append('\n');
            }
        }
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

    private static JSONObject withOutcome(JSONObject card, JSONObject outcome) {
        if (outcome == null) return card;
        JSONObject working = JSON.parseObject(card.toJSONString());
        working.getJSONArray("subQuestions").add(outcome);
        return working;
    }

    private static JSONObject outcomeOf(JSONObject card) {
        if (card == null || card.getJSONArray("subQuestions") == null) return null;
        for (Object item : card.getJSONArray("subQuestions")) {
            if (isProbability((JSONObject) item)) return (JSONObject) item;
        }
        return null;
    }

    private static boolean isProbability(JSONObject question) {
        return PROBABILITY.equals(question.getString("kind"));
    }

    /** Every interval needs a probability in [0, 1] and the total must be 1 within rounding; null otherwise. */
    private static JSONObject parseProbabilities(JSONObject question, JSONObject raw) {
        if (raw == null) return null;
        JSONObject clean = new JSONObject(true);
        double total = 0;
        Set<String> ids = new HashSet<>();
        for (Object item : question.getJSONArray("options")) {
            String id = ((JSONObject) item).getString("id");
            ids.add(id);
            Double value;
            try {
                value = raw.getDouble(id);
            } catch (RuntimeException notANumber) {
                return null;
            }
            if (value == null || value < 0 || value > 1) return null;
            clean.put(id, value);
            total += value;
        }
        if (!ids.containsAll(raw.keySet()) || Math.abs(total - 1.0) > 0.01) return null;
        return clean;
    }

    private static Set<String> factIds(JSONArray facts) {
        Set<String> ids = new HashSet<>();
        for (Object item : facts) ids.add(((JSONObject) item).getString("id"));
        return ids;
    }

    // ---- P3 agenda (program only) ----

    /** Picks the most split disputes with one representative per leading side, and the leading consensus. */
    private JSONObject agenda(Long meetingId, Long epoch, int round, JSONObject card, JSONArray answers,
                              Set<String> onlyQuestions) {
        String kind = round == 1 ? AGENDA : AGENDA + SECOND_ROUND;
        JSONObject stored = loadObject(meetingId, kind, MEETING_LEVEL);
        if (stored != null) return stored;
        JSONObject firstRound = summarize(card, answers);
        List<JSONObject> disputes = firstRound.getJSONArray("disputes").toJavaList(JSONObject.class);
        if (onlyQuestions != null) disputes.removeIf(dispute -> !onlyQuestions.contains(dispute.getString("questionId")));
        disputes.sort((left, right) -> Double.compare(topShare(left), topShare(right)));
        JSONArray chosen = new JSONArray();
        for (JSONObject dispute : disputes.subList(0, Math.min(MAX_CROSS_EXAMS, disputes.size()))) {
            List<JSONObject> positions = dispute.getJSONArray("positions").toJavaList(JSONObject.class);
            positions.sort((left, right) -> Integer.compare(
                    right.getJSONArray("members").size(), left.getJSONArray("members").size()));
            JSONObject entry = new JSONObject(true);
            entry.put("questionId", dispute.getString("questionId"));
            entry.put("title", dispute.getString("title"));
            entry.put("a", side(positions.get(0)));
            entry.put("b", side(positions.get(1)));
            chosen.add(entry);
        }
        JSONObject redTeam = null;
        JSONObject leading = null;
        for (Object item : round == 1 ? firstRound.getJSONArray("conclusions") : new JSONArray()) {
            JSONObject conclusion = (JSONObject) item;
            if (leading == null || conclusion.getIntValue("support") > leading.getIntValue("support")) leading = conclusion;
        }
        if (leading != null) {
            JSONArray supporters = leading.getJSONArray("members");
            JSONObject devil = supporters.getJSONObject(supporters.size() - 1);
            redTeam = new JSONObject(true);
            redTeam.put("questionId", leading.getString("questionId"));
            redTeam.put("title", leading.getString("title"));
            redTeam.put("optionId", leading.getString("optionId"));
            redTeam.put("optionLabel", leading.getString("optionLabel"));
            redTeam.put("memberId", devil.get("memberId"));
            redTeam.put("memberName", devil.getString("memberName"));
        }
        JSONObject agenda = new JSONObject(true);
        agenda.put("disputes", chosen);
        agenda.put("redTeam", redTeam);
        return save(meetingId, kind, MEETING_LEVEL, epoch, agenda);
    }

    private static double topShare(JSONObject dispute) {
        int top = 0;
        for (Object position : dispute.getJSONArray("positions")) {
            top = Math.max(top, ((JSONObject) position).getJSONArray("members").size());
        }
        return dispute.getIntValue("total") == 0 ? 1.0 : (double) top / dispute.getIntValue("total");
    }

    private static JSONObject side(JSONObject position) {
        JSONObject first = position.getJSONArray("members").getJSONObject(0);
        JSONObject side = new JSONObject(true);
        side.put("memberId", first.get("memberId"));
        side.put("memberName", first.getString("memberName"));
        side.put("optionId", position.getString("optionId"));
        side.put("optionLabel", position.getString("optionLabel"));
        side.put("reasons", first.getJSONArray("reasons"));
        return side;
    }

    // ---- P5 paired cross-examination and consensus red team ----

    private JSONObject crossExam(Long meetingId, Long epoch, String kind, JSONObject card, JSONArray facts,
                                 JSONObject dispute, Map<Long, AiMeetingMember> membersById) {
        JSONObject stored = loadObject(meetingId, kind, MEETING_LEVEL);
        if (stored != null) return stored;
        JSONObject a = dispute.getJSONObject("a");
        JSONObject b = dispute.getJSONObject("b");
        AiMeetingMember asker = membersById.get(a.getLong("memberId"));
        AiMeetingMember answerer = membersById.get(b.getLong("memberId"));
        String[] corpora = corpora(facts, card);
        String topic = "子问题 " + dispute.getString("questionId") + " " + dispute.getString("title");
        JSONObject record = new JSONObject(true);
        record.put("questionId", dispute.getString("questionId"));
        record.put("title", dispute.getString("title"));
        record.put("a", a);
        record.put("b", b);
        String question = gatedText(meetingId, asker, MeetingCallContext.CROSS_EXAM, MeetingCallContext.CROSS_EXAM_REWRITE,
                memberSystem(asker), cardAndFacts(card, facts) + "\n【质询任务】" + topic + "：你选择了「"
                        + a.getString("optionLabel") + "」，「" + b.getString("memberName") + "」选择了「"
                        + b.getString("optionLabel") + "」。对方公开的理由：\n" + reasonsText(b.getJSONArray("reasons"))
                        + "请针对对方理由中最关键的证据或推理漏洞提出一个质询，不超过 200 字。"
                        + "数字只能来自事实底稿。直接输出质询内容。", corpora);
        if (question == null) {
            record.put("failed", true);
            record.put("reason", "质询内容含有资料里找不到来源的数字，重写后仍不合格");
            return save(meetingId, kind, MEETING_LEVEL, epoch, record);
        }
        record.put("question", question);
        String reply = gatedText(meetingId, answerer, MeetingCallContext.CROSS_EXAM, MeetingCallContext.CROSS_EXAM_REWRITE,
                memberSystem(answerer), cardAndFacts(card, facts) + "\n【回应质询】" + topic + "：你选择了「"
                        + b.getString("optionLabel") + "」。「" + a.getString("memberName") + "」对你的质询：\n"
                        + question + "\n请正面回应：承认、修正或反驳，并说明依据，不超过 200 字。"
                        + "数字只能来自事实底稿。直接输出回应内容。", corpora);
        record.put("failed", false);
        if (reply == null) {
            record.put("replyFailed", true);
        } else {
            record.put("reply", reply);
        }
        return save(meetingId, kind, MEETING_LEVEL, epoch, record);
    }

    private JSONObject redTeam(Long meetingId, Long epoch, JSONObject card, JSONArray facts, JSONObject target,
                               Map<Long, AiMeetingMember> membersById) {
        JSONObject stored = loadObject(meetingId, RED_TEAM, MEETING_LEVEL);
        if (stored != null) return stored;
        AiMeetingMember member = membersById.get(target.getLong("memberId"));
        String argument = gatedText(meetingId, member, MeetingCallContext.RED_TEAM, MeetingCallContext.RED_TEAM_REWRITE,
                memberSystem(member), cardAndFacts(card, facts) + "\n【红队任务】多数议员在子问题 "
                        + target.getString("questionId") + " " + target.getString("title") + " 上选择了「"
                        + target.getString("optionLabel") + "」。请站在反方论证这条结论最可能错在哪里："
                        + "列出 1–2 个最可能的失败原因，并说明需要什么证据才能确认或排除，不超过 250 字。"
                        + "数字只能来自事实底稿。直接输出论证内容。", corpora(facts, card));
        JSONObject record = new JSONObject(true);
        record.putAll(target);
        record.put("failed", argument == null);
        if (argument != null) record.put("argument", argument);
        return save(meetingId, RED_TEAM, MEETING_LEVEL, epoch, record);
    }

    /** Same policy as member answers: one rewrite for unsourced numbers, then the turn is dropped (null). */
    private String gatedText(Long meetingId, AiMeetingMember member, String stage, String rewriteStage,
                             String system, String prompt, String[] corpora) {
        String reply;
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, member.getId(), stage)) {
            reply = call(member.getModelType(), member.getMemberName(), system, prompt);
        }
        String gated = blank(reply) ? null : gateNumbers(reply.trim(), corpora);
        if (gated != null) return gated;
        String why = blank(reply) ? "没有输出内容" : unsupportedNumbers(reply, corpora);
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, member.getId(), rewriteStage)) {
            reply = call(member.getModelType(), member.getMemberName(), system, prompt + "\n\n【上一版不合格】" + why);
        }
        return blank(reply) ? null : gateNumbers(reply.trim(), corpora);
    }

    private static String reasonsText(JSONArray reasons) {
        StringBuilder sb = new StringBuilder();
        for (Object item : reasons) {
            JSONObject reason = (JSONObject) item;
            sb.append("- ").append(reason.getString("text"));
            JSONArray cited = reason.getJSONArray("factIds");
            if (cited != null && !cited.isEmpty()) sb.append("（").append(String.join("、", cited.toJavaList(String.class))).append("）");
            sb.append("\n");
        }
        return sb.toString();
    }

    // ---- P6 anonymous re-estimate ----

    private JSONObject finalAnswer(Long meetingId, Long epoch, String kind, JSONObject card, JSONArray facts,
                                   JSONArray answers, JSONArray crossExams, JSONObject redTeam, JSONObject first,
                                   AiMeetingMember member) {
        JSONObject stored = loadObject(meetingId, kind, member.getId());
        if (stored != null) return stored;
        JSONObject record = new JSONObject(true);
        record.put("memberId", member.getId());
        record.put("memberName", member.getMemberName());
        if (first.getBooleanValue("skipped")) {
            record.put("skipped", true);
            record.put("reason", "首轮作答无效，未参加再估");
            return save(meetingId, kind, member.getId(), epoch, record);
        }
        String system = memberSystem(member) + "这是匿名再估：你能看到其他人选择的分布和交锋记录，但看不到是谁选的。只输出 JSON。";
        String prompt = answerPrompt(card, facts) + "\n\n【再估任务】" + reestimateContext(card, answers, crossExams, redTeam, first)
                + "请重新作答全部子问题，格式同上；如果某题改了选项，在该题加 changeReason 说明原因。";
        String[] corpora = corpora(facts, card);
        Set<String> factIds = factIds(facts);
        Answer parsed;
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                meetingId, 1, member.getId(), MeetingCallContext.REESTIMATE)) {
            parsed = parseAnswer(call(member.getModelType(), member.getMemberName(), system, prompt),
                    card, factIds, corpora);
        }
        String rewriteReason = parsed.error;
        if (parsed.error != null) {
            try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(
                    meetingId, 1, member.getId(), MeetingCallContext.REESTIMATE_REWRITE)) {
                parsed = parseAnswer(call(member.getModelType(), member.getMemberName(), system,
                        prompt + "\n\n【上一版作答不合格】" + parsed.error + "。请按要求重新输出完整 JSON。"),
                        card, factIds, corpora);
            }
        }
        record.put("skipped", false);
        if (parsed.error != null) {
            // A failed re-estimate must not erase a valid first-round position.
            record.put("fallback", true);
            record.put("reason", parsed.error);
            record.put("answers", first.getJSONArray("answers"));
        } else {
            record.put("fallback", false);
            record.put("answers", parsed.answers);
        }
        if (rewriteReason != null) record.put("rewriteReason", rewriteReason);
        return save(meetingId, kind, member.getId(), epoch, record);
    }

    private static String reestimateContext(JSONObject card, JSONArray answers, JSONArray crossExams,
                                            JSONObject redTeam, JSONObject first) {
        StringBuilder sb = new StringBuilder("你上一轮的选择：");
        for (Object item : first.getJSONArray("answers")) {
            JSONObject choice = (JSONObject) item;
            sb.append(choice.getString("questionId")).append("=").append(choice.getString("optionId")).append("；");
        }
        sb.append("\n各子问题的匿名分布：\n");
        for (Object item : card.getJSONArray("subQuestions")) {
            JSONObject question = (JSONObject) item;
            if (isProbability(question)) {
                JSONObject pooled = outcomeSummary(question, answeredOnly(answers));
                List<String> means = new ArrayList<>();
                for (Object option : pooled.getJSONArray("options")) {
                    JSONObject json = (JSONObject) option;
                    means.add(json.getString("id") + " " + json.getString("label") + " 平均 "
                            + Math.round(json.getDoubleValue("mean") * 100) + "%");
                }
                sb.append(question.getString("id")).append(" ").append(question.getString("title")).append("：")
                        .append(String.join(" / ", means)).append("\n");
                continue;
            }
            List<String> counts = new ArrayList<>();
            for (Object optionItem : question.getJSONArray("options")) {
                JSONObject option = (JSONObject) optionItem;
                int count = 0;
                for (Object answerItem : answers) {
                    JSONObject answer = (JSONObject) answerItem;
                    if (answer.getBooleanValue("skipped")) continue;
                    for (Object choice : answer.getJSONArray("answers")) {
                        JSONObject json = (JSONObject) choice;
                        if (question.getString("id").equals(json.getString("questionId"))
                                && option.getString("id").equals(json.getString("optionId"))) count++;
                    }
                }
                counts.add(option.getString("id") + " " + option.getString("label") + " " + count + " 人");
            }
            sb.append(question.getString("id")).append(" ").append(question.getString("title")).append("：")
                    .append(String.join(" / ", counts)).append("\n");
        }
        sb.append("【交锋记录】\n");
        for (Object item : crossExams) {
            JSONObject exam = (JSONObject) item;
            sb.append(exam.getString("questionId")).append(" ").append(exam.getString("title")).append("：");
            if (exam.getBooleanValue("failed")) {
                sb.append("质询未能完成。\n");
                continue;
            }
            sb.append("质询：").append(exam.getString("question")).append("\n回应：")
                    .append(exam.getString("reply") == null ? "未回应" : exam.getString("reply")).append("\n");
        }
        if (redTeam != null && !redTeam.getBooleanValue("failed")) {
            sb.append("【红队对「").append(redTeam.getString("title")).append("：").append(redTeam.getString("optionLabel"))
                    .append("」的反方论证】\n").append(redTeam.getString("argument")).append("\n");
        }
        return sb.toString();
    }

    // ---- S5 dispute classification, evidence top-up and the second-round trigger ----

    /**
     * The moderator names each remaining dispute's type, crux and (for fact disputes) the question that
     * would settle it. Invalid entries get one rewrite; whatever is still invalid stays UNCLASSIFIED.
     */
    private JSONObject classify(Long meetingId, Long epoch, JSONObject card, JSONArray facts, JSONArray disputes,
                                JSONArray crossExams) {
        JSONObject stored = loadObject(meetingId, CLASSIFY, MEETING_LEVEL);
        if (stored != null) return stored;
        String model = orchestrator.resolveMeetingModelType(meetingId);
        String system = "你是中立的会议主持人，负责判断分歧的根源，不表达立场。只输出 JSON。";
        String prompt = cardAndFacts(card, facts) + "\n【仍存在的分歧】\n" + disputesText(disputes, crossExams)
                + "\n对每条分歧判断类型：FACT（双方依据的事实不同，能靠补充证据裁决）/ INTERPRETATION（事实相同，"
                + "权重或因果判断不同）/ ASSUMPTION（对未来的假设不同）/ RISK_PREFERENCE（判断相同，行动取向不同）。"
                + "写出关键分歧点，格式“若……，则……；否则……”。FACT 类必须给一道能裁决它的取证题，只问一个可以核实的事实。\n"
                + "只输出 JSON：{\"disputes\":[{\"questionId\":\"Q1\",\"type\":\"FACT\",\"crux\":\"\",\"evidenceQuestion\":\"\"}]}";
        Set<String> expected = new LinkedHashSet<>();
        for (Object item : disputes) expected.add(((JSONObject) item).getString("questionId"));
        Map<String, JSONObject> valid;
        try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.CLASSIFY)) {
            valid = parseClassification(call(model, "主持人", system, prompt), expected);
        }
        if (valid.size() < expected.size()) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(valid.keySet());
            try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.CLASSIFY_REWRITE)) {
                Map<String, JSONObject> retried = parseClassification(call(model, "主持人", system, prompt
                        + "\n\n【上一版不合格】这些分歧缺少合格的类型、关键分歧点或取证题：" + missing
                        + "。请重新输出全部分歧的完整 JSON。"), expected);
                for (Map.Entry<String, JSONObject> entry : retried.entrySet()) valid.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        JSONArray classified = new JSONArray();
        for (String questionId : expected) {
            JSONObject entry = valid.get(questionId);
            if (entry == null) {
                entry = new JSONObject(true);
                entry.put("questionId", questionId);
                entry.put("type", "UNCLASSIFIED");
            }
            classified.add(entry);
        }
        JSONObject record = new JSONObject(true);
        record.put("disputes", classified);
        return save(meetingId, CLASSIFY, MEETING_LEVEL, epoch, record);
    }

    private static Map<String, JSONObject> parseClassification(String reply, Set<String> expected) {
        Map<String, JSONObject> valid = new LinkedHashMap<>();
        JSONObject root = parseObject(reply);
        JSONArray entries = root == null ? null : root.getJSONArray("disputes");
        if (entries == null) return valid;
        for (Object item : entries) {
            if (!(item instanceof JSONObject)) continue;
            JSONObject entry = (JSONObject) item;
            String questionId = entry.getString("questionId");
            String type = entry.getString("type");
            String crux = entry.getString("crux");
            String evidenceQuestion = entry.getString("evidenceQuestion");
            if (!expected.contains(questionId) || valid.containsKey(questionId) || !DISPUTE_TYPES.contains(type)
                    || blank(crux) || !(crux.contains("若") && crux.contains("则") && crux.contains("否则"))
                    || ("FACT".equals(type) && blank(evidenceQuestion))) continue;
            JSONObject clean = new JSONObject(true);
            clean.put("questionId", questionId);
            clean.put("type", type);
            clean.put("crux", crux.trim());
            if ("FACT".equals(type)) clean.put("evidenceQuestion", evidenceQuestion.trim());
            valid.put(questionId, clean);
        }
        return valid;
    }

    private static String disputesText(JSONArray disputes, JSONArray crossExams) {
        StringBuilder sb = new StringBuilder();
        for (Object item : disputes) {
            JSONObject dispute = (JSONObject) item;
            sb.append(dispute.getString("questionId")).append(" ").append(dispute.getString("title")).append("\n");
            for (Object positionItem : dispute.getJSONArray("positions")) {
                JSONObject position = (JSONObject) positionItem;
                sb.append("  选「").append(position.getString("optionLabel")).append("」：\n");
                for (Object memberItem : position.getJSONArray("members")) {
                    JSONObject member = (JSONObject) memberItem;
                    sb.append("  ").append(member.getString("memberName")).append("\n  ")
                            .append(reasonsText(member.getJSONArray("reasons")).replace("\n", "\n  "));
                }
            }
            for (Object examItem : crossExams) {
                JSONObject exam = (JSONObject) examItem;
                if (!dispute.getString("questionId").equals(exam.getString("questionId"))
                        || exam.getBooleanValue("failed")) continue;
                sb.append("  质询：").append(exam.getString("question")).append("\n  回应：")
                        .append(exam.getString("reply") == null ? "未回应" : exam.getString("reply")).append("\n");
            }
        }
        return sb.toString();
    }

    /** Fact disputes get their evidence question searched, at most {@link #TOP_UP_BUDGET} per meeting. */
    private JSONObject topUp(Long meetingId, Long epoch, JSONObject card, JSONArray facts, JSONObject classification) {
        JSONObject stored = loadObject(meetingId, TOP_UP, MEETING_LEVEL);
        if (stored != null) return stored;
        int[] next = {maxIndex(facts, "F"), maxIndex(facts, "L"), maxIndex(facts, "G")};
        JSONArray added = new JSONArray();
        JSONObject byQuestion = new JSONObject(true);
        JSONArray overBudget = new JSONArray();
        int used = 0;
        for (Object item : classification.getJSONArray("disputes")) {
            JSONObject entry = (JSONObject) item;
            if (!"FACT".equals(entry.getString("type"))) continue;
            if (used >= TOP_UP_BUDGET) {
                overBudget.add(entry.getString("questionId"));
                continue;
            }
            if (activeRoom(meetingId, epoch) == null) return null;
            used++;
            String question = entry.getString("evidenceQuestion");
            JSONArray ids = new JSONArray();
            try (MeetingCallContext.Scope ignored = MeetingCallContext.stage(MeetingCallContext.EVIDENCE_SEARCH)) {
                MeetingReviewedEvidenceService.ReviewedEvidence evidence =
                        resolveEvidence(meetingId, card.getString("asOf"), question);
                JSONArray claims = evidence.packet.getJSONArray("claims");
                JSONArray candidates = evidence.packet.getJSONArray("provisionalClaims");
                for (int i = 0; claims != null && i < claims.size(); i++) {
                    JSONObject fact = fact("F-" + (++next[0]), "F", question, claims.getJSONObject(i));
                    added.add(fact);
                    ids.add(fact.getString("id"));
                }
                for (int i = 0; candidates != null && i < candidates.size(); i++) {
                    JSONObject fact = fact("L-" + (++next[1]), "L", question, candidates.getJSONObject(i));
                    added.add(fact);
                    ids.add(fact.getString("id"));
                }
                if (ids.isEmpty()) {
                    JSONObject gap = gapFact(++next[2], question, "N8N_" + evidence.packet.getString("status"));
                    added.add(gap);
                    ids.add(gap.getString("id"));
                }
            } catch (MeetingReviewedEvidenceService.EvidenceRejected rejected) {
                JSONObject gap = gapFact(++next[2], question, rejected.getMessage());
                added.add(gap);
                ids.add(gap.getString("id"));
            }
            byQuestion.put(entry.getString("questionId"), ids);
        }
        JSONObject record = new JSONObject(true);
        record.put("added", added);
        record.put("byQuestion", byQuestion);
        record.put("overBudget", overBudget);
        return save(meetingId, TOP_UP, MEETING_LEVEL, epoch, record);
    }

    /** Only fact disputes whose top-up produced new F or L facts are worth a second round. */
    private static Set<String> reopenedByEvidence(JSONObject topUp) {
        Set<String> reopened = new LinkedHashSet<>();
        JSONObject byQuestion = topUp.getJSONObject("byQuestion");
        for (String questionId : byQuestion.keySet()) {
            for (Object id : byQuestion.getJSONArray(questionId)) {
                if (!String.valueOf(id).startsWith("G-")) reopened.add(questionId);
            }
        }
        return reopened;
    }

    private static int maxIndex(JSONArray facts, String tier) {
        int max = 0;
        for (Object item : facts) {
            String id = ((JSONObject) item).getString("id");
            if (id != null && id.startsWith(tier + "-")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(tier.length() + 1))); }
                catch (NumberFormatException ignored) { /* not a numbered fact */ }
            }
        }
        return max;
    }

    // ---- P7 program summary ----

    /** The summary is built from the re-estimated answers; first-round answers only feed the change log. */
    private JSONObject result(Long meetingId, Long epoch, JSONObject card, JSONArray facts,
                              JSONArray finalAnswers, JSONArray firstAnswers, JSONObject classification,
                              JSONObject topUp, int loops) {
        JSONObject stored = loadObject(meetingId, RESULT, MEETING_LEVEL);
        if (stored != null) return stored;
        JSONObject result = summarize(card, finalAnswers);
        annotate(result, classification, topUp);
        markSettledDisputes(result, summarize(card, firstAnswers));
        result.put("loops", loops);
        JSONArray openQuestions = new JSONArray();
        for (Object item : facts) {
            JSONObject fact = (JSONObject) item;
            if ("G".equals(fact.getString("tier"))) openQuestions.add(fact.getString("question"));
        }
        result.put("openQuestions", openQuestions);
        result.put("changes", changes(firstAnswers, finalAnswers));
        return save(meetingId, RESULT, MEETING_LEVEL, epoch, result);
    }

    /**
     * Remaining disputes carry type, crux and status: UNRESOLVED (still split on interpretation, assumption,
     * risk preference, or despite new evidence), UNDECIDED (a fact dispute that got no new evidence or ran
     * out of budget) or UNCLASSIFIED. Conclusions that were disputes after the first round are marked.
     */
    private static void annotate(JSONObject result, JSONObject classification, JSONObject topUp) {
        Map<String, JSONObject> byQuestion = new LinkedHashMap<>();
        if (classification != null) {
            for (Object item : classification.getJSONArray("disputes")) {
                JSONObject entry = (JSONObject) item;
                byQuestion.put(entry.getString("questionId"), entry);
            }
        }
        JSONObject evidence = topUp == null ? new JSONObject() : topUp.getJSONObject("byQuestion");
        for (Object item : result.getJSONArray("disputes")) {
            JSONObject dispute = (JSONObject) item;
            JSONObject entry = byQuestion.get(dispute.getString("questionId"));
            String type = entry == null ? "UNCLASSIFIED" : entry.getString("type");
            dispute.put("type", type);
            if (entry != null && entry.getString("crux") != null) dispute.put("crux", entry.getString("crux"));
            if (entry != null && entry.getString("evidenceQuestion") != null) {
                dispute.put("evidenceQuestion", entry.getString("evidenceQuestion"));
            }
            JSONArray ids = evidence.getJSONArray(dispute.getString("questionId"));
            if (ids != null) dispute.put("topUpFactIds", ids);
            if ("UNCLASSIFIED".equals(type)) {
                dispute.put("status", "UNCLASSIFIED");
            } else if ("FACT".equals(type) && (ids == null
                    || ids.stream().allMatch(id -> String.valueOf(id).startsWith("G-")))) {
                dispute.put("status", "UNDECIDED");
            } else {
                dispute.put("status", "UNRESOLVED");
            }
        }
        for (Object item : result.getJSONArray("conclusions")) {
            JSONObject conclusion = (JSONObject) item;
            JSONObject entry = byQuestion.get(conclusion.getString("questionId"));
            if (entry == null) continue;
            conclusion.put("resolvedAfterDispute", true);
            conclusion.put("type", entry.getString("type"));
            if (entry.getString("crux") != null) conclusion.put("crux", entry.getString("crux"));
        }
    }

    /** A first-round dispute that ends as a conclusion was settled by cross-examination or new evidence. */
    private static void markSettledDisputes(JSONObject result, JSONObject firstRound) {
        Set<String> disputed = new LinkedHashSet<>();
        for (Object item : firstRound.getJSONArray("disputes")) disputed.add(((JSONObject) item).getString("questionId"));
        for (Object item : result.getJSONArray("conclusions")) {
            JSONObject conclusion = (JSONObject) item;
            if (disputed.contains(conclusion.getString("questionId"))) conclusion.put("resolvedAfterDispute", true);
        }
    }

    private static JSONArray changes(JSONArray firstAnswers, JSONArray finalAnswers) {
        Map<String, String> firstChoice = new LinkedHashMap<>();
        for (Object item : firstAnswers) {
            JSONObject answer = (JSONObject) item;
            if (answer.getBooleanValue("skipped")) continue;
            for (Object choice : answer.getJSONArray("answers")) {
                JSONObject json = (JSONObject) choice;
                firstChoice.put(answer.get("memberId") + "|" + json.getString("questionId"), json.getString("optionId"));
            }
        }
        JSONArray changes = new JSONArray();
        for (Object item : finalAnswers) {
            JSONObject answer = (JSONObject) item;
            if (answer.getBooleanValue("skipped") || answer.getBooleanValue("fallback")) continue;
            for (Object choice : answer.getJSONArray("answers")) {
                JSONObject json = (JSONObject) choice;
                String before = firstChoice.get(answer.get("memberId") + "|" + json.getString("questionId"));
                if (before == null || before.equals(json.getString("optionId"))) continue;
                JSONObject change = new JSONObject(true);
                change.put("memberId", answer.get("memberId"));
                change.put("memberName", answer.getString("memberName"));
                change.put("questionId", json.getString("questionId"));
                change.put("from", before);
                change.put("to", json.getString("optionId"));
                change.put("reason", json.getString("changeReason"));
                changes.add(change);
            }
        }
        return changes;
    }

    private JSONObject summarize(JSONObject card, JSONArray answers) {
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
        JSONObject outcome = null;
        for (Object item : card.getJSONArray("subQuestions")) {
            JSONObject question = (JSONObject) item;
            if (isProbability(question)) {
                outcome = total == 0 ? null : outcomeSummary(question, answered);
                continue;
            }
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
                if (evidenceThin(byOption.get(topOption))) line.put("evidenceThin", true);
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
        JSONObject summary = new JSONObject(true);
        summary.put("answeredCount", total);
        summary.put("skippedCount", skipped);
        summary.put("conclusions", conclusions);
        summary.put("disputes", disputes);
        summary.put("insufficient", insufficient);
        if (outcome != null) summary.put("outcome", outcome);
        return summary;
    }

    /**
     * A consensus is evidence-thin when at least half of its supporters' reasons cite no verified (F) or
     * low-confidence (L) fact: members agreed mostly because the evidence was missing, not because it was there.
     */
    private static boolean evidenceThin(JSONArray supporters) {
        int reasons = 0;
        int unbacked = 0;
        for (Object item : supporters) {
            JSONArray list = ((JSONObject) item).getJSONArray("reasons");
            for (int i = 0; list != null && i < list.size(); i++) {
                reasons++;
                JSONArray ids = list.getJSONObject(i).getJSONArray("factIds");
                boolean backed = ids != null && ids.stream()
                        .anyMatch(id -> String.valueOf(id).startsWith("F-") || String.valueOf(id).startsWith("L-"));
                if (!backed) unbacked++;
            }
        }
        return reasons > 0 && unbacked * 2 >= reasons;
    }

    private static List<JSONObject> answeredOnly(JSONArray answers) {
        List<JSONObject> answered = new ArrayList<>();
        for (Object item : answers) {
            JSONObject answer = (JSONObject) item;
            if (!answer.getBooleanValue("skipped")) answered.add(answer);
        }
        return answered;
    }

    /**
     * Equal-weight pooled probabilities without extremizing. A member is in the minority when their most likely
     * interval differs from the pooled one or any interval deviates from the pool by at least 0.25.
     */
    private static JSONObject outcomeSummary(JSONObject question, List<JSONObject> answered) {
        String questionId = question.getString("id");
        List<JSONObject> distributions = new ArrayList<>();
        List<JSONObject> owners = new ArrayList<>();
        for (JSONObject answer : answered) {
            for (Object item : answer.getJSONArray("answers")) {
                JSONObject choice = (JSONObject) item;
                if (questionId.equals(choice.getString("questionId")) && choice.getJSONObject("probabilities") != null) {
                    distributions.add(choice.getJSONObject("probabilities"));
                    JSONObject owner = new JSONObject(true);
                    owner.put("memberId", answer.get("memberId"));
                    owner.put("memberName", answer.getString("memberName"));
                    owner.put("reasons", choice.getJSONArray("reasons"));
                    owner.put("falsifier", choice.getString("falsifier"));
                    owners.add(owner);
                }
            }
        }
        JSONArray options = new JSONArray();
        Map<String, Double> pooled = new LinkedHashMap<>();
        String aggregateMode = null;
        for (Object item : question.getJSONArray("options")) {
            JSONObject option = (JSONObject) item;
            String id = option.getString("id");
            double sum = 0;
            double min = 1;
            double max = 0;
            for (JSONObject distribution : distributions) {
                double value = distribution.getDoubleValue(id);
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            double mean = distributions.isEmpty() ? 0 : sum / distributions.size();
            pooled.put(id, mean);
            if (aggregateMode == null || mean > pooled.get(aggregateMode)) aggregateMode = id;
            JSONObject line = new JSONObject(true);
            line.put("id", id);
            line.put("label", option.getString("label"));
            line.put("lowerPct", option.get("lowerPct"));
            line.put("upperPct", option.get("upperPct"));
            line.put("mean", round4(mean));
            line.put("min", distributions.isEmpty() ? 0 : round4(min));
            line.put("max", distributions.isEmpty() ? 0 : round4(max));
            options.add(line);
        }
        JSONArray minority = new JSONArray();
        for (int i = 0; i < distributions.size(); i++) {
            JSONObject distribution = distributions.get(i);
            String mode = null;
            boolean deviates = false;
            for (Map.Entry<String, Double> entry : pooled.entrySet()) {
                double value = distribution.getDoubleValue(entry.getKey());
                if (mode == null || value > distribution.getDoubleValue(mode)) mode = entry.getKey();
                if (Math.abs(value - entry.getValue()) >= MINORITY_DEVIATION - 1e-9) deviates = true;
            }
            if (!Objects.equals(mode, aggregateMode) || deviates) {
                JSONObject member = owners.get(i);
                member.put("mode", mode);
                minority.add(member);
            }
        }
        JSONObject summary = new JSONObject(true);
        summary.put("questionId", questionId);
        summary.put("title", question.getString("title"));
        summary.put("total", distributions.size());
        summary.put("aggregateMode", aggregateMode);
        summary.put("options", options);
        summary.put("minority", minority);
        return summary;
    }

    private static double round4(double value) {
        return Math.round(value * 10000) / 10000.0;
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
                    + "（" + line.getIntValue("support") + "/" + line.getIntValue("total")
                    + (line.getBooleanValue("evidenceThin") ? "，证据不足的共识：多数理由只引用了资料缺口" : "") + "）");
        }
        List<String> disputeLines = new ArrayList<>();
        for (Object item : result.getJSONArray("disputes")) {
            JSONObject line = (JSONObject) item;
            List<String> sides = new ArrayList<>();
            for (Object position : line.getJSONArray("positions")) {
                JSONObject json = (JSONObject) position;
                sides.add(json.getString("optionLabel") + " " + json.getJSONArray("members").size() + " 人");
            }
            String why = line.getString("crux") == null ? "" : "；" + typeLabel(line.getString("type")) + "：" + line.getString("crux");
            disputeLines.add(line.getString("title") + "（" + String.join("，", sides) + why + "）");
        }
        JSONObject outcome = result.getJSONObject("outcome");
        if (outcome != null && outcome.getString("aggregateMode") != null) {
            for (Object item : outcome.getJSONArray("options")) {
                JSONObject option = (JSONObject) item;
                if (!option.getString("id").equals(outcome.getString("aggregateMode"))) continue;
                conclusionLines.add(outcome.getString("title") + " → 合议最可能「" + option.getString("label") + "」（"
                        + Math.round(option.getDoubleValue("mean") * 100) + "%，少数派 "
                        + outcome.getJSONArray("minority").size() + " 人）");
            }
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
        // Gaps stay listed in the result; following them up daily through ordinary web search broke the evidence rules.
        trackingCard.setOpenQuestions(new ArrayList<>());
        room.setFinalConclusion(conclusion);
        room.setConclusionTime(concludedAt);
        room.setStatus(MeetingStatusEnum.CONCLUDED.getCode());
        workflowService.markMeetingConcluded(room, trackingCard, trackingItems(meetingId, result));
    }

    /**
     * What the program follows up after the meeting: the outcome at expiry (judged by the program) and each
     * assumption dispute (judged by a person on the same date). Falsifiers are not tracked yet.
     */
    private List<ProgramTrackingItemSpec> trackingItems(Long meetingId, JSONObject result) {
        List<ProgramTrackingItemSpec> specs = new ArrayList<>();
        JSONObject outcome = loadObject(meetingId, OUTCOME, MEETING_LEVEL);
        Date expiry = expiryEstimate(outcome);
        if (outcome != null && expiry != null) {
            ProgramTrackingItemSpec spec = new ProgramTrackingItemSpec();
            spec.setItemType(RoundtableV2OutcomeJudge.OUTCOME_EXPIRY);
            spec.setTitle("结果到期判定：" + outcome.getString("title"));
            spec.setDescription("到期后由程序用前复权日K判定实际涨跌落在哪一档，并给合议分布和每位议员打分（RPS 为主，Brier 为辅）");
            spec.setOwnerType("SYSTEM");
            spec.setAutoRefresh(true);
            spec.setNextCheckAt(expiry);
            spec.setDueAt(expiry);
            specs.add(spec);
        }
        for (Object item : result.getJSONArray("disputes")) {
            JSONObject dispute = (JSONObject) item;
            if (!"ASSUMPTION".equals(dispute.getString("type"))) continue;
            ProgramTrackingItemSpec spec = new ProgramTrackingItemSpec();
            spec.setItemType("ASSUMPTION_DISPUTE");
            spec.setTitle("假设分歧：" + dispute.getString("title")
                    + (dispute.getString("crux") == null ? "" : "（" + dispute.getString("crux") + "）"));
            spec.setDescription("圆桌 v2 的假设分歧：到期时人工判定哪一方的假设成立");
            spec.setOwnerType("HUMAN");
            spec.setAutoRefresh(false);
            spec.setDueAt(expiry);
            specs.add(spec);
        }
        return specs;
    }

    /** Horizon trading days ≈ horizon × 7/5 calendar days after the base close, checked at 16:00 after the close. */
    private static Date expiryEstimate(JSONObject outcome) {
        if (outcome == null || outcome.getString("baseDate") == null) return null;
        try {
            LocalDate base = LocalDate.parse(outcome.getString("baseDate"));
            long days = (long) Math.ceil(outcome.getIntValue("horizonTradingDays") * 7.0 / 5);
            return Date.from(base.plusDays(days).atTime(16, 0).atZone(MARKET_ZONE).toInstant());
        } catch (java.time.DateTimeException invalid) {
            return null;
        }
    }

    private static String typeLabel(String type) {
        switch (type == null ? "" : type) {
            case "FACT": return "事实分歧";
            case "INTERPRETATION": return "解读分歧";
            case "ASSUMPTION": return "假设分歧";
            case "RISK_PREFERENCE": return "风险偏好分歧";
            default: return "未分类分歧";
        }
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
    private static class Blocked extends RuntimeException {
        private Blocked(String reason) {
            super(reason);
        }
    }

    /** The model could not be reached; nothing is stored for the step, so resuming the meeting retries it. */
    private static final class ModelUnavailable extends Blocked {
        private ModelUnavailable(String who, String detail) {
            super("「" + who + "」的模型调用失败（" + detail + "）");
        }
    }
}
