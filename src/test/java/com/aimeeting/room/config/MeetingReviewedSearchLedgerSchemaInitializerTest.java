package com.aimeeting.room.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingReviewedSearchLedgerSchemaInitializerTest {
    @Test
    void disabledOptionalFeatureDoesNotTouchDatabaseAtStartup() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        MeetingReviewedSearchLedgerSchemaInitializer initializer =
                new MeetingReviewedSearchLedgerSchemaInitializer(dataSource, false);

        assertDoesNotThrow(initializer::ensureSchema);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void deniedDdlDoesNotAbortStartupButReviewedUseReportsMissingMigrations() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("test");
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getTables(any(), any(), anyString(), any(String[].class)))
                .thenAnswer(ignored -> rows(Collections.emptyList()));
        when(metadata.getColumns(any(), any(), anyString(), anyString()))
                .thenAnswer(ignored -> rows(Collections.emptyList()));
        when(connection.createStatement()).thenThrow(new SQLException("CREATE and ALTER denied"));
        MeetingReviewedSearchLedgerSchemaInitializer initializer =
                new MeetingReviewedSearchLedgerSchemaInitializer(dataSource, true);

        assertDoesNotThrow(initializer::ensureSchema);
        assertFalse(initializer.isReady());
        assertFalse(initializer.hasLedgerTables());
        assertFalse(initializer.hasTurnSkipTable());
        IllegalStateException failure = assertThrows(IllegalStateException.class, initializer::requireReady);
        assertTrue(failure.getMessage().startsWith("REVIEWED_SEARCH_SCHEMA_NOT_READY:"));
        assertTrue(failure.getMessage().contains("migrate-meeting-reviewed-search-ledger-20261006.sql"));
        assertTrue(failure.getMessage().contains("migrate-meeting-run-epoch-20261006.sql"));
        assertTrue(failure.getMessage().contains("migrate-meeting-reviewed-plan-versions-20261006.sql"));
        assertTrue(failure.getMessage().contains("migrate-meeting-reviewed-turn-skip-20261006.sql"));
        assertTrue(failure.getMessage().contains("migrate-meeting-round-summaries-20261006.sql"));
    }

    @Test
    void completeExistingSchemaNeedsNoDdlPrivilege() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("test");
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getTables(any(), any(), anyString(), any(String[].class)))
                .thenAnswer(ignored -> rows(Arrays.asList(
                        row("TABLE_NAME", "ai_meeting_room"),
                        row("TABLE_NAME", "ai_meeting_reviewed_search_plan"),
                        row("TABLE_NAME", "ai_meeting_reviewed_search_gap"),
                        row("TABLE_NAME", "ai_meeting_reviewed_turn_skip"))));
        when(metadata.getColumns(any(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            String table = invocation.getArgument(2);
            List<String> names;
            if ("ai_meeting_room".equals(table)) {
                names = Arrays.asList("id", "run_epoch", "round_summaries");
            } else if ("ai_meeting_reviewed_search_plan".equals(table)) {
                names = Arrays.asList("id", "meeting_id", "round_number", "member_id",
                        "target_question_id", "as_of", "context_opinion_id", "supersedes_plan_id",
                        "normalized_plan_json", "gmt_create");
            } else if ("ai_meeting_reviewed_turn_skip".equals(table)) {
                names = Arrays.asList("id", "meeting_id", "round_number", "member_id",
                        "target_question_id", "reason_code", "run_epoch", "as_of",
                        "context_opinion_id", "plan_id", "gap_id", "active_turn", "retry_prepared_at",
                        "retry_prepared_epoch", "gmt_create");
            } else {
                names = Arrays.asList("id", "meeting_id", "round_number", "as_of", "question_hash",
                        "exact_question", "request_id", "status", "packet_json", "status_reason",
                        "gmt_create", "gmt_modify");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (String name : names) result.add(row("COLUMN_NAME", name));
            return rows(result);
        });
        when(metadata.getIndexInfo(any(), any(), anyString(), eq(true), eq(false)))
                .thenAnswer(invocation -> {
                    String table = invocation.getArgument(2);
                    List<Map<String, Object>> result = new ArrayList<>();
                    if ("ai_meeting_reviewed_search_plan".equals(table)) {
                        addIndex(result, "plan_turn", "meeting_id", "round_number", "member_id",
                                "target_question_id", "as_of", "context_opinion_id");
                    } else if ("ai_meeting_reviewed_turn_skip".equals(table)) {
                        addIndex(result, "skip_turn", "meeting_id", "round_number", "member_id",
                                "target_question_id", "active_turn");
                    } else {
                        addIndex(result, "gap_identity", "meeting_id", "round_number", "as_of", "question_hash");
                        addIndex(result, "gap_request", "request_id");
                    }
                    return rows(result);
                });
        MeetingReviewedSearchLedgerSchemaInitializer initializer =
                new MeetingReviewedSearchLedgerSchemaInitializer(dataSource, true);

        assertDoesNotThrow(initializer::ensureSchema);
        assertTrue(initializer.isReady());
        assertTrue(initializer.hasLedgerTables());
        assertTrue(initializer.hasTurnSkipTable());
        assertDoesNotThrow(initializer::requireReady);
        verify(connection, never()).createStatement();
    }

    private static void addIndex(List<Map<String, Object>> rows, String index, String... columns) {
        for (int i = 0; i < columns.length; i++) {
            Map<String, Object> row = row("INDEX_NAME", index);
            row.put("COLUMN_NAME", columns[i]);
            row.put("ORDINAL_POSITION", i + 1);
            row.put("NON_UNIQUE", false);
            rows.add(row);
        }
    }

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put(key, value);
        return row;
    }

    private static ResultSet rows(List<Map<String, Object>> entries) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        AtomicInteger position = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(ignored -> position.incrementAndGet() < entries.size());
        when(resultSet.getString(anyString())).thenAnswer(invocation ->
                (String) entries.get(position.get()).get(invocation.getArgument(0)));
        when(resultSet.getInt(anyString())).thenAnswer(invocation ->
                (Integer) entries.get(position.get()).get(invocation.getArgument(0)));
        when(resultSet.getBoolean(anyString())).thenAnswer(invocation ->
                (Boolean) entries.get(position.get()).get(invocation.getArgument(0)));
        return resultSet;
    }
}
