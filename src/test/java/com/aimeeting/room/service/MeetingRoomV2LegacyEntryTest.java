package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dto.TrackingItemUpdateRequest;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A roundtable v2 meeting must never be driven by the old multi-round flow through any side entrance. */
class MeetingRoomV2LegacyEntryTest {

    @Test
    void aFollowUpMessageOnAnEndedV2MeetingIsRefusedAndTheMeetingIsUntouched() {
        Fixture fixture = new Fixture(MeetingStatusEnum.CONCLUDED.getCode());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> fixture.service.addHumanMessage(1L, "请补充最新证据", false, null));

        assertTrue(refused.getMessage().contains("圆桌 v2"), refused.getMessage());
        fixture.assertUntouched(MeetingStatusEnum.CONCLUDED.getCode());
    }

    @Test
    void aMessageOnARunningV2MeetingIsRefusedAndPointsToTheInterventionWindow() {
        Fixture fixture = new Fixture(MeetingStatusEnum.IN_PROGRESS.getCode());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> fixture.service.addHumanMessage(1L, "你为什么这么判断？", false, null));

        assertTrue(refused.getMessage().contains("介入窗口"), refused.getMessage());
        fixture.assertUntouched(MeetingStatusEnum.IN_PROGRESS.getCode());
    }

    @Test
    void aTrackingUpdateThatWouldReopenAV2MeetingOnlyUpdatesTheItem() {
        Fixture fixture = new Fixture(MeetingStatusEnum.CONCLUDED.getCode());
        when(fixture.workflow.updateTrackingItem(7L, "CHANGED", "假设被证伪", null)).thenReturn(
                new MeetingWorkflowService.TrackingProgressResult(1L, 7L, "海外关税", "CHANGED", true,
                        "追踪项发生关键变化", "假设被证伪", null));
        TrackingItemUpdateRequest request = new TrackingItemUpdateRequest();
        request.setStatus("CHANGED");
        request.setNote("假设被证伪");

        fixture.service.updateTrackingItem(7L, request);

        verify(fixture.workflow).updateTrackingItem(7L, "CHANGED", "假设被证伪", null);
        fixture.assertUntouched(MeetingStatusEnum.CONCLUDED.getCode());
    }

    @Test
    void reopeningAV2MeetingFromATrackingSignalIsRefusedAndTheMeetingIsUntouched() {
        Fixture fixture = new Fixture(MeetingStatusEnum.CONCLUDED.getCode());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> fixture.service.reopenMeetingFromTrackingSignal(1L, 7L, "海外关税", "关键变化", "说明", null));

        assertTrue(refused.getMessage().contains("圆桌 v2"), refused.getMessage());
        fixture.assertUntouched(MeetingStatusEnum.CONCLUDED.getCode());
    }

    private static final class Fixture {
        private final MeetingRoomService service = new MeetingRoomService();
        private final AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        private final AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        private final MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        private final MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        private final ExecutorService executor = mock(ExecutorService.class);
        private final AiMeetingRoom room = new AiMeetingRoom();

        private Fixture(int status) {
            room.setId(1L);
            room.setTitle("比亚迪未来三个月走势");
            room.setStatus(status);
            room.setRunEpoch(3L);
            room.setCurrentRound(1);
            room.setFinalConclusion("原有结论");
            when(rooms.selectById(1L)).thenReturn(room);
            when(workflow.isRoundtableV2Workflow(1L)).thenReturn(true);
            when(reviewed.isEnabled()).thenReturn(true);
            ReflectionTestUtils.setField(service, "roomMapper", rooms);
            ReflectionTestUtils.setField(service, "opinionMapper", opinions);
            ReflectionTestUtils.setField(service, "workflowService", workflow);
            ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
            ReflectionTestUtils.setField(service, "meetingExecutor", executor);
        }

        private void assertUntouched(int status) {
            verify(executor, never()).submit(any(Runnable.class));
            verify(rooms, never()).claimReviewedRun(anyLong(), anyInt(), anyLong(), anyInt());
            verify(rooms, never()).updateStatus(anyLong(), anyInt());
            verify(rooms, never()).updateCurrentRound(anyLong(), anyInt());
            verify(rooms, never()).updateConclusionHistory(anyLong(), anyString());
            verify(opinions, never()).insert(any());
            verify(workflow, never()).reopenMeetingWorkflow(anyLong());
            verify(workflow, never()).markWorkflowActiveDiscussion(anyLong(), any());
            assertEquals(status, room.getStatus());
            assertEquals(3L, room.getRunEpoch());
        }
    }
}
