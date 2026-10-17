package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.controller.MeetingController;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingV2RecordMapper;
import com.aimeeting.room.dto.RoundtableV2ViewVO;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Roundtable v2 skeleton: issue card, fact base, back-to-back answers, program summary. */
@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class RoundtableV2FlowIntegrationTest {
    private static final long EPOCH = 4L;
    private static final String CARD = "{\"topic\":\"宁德时代未来三个月走势\",\"horizon\":\"三个月\","
            + "\"subQuestions\":["
            + "{\"id\":\"Q1\",\"title\":\"业绩基本面\",\"options\":[{\"id\":\"A\",\"label\":\"支撑股价\"},"
            + "{\"id\":\"B\",\"label\":\"拖累股价\"}]},"
            + "{\"id\":\"Q2\",\"title\":\"订单与需求\",\"options\":[{\"id\":\"A\",\"label\":\"偏强\"},"
            + "{\"id\":\"B\",\"label\":\"偏弱\"}]}],"
            + "\"evidenceQuestions\":[\"2025年营收是多少？\",\"最新订单规模是多少？\",\"海外关税影响多大？\"],"
            + "\"referenceClass\":null}";

    private static final String DEFAULT_CLASSIFY = "{\"disputes\":[{\"questionId\":\"Q2\","
            + "\"type\":\"INTERPRETATION\",\"crux\":\"若需求回暖，则偏强；否则偏弱\"}]}";

    private final long meetingId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;

    @Autowired private MeetingReviewedSearchLedgerService ledger;
    @Autowired private AiMeetingV2RecordMapper records;
    @Autowired private MeetingController controller;

    @AfterEach
    void cleanup() {
        ledger.deleteByMeetingId(meetingId);
        records.deleteByMeetingId(meetingId);
    }

    @Test
    void skeletonProducesFactBaseAnswersConclusionsAndDisputes() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "2025年营收：100亿元，增长稳健", "F-1", "A", "订单需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "B", "订单规模80亿元，增速放缓", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求回暖", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertEquals(2, view.getIssueCard().getJSONArray("subQuestions").size());
        assertEquals(List.of("F-1:F", "L-1:L", "G-1:G"), factIdsAndTiers(view.getFacts()));
        assertEquals(3, view.getAnswers().size());
        assertTrue(answerOf(view, "乙").toJSONString().contains("80亿元〔低置信〕"),
                answerOf(view, "乙").toJSONString());

        JSONArray conclusions = view.getResult().getJSONArray("conclusions");
        assertEquals(1, conclusions.size());
        assertEquals("Q1", conclusions.getJSONObject(0).getString("questionId"));
        assertEquals("A", conclusions.getJSONObject(0).getString("optionId"));
        assertEquals(3, conclusions.getJSONObject(0).getIntValue("support"));
        assertEquals(3, conclusions.getJSONObject(0).getIntValue("total"));
        JSONArray disputes = view.getResult().getJSONArray("disputes");
        assertEquals(1, disputes.size());
        assertEquals("Q2", disputes.getJSONObject(0).getString("questionId"));
        Map<String, Integer> positionSizes = new HashMap<>();
        for (Object position : disputes.getJSONObject(0).getJSONArray("positions")) {
            JSONObject json = (JSONObject) position;
            positionSizes.put(json.getString("optionId"), json.getJSONArray("members").size());
        }
        assertEquals(Map.of("A", 2, "B", 1), positionSizes);

        ArgumentCaptor<String> artifact = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> conclusion = ArgumentCaptor.forClass(String.class);
        verify(fixture.writer).concludeIfCurrent(eq(meetingId), eq(EPOCH), anyInt(), anyInt(),
                nullable(String.class), artifact.capture(), anyString(), conclusion.capture(), any(Date.class));
        assertTrue(conclusion.getValue().contains("业绩基本面"), conclusion.getValue());
        assertTrue(JSON.parseObject(artifact.getValue()).getJSONArray("majorDisputes").toJSONString()
                .contains("订单与需求"), artifact.getValue());
        verify(fixture.workflow).markMeetingConcluded(any(), any());
    }

    @Test
    void invalidIssueCardTwicePausesTheMeetingBeforeAnySearch() {
        Fixture fixture = new Fixture();
        fixture.moderatorReplies.add("{\"subQuestions\":[]}");
        fixture.moderatorReplies.add("{\"subQuestions\":[]}");

        fixture.service.run(meetingId, EPOCH);

        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
        verify(fixture.reviewed, never()).search(anyString(), anyString(), anyString(), anyLong());
        verify(fixture.orchestrator, times(2)).callModelStrict(anyString(), anyString(), anyString(),
                nullable(Integer.class));
        verify(fixture.writer, never()).concludeIfCurrent(anyLong(), anyLong(), anyInt(), anyInt(),
                nullable(String.class), anyString(), anyString(), anyString(), any(Date.class));
    }

    @Test
    void invalidAnswerIsRewrittenOnceAndAStillInvalidMemberIsSkipped() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("C", "营收稳健", "F-1", "A", "需求偏强", "L-1"),
                answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-9", "A", "需求偏强", "L-1"),
                answer("A", "营收稳健", "F-9", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(answerOf(view, "乙").getBooleanValue("skipped"), answerOf(view, "乙").toJSONString());
        JSONArray conclusions = view.getResult().getJSONArray("conclusions");
        assertEquals(2, conclusions.size());
        assertEquals(2, conclusions.getJSONObject(0).getIntValue("total"));
        assertEquals(0, view.getResult().getJSONArray("disputes").size());
    }

    @Test
    void issueCardPromptShowsTheDomainFieldInItsJsonTemplateAndAsksOnlyForPublishedFacts() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        String prompt = fixture.moderatorPrompts.get(0);
        String template = prompt.substring(prompt.indexOf("只输出 JSON"));
        // Models copy the output template; a field only described in prose gets dropped.
        assertTrue(template.contains("\"stock\":"), template);
        assertTrue(prompt.contains("不要再问股价涨跌"), prompt);
        assertTrue(prompt.contains("尚未发布"), prompt);
    }

    @Test
    void memberWhoseModelIsDownIsSkippedWithTheRealReasonWhenTwoOthersAnswered() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.down.add("divergence:乙");

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        JSONObject skipped = answerOf(view, "乙");
        assertTrue(skipped.getBooleanValue("skipped"), skipped.toJSONString());
        assertTrue(skipped.getString("reason").contains("模型调用失败"), skipped.toJSONString());
        // A provider failure is not a format problem, so there is no rewrite attempt.
        assertEquals(1, fixture.calls.stream().filter("divergence:乙"::equals).count(), fixture.calls.toString());
        assertEquals(2, view.getResult().getIntValue("answeredCount"));
    }

    @Test
    void meetingPausesWhenFewerThanTwoMembersCanAnswerAndResumeAsksThemAgain() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.down.add("divergence:甲");
        fixture.down.add("divergence:乙");

        fixture.service.run(meetingId, EPOCH);

        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(fixture.workflow).markReviewedEvidenceBlocked(eq(meetingId), reason.capture());
        assertTrue(reason.getValue().contains("模型调用失败") && reason.getValue().contains("甲")
                && reason.getValue().contains("乙"), reason.getValue());
        RoundtableV2ViewVO paused = controller.getRoundtableV2(meetingId).getData();
        assertEquals(1, paused.getAnswers().size(), paused.getAnswers().toJSONString());
        verify(fixture.writer, never()).concludeIfCurrent(anyLong(), anyLong(), anyInt(), anyInt(),
                nullable(String.class), anyString(), anyString(), anyString(), any(Date.class));

        fixture.down.clear();
        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(!answerOf(view, "甲").getBooleanValue("skipped"), answerOf(view, "甲").toJSONString());
        assertEquals(3, view.getResult().getIntValue("answeredCount"));
        assertEquals(1, fixture.calls.stream().filter("divergence:丙"::equals).count(), fixture.calls.toString());
    }

    @Test
    void modelFailureDuringCrossExamPausesInsteadOfLookingLikeAnUnsourcedNumber() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "B", "需求偏弱", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.down.add("cross_exam:甲");

        fixture.service.run(meetingId, EPOCH);

        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
        assertTrue(controller.getRoundtableV2(meetingId).getData().getCrossExams().isEmpty());

        fixture.down.clear();
        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        fixture.service.run(meetingId, EPOCH);

        JSONObject exam = controller.getRoundtableV2(meetingId).getData().getCrossExams().getJSONObject(0);
        assertTrue(!exam.getBooleanValue("failed") && exam.getString("question") != null, exam.toJSONString());
    }

    @Test
    void factIdsInsideReasonTextAreNotTreatedAsNegativeNumbers() {
        Fixture fixture = new Fixture();
        String cited = answer("A", "排产数据缺失（G-1），营收见F-1", "G-1", "A", "订单待核（L-1）", "L-1");
        fixture.answers("甲", cited);
        fixture.answers("乙", cited);
        fixture.answers("丙", cited);

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertEquals(0, fixture.calls.stream().filter(call -> call.startsWith("divergence_rewrite")).count(),
                fixture.calls.toString());
        assertEquals(3, fixture.calls.stream().filter(call -> call.startsWith("divergence:")).count(), fixture.calls.toString());
        for (Object item : view.getFinalAnswers()) {
            JSONObject finalAnswer = (JSONObject) item;
            assertTrue(!finalAnswer.getBooleanValue("fallback"), finalAnswer.toJSONString());
        }
    }

    @Test
    void rewrittenAnswerKeepsTheReasonForTheRewrite() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("C", "营收稳健", "F-1", "A", "需求偏强", "L-1"),
                answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        JSONObject first = answerOf(view, "甲");
        assertTrue(!first.getBooleanValue("skipped"), first.toJSONString());
        assertTrue(first.getString("rewriteReason") != null && !first.getString("rewriteReason").isBlank(),
                first.toJSONString());
        assertTrue(answerOf(view, "乙").getString("rewriteReason") == null, answerOf(view, "乙").toJSONString());
    }

    @Test
    void consensusRestingMostlyOnGapsIsMarkedAsEvidenceThin() {
        Fixture fixture = new Fixture();
        String gapDriven = answer("A", "关税影响未能核实，暂按中性", "G-1", "A", "营收稳健", "F-1");
        fixture.answers("甲", gapDriven);
        fixture.answers("乙", gapDriven);
        fixture.answers("丙", gapDriven);

        fixture.service.run(meetingId, EPOCH);

        JSONArray conclusions = controller.getRoundtableV2(meetingId).getData().getResult().getJSONArray("conclusions");
        assertTrue(conclusions.getJSONObject(0).getBooleanValue("evidenceThin"), conclusions.toJSONString());
        assertTrue(!conclusions.getJSONObject(1).getBooleanValue("evidenceThin"), conclusions.toJSONString());
        ArgumentCaptor<String> conclusion = ArgumentCaptor.forClass(String.class);
        verify(fixture.writer).concludeIfCurrent(eq(meetingId), eq(EPOCH), anyInt(), anyInt(),
                nullable(String.class), anyString(), anyString(), conclusion.capture(), any(Date.class));
        assertEquals(1, conclusion.getValue().split("证据不足的共识", -1).length - 1, conclusion.getValue());
    }

    @Test
    void resumeReusesStoredIssueCardFactsAndAnswers() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        doThrow(new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE"))
                .doNothing()
                .when(fixture.writer).concludeIfCurrent(anyLong(), anyLong(), anyInt(), anyInt(),
                        nullable(String.class), anyString(), anyString(), anyString(), any(Date.class));

        fixture.service.run(meetingId, EPOCH);
        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        fixture.service.run(meetingId, EPOCH);

        // moderator 1 + first-round answers 3 + red team on the leading consensus 1 + re-estimates 3
        verify(fixture.orchestrator, times(8)).callModelStrict(anyString(), anyString(), anyString(),
                nullable(Integer.class));
        verify(fixture.reviewed, times(3)).search(anyString(), anyString(), anyString(), anyLong());
        verify(fixture.writer, times(2)).concludeIfCurrent(anyLong(), anyLong(), anyInt(), anyInt(),
                nullable(String.class), anyString(), anyString(), anyString(), any(Date.class));
    }

    @Test
    void crossExamRedTeamAndReestimateDriveTheFinalSummary() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "2025年营收：100亿元，增长稳健", "F-1", "A", "订单需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "B", "订单规模80亿元，增速放缓", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求回暖", "L-1"));
        fixture.finalAnswers("乙", withChangeReason(answer("A", "营收稳健", "F-1", "A", "需求回暖", "L-1"),
                "Q2", "质询后认可需求偏强"));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        JSONObject dispute = view.getAgenda().getJSONArray("disputes").getJSONObject(0);
        assertEquals("Q2", dispute.getString("questionId"));
        assertEquals("甲", dispute.getJSONObject("a").getString("memberName"));
        assertEquals("乙", dispute.getJSONObject("b").getString("memberName"));
        assertEquals("丙", view.getRedTeam().getString("memberName"));
        assertEquals("Q1", view.getRedTeam().getString("questionId"));
        assertEquals(1, view.getCrossExams().size());
        assertTrue(view.getCrossExams().getJSONObject(0).getString("question").length() > 0);
        assertTrue(view.getCrossExams().getJSONObject(0).getString("reply").length() > 0);
        assertTrue(fixture.calls.containsAll(List.of("cross_exam:甲", "cross_exam:乙", "red_team:丙",
                "reestimate:甲", "reestimate:乙", "reestimate:丙")), fixture.calls.toString());
        assertEquals(3, view.getFinalAnswers().size());

        assertEquals(2, view.getResult().getJSONArray("conclusions").size());
        assertEquals(0, view.getResult().getJSONArray("disputes").size());
        JSONArray changes = view.getResult().getJSONArray("changes");
        assertEquals(1, changes.size());
        assertEquals("乙", changes.getJSONObject(0).getString("memberName"));
        assertEquals("Q2", changes.getJSONObject(0).getString("questionId"));
        assertEquals("B", changes.getJSONObject(0).getString("from"));
        assertEquals("A", changes.getJSONObject(0).getString("to"));
        assertEquals("质询后认可需求偏强", changes.getJSONObject(0).getString("reason"));
    }

    @Test
    void crossExamQuestionWithUnsourcedNumberIsRewrittenOnceThenDropped() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "订单需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "B", "增速放缓", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求回暖", "L-1"));
        fixture.crossExam("甲", "营收已到150亿元，你的判断站不住。", "营收已到150亿元，你的判断站不住。");

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(view.getCrossExams().getJSONObject(0).getBooleanValue("failed"),
                view.getCrossExams().toJSONString());
        assertTrue(!fixture.calls.contains("cross_exam:乙"), fixture.calls.toString());
        assertTrue(fixture.calls.contains("reestimate:乙"), fixture.calls.toString());
    }

    @Test
    void invalidReestimateFallsBackToTheFirstRoundAnswer() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "订单需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "B", "增速放缓", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求回暖", "L-1"));
        fixture.finalAnswers("丙", "不是 JSON", "还是不是 JSON");

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        JSONObject third = null;
        for (Object item : view.getFinalAnswers()) {
            if ("丙".equals(((JSONObject) item).getString("memberName"))) third = (JSONObject) item;
        }
        assertTrue(third != null && third.getBooleanValue("fallback"), view.getFinalAnswers().toJSONString());
        assertEquals(1, view.getResult().getJSONArray("disputes").size());
        assertEquals(3, view.getResult().getIntValue("answeredCount"));
    }

    @Test
    void factDisputeWithNewEvidenceRunsASecondRoundAndResumesWithoutRepeating() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "订单需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "B", "增速放缓", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求回暖", "L-1"));
        fixture.finalAnswers("乙", answer("A", "营收稳健", "F-1", "B", "增速放缓", "L-1"),
                withChangeReason(answer("A", "营收稳健", "F-1", "A", "出货同比增长20%", "F-2"),
                        "Q2", "补证显示出货同比增长20%"));
        fixture.classifyReplies.add("{\"disputes\":[{\"questionId\":\"Q2\",\"type\":\"FACT\","
                + "\"crux\":\"若最新季度出货同比为正，则需求偏强；否则偏弱\","
                + "\"evidenceQuestion\":\"最新季度出货同比增速是多少？\"}]}");
        doThrow(new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE"))
                .doNothing()
                .when(fixture.writer).concludeIfCurrent(anyLong(), anyLong(), anyInt(), anyInt(),
                        nullable(String.class), anyString(), anyString(), anyString(), any(Date.class));

        fixture.service.run(meetingId, EPOCH);
        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        int callsAfterFirstRun = fixture.calls.size();
        fixture.service.run(meetingId, EPOCH);

        assertEquals(callsAfterFirstRun, fixture.calls.size(), "a resumed meeting must not repeat model calls");
        verify(fixture.reviewed, times(4)).search(anyString(), anyString(), anyString(), anyLong());
        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertEquals("FACT", view.getClassification().getJSONArray("disputes").getJSONObject(0).getString("type"));
        assertEquals("F-2", view.getTopUp().getJSONArray("added").getJSONObject(0).getString("id"));
        assertEquals(3, view.getSecondFinalAnswers().size());
        assertEquals(2, fixture.calls.stream().filter("cross_exam:甲"::equals).count(), fixture.calls.toString());
        JSONObject result = view.getResult();
        assertEquals(2, result.getIntValue("loops"));
        assertEquals(0, result.getJSONArray("disputes").size());
        JSONObject resolved = null;
        for (Object item : result.getJSONArray("conclusions")) {
            if ("Q2".equals(((JSONObject) item).getString("questionId"))) resolved = (JSONObject) item;
        }
        assertTrue(resolved != null && resolved.getBooleanValue("resolvedAfterDispute"), result.toJSONString());
        assertEquals("FACT", resolved.getString("type"));
    }

    @Test
    void nonFactDisputeIsAnnotatedAndStopsAfterOneRound() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "订单需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "B", "增速放缓", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求回暖", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertEquals(1, view.getResult().getIntValue("loops"));
        JSONObject dispute = view.getResult().getJSONArray("disputes").getJSONObject(0);
        assertEquals("INTERPRETATION", dispute.getString("type"));
        assertEquals("若需求回暖，则偏强；否则偏弱", dispute.getString("crux"));
        assertEquals("UNRESOLVED", dispute.getString("status"));
        verify(fixture.reviewed, times(3)).search(anyString(), anyString(), anyString(), anyLong());
        assertEquals(3, fixture.calls.stream().filter(call -> call.startsWith("reestimate:")).count());
    }

    @Test
    void evidenceTopUpStopsAtThreeQuestionsAndMarksTheRestUndecided() {
        Fixture fixture = new Fixture();
        fixture.card = FOUR_QUESTION_CARD;
        fixture.answers("甲", fourAnswers("A"));
        fixture.answers("乙", fourAnswers("B"));
        fixture.answers("丙", fourAnswers("A"));
        StringBuilder classify = new StringBuilder("{\"disputes\":[");
        for (int i = 1; i <= 4; i++) {
            if (i > 1) classify.append(",");
            classify.append("{\"questionId\":\"Q").append(i).append("\",\"type\":\"FACT\",")
                    .append("\"crux\":\"若事实").append(i).append("成立，则选A；否则选B\",")
                    .append("\"evidenceQuestion\":\"补证题").append(i).append("\"}");
        }
        fixture.classifyReplies.add(classify.append("]}").toString());

        fixture.service.run(meetingId, EPOCH);

        verify(fixture.reviewed, times(6)).search(anyString(), anyString(), anyString(), anyLong());
        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertEquals(List.of("Q4"), view.getTopUp().getJSONArray("overBudget").toJavaList(String.class));
        assertEquals(1, view.getResult().getIntValue("loops"));
        for (Object item : view.getResult().getJSONArray("disputes")) {
            assertEquals("UNDECIDED", ((JSONObject) item).getString("status"), item.toString());
        }
    }

    @Test
    void invalidClassificationLeavesDisputesUnclassifiedWithoutBlocking() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "订单需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "B", "增速放缓", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求回暖", "L-1"));
        fixture.classifyReplies.add("{\"disputes\":[{\"questionId\":\"Q2\",\"type\":\"WHATEVER\"}]}");

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertEquals("UNCLASSIFIED", view.getResult().getJSONArray("disputes").getJSONObject(0).getString("status"));
        assertEquals(2, fixture.calls.stream().filter("classify:主持人"::equals).count(), fixture.calls.toString());
        verify(fixture.writer).concludeIfCurrent(anyLong(), anyLong(), anyInt(), anyInt(),
                nullable(String.class), anyString(), anyString(), anyString(), any(Date.class));
    }

    private static final String FOUR_QUESTION_CARD = "{\"topic\":\"四个驱动因素\",\"subQuestions\":["
            + "{\"id\":\"Q1\",\"title\":\"因素一\",\"options\":[{\"id\":\"A\",\"label\":\"正\"},{\"id\":\"B\",\"label\":\"负\"}]},"
            + "{\"id\":\"Q2\",\"title\":\"因素二\",\"options\":[{\"id\":\"A\",\"label\":\"正\"},{\"id\":\"B\",\"label\":\"负\"}]},"
            + "{\"id\":\"Q3\",\"title\":\"因素三\",\"options\":[{\"id\":\"A\",\"label\":\"正\"},{\"id\":\"B\",\"label\":\"负\"}]},"
            + "{\"id\":\"Q4\",\"title\":\"因素四\",\"options\":[{\"id\":\"A\",\"label\":\"正\"},{\"id\":\"B\",\"label\":\"负\"}]}],"
            + "\"evidenceQuestions\":[\"2025年营收是多少？\",\"最新订单规模是多少？\",\"海外关税影响多大？\"]}";

    private static String fourAnswers(String option) {
        JSONArray answers = new JSONArray();
        for (int i = 1; i <= 4; i++) answers.add(answerItem("Q" + i, option, "依据营收与订单资料", "F-1"));
        JSONObject root = new JSONObject();
        root.put("answers", answers);
        return root.toJSONString();
    }

    @Test
    void stockTopicPutsEastmoneyFactsFirstAndMembersMayCiteTheirNumbers() {
        Fixture fixture = new Fixture();
        fixture.card = CARD.replace("\"referenceClass\":null", "\"referenceClass\":null,"
                + "\"stock\":{\"symbol\":\"300750\",\"market\":\"sz\"}");
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY")).thenReturn(stockBundle());
        String reply = answer("A", "市盈率22.5倍，处于近一年较低分位", "F-1", "A", "订单需求偏强", "L-1");
        fixture.answers("甲", reply);
        fixture.answers("乙", reply);
        fixture.answers("丙", reply);

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        JSONObject firstFact = view.getFacts().getJSONObject(0);
        assertEquals("F-1", firstFact.getString("id"));
        assertEquals("东方财富", firstFact.getString("source"));
        assertTrue(firstFact.getBooleanValue("singleSource"), firstFact.toJSONString());
        assertTrue(view.getFacts().toJSONString().contains("22.50倍"), view.getFacts().toJSONString());
        assertTrue(view.getFacts().toJSONString().contains("近60个交易日涨跌幅"), view.getFacts().toJSONString());
        assertTrue(!answerOf(view, "甲").getBooleanValue("skipped"), answerOf(view, "甲").toJSONString());
        assertTrue(answerOf(view, "甲").toJSONString().contains("22.5倍，"), answerOf(view, "甲").toJSONString());
        verify(fixture.eastmoney, times(1)).buildBundle("300750", "sz", "DAY");
        verify(fixture.reviewed, times(3)).search(anyString(), anyString(), anyString(), anyLong());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(fixture.orchestrator, org.mockito.Mockito.atLeastOnce())
                .callModelStrict(anyString(), org.mockito.ArgumentMatchers.contains("主持人"), prompts.capture(),
                        nullable(Integer.class));
        assertTrue(prompts.getAllValues().get(0).contains("东方财富"), prompts.getAllValues().get(0));
    }

    @Test
    void generalTopicNeverCallsEastmoney() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        verify(fixture.eastmoney, never()).buildBundle(anyString(), anyString(), anyString());
    }

    @Test
    void eastmoneyFailureBecomesAGapAndTheMeetingContinues() {
        Fixture fixture = new Fixture();
        fixture.card = CARD.replace("\"referenceClass\":null", "\"referenceClass\":null,"
                + "\"stock\":{\"symbol\":\"300750\",\"market\":\"sz\"}");
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY")).thenThrow(new IllegalStateException("quote_unavailable"));
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertEquals("G-1", view.getFacts().getJSONObject(0).getString("id"));
        assertTrue(view.getFacts().getJSONObject(0).getString("question").contains("东方财富"));
        verify(fixture.writer).concludeIfCurrent(anyLong(), anyLong(), anyInt(), anyInt(),
                nullable(String.class), anyString(), anyString(), anyString(), any(Date.class));
    }

    @Test
    void invalidStockCodeIsTreatedAsAGeneralTopic() {
        Fixture fixture = new Fixture();
        fixture.card = CARD.replace("\"referenceClass\":null", "\"referenceClass\":null,"
                + "\"stock\":{\"symbol\":\"CATL\",\"market\":\"sz\"}");
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        verify(fixture.eastmoney, never()).buildBundle(anyString(), anyString(), anyString());
        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(view.getIssueCard().get("stock") == null, view.getIssueCard().toJSONString());
    }

    @Test
    void stockMeetingAddsReferenceClassAndAnOutcomeDistributionWithMinority() {
        Fixture fixture = stockFixtureWithHistory();
        String base = answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1");
        fixture.answers("甲", withOutcome(base, "下跌8%–23%的可能性不高", 0, 0.1, 0.5, 0.3, 0.1));
        fixture.answers("乙", withOutcome(base, "估值偏高", 0.1, 0.5, 0.3, 0.1, 0));
        fixture.answers("丙", withOutcome(base, "需求稳定", 0, 0.1, 0.5, 0.3, 0.1));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(view.getFacts().toJSONString().contains("历史参照"), view.getFacts().toJSONString());
        assertTrue(view.getFacts().toJSONString().contains("P50"), view.getFacts().toJSONString());
        JSONArray options = view.getOutcomeQuestion().getJSONArray("options");
        assertEquals(List.of("跌幅超过23%", "下跌8%–23%", "在±8%以内", "上涨8%–23%", "涨幅超过23%"),
                options.stream().map(option -> ((JSONObject) option).getString("label"))
                        .collect(java.util.stream.Collectors.toList()));
        assertTrue(!answerOf(view, "甲").getBooleanValue("skipped"), answerOf(view, "甲").toJSONString());

        JSONObject outcome = view.getResult().getJSONObject("outcome");
        assertEquals("C", outcome.getString("aggregateMode"));
        JSONObject middle = outcome.getJSONArray("options").getJSONObject(2);
        assertEquals(0.4333, middle.getDoubleValue("mean"), 0.001);
        assertEquals(List.of("乙"), outcome.getJSONArray("minority").stream()
                .map(member -> ((JSONObject) member).getString("memberName"))
                .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void outcomeQuestionStartsFromTheLastTradingDayClose() {
        Fixture fixture = stockFixtureWithHistory();
        String base = answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1");
        String valid = withOutcome(base, "需求稳定", 0, 0.1, 0.5, 0.3, 0.1);
        fixture.answers("甲", valid);
        fixture.answers("乙", valid);
        fixture.answers("丙", valid);

        fixture.service.run(meetingId, EPOCH);

        // The meeting date may be a weekend; the interval is measured from the latest close actually available.
        JSONObject outcome = controller.getRoundtableV2(meetingId).getData().getOutcomeQuestion();
        assertEquals("2026-10-16", outcome.getString("baseDate"), outcome.toJSONString());
        assertEquals(0, new java.math.BigDecimal("102").compareTo(outcome.getBigDecimal("baseClose")), outcome.toJSONString());
        assertTrue(outcome.getString("title").contains("以2026-10-16收盘价为起点"), outcome.toJSONString());
    }

    @Test
    void eastmoneyHistoryIsRetriedOnceBeforeGivingUp() {
        Fixture fixture = stockFixtureWithHistory();
        when(fixture.eastmoney.fetchDailyHistory(eq("300750"), eq("sz"), anyInt()))
                .thenThrow(new IllegalStateException("unexpected end of stream")).thenReturn(history());
        String valid = withOutcome(answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"), "需求稳定", 0, 0.1, 0.5, 0.3, 0.1);
        fixture.answers("甲", valid);
        fixture.answers("乙", valid);
        fixture.answers("丙", valid);

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(view.getOutcomeQuestion() != null, view.getFacts().toJSONString());
        assertEquals("东方财富", factWhoseQuestionContains(view, "历史参照").getString("source"));
    }

    @Test
    void historyFallsBackToTencentAndTheFactNamesItsSource() {
        Fixture fixture = stockFixtureWithHistory();
        when(fixture.eastmoney.fetchDailyHistory(eq("300750"), eq("sz"), anyInt()))
                .thenThrow(new IllegalStateException("unexpected end of stream"));
        when(fixture.eastmoney.fetchTencentDailyHistory(eq("300750"), eq("sz"), anyInt())).thenReturn(history());
        String valid = withOutcome(answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"), "需求稳定", 0, 0.1, 0.5, 0.3, 0.1);
        fixture.answers("甲", valid);
        fixture.answers("乙", valid);
        fixture.answers("丙", valid);

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(view.getOutcomeQuestion() != null, view.getFacts().toJSONString());
        JSONObject reference = factWhoseQuestionContains(view, "历史参照");
        assertEquals("腾讯证券", reference.getString("source"));
        assertTrue(reference.getString("statement").contains("腾讯前复权日K"), reference.toJSONString());
    }

    @Test
    void stockFactsAreRoundedToTwoDecimalsWithPlainDates() {
        Fixture fixture = stockFixtureWithHistory();
        EastmoneyMarketDataService.MarketDataBundle bundle = stockBundle();
        Map<String, Object> valuation = new java.util.LinkedHashMap<>();
        valuation.put("tradeDate", "2026-10-16 00:00:00");
        valuation.put("peTtm", new java.math.BigDecimal("15.07936443"));
        valuation.put("pePercentile1y", new java.math.BigDecimal("0.3846153846"));
        valuation.put("pbMrq", new java.math.BigDecimal("3.37876797"));
        valuation.put("pbPercentile1y", new java.math.BigDecimal("0.3846153846"));
        bundle.getPayload().put("valuation", valuation);
        bundle.getPayload().put("financials", List.of(Map.of("reportDate", "2026-06-30 00:00:00",
                "revenue", new java.math.BigDecimal("276917000000"),
                "revenueYoyPct", new java.math.BigDecimal("54.800369148545"),
                "netProfit", new java.math.BigDecimal("43284000000"),
                "netProfitYoyPct", new java.math.BigDecimal("41.983941749454"))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY")).thenReturn(bundle);
        String valid = withOutcome(answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"), "需求稳定", 0, 0.1, 0.5, 0.3, 0.1);
        fixture.answers("甲", valid);
        fixture.answers("乙", valid);
        fixture.answers("丙", valid);

        fixture.service.run(meetingId, EPOCH);

        String facts = controller.getRoundtableV2(meetingId).getData().getFacts().toJSONString();
        assertTrue(facts.contains("市盈率TTM（2026-10-16）：15.08倍"), facts);
        assertTrue(facts.contains("市盈率TTM近一年分位（2026-10-16，0%为近一年最低）：0.38%"), facts);
        assertTrue(facts.contains("营业总收入同比（报告期 2026-06-30）：54.80%"), facts);
        assertTrue(!facts.contains("00:00:00"), facts);
    }

    @Test
    void outcomeProbabilitiesNotSummingToOneAreRewrittenOnce() {
        Fixture fixture = stockFixtureWithHistory();
        String base = answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1");
        String valid = withOutcome(base, "需求稳定", 0, 0.1, 0.5, 0.3, 0.1);
        fixture.answers("甲", withOutcome(base, "需求稳定", 0, 0.1, 0.4, 0.2, 0.1), valid);
        fixture.answers("乙", valid);
        fixture.answers("丙", valid);

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(!answerOf(view, "甲").getBooleanValue("skipped"), answerOf(view, "甲").toJSONString());
        assertEquals(2, fixture.calls.stream().filter("divergence:甲"::equals).count(), fixture.calls.toString());
    }

    @Test
    void generalTopicHasNoOutcomeQuestion() {
        Fixture fixture = new Fixture();
        fixture.answers("甲", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("乙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));
        fixture.answers("丙", answer("A", "营收稳健", "F-1", "A", "需求偏强", "L-1"));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        assertTrue(view.getOutcomeQuestion() == null);
        assertTrue(view.getResult().get("outcome") == null, view.getResult().toJSONString());
    }

    /** Runs a meeting that touches every section and writes the API payload as the frontend's test sample. */
    @Test
    void richMeetingProducesEverySectionOfTheFrontendSample() throws Exception {
        Fixture fixture = stockFixtureWithHistory();
        // Q1 is a first-round consensus (red team target); Q2 splits, and 丙 moves to 乙's side after cross-examination.
        String agree = answer("A", "市盈率22.5倍，处于近一年较低分位", "F-4", "A", "订单需求偏强", "L-1");
        String split = answer("A", "营收同比8.5%，增速一般", "F-9", "B", "订单规模80亿元，增速放缓", "L-1");
        fixture.answers("甲", withOutcome(agree, "下跌8%–23%的可能性不高", 0, 0.1, 0.5, 0.3, 0.1));
        fixture.answers("乙", withOutcome(split, "估值不低", 0.1, 0.5, 0.3, 0.1, 0));
        fixture.answers("丙", withOutcome(agree, "需求稳定", 0, 0.1, 0.5, 0.3, 0.1));
        fixture.finalAnswers("丙", withOutcome(withChangeReason(
                answer("A", "市盈率22.5倍，处于近一年较低分位", "F-4", "B", "订单增速放缓", "L-1"),
                "Q2", "质询后认为订单增速放缓的证据更扎实"), "需求稳定", 0, 0.1, 0.5, 0.3, 0.1));

        fixture.service.run(meetingId, EPOCH);

        RoundtableV2ViewVO view = controller.getRoundtableV2(meetingId).getData();
        java.nio.file.Path sample = java.nio.file.Paths.get("target", "roundtable-v2-sample.json");
        java.nio.file.Files.writeString(sample, JSON.toJSONString(view, true));
        JSONObject result = view.getResult();
        assertTrue(view.getOutcomeQuestion() != null && result.getJSONObject("outcome") != null);
        assertEquals(1, result.getJSONArray("changes").size());
        assertEquals("INTERPRETATION", result.getJSONArray("disputes").getJSONObject(0).getString("type"));
        assertTrue(!view.getCrossExams().isEmpty() && view.getRedTeam() != null);
    }

    private Fixture stockFixtureWithHistory() {
        Fixture fixture = new Fixture();
        fixture.card = CARD.replace("\"referenceClass\":null", "\"referenceClass\":null,\"horizonTradingDays\":60,"
                + "\"stock\":{\"symbol\":\"300750\",\"market\":\"sz\"}");
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY")).thenReturn(stockBundle());
        when(fixture.eastmoney.fetchDailyHistory(eq("300750"), eq("sz"), anyInt())).thenReturn(history());
        return fixture;
    }

    /** Closes alternate 100/102: daily volatility ≈ 1.98%, so σ over 60 days ≈ 15.4% → bounds 8% and 23%. */
    private static List<Map<String, Object>> history() {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Map<String, Object> bar = new java.util.LinkedHashMap<>();
            bar.put("date", i == 399 ? "2026-10-16" : "d" + i);
            bar.put("close", new java.math.BigDecimal(i % 2 == 0 ? "100" : "102"));
            history.add(bar);
        }
        return history;
    }

    private static JSONObject factWhoseQuestionContains(RoundtableV2ViewVO view, String text) {
        for (Object item : view.getFacts()) {
            JSONObject fact = (JSONObject) item;
            if (fact.getString("question") != null && fact.getString("question").contains(text)) return fact;
        }
        throw new AssertionError("no fact about " + text + " in " + view.getFacts());
    }

    private static String withOutcome(String answerJson, String reason, double... probabilities) {
        JSONObject root = JSON.parseObject(answerJson);
        JSONObject outcome = new JSONObject(true);
        outcome.put("questionId", "OUTCOME");
        JSONObject distribution = new JSONObject(true);
        String[] ids = {"A", "B", "C", "D", "E"};
        for (int i = 0; i < ids.length; i++) distribution.put(ids[i], probabilities[i]);
        outcome.put("probabilities", distribution);
        JSONObject because = new JSONObject(true);
        because.put("text", reason);
        because.put("factIds", List.of("F-1"));
        outcome.put("reasons", List.of(because));
        outcome.put("falsifier", "若需求明显转弱则判断失效");
        root.getJSONArray("answers").add(outcome);
        return root.toJSONString();
    }

    private static EastmoneyMarketDataService.MarketDataBundle stockBundle() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("symbol", "300750");
        payload.put("market", "sz");
        payload.put("stockDetail", Map.of("changePercent", new java.math.BigDecimal("1.23"),
                "marketCap", new java.math.BigDecimal("850000000000")));
        payload.put("valuation", Map.of("tradeDate", "2026-10-16", "peTtm", new java.math.BigDecimal("22.5"),
                "pePercentile1y", new java.math.BigDecimal("35.0"), "pbMrq", new java.math.BigDecimal("4.8"),
                "pbPercentile1y", new java.math.BigDecimal("40.0")));
        payload.put("financials", List.of(Map.of("reportDate", "2026-06-30 00:00:00",
                "revenue", new java.math.BigDecimal("178000000000"), "revenueYoyPct", new java.math.BigDecimal("8.5"),
                "netProfit", new java.math.BigDecimal("30500000000"), "netProfitYoyPct", new java.math.BigDecimal("12.3"))));
        Map<String, Object> indicators = new java.util.LinkedHashMap<>();
        indicators.put("asOfDate", "2026-10-16");
        indicators.put("close", new java.math.BigDecimal("180.50"));
        indicators.put("ma60", new java.math.BigDecimal("175.00"));
        indicators.put("priceVsMa20Pct", new java.math.BigDecimal("2.10"));
        indicators.put("return5Pct", new java.math.BigDecimal("-1.20"));
        indicators.put("return20Pct", new java.math.BigDecimal("3.40"));
        indicators.put("historicalVolatility20Pct", new java.math.BigDecimal("28.50"));
        payload.put("technicalIndicators", indicators);
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            Map<String, Object> bar = new java.util.LinkedHashMap<>();
            java.math.BigDecimal close = new java.math.BigDecimal(150 + i);
            bar.put("date", "day-" + i);
            bar.put("close", close);
            bar.put("high", close.add(java.math.BigDecimal.ONE));
            bars.add(bar);
        }
        bars.get(bars.size() - 1).put("close", new java.math.BigDecimal("180.50"));
        payload.put("dailyKline", bars);
        return new EastmoneyMarketDataService.MarketDataBundle(payload, List.of(), List.of());
    }

    private static String withChangeReason(String answerJson, String questionId, String reason) {
        JSONObject root = JSON.parseObject(answerJson);
        for (Object item : root.getJSONArray("answers")) {
            JSONObject answer = (JSONObject) item;
            if (questionId.equals(answer.getString("questionId"))) answer.put("changeReason", reason);
        }
        return root.toJSONString();
    }

    private static String answer(String q1Option, String q1Reason, String q1Fact,
                                 String q2Option, String q2Reason, String q2Fact) {
        JSONArray answers = new JSONArray();
        answers.add(answerItem("Q1", q1Option, q1Reason, q1Fact));
        answers.add(answerItem("Q2", q2Option, q2Reason, q2Fact));
        JSONObject root = new JSONObject();
        root.put("answers", answers);
        return root.toJSONString();
    }

    private static JSONObject answerItem(String questionId, String optionId, String reason, String factId) {
        JSONObject item = new JSONObject(true);
        item.put("questionId", questionId);
        item.put("optionId", optionId);
        JSONObject because = new JSONObject(true);
        because.put("text", reason);
        because.put("factIds", List.of(factId));
        item.put("reasons", List.of(because));
        item.put("falsifier", "若下一季度需求明显转弱则判断失效");
        return item;
    }

    private static List<String> factIdsAndTiers(JSONArray facts) {
        List<String> ids = new ArrayList<>();
        for (Object fact : facts) {
            JSONObject json = (JSONObject) fact;
            ids.add(json.getString("id") + ":" + json.getString("tier"));
        }
        return ids;
    }

    private static JSONObject answerOf(RoundtableV2ViewVO view, String memberName) {
        for (Object answer : view.getAnswers()) {
            JSONObject json = (JSONObject) answer;
            if (memberName.equals(json.getString("memberName"))) return json;
        }
        throw new AssertionError("no answer for " + memberName + " in " + view.getAnswers());
    }

    private final class Fixture {
        private final AiMeetingRoom room = new AiMeetingRoom();
        private final AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        private final AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        private final MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        private final MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        private final MeetingReviewedFinalizationWriter writer = mock(MeetingReviewedFinalizationWriter.class);
        private final MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        private final EastmoneyMarketDataService eastmoney = mock(EastmoneyMarketDataService.class);
        private final List<String> moderatorReplies = new ArrayList<>();
        private String card = CARD;
        private final Map<String, List<String>> memberReplies = new HashMap<>();
        private final Map<String, List<String>> finalReplies = new HashMap<>();
        private final Map<String, List<String>> crossExamReplies = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final List<String> classifyReplies = new ArrayList<>();
        private final List<String> moderatorPrompts = new ArrayList<>();
        /** "stage:name" entries whose model call fails as if the provider were down. */
        private final java.util.Set<String> down = new java.util.HashSet<>();
        private final RoundtableV2Service service = new RoundtableV2Service();

        private Fixture() {
            room.setId(meetingId);
            room.setTitle("宁德时代未来三个月走势");
            room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
            room.setRunEpoch(EPOCH);
            room.setCurrentRound(0);
            when(rooms.selectById(meetingId)).thenReturn(room);
            when(rooms.updateStatusIfCurrentAndEpoch(eq(meetingId), anyInt(), eq(EPOCH), anyInt()))
                    .thenAnswer(invocation -> {
                        room.setStatus(invocation.getArgument(3));
                        return 1;
                    });
            List<AiMeetingMember> seats = new ArrayList<>();
            long id = 21L;
            for (String name : List.of("甲", "乙", "丙")) {
                AiMeetingMember member = new AiMeetingMember();
                member.setId(id++);
                member.setMeetingId(meetingId);
                member.setMemberName(name);
                member.setModelType("deepseek-chat");
                member.setPersona(name + "的分析视角");
                seats.add(member);
            }
            when(members.selectByMeetingId(meetingId)).thenReturn(seats);
            when(reviewed.isEnabled()).thenReturn(true);
            when(reviewed.search(anyString(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
                String question = invocation.getArgument(1);
                if (question.contains("营收")) {
                    return crossChecked(invocation.getArgument(0), question, invocation.getArgument(2),
                            "2025年营收：100亿元");
                }
                if (question.contains("出货")) {
                    return crossChecked(invocation.getArgument(0), question, invocation.getArgument(2),
                            "最新季度出货同比增长20%");
                }
                if (question.contains("订单")) {
                    return provisional(invocation.getArgument(0), question, invocation.getArgument(2),
                            "待核验的订单规模80亿");
                }
                throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_UNVERIFIED");
            });
            when(orchestrator.resolveMeetingModelType(meetingId)).thenReturn("deepseek-chat");
            when(orchestrator.callModelStrict(anyString(), anyString(), anyString(), nullable(Integer.class)))
                    .thenAnswer(invocation -> {
                        String system = invocation.getArgument(1);
                        MeetingCallContext.Frame frame = MeetingCallContext.current();
                        String stage = frame == null ? "none" : frame.stage.replace("_rewrite", "");
                        if (system.contains("主持人")) {
                            calls.add(stage + ":主持人");
                            moderatorPrompts.add(invocation.getArgument(2));
                            if (MeetingCallContext.CLASSIFY.equals(stage)) {
                                return classifyReplies.isEmpty() ? DEFAULT_CLASSIFY
                                        : classifyReplies.size() > 1 ? classifyReplies.remove(0) : classifyReplies.get(0);
                            }
                            return moderatorReplies.isEmpty() ? card : moderatorReplies.remove(0);
                        }
                        String name = memberIn(system);
                        calls.add(stage + ":" + name);
                        if (down.contains(stage + ":" + name)) {
                            throw new IllegalStateException("meeting_selected_model_unavailable");
                        }
                        switch (stage) {
                            case MeetingCallContext.DIVERGENCE:
                                return next(memberReplies.get(name));
                            case MeetingCallContext.REESTIMATE:
                                List<String> finals = finalReplies.get(name);
                                return finals != null ? next(finals) : last(memberReplies.get(name));
                            case MeetingCallContext.CROSS_EXAM:
                                List<String> exam = crossExamReplies.get(name);
                                return exam != null ? next(exam) : name + "：请说明你的判断依据哪条事实。";
                            case MeetingCallContext.RED_TEAM:
                                return "若下一季度需求转弱，这条结论最可能失效。";
                            default:
                                throw new IllegalStateException("unexpected model call: " + stage + " " + system);
                        }
                    });
            ReflectionTestUtils.setField(service, "roomMapper", rooms);
            ReflectionTestUtils.setField(service, "memberMapper", members);
            ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
            ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
            ReflectionTestUtils.setField(service, "reviewedSearchLedgerService", ledger);
            ReflectionTestUtils.setField(service, "recordMapper", records);
            ReflectionTestUtils.setField(service, "reviewedFinalizationWriter", writer);
            ReflectionTestUtils.setField(service, "workflowService", workflow);
            ReflectionTestUtils.setField(service, "aiModelProperties", new AiModelProperties());
            ReflectionTestUtils.setField(service, "domains", List.of(new StockMeetingDomain(eastmoney)));
        }

        private void answers(String memberName, String... replies) {
            memberReplies.put(memberName, new ArrayList<>(List.of(replies)));
        }

        private void finalAnswers(String memberName, String... replies) {
            finalReplies.put(memberName, new ArrayList<>(List.of(replies)));
        }

        private void crossExam(String memberName, String... replies) {
            crossExamReplies.put(memberName, new ArrayList<>(List.of(replies)));
        }

        private String memberIn(String system) {
            for (String name : List.of("甲", "乙", "丙")) {
                if (system.contains("「" + name + "」")) return name;
            }
            throw new IllegalStateException("unknown speaker: " + system);
        }

        private String next(List<String> replies) {
            return replies.size() > 1 ? replies.remove(0) : replies.get(0);
        }

        private String last(List<String> replies) {
            return replies.get(replies.size() - 1);
        }
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence crossChecked(
            String requestId, String question, String asOf, String statement) {
        JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"status\":\"CROSS_CHECKED\",\"audit\":{\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"fact\"],\"links\":[\"https://example.org/source\"]},"
                + "{\"phase\":\"review\",\"id\":\"fact\",\"verdict\":\"PASS\","
                + "\"reviewerVerdict\":\"PASS\",\"searchCount\":1,"
                + "\"links\":[\"https://example.org/review\"]}]},"
                + "\"claims\":[{\"id\":\"fact\","
                + "\"sourceUrls\":[\"https://example.org/source\"],"
                + "\"reviewLinks\":[\"https://example.org/review\"],"
                + "\"reviewMethod\":\"TWO_ISOLATED_DEEPSEEK_SEARCHES\"}]}");
        packet.getJSONArray("claims").getJSONObject(0).put("statement", statement);
        packet.put("requestId", requestId);
        packet.put("question", question);
        packet.put("asOf", asOf);
        return MeetingReviewedEvidenceService.acceptPacket(packet, requestId, question);
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence provisional(
            String requestId, String question, String asOf, String statement) {
        JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"status\":\"UNVERIFIED\",\"claims\":[],"
                + "\"provisionalClaims\":[{\"id\":\"candidate\","
                + "\"sourceUrls\":[\"https://example.org/candidate\"],"
                + "\"reviewVerdict\":\"UNKNOWN\",\"confidenceBand\":\"LOW\","
                + "\"confidenceScore\":0.25,"
                + "\"confidenceBasis\":\"UNCALIBRATED_POLICY_SCORE\","
                + "\"limitation\":\"缺少独立来源\"}],"
                + "\"audit\":{\"searchCalls\":1,\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"candidate\"],\"links\":[\"https://example.org/candidate\"]},"
                + "{\"phase\":\"review\",\"id\":\"candidate\",\"verdict\":\"UNKNOWN\","
                + "\"reviewerVerdict\":\"UNKNOWN\",\"searchCount\":1}]}}");
        packet.getJSONArray("provisionalClaims").getJSONObject(0).put("statement", statement);
        packet.put("requestId", requestId);
        packet.put("question", question);
        packet.put("asOf", asOf);
        return MeetingReviewedEvidenceService.acceptPacket(packet, requestId, question);
    }
}
