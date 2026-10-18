package com.aimeeting.room;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.service.InterruptedMeetingRecovery;
import com.aimeeting.room.service.MeetingWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** After a restart no worker is running any meeting, so meetings left running or reopening are paused. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("smoketest")
class InterruptedMeetingRecoveryPersistenceTest {
    @Autowired private AiMeetingRoomMapper rooms;
    private final List<Long> created = new ArrayList<>();

    @Test
    void runningAndReopeningMeetingsArePausedWithTheReasonAndANewEpoch() {
        MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        try {
            AiMeetingRoom running = room(MeetingStatusEnum.IN_PROGRESS);
            AiMeetingRoom reopening = room(MeetingStatusEnum.REOPENED);
            AiMeetingRoom concluded = room(MeetingStatusEnum.CONCLUDED);
            AiMeetingRoom paused = room(MeetingStatusEnum.PAUSED);

            new InterruptedMeetingRecovery(rooms, workflow, true).pauseInterrupted();

            assertPaused(running);
            assertPaused(reopening);
            assertEquals(MeetingStatusEnum.CONCLUDED.getCode(), rooms.selectById(concluded.getId()).getStatus());
            assertEquals(epochOf(concluded), epochOf(rooms.selectById(concluded.getId())));
            assertEquals(epochOf(paused), epochOf(rooms.selectById(paused.getId())));
            verify(workflow).markReviewedEvidenceBlocked(running.getId(), InterruptedMeetingRecovery.REASON);
            verify(workflow).markReviewedEvidenceBlocked(reopening.getId(), InterruptedMeetingRecovery.REASON);
            verify(workflow, never()).markReviewedEvidenceBlocked(eq(concluded.getId()), anyString());
            verify(workflow, never()).markReviewedEvidenceBlocked(eq(paused.getId()), anyString());
        } finally {
            created.forEach(rooms::deleteById);
        }
    }

    @Test
    void nothingIsPausedWhenTheRecoveryIsSwitchedOff() {
        MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        try {
            AiMeetingRoom running = room(MeetingStatusEnum.IN_PROGRESS);

            new InterruptedMeetingRecovery(rooms, workflow, false).onReady();

            assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(), rooms.selectById(running.getId()).getStatus());
            verify(workflow, never()).markReviewedEvidenceBlocked(anyLong(), anyString());
        } finally {
            created.forEach(rooms::deleteById);
        }
    }

    private void assertPaused(AiMeetingRoom before) {
        AiMeetingRoom after = rooms.selectById(before.getId());
        assertEquals(MeetingStatusEnum.PAUSED.getCode(), after.getStatus());
        assertEquals(epochOf(before) + 1, epochOf(after), "a stale worker must not write after the pause");
    }

    private AiMeetingRoom room(MeetingStatusEnum status) {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("interrupted meeting " + status);
        room.setCreatorId(1L);
        room.setStatus(status.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(0);
        rooms.insert(room);
        created.add(room.getId());
        return rooms.selectById(room.getId());
    }

    private static long epochOf(AiMeetingRoom room) {
        return room.getRunEpoch() == null ? 0L : room.getRunEpoch();
    }
}
