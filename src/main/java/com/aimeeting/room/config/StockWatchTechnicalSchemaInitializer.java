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
                    + "analysis_mode VARCHAR(32) NOT NULL DEFAULT 'INTERVAL',"
                    + "analysis_interval_minutes INT DEFAULT 30,"
                    + "anomaly_trigger_mode VARCHAR(32) NOT NULL DEFAULT 'IMMEDIATE_ANALYSIS',"
                    + "weekly_review_enabled TINYINT NOT NULL DEFAULT 1,"
                    + "monthly_review_enabled TINYINT NOT NULL DEFAULT 1,"
                    + "analysis_policy_version BIGINT NOT NULL DEFAULT 0,"
                    + "analysis_policy_modified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_sw_item_symbol (symbol, market),"
                    + "KEY idx_sw_item_enabled (enabled, symbol, market),"
                    + "KEY idx_sw_item_group (group_key, enabled, symbol, market)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            safeAddColumn("stock_watch_item", "group_key", "VARCHAR(32) NOT NULL DEFAULT 'watch'");
            safeAddColumn("stock_watch_item", "analysis_mode", "VARCHAR(32) NOT NULL DEFAULT 'INTERVAL'");
            safeAddColumn("stock_watch_item", "analysis_interval_minutes", "INT DEFAULT 30");
            safeAddColumn("stock_watch_item", "anomaly_trigger_mode", "VARCHAR(32) NOT NULL DEFAULT 'IMMEDIATE_ANALYSIS'");
            safeAddColumn("stock_watch_item", "weekly_review_enabled", "TINYINT NOT NULL DEFAULT 1");
            safeAddColumn("stock_watch_item", "monthly_review_enabled", "TINYINT NOT NULL DEFAULT 1");
            safeAddColumn("stock_watch_item", "analysis_policy_version", "BIGINT NOT NULL DEFAULT 0");
            safeAddColumn("stock_watch_item", "analysis_policy_modified_at", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
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
                    + "context_limit_chars INT NOT NULL DEFAULT 500000,"
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
                    + "analysis_task_id BIGINT,"
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
                    + "UNIQUE KEY uk_sw_tech_analysis_task (analysis_task_id),"
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
            safeAddColumn("stock_watch_technical_analysis", "analysis_task_id", "BIGINT");
            safeExecute("CREATE UNIQUE INDEX uk_sw_tech_analysis_task ON stock_watch_technical_analysis(analysis_task_id)");
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
                    + "direction_policy_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY_1PCT',"
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

            safeAddColumn("stock_watch_prediction", "direction_policy_version",
                    "VARCHAR(32) NOT NULL DEFAULT 'LEGACY_1PCT'");

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

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_analysis_demand ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,demand_key VARCHAR(128) NOT NULL,"
                    + "symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,stock_name VARCHAR(128),"
                    + "capability VARCHAR(64) NOT NULL DEFAULT 'TECHNICAL_PLUS_ANOMALY',"
                    + "trigger_type VARCHAR(32) NOT NULL DEFAULT 'ANOMALY_DEFERRED',"
                    + "schedule_slot_key VARCHAR(128),run_slot VARCHAR(64),coalesce_key VARCHAR(192),active_coalesce_key VARCHAR(192),"
                    + "source_policy_version BIGINT,reason_codes_json TEXT,trigger_context_json MEDIUMTEXT,"
                    + "period_type VARCHAR(16) NOT NULL DEFAULT 'DAY',"
                    + "target_fact_observation_id BIGINT,target_fact_observation_key VARCHAR(64),"
                    + "target_source_time_epoch_ms BIGINT,trade_date DATE NOT NULL,"
                    + "business_generation INT NOT NULL DEFAULT 1,retry_of_demand_id BIGINT,"
                    + "superseded_by_demand_id BIGINT,"
                    + "status VARCHAR(32) NOT NULL,priority INT NOT NULL DEFAULT 400,"
                    + "not_before DATETIME NOT NULL,deadline_at DATETIME NOT NULL,"
                    + "provider_submit_count INT NOT NULL DEFAULT 0,analysis_task_id BIGINT,analysis_id BIGINT,"
                    + "lease_token VARCHAR(64),lease_until DATETIME,error_message TEXT,"
                    + "row_version BIGINT NOT NULL DEFAULT 0,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),UNIQUE KEY uk_sw_analysis_demand_key (demand_key),"
                    + "UNIQUE KEY uk_sw_analysis_demand_active_coalesce (active_coalesce_key),"
                    + "KEY idx_sw_analysis_demand_dispatch (status,not_before,priority),"
                    + "KEY idx_sw_analysis_demand_task (analysis_task_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            safeAddColumn("stock_watch_analysis_demand", "capability", "VARCHAR(64) NOT NULL DEFAULT 'TECHNICAL_PLUS_ANOMALY'");
            safeAddColumn("stock_watch_analysis_demand", "trigger_type", "VARCHAR(32) NOT NULL DEFAULT 'ANOMALY_DEFERRED'");
            safeAddColumn("stock_watch_analysis_demand", "schedule_slot_key", "VARCHAR(128)");
            safeAddColumn("stock_watch_analysis_demand", "run_slot", "VARCHAR(64)");
            safeAddColumn("stock_watch_analysis_demand", "coalesce_key", "VARCHAR(192)");
            safeAddColumn("stock_watch_analysis_demand", "active_coalesce_key", "VARCHAR(192)");
            safeAddColumn("stock_watch_analysis_demand", "source_policy_version", "BIGINT");
            safeAddColumn("stock_watch_analysis_demand", "reason_codes_json", "TEXT");
            safeAddColumn("stock_watch_analysis_demand", "trigger_context_json", "MEDIUMTEXT");
            safeAddColumn("stock_watch_analysis_demand", "superseded_by_demand_id", "BIGINT");
            safeExecute("ALTER TABLE stock_watch_analysis_demand MODIFY target_fact_observation_id BIGINT NULL");
            safeExecute("ALTER TABLE stock_watch_analysis_demand MODIFY target_fact_observation_key VARCHAR(64) NULL");
            safeExecute("ALTER TABLE stock_watch_analysis_demand MODIFY target_source_time_epoch_ms BIGINT NULL");
            safeExecute("CREATE UNIQUE INDEX uk_sw_analysis_demand_active_coalesce ON stock_watch_analysis_demand(active_coalesce_key)");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_analysis_scheduler_ownership ("
                    + "id INT NOT NULL,mode VARCHAR(16) NOT NULL,generation BIGINT NOT NULL DEFAULT 0,"
                    + "row_version BIGINT NOT NULL DEFAULT 0,last_error TEXT,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            safeExecute("INSERT IGNORE INTO stock_watch_analysis_scheduler_ownership(id,mode,generation,row_version) VALUES (1,'LEGACY',0,0)");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS stock_watch_alert_delivery_obligation ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,event_key VARCHAR(128) NOT NULL,"
                    + "open_episode_key VARCHAR(128),config_id BIGINT,rule_id VARCHAR(64) NOT NULL,"
                    + "symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,stock_name VARCHAR(128),"
                    + "recipient_hash VARCHAR(64) NOT NULL,recipient_address VARCHAR(320) NOT NULL,"
                    + "sender_snapshot_json TEXT,target_fact_observation_id BIGINT,"
                    + "target_fact_observation_key VARCHAR(64),target_source_time_epoch_ms BIGINT,trade_date DATE,"
                    + "signal_fingerprint VARCHAR(512) NOT NULL,inline_analysis_id BIGINT,analysis_demand_id BIGINT,"
                    + "deadline_at DATETIME NOT NULL,episode_suppressed_until DATETIME,"
                    + "initial_subject VARCHAR(512) NOT NULL,initial_body MEDIUMTEXT NOT NULL,"
                    + "initial_message_id VARCHAR(255) NOT NULL,initial_status VARCHAR(32) NOT NULL,"
                    + "initial_attempt_count INT NOT NULL DEFAULT 0,initial_next_attempt_at DATETIME,"
                    + "initial_lease_token VARCHAR(64),initial_lease_until DATETIME,initial_sent_at DATETIME,initial_error TEXT,"
                    + "supplement_analysis_id BIGINT,supplement_subject VARCHAR(512),supplement_body MEDIUMTEXT,"
                    + "supplement_message_id VARCHAR(255) NOT NULL,supplement_status VARCHAR(32) NOT NULL,"
                    + "supplement_attempt_count INT NOT NULL DEFAULT 0,supplement_next_attempt_at DATETIME,"
                    + "supplement_lease_token VARCHAR(64),supplement_lease_until DATETIME,"
                    + "supplement_sent_at DATETIME,supplement_error TEXT,row_version BIGINT NOT NULL DEFAULT 0,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_sw_alert_obligation_event_recipient (event_key,recipient_hash),"
                    + "UNIQUE KEY uk_sw_alert_obligation_open_episode (open_episode_key),"
                    + "KEY idx_sw_alert_initial_dispatch (initial_status,initial_next_attempt_at),"
                    + "KEY idx_sw_alert_supplement_dispatch (supplement_status,supplement_next_attempt_at),"
                    + "KEY idx_sw_alert_demand (analysis_demand_id,supplement_status)"
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
        ensureV17FactSchema();
        ensurePriceFailureResearchSchema();
    }

    private void ensureV17FactSchema() {
        safeExecute("CREATE TABLE IF NOT EXISTS stock_watch_fact_head ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,arrival_version BIGINT NOT NULL DEFAULT 0,"
                + "latest_observation_id BIGINT,last_source_time_epoch_ms BIGINT,last_source_date VARCHAR(16),"
                + "last_source_quality VARCHAR(32),last_fact_hash VARCHAR(64),row_version BIGINT NOT NULL DEFAULT 0,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),UNIQUE KEY uk_sw_fact_head_symbol (symbol, market)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        safeExecute("CREATE TABLE IF NOT EXISTS stock_watch_fact_observation ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,"
                + "stock_name VARCHAR(128),observation_key VARCHAR(64) NOT NULL,arrival_version BIGINT NOT NULL,"
                + "source_time_epoch_ms BIGINT NOT NULL,source_date VARCHAR(16) NOT NULL,source_time_quality VARCHAR(32) NOT NULL,"
                + "fact_hash VARCHAR(64) NOT NULL,fact_snapshot_json MEDIUMTEXT NOT NULL,data_quality_json TEXT,"
                + "sampled_at DATETIME NOT NULL,gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY (id),"
                + "UNIQUE KEY uk_sw_fact_observation_key (observation_key),"
                + "UNIQUE KEY uk_sw_fact_observation_version (symbol, market, arrival_version),"
                + "KEY idx_sw_fact_observation_source (symbol, market, source_time_epoch_ms)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        safeExecute("CREATE TABLE IF NOT EXISTS stock_watch_intent_test ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,"
                + "origin_observation_id BIGINT NOT NULL,activation_after_observation_id BIGINT NOT NULL,"
                + "test_key VARCHAR(160) NOT NULL,claim_ref VARCHAR(64) NOT NULL,metric_path VARCHAR(128) NOT NULL,"
                + "comparison_operator VARCHAR(32) NOT NULL,target_value_json TEXT NOT NULL,max_observation_age INT NOT NULL,"
                + "required_observed_count INT NOT NULL,age_count INT NOT NULL DEFAULT 0,observed_count INT NOT NULL DEFAULT 0,"
                + "test_verdict VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED',row_version BIGINT NOT NULL DEFAULT 0,"
                + "last_evaluated_observation_id BIGINT,last_evaluated_source_date VARCHAR(16),resolved_observation_id BIGINT,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,PRIMARY KEY (id),"
                + "UNIQUE KEY uk_sw_intent_test_origin_key (origin_observation_id, test_key),"
                + "KEY idx_sw_intent_test_active (symbol, market, resolved_observation_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        safeAddColumn("stock_watch_intent_test", "last_evaluated_source_date", "VARCHAR(16)");
        safeExecute("CREATE TABLE IF NOT EXISTS stock_watch_intent_test_result ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,observation_id BIGINT NOT NULL,observation_version BIGINT NOT NULL,"
                + "test_id BIGINT NOT NULL,actual_value_json TEXT,match_status VARCHAR(32) NOT NULL,"
                + "verdict_after VARCHAR(32) NOT NULL,age_after INT NOT NULL,observed_after INT NOT NULL,"
                + "evaluated_at DATETIME NOT NULL,gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY (id),"
                + "UNIQUE KEY uk_sw_intent_test_result (observation_id, test_id),"
                + "KEY idx_sw_intent_test_result_test (test_id, observation_version)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        safeExecute("CREATE TABLE IF NOT EXISTS stock_watch_analysis_task ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,submission_key VARCHAR(255) NOT NULL,request_digest VARCHAR(64) NOT NULL,"
                + "purpose VARCHAR(64) NOT NULL,symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,period_type VARCHAR(16),"
                + "observation_key VARCHAR(128),generation INT NOT NULL DEFAULT 1,status VARCHAR(32) NOT NULL,provider VARCHAR(64),"
                + "request_envelope_json MEDIUMTEXT NOT NULL,response_hash VARCHAR(64),"
                + "response_envelope_hash VARCHAR(64),response_envelope_json MEDIUMTEXT,raw_response MEDIUMTEXT,"
                + "content_status VARCHAR(32),completion_reason VARCHAR(128),provider_status VARCHAR(64),submitted_at DATETIME,"
                + "completed_at DATETIME,error_message TEXT,row_version BIGINT NOT NULL DEFAULT 0,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,PRIMARY KEY (id),"
                + "UNIQUE KEY uk_sw_analysis_task_submission (submission_key),"
                + "KEY idx_sw_analysis_task_observation (symbol, market, observation_key),"
                + "KEY idx_sw_analysis_task_status (status, gmt_create)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        safeAddColumn("stock_watch_analysis_task", "response_envelope_hash", "VARCHAR(64)");
        safeAddColumn("stock_watch_analysis_task", "response_envelope_json", "MEDIUMTEXT");
    }

    private void ensurePriceFailureResearchSchema() {
        safeExecute("CREATE TABLE IF NOT EXISTS stock_watch_price_failure_state ("
                + "rule_version VARCHAR(96) NOT NULL,rule_spec_hash VARCHAR(64) NOT NULL,"
                + "rule_spec_json MEDIUMTEXT NOT NULL,bootstrap_observation_id BIGINT NOT NULL,"
                + "last_observation_id BIGINT NOT NULL,row_version BIGINT NOT NULL DEFAULT 0,"
                + "started_at DATETIME NOT NULL,gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (rule_version)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        safeExecute("CREATE TABLE IF NOT EXISTS stock_watch_price_failure_assessment ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,rule_version VARCHAR(96) NOT NULL,"
                + "rule_spec_hash VARCHAR(64) NOT NULL,observation_id BIGINT NOT NULL,"
                + "symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,stock_name VARCHAR(128),"
                + "source_date VARCHAR(16) NOT NULL,source_time_epoch_ms BIGINT NOT NULL,"
                + "available_time_epoch_ms BIGINT NOT NULL,evaluated_at DATETIME NOT NULL,"
                + "eligible TINYINT NOT NULL,high_position TINYINT NOT NULL,active_turnover TINYINT NOT NULL,"
                + "current_not_collapsed TINYINT NOT NULL,price_shape_triggered TINYINT NOT NULL,"
                + "flow_data_complete TINYINT NOT NULL,strong_outflow TINYINT NOT NULL,"
                + "shape_code VARCHAR(64) NOT NULL,stratum VARCHAR(64) NOT NULL,signal_price DECIMAL(18,6),"
                + "feature_snapshot_json MEDIUMTEXT NOT NULL,exclusion_reason VARCHAR(128),"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY (id),"
                + "UNIQUE KEY uk_sw_pf_assessment_observation (rule_version,observation_id),"
                + "KEY idx_sw_pf_assessment_control (rule_version,market,source_date,source_time_epoch_ms),"
                + "KEY idx_sw_pf_assessment_shape (rule_version,price_shape_triggered,source_date)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        safeExecute("CREATE TABLE IF NOT EXISTS stock_watch_price_failure_ledger ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,sample_key VARCHAR(64) NOT NULL,signal_day_key VARCHAR(192),"
                + "rule_version VARCHAR(96) NOT NULL,rule_spec_hash VARCHAR(64) NOT NULL,"
                + "sample_role VARCHAR(16) NOT NULL,matched_signal_id BIGINT,origin_assessment_id BIGINT NOT NULL,"
                + "origin_observation_id BIGINT NOT NULL,symbol VARCHAR(16) NOT NULL,market VARCHAR(8) NOT NULL,"
                + "stock_name VARCHAR(128),stratum VARCHAR(64) NOT NULL,source_date VARCHAR(16) NOT NULL,"
                + "source_time_epoch_ms BIGINT NOT NULL,available_time_epoch_ms BIGINT NOT NULL,"
                + "available_at DATETIME NOT NULL,signal_price DECIMAL(18,6),feature_snapshot_json MEDIUMTEXT NOT NULL,"
                + "match_score DECIMAL(18,8),controls_finalized TINYINT NOT NULL DEFAULT 0,"
                + "status VARCHAR(32) NOT NULL,entry_observation_id BIGINT,"
                + "entry_source_time_epoch_ms BIGINT,entry_source_date VARCHAR(16),entry_price DECIMAL(18,6),"
                + "t1_trade_date VARCHAR(16),t1_open DECIMAL(18,6),t1_high DECIMAL(18,6),"
                + "t1_low DECIMAL(18,6),t1_close DECIMAL(18,6),t1_max_drawdown_pct DECIMAL(12,6),"
                + "t1_close_return_pct DECIMAL(12,6),t1_gap_pct DECIMAL(12,6),"
                + "t1_max_upside_pct DECIMAL(12,6),t1_drawdown_5_hit TINYINT,data_quality_reason VARCHAR(128),"
                + "row_version BIGINT NOT NULL DEFAULT 0,matured_at DATETIME,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "gmt_modify DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),UNIQUE KEY uk_sw_pf_ledger_sample (sample_key),"
                + "UNIQUE KEY uk_sw_pf_ledger_signal_day (signal_day_key),"
                + "KEY idx_sw_pf_ledger_pending (rule_version,status),"
                + "KEY idx_sw_pf_ledger_controls (matched_signal_id,sample_role),"
                + "KEY idx_sw_pf_ledger_control_reconcile (rule_version,sample_role,controls_finalized,id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        safeAddColumn("stock_watch_price_failure_ledger", "controls_finalized", "TINYINT NOT NULL DEFAULT 0");
        safeExecute("CREATE INDEX idx_sw_pf_ledger_control_reconcile ON stock_watch_price_failure_ledger "
                + "(rule_version,sample_role,controls_finalized,id)");
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
