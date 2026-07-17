package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockWatchV6MigrationService {

    private static final String SCOPE = StockWatchAnalysisTaskService.TECHNICAL_RUNTIME_SCOPE;
    private static final String LOCK_NAME = "stock-watch-v6-migration";
    private static final Pattern TECHNICAL_SUBMISSION = Pattern.compile("^stock-analysis-v[0-9]+:.*");
    private static final Set<String> PROVIDER_STATES = new LinkedHashSet<>(Arrays.asList(
            "SUBMITTED", "RESPONSE_RECEIVED", "PROJECTION_FAILED", "SUCCESS", "PROJECTED_SUCCESS",
            "INVALID_RESPONSE", "PROJECTION_INVALID", "UNKNOWN"
    ));

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;

    public void ensureSchema() {
        Integer triggerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() "
                        + "AND trigger_name LIKE 'trg_sw_analysis_task_generation_%'", Integer.class);
        if (triggerCount != null && triggerCount > 0) {
            throw new IllegalStateException("stock_watch_v6_legacy_trigger_present:remove_before_schema_step");
        }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_analysis_observation_identity ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,identity_key VARCHAR(64) NOT NULL,"
                + "analysis_scope VARCHAR(40) NOT NULL,symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,"
                + "period_type VARCHAR(16) NOT NULL,observation_key VARCHAR(160) NOT NULL,canonical_task_id BIGINT,"
                + "migration_state VARCHAR(40) NOT NULL DEFAULT 'PENDING',row_version BIGINT NOT NULL DEFAULT 0,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,PRIMARY KEY(id),"
                + "UNIQUE KEY uk_sw_analysis_identity_key(identity_key),"
                + "UNIQUE KEY uk_sw_analysis_identity_observation(analysis_scope,market,symbol,period_type,observation_key),"
                + "UNIQUE KEY uk_sw_analysis_identity_task(canonical_task_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        upgradeEmptyIdentitySchema();
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_analysis_identity_candidate ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,identity_id BIGINT NOT NULL,task_id BIGINT NOT NULL,"
                + "disposition VARCHAR(48) NOT NULL,reason VARCHAR(255) NOT NULL,"
                + "source_fingerprint VARCHAR(64) NOT NULL,verified_at DATETIME NOT NULL,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(id),"
                + "UNIQUE KEY uk_sw_identity_candidate(identity_id,task_id),"
                + "KEY idx_sw_candidate_task(task_id),KEY idx_sw_candidate_disposition(disposition)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("stock_watch_analysis_identity_candidate", "source_fingerprint",
                "VARCHAR(64) NOT NULL DEFAULT ''");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_analysis_migration_state ("
                + "migration_key VARCHAR(64) NOT NULL,generation INT NOT NULL,status VARCHAR(24) NOT NULL,"
                + "technical_task_cutoff_id BIGINT NOT NULL DEFAULT 0,legacy_analysis_cutoff_id BIGINT NOT NULL DEFAULT 0,"
                + "legacy_analysis_count BIGINT NOT NULL DEFAULT 0,plan_hash VARCHAR(64) NOT NULL DEFAULT '',"
                + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY(migration_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("stock_watch_analysis_migration_state", "technical_task_cutoff_id",
                "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing("stock_watch_analysis_migration_state", "legacy_analysis_cutoff_id",
                "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing("stock_watch_analysis_migration_state", "legacy_analysis_count",
                "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing("stock_watch_analysis_migration_state", "plan_hash",
                "VARCHAR(64) NOT NULL DEFAULT ''");
        log.info("stock-watch v6 schema ready");
    }

    public MigrationPlan preview() {
        return withMigrationLock(this::preflight);
    }

    public MigrationPlan migrate() {
        return withMigrationLock(() -> {
            MigrationPlan plan = preflight();
            MigrationState state = readState();
            if (state != null && state.ready()) {
                validateReadyState(plan, state);
                log.info("stock-watch v6 migration no-op status=READY planHash={}", plan.planHash);
                return plan;
            }
            transactionTemplate.executeWithoutResult(status -> applyPlan(plan));
            MigrationState ready = readState();
            validateReadyState(preflight(), ready);
            log.info("stock-watch v6 migration committed identities={} candidates={} legacyArchiveCount={} planHash={}",
                    plan.groups.size(), plan.candidateCount(), plan.legacyAnalysisCount, plan.planHash);
            return plan;
        });
    }

    private MigrationPlan preflight() {
        assertSchemaReady();
        MigrationState state = readState();
        long taskCutoff = state != null && state.ready()
                ? state.technicalTaskCutoffId : maxTechnicalTaskId();
        List<Map<String, Object>> tasks = technicalTasksAtOrBefore(taskCutoff);
        Map<String, List<Map<String, Object>>> grouped = tasks.stream().collect(Collectors.groupingBy(
                this::taskIdentityKey, LinkedHashMap::new, Collectors.toList()));
        List<IdentityPlan> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            groups.add(planIdentity(entry.getKey(), entry.getValue()));
        }
        groups.sort(Comparator.comparing(plan -> plan.identityKey));

        long legacyCutoff = state != null && state.ready()
                ? state.legacyAnalysisCutoffId : maxTasklessAnalysisId();
        long legacyCount = countTasklessAtOrBefore(legacyCutoff);
        if (state != null && state.ready()) {
            Long postReadyTaskless = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_watch_technical_analysis "
                            + "WHERE analysis_task_id IS NULL AND id>?", Long.class, legacyCutoff);
            if (postReadyTaskless != null && postReadyTaskless > 0) {
                throw new IllegalStateException("stock_watch_v6_post_ready_taskless_analysis:" + postReadyTaskless);
            }
        }
        MigrationPlan plan = new MigrationPlan(taskCutoff, legacyCutoff, legacyCount, groups);
        log.info("stock-watch v6 migration preview technicalTasks={} identities={} candidates={} "
                        + "legacyArchiveCount={} legacyCutoff={} planHash={}",
                tasks.size(), groups.size(), plan.candidateCount(), legacyCount, legacyCutoff, plan.planHash);
        return plan;
    }

    private IdentityPlan planIdentity(String groupKey, List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) throw new IllegalStateException("empty_analysis_task_group");
        for (Map<String, Object> candidate : candidates) verifyRequestShape(candidate);
        List<Map<String, Object>> touched = candidates.stream().filter(this::providerTouched).collect(Collectors.toList());
        Map<String, Object> winner;
        if (touched.isEmpty()) {
            winner = candidates.stream().sorted(Comparator.comparingInt(this::contractVersion).reversed()
                    .thenComparing(Comparator.comparingLong(this::taskId).reversed())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("empty_analysis_task_group"));
        } else if (touched.size() == 1) {
            winner = touched.get(0);
            verifyProviderTask(winner);
        } else {
            for (Map<String, Object> task : touched) verifyProviderTask(task);
            List<Map<String, Object>> projected = touched.stream()
                    .filter(task -> "PROJECTED_SUCCESS".equalsIgnoreCase(text(task.get("status"))))
                    .collect(Collectors.toList());
            if (projected.size() != 1) throw ambiguous(groupKey, touched);
            winner = projected.get(0);
            verifyProjectedSuccess(winner);
        }
        if ("PROJECTED_SUCCESS".equalsIgnoreCase(text(winner.get("status")))) verifyProjectedSuccess(winner);

        String scope = SCOPE;
        String symbol = text(winner.get("symbol")).toLowerCase(Locale.ROOT);
        String market = text(winner.get("market")).toUpperCase(Locale.ROOT);
        String period = firstNonBlank(text(winner.get("period_type")), "DAY").toUpperCase(Locale.ROOT);
        String observation = text(winner.get("observation_key"));
        String identityKey = identityKey(scope, symbol, market, period, observation);
        List<CandidatePlan> planned = new ArrayList<>();
        for (Map<String, Object> task : candidates) {
            boolean canonical = taskId(task) == taskId(winner);
            String disposition = canonical ? "CANONICAL"
                    : (providerTouched(task) ? "NONCANONICAL_PREVIOUS_ATTEMPT" : "NONCANONICAL_PRE_PROVIDER");
            String reason = canonical ? "validated_canonical_task"
                    : (providerTouched(task) ? "superseded_provider_attempt" : "superseded_before_provider");
            planned.add(new CandidatePlan(taskId(task), disposition, reason, taskFingerprint(task)));
        }
        planned.sort(Comparator.comparingLong(candidate -> candidate.taskId));
        return new IdentityPlan(identityKey, scope, symbol, market, period, observation,
                taskId(winner), planned);
    }

    private void applyPlan(MigrationPlan plan) {
        Date verifiedAt = new Date();
        for (IdentityPlan identity : plan.groups) {
            jdbcTemplate.update("INSERT INTO stock_watch_analysis_observation_identity(identity_key,analysis_scope,"
                            + "symbol,market,period_type,observation_key,canonical_task_id,migration_state,row_version) "
                            + "VALUES(?,?,?,?,?,?,?,?,0) ON DUPLICATE KEY UPDATE "
                            + "canonical_task_id=IF(canonical_task_id IS NULL,VALUES(canonical_task_id),canonical_task_id),"
                            + "migration_state='MIGRATED_CANONICAL'",
                    identity.identityKey, identity.scope, identity.symbol, identity.market, identity.period,
                    identity.observation, identity.canonicalTaskId, "MIGRATED_CANONICAL");
            Long identityId = jdbcTemplate.queryForObject(
                    "SELECT id FROM stock_watch_analysis_observation_identity WHERE identity_key=?",
                    Long.class, identity.identityKey);
            Long canonicalTaskId = jdbcTemplate.queryForObject(
                    "SELECT canonical_task_id FROM stock_watch_analysis_observation_identity WHERE identity_key=?",
                    Long.class, identity.identityKey);
            if (identityId == null || canonicalTaskId == null || canonicalTaskId != identity.canonicalTaskId) {
                throw new IllegalStateException("analysis_identity_binding_conflict:" + identity.identityKey);
            }
            for (CandidatePlan candidate : identity.candidates) {
                jdbcTemplate.update("INSERT INTO stock_watch_analysis_identity_candidate(identity_id,task_id,"
                                + "disposition,reason,source_fingerprint,verified_at) VALUES(?,?,?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE disposition=VALUES(disposition),reason=VALUES(reason),"
                                + "source_fingerprint=VALUES(source_fingerprint),verified_at=VALUES(verified_at)",
                        identityId, candidate.taskId, candidate.disposition, candidate.reason,
                        candidate.sourceFingerprint, verifiedAt);
            }
        }
        jdbcTemplate.update("INSERT INTO stock_watch_analysis_migration_state(migration_key,generation,status,"
                        + "technical_task_cutoff_id,legacy_analysis_cutoff_id,legacy_analysis_count,plan_hash) "
                        + "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE generation=VALUES(generation),"
                        + "status=VALUES(status),technical_task_cutoff_id=VALUES(technical_task_cutoff_id),"
                        + "legacy_analysis_cutoff_id=VALUES(legacy_analysis_cutoff_id),"
                        + "legacy_analysis_count=VALUES(legacy_analysis_count),plan_hash=VALUES(plan_hash)",
                StockWatchV6RuntimeGate.MIGRATION_KEY, StockWatchV6RuntimeGate.GENERATION, "READY",
                plan.technicalTaskCutoffId, plan.legacyAnalysisCutoffId, plan.legacyAnalysisCount, plan.planHash);
    }

    private void validateReadyState(MigrationPlan plan, MigrationState state) {
        if (state == null || !state.ready()) throw new IllegalStateException("stock_watch_v6_ready_state_missing");
        if (state.technicalTaskCutoffId != plan.technicalTaskCutoffId
                || state.legacyAnalysisCutoffId != plan.legacyAnalysisCutoffId
                || state.legacyAnalysisCount != plan.legacyAnalysisCount
                || !state.planHash.equals(plan.planHash)) {
            throw new IllegalStateException("stock_watch_v6_ready_state_drift");
        }
        Long identities = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_watch_analysis_observation_identity WHERE analysis_scope=?",
                Long.class, SCOPE);
        Long candidates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_watch_analysis_identity_candidate c "
                        + "JOIN stock_watch_analysis_observation_identity i ON i.id=c.identity_id "
                        + "WHERE i.analysis_scope=?", Long.class, SCOPE);
        if (identities == null || identities != plan.groups.size()
                || candidates == null || candidates != plan.candidateCount()) {
            throw new IllegalStateException("stock_watch_v6_ready_projection_count_mismatch");
        }
        for (IdentityPlan identity : plan.groups) {
            for (CandidatePlan candidate : identity.candidates) {
                Long matching = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_watch_analysis_identity_candidate c "
                                + "JOIN stock_watch_analysis_observation_identity i ON i.id=c.identity_id "
                                + "WHERE i.identity_key=? AND c.task_id=? AND c.disposition=? AND c.reason=? "
                                + "AND c.source_fingerprint=?",
                        Long.class, identity.identityKey, candidate.taskId, candidate.disposition,
                        candidate.reason, candidate.sourceFingerprint);
                if (matching == null || matching != 1L) {
                    throw new IllegalStateException("stock_watch_v6_ready_candidate_drift:"
                            + identity.identityKey + ":" + candidate.taskId);
                }
            }
        }
    }

    private void verifyRequestShape(Map<String, Object> task) {
        if (!StringUtils.hasText(text(task.get("request_digest")))) throw invalid(task, "request_digest_missing");
        JSONObject envelope = parseObject(text(task.get("request_envelope_json")), "request_envelope_invalid", task);
        if ("rule".equalsIgnoreCase(envelope.getString("scope"))) throw invalid(task, "rule_scope_in_technical_group");
        String submissionKey = text(task.get("submission_key"));
        if (!TECHNICAL_SUBMISSION.matcher(submissionKey).matches() || submissionKey.contains(":rule:")) {
            throw invalid(task, "technical_submission_key_invalid");
        }
    }

    private void verifyProviderTask(Map<String, Object> task) {
        verifyRequestShape(task);
        String status = text(task.get("status")).toUpperCase(Locale.ROOT);
        if ("SUBMITTED".equals(status)) {
            throw invalid(task, "submitted_requires_bridge_ledger_resolution");
        }
        String raw = text(task.get("raw_response"));
        String responseHash = text(task.get("response_hash"));
        if (("UNKNOWN".equals(status) || "FAILED".equals(status)) && !StringUtils.hasText(raw)) {
            if (task.get("submitted_at") == null || !StringUtils.hasText(text(task.get("error_message")))) {
                throw invalid(task, "terminal_unknown_field_shape_invalid");
            }
            return;
        }
        if (!StringUtils.hasText(raw) || !sha256(raw).equals(responseHash)) throw invalid(task, "response_hash_invalid");
        String envelopeJson = text(task.get("response_envelope_json"));
        String envelopeHash = text(task.get("response_envelope_hash"));
        if (!StringUtils.hasText(envelopeJson) || !sha256(envelopeJson).equals(envelopeHash)) {
            if ("INVALID_RESPONSE".equals(status) && contractVersion(task) < 5) return;
            throw invalid(task, "response_envelope_hash_invalid");
        }
        JSONObject envelope = parseObject(envelopeJson, "response_envelope_invalid", task);
        if (!text(task.get("submission_key")).equals(envelope.getString("submissionKey"))
                || !text(task.get("request_digest")).equals(envelope.getString("requestDigest"))
                || !responseHash.equals(envelope.getString("responseHash"))) {
            throw invalid(task, "response_envelope_fields_invalid");
        }
    }

    private void verifyProjectedSuccess(Map<String, Object> task) {
        verifyProviderTask(task);
        List<Map<String, Object>> analyses = jdbcTemplate.queryForList(
                "SELECT * FROM stock_watch_technical_analysis WHERE analysis_task_id=? AND status='SUCCESS'",
                taskId(task));
        if (analyses.size() != 1) throw invalid(task, "projected_success_analysis_count:" + analyses.size());
        Map<String, Object> analysis = analyses.get(0);
        if (!text(task.get("raw_response")).equals(text(analysis.get("raw_response")))) {
            throw invalid(task, "analysis_raw_response_mismatch");
        }
        if (!text(task.get("request_digest")).equals(text(analysis.get("prompt_hash")))) {
            throw invalid(task, "analysis_prompt_hash_mismatch");
        }
        String period = firstNonBlank(text(task.get("period_type")), "DAY").toUpperCase(Locale.ROOT);
        String expectedObservation = period + ":" + text(analysis.get("analysis_date")) + ":"
                + text(analysis.get("run_slot"));
        if (!text(task.get("symbol")).equalsIgnoreCase(text(analysis.get("symbol")))
                || !text(task.get("market")).equalsIgnoreCase(text(analysis.get("market")))
                || !period.equalsIgnoreCase(firstNonBlank(text(analysis.get("period_type")), "DAY"))
                || !text(task.get("observation_key")).equals(expectedObservation)) {
            throw invalid(task, "analysis_identity_mismatch");
        }
        JSONObject request = parseObject(text(task.get("request_envelope_json")), "request_envelope_invalid", task);
        String projectionJson = request.getString("projectionJson");
        String projectionDigest = request.getString("projectionDigest");
        if (!StringUtils.hasText(projectionJson) || !sha256(projectionJson).equals(projectionDigest)) {
            throw invalid(task, "projection_digest_invalid");
        }
        if (contractVersion(task) >= 5) {
            Long analysisId = longValue(analysis.get("id"));
            List<String> horizons = jdbcTemplate.queryForList(
                    "SELECT horizon_code FROM stock_watch_prediction WHERE analysis_id=? ORDER BY horizon_code",
                    String.class, analysisId);
            Set<String> actual = new LinkedHashSet<>(horizons);
            Set<String> expected = requiredHorizons(period);
            if (!actual.equals(expected)) throw invalid(task, "required_predictions_invalid:" + actual);
        }
    }

    private Set<String> requiredHorizons(String period) {
        if ("WEEK".equals(period)) return Collections.singleton("W1");
        if ("MONTH".equals(period)) return Collections.singleton("M1");
        return new LinkedHashSet<>(Arrays.asList("T1", "T5"));
    }

    private boolean providerTouched(Map<String, Object> task) {
        return task.get("submitted_at") != null
                || PROVIDER_STATES.contains(text(task.get("status")).toUpperCase(Locale.ROOT));
    }

    private List<Map<String, Object>> technicalTasksAtOrBefore(long cutoff) {
        if (cutoff <= 0) return Collections.emptyList();
        List<Map<String, Object>> all = jdbcTemplate.queryForList(
                "SELECT * FROM stock_watch_analysis_task WHERE id<=? ORDER BY id", cutoff);
        return all.stream().filter(this::isTechnicalTask).collect(Collectors.toList());
    }

    private boolean isTechnicalTask(Map<String, Object> task) {
        String key = text(task.get("submission_key"));
        if (!TECHNICAL_SUBMISSION.matcher(key).matches() || key.contains(":rule:")) return false;
        try {
            JSONObject request = JSON.parseObject(text(task.get("request_envelope_json")));
            return request == null || !"rule".equalsIgnoreCase(request.getString("scope"));
        } catch (Exception invalid) {
            return true;
        }
    }

    private long maxTechnicalTaskId() {
        Long max = 0L;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,submission_key,request_envelope_json FROM stock_watch_analysis_task ORDER BY id");
        for (Map<String, Object> row : rows) if (isTechnicalTask(row)) max = Math.max(max, taskId(row));
        return max;
    }

    private long maxTasklessAnalysisId() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id),0) FROM stock_watch_technical_analysis WHERE analysis_task_id IS NULL",
                Long.class);
        return value == null ? 0L : value;
    }

    private long countTasklessAtOrBefore(long cutoff) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_watch_technical_analysis WHERE analysis_task_id IS NULL AND id<=?",
                Long.class, cutoff);
        return value == null ? 0L : value;
    }

    private void assertSchemaReady() {
        for (String table : Arrays.asList("stock_watch_analysis_observation_identity",
                "stock_watch_analysis_identity_candidate", "stock_watch_analysis_migration_state")) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                    Integer.class, table);
            if (count == null || count != 1) throw new IllegalStateException("stock_watch_v6_schema_missing:" + table);
        }
    }

    private void upgradeEmptyIdentitySchema() {
        if (columnExists("stock_watch_analysis_observation_identity", "analysis_scope")) return;
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_watch_analysis_observation_identity", Long.class);
        if (rows != null && rows > 0) throw new IllegalStateException("stock_watch_v6_identity_schema_nonempty_upgrade_refused");
        jdbcTemplate.execute("ALTER TABLE stock_watch_analysis_observation_identity "
                + "ADD COLUMN analysis_scope VARCHAR(40) NOT NULL DEFAULT 'TECHNICAL_RUNTIME' AFTER identity_key");
        dropIndexIfExists("stock_watch_analysis_observation_identity", "uk_sw_analysis_identity_observation");
        jdbcTemplate.execute("CREATE UNIQUE INDEX uk_sw_analysis_identity_observation "
                + "ON stock_watch_analysis_observation_identity(analysis_scope,market,symbol,period_type,observation_key)");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        if (!columnExists(table, column)) jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name=? AND column_name=?", Integer.class, table, column);
        return count != null && count == 1;
    }

    private void dropIndexIfExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                        + "AND table_name=? AND index_name=?", Integer.class, table, index);
        if (count != null && count > 0) jdbcTemplate.execute("ALTER TABLE " + table + " DROP INDEX " + index);
    }

    private MigrationState readState() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM stock_watch_analysis_migration_state WHERE migration_key=?",
                    StockWatchV6RuntimeGate.MIGRATION_KEY);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            return new MigrationState(intValue(row.get("generation")), text(row.get("status")),
                    longValue(row.get("technical_task_cutoff_id")), longValue(row.get("legacy_analysis_cutoff_id")),
                    longValue(row.get("legacy_analysis_count")), text(row.get("plan_hash")));
        } catch (Exception missingSchema) {
            return null;
        }
    }

    private <T> T withMigrationLock(Supplier<T> action) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement acquire = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            acquire.setString(1, LOCK_NAME);
            try (ResultSet result = acquire.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("stock_watch_v6_migration_lock_unavailable");
                }
            }
            try {
                return action.get();
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, LOCK_NAME);
                    release.executeQuery();
                }
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("stock_watch_v6_migration_lock_failed:" + e.getMessage(), e);
        }
    }

    private String taskIdentityKey(Map<String, Object> task) {
        return SCOPE + "|" + text(task.get("market")).toUpperCase(Locale.ROOT) + "|"
                + text(task.get("symbol")).toLowerCase(Locale.ROOT) + "|"
                + firstNonBlank(text(task.get("period_type")), "DAY").toUpperCase(Locale.ROOT) + "|"
                + text(task.get("observation_key"));
    }

    private String identityKey(String scope, String symbol, String market, String period, String observation) {
        return sha256(scope + "\u0000" + market + "\u0000" + symbol + "\u0000" + period + "\u0000" + observation);
    }

    private int contractVersion(Map<String, Object> task) {
        try {
            JSONObject envelope = JSON.parseObject(text(task.get("request_envelope_json")));
            return envelope == null ? 0 : envelope.getIntValue("responseContractVersion");
        } catch (Exception ignored) {
            return 0;
        }
    }

    private JSONObject parseObject(String json, String check, Map<String, Object> task) {
        try {
            JSONObject parsed = JSON.parseObject(json);
            if (parsed == null) throw invalid(task, check);
            return parsed;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(task, check);
        }
    }

    private String taskFingerprint(Map<String, Object> task) {
        return sha256(taskId(task) + "|" + text(task.get("status")) + "|" + text(task.get("request_digest"))
                + "|" + text(task.get("response_hash")) + "|" + text(task.get("response_envelope_hash")));
    }

    private IllegalStateException ambiguous(String identity, List<Map<String, Object>> tasks) {
        return new IllegalStateException("analysis_identity_ambiguous_provider_tasks:" + identity + ":"
                + tasks.stream().map(task -> taskId(task) + ":" + text(task.get("status")))
                .collect(Collectors.joining(",")));
    }

    private IllegalStateException invalid(Map<String, Object> task, String check) {
        return new IllegalStateException("analysis_task_integrity_invalid:id=" + taskId(task)
                + ",status=" + text(task.get("status")) + ",check=" + check);
    }

    private long taskId(Map<String, Object> task) {
        return longValue(task.get("id"));
    }

    private long longValue(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) return 0L;
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(text(value));
    }

    private int intValue(Object value) {
        return (int) longValue(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }

    public static final class MigrationPlan {
        public final long technicalTaskCutoffId;
        public final long legacyAnalysisCutoffId;
        public final long legacyAnalysisCount;
        public final List<IdentityPlan> groups;
        public final String planHash;

        MigrationPlan(long technicalTaskCutoffId, long legacyAnalysisCutoffId,
                      long legacyAnalysisCount, List<IdentityPlan> groups) {
            this.technicalTaskCutoffId = technicalTaskCutoffId;
            this.legacyAnalysisCutoffId = legacyAnalysisCutoffId;
            this.legacyAnalysisCount = legacyAnalysisCount;
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            String material = technicalTaskCutoffId + "|" + legacyAnalysisCutoffId + "|" + legacyAnalysisCount + "|"
                    + groups.stream().map(IdentityPlan::fingerprint).collect(Collectors.joining(";"));
            this.planHash = sha256(material);
        }

        public int candidateCount() {
            return groups.stream().mapToInt(group -> group.candidates.size()).sum();
        }

        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("generation", StockWatchV6RuntimeGate.GENERATION);
            payload.put("technicalTaskCutoffId", technicalTaskCutoffId);
            payload.put("identityCount", groups.size());
            payload.put("candidateCount", candidateCount());
            payload.put("canonicalTaskIds", groups.stream().map(group -> group.canonicalTaskId).collect(Collectors.toList()));
            payload.put("legacyAnalysisCutoffId", legacyAnalysisCutoffId);
            payload.put("legacyAnalysisCount", legacyAnalysisCount);
            payload.put("planHash", planHash);
            return payload;
        }
    }

    static final class IdentityPlan {
        final String identityKey;
        final String scope;
        final String symbol;
        final String market;
        final String period;
        final String observation;
        final long canonicalTaskId;
        final List<CandidatePlan> candidates;

        IdentityPlan(String identityKey, String scope, String symbol, String market, String period,
                     String observation, long canonicalTaskId, List<CandidatePlan> candidates) {
            this.identityKey = identityKey;
            this.scope = scope;
            this.symbol = symbol;
            this.market = market;
            this.period = period;
            this.observation = observation;
            this.canonicalTaskId = canonicalTaskId;
            this.candidates = candidates;
        }

        String fingerprint() {
            return identityKey + "|" + canonicalTaskId + "|" + candidates.stream()
                    .map(CandidatePlan::fingerprint).collect(Collectors.joining(","));
        }
    }

    static final class CandidatePlan {
        final long taskId;
        final String disposition;
        final String reason;
        final String sourceFingerprint;

        CandidatePlan(long taskId, String disposition, String reason, String sourceFingerprint) {
            this.taskId = taskId;
            this.disposition = disposition;
            this.reason = reason;
            this.sourceFingerprint = sourceFingerprint;
        }

        String fingerprint() {
            return taskId + "|" + disposition + "|" + reason + "|" + sourceFingerprint;
        }
    }

    static final class MigrationState {
        final int generation;
        final String status;
        final long technicalTaskCutoffId;
        final long legacyAnalysisCutoffId;
        final long legacyAnalysisCount;
        final String planHash;

        MigrationState(int generation, String status, long technicalTaskCutoffId,
                       long legacyAnalysisCutoffId, long legacyAnalysisCount, String planHash) {
            this.generation = generation;
            this.status = status;
            this.technicalTaskCutoffId = technicalTaskCutoffId;
            this.legacyAnalysisCutoffId = legacyAnalysisCutoffId;
            this.legacyAnalysisCount = legacyAnalysisCount;
            this.planHash = planHash;
        }

        boolean ready() {
            return generation == StockWatchV6RuntimeGate.GENERATION && "READY".equals(status);
        }
    }
}
