package com.aimeeting.room.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Creates additive meeting tables (usage call log, roundtable v2 checkpoints) on existing MySQL databases. */
@Component
@Slf4j
public class MeetingAdditiveSchemaInitializer {
    private static final Map<String, String> MIGRATIONS = new LinkedHashMap<>();

    static {
        MIGRATIONS.put("ai_meeting_call_log", "sql/migrate-meeting-call-log-20261017.sql");
        MIGRATIONS.put("ai_meeting_v2_record", "sql/migrate-meeting-v2-record-20261017.sql");
    }

    private final DataSource dataSource;

    public MeetingAdditiveSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void ensureSchema() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            // H2 tests load schema-local.sql; only MySQL/MariaDB need the additive migrations.
            if (!product.contains("mysql") && !product.contains("mariadb")) return;
            for (Map.Entry<String, String> migration : MIGRATIONS.entrySet()) {
                try (ResultSet tables = connection.getMetaData().getTables(
                        connection.getCatalog(), null, migration.getKey(), null)) {
                    if (tables.next()) continue;
                }
                try {
                    new ResourceDatabasePopulator(new ClassPathResource(migration.getValue())).populate(connection);
                    log.info("Created {}", migration.getKey());
                } catch (RuntimeException e) {
                    log.warn("Table {} is unavailable until {} is applied: {}",
                            migration.getKey(), migration.getValue(), e.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            log.warn("Additive meeting tables could not be inspected at startup: {}", e.getClass().getSimpleName());
        }
    }
}
