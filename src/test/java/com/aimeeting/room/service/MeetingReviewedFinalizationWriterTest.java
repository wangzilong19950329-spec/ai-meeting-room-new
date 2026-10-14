package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingReviewedFinalizationWriterTest {
    @Autowired private AiMeetingRoomMapper rooms;
    @Autowired private MeetingReviewedFinalizationWriter writer;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void conclusionCommitsAsOneEpochTransitionAndRejectsLateRun() {
        long id = newActiveRoom();
        try {
            writer.concludeIfCurrent(id, 0L, MeetingStatusEnum.IN_PROGRESS.getCode(), 1,
                    null, "{\"finalDecision\":\"通过\"}", "[{\"eventType\":\"CONCLUDED\"}]",
                    "通过", new Date());
            AiMeetingRoom concluded = rooms.selectById(id);
            assertEquals(MeetingStatusEnum.CONCLUDED.getCode(), concluded.getStatus());
            assertEquals(1L, concluded.getRunEpoch());
            assertEquals("通过", concluded.getFinalConclusion());
            assertEquals("{\"finalDecision\":\"通过\"}", concluded.getDecisionArtifact());
            assertEquals("[{\"eventType\":\"CONCLUDED\"}]", concluded.getConclusionHistory());
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.concludeIfCurrent(id, 0L, MeetingStatusEnum.IN_PROGRESS.getCode(), 1,
                            null, "{}", "[]", "过期结论", new Date()));
            assertEquals("通过", rooms.selectById(id).getFinalConclusion());
        } finally {
            rooms.deleteById(id);
        }
    }

    @Test
    void finalStatusWriteFailureRollsBackArtifactAndHistory() {
        long id = newActiveRoom();
        String constraint = "chk_reviewed_finalize_" + id;
        jdbc.execute("ALTER TABLE ai_meeting_room ADD CONSTRAINT " + constraint
                + " CHECK (id <> " + id + " OR status <> " + MeetingStatusEnum.CONCLUDED.getCode() + ")");
        try {
            assertThrows(RuntimeException.class,
                    () -> writer.concludeIfCurrent(id, 0L, MeetingStatusEnum.IN_PROGRESS.getCode(), 1,
                            null, "{\"finalDecision\":\"通过\"}", "[{\"eventType\":\"CONCLUDED\"}]",
                            "通过", new Date()));
            AiMeetingRoom after = rooms.selectById(id);
            assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(), after.getStatus());
            assertEquals(0L, after.getRunEpoch());
            assertNull(after.getDecisionArtifact());
            assertNull(after.getConclusionHistory());
            assertNull(after.getFinalConclusion());
        } finally {
            jdbc.execute("ALTER TABLE ai_meeting_room DROP CONSTRAINT " + constraint);
            rooms.deleteById(id);
        }
    }

    @Test
    void changedRoundIsRejectedBeforeAnyWrite() {
        long id = newActiveRoom();
        try {
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.concludeIfCurrent(id, 0L, MeetingStatusEnum.IN_PROGRESS.getCode(), 2,
                            null, "{}", "[]", "错误轮次", new Date()));
            assertNull(rooms.selectById(id).getDecisionArtifact());
        } finally {
            rooms.deleteById(id);
        }
    }

    private long newActiveRoom() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("审核结论原子提交测试");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(1);
        rooms.insert(room);
        return room.getId();
    }
}
