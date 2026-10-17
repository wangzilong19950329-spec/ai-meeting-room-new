package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingVoteMapper;
import com.aimeeting.room.dto.ConsensusResult;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The conclusion, summary and decision card pass the same number gate as reviewed speeches. */
class MeetingReviewedFinalizationNumberGateTest {
    private static final long MEETING = 7L;
    private static final long EPOCH = 3L;
    private static final String CARD_OK = "{\"topicOverview\":\"主题\",\"projectKey\":null,\"projectContext\":null,"
            + "\"consensusPath\":\"过程\",\"finalDecision\":\"x\",\"keyViewpoints\":[],\"majorDisputes\":[],"
            + "\"decisionBasis\":[],\"openQuestions\":[],\"nextActions\":[\"跟踪营收是否保持100亿元\"]}";

    @Test
    void summaryWithUnsourcedNumberIsRewrittenOnce() {
        Fixture fixture = new Fixture();
        fixture.summaries("营收120亿元，判断偏乐观。", "营收100亿元，判断偏乐观。");

        fixture.finalize("看多，资料充分");

        assertEquals("营收100亿元，判断偏乐观。", fixture.savedSummary());
        verify(fixture.orchestrator, times(2)).callModelStrict(eq("deepseek-chat"), anyString(),
                startsWith("总结提示"), nullable(Integer.class));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(fixture.orchestrator, atLeastOnce()).callModelStrict(eq("deepseek-chat"), anyString(),
                prompts.capture(), nullable(Integer.class));
        assertTrue(prompts.getAllValues().stream().anyMatch(prompt ->
                prompt.startsWith("总结提示") && prompt.contains("120亿元")), prompts.getAllValues().toString());
    }

    @Test
    void summaryStillUnsourcedAfterRewriteIsMasked() {
        Fixture fixture = new Fixture();
        fixture.summaries("营收120亿元，判断偏乐观。", "营收120亿元，判断偏乐观。");

        fixture.finalize("看多，资料充分");

        assertEquals("营收[未核验]，判断偏乐观。", fixture.savedSummary());
    }

    @Test
    void lowConfidenceNumbersInSummaryAndConclusionAreLabelled() {
        Fixture fixture = new Fixture();
        fixture.summaries("订单规模80亿元。", "订单规模80亿元。");

        fixture.finalize("看多，订单规模80亿元");

        assertEquals("订单规模80亿元〔低置信〕。", fixture.savedSummary());
        assertTrue(fixture.savedConclusion().contains("80亿元〔低置信〕"), fixture.savedConclusion());
    }

    @Test
    void conclusionUnsourcedNumberIsMasked() {
        Fixture fixture = new Fixture();
        fixture.summaries("本次讨论已结束。", "本次讨论已结束。");

        fixture.finalize("看多，目标价300元");

        assertEquals("看多，目标价[未核验]", fixture.savedConclusion());
        assertFalse(fixture.savedConclusion().contains("300"));
    }

    @Test
    void decisionCardFieldsAreGatedBeforeTrackingItems() {
        Fixture fixture = new Fixture();
        fixture.summaries("本次讨论已结束。", "本次讨论已结束。");
        String unsourced = CARD_OK.replace("跟踪营收是否保持100亿元", "跟踪营收是否达到150亿元");
        fixture.cards(unsourced, unsourced);

        fixture.finalize("看多，资料充分");

        assertEquals("跟踪营收是否达到[未核验]",
                JSON.parseObject(fixture.savedArtifact()).getJSONArray("nextActions").getString(0));
        verify(fixture.orchestrator, times(2)).callModelStrict(eq("deepseek-chat"), anyString(),
                startsWith("卡片提示"), nullable(Integer.class));
        ArgumentCaptor<MeetingDetailVO.DecisionArtifactVO> card =
                ArgumentCaptor.forClass(MeetingDetailVO.DecisionArtifactVO.class);
        verify(fixture.workflow).markMeetingConcluded(any(), card.capture());
        assertEquals("跟踪营收是否达到[未核验]", card.getValue().getNextActions().get(0));
    }

    @Test
    void finalizationCallsAreTaggedByStage() {
        Fixture fixture = new Fixture();
        List<String> stages = new java.util.ArrayList<>();
        List<String> summaries = new java.util.ArrayList<>(List.of("营收120亿元。", "营收100亿元。"));
        when(fixture.orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), startsWith("总结提示"),
                nullable(Integer.class))).thenAnswer(invocation -> {
                    stages.add(stageNow(MEETING));
                    return summaries.remove(0);
                });
        when(fixture.orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), startsWith("卡片提示"),
                nullable(Integer.class))).thenAnswer(invocation -> {
                    stages.add(stageNow(MEETING));
                    return CARD_OK;
                });

        fixture.finalize("看多，资料充分");

        assertEquals(List.of(MeetingCallContext.SUMMARY, MeetingCallContext.SUMMARY_REWRITE,
                MeetingCallContext.DECISION_CARD), stages);
    }

    @Test
    void roundSummaryIsTaggedWithItsStage() {
        Fixture fixture = new Fixture();
        when(fixture.orchestrator.buildRoundSummaryPrompt(MEETING, 1)).thenReturn("轮摘要提示");
        List<String> stages = new java.util.ArrayList<>();
        when(fixture.orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), eq("轮摘要提示"), eq(220)))
                .thenAnswer(invocation -> {
                    stages.add(stageNow(MEETING));
                    return "本轮摘要";
                });

        ReflectionTestUtils.invokeMethod(fixture.service, "generateAndSaveRoundSummary", MEETING, 1, EPOCH);

        assertEquals(List.of(MeetingCallContext.ROUND_SUMMARY), stages);
    }

    private static String stageNow(long meetingId) {
        MeetingCallContext.Frame frame = MeetingCallContext.current();
        if (frame == null || frame.meetingId == null || frame.meetingId != meetingId) return null;
        return frame.stage;
    }

    private static final class Fixture {
        private final AiMeetingRoom room = new AiMeetingRoom();
        private final AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        private final AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        private final AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        private final AiMeetingVoteMapper votes = mock(AiMeetingVoteMapper.class);
        private final MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        private final MeetingConsensusService consensus = mock(MeetingConsensusService.class);
        private final MeetingReviewedTurnSkipService skips = mock(MeetingReviewedTurnSkipService.class);
        private final MeetingReviewedFinalizationWriter writer = mock(MeetingReviewedFinalizationWriter.class);
        private final MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        private final MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        private final MeetingRoomService service = new MeetingRoomService();

        private Fixture() {
            room.setId(MEETING);
            room.setTitle("数字闸门收尾测试");
            room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
            room.setRunEpoch(EPOCH);
            room.setCurrentRound(1);
            when(rooms.selectById(MEETING)).thenReturn(room);
            when(rooms.updateSummaryIfEpoch(eq(MEETING), eq(EPOCH), anyString())).thenReturn(1);
            AiMeetingMember member = new AiMeetingMember();
            member.setId(11L);
            member.setMemberName("价值派");
            when(members.selectByMeetingId(MEETING)).thenReturn(List.of(member));
            AiMeetingOpinion opinion = new AiMeetingOpinion();
            opinion.setMeetingId(MEETING);
            opinion.setMemberId(11L);
            opinion.setRoundNumber(1);
            opinion.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
            opinion.setContent("营收100亿元，订单规模80亿元〔低置信〕。");
            opinion.setEvidence("{\"reviewedSearchStatus\":\"CROSS_CHECKED\",\"results\":["
                    + "{\"source\":\"n8n 双会话搜索审核：fact\",\"snippet\":\"2025年营收：100亿元\","
                    + "\"provider\":\"n8n-deepseek-reviewed\",\"sourceType\":\"CROSS_CHECKED_SEARCH\"},"
                    + "{\"source\":\"n8n 待核验候选：order\",\"snippet\":\"【待核验】订单规模80亿元\","
                    + "\"provider\":\"n8n-deepseek-provisional\",\"sourceType\":\"LOW_CONFIDENCE_SEARCH\"}]}");
            when(opinions.selectByMeetingId(MEETING)).thenReturn(List.of(opinion));
            when(opinions.selectByMeetingIdAndRound(MEETING, 1)).thenReturn(List.of(opinion));
            when(votes.selectByMeetingIdAndRound(MEETING, 1)).thenReturn(Collections.emptyList());
            when(reviewed.isEnabled()).thenReturn(true);
            when(consensus.reviewedEligibleSpeakerIds(MEETING, 1)).thenReturn(Set.of(11L));
            when(skips.listSkippedByMeetingAndRound(MEETING, 1)).thenReturn(Collections.emptyList());
            when(orchestrator.resolveMeetingModelType(MEETING)).thenReturn("deepseek-chat");
            when(orchestrator.buildSummaryPrompt(eq(MEETING), anyString())).thenReturn("总结提示");
            when(orchestrator.buildDecisionArtifactPrompt(eq(MEETING), anyString())).thenReturn("卡片提示");
            cards(CARD_OK, CARD_OK);
            ReflectionTestUtils.setField(service, "roomMapper", rooms);
            ReflectionTestUtils.setField(service, "memberMapper", members);
            ReflectionTestUtils.setField(service, "opinionMapper", opinions);
            ReflectionTestUtils.setField(service, "voteMapper", votes);
            ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
            ReflectionTestUtils.setField(service, "consensusService", consensus);
            ReflectionTestUtils.setField(service, "reviewedTurnSkipService", skips);
            ReflectionTestUtils.setField(service, "reviewedFinalizationWriter", writer);
            ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
            ReflectionTestUtils.setField(service, "workflowService", workflow);
            ReflectionTestUtils.setField(service, "notificationService", mock(NotificationService.class));
        }

        private void summaries(String first, String second) {
            when(orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), startsWith("总结提示"),
                    nullable(Integer.class))).thenReturn(first, second);
        }

        private void cards(String first, String second) {
            when(orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), startsWith("卡片提示"),
                    nullable(Integer.class))).thenReturn(first, second);
        }

        private void finalize(String conclusion) {
            ConsensusResult result = ConsensusResult.partial(conclusion, null, List.of(11L), Collections.emptyList());
            result.setAbsentMemberIds(Collections.emptyList());
            ReflectionTestUtils.invokeMethod(service, "finalizeMeeting", MEETING, result, EPOCH);
        }

        private String savedSummary() {
            ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
            verify(rooms).updateSummaryIfEpoch(eq(MEETING), eq(EPOCH), summary.capture());
            return summary.getValue();
        }

        private String savedArtifact() {
            ArgumentCaptor<String> artifact = ArgumentCaptor.forClass(String.class);
            verify(writer).concludeIfCurrent(eq(MEETING), anyLong(), anyInt(), anyInt(), nullable(String.class),
                    artifact.capture(), anyString(), anyString(), any(Date.class));
            return artifact.getValue();
        }

        private String savedConclusion() {
            ArgumentCaptor<String> conclusion = ArgumentCaptor.forClass(String.class);
            verify(writer).concludeIfCurrent(eq(MEETING), anyLong(), anyInt(), anyInt(), nullable(String.class),
                    anyString(), anyString(), conclusion.capture(), any(Date.class));
            return conclusion.getValue();
        }
    }
}
