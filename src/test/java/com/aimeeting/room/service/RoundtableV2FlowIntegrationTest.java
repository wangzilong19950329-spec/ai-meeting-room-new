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
        assertTrue(view.getFacts().toJSONString().contains("22.5倍"), view.getFacts().toJSONString());
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
                            if (MeetingCallContext.CLASSIFY.equals(stage)) {
                                return classifyReplies.isEmpty() ? DEFAULT_CLASSIFY
                                        : classifyReplies.size() > 1 ? classifyReplies.remove(0) : classifyReplies.get(0);
                            }
                            return moderatorReplies.isEmpty() ? card : moderatorReplies.remove(0);
                        }
                        String name = memberIn(system);
                        calls.add(stage + ":" + name);
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
