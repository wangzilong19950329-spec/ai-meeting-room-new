package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockWatchRuntimeStatusServiceTest {

    @Test
    void databaseErrorsDegradeRuntimeStatusInsteadOfReportingZeroJobs() {
        StockWatchTechnicalAnalysisService technicalAnalysisService = mock(StockWatchTechnicalAnalysisService.class);
        when(technicalAnalysisService.status()).thenReturn(Map.of(
                "enabled", true,
                "running", false,
                "lastRunStatus", "idle"
        ));
        StockWatchAnalysisSchedulerOwnershipService ownershipService =
                mock(StockWatchAnalysisSchedulerOwnershipService.class);
        when(ownershipService.snapshot()).thenReturn(Map.of("mode", "POLICY"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:runtime-status-empty;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));

        StockWatchRuntimeStatusService service = new StockWatchRuntimeStatusService(
                technicalAnalysisService, ownershipService, jdbcTemplate);
        ReflectionTestUtils.setField(service, "bridgeUrl", "http://127.0.0.1:8791/search");

        Map<String, Object> status = service.current("688017", "sh");

        assertEquals("DEGRADED", status.get("status"));
        assertNull(status.get("pendingJobs"));
        assertNull(status.get("retryJobs"));
        Map<?, ?> retryPolicy = (Map<?, ?>) status.get("retryPolicy");
        assertEquals("UNKNOWN", retryPolicy.get("status"));
        List<?> warnings = (List<?>) status.get("warnings");
        assertTrue(warnings.contains("pending_jobs_unavailable"));
        assertTrue(warnings.contains("delivery_retry_policy_unavailable"));
    }
}
