package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.MeetingReviewedTurnSkipMapper;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Stores abstentions separately from discussion and fences them by the active run. */
@Service
@DependsOn("meetingReviewedSearchLedgerSchemaInitializer")
@RequiredArgsConstructor
public class MeetingReviewedTurnSkipService {
    private final AiMeetingRoomMapper roomMapper;
    private final AiMeetingOpinionMapper opinionMapper;
    private final MeetingReviewedTurnSkipMapper skipMapper;

    public MeetingReviewedTurnSkip findSkip(Long meetingId, int roundNumber, Long memberId,
                                             Long targetQuestionId) {
        return skipMapper.findByTurn(meetingId, roundNumber, memberId, targetId(targetQuestionId));
    }

    public List<MeetingReviewedTurnSkip> listSkippedByMeetingAndRound(Long meetingId, int roundNumber) {
        if (meetingId == null) return Collections.emptyList();
        return skipMapper.listByMeetingAndRound(meetingId, roundNumber);
    }

    public List<MeetingReviewedTurnSkip> listSkippedByMeetingId(Long meetingId) {
        if (meetingId == null) return Collections.emptyList();
        return skipMapper.listByMeetingId(meetingId);
    }

    /** A failed member may abstain only while its own run and discussion context remain current. */
    @Transactional
    public MeetingReviewedTurnSkip recordIfCurrent(MeetingReviewedTurnSkip requested,
                                                    int expectedStatus,
                                                    Long expectedHumanOpinionId) {
        if (requested == null || requested.getMeetingId() == null
                || requested.getRoundNumber() == null || requested.getRoundNumber() < 0
                || requested.getMemberId() == null || requested.getMemberId() <= 0
                || requested.getAsOf() == null
                || !LocalDate.parse(requested.getAsOf()).toString().equals(requested.getAsOf())) {
            throw new IllegalArgumentException("reviewed_skip_identity_invalid");
        }
        if (requested.getReasonCode() == null
                || !requested.getReasonCode().matches("[A-Z][A-Z0-9_:-]{0,127}")) {
            throw new IllegalArgumentException("reviewed_skip_reason_invalid");
        }
        AiMeetingRoom room = roomMapper.selectByIdForUpdate(requested.getMeetingId());
        if (room == null || !isActiveStatus(expectedStatus)
                || !Objects.equals(room.getStatus(), expectedStatus)
                || !Objects.equals(room.getCurrentRound(), requested.getRoundNumber())
                || (requested.getRunEpoch() != null
                    && !Objects.equals(room.getRunEpoch(), requested.getRunEpoch()))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE");
        }
        if (!Objects.equals(requested.getAsOf(), LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_SEARCH_PLAN_STALE");
        }
        List<AiMeetingOpinion> roundOpinions = opinionMapper.selectByMeetingIdAndRound(
                requested.getMeetingId(), requested.getRoundNumber());
        Long currentHumanOpinionId = roundOpinions == null ? null : roundOpinions.stream()
                .filter(opinion -> Objects.equals(opinion.getMemberId(), 0L))
                .map(AiMeetingOpinion::getId).filter(Objects::nonNull)
                .max(Long::compareTo).orElse(null);
        if (!Objects.equals(currentHumanOpinionId, expectedHumanOpinionId)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_HUMAN_CONTEXT_CHANGED");
        }
        long target = targetId(requested.getTargetQuestionId());
        if (roundOpinions != null && roundOpinions.stream().anyMatch(opinion ->
                Objects.equals(opinion.getMemberId(), requested.getMemberId())
                        && Objects.equals(opinion.getOpinionType(), OpinionTypeEnum.STATEMENT.getCode())
                        && Objects.equals(opinion.getParentOpinionId(), target == 0L ? null : target))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_OPINION_ALREADY_WRITTEN");
        }
        MeetingReviewedTurnSkip existing = skipMapper.findByTurn(requested.getMeetingId(),
                requested.getRoundNumber(), requested.getMemberId(), target);
        if (existing != null) return existing;
        requested.setTargetQuestionId(target);
        requested.setContextOpinionId(expectedHumanOpinionId == null ? 0L : expectedHumanOpinionId);
        requested.setActiveTurn(1);
        try {
            skipMapper.insert(requested);
            return requested;
        } catch (DataIntegrityViolationException duplicate) {
            MeetingReviewedTurnSkip winner = skipMapper.findByTurn(requested.getMeetingId(),
                    requested.getRoundNumber(), requested.getMemberId(), target);
            if (winner == null) throw duplicate;
            return winner;
        }
    }

    /** Prepare one deterministic local rejection for retry without starting model work or erasing history. */
    @Transactional
    public MeetingReviewedTurnSkip prepareRetry(Long meetingId, Long memberId, Long skipId,
                                                Integer expectedRound, Long expectedEpoch) {
        if (meetingId == null || memberId == null || skipId == null || expectedRound == null
                || expectedRound < 1 || expectedEpoch == null || expectedEpoch < 0) {
            throw new IllegalArgumentException("REVIEWED_RETRY_IDENTITY_INVALID");
        }
        AiMeetingRoom room = roomMapper.selectByIdForUpdate(meetingId);
        MeetingReviewedTurnSkip skip = skipMapper.findById(skipId);
        if (room == null || skip == null || !Objects.equals(skip.getMeetingId(), meetingId)
                || !Objects.equals(skip.getMemberId(), memberId)
                || !Objects.equals(skip.getRoundNumber(), expectedRound)) {
            throw new IllegalArgumentException("REVIEWED_RETRY_TURN_MISMATCH");
        }
        if (!Objects.equals(room.getCurrentRound(), expectedRound)
                || !Objects.equals(room.getRunEpoch(), expectedEpoch)
                || !Objects.equals(room.getStatus(), MeetingStatusEnum.PAUSED.getCode())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE");
        }
        // Provider/transport/unknown-outcome failures must never become eligible through this API.
        if (!"MODEL_UNSUPPORTED_CLAIMS".equals(skip.getReasonCode())) {
            throw new IllegalArgumentException("REVIEWED_RETRY_REASON_NOT_SAFE");
        }
        if (skip.getRetryPreparedAt() != null) {
            if (Objects.equals(skip.getRetryPreparedEpoch(), expectedEpoch)) return skip;
            throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_RETRY_ALREADY_CONSUMED");
        }
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingIdAndRound(meetingId, expectedRound);
        Long currentHumanId = opinions == null ? null : opinions.stream()
                .filter(opinion -> Objects.equals(opinion.getMemberId(), 0L))
                .map(AiMeetingOpinion::getId).filter(Objects::nonNull).max(Long::compareTo).orElse(null);
        if (!Objects.equals(skip.getContextOpinionId(), currentHumanId == null ? 0L : currentHumanId)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_HUMAN_CONTEXT_CHANGED");
        }
        if (opinions != null && opinions.stream().anyMatch(opinion ->
                Objects.equals(opinion.getMemberId(), memberId)
                        && Objects.equals(opinion.getOpinionType(), OpinionTypeEnum.STATEMENT.getCode())
                        && Objects.equals(opinion.getParentOpinionId(), skip.getTargetQuestionId() == 0L
                                ? null : skip.getTargetQuestionId()))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEMBER_OPINION_ALREADY_WRITTEN");
        }
        if (skipMapper.prepareRetry(skipId, expectedEpoch) != 1) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_RETRY_STATE_CHANGED");
        }
        return skipMapper.findById(skipId);
    }

    @Transactional
    public int deleteByMeetingId(Long meetingId) {
        return meetingId == null ? 0 : skipMapper.deleteByMeetingId(meetingId);
    }

    private static long targetId(Long targetQuestionId) {
        if (targetQuestionId != null && targetQuestionId < 1) {
            throw new IllegalArgumentException("reviewed_skip_target_invalid");
        }
        return targetQuestionId == null ? 0L : targetQuestionId;
    }

    private static boolean isActiveStatus(int status) {
        return status == MeetingStatusEnum.IN_PROGRESS.getCode()
                || status == MeetingStatusEnum.REOPENED.getCode();
    }
}
