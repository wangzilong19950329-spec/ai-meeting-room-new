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

        verify(fixture.orchestrator, times(4)).callModelStrict(anyString(), anyString(), anyString(),
                nullable(Integer.class));
        verify(fixture.reviewed, times(3)).search(anyString(), anyString(), anyString(), anyLong());
        verify(fixture.writer, times(2)).concludeIfCurrent(anyLong(), anyLong(), anyInt(), anyInt(),
                nullable(String.class), anyString(), anyString(), anyString(), any(Date.class));
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
        private final List<String> moderatorReplies = new ArrayList<>();
        private final Map<String, List<String>> memberReplies = new HashMap<>();
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
                        if (system.contains("主持人")) {
                            return moderatorReplies.isEmpty() ? CARD : moderatorReplies.remove(0);
                        }
                        for (Map.Entry<String, List<String>> entry : memberReplies.entrySet()) {
                            if (system.contains("「" + entry.getKey() + "」")) {
                                List<String> replies = entry.getValue();
                                return replies.size() > 1 ? replies.remove(0) : replies.get(0);
                            }
                        }
                        throw new IllegalStateException("unexpected model call: " + system);
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
        }

        private void answers(String memberName, String... replies) {
            memberReplies.put(memberName, new ArrayList<>(List.of(replies)));
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
