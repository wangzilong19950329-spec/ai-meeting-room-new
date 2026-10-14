package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.config.MeetingReviewedSearchLedgerSchemaInitializer;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingRoomStartClaimTest {

    @Test
    void concurrentReviewedStartsFromSameCreatedSnapshotSubmitOnlyOnce() throws Exception {
        assertConcurrentReviewedClaimsOnlyOnce(MeetingStatusEnum.CREATED.getCode());
    }

    @Test
    void concurrentReviewedResumesFromSamePausedSnapshotSubmitOnlyOnce() throws Exception {
        assertConcurrentReviewedClaimsOnlyOnce(MeetingStatusEnum.PAUSED.getCode());
    }

    private void assertConcurrentReviewedClaimsOnlyOnce(int initialStatus) throws Exception {
        Fixture fixture = new Fixture(true, initialStatus);
        CyclicBarrier staleReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        when(fixture.rooms.selectById(1L)).thenAnswer(invocation -> {
            int snapshotStatus = fixture.status.get();
            if (reads.incrementAndGet() <= 2) staleReads.await(5, TimeUnit.SECONDS);
            return fixture.snapshot(snapshotStatus);
        });
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = callers.submit(() -> fixture.startIfClaimed());
            Future<Boolean> second = callers.submit(() -> fixture.startIfClaimed());
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            callers.shutdownNow();
        }
        assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(), fixture.status.get());
        verify(fixture.meetingExecutor).submit(any(Runnable.class));
        verify(fixture.workflow).ensureWorkflowForMeeting(any(AiMeetingRoom.class), eq(null));
        verify(fixture.workflow).activateMeetingWorkflow(1L);
    }

    @Test
    void reviewedWorkflowSetupFailureReturnsClaimToPausedBeforeSubmit() {
        Fixture fixture = new Fixture(true, MeetingStatusEnum.CREATED.getCode());
        when(fixture.workflow.ensureWorkflowForMeeting(any(AiMeetingRoom.class), eq(null)))
                .thenThrow(new IllegalStateException("workflow unavailable"));

        assertThrows(IllegalStateException.class, () -> fixture.service.startMeeting(1L));

        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.status.get());
        verify(fixture.meetingExecutor, never()).submit(any(Runnable.class));
        verify(fixture.workflow).markReviewedEvidenceBlocked(eq(1L), any(String.class));
    }

    @Test
    void reviewedSubmitRejectionReturnsClaimToPaused() {
        Fixture fixture = new Fixture(true, MeetingStatusEnum.CREATED.getCode());
        when(fixture.meetingExecutor.submit(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("executor stopped"));

        assertThrows(RejectedExecutionException.class, () -> fixture.service.startMeeting(1L));

        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.status.get());
        verify(fixture.rooms).updateStatusIfCurrentAndEpoch(1L,
                MeetingStatusEnum.IN_PROGRESS.getCode(), 1L, MeetingStatusEnum.PAUSED.getCode());
        verify(fixture.workflow).markReviewedEvidenceBlocked(eq(1L), any(String.class));
    }

    @Test
    void legacyStartKeepsExistingStatusUpdatePath() {
        Fixture fixture = new Fixture(false, MeetingStatusEnum.CREATED.getCode());

        fixture.service.startMeeting(1L);

        assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(), fixture.status.get());
        verify(fixture.rooms).updateStatus(1L, MeetingStatusEnum.IN_PROGRESS.getCode());
        verify(fixture.rooms, never()).claimReviewedRun(eq(1L), any(Integer.class),
                anyLong(), any(Integer.class));
        verify(fixture.meetingExecutor).submit(any(Runnable.class));
    }

    private static final class Fixture {
        private final MeetingRoomService service = new MeetingRoomService();
        private final AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        private final AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        private final MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        private final MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        private final MeetingReviewedSearchLedgerSchemaInitializer schema = mock(MeetingReviewedSearchLedgerSchemaInitializer.class);
        private final ExecutorService meetingExecutor = mock(ExecutorService.class);
        private final AtomicInteger status;
        private final AtomicLong epoch = new AtomicLong();

        private Fixture(boolean reviewedEnabled, int initialStatus) {
            status = new AtomicInteger(initialStatus);
            when(rooms.selectById(1L)).thenAnswer(invocation -> snapshot(status.get()));
            when(rooms.updateStatusIfCurrent(eq(1L), any(Integer.class), any(Integer.class)))
                    .thenAnswer(invocation -> status.compareAndSet(
                            invocation.getArgument(1), invocation.getArgument(2)) ? 1 : 0);
            when(rooms.claimReviewedRun(eq(1L), any(Integer.class), anyLong(),
                    any(Integer.class))).thenAnswer(invocation -> {
                synchronized (this) {
                    if (status.get() != (int) invocation.getArgument(1)
                            || epoch.get() != (long) invocation.getArgument(2)) return 0;
                    status.set(invocation.getArgument(3));
                    epoch.incrementAndGet();
                    return 1;
                }
            });
            when(rooms.updateStatusIfCurrentAndEpoch(eq(1L), any(Integer.class),
                    anyLong(), any(Integer.class))).thenAnswer(invocation -> {
                synchronized (this) {
                    if (status.get() != (int) invocation.getArgument(1)
                            || epoch.get() != (long) invocation.getArgument(2)) return 0;
                    status.set(invocation.getArgument(3));
                    epoch.incrementAndGet();
                    return 1;
                }
            });
            when(rooms.updateStatus(eq(1L), any(Integer.class))).thenAnswer(invocation -> {
                status.set(invocation.getArgument(1));
                return 1;
            });
            when(workflow.isDefaultRoundtableWorkflow(1L)).thenReturn(true);
            when(reviewed.isEnabled()).thenReturn(reviewedEnabled);
            when(meetingExecutor.submit(any(Runnable.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));
            ReflectionTestUtils.setField(service, "roomMapper", rooms);
            ReflectionTestUtils.setField(service, "opinionMapper", opinions);
            ReflectionTestUtils.setField(service, "workflowService", workflow);
            ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
            ReflectionTestUtils.setField(service, "reviewedSearchLedgerSchemaInitializer", schema);
            ReflectionTestUtils.setField(service, "meetingExecutor", meetingExecutor);
        }

        private AiMeetingRoom snapshot(int snapshotStatus) {
            AiMeetingRoom room = new AiMeetingRoom();
            room.setId(1L);
            room.setStatus(snapshotStatus);
            room.setRunEpoch(epoch.get());
            room.setMaxRounds(1);
            room.setCurrentRound(0);
            return room;
        }

        private boolean startIfClaimed() {
            try {
                service.startMeeting(1L);
                return true;
            } catch (IllegalStateException lostClaim) {
                assertTrue(lostClaim.getMessage().contains("无法重复启动"));
                return false;
            }
        }
    }
}
