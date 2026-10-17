package com.aimeeting.room.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Locale;

/** Creates the meeting call-log table on existing MySQL databases; usage recording is best-effort. */
@Component
@Slf4j
public class MeetingCallLogSchemaInitializer {
    private static final String MIGRATION = "sql/migrate-meeting-call-log-20261017.sql";
    private static final String TABLE = "ai_meeting_call_log";

    private final DataSource dataSource;

    public MeetingCallLogSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void ensureSchema() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            // H2 tests load schema-local.sql; only MySQL/MariaDB need the additive migration.
            if (!product.contains("mysql") && !product.contains("mariadb")) return;
            try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, TABLE, null)) {
                if (tables.next()) return;
            }
            new ResourceDatabasePopulator(new ClassPathResource(MIGRATION)).populate(connection);
            log.info("Created {} for meeting usage accounting", TABLE);
        } catch (Exception e) {
            log.warn("Meeting call-log table is unavailable; usage will not be recorded until {} is applied: {}",
                    MIGRATION, e.getClass().getSimpleName());
        }
    }
}
