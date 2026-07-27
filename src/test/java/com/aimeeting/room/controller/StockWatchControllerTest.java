package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockQuoteService;
import com.aimeeting.room.service.StockWatchRuntimeStatusService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockWatchControllerTest {

    @Test
    void intradayBarsReturnsAuditableEnvelope() {
        StockQuoteService quoteService = mock(StockQuoteService.class);
        StockWatchRuntimeStatusService runtimeStatusService = mock(StockWatchRuntimeStatusService.class);
        Map<String, Object> envelope = Map.of(
                "requestedSymbol", "688017",
                "requestedMarket", "sh",
                "source", "Eastmoney trends2",
                "status", "VALID",
                "qualitySummary", Map.of("totalBars", 1, "validBars", 1, "invalidBars", 0),
                "bars", List.of(Map.of("startTime", "2026-07-24 09:31", "qualityStatus", "VALID"))
        );
        when(quoteService.fetchIntradayBars("688017", "sh")).thenReturn(envelope);
        StockWatchController controller = new StockWatchController(quoteService, runtimeStatusService);

        ApiResponse<Map<String, Object>> response = controller.getIntradayBars("688017", "sh");

        assertEquals(200, response.getCode());
        assertEquals("VALID", response.getData().get("status"));
        assertEquals("Eastmoney trends2", response.getData().get("source"));
        verify(quoteService).fetchIntradayBars("688017", "sh");
    }

    @Test
    void runtimeStatusPassesThroughWarnings() {
        StockQuoteService quoteService = mock(StockQuoteService.class);
        StockWatchRuntimeStatusService runtimeStatusService = mock(StockWatchRuntimeStatusService.class);
        Map<String, Object> status = Map.of(
                "status", "DEGRADED",
                "warnings", List.of("pending_jobs_unavailable")
        );
        when(runtimeStatusService.current("688017", "sh")).thenReturn(status);
        StockWatchController controller = new StockWatchController(quoteService, runtimeStatusService);

        ApiResponse<Map<String, Object>> response = controller.runtimeStatus("688017", "sh");

        assertEquals(200, response.getCode());
        assertEquals("DEGRADED", response.getData().get("status"));
        assertEquals(List.of("pending_jobs_unavailable"), response.getData().get("warnings"));
        verify(runtimeStatusService).current("688017", "sh");
    }
}
