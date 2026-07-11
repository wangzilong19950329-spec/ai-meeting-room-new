package com.aimeeting.room.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockWatchTechnicalSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "symbol VARCHAR(16) NOT NULL,"
                    + "market VARCHAR(8) NOT NULL,"
                    + "stock_name VARCHAR(128),"
                    + "image_url TEXT,"
                    + "enabled TINYINT NOT NULL DEFAULT 1,"
                    + "source VARCHAR(32) NOT NULL DEFAULT 'manual',"
                    + "group_key VARCHAR(32) NOT NULL DEFAULT 'watch',"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_sw_item_symbol (symbol, market),"
                    + "KEY idx_sw_item_enabled (enabled, symbol, market),"
                    + "KEY idx_sw_item_group (group_key, enabled, symbol, market)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            safeAddColumn("stock_watch_item", "group_key", "VARCHAR(32) NOT NULL DEFAULT 'watch'");
            safeExecute("CREATE INDEX idx_sw_item_group ON stock_watch_item(group_key, enabled, symbol, market)");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_technical_session ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "symbol VARCHAR(16) NOT NULL,"
                    + "market VARCHAR(8) NOT NULL,"
                    + "stock_name VARCHAR(128),"
                    + "period_type VARCHAR(16) NOT NULL DEFAULT 'DAY',"
                    + "session_key VARCHAR(128) NOT NULL,"
                    + "bridge_target_id VARCHAR(128),"
                    + "conversation_url VARCHAR(1024),"
                    + "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',"
                    + "generation INT NOT NULL DEFAULT 1,"
                    + "context_chars INT NOT NULL DEFAULT 0,"
                    + "context_limit_chars INT NOT NULL DEFAULT 80000,"
                    + "last_run_at DATETIME,"
                    + "last_run_status VARCHAR(64),"
                    + "last_error TEXT,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_sw_tech_session_symbol (symbol, market, period_type, status),"
                    + "KEY idx_sw_tech_session_key (session_key)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_technical_analysis ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "session_id BIGINT,"
                    + "symbol VARCHAR(16) NOT NULL,"
                    + "market VARCHAR(8) NOT NULL,"
                    + "stock_name VARCHAR(128),"
                    + "analysis_date DATE NOT NULL,"
                    + "run_slot VARCHAR(16) NOT NULL,"
                    + "period_type VARCHAR(16) NOT NULL DEFAULT 'DAY',"
                    + "period_key VARCHAR(32),"
                    + "source_record_ids TEXT,"
                    + "evidence_signal_json MEDIUMTEXT,"
                    + "main_force_intent_json TEXT,"
                    + "main_force_intent_status VARCHAR(32),"
                    + "main_force_intent_warnings_json TEXT,"
                    + "status VARCHAR(32) NOT NULL,"
                    + "stance VARCHAR(64),"
                    + "tone VARCHAR(32),"
                    + "confidence DECIMAL(5,4),"
                    + "title VARCHAR(256),"
                    + "conclusion TEXT,"
                    + "evidence_json TEXT,"
                    + "missing_data_json TEXT,"
                    + "data_snapshot_json MEDIUMTEXT,"
                    + "raw_response MEDIUMTEXT,"
                    + "prompt_hash VARCHAR(128),"
                    + "provider VARCHAR(64),"
                    + "bridge_target_id VARCHAR(128),"
                    + "conversation_url VARCHAR(1024),"
                    + "context_chars_delta INT DEFAULT 0,"
                    + "error_message TEXT,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_sw_tech_analysis_symbol_date (symbol, market, period_type, analysis_date),"
                    + "KEY idx_sw_tech_analysis_session (session_id),"
                    + "KEY idx_sw_tech_analysis_slot (symbol, market, period_type, period_key, analysis_date, run_slot)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            safeAddColumn("stock_watch_technical_session", "period_type", "VARCHAR(16) NOT NULL DEFAULT 'DAY'");
            safeAddColumn("stock_watch_technical_analysis", "period_type", "VARCHAR(16) NOT NULL DEFAULT 'DAY'");
            safeAddColumn("stock_watch_technical_analysis", "period_key", "VARCHAR(32)");
            safeAddColumn("stock_watch_technical_analysis", "source_record_ids", "TEXT");
            safeAddColumn("stock_watch_technical_analysis", "evidence_signal_json", "MEDIUMTEXT");
            safeAddColumn("stock_watch_technical_analysis", "main_force_intent_json", "TEXT");
            safeAddColumn("stock_watch_technical_analysis", "main_force_intent_status", "VARCHAR(32)");
            safeAddColumn("stock_watch_technical_analysis", "main_force_intent_warnings_json", "TEXT");
            safeAddColumn("stock_watch_technical_analysis", "missing_data_json", "TEXT");
            safeAddColumn("stock_watch_technical_analysis", "data_snapshot_json", "MEDIUMTEXT");
            safeExecute("CREATE INDEX idx_sw_tech_analysis_period ON stock_watch_technical_analysis(symbol, market, period_type, period_key)");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_prediction ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "analysis_id BIGINT NOT NULL,"
                    + "symbol VARCHAR(16) NOT NULL,"
                    + "market VARCHAR(8) NOT NULL,"
                    + "stock_name VARCHAR(128),"
                    + "horizon_code VARCHAR(16) NOT NULL,"
                    + "horizon_trading_days INT NOT NULL,"
                    + "forecast_date DATE NOT NULL,"
                    + "source_price DECIMAL(18,4) NOT NULL,"
                    + "direction VARCHAR(16) NOT NULL,"
                    + "probability DECIMAL(6,5),"
                    + "target_low DECIMAL(18,4),"
                    + "target_high DECIMAL(18,4),"
                    + "expected_return_low DECIMAL(12,6),"
                    + "expected_return_high DECIMAL(12,6),"
                    + "invalidation_price DECIMAL(18,4),"
                    + "rationale TEXT,"
                    + "input_snapshot_json MEDIUMTEXT,"
                    + "status VARCHAR(24) NOT NULL DEFAULT 'PENDING',"
                    + "evaluated_trade_date DATE,"
                    + "actual_close DECIMAL(18,4),"
                    + "actual_high DECIMAL(18,4),"
                    + "actual_low DECIMAL(18,4),"
                    + "actual_return_pct DECIMAL(12,6),"
                    + "direction_hit TINYINT,"
                    + "range_close_hit TINYINT,"
                    + "range_touch_hit TINYINT,"
                    + "invalidated TINYINT,"
                    + "absolute_error_pct DECIMAL(12,6),"
                    + "brier_score DECIMAL(12,8),"
                    + "evaluation_note TEXT,"
                    + "evaluated_at DATETIME,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_sw_prediction_analysis_horizon (analysis_id, horizon_code),"
                    + "KEY idx_sw_prediction_symbol_date (symbol, market, forecast_date),"
                    + "KEY idx_sw_prediction_status (status, forecast_date)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_delivery_config ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "symbol VARCHAR(16) NOT NULL,"
                    + "market VARCHAR(8) NOT NULL,"
                    + "stock_name VARCHAR(128),"
                    + "enabled TINYINT NOT NULL DEFAULT 0,"
                    + "recipients_json TEXT NOT NULL,"
                    + "sender_mode VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',"
                    + "frequency_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',"
                    + "interval_minutes INT NOT NULL DEFAULT 120,"
                    + "trigger_policy_json TEXT,"
                    + "active_from DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "last_dispatch_status VARCHAR(32),"
                    + "last_dispatch_message TEXT,"
                    + "last_dispatch_at DATETIME,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_sw_delivery_config_symbol (symbol, market),"
                    + "KEY idx_sw_delivery_config_enabled (enabled, frequency_type)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_delivery_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "config_id BIGINT NOT NULL,"
                    + "analysis_id BIGINT NOT NULL,"
                    + "symbol VARCHAR(16) NOT NULL,"
                    + "market VARCHAR(8) NOT NULL,"
                    + "recipient_hash VARCHAR(64) NOT NULL,"
                    + "recipient_masked VARCHAR(255) NOT NULL,"
                    + "status VARCHAR(32) NOT NULL,"
                    + "message_id VARCHAR(255),"
                    + "error_message TEXT,"
                    + "sent_at DATETIME,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_sw_delivery_once (analysis_id, recipient_hash),"
                    + "KEY idx_sw_delivery_config_status (config_id, status),"
                    + "KEY idx_sw_delivery_symbol_time (symbol, market, gmt_create)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_fc_card ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "symbol VARCHAR(16) NOT NULL,"
                    + "market VARCHAR(8) NOT NULL,"
                    + "stock_name VARCHAR(128),"
                    + "card_key VARCHAR(128) NOT NULL,"
                    + "card_title VARCHAR(256) NOT NULL,"
                    + "card_type VARCHAR(64),"
                    + "thesis TEXT,"
                    + "trigger_logic TEXT,"
                    + "data_source_tier VARCHAR(32),"
                    + "data_sources TEXT,"
                    + "collection_method VARCHAR(32),"
                    + "frequency VARCHAR(128),"
                    + "parser_spec TEXT,"
                    + "baseline_reading TEXT,"
                    + "failure_monitor TEXT,"
                    + "auto_proxy TEXT,"
                    + "activation_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',"
                    + "activation_blockers TEXT,"
                    + "enabled TINYINT NOT NULL DEFAULT 0,"
                    + "notify_enabled TINYINT NOT NULL DEFAULT 1,"
                    + "last_collect_status VARCHAR(64),"
                    + "last_collect_note TEXT,"
                    + "last_collected_at DATETIME,"
                    + "raw_markdown MEDIUMTEXT,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_sw_fc_card_key (symbol, market, card_key),"
                    + "KEY idx_sw_fc_card_symbol_status (symbol, market, activation_status),"
                    + "KEY idx_sw_fc_card_enabled (enabled, symbol, market)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception e) {
            log.warn("failed to ensure stock-watch technical analysis schema: {}", e.getMessage());
        }
    }

    private void safeAddColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (Exception ignored) {
            // Column already exists, or the local database dialect reports duplicate columns differently.
        }
    }

    private void safeExecute(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception ignored) {
            // Index already exists or dialect does not support this exact statement.
        }
    }
}
