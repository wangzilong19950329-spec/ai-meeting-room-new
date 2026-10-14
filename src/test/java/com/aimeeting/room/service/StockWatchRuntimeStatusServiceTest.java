package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
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

    @Test
    void pendingJobBreakdownSeparatesDispatchableFromExpiredAndSubmitted() {
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
                "jdbc:h2:mem:runtime-status-breakdown;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbcTemplate.execute("CREATE TABLE stock_watch_analysis_demand ("
                + "symbol VARCHAR(16), market VARCHAR(8), status VARCHAR(32), "
                + "not_before TIMESTAMP, deadline_at TIMESTAMP, lease_until TIMESTAMP, "
                + "error_message VARCHAR(255), provider_submit_count INT)");
        jdbcTemplate.execute("CREATE TABLE stock_watch_alert_delivery_obligation ("
                + "symbol VARCHAR(16), market VARCHAR(8), initial_status VARCHAR(32), supplement_status VARCHAR(32), "
                + "initial_attempt_count INT, supplement_attempt_count INT, "
                + "initial_next_attempt_at TIMESTAMP, supplement_next_attempt_at TIMESTAMP, "
                + "initial_error VARCHAR(255), supplement_error VARCHAR(255))");
        Instant now = Instant.now();
        insertDemand(jdbcTemplate, "PENDING", now.minusSeconds(60), now.plusSeconds(3600), null);
        insertDemand(jdbcTemplate, "PENDING", now.plusSeconds(600), now.plusSeconds(3600), null);
        insertDemand(jdbcTemplate, "PENDING", now.minusSeconds(3600), now.minusSeconds(60), null);
        insertDemand(jdbcTemplate, "CLAIMED", now.minusSeconds(60), now.plusSeconds(3600), now.plusSeconds(600));
        insertDemand(jdbcTemplate, "SUBMITTED", now.minusSeconds(60), now.plusSeconds(3600), null);

        StockWatchRuntimeStatusService service = new StockWatchRuntimeStatusService(
                technicalAnalysisService, ownershipService, jdbcTemplate);
        ReflectionTestUtils.setField(service, "bridgeUrl", "http://127.0.0.1:8791/search");

        Map<String, Object> status = service.current("002979", "sz");

        assertEquals("VALID", status.get("status"));
        assertEquals(5, status.get("pendingJobs"));
        assertEquals(1, status.get("dispatchablePendingJobs"));
        assertEquals(1, status.get("expiredPendingJobs"));
        Map<?, ?> breakdown = (Map<?, ?>) status.get("pendingJobBreakdown");
        assertEquals(1, breakdown.get("scheduledPending"));
        assertEquals(1, breakdown.get("claimedLeased"));
        assertEquals(1, breakdown.get("submitted"));
    }

    private void insertDemand(JdbcTemplate jdbcTemplate,
                              String status,
                              Instant notBefore,
                              Instant deadlineAt,
                              Instant leaseUntil) {
        jdbcTemplate.update("INSERT INTO stock_watch_analysis_demand "
                        + "(symbol,market,status,not_before,deadline_at,lease_until,error_message,provider_submit_count) "
                        + "VALUES (?,?,?,?,?,?,NULL,0)",
                "002979", "sz", status, Timestamp.from(notBefore), Timestamp.from(deadlineAt),
                leaseUntil == null ? null : Timestamp.from(leaseUntil));
    }
}
