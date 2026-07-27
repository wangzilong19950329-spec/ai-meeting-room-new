package com.aimeeting.room.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StockWatchAnalysisSchedulerOwnershipService {

    private final JdbcTemplate jdbcTemplate;

    public boolean isLegacyOwner() {
        return "LEGACY".equals(snapshot().get("mode"));
    }

    public boolean isPolicyOwner() {
        return "POLICY".equals(snapshot().get("mode"));
    }

    public Map<String, Object> snapshot() {
        return jdbcTemplate.queryForObject(
                "SELECT mode,generation,row_version,last_error,gmt_modify "
                        + "FROM stock_watch_analysis_scheduler_ownership WHERE id=1",
                (rs, rowNum) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("mode", rs.getString("mode"));
                    result.put("generation", rs.getLong("generation"));
                    result.put("version", rs.getLong("row_version"));
                    result.put("lastError", rs.getString("last_error"));
                    result.put("modifiedAt", rs.getTimestamp("gmt_modify"));
                    return result;
                });
    }

    @Transactional
    public Map<String, Object> switchMode(String rawMode, Long expectedVersion) {
        String mode = rawMode == null ? "" : rawMode.trim().toUpperCase(Locale.ROOT);
        if (!"LEGACY".equals(mode) && !"POLICY".equals(mode)) {
            throw new IllegalArgumentException("mode只支持LEGACY或POLICY");
        }
        if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion不能为空");
        int updated = jdbcTemplate.update(
                "UPDATE stock_watch_analysis_scheduler_ownership "
                        + "SET mode=?, generation=generation+1, row_version=row_version+1, "
                        + "last_error=NULL, gmt_modify=CURRENT_TIMESTAMP "
                        + "WHERE id=1 AND row_version=?",
                mode, expectedVersion);
        if (updated != 1) throw new OwnershipConflictException("调度器所有权已变化，请刷新后重试");
        return snapshot();
    }

    public void recordError(String error) {
        jdbcTemplate.update("UPDATE stock_watch_analysis_scheduler_ownership "
                        + "SET last_error=?, gmt_modify=CURRENT_TIMESTAMP WHERE id=1",
                error == null ? "unknown_error" : error);
    }

    public static class OwnershipConflictException extends RuntimeException {
        public OwnershipConflictException(String message) {
            super(message);
        }
    }
}
