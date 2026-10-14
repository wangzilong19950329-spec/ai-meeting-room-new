package com.aimeeting.room.service;

import com.aimeeting.room.config.MeetingReviewedSearchLedgerSchemaInitializer;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingReviewedSearchProgressSkipTest {
    @Test
    void skipBeforePlanWasSavedStillAppearsInMeetingProgress() {
        MeetingReviewedSearchLedgerService ledger = mock(MeetingReviewedSearchLedgerService.class);
        MeetingReviewedSearchLedgerSchemaInitializer schema = mock(MeetingReviewedSearchLedgerSchemaInitializer.class);
        MeetingReviewedTurnSkipService skips = mock(MeetingReviewedTurnSkipService.class);
        when(schema.isReady()).thenReturn(true);
        when(ledger.listPlansByMeetingId(5L)).thenReturn(List.of());
        when(ledger.listGapsByMeetingId(5L)).thenReturn(List.of());
        MeetingReviewedTurnSkip skip = new MeetingReviewedTurnSkip();
        skip.setMeetingId(5L);
        skip.setRoundNumber(1);
        skip.setMemberId(12L);
        skip.setAsOf("2026-10-06");
        skip.setReasonCode("N8N_REVIEW_UNVERIFIED");
        when(skips.listSkippedByMeetingId(5L)).thenReturn(List.of(skip));
        MeetingReviewedSearchProgressService progress = new MeetingReviewedSearchProgressService(ledger, schema);
        ReflectionTestUtils.setField(progress, "turnSkipService", skips);
        MeetingDetailVO detail = new MeetingDetailVO();

        progress.fill(detail, 5L, List.of(), List.of());

        assertEquals(1, detail.getReviewedSearchProgress().size());
        assertEquals("SKIPPED", detail.getReviewedSearchProgress().get(0).getPlanStatus());
        assertEquals("N8N_REVIEW_UNVERIFIED",
                detail.getReviewedSearchProgress().get(0).getSkipReasonCode());
    }
}
