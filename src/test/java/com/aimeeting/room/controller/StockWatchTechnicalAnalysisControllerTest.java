package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.AnalysisReuseOnlyMissException;
import com.aimeeting.room.service.StockWatchPredictionService;
import com.aimeeting.room.service.StockWatchTechnicalAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockWatchTechnicalAnalysisControllerTest {

    @Test
    void reuseOnlyMissReturnsStructuredApplicationConflict() {
        StockWatchTechnicalAnalysisService service = mock(StockWatchTechnicalAnalysisService.class);
        when(service.runManual(anyMap())).thenThrow(new AnalysisReuseOnlyMissException("DAY:2026-07-17:13:00"));
        StockWatchTechnicalAnalysisController controller = new StockWatchTechnicalAnalysisController(
                service, mock(StockWatchPredictionService.class));

        ApiResponse<Map<String, Object>> response = controller.run(Collections.singletonMap("reuseOnly", true));

        assertEquals(409, response.getCode());
        assertEquals("当前观察窗口没有可复用分析", response.getMsg());
        assertEquals("ANALYSIS_REUSE_ONLY_MISS", response.getData().get("errorCode"));
        assertEquals("DAY:2026-07-17:13:00", response.getData().get("observationKey"));
        assertFalse((Boolean) response.getData().get("retryable"));
    }
}
