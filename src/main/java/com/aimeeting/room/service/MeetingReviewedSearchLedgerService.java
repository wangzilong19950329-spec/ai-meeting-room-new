package com.aimeeting.room.service;

import com.aimeeting.room.dao.MeetingReviewedSearchLedgerMapper;
import com.aimeeting.room.entity.MeetingReviewedSearchGap;
import com.aimeeting.room.entity.MeetingReviewedSearchPlan;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Durable identity and single-dispatch gate for reviewed meeting searches.
 * A process crash after claiming a gap leaves it IN_FLIGHT; only an explicit
 * operator decision may retry it because the remote request may have run.
 */
@Service
@DependsOn("meetingReviewedSearchLedgerSchemaInitializer")
@RequiredArgsConstructor
public class MeetingReviewedSearchLedgerService {
    public static final String PENDING = "PENDING";
    public static final String IN_FLIGHT = "IN_FLIGHT";
    public static final String CROSS_CHECKED = "CROSS_CHECKED";
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String REJECTED = "REJECTED";

    private final MeetingReviewedSearchLedgerMapper mapper;

    public List<MeetingReviewedSearchPlan> listPlansByMeetingId(Long meetingId) {
        return mapper.listPlansByMeetingId(meetingId);
    }

    public List<MeetingReviewedSearchGap> listGapsByMeetingId(Long meetingId) {
        return mapper.listGapsByMeetingId(meetingId);
    }

    public MeetingReviewedSearchPlan findPlan(Long meetingId, int roundNumber, Long memberId,
                                               Long targetQuestionId) {
        validateTurn(meetingId, roundNumber, memberId);
        return mapper.findPlan(meetingId, roundNumber, memberId, targetId(targetQuestionId));
    }

    public MeetingReviewedSearchPlan findPlanVersion(Long meetingId, int roundNumber, Long memberId,
                                                      Long targetQuestionId, String asOf,
                                                      Long contextOpinionId) {
        validateTurn(meetingId, roundNumber, memberId);
        validateAsOf(asOf);
        return mapper.findPlanVersion(meetingId, roundNumber, memberId, targetId(targetQuestionId),
                asOf, contextId(contextOpinionId));
    }

    /** Compatibility overload for callers without an explicit human-context anchor. */
    public MeetingReviewedSearchPlan savePlanIfAbsent(Long meetingId, int roundNumber, Long memberId,
                                                       Long targetQuestionId, String asOf,
                                                       String normalizedPlanJson) {
        return savePlanIfAbsent(meetingId, roundNumber, memberId, targetQuestionId,
                asOf, null, normalizedPlanJson);
    }

    /** Append an immutable plan version for this date and human-context snapshot. */
    public MeetingReviewedSearchPlan savePlanIfAbsent(Long meetingId, int roundNumber, Long memberId,
                                                       Long targetQuestionId, String asOf,
                                                       Long contextOpinionId, String normalizedPlanJson) {
        validateTurn(meetingId, roundNumber, memberId);
        validateAsOf(asOf);
        if (normalizedPlanJson == null || normalizedPlanJson.isBlank()) {
            throw new IllegalArgumentException("reviewed_plan_json_missing");
        }
        JSONObject plan = JSON.parseObject(normalizedPlanJson);
        if (plan == null) throw new IllegalArgumentException("reviewed_plan_json_invalid");
        Long target = targetId(targetQuestionId);
        Long context = contextId(contextOpinionId);
        MeetingReviewedSearchPlan existing = mapper.findPlanVersion(meetingId, roundNumber, memberId,
                target, asOf, context);
        if (existing != null) return existing;

        MeetingReviewedSearchPlan prior = mapper.findPlan(meetingId, roundNumber, memberId, target);
        if (prior != null) {
            // A new plan must not quietly bypass a remote request with an unknown or rejected result.
            boolean unresolved = mapper.listGapsByMeetingId(meetingId).stream()
                    .anyMatch(gap -> gap.getRoundNumber() != null && gap.getRoundNumber() == roundNumber
                            && gap.getAsOf().equals(prior.getAsOf())
                            && (IN_FLIGHT.equals(gap.getStatus()) || UNKNOWN.equals(gap.getStatus())
                                || REJECTED.equals(gap.getStatus())));
            if (unresolved) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected(
                        "PRIOR_SEARCH_OUTCOME_REQUIRES_REVIEW");
            }
        }

        MeetingReviewedSearchPlan candidate = new MeetingReviewedSearchPlan();
        candidate.setMeetingId(meetingId);
        candidate.setRoundNumber(roundNumber);
        candidate.setMemberId(memberId);
        candidate.setTargetQuestionId(target);
        candidate.setAsOf(asOf);
        candidate.setContextOpinionId(context);
        candidate.setSupersedesPlanId(prior == null ? null : prior.getId());
        candidate.setNormalizedPlanJson(normalizedPlanJson);
        try {
            mapper.insertPlan(candidate);
        } catch (DataIntegrityViolationException duplicate) {
            MeetingReviewedSearchPlan winner = mapper.findPlanVersion(meetingId, roundNumber,
                    memberId, target, asOf, context);
            if (winner == null) throw duplicate;
            return winner;
        }
        return mapper.findPlanVersion(meetingId, roundNumber, memberId, target, asOf, context);
    }

    public MeetingReviewedSearchGap findOrCreateGap(Long meetingId, int roundNumber, String asOf,
                                                     String exactQuestion) {
        if (meetingId == null || meetingId <= 0 || roundNumber < 0) {
            throw new IllegalArgumentException("reviewed_gap_turn_invalid");
        }
        validateAsOf(asOf);
        if (exactQuestion == null || exactQuestion.isBlank() || exactQuestion.length() > 20_000) {
            throw new IllegalArgumentException("reviewed_gap_question_invalid");
        }
        String hash = sha256(exactQuestion);
        MeetingReviewedSearchGap existing = mapper.findGap(meetingId, roundNumber, asOf, hash);
        if (existing != null) return requireQuestionMatch(existing, exactQuestion);

        MeetingReviewedSearchGap candidate = new MeetingReviewedSearchGap();
        candidate.setMeetingId(meetingId);
        candidate.setRoundNumber(roundNumber);
        candidate.setAsOf(asOf);
        candidate.setQuestionHash(hash);
        candidate.setExactQuestion(exactQuestion);
        candidate.setRequestId("meeting-" + UUID.randomUUID());
        candidate.setStatus(PENDING);
        try {
            mapper.insertGap(candidate);
        } catch (DataIntegrityViolationException duplicate) {
            MeetingReviewedSearchGap winner = mapper.findGap(meetingId, roundNumber, asOf, hash);
            if (winner == null) throw duplicate;
            return requireQuestionMatch(winner, exactQuestion);
        }
        return requireQuestionMatch(mapper.findGap(meetingId, roundNumber, asOf, hash), exactQuestion);
    }

    public MeetingReviewedSearchGap findGapById(Long gapId) {
        return mapper.findGapById(gapId);
    }

    /** Exactly one caller changes PENDING to IN_FLIGHT. All later claims return false. */
    public boolean claimPendingGap(Long gapId) {
        return gapId != null && mapper.claimPendingGap(gapId) == 1;
    }

    /** Only the claiming worker may use this before it calls the remote provider. */
    public boolean releaseUnsubmittedGap(Long gapId) {
        return gapId != null && mapper.releaseUnsubmittedGap(gapId) == 1;
    }

    /** Accept only a packet proving the persisted request ID and exact question. */
    public boolean markCrossChecked(Long gapId, String packetJson) {
        MeetingReviewedSearchGap gap = requireGap(gapId);
        if (!IN_FLIGHT.equals(gap.getStatus())) return false;
        JSONObject packet = JSON.parseObject(packetJson);
        MeetingReviewedEvidenceService.acceptPacket(packet, gap.getRequestId(), gap.getExactQuestion());
        if (!CROSS_CHECKED.equals(packet.getString("status"))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_PACKET_STATUS_INVALID");
        }
        String packetAsOf = packet.getString("asOf");
        if (packetAsOf != null && !packetAsOf.equals(gap.getAsOf())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_PACKET_ASOF_INVALID");
        }
        return mapper.markCrossChecked(gapId, packetJson) == 1;
    }

    /** Persist a completed search with incomplete corroboration without promoting it to verified. */
    public boolean markLowConfidence(Long gapId, String packetJson) {
        MeetingReviewedSearchGap gap = requireGap(gapId);
        if (!IN_FLIGHT.equals(gap.getStatus())) return false;
        JSONObject packet = JSON.parseObject(packetJson);
        MeetingReviewedEvidenceService.acceptPacket(packet, gap.getRequestId(), gap.getExactQuestion());
        if (!"PARTIAL".equals(packet.getString("status"))
                && !"UNVERIFIED".equals(packet.getString("status"))) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_PACKET_STATUS_INVALID");
        }
        String packetAsOf = packet.getString("asOf");
        if (packetAsOf != null && !packetAsOf.equals(gap.getAsOf())) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_PACKET_ASOF_INVALID");
        }
        return mapper.markLowConfidence(gapId, packetJson) == 1;
    }

    /** Unknown remote outcome is terminal for automatic resume. */
    public boolean markUnknown(Long gapId, String reason) {
        return gapId != null && mapper.markUnknown(gapId, reasonCode(reason)) == 1;
    }

    /** A known HTTP or packet rejection is terminal for automatic resume. */
    public boolean markRejected(Long gapId, String reason) {
        return gapId != null && mapper.markRejected(gapId, reasonCode(reason)) == 1;
    }

    @Transactional
    public int deleteByMeetingId(Long meetingId) {
        if (meetingId == null) return 0;
        return mapper.deleteGapsByMeetingId(meetingId) + mapper.deletePlansByMeetingId(meetingId);
    }

    private static MeetingReviewedSearchGap requireQuestionMatch(MeetingReviewedSearchGap gap,
                                                                  String exactQuestion) {
        if (gap == null || !exactQuestion.equals(gap.getExactQuestion())) {
            throw new IllegalStateException("reviewed_gap_hash_identity_conflict");
        }
        return gap;
    }

    private MeetingReviewedSearchGap requireGap(Long gapId) {
        if (gapId == null) throw new IllegalArgumentException("reviewed_gap_id_missing");
        MeetingReviewedSearchGap gap = mapper.findGapById(gapId);
        if (gap == null) throw new IllegalArgumentException("reviewed_gap_missing");
        return gap;
    }

    private static Long targetId(Long targetQuestionId) {
        if (targetQuestionId != null && targetQuestionId < 1) {
            throw new IllegalArgumentException("reviewed_target_question_id_invalid");
        }
        return targetQuestionId == null ? 0L : targetQuestionId;
    }

    private static Long contextId(Long contextOpinionId) {
        if (contextOpinionId != null && contextOpinionId < 1) {
            throw new IllegalArgumentException("reviewed_context_opinion_id_invalid");
        }
        return contextOpinionId == null ? 0L : contextOpinionId;
    }

    private static void validateTurn(Long meetingId, int roundNumber, Long memberId) {
        if (meetingId == null || meetingId <= 0 || roundNumber < 0 || memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("reviewed_plan_turn_invalid");
        }
    }

    private static void validateAsOf(String asOf) {
        if (asOf == null || !LocalDate.parse(asOf).toString().equals(asOf)) {
            throw new IllegalArgumentException("reviewed_as_of_invalid");
        }
    }

    /** Persist only a bounded machine-readable code, never raw provider messages or credentials. */
    private static String reasonCode(String reason) {
        return reason != null && reason.matches("[A-Z][A-Z0-9_:-]{0,127}")
                ? reason : "PROVIDER_OUTCOME_UNKNOWN";
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }
}
