package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.aimeeting.room.enums.MeetingStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingReviewedTurnSkipServiceTest {
    @Autowired private AiMeetingRoomMapper rooms;
    @Autowired private AiMeetingOpinionMapper opinions;
    @Autowired private MeetingReviewedTurnSkipService skips;

    @Test
    void skipIsDurableDistinctFromAnOpinionAndCannotBeWrittenByOldRun() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("议员弃权代际测试");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(1);
        rooms.insert(room);
        long id = room.getId();
        try {
            MeetingReviewedTurnSkip request = skip(id, 7L, 0L);
            MeetingReviewedTurnSkip saved = skips.recordIfCurrent(request,
                    MeetingStatusEnum.IN_PROGRESS.getCode(), null);
            assertNotNull(saved.getId());
            assertEquals(1, skips.listSkippedByMeetingAndRound(id, 1).size());
            assertEquals(0, opinions.selectByMeetingIdAndRound(id, 1).size());

            MeetingReviewedTurnSkip duplicate = skips.recordIfCurrent(skip(id, 7L, 0L),
                    MeetingStatusEnum.IN_PROGRESS.getCode(), null);
            assertEquals(saved.getId(), duplicate.getId());
            assertEquals("MODEL_PROVIDER_ERROR", duplicate.getReasonCode());

            assertEquals(1, rooms.updateStatusIfCurrentAndEpoch(id,
                    MeetingStatusEnum.IN_PROGRESS.getCode(), 0L, MeetingStatusEnum.PAUSED.getCode()));
            assertEquals(1, rooms.claimReviewedRun(id, MeetingStatusEnum.PAUSED.getCode(), 1L,
                    MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertEquals("MEETING_RUN_STALE", assertThrows(
                    MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> skips.recordIfCurrent(skip(id, 8L, 0L),
                            MeetingStatusEnum.IN_PROGRESS.getCode(), null)).getMessage());
            assertEquals(1, skips.listSkippedByMeetingAndRound(id, 1).size());
        } finally {
            skips.deleteByMeetingId(id);
            opinions.deleteByMeetingId(id);
            rooms.deleteById(id);
        }
    }

    private static MeetingReviewedTurnSkip skip(long meetingId, long memberId, long epoch) {
        MeetingReviewedTurnSkip skip = new MeetingReviewedTurnSkip();
        skip.setMeetingId(meetingId);
        skip.setRoundNumber(1);
        skip.setMemberId(memberId);
        skip.setReasonCode("MODEL_PROVIDER_ERROR");
        skip.setRunEpoch(epoch);
        skip.setAsOf(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        return skip;
    }
}
