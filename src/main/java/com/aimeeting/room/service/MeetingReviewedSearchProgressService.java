package com.aimeeting.room.service;

import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.config.MeetingReviewedSearchLedgerSchemaInitializer;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.MeetingReviewedSearchGap;
import com.aimeeting.room.entity.MeetingReviewedSearchPlan;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.aimeeting.room.enums.OpinionTypeEnum;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Safe, read-only progress projection. Shared search gaps are never assigned to a specific member. */
@Service
@RequiredArgsConstructor
public class MeetingReviewedSearchProgressService {
    private final MeetingReviewedSearchLedgerService ledger;
    private final MeetingReviewedSearchLedgerSchemaInitializer schema;
    @Autowired(required = false)
    private MeetingReviewedTurnSkipService turnSkipService;

    public void fill(MeetingDetailVO detail, Long meetingId, List<AiMeetingMember> members,
                     List<AiMeetingOpinion> opinions) {
        if (!schema.isReady()) {
            // A DML-only deployment may start before the optional reviewed schema is migrated.
            detail.setReviewedSearchProgress(Collections.emptyList());
            detail.setReviewedGapProgress(Collections.emptyList());
            return;
        }
        Map<Long, String> names = members == null ? Collections.emptyMap() : members.stream()
                .collect(Collectors.toMap(AiMeetingMember::getId, AiMeetingMember::getMemberName,
                        (first, ignored) -> first));
        Map<Long, Integer> memberOrder = new HashMap<>();
        if (members != null) {
            for (int i = 0; i < members.size(); i++) memberOrder.put(members.get(i).getId(), i);
        }
        // Context changes create a new persisted version for the same speaking turn.
        // Only the latest version represents the member's current work; earlier plans remain audit history.
        Map<String, MeetingReviewedSearchPlan> latestPlans = new LinkedHashMap<>();
        for (MeetingReviewedSearchPlan plan : ledger.listPlansByMeetingId(meetingId)) {
            String turn = plan.getRoundNumber() + ":" + plan.getMemberId() + ":" + plan.getTargetQuestionId();
            latestPlans.remove(turn);
            latestPlans.put(turn, plan);
        }
        Map<String, MeetingDetailVO.ReviewedSearchProgressVO> plansByTurn = new LinkedHashMap<>();
        for (MeetingReviewedSearchPlan plan : latestPlans.values()) {
            MeetingDetailVO.ReviewedSearchProgressVO item = new MeetingDetailVO.ReviewedSearchProgressVO();
            item.setRoundNumber(plan.getRoundNumber());
            item.setMemberId(plan.getMemberId());
            item.setMemberName(names.getOrDefault(plan.getMemberId(), "议员" + plan.getMemberId()));
            item.setAsOf(plan.getAsOf());
            item.setNeedQuestions(extractQuestions(plan.getNormalizedPlanJson()));
            boolean spoken = opinions != null && opinions.stream().anyMatch(opinion ->
                    Objects.equals(opinion.getMemberId(), plan.getMemberId())
                            && Objects.equals(opinion.getRoundNumber(), plan.getRoundNumber())
                            && Objects.equals(opinion.getOpinionType(), OpinionTypeEnum.STATEMENT.getCode())
                            && Objects.equals(opinion.getParentOpinionId(),
                                    plan.getTargetQuestionId() == null || plan.getTargetQuestionId() == 0
                                            ? null : plan.getTargetQuestionId()));
            item.setPlanStatus(spoken ? "SPOKEN" : "PLANNED");
            plansByTurn.put(turnKey(plan.getRoundNumber(), plan.getMemberId(), plan.getTargetQuestionId()), item);
        }
        if (turnSkipService != null) {
            for (MeetingReviewedTurnSkip skip : turnSkipService.listSkippedByMeetingId(meetingId)) {
                String turn = turnKey(skip.getRoundNumber(), skip.getMemberId(), skip.getTargetQuestionId());
                MeetingDetailVO.ReviewedSearchProgressVO item = plansByTurn.computeIfAbsent(turn, ignored -> {
                    MeetingDetailVO.ReviewedSearchProgressVO empty = new MeetingDetailVO.ReviewedSearchProgressVO();
                    empty.setRoundNumber(skip.getRoundNumber());
                    empty.setMemberId(skip.getMemberId());
                    empty.setMemberName(names.getOrDefault(skip.getMemberId(), "议员" + skip.getMemberId()));
                    empty.setAsOf(skip.getAsOf());
                    return empty;
                });
                if (!"SPOKEN".equals(item.getPlanStatus())) {
                    if (!Objects.equals(item.getAsOf(), skip.getAsOf())) item.setNeedQuestions(Collections.emptyList());
                    item.setAsOf(skip.getAsOf());
                    item.setPlanStatus("SKIPPED");
                    item.setSkipReasonCode(skip.getReasonCode());
                }
            }
        }
        List<MeetingDetailVO.ReviewedSearchProgressVO> orderedPlans = new ArrayList<>(plansByTurn.values());
        orderedPlans.sort(Comparator
                .comparing(MeetingDetailVO.ReviewedSearchProgressVO::getRoundNumber,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(item -> memberOrder.getOrDefault(item.getMemberId(), Integer.MAX_VALUE)));
        detail.setReviewedSearchProgress(orderedPlans);
        Map<Integer, MeetingDetailVO.ReviewedGapProgressVO> rounds = new LinkedHashMap<>();
        for (MeetingReviewedSearchGap gap : ledger.listGapsByMeetingId(meetingId)) {
            MeetingDetailVO.ReviewedGapProgressVO item = rounds.computeIfAbsent(gap.getRoundNumber(), round -> {
                MeetingDetailVO.ReviewedGapProgressVO progress = new MeetingDetailVO.ReviewedGapProgressVO();
                progress.setRoundNumber(round);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String status : List.of("PENDING", "IN_FLIGHT", "CROSS_CHECKED", "LOW_CONFIDENCE", "UNKNOWN", "REJECTED")) {
                    counts.put(status, 0);
                }
                progress.setStatusCounts(counts);
                return progress;
            });
            item.getStatusCounts().computeIfPresent(gap.getStatus(), (status, count) -> count + 1);
        }
        detail.setReviewedGapProgress(new ArrayList<>(rounds.values()));
    }

    private static String turnKey(Integer round, Long member, Long target) {
        return round + ":" + member + ":" + (target == null ? 0L : target);
    }

    private static List<String> extractQuestions(String normalizedPlanJson) {
        try {
            JSONObject plan = JSON.parseObject(normalizedPlanJson);
            JSONArray needs = plan == null ? null : plan.getJSONArray("needs");
            if (needs == null) return Collections.emptyList();
            List<String> questions = new ArrayList<>();
            for (int i = 0; i < needs.size(); i++) {
                JSONObject need = needs.getJSONObject(i);
                if (need != null && need.getString("question") != null) {
                    questions.add(need.getString("question"));
                }
            }
            return questions;
        } catch (RuntimeException malformed) {
            return Collections.emptyList();
        }
    }
}
