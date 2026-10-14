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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingReviewedOpinionWriterTest {
    @Autowired private AiMeetingRoomMapper rooms;
    @Autowired private AiMeetingOpinionMapper opinions;
    @Autowired private MeetingReviewedTurnSkipMapper skips;
    @Autowired private MeetingReviewedOpinionWriter writer;
    @Autowired private MeetingReviewedTurnSkipService skipService;

    @Test
    void pauseResumeEpochFencesOldWorkerEvenWhenStatusReturnsToInProgress() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("审核发言代际保护测试");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(1);
        rooms.insert(room);
        long id = room.getId();
        try {
            assertEquals(1, rooms.updateStatusIfCurrentAndEpoch(id,
                    MeetingStatusEnum.IN_PROGRESS.getCode(), 0L, MeetingStatusEnum.PAUSED.getCode()));
            assertEquals(1, rooms.claimReviewedRun(id, MeetingStatusEnum.PAUSED.getCode(), 1L,
                    MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertIfCurrent(opinion(id, 1L), 0L,
                            MeetingStatusEnum.IN_PROGRESS.getCode(), null));
            assertEquals(0, opinions.selectByMeetingIdAndRound(id, 1).size());
            writer.insertIfCurrent(opinion(id, 1L), 2L, MeetingStatusEnum.IN_PROGRESS.getCode(), null);
            assertEquals(1, opinions.selectByMeetingIdAndRound(id, 1).size());
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertIfCurrent(opinion(id, 1L), 2L,
                            MeetingStatusEnum.IN_PROGRESS.getCode(), null));
            assertEquals(1, opinions.selectByMeetingIdAndRound(id, 1).size());
        } finally {
            opinions.deleteByMeetingId(id);
            rooms.deleteById(id);
        }
    }

    @Test
    void humanMessageInvalidatesAReviewedOpinionPlannedBeforeItWasWritten() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("人类补充指令与审核发言提交序列化测试");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(1);
        rooms.insert(room);
        long id = room.getId();
        try {
            AiMeetingOpinion human = opinion(id, 0L);
            human.setContent("请补充权威数据来源");
            writer.insertHumanIfCurrent(human, 0L, MeetingStatusEnum.IN_PROGRESS.getCode());
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertIfCurrent(opinion(id, 1L), 0L,
                            MeetingStatusEnum.IN_PROGRESS.getCode(), null));
            assertEquals(1, opinions.selectByMeetingIdAndRound(id, 1).size());
            writer.insertIfCurrent(opinion(id, 1L), 0L,
                    MeetingStatusEnum.IN_PROGRESS.getCode(), human.getId());
            assertEquals(2, opinions.selectByMeetingIdAndRound(id, 1).size());
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertHumanIfCurrent(opinion(id, 0L), 0L,
                            MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertEquals(2, opinions.selectByMeetingIdAndRound(id, 1).size());
        } finally {
            opinions.deleteByMeetingId(id);
            rooms.deleteById(id);
        }
    }

    @Test
    void humanMessageCannotChangeTheRoundAfterItsFirstMemberWasSkipped() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("缺席后人类指令不重用旧席位计划");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(1);
        rooms.insert(room);
        long id = room.getId();
        try {
            MeetingReviewedTurnSkip skip = new MeetingReviewedTurnSkip();
            skip.setMeetingId(id);
            skip.setRoundNumber(1);
            skip.setMemberId(7L);
            skip.setTargetQuestionId(0L);
            skip.setReasonCode("N8N_REVIEW_UNVERIFIED");
            skip.setRunEpoch(0L);
            skip.setAsOf("2026-10-06");
            skip.setContextOpinionId(0L);
            skips.insert(skip);
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertHumanIfCurrent(opinion(id, 0L), 0L,
                            MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertEquals(0, opinions.selectByMeetingIdAndRound(id, 1).size());
        } finally {
            skips.deleteByMeetingId(id);
            rooms.deleteById(id);
        }
    }

    @Test
    void skippedMemberCannotPublishAStatementForTheSameTurn() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("弃权后发言提交序列化测试");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(1);
        rooms.insert(room);
        long id = room.getId();
        try {
            MeetingReviewedTurnSkip skip = new MeetingReviewedTurnSkip();
            skip.setMeetingId(id);
            skip.setRoundNumber(1);
            skip.setMemberId(7L);
            skip.setReasonCode("N8N_REVIEW_UNVERIFIED");
            skip.setRunEpoch(0L);
            skip.setAsOf(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
            skipService.recordIfCurrent(skip, MeetingStatusEnum.IN_PROGRESS.getCode(), null);

            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertIfCurrent(opinion(id, 7L), 0L,
                            MeetingStatusEnum.IN_PROGRESS.getCode(), null));
            assertEquals(0, opinions.selectByMeetingIdAndRound(id, 1).size());
            assertEquals(1, skips.listByMeetingAndRound(id, 1).size());
        } finally {
            skips.deleteByMeetingId(id);
            rooms.deleteById(id);
        }
    }

    private static AiMeetingOpinion opinion(long meetingId, long memberId) {
        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(meetingId);
        opinion.setMemberId(memberId);
        opinion.setRoundNumber(1);
        opinion.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
        opinion.setContent("已核验发言");
        return opinion;
    }
}
