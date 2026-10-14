package com.aimeeting.room.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Best-effort additive migration for reviewed meetings; DML-only accounts can start normally. */
@Component
@Slf4j
public class MeetingReviewedSearchLedgerSchemaInitializer {
    private static final String LEDGER_MIGRATION = "sql/migrate-meeting-reviewed-search-ledger-20261006.sql";
    private static final String EPOCH_MIGRATION = "sql/migrate-meeting-run-epoch-20261006.sql";
    private static final String ROUND_SUMMARIES_MIGRATION =
            "sql/migrate-meeting-round-summaries-20261006.sql";
    private static final String PLAN_VERSION_MIGRATION =
            "sql/migrate-meeting-reviewed-plan-versions-20261006.sql";
    private static final String TURN_SKIP_MIGRATION =
            "sql/migrate-meeting-reviewed-turn-skip-20261006.sql";
    private static final String TURN_RETRY_MIGRATION =
            "sql/migrate-meeting-reviewed-turn-retry-20261009.sql";
    private static final String ROOM = "ai_meeting_room";
    private static final String PLAN = "ai_meeting_reviewed_search_plan";
    private static final String GAP = "ai_meeting_reviewed_search_gap";
    private static final String SKIP = "ai_meeting_reviewed_turn_skip";

    private final DataSource dataSource;
    private final boolean reviewedSearchEnabled;

    public MeetingReviewedSearchLedgerSchemaInitializer(DataSource dataSource,
            @Value("${AI_MEETING_REVIEWED_SEARCH_ENABLED:false}") boolean reviewedSearchEnabled) {
        this.dataSource = dataSource;
        this.reviewedSearchEnabled = reviewedSearchEnabled;
    }

    @PostConstruct
    public void ensureSchema() {
        if (!reviewedSearchEnabled) return;
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            // H2 tests load schema-local.sql; only MySQL/MariaDB use these migrations.
            if (!product.contains("mysql") && !product.contains("mariadb")) return;
            Set<String> tables = tableNames(connection);
            if (!tables.contains(PLAN) || !tables.contains(GAP)) migrate(LEDGER_MIGRATION);
            if (!tables.contains(SKIP)) migrate(TURN_SKIP_MIGRATION);
            if (!columns(connection, ROOM).contains("run_epoch")) migrate(EPOCH_MIGRATION);
            if (!columns(connection, ROOM).contains("round_summaries")) migrate(ROUND_SUMMARIES_MIGRATION);
            if (tableNames(connection).contains(PLAN) && !hasPlanVersionSchema(connection)) {
                migratePlanVersions();
            }
            if (tableNames(connection).contains(SKIP) && !hasTurnRetrySchema(connection)) {
                migrateTurnRetries();
            }
            List<String> missing = missingRequirements(connection);
            if (!missing.isEmpty()) {
                log.warn("Reviewed meeting schema is not ready: {}. Apply {}, {}, {}, {}, {}, and {} with a DDL-capable account before starting reviewed meetings.",
                        missing, LEDGER_MIGRATION, EPOCH_MIGRATION, PLAN_VERSION_MIGRATION,
                        TURN_SKIP_MIGRATION, ROUND_SUMMARIES_MIGRATION, TURN_RETRY_MIGRATION);
            }
        } catch (SQLException e) {
            // The optional search feature must not prevent the rest of the app from starting.
            log.warn("Reviewed meeting schema could not be inspected at startup: {}", e.getClass().getSimpleName());
        }
    }

    /** Recheck at use time so an operator can apply migrations without restarting. */
    public boolean isReady() {
        try (Connection connection = dataSource.getConnection()) {
            return missingRequirements(connection).isEmpty();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Deletion of legacy meetings must work even when the optional tables were never installed. */
    public boolean hasLedgerTables() {
        return hasTables(PLAN, GAP);
    }

    public boolean hasTurnSkipTable() {
        return hasTables(SKIP);
    }

    private boolean hasTables(String... required) {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> available = tableNames(connection);
            return Arrays.stream(required).allMatch(available::contains);
        } catch (SQLException unavailable) {
            return false;
        }
    }

    /** Called before claiming a reviewed run; no meeting state changes on failure. */
    public void requireReady() {
        try (Connection connection = dataSource.getConnection()) {
            List<String> missing = missingRequirements(connection);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("REVIEWED_SEARCH_SCHEMA_NOT_READY: " + String.join(",", missing)
                        + "; apply " + LEDGER_MIGRATION + ", " + EPOCH_MIGRATION
                        + ", " + PLAN_VERSION_MIGRATION + ", " + TURN_SKIP_MIGRATION
                        + ", " + ROUND_SUMMARIES_MIGRATION + " and " + TURN_RETRY_MIGRATION);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("REVIEWED_SEARCH_SCHEMA_CHECK_UNAVAILABLE", e);
        }
    }

    private void migrate(String resource) {
        try {
            new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(dataSource);
        } catch (RuntimeException deniedOrInvalid) {
            // A restricted database account is normal in production. Never log SQL or credentials.
            log.warn("Reviewed meeting migration {} was not applied ({}); use a DDL-capable account out of band.",
                    resource, deniedOrInvalid.getClass().getSimpleName());
        }
    }

    /** Conditionally apply the old-table upgrade; MySQL has no portable ADD COLUMN IF NOT EXISTS. */
    private void migratePlanVersions() {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> available = columns(connection, PLAN);
            try (Statement statement = connection.createStatement()) {
                if (!available.contains("context_opinion_id")) {
                    statement.execute("ALTER TABLE " + PLAN
                            + " ADD COLUMN context_opinion_id BIGINT NOT NULL DEFAULT 0");
                }
                if (!available.contains("supersedes_plan_id")) {
                    statement.execute("ALTER TABLE " + PLAN + " ADD COLUMN supersedes_plan_id BIGINT NULL");
                }
                if (!hasUniqueIndex(connection, PLAN, planVersionUniqueKey())) {
                    String removeOld = hasIndexNamed(connection, PLAN, "uk_reviewed_plan_turn")
                            ? "DROP INDEX uk_reviewed_plan_turn, " : "";
                    statement.execute("ALTER TABLE " + PLAN + " " + removeOld
                            + "ADD UNIQUE KEY uk_reviewed_plan_turn "
                            + "(meeting_id, round_number, member_id, target_question_id, as_of, context_opinion_id)");
                }
            }
        } catch (SQLException deniedOrInvalid) {
            log.warn("Reviewed plan version migration was not applied ({}); use a DDL-capable account out of band.",
                    deniedOrInvalid.getClass().getSimpleName());
        }
    }

    private static boolean hasPlanVersionSchema(Connection connection) throws SQLException {
        Set<String> available = columns(connection, PLAN);
        return available.contains("context_opinion_id") && available.contains("supersedes_plan_id")
                && hasUniqueIndex(connection, PLAN, planVersionUniqueKey());
    }

    private void migrateTurnRetries() {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> available = columns(connection, SKIP);
            try (Statement statement = connection.createStatement()) {
                if (!available.contains("active_turn")) {
                    statement.execute("ALTER TABLE " + SKIP + " ADD COLUMN active_turn TINYINT NULL DEFAULT 1");
                }
                if (!available.contains("retry_prepared_at")) {
                    statement.execute("ALTER TABLE " + SKIP + " ADD COLUMN retry_prepared_at DATETIME NULL");
                }
                if (!available.contains("retry_prepared_epoch")) {
                    statement.execute("ALTER TABLE " + SKIP + " ADD COLUMN retry_prepared_epoch BIGINT NULL");
                }
                if (!hasUniqueIndex(connection, SKIP, activeSkipUniqueKey())) {
                    String removeOld = hasIndexNamed(connection, SKIP, "uk_reviewed_skip_turn")
                            ? "DROP INDEX uk_reviewed_skip_turn, " : "";
                    statement.execute("ALTER TABLE " + SKIP + " " + removeOld
                            + "ADD UNIQUE KEY uk_reviewed_skip_turn "
                            + "(meeting_id, round_number, member_id, target_question_id, active_turn)");
                }
            }
        } catch (SQLException deniedOrInvalid) {
            log.warn("Reviewed turn retry migration was not applied ({}); use a DDL-capable account out of band.",
                    deniedOrInvalid.getClass().getSimpleName());
        }
    }

    private static boolean hasTurnRetrySchema(Connection connection) throws SQLException {
        Set<String> available = columns(connection, SKIP);
        return available.containsAll(Arrays.asList("active_turn", "retry_prepared_at", "retry_prepared_epoch"))
                && hasUniqueIndex(connection, SKIP, activeSkipUniqueKey());
    }

    private static List<String> activeSkipUniqueKey() {
        return Arrays.asList("meeting_id", "round_number", "member_id", "target_question_id", "active_turn");
    }

    private static List<String> planVersionUniqueKey() {
        return Arrays.asList("meeting_id", "round_number", "member_id", "target_question_id",
                "as_of", "context_opinion_id");
    }

    private static List<String> missingRequirements(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (!product.contains("mysql") && !product.contains("mariadb") && !product.contains("h2")) {
            return Collections.singletonList("unsupported_database:" + product.replaceAll("[^a-z0-9_-]", "_"));
        }
        Set<String> tables = tableNames(connection);
        List<String> missing = new ArrayList<>();
        requireColumns(connection, tables, ROOM, Arrays.asList("id", "run_epoch", "round_summaries"), missing);
        requireColumns(connection, tables, PLAN, Arrays.asList("id", "meeting_id", "round_number",
                "member_id", "target_question_id", "as_of", "context_opinion_id",
                "supersedes_plan_id", "normalized_plan_json", "gmt_create"), missing);
        requireColumns(connection, tables, GAP, Arrays.asList("id", "meeting_id", "round_number",
                "as_of", "question_hash", "exact_question", "request_id", "status", "packet_json",
                "status_reason", "gmt_create", "gmt_modify"), missing);
        requireColumns(connection, tables, SKIP, Arrays.asList("id", "meeting_id", "round_number",
                "member_id", "target_question_id", "reason_code", "run_epoch", "as_of",
                "context_opinion_id", "plan_id", "gap_id", "active_turn", "retry_prepared_at",
                "retry_prepared_epoch", "gmt_create"), missing);
        if (tables.contains(PLAN) && !hasUniqueIndex(connection, PLAN,
                planVersionUniqueKey())) {
            missing.add(PLAN + ".unique_turn");
        }
        if (tables.contains(GAP)) {
            if (!hasUniqueIndex(connection, GAP,
                    Arrays.asList("meeting_id", "round_number", "as_of", "question_hash"))) {
                missing.add(GAP + ".unique_identity");
            }
            if (!hasUniqueIndex(connection, GAP, Collections.singletonList("request_id"))) {
                missing.add(GAP + ".unique_request_id");
            }
        }
        if (tables.contains(SKIP) && !hasUniqueIndex(connection, SKIP,
                activeSkipUniqueKey())) {
            missing.add(SKIP + ".unique_turn");
        }
        return missing;
    }

    private static void requireColumns(Connection connection, Set<String> tables, String table,
                                       List<String> expected, List<String> missing) throws SQLException {
        if (!tables.contains(table)) {
            missing.add(table);
            return;
        }
        Set<String> actual = columns(connection, table);
        for (String column : expected) {
            if (!actual.contains(column)) missing.add(table + "." + column);
        }
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%",
                new String[]{"TABLE"})) {
            while (tables.next()) names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                physicalTableName(connection, table), "%")) {
            while (columns.next()) names.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static boolean hasUniqueIndex(Connection connection, String table, List<String> expected)
            throws SQLException {
        Map<String, Map<Integer, String>> indexes = new HashMap<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null,
                physicalTableName(connection, table), true, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name != null && column != null && !rows.getBoolean("NON_UNIQUE")) {
                    indexes.computeIfAbsent(name, ignored -> new TreeMap<>())
                            .put(rows.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
                }
            }
        }
        return indexes.values().stream().anyMatch(index -> new ArrayList<>(index.values()).equals(expected));
    }

    private static boolean hasIndexNamed(Connection connection, String table, String expected)
            throws SQLException {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null,
                physicalTableName(connection, table), false, false)) {
            while (rows.next()) {
                if (expected.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
            }
        }
        return false;
    }

    private static String physicalTableName(Connection connection, String table) throws SQLException {
        return connection.getMetaData().storesUpperCaseIdentifiers()
                ? table.toUpperCase(Locale.ROOT) : table;
    }
}
