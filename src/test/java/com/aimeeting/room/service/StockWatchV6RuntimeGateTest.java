package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockWatchV6RuntimeGateTest {

    @Test
    void runtimeModeFailsClosedWhenReadyStateIsMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), anyInt())).thenReturn(0);
        StockWatchV6RuntimeGate gate = new StockWatchV6RuntimeGate(jdbcTemplate, "runtime");

        assertThrows(IllegalStateException.class, gate::verifyAtStartup);
        assertThrows(IllegalStateException.class, gate::assertReady);
    }

    @Test
    void explicitTestModeIsTheOnlyNoDatabaseBypass() {
        StockWatchV6RuntimeGate gate = new StockWatchV6RuntimeGate(mock(JdbcTemplate.class), "disabled");

        assertDoesNotThrow(gate::verifyAtStartup);
        assertDoesNotThrow(gate::assertReady);
    }
}
