package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.entity.StockWatchTechnicalSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockWatchAnalysisProjectionServiceTest {

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
}
