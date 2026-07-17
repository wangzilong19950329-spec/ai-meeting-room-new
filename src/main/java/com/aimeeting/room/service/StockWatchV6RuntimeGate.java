package com.aimeeting.room.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Locale;

@Slf4j
@Service
@DependsOn("stockWatchTechnicalSchemaInitializer")
public class StockWatchV6RuntimeGate {

    public static final int GENERATION = 6;
    public static final String MIGRATION_KEY = "stock-watch-v6";

    private final JdbcTemplate jdbcTemplate;
    private final String mode;
    private volatile boolean ready;

    public StockWatchV6RuntimeGate(JdbcTemplate jdbcTemplate,
                                   @Value("${stock-watch.technical-analysis.v6-mode:runtime}") String mode) {
        this.jdbcTemplate = jdbcTemplate;
        this.mode = normalizeMode(mode);
    }

    @PostConstruct
    public void verifyAtStartup() {
        if ("disabled".equals(mode)) {
            log.warn("stock-watch v6 runtime gate disabled by explicit profile");
            ready = true;
            return;
        }
        if ("migration".equals(mode)) {
            log.info("stock-watch v6 runtime gate bypassed for explicit migration process");
            ready = true;
            return;
        }
        ready = loadReadyState();
        if (!ready) {
            throw new IllegalStateException("stock_watch_v6_runtime_not_ready:run_explicit_migration_first");
        }
        log.info("stock-watch v6 runtime gate ready generation={}", GENERATION);
    }

    public void assertReady() {
        if (!ready) throw new IllegalStateException("stock_watch_v6_runtime_not_ready");
    }

    public boolean isReady() {
        return ready;
    }

    private boolean loadReadyState() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_watch_analysis_migration_state "
                            + "WHERE migration_key=? AND generation=? AND status='READY'",
                    Integer.class, MIGRATION_KEY, GENERATION);
            return count != null && count == 1;
        } catch (Exception missingSchema) {
            log.error("stock-watch v6 runtime state unavailable: {}", missingSchema.getMessage());
            return false;
        }
    }

    private String normalizeMode(String value) {
        String normalized = value == null ? "runtime" : value.trim().toLowerCase(Locale.ROOT);
        if (!"runtime".equals(normalized) && !"migration".equals(normalized) && !"disabled".equals(normalized)) {
            throw new IllegalArgumentException("stock_watch_v6_mode_invalid:" + value);
        }
        return normalized;
    }
}
