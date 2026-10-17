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
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void explicitPreSubmitBridgeTimeoutIsNotMarkedUnknown() {
        StockWatchAnalysisTaskService service = new StockWatchAnalysisTaskService(
                mock(StockWatchAnalysisTaskMapper.class),
                mock(StockWatchAnalysisObservationIdentityMapper.class),
                mock(StockWatchV6RuntimeGate.class));
        StockWatchBridgeHttpException error = StockWatchBridgeHttpException.fromResponse(
                "bridge",
                429,
                "{\"ok\":false,\"error\":\"bridge_queue_timeout\","
                        + "\"providerOutcomeUnknown\":false,\"retrySafe\":true}");

        assertFalse(service.shouldMarkUnknown(error));
        assertEquals("bridge_queue_timeout", error.getErrorCode());
        assertTrue(error.getRetrySafe());
    }

    @Test
    void incompleteBridgeResponseIsClassifiedAsInvalidBeforeTimeoutWording() {
        StockWatchAnalysisTaskService service = new StockWatchAnalysisTaskService(
                mock(StockWatchAnalysisTaskMapper.class),
                mock(StockWatchAnalysisObservationIdentityMapper.class),
                mock(StockWatchV6RuntimeGate.class));
        IllegalStateException error = new IllegalStateException(
                "DeepSeek 网页回答未完整生成，completionReason=timeout_incomplete_json");

        assertTrue(service.shouldMarkInvalid(error));
    }

    @Test
    void unknownTaskRemainsRecoverableAndAcceptsLateBridgeResponse() {
        StockWatchAnalysisTaskMapper taskMapper = mock(StockWatchAnalysisTaskMapper.class);
        StockWatchAnalysisTaskService service = new StockWatchAnalysisTaskService(
                taskMapper,
                mock(StockWatchAnalysisObservationIdentityMapper.class),
                mock(StockWatchV6RuntimeGate.class));
        StockWatchAnalysisTask task = new StockWatchAnalysisTask();
        task.setId(1499L);
        task.setSubmissionKey("stock-analysis-slot-v1:sz:300750:DAY:2026-10-16:13:00");
        task.setRequestDigest("frozen-digest");
        task.setStatus("UNKNOWN");
        task.setRowVersion(3L);
        StockWatchAnalysisTaskService.Reservation reservation =
                StockWatchAnalysisTaskService.Reservation.existing(task, "frozen-digest");
        when(taskMapper.transition(eq(1499L), eq(3L), any(), eq("RESPONSE_RECEIVED"),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                eq("RECOVERED_FROM_BRIDGE_LEDGER"), any(), any())).thenReturn(1);

        service.markResponseReceived(reservation, "{\"stance\":\"中性\"}", "complete", "done",
                "RECOVERED_FROM_BRIDGE_LEDGER", "target-1", "https://chat.deepseek.com/a");

        assertTrue(reservation.isInProgress());
        assertFalse(reservation.isTerminalFailure());
        assertEquals("RESPONSE_RECEIVED", reservation.getTask().getStatus());
    }
}
