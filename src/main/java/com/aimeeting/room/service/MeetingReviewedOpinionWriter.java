package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.MeetingReviewedTurnSkipMapper;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Serializes a reviewed opinion commit with meeting status and run-epoch transitions. */
@Service
@RequiredArgsConstructor
public class MeetingReviewedOpinionWriter {
    private final AiMeetingRoomMapper roomMapper;
    private final AiMeetingOpinionMapper opinionMapper;
    private final MeetingReviewedTurnSkipMapper skipMapper;

    @Transactional
    public void insertIfCurrent(AiMeetingOpinion opinion, long expectedEpoch, int expectedStatus,
                                Long expectedHumanOpinionId) {
        AiMeetingRoom room = roomMapper.selectByIdForUpdate(opinion.getMeetingId());
        if (room == null || !Objects.equals(room.getRunEpoch(), expectedEpoch)
                || !Objects.equals(room.getStatus(), expectedStatus)
                || Objects.equals(room.getStatus(), MeetingStatusEnum.PAUSED.getCode())
                || !Objects.equals(room.getCurrentRound(), opinion.getRoundNumber())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE");
        }
        List<AiMeetingOpinion> existing = opinionMapper.selectByMeetingIdAndRound(
                opinion.getMeetingId(), opinion.getRoundNumber());
        Long latestHumanOpinionId = existing == null ? null : existing.stream()
                .filter(candidate -> Objects.equals(candidate.getMemberId(), 0L))
                .map(AiMeetingOpinion::getId)
                .filter(Objects::nonNull)
                .max(Long::compareTo).orElse(null);
        if (!Objects.equals(latestHumanOpinionId, expectedHumanOpinionId)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_HUMAN_CONTEXT_CHANGED");
        }
        if (existing != null && existing.stream().anyMatch(candidate ->
                Objects.equals(candidate.getMemberId(), opinion.getMemberId())
                        && Objects.equals(candidate.getOpinionType(), OpinionTypeEnum.STATEMENT.getCode())
                        && Objects.equals(candidate.getParentOpinionId(), opinion.getParentOpinionId()))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_OPINION_ALREADY_WRITTEN");
        }
        Long targetQuestionId = opinion.getParentOpinionId() == null ? 0L : opinion.getParentOpinionId();
        if (skipMapper.findByTurn(opinion.getMeetingId(), opinion.getRoundNumber(),
                opinion.getMemberId(), targetQuestionId) != null) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_TURN_ALREADY_SKIPPED");
        }
        opinionMapper.insert(opinion);
    }

    /** Serialize an active human message with reviewed model commits. */
    @Transactional
    public void insertHumanIfCurrent(AiMeetingOpinion opinion, long expectedEpoch, int expectedStatus) {
        AiMeetingRoom room = roomMapper.selectByIdForUpdate(opinion.getMeetingId());
        if (room == null || !Objects.equals(room.getRunEpoch(), expectedEpoch)
                || !Objects.equals(room.getStatus(), expectedStatus)
                || !Objects.equals(room.getCurrentRound() == null || room.getCurrentRound() < 1
                        ? 1 : room.getCurrentRound(), opinion.getRoundNumber())
                || !Objects.equals(opinion.getMemberId(), 0L)
                || !isActiveStatus(expectedStatus)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE");
        }
        List<AiMeetingOpinion> roundOpinions = opinionMapper.selectByMeetingIdAndRound(
                opinion.getMeetingId(), opinion.getRoundNumber());
        if (roundOpinions != null && roundOpinions.stream().anyMatch(existing ->
                !Objects.equals(existing.getMemberId(), 0L)
                        && Objects.equals(existing.getOpinionType(), OpinionTypeEnum.STATEMENT.getCode()))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected(
                    "MEETING_ROUND_ALREADY_ADVANCED_USE_FOLLOW_UP");
        }
        if (!skipMapper.listByMeetingAndRound(opinion.getMeetingId(), opinion.getRoundNumber()).isEmpty()) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected(
                    "MEETING_ROUND_ALREADY_ADVANCED_USE_FOLLOW_UP");
        }
        opinionMapper.insert(opinion);
    }

    private static boolean isActiveStatus(int status) {
        return status == MeetingStatusEnum.IN_PROGRESS.getCode()
                || status == MeetingStatusEnum.REOPENED.getCode();
    }
}
