package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/** Commits a reviewed conclusion as one fenced database transition. Model calls happen before this transaction. */
@Service
@RequiredArgsConstructor
public class MeetingReviewedFinalizationWriter {
    private final AiMeetingRoomMapper roomMapper;

    @Transactional
    public void concludeIfCurrent(Long meetingId, long expectedEpoch, int expectedStatus,
                                  int expectedRound, String expectedHistory,
                                  String artifactJson, String conclusionHistory,
                                  String finalConclusion, Date concludedAt) {
        if (artifactJson == null || artifactJson.isBlank()
                || conclusionHistory == null || conclusionHistory.isBlank()
                || finalConclusion == null || finalConclusion.isBlank() || concludedAt == null) {
            throw new IllegalArgumentException("reviewed_conclusion_incomplete");
        }
        AiMeetingRoom room = roomMapper.selectByIdForUpdate(meetingId);
        if (room == null || !isActiveStatus(expectedStatus)
                || !Objects.equals(room.getStatus(), expectedStatus)
                || !Objects.equals(room.getRunEpoch(), expectedEpoch)
                || !Objects.equals(room.getCurrentRound(), expectedRound)
                || !Objects.equals(room.getConclusionHistory(), expectedHistory)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE");
        }
        requireOne(roomMapper.updateDecisionArtifactIfEpoch(meetingId, expectedEpoch, artifactJson));
        requireOne(roomMapper.updateConclusionHistoryIfEpoch(meetingId, expectedEpoch, conclusionHistory));
        requireOne(roomMapper.updateConclusionIfEpoch(meetingId, expectedEpoch, finalConclusion,
                concludedAt, MeetingStatusEnum.CONCLUDED.getCode()));
    }

    private static void requireOne(int updated) {
        if (updated != 1) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE");
        }
    }

    private static boolean isActiveStatus(int status) {
        return status == MeetingStatusEnum.IN_PROGRESS.getCode()
                || status == MeetingStatusEnum.REOPENED.getCode();
    }
}
