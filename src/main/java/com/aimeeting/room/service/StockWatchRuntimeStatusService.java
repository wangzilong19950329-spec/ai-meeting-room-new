package com.aimeeting.room.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StockWatchRuntimeStatusService {

    private static final int DELIVERY_MAX_ATTEMPTS = 3;

    private final StockWatchTechnicalAnalysisService technicalAnalysisService;
    private final StockWatchAnalysisSchedulerOwnershipService ownershipService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${stock-watch.technical-analysis.bridge-url:http://127.0.0.1:8791/search}")
    private String bridgeUrl;

    public Map<String, Object> current(String symbol, String market) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> technical = safeTechnicalStatus(warnings);
        Map<String, Object> ownership = safeOwnership(warnings);
        String normalizedSymbol = text(symbol);
        String normalizedMarket = text(market).toLowerCase();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schedulingEnabled", bool(technical.get("enabled")));
        result.put("analysisRunning", bool(technical.get("running")));
        result.put("analysisMode", text(ownership.get("mode"), "UNKNOWN"));
        result.put("provider", "DeepSeek 网页");
        result.put("providerVersionState", StringUtils.hasText(bridgeUrl) ? "CONFIGURED" : "UNCONFIGURED");
        result.put("lastRunAt", technical.get("lastRunAt"));
        result.put("lastRunStatus", text(technical.get("lastRunStatus"), "unknown"));
        result.put("nextDueAt", nextDueAt(normalizedSymbol, normalizedMarket, warnings));
        result.put("pendingJobs", pendingJobs(normalizedSymbol, normalizedMarket, warnings));
        result.put("retryJobs", retryJobs(normalizedSymbol, normalizedMarket, warnings));
        result.put("retryPolicy", retryPolicy(normalizedSymbol, normalizedMarket, warnings));
        result.put("schedulerOwnership", ownership);
        result.put("warnings", warnings);
        result.put("status", warnings.isEmpty() ? "VALID" : "DEGRADED");
        return result;
    }

    private Map<String, Object> retryPolicy(String symbol, String market, List<String> warnings) {
        Map<String, Object> retry = retryDeliveryPolicy(symbol, market, warnings);
        if (retry != null) return retry;
        retry = retryAnalysisDemandPolicy(symbol, market, warnings);
        if (retry != null) return retry;
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("maxAttempts", DELIVERY_MAX_ATTEMPTS);
        empty.put("currentAttempt", 0);
        empty.put("backoffStrategy", "NONE");
        empty.put("nextRetryAt", null);
        empty.put("retryable", false);
        empty.put("lastErrorCode", null);
        empty.put("status", "VALID");
        return empty;
    }

    private Map<String, Object> retryDeliveryPolicy(String symbol, String market, List<String> warnings) {
        String filter = stockFilter(symbol, market, "WHERE");
        String sql = "SELECT attempt_count,next_attempt_at,error_code FROM ("
                + "SELECT initial_attempt_count AS attempt_count, initial_next_attempt_at AS next_attempt_at, initial_error AS error_code, symbol, market "
                + "FROM stock_watch_alert_delivery_obligation WHERE initial_status='RETRY' "
                + "UNION ALL "
                + "SELECT supplement_attempt_count AS attempt_count, supplement_next_attempt_at AS next_attempt_at, supplement_error AS error_code, symbol, market "
                + "FROM stock_watch_alert_delivery_obligation WHERE supplement_status='RETRY'"
                + ") retry_rows " + filter + " ORDER BY next_attempt_at ASC LIMIT 1";
        List<Map<String, Object>> rows = safeRows(sql, params(symbol, market), warnings, "delivery_retry_policy_unavailable");
        if (rows == null) return unknownRetryPolicy("delivery_retry_policy_unavailable");
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("maxAttempts", DELIVERY_MAX_ATTEMPTS);
        policy.put("currentAttempt", integer(row.get("attempt_count")));
        policy.put("backoffStrategy", "EXPONENTIAL_1M_2M_4M_MAX_30M");
        policy.put("nextRetryAt", instant(row.get("next_attempt_at")));
        policy.put("retryable", true);
        policy.put("lastErrorCode", text(row.get("error_code"), null));
        policy.put("status", "VALID");
        return policy;
    }

    private Map<String, Object> retryAnalysisDemandPolicy(String symbol, String market, List<String> warnings) {
        String filter = stockFilter(symbol, market, "AND");
        String sql = "SELECT provider_submit_count,not_before,deadline_at,error_message FROM stock_watch_analysis_demand "
                + "WHERE status='PENDING' AND error_message LIKE 'unified_analysis_submit_retry:%' "
                + filter + " ORDER BY not_before ASC LIMIT 1";
        List<Map<String, Object>> rows = safeRows(sql, params(symbol, market), warnings, "analysis_retry_policy_unavailable");
        if (rows == null) return unknownRetryPolicy("analysis_retry_policy_unavailable");
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("maxAttempts", null);
        policy.put("currentAttempt", integer(row.get("provider_submit_count")));
        policy.put("backoffStrategy", "RETRY_UNTIL_DEADLINE_BY_NOT_BEFORE");
        policy.put("nextRetryAt", instant(row.get("not_before")));
        policy.put("deadlineAt", instant(row.get("deadline_at")));
        policy.put("retryable", true);
        policy.put("lastErrorCode", text(row.get("error_message"), null));
        policy.put("status", "VALID");
        return policy;
    }

    private Map<String, Object> unknownRetryPolicy(String reason) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("maxAttempts", null);
        policy.put("currentAttempt", null);
        policy.put("backoffStrategy", "UNKNOWN");
        policy.put("nextRetryAt", null);
        policy.put("retryable", null);
        policy.put("lastErrorCode", reason);
        policy.put("status", "UNKNOWN");
        return policy;
    }

    private Integer pendingJobs(String symbol, String market, List<String> warnings) {
        String filter = stockFilter(symbol, market, "AND");
        return safeCount("SELECT COUNT(*) FROM stock_watch_analysis_demand "
                + "WHERE status IN ('PENDING','CLAIMED','SUBMITTED') " + filter, params(symbol, market),
                warnings, "pending_jobs_unavailable");
    }

    private Integer retryJobs(String symbol, String market, List<String> warnings) {
        String demandFilter = stockFilter(symbol, market, "AND");
        String deliveryFilter = stockFilter(symbol, market, "AND");
        Integer demandRetryJobs = safeCount("SELECT COUNT(*) FROM stock_watch_analysis_demand "
                        + "WHERE status='PENDING' AND error_message LIKE 'unified_analysis_submit_retry:%' " + demandFilter,
                params(symbol, market), warnings, "analysis_retry_jobs_unavailable");
        Integer deliveryRetryJobs = safeCount("SELECT COUNT(*) FROM stock_watch_alert_delivery_obligation "
                        + "WHERE (initial_status='RETRY' OR supplement_status='RETRY') " + deliveryFilter,
                params(symbol, market), warnings, "delivery_retry_jobs_unavailable");
        if (demandRetryJobs == null || deliveryRetryJobs == null) {
            return null;
        }
        return demandRetryJobs + deliveryRetryJobs;
    }

    private String nextDueAt(String symbol, String market, List<String> warnings) {
        String filter = stockFilter(symbol, market, "AND");
        List<Map<String, Object>> rows = safeRows("SELECT MIN(not_before) AS next_due_at FROM stock_watch_analysis_demand "
                + "WHERE status='PENDING' " + filter, params(symbol, market), warnings, "next_due_at_unavailable");
        if (rows == null || rows.isEmpty()) return null;
        return instant(rows.get(0).get("next_due_at"));
    }

    private Map<String, Object> safeTechnicalStatus(List<String> warnings) {
        try {
            return technicalAnalysisService.status();
        } catch (Exception ignored) {
            warnings.add("technical_status_unavailable");
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> safeOwnership(List<String> warnings) {
        try {
            return ownershipService.snapshot();
        } catch (Exception ignored) {
            warnings.add("scheduler_ownership_unavailable");
            return new LinkedHashMap<>();
        }
    }

    private Integer safeCount(String sql, Object[] params, List<String> warnings, String warningCode) {
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, params);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            warnings.add(warningCode);
            return null;
        }
    }

    private List<Map<String, Object>> safeRows(String sql, Object[] params, List<String> warnings, String warningCode) {
        try {
            return jdbcTemplate.queryForList(sql, params);
        } catch (Exception ignored) {
            warnings.add(warningCode);
            return null;
        }
    }

    private String stockFilter(String symbol, String market, String prefix) {
        if (!StringUtils.hasText(symbol) || !StringUtils.hasText(market)) {
            return "";
        }
        return " " + prefix + " symbol=? AND market=?";
    }

    private Object[] params(String symbol, String market) {
        if (!StringUtils.hasText(symbol) || !StringUtils.hasText(market)) {
            return new Object[0];
        }
        return new Object[]{symbol, market};
    }

    private boolean bool(Object raw) {
        return raw instanceof Boolean && (Boolean) raw;
    }

    private int integer(Object raw) {
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return raw == null ? 0 : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String instant(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Timestamp) return ((Timestamp) raw).toInstant().toString();
        if (raw instanceof java.util.Date) return ((java.util.Date) raw).toInstant().toString();
        if (raw instanceof Instant) return raw.toString();
        return String.valueOf(raw);
    }

    private String text(Object raw) {
        return text(raw, "");
    }

    private String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }
}
