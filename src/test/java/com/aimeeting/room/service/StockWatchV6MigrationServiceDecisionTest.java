package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchV6MigrationServiceDecisionTest {

    private StockWatchV6MigrationService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v6decision;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE stock_watch_technical_analysis (id BIGINT PRIMARY KEY, analysis_task_id BIGINT,"
                + "status VARCHAR(32),raw_response CLOB,prompt_hash VARCHAR(64),symbol VARCHAR(16),market VARCHAR(8),"
                + "period_type VARCHAR(16),analysis_date DATE,run_slot VARCHAR(16))");
        jdbc.execute("CREATE TABLE stock_watch_prediction (analysis_id BIGINT,horizon_code VARCHAR(16))");
        service = new StockWatchV6MigrationService(jdbc, dataSource,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void uniqueFullyProjectedSuccessWinsWithoutRewritingPriorAttempt() {
        Map<String, Object> invalid = invalidTask(3L);
        Map<String, Object> success = successTask(4L);
        seedProjection();

        Object plan = ReflectionTestUtils.invokeMethod(service, "planIdentity", "fixture", Arrays.asList(invalid, success));

        assertEquals(4L, ReflectionTestUtils.getField(plan, "canonicalTaskId"));
        assertEquals("INVALID_RESPONSE", invalid.get("status"));
        assertEquals("PROJECTED_SUCCESS", success.get("status"));
    }

    @Test
    void corruptedHashAndMultipleProjectedSuccessFailClosed() {
        Map<String, Object> invalid = invalidTask(3L);
        Map<String, Object> corrupt = successTask(4L);
        corrupt.put("response_hash", "corrupted");
        seedProjection();

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "planIdentity", "fixture", Arrays.asList(invalid, corrupt)));

        Map<String, Object> success = successTask(4L);
        invalid.put("status", "PROJECTED_SUCCESS");
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "planIdentity", "fixture", Arrays.asList(invalid, success)));
    }

    @Test
    void ruleSubmissionIsNotClassifiedAsTechnicalMigrationInput() {
        Map<String, Object> rule = invalidTask(9L);
        rule.put("submission_key", "stock-analysis-v1:rule:sz:002979:9:now:deepseek-web");
        JSONObject request = new JSONObject(true);
        request.put("schema", "stock-analysis-v1");
        request.put("scope", "rule");
        rule.put("request_envelope_json", request.toJSONString());

        Boolean technical = ReflectionTestUtils.invokeMethod(service, "isTechnicalTask", rule);

        assertFalse(Boolean.TRUE.equals(technical));
    }

    @Test
    void bootstrapPlanStartsNewEpochWithoutInspectingLegacyTechnicalTasks() {
        JdbcTemplate jdbc = (JdbcTemplate) ReflectionTestUtils.getField(service, "jdbcTemplate");
        jdbc.update("INSERT INTO stock_watch_technical_analysis VALUES(771,NULL,'SUCCESS','legacy',NULL,"
                + "'002979','sz','DAY',?,'15:00')", Date.valueOf("2026-07-16"));

        Object plan = ReflectionTestUtils.invokeMethod(service, "bootstrapPlan");

        assertEquals(0L, ReflectionTestUtils.getField(plan, "technicalTaskCutoffId"));
        assertEquals(771L, ReflectionTestUtils.getField(plan, "legacyAnalysisCutoffId"));
        assertEquals(1L, ReflectionTestUtils.getField(plan, "legacyAnalysisCount"));
        assertTrue(((java.util.List<?>) ReflectionTestUtils.getField(plan, "groups")).isEmpty());
    }

    private Map<String, Object> invalidTask(long id) {
        Map<String, Object> task = baseTask(id, "stock-analysis-v4:sz:002979:DAY:2026-07-16:15:00");
        String raw = "{\"invalid\":true}";
        task.put("status", "INVALID_RESPONSE");
        task.put("raw_response", raw);
        task.put("response_hash", sha256(raw));
        task.put("submitted_at", new java.util.Date());
        return task;
    }

    private Map<String, Object> successTask(long id) {
        Map<String, Object> task = baseTask(id, "stock-analysis-v5:sz:002979:DAY:2026-07-16:15:00");
        String projectionJson = "{\"periodType\":\"DAY\"}";
        JSONObject request = new JSONObject(true);
        request.put("schema", "stock-analysis-v3");
        request.put("responseContractVersion", 5);
        request.put("projectionJson", projectionJson);
        request.put("projectionDigest", sha256(projectionJson));
        task.put("request_envelope_json", request.toJSONString());
        String raw = "{\"valid\":true}";
        task.put("status", "PROJECTED_SUCCESS");
        task.put("raw_response", raw);
        task.put("response_hash", sha256(raw));
        JSONObject response = new JSONObject(true);
        response.put("schema", "stock-analysis-response-v1");
        response.put("submissionKey", task.get("submission_key"));
        response.put("requestDigest", task.get("request_digest"));
        response.put("responseHash", task.get("response_hash"));
        task.put("response_envelope_json", response.toJSONString());
        task.put("response_envelope_hash", sha256(response.toJSONString()));
        task.put("submitted_at", new java.util.Date());
        return task;
    }

    private Map<String, Object> baseTask(long id, String submissionKey) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", id);
        task.put("submission_key", submissionKey);
        task.put("request_digest", "prompt-digest");
        task.put("request_envelope_json", "{\"schema\":\"stock-analysis-v2\"}");
        task.put("purpose", "stock-analysis-v" + id);
        task.put("symbol", "002979");
        task.put("market", "sz");
        task.put("period_type", "DAY");
        task.put("observation_key", "DAY:2026-07-16:15:00");
        return task;
    }

    private void seedProjection() {
        JdbcTemplate jdbc = (JdbcTemplate) ReflectionTestUtils.getField(service, "jdbcTemplate");
        jdbc.update("INSERT INTO stock_watch_technical_analysis VALUES(770,4,'SUCCESS',?,'prompt-digest',"
                + "'002979','sz','DAY',?,'15:00')", "{\"valid\":true}", Date.valueOf("2026-07-16"));
        jdbc.update("INSERT INTO stock_watch_prediction VALUES(770,'T1')");
        jdbc.update("INSERT INTO stock_watch_prediction VALUES(770,'T5')");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
