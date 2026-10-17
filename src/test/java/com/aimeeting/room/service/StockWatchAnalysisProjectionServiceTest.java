package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.entity.StockWatchTechnicalSession;
import com.aimeeting.room.entity.StockWatchAnalysisTask;
import com.aimeeting.room.service.StockWatchAnalysisTaskService.Reservation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockWatchAnalysisProjectionServiceTest {

    @Test
    void lateSuccessUpdatesExistingErrorDiagnosticInPlace() {
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchPredictionService predictionService = mock(StockWatchPredictionService.class);
        StockWatchTechnicalSessionMapper sessionMapper = mock(StockWatchTechnicalSessionMapper.class);
        StockWatchAnalysisTaskService taskService = mock(StockWatchAnalysisTaskService.class);
        StockWatchAnalysisProjectionService service = new StockWatchAnalysisProjectionService(
                analysisMapper, predictionService, mock(StockWatchFactPersistenceService.class), sessionMapper,
                taskService, mock(StockWatchV6RuntimeGate.class));
        StockWatchAnalysisTask task = task(1499L, "RESPONSE_RECEIVED");
        task.setRawResponse("{\"forecasts\":[]}");
        Reservation reservation = Reservation.existing(task, "digest-1499");
        StockWatchTechnicalAnalysis existing = new StockWatchTechnicalAnalysis();
        existing.setId(701L);
        existing.setAnalysisTaskId(1499L);
        existing.setStatus("ERROR");
        StockWatchTechnicalAnalysis recovered = new StockWatchTechnicalAnalysis();
        recovered.setAnalysisTaskId(1499L);
        recovered.setStatus("SUCCESS");
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setId(17L);
        session.setContextChars(10);
        when(analysisMapper.selectByAnalysisTaskId(1499L)).thenReturn(existing);
        when(analysisMapper.updateRecovered(recovered)).thenReturn(1);
        when(sessionMapper.updateAfterRun(anyLong(), any(), any(), anyInt(), any(), anyString(), any()))
                .thenReturn(1);

        StockWatchTechnicalAnalysis result = service.project(reservation, null, Collections.emptyMap(), recovered,
                new com.alibaba.fastjson.JSONArray(), LocalDate.of(2026, 10, 16), 388.31, "{}",
                "direction-policy-v1", session, null, null);

        org.junit.jupiter.api.Assertions.assertEquals(701L, result.getId());
        verify(analysisMapper).updateRecovered(recovered);
        verify(analysisMapper, never()).insert(recovered);
        verify(taskService).markProjectedSuccess(reservation);
    }

    @Test
    void terminalReconcileTransitionsTaskEvenWhenDiagnosticAlreadyExists() {
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchAnalysisTaskService taskService = mock(StockWatchAnalysisTaskService.class);
        StockWatchAnalysisProjectionService service = new StockWatchAnalysisProjectionService(
                analysisMapper, mock(StockWatchPredictionService.class),
                mock(StockWatchFactPersistenceService.class), mock(StockWatchTechnicalSessionMapper.class),
                taskService, mock(StockWatchV6RuntimeGate.class));
        StockWatchAnalysisTask task = task(1500L, "UNKNOWN");
        Reservation reservation = Reservation.existing(task, "digest-1500");
        StockWatchTechnicalAnalysis existing = new StockWatchTechnicalAnalysis();
        existing.setId(702L);
        existing.setAnalysisTaskId(1500L);
        existing.setStatus("ERROR");
        when(analysisMapper.selectByAnalysisTaskId(1500L)).thenReturn(existing);
        when(analysisMapper.updateAbandonedDiagnostic(existing)).thenReturn(1);

        StockWatchTechnicalAnalysis result = service.projectAbandoned(
                reservation, existing, "FAILED", "provider_outcome_not_retriable:FAILED_PRE_SUBMIT");

        org.junit.jupiter.api.Assertions.assertEquals(702L, result.getId());
        verify(taskService).markAbandoned(reservation, "FAILED",
                "provider_outcome_not_retriable:FAILED_PRE_SUBMIT");
        verify(analysisMapper).updateAbandonedDiagnostic(existing);
        verify(analysisMapper, never()).insert(existing);
    }

    @Test
    void failureTelemetrySqlExceptionIsBestEffort() {
        StockWatchTechnicalSessionMapper sessionMapper = mock(StockWatchTechnicalSessionMapper.class);
        StockWatchAnalysisProjectionService service = new StockWatchAnalysisProjectionService(
                mock(StockWatchTechnicalAnalysisMapper.class), mock(StockWatchPredictionService.class),
                mock(StockWatchFactPersistenceService.class), sessionMapper,
                mock(StockWatchAnalysisTaskService.class), mock(StockWatchV6RuntimeGate.class));
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setId(17L);
        session.setContextChars(10);
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setId(71L);
        record.setContextCharsDelta(20);
        when(sessionMapper.updateAfterRun(anyLong(), any(), any(), anyInt(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("telemetry SQL unavailable"));

        assertDoesNotThrow(() -> service.updateFailureTelemetryBestEffort(
                session, record, null, null, "analysis failed"));
    }

    private StockWatchAnalysisTask task(long id, String status) {
        StockWatchAnalysisTask task = new StockWatchAnalysisTask();
        task.setId(id);
        task.setStatus(status);
        task.setRequestDigest("digest-" + id);
        return task;
    }
}
