package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.AnalysisReuseOnlyMissException;
import com.aimeeting.room.service.StockWatchAnalysisScheduler;
import com.aimeeting.room.service.StockWatchAnalysisSchedulerOwnershipService;
import com.aimeeting.room.service.StockWatchPredictionService;
import com.aimeeting.room.service.StockWatchTechnicalAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockWatchTechnicalAnalysisControllerTest {

    @Test
    void predictionHistoryCursorBindsThroughHttpAndRejectsIncompleteCursor() throws Exception {
        StockWatchPredictionService predictions = mock(StockWatchPredictionService.class);
        StockWatchTechnicalAnalysisController controller = new StockWatchTechnicalAnalysisController(
                mock(StockWatchTechnicalAnalysisService.class), predictions,
                mock(StockWatchAnalysisSchedulerOwnershipService.class),
                mock(StockWatchAnalysisScheduler.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(predictions.historyBefore("300750", "sz", LocalDate.of(2026, 7, 28), 123L, 200))
                .thenReturn(Map.of("records", Collections.singletonList(Map.of("id", 122L))));

        mvc.perform(get("/api/stock-watch/technical-analysis/predictions")
                        .param("symbol", "300750").param("market", "sz").param("limit", "200")
                        .param("beforeDate", "2026-07-28").param("beforeId", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(122));
        verify(predictions).historyBefore("300750", "sz", LocalDate.of(2026, 7, 28), 123L, 200);

        mvc.perform(get("/api/stock-watch/technical-analysis/predictions")
                        .param("symbol", "300750").param("market", "sz").param("beforeDate", "2026-07-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void reuseOnlyMissReturnsStructuredApplicationConflict() {
        StockWatchTechnicalAnalysisService service = mock(StockWatchTechnicalAnalysisService.class);
        when(service.runManual(anyMap())).thenThrow(new AnalysisReuseOnlyMissException("DAY:2026-07-17:13:00"));
        StockWatchAnalysisSchedulerOwnershipService ownershipService =
                mock(StockWatchAnalysisSchedulerOwnershipService.class);
        StockWatchTechnicalAnalysisController controller = new StockWatchTechnicalAnalysisController(
                service, mock(StockWatchPredictionService.class), ownershipService,
                mock(StockWatchAnalysisScheduler.class));

        ApiResponse<Map<String, Object>> response = controller.run(Collections.singletonMap("reuseOnly", true));

        assertEquals(409, response.getCode());
        assertEquals("当前观察窗口没有可复用分析", response.getMsg());
        assertEquals("ANALYSIS_REUSE_ONLY_MISS", response.getData().get("errorCode"));
        assertEquals("DAY:2026-07-17:13:00", response.getData().get("observationKey"));
        assertFalse((Boolean) response.getData().get("retryable"));
    }

    @Test
    void statusIncludesSchedulerOwnershipSnapshot() {
        StockWatchTechnicalAnalysisService service = mock(StockWatchTechnicalAnalysisService.class);
        when(service.status()).thenReturn(Map.of("enabled", true));
        StockWatchAnalysisSchedulerOwnershipService ownershipService =
                mock(StockWatchAnalysisSchedulerOwnershipService.class);
        when(ownershipService.snapshot()).thenReturn(Map.of("mode", "POLICY", "version", 3L));
        StockWatchTechnicalAnalysisController controller = new StockWatchTechnicalAnalysisController(
                service, mock(StockWatchPredictionService.class), ownershipService,
                mock(StockWatchAnalysisScheduler.class));

        ApiResponse<Map<String, Object>> response = controller.status();

        assertEquals(200, response.getCode());
        assertEquals(Map.of("mode", "POLICY", "version", 3L), response.getData().get("schedulerOwnership"));
    }

    @Test
    void policyOwnershipSwitchIsRejectedWhenLegacyBatchIsRunning() {
        StockWatchTechnicalAnalysisService service = mock(StockWatchTechnicalAnalysisService.class);
        when(service.isRunning()).thenReturn(true);
        StockWatchAnalysisSchedulerOwnershipService ownershipService =
                mock(StockWatchAnalysisSchedulerOwnershipService.class);
        StockWatchTechnicalAnalysisController controller = new StockWatchTechnicalAnalysisController(
                service, mock(StockWatchPredictionService.class), ownershipService,
                mock(StockWatchAnalysisScheduler.class));

        ApiResponse<Map<String, Object>> response = controller.switchSchedulerOwnership(
                Map.of("mode", "POLICY", "expectedVersion", 3));

        assertEquals(409, response.getCode());
        verify(ownershipService, never()).switchMode(anyString(), any());
    }
}
