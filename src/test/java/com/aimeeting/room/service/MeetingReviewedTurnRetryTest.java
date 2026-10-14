package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.MeetingReviewedTurnSkipMapper;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingReviewedTurnRetryTest {
    @Autowired private AiMeetingRoomMapper rooms;
    @Autowired private AiMeetingOpinionMapper opinions;
    @Autowired private MeetingReviewedTurnSkipMapper skipMapper;
    @Autowired private MeetingReviewedTurnSkipService skips;
    @Autowired private MeetingReviewedOpinionWriter writer;

    @Test
    void retryRetainsSuccessfulSpeechAndFailureHistoryWhileUnknownMemberStaysBlocked() {
        long meeting = room();
        try {
            AiMeetingOpinion successful = opinion(meeting, 10L);
            writer.insertIfCurrent(successful, 0L, 1, null);
            MeetingReviewedTurnSkip local = record(meeting, 11L, "MODEL_UNSUPPORTED_CLAIMS", 0L);
            MeetingReviewedTurnSkip unknown = record(meeting, 12L, "MODEL_PLAN_PROVIDER_ERROR", 0L);
            pause(meeting, 0L);

            MeetingReviewedTurnSkip prepared = skips.prepareRetry(meeting, 11L, local.getId(), 1, 1L);
            assertNotNull(prepared.getRetryPreparedAt());
            assertNull(prepared.getActiveTurn());
            assertEquals(1L, prepared.getRetryPreparedEpoch());
            assertEquals(local.getReasonCode(), prepared.getReasonCode());
            assertEquals(local.getRunEpoch(), prepared.getRunEpoch());
            assertEquals(local.getAsOf(), prepared.getAsOf());
            assertEquals(prepared.getRetryPreparedAt(),
                    skips.prepareRetry(meeting, 11L, local.getId(), 1, 1L).getRetryPreparedAt());
            assertNull(skips.findSkip(meeting, 1, 11L, null));
            assertEquals(unknown.getId(), skips.findSkip(meeting, 1, 12L, null).getId());
            assertEquals(1, skips.listSkippedByMeetingId(meeting).size());
            assertEquals(successful.getId(), opinions.selectByMeetingIdAndRound(meeting, 1).get(0).getId());
            assertEquals(5, rooms.selectById(meeting).getStatus());
            assertEquals(1L, rooms.selectById(meeting).getRunEpoch());

            assertEquals(1, rooms.claimReviewedRun(meeting, 5, 1L, 1));
            writer.insertIfCurrent(opinion(meeting, 11L), 2L, 1, null);
            assertEquals(2, opinions.selectByMeetingIdAndRound(meeting, 1).size());
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertIfCurrent(opinion(meeting, 12L), 2L, 1, null));
            assertNotNull(skipMapper.findById(local.getId()));
            assertNotNull(skipMapper.findById(unknown.getId()));
        } finally { clean(meeting); }
    }

    @Test
    void eachRepeatedLocalFailureIsNewAuditRowAndOldRetryCannotReleaseIt() {
        long meeting = room();
        try {
            MeetingReviewedTurnSkip first = record(meeting, 11L, "MODEL_UNSUPPORTED_CLAIMS", 0L);
            pause(meeting, 0L);
            skips.prepareRetry(meeting, 11L, first.getId(), 1, 1L);
            rooms.claimReviewedRun(meeting, 5, 1L, 1);
            MeetingReviewedTurnSkip second = record(meeting, 11L, "MODEL_UNSUPPORTED_CLAIMS", 2L);
            assertNotEquals(first.getId(), second.getId());
            pause(meeting, 2L);
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> skips.prepareRetry(meeting, 11L, first.getId(), 1, 3L));
            assertEquals(second.getId(), skips.findSkip(meeting, 1, 11L, null).getId());
            skips.prepareRetry(meeting, 11L, second.getId(), 1, 3L);
            rooms.claimReviewedRun(meeting, 5, 3L, 1);
            MeetingReviewedTurnSkip third = record(meeting, 11L, "MODEL_UNSUPPORTED_CLAIMS", 4L);
            assertNotEquals(second.getId(), third.getId());
            assertNotNull(skipMapper.findById(first.getId()).getRetryPreparedAt());
            assertNotNull(skipMapper.findById(second.getId()).getRetryPreparedAt());
            assertEquals(third.getId(), skips.findSkip(meeting, 1, 11L, null).getId());
        } finally { clean(meeting); }
    }

    @Test
    void rejectsProviderUnknownConflictsAndStaleRequestsWithoutChangingAnySkip() {
        long meeting = room();
        try {
            MeetingReviewedTurnSkip local = record(meeting, 11L, "MODEL_UNSUPPORTED_CLAIMS", 0L);
            MeetingReviewedTurnSkip unknown = record(meeting, 12L, "MODEL_PLAN_PROVIDER_ERROR", 0L);
            MeetingReviewedTurnSkip conflict = record(meeting, 13L, "MODEL_CROSS_MEMBER_NUMERIC_CONFLICT", 0L);
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> skips.prepareRetry(meeting, 11L, local.getId(), 1, 0L));
            pause(meeting, 0L);
            assertThrows(IllegalArgumentException.class,
                    () -> skips.prepareRetry(meeting, 12L, unknown.getId(), 1, 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> skips.prepareRetry(meeting, 13L, conflict.getId(), 1, 1L));
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> skips.prepareRetry(meeting, 11L, local.getId(), 1, 0L));
            assertThrows(IllegalArgumentException.class,
                    () -> skips.prepareRetry(meeting, 11L, local.getId(), 2, 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> skips.prepareRetry(meeting, 12L, local.getId(), 1, 1L));
            assertEquals(3, skips.listSkippedByMeetingAndRound(meeting, 1).size());
            assertNull(skipMapper.findById(local.getId()).getRetryPreparedAt());
            assertEquals(1L, rooms.selectById(meeting).getRunEpoch());
        } finally { clean(meeting); }
    }

    @Test
    void concurrentIdenticalPreparationsProduceOneTransitionAndNoGeneration() {
        long meeting = room();
        try {
            MeetingReviewedTurnSkip local = record(meeting, 11L, "MODEL_UNSUPPORTED_CLAIMS", 0L);
            pause(meeting, 0L);
            CompletableFuture<MeetingReviewedTurnSkip> first = CompletableFuture.supplyAsync(
                    () -> skips.prepareRetry(meeting, 11L, local.getId(), 1, 1L));
            CompletableFuture<MeetingReviewedTurnSkip> second = CompletableFuture.supplyAsync(
                    () -> skips.prepareRetry(meeting, 11L, local.getId(), 1, 1L));
            assertEquals(first.join().getRetryPreparedAt(), second.join().getRetryPreparedAt());
            assertEquals(0, skips.listSkippedByMeetingId(meeting).size());
            assertEquals(0, opinions.selectByMeetingIdAndRound(meeting, 1).size());
            assertEquals(5, rooms.selectById(meeting).getStatus());
        } finally { clean(meeting); }
    }

    private long room() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("审核会议单议员重试测试");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(3);
        room.setCurrentRound(1);
        rooms.insert(room);
        return room.getId();
    }

    private MeetingReviewedTurnSkip record(long meeting, long member, String reason, long epoch) {
        MeetingReviewedTurnSkip skip = new MeetingReviewedTurnSkip();
        skip.setMeetingId(meeting);
        skip.setRoundNumber(1);
        skip.setMemberId(member);
        skip.setReasonCode(reason);
        skip.setRunEpoch(epoch);
        skip.setAsOf(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        return skips.recordIfCurrent(skip, 1, null);
    }

    private void pause(long meeting, long epoch) {
        assertEquals(1, rooms.updateStatusIfCurrentAndEpoch(meeting, 1, epoch, 5));
    }

    private static AiMeetingOpinion opinion(long meeting, long member) {
        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(meeting);
        opinion.setMemberId(member);
        opinion.setRoundNumber(1);
        opinion.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
        opinion.setContent("已核验发言保持不变");
        return opinion;
    }

    private void clean(long meeting) {
        skips.deleteByMeetingId(meeting);
        opinions.deleteByMeetingId(meeting);
        rooms.deleteById(meeting);
    }
}
