package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.config.MeetingReviewedSearchLedgerSchemaInitializer;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingRoomEndedTargetedFollowUpTest {

    @Test
    void reviewedFailurePausesAndResumeRetriesOnlyTheTargetedReply() {
        Fixture fixture = new Fixture(MeetingStatusEnum.CONCLUDED.getCode());
        AtomicInteger attempts = new AtomicInteger();
        when(fixture.debate.generateSingleTargetReply(eq(1L), eq(9L), eq(4), anyLong())).thenAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_UNVERIFIED");
            }
            return fixture.addReply();
        });

        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> fixture.service.addHumanMessage(1L, "请补充最新证据", false, 9L));
        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
        assertEquals(4, fixture.room.getCurrentRound());
        assertEquals("原有结论", fixture.room.getFinalConclusion());
        assertEquals(1, fixture.opinions.size());
        assertEquals(MeetingStatusEnum.CONCLUDED.getCode(), JSON.parseObject(fixture.opinions.get(0).getEvidence())
                .getInteger("endedTargetedOriginalStatus"));
        assertTrue(fixture.room.getConclusionHistory().contains("FOLLOW_UP_TARGETED_BLOCKED"));

        fixture.service.startMeeting(1L);

        assertEquals(2, attempts.get());
        assertEquals(2, fixture.opinions.size());
        assertEquals(fixture.opinions.get(0).getId(), fixture.opinions.get(1).getParentOpinionId());
        assertEquals(MeetingStatusEnum.CONCLUDED.getCode(), fixture.room.getStatus());
        assertEquals("原有结论", fixture.room.getFinalConclusion());
        verify(fixture.workflow, never()).activateMeetingWorkflow(1L);
    }

    @Test
    void repeatedReviewedFailureKeepsOneQuestionAndRemainsPaused() {
        Fixture fixture = new Fixture(MeetingStatusEnum.VERIFIED_VALID.getCode());
        when(fixture.debate.generateSingleTargetReply(eq(1L), eq(9L), eq(4), anyLong()))
                .thenThrow(new MeetingReviewedEvidenceService.EvidenceRejected("MODEL_OUTPUT_UNAVAILABLE"));

        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> fixture.service.addHumanMessage(1L, "请解释原因", false, 9L));
        fixture.service.startMeeting(1L);

        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
        assertEquals(1, fixture.opinions.size());
        assertEquals("原有结论", fixture.room.getFinalConclusion());
    }

    @Test
    void resumeDoesNotRegenerateAnAlreadySavedTargetedReply() {
        Fixture fixture = new Fixture(MeetingStatusEnum.CONCLUDED.getCode());
        when(fixture.debate.generateSingleTargetReply(eq(1L), eq(9L), eq(4), anyLong()))
                .thenThrow(new MeetingReviewedEvidenceService.EvidenceRejected("N8N_OUTCOME_UNKNOWN"));

        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> fixture.service.addHumanMessage(1L, "再说明一次", false, 9L));
        fixture.addReply();
        fixture.service.startMeeting(1L);

        verify(fixture.debate, times(1)).generateSingleTargetReply(eq(1L), eq(9L), eq(4), anyLong());
        assertEquals(MeetingStatusEnum.CONCLUDED.getCode(), fixture.room.getStatus());
        assertEquals(2, fixture.opinions.size());
    }

    @Test
    void rejectedResumeClaimDoesNotSubmitAnotherTargetedReply() {
        Fixture fixture = new Fixture(MeetingStatusEnum.CONCLUDED.getCode());
        when(fixture.debate.generateSingleTargetReply(eq(1L), eq(9L), eq(4), anyLong()))
                .thenThrow(new MeetingReviewedEvidenceService.EvidenceRejected("N8N_OUTCOME_UNKNOWN"));
        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> fixture.service.addHumanMessage(1L, "请补充证据", false, 9L));
        doReturn(0).when(fixture.rooms).claimReviewedRun(eq(1L),
                eq(MeetingStatusEnum.PAUSED.getCode()), anyLong(),
                eq(MeetingStatusEnum.REOPENED.getCode()));

        assertThrows(IllegalStateException.class, () -> fixture.service.startMeeting(1L));

        verify(fixture.debate, times(1)).generateSingleTargetReply(eq(1L), eq(9L), eq(4), anyLong());
        verify(fixture.executor, never()).submit(any(Runnable.class));
        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
    }

    @Test
    void successfulEndedTargetedReplyKeepsOriginalEndedStatus() {
        Fixture fixture = new Fixture(MeetingStatusEnum.VERIFIED_INVALID.getCode());
        when(fixture.debate.generateSingleTargetReply(eq(1L), eq(9L), eq(4), anyLong()))
                .thenAnswer(invocation -> fixture.addReply());

        fixture.service.addHumanMessage(1L, "请单独回应", false, 9L);

        assertEquals(MeetingStatusEnum.VERIFIED_INVALID.getCode(), fixture.room.getStatus());
        assertEquals(2, fixture.opinions.size());
        assertEquals("原有结论", fixture.room.getFinalConclusion());
    }

    private static final class Fixture {
        private final MeetingRoomService service = new MeetingRoomService();
        private final AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        private final AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        private final AiMeetingOpinionMapper opinionMapper = mock(AiMeetingOpinionMapper.class);
        private final MeetingDebateService debate = mock(MeetingDebateService.class);
        private final MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        private final MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        private final ExecutorService executor = mock(ExecutorService.class);
        private final AiMeetingRoom room = new AiMeetingRoom();
        private final List<AiMeetingOpinion> opinions = new ArrayList<>();

        private Fixture(int originalStatus) {
            room.setId(1L);
            room.setTitle("已结束的讨论");
            room.setStatus(originalStatus);
            room.setRunEpoch(0L);
            room.setCurrentRound(3);
            room.setMaxRounds(3);
            room.setFinalConclusion("原有结论");
            AiMeetingMember target = new AiMeetingMember();
            target.setId(9L);
            target.setMeetingId(1L);
            target.setMemberName("目标议员");
            when(rooms.selectById(1L)).thenReturn(room);
            when(rooms.updateStatus(eq(1L), any(Integer.class))).thenAnswer(invocation -> {
                room.setStatus(invocation.getArgument(1));
                return 1;
            });
            when(rooms.updateStatusIfCurrent(eq(1L), any(Integer.class), any(Integer.class)))
                    .thenAnswer(invocation -> {
                        int expected = invocation.getArgument(1);
                        if (room.getStatus() != expected) return 0;
                        room.setStatus(invocation.getArgument(2));
                        return 1;
                    });
            when(rooms.claimReviewedRun(eq(1L), any(Integer.class), anyLong(),
                    any(Integer.class))).thenAnswer(invocation -> {
                if (room.getStatus() != (int) invocation.getArgument(1)
                        || room.getRunEpoch() != (long) invocation.getArgument(2)) return 0;
                room.setStatus(invocation.getArgument(3));
                room.setRunEpoch(room.getRunEpoch() + 1);
                return 1;
            });
            when(rooms.updateStatusIfCurrentAndEpoch(eq(1L), any(Integer.class), anyLong(),
                    any(Integer.class))).thenAnswer(invocation -> {
                if (room.getStatus() != (int) invocation.getArgument(1)
                        || room.getRunEpoch() != (long) invocation.getArgument(2)) return 0;
                room.setStatus(invocation.getArgument(3));
                room.setRunEpoch(room.getRunEpoch() + 1);
                return 1;
            });
            when(rooms.updateCurrentRound(eq(1L), any(Integer.class))).thenAnswer(invocation -> {
                room.setCurrentRound(invocation.getArgument(1));
                return 1;
            });
            when(rooms.updateCurrentRoundIfEpoch(eq(1L), anyLong(), any(Integer.class)))
                    .thenAnswer(invocation -> {
                        if (room.getRunEpoch() != (long) invocation.getArgument(1)) return 0;
                        room.setCurrentRound(invocation.getArgument(2));
                        return 1;
                    });
            when(rooms.updateConclusionHistory(eq(1L), any(String.class))).thenAnswer(invocation -> {
                room.setConclusionHistory(invocation.getArgument(1));
                return 1;
            });
            when(rooms.updateConclusionHistoryIfEpoch(eq(1L), anyLong(), any(String.class)))
                    .thenAnswer(invocation -> {
                        if (room.getRunEpoch() != (long) invocation.getArgument(1)) return 0;
                        room.setConclusionHistory(invocation.getArgument(2));
                        return 1;
                    });
            when(members.selectById(9L)).thenReturn(target);
            when(opinionMapper.insert(any())).thenAnswer(invocation -> {
                AiMeetingOpinion opinion = invocation.getArgument(0);
                opinion.setId(100L + opinions.size());
                opinions.add(opinion);
                return 1;
            });
            when(opinionMapper.selectByMeetingIdAndRound(1L, 4)).thenAnswer(invocation -> new ArrayList<>(opinions));
            when(reviewed.isEnabled()).thenReturn(true);
            when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return CompletableFuture.completedFuture(null);
            });
            ReflectionTestUtils.setField(service, "roomMapper", rooms);
            ReflectionTestUtils.setField(service, "memberMapper", members);
            ReflectionTestUtils.setField(service, "opinionMapper", opinionMapper);
            ReflectionTestUtils.setField(service, "debateService", debate);
            ReflectionTestUtils.setField(service, "workflowService", workflow);
            ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
            ReflectionTestUtils.setField(service, "reviewedSearchLedgerSchemaInitializer",
                    mock(MeetingReviewedSearchLedgerSchemaInitializer.class));
            ReflectionTestUtils.setField(service, "meetingExecutor", executor);
        }

        private AiMeetingOpinion addReply() {
            AiMeetingOpinion reply = new AiMeetingOpinion();
            reply.setMeetingId(1L);
            reply.setMemberId(9L);
            reply.setRoundNumber(4);
            reply.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
            reply.setParentOpinionId(opinions.get(0).getId());
            opinionMapper.insert(reply);
            return reply;
        }
    }
}
