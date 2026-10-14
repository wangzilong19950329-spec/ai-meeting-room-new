package com.aimeeting.room;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("smoketest")
class MeetingRoomStatusClaimPersistenceTest {
    @Autowired private AiMeetingRoomMapper rooms;

    @Test
    void databaseStatusClaimAcceptsOnlyTheExpectedState() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("status claim test");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.CREATED.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(0);
        rooms.insert(room);
        try {
            assertEquals(1, rooms.updateStatusIfCurrent(room.getId(),
                    MeetingStatusEnum.CREATED.getCode(), MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertEquals(0, rooms.updateStatusIfCurrent(room.getId(),
                    MeetingStatusEnum.CREATED.getCode(), MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(),
                    rooms.selectById(room.getId()).getStatus());
        } finally {
            rooms.deleteById(room.getId());
        }
    }

    @Test
    void reviewedRunEpochRejectsStaleWorkerAfterPauseAndResume() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("epoch fencing test");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.CREATED.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(0);
        rooms.insert(room);
        try {
            assertEquals(0L, rooms.selectById(room.getId()).getRunEpoch());
            assertEquals(1, rooms.claimReviewedRun(room.getId(),
                    MeetingStatusEnum.CREATED.getCode(), 0L,
                    MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertEquals(1L, rooms.selectById(room.getId()).getRunEpoch());
            assertEquals(1, rooms.updateStatusIfCurrentAndEpoch(room.getId(),
                    MeetingStatusEnum.IN_PROGRESS.getCode(), 1L,
                    MeetingStatusEnum.PAUSED.getCode()));
            assertEquals(0, rooms.updateCurrentRoundIfEpoch(room.getId(), 1L, 1));
            assertEquals(1, rooms.claimReviewedRun(room.getId(),
                    MeetingStatusEnum.PAUSED.getCode(), 2L,
                    MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertEquals(0, rooms.updateStatusIfCurrentAndEpoch(room.getId(),
                    MeetingStatusEnum.IN_PROGRESS.getCode(), 1L,
                    MeetingStatusEnum.PAUSED.getCode()));
            assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(),
                    rooms.selectById(room.getId()).getStatus());
            assertEquals(3L, rooms.selectById(room.getId()).getRunEpoch());
        } finally {
            rooms.deleteById(room.getId());
        }
    }
}
