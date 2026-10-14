package com.aimeeting.room.service;

import com.aimeeting.room.config.MeetingReviewedSearchLedgerSchemaInitializer;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.entity.MeetingReviewedSearchPlan;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeetingReviewedSearchProgressSchemaTest {
    @Test
    void missingOptionalSchemaDoesNotBreakMeetingDetail() {
        MeetingReviewedSearchLedgerService ledger = mock(MeetingReviewedSearchLedgerService.class);
        MeetingReviewedSearchLedgerSchemaInitializer schema = mock(MeetingReviewedSearchLedgerSchemaInitializer.class);
        when(schema.isReady()).thenReturn(false);
        MeetingDetailVO detail = new MeetingDetailVO();

        new MeetingReviewedSearchProgressService(ledger, schema)
                .fill(detail, 7L, Collections.emptyList(), Collections.emptyList());

        assertTrue(detail.getReviewedSearchProgress().isEmpty());
        assertTrue(detail.getReviewedGapProgress().isEmpty());
        verifyNoInteractions(ledger);
    }

    @Test
    void supersededPlanDoesNotAppearAsAnotherPendingSpeaker() {
        MeetingReviewedSearchLedgerService ledger = mock(MeetingReviewedSearchLedgerService.class);
        MeetingReviewedSearchLedgerSchemaInitializer schema = mock(MeetingReviewedSearchLedgerSchemaInitializer.class);
        when(schema.isReady()).thenReturn(true);
        MeetingReviewedSearchPlan oldPlan = plan(1L, "旧资料需求");
        MeetingReviewedSearchPlan newPlan = plan(2L, "新资料需求");
        when(ledger.listPlansByMeetingId(7L)).thenReturn(Arrays.asList(oldPlan, newPlan));
        when(ledger.listGapsByMeetingId(7L)).thenReturn(Collections.emptyList());
        MeetingDetailVO detail = new MeetingDetailVO();

        new MeetingReviewedSearchProgressService(ledger, schema)
                .fill(detail, 7L, Collections.emptyList(), Collections.emptyList());

        assertTrue(detail.getReviewedSearchProgress().size() == 1);
        assertTrue(detail.getReviewedSearchProgress().get(0).getNeedQuestions().contains("新资料需求"));
    }

    private static MeetingReviewedSearchPlan plan(long id, String question) {
        MeetingReviewedSearchPlan plan = new MeetingReviewedSearchPlan();
        plan.setId(id);
        plan.setRoundNumber(1);
        plan.setMemberId(20L);
        plan.setTargetQuestionId(0L);
        plan.setAsOf("2026-10-06");
        plan.setNormalizedPlanJson("{\"needs\":[{\"question\":\"" + question + "\"}]}");
        return plan;
    }
}
