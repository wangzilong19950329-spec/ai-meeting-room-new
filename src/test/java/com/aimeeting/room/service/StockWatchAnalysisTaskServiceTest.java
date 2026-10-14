package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchAnalysisObservationIdentityMapper;
import com.aimeeting.room.dao.StockWatchAnalysisTaskMapper;
import com.aimeeting.room.entity.StockWatchAnalysisObservationIdentity;
import com.aimeeting.room.entity.StockWatchAnalysisTask;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockWatchAnalysisTaskServiceTest {

    @Test
    void canonicalClaimCreatesAndBindsOneStableTask() {
        StockWatchAnalysisTaskMapper taskMapper = mock(StockWatchAnalysisTaskMapper.class);
        StockWatchAnalysisObservationIdentityMapper identityMapper = mock(StockWatchAnalysisObservationIdentityMapper.class);
        StockWatchAnalysisTaskService service = new StockWatchAnalysisTaskService(
                taskMapper, identityMapper, mock(StockWatchV6RuntimeGate.class));
        StockWatchAnalysisObservationIdentity identity = new StockWatchAnalysisObservationIdentity();
        identity.setId(11L);
        identity.setRowVersion(0L);
        when(identityMapper.selectByIdentityKeyForUpdate(anyString())).thenReturn(identity);
        when(taskMapper.selectByObservation("002979", "SZ", "DAY", "DAY:2026-07-16:15:00"))
                .thenReturn(Collections.emptyList());
        when(taskMapper.insert(any())).thenAnswer(invocation -> {
            ((StockWatchAnalysisTask) invocation.getArgument(0)).setId(91L);
            return 1;
        });
        when(identityMapper.bindCanonical(11L, 0L, 91L, "CANONICAL")).thenReturn(1);

        StockWatchAnalysisTaskService.Reservation result = service.claimCanonical(
                "stock-analysis-slot-v1:sz:002979:DAY:2026-07-16:15:00",
                "stock-analysis-response-v6", "002979", "sz", "day",
                "DAY:2026-07-16:15:00", "deepseek_web_bridge");

        assertTrue(result.isNewSubmission());
        assertEquals(91L, result.getTask().getId());
        assertEquals("002979", result.getTask().getSymbol());
        assertEquals("SZ", result.getTask().getMarket());
        verify(identityMapper).insertIfAbsent(any());
        verify(identityMapper).bindCanonical(11L, 0L, 91L, "CANONICAL");
    }

    @Test
    void canonicalClaimReturnsBoundTaskWithoutCreatingAnother() {
        StockWatchAnalysisTaskMapper taskMapper = mock(StockWatchAnalysisTaskMapper.class);
        StockWatchAnalysisObservationIdentityMapper identityMapper = mock(StockWatchAnalysisObservationIdentityMapper.class);
        StockWatchAnalysisTaskService service = new StockWatchAnalysisTaskService(
                taskMapper, identityMapper, mock(StockWatchV6RuntimeGate.class));
        StockWatchAnalysisObservationIdentity identity = new StockWatchAnalysisObservationIdentity();
        identity.setId(12L);
        identity.setCanonicalTaskId(92L);
        identity.setRowVersion(3L);
        StockWatchAnalysisTask task = new StockWatchAnalysisTask();
        task.setId(92L);
        task.setRequestDigest("frozen-digest");
        task.setStatus("PROJECTED_SUCCESS");
        when(identityMapper.selectByIdentityKeyForUpdate(anyString())).thenReturn(identity);
        when(taskMapper.selectById(92L)).thenReturn(task);

        StockWatchAnalysisTaskService.Reservation result = service.claimCanonical(
                "unused-new-key", "stock-analysis-response-v6", "002979", "sz", "DAY",
                "DAY:2026-07-16:15:00", "deepseek_web_bridge");

        assertFalse(result.isNewSubmission());
        assertEquals(92L, result.getTask().getId());
        verify(taskMapper, never()).insert(any());
        verify(identityMapper, never()).bindCanonical(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void structuredBridgeUnknownOutcomeIsMarkedUnknown() {
        StockWatchAnalysisTaskService service = new StockWatchAnalysisTaskService(
                mock(StockWatchAnalysisTaskMapper.class),
                mock(StockWatchAnalysisObservationIdentityMapper.class),
                mock(StockWatchV6RuntimeGate.class));
        StockWatchBridgeHttpException error = StockWatchBridgeHttpException.fromResponse(
                "bridge",
                504,
                "{\"ok\":false,\"error\":\"provider_outcome_unknown\","
                        + "\"providerOutcomeUnknown\":true,\"retrySafe\":false}");

        assertTrue(service.shouldMarkUnknown(error));
        assertEquals("provider_outcome_unknown", error.getErrorCode());
        assertFalse(error.getRetrySafe());
    }
}
