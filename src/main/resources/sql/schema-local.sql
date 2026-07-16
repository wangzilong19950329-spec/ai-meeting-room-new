CREATE TABLE IF NOT EXISTS ai_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(128),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_user_username ON ai_user(username);

CREATE TABLE IF NOT EXISTS ai_meeting_room (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    description CLOB,
    project_key VARCHAR(128),
    project_context CLOB,
    inherited_context CLOB,
    creator_id BIGINT NOT NULL,
    status TINYINT DEFAULT 0 NOT NULL,
    max_rounds INT DEFAULT 10 NOT NULL,
    current_round INT DEFAULT 0 NOT NULL,
    final_conclusion CLOB,
    conclusion_time TIMESTAMP,
    summary CLOB,
    decision_artifact CLOB,
    round_summaries CLOB,
    conclusion_history CLOB,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_meeting_room_creator_id ON ai_meeting_room(creator_id);
CREATE INDEX IF NOT EXISTS idx_meeting_room_status ON ai_meeting_room(status);
CREATE INDEX IF NOT EXISTS idx_meeting_room_project_key ON ai_meeting_room(project_key);

CREATE TABLE IF NOT EXISTS ai_workflow_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_key VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description CLOB,
    layout_config CLOB,
    actor_slots_config CLOB,
    built_in TINYINT DEFAULT 0 NOT NULL,
    active TINYINT DEFAULT 1 NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_template_key ON ai_workflow_template(template_key);

CREATE TABLE IF NOT EXISTS ai_workflow_stage_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    stage_key VARCHAR(128) NOT NULL,
    stage_name VARCHAR(128) NOT NULL,
    stage_order INT NOT NULL,
    stage_type VARCHAR(64) NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    actor_binding_mode VARCHAR(64),
    actor_slot_key VARCHAR(128),
    completion_rule VARCHAR(128),
    skill_key VARCHAR(128),
    skill_prompt CLOB,
    skill_file_ids CLOB,
    search_strategy VARCHAR(32),
    input_stage_keys CLOB,
    pass_target VARCHAR(128),
    reject_target VARCHAR(128),
    reopen_target VARCHAR(128),
    auto_start TINYINT DEFAULT 0 NOT NULL,
    review_required TINYINT DEFAULT 0 NOT NULL,
    max_review_attempts INT DEFAULT 0 NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_stage_template_template_id ON ai_workflow_stage_template(template_id);

ALTER TABLE ai_workflow_stage_template ADD COLUMN IF NOT EXISTS search_strategy VARCHAR(32);
ALTER TABLE ai_workflow_stage_template ADD COLUMN IF NOT EXISTS input_stage_keys CLOB;

CREATE TABLE IF NOT EXISTS ai_skill_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name VARCHAR(512) NOT NULL,
    stored_name VARCHAR(512) NOT NULL,
    content_type VARCHAR(256),
    file_size BIGINT NOT NULL,
    storage_path CLOB NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_workflow_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_key VARCHAR(128) NOT NULL,
    current_stage_key VARCHAR(128) NOT NULL,
    current_stage_order INT NOT NULL,
    status VARCHAR(64) NOT NULL,
    blocked_reason VARCHAR(512),
    reopen_count INT DEFAULT 0 NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_run_meeting_id ON ai_workflow_run(meeting_id);

CREATE TABLE IF NOT EXISTS ai_workflow_stage_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_run_id BIGINT NOT NULL,
    meeting_id BIGINT NOT NULL,
    stage_template_id BIGINT NOT NULL,
    stage_key VARCHAR(128) NOT NULL,
    stage_name VARCHAR(128) NOT NULL,
    stage_order INT NOT NULL,
    stage_type VARCHAR(64) NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    attempt_no INT DEFAULT 1 NOT NULL,
    status VARCHAR(64) NOT NULL,
    handoff_summary CLOB,
    handoff_payload CLOB,
    outcome VARCHAR(128),
    reject_reason VARCHAR(1024),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_stage_run_workflow_run_id ON ai_workflow_stage_run(workflow_run_id);
CREATE INDEX IF NOT EXISTS idx_stage_run_meeting_stage_key ON ai_workflow_stage_run(meeting_id, stage_key);

CREATE TABLE IF NOT EXISTS ai_workflow_stage_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_run_id BIGINT NOT NULL,
    stage_run_id BIGINT NOT NULL,
    meeting_id BIGINT NOT NULL,
    stage_key VARCHAR(128) NOT NULL,
    attempt_no INT DEFAULT 1 NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(128),
    content CLOB,
    payload_json CLOB,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_stage_submission_meeting_id ON ai_workflow_stage_submission(meeting_id);
CREATE INDEX IF NOT EXISTS idx_stage_submission_stage_run_id ON ai_workflow_stage_submission(stage_run_id);

CREATE TABLE IF NOT EXISTS ai_meeting_tracking_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creator_id BIGINT,
    meeting_id BIGINT,
    workflow_run_id BIGINT,
    stage_run_id BIGINT,
    source_type VARCHAR(32) DEFAULT 'MEETING' NOT NULL,
    item_type VARCHAR(64) NOT NULL,
    title VARCHAR(512) NOT NULL,
    description CLOB,
    status VARCHAR(64) NOT NULL,
    owner_type VARCHAR(64),
    owner_ref VARCHAR(128),
    reopen_trigger TINYINT DEFAULT 0 NOT NULL,
    required_for_continue TINYINT DEFAULT 0 NOT NULL,
    reopen_policy VARCHAR(32) DEFAULT 'SUGGEST',
    auto_refresh_enabled TINYINT DEFAULT 1 NOT NULL,
    refresh_mode VARCHAR(32) DEFAULT 'DAILY',
    refresh_interval_hours INT DEFAULT 24,
    watch_query VARCHAR(1024),
    watch_scope VARCHAR(128),
    next_check_at TIMESTAMP,
    last_checked_at TIMESTAMP,
    last_check_status VARCHAR(64),
    last_check_note CLOB,
    last_check_fingerprint VARCHAR(128),
    change_severity VARCHAR(32),
    due_at TIMESTAMP,
    last_update_at TIMESTAMP,
    closed_at TIMESTAMP,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tracking_item_meeting_id ON ai_meeting_tracking_item(meeting_id);
CREATE INDEX IF NOT EXISTS idx_tracking_item_status ON ai_meeting_tracking_item(status);
CREATE INDEX IF NOT EXISTS idx_tracking_item_next_check ON ai_meeting_tracking_item(next_check_at);

CREATE TABLE IF NOT EXISTS ai_meeting_tracking_update (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tracking_item_id BIGINT NOT NULL,
    meeting_id BIGINT,
    update_type VARCHAR(64) NOT NULL,
    status_before VARCHAR(64),
    status_after VARCHAR(64),
    content CLOB,
    evidence_ref VARCHAR(1024),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tracking_update_item_id ON ai_meeting_tracking_update(tracking_item_id);

CREATE TABLE IF NOT EXISTS ai_meeting_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    member_name VARCHAR(128) NOT NULL,
    model_type VARCHAR(64) NOT NULL,
    persona VARCHAR(2048) NOT NULL,
    actor_slot_key VARCHAR(128),
    actor_slot_name VARCHAR(128),
    role TINYINT DEFAULT 0 NOT NULL,
    total_score INT DEFAULT 0 NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_meeting_member_meeting_id ON ai_meeting_member(meeting_id);

CREATE TABLE IF NOT EXISTS ai_meeting_opinion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    round_number INT NOT NULL,
    parent_opinion_id BIGINT,
    content CLOB NOT NULL,
    evidence CLOB,
    opinion_type TINYINT DEFAULT 0 NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_meeting_opinion_meeting_round ON ai_meeting_opinion(meeting_id, round_number);
CREATE INDEX IF NOT EXISTS idx_meeting_opinion_parent ON ai_meeting_opinion(parent_opinion_id);

CREATE TABLE IF NOT EXISTS ai_meeting_vote (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    round_number INT NOT NULL,
    vote_position VARCHAR(1024) NOT NULL,
    confidence DECIMAL(3, 2) DEFAULT 0.50 NOT NULL,
    reasoning CLOB,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_meeting_vote_meeting_member_round
    ON ai_meeting_vote(meeting_id, member_id, round_number);
CREATE INDEX IF NOT EXISTS idx_meeting_vote_meeting_round ON ai_meeting_vote(meeting_id, round_number);

CREATE TABLE IF NOT EXISTS ai_meeting_score_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    score_delta INT NOT NULL,
    reason VARCHAR(256) NOT NULL,
    contribution_detail CLOB,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_score_log_meeting_id ON ai_meeting_score_log(meeting_id);
CREATE INDEX IF NOT EXISTS idx_score_log_member_id ON ai_meeting_score_log(member_id);

CREATE TABLE IF NOT EXISTS ai_meeting_conclusion_cluster (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    round_number INT NOT NULL,
    cluster_label VARCHAR(1024) NOT NULL,
    member_count INT DEFAULT 0 NOT NULL,
    member_ids VARCHAR(2048) NOT NULL,
    agreement_ratio DECIMAL(3, 2) DEFAULT 0.00 NOT NULL,
    is_consensus TINYINT DEFAULT 0 NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cluster_meeting_round ON ai_meeting_conclusion_cluster(meeting_id, round_number);

CREATE TABLE IF NOT EXISTS ai_member_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    persona CLOB,
    skills VARCHAR(1024),
    personality VARCHAR(1024),
    model_type VARCHAR(64),
    tags VARCHAR(1024),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_member_template_gmt_create ON ai_member_template(gmt_create);

CREATE TABLE IF NOT EXISTS stock_watch_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    stock_name VARCHAR(128),
    image_url CLOB,
    enabled TINYINT DEFAULT 1 NOT NULL,
    source VARCHAR(32) DEFAULT 'manual' NOT NULL,
    group_key VARCHAR(32) DEFAULT 'watch' NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_item_symbol ON stock_watch_item(symbol, market);
CREATE INDEX IF NOT EXISTS idx_sw_item_enabled ON stock_watch_item(enabled, symbol, market);
CREATE INDEX IF NOT EXISTS idx_sw_item_group ON stock_watch_item(group_key, enabled, symbol, market);

CREATE TABLE IF NOT EXISTS stock_watch_technical_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    stock_name VARCHAR(128),
    period_type VARCHAR(16) DEFAULT 'DAY' NOT NULL,
    session_key VARCHAR(128) NOT NULL,
    bridge_target_id VARCHAR(128),
    conversation_url VARCHAR(1024),
    status VARCHAR(32) DEFAULT 'ACTIVE' NOT NULL,
    generation INT DEFAULT 1 NOT NULL,
    context_chars INT DEFAULT 0 NOT NULL,
    context_limit_chars INT DEFAULT 80000 NOT NULL,
    last_run_at TIMESTAMP,
    last_run_status VARCHAR(64),
    last_error CLOB,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sw_tech_session_symbol ON stock_watch_technical_session(symbol, market, period_type, status);
CREATE INDEX IF NOT EXISTS idx_sw_tech_session_key ON stock_watch_technical_session(session_key);

CREATE TABLE IF NOT EXISTS stock_watch_technical_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    analysis_task_id BIGINT,
    session_id BIGINT,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    stock_name VARCHAR(128),
    analysis_date DATE NOT NULL,
    run_slot VARCHAR(16) NOT NULL,
    period_type VARCHAR(16) DEFAULT 'DAY' NOT NULL,
    period_key VARCHAR(32),
    source_record_ids CLOB,
    evidence_signal_json CLOB,
    main_force_intent_json CLOB,
    main_force_intent_status VARCHAR(32),
    main_force_intent_warnings_json CLOB,
    status VARCHAR(32) NOT NULL,
    stance VARCHAR(64),
    tone VARCHAR(32),
    confidence DECIMAL(5, 4),
    title VARCHAR(256),
    conclusion CLOB,
    evidence_json CLOB,
    missing_data_json CLOB,
    data_snapshot_json CLOB,
    raw_response CLOB,
    prompt_hash VARCHAR(128),
    provider VARCHAR(64),
    bridge_target_id VARCHAR(128),
    conversation_url VARCHAR(1024),
    context_chars_delta INT DEFAULT 0,
    error_message CLOB,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_tech_analysis_task ON stock_watch_technical_analysis(analysis_task_id);
CREATE INDEX IF NOT EXISTS idx_sw_tech_analysis_symbol_date ON stock_watch_technical_analysis(symbol, market, period_type, analysis_date);
CREATE INDEX IF NOT EXISTS idx_sw_tech_analysis_session ON stock_watch_technical_analysis(session_id);
CREATE INDEX IF NOT EXISTS idx_sw_tech_analysis_slot ON stock_watch_technical_analysis(symbol, market, period_type, period_key, analysis_date, run_slot);
CREATE INDEX IF NOT EXISTS idx_sw_tech_analysis_period ON stock_watch_technical_analysis(symbol, market, period_type, period_key);

CREATE TABLE IF NOT EXISTS stock_watch_prediction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    analysis_id BIGINT NOT NULL,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    stock_name VARCHAR(128),
    horizon_code VARCHAR(16) NOT NULL,
    horizon_trading_days INT NOT NULL,
    forecast_date DATE NOT NULL,
    source_price DECIMAL(18,4) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    probability DECIMAL(6,5),
    target_low DECIMAL(18,4),
    target_high DECIMAL(18,4),
    expected_return_low DECIMAL(12,6),
    expected_return_high DECIMAL(12,6),
    invalidation_price DECIMAL(18,4),
    rationale CLOB,
    input_snapshot_json CLOB,
    status VARCHAR(24) DEFAULT 'PENDING' NOT NULL,
    evaluated_trade_date DATE,
    actual_close DECIMAL(18,4),
    actual_high DECIMAL(18,4),
    actual_low DECIMAL(18,4),
    actual_return_pct DECIMAL(12,6),
    direction_hit BOOLEAN,
    range_close_hit BOOLEAN,
    range_touch_hit BOOLEAN,
    invalidated BOOLEAN,
    absolute_error_pct DECIMAL(12,6),
    brier_score DECIMAL(12,8),
    evaluation_note CLOB,
    evaluated_at TIMESTAMP,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_sw_prediction_analysis_horizon UNIQUE (analysis_id, horizon_code)
);
CREATE INDEX IF NOT EXISTS idx_sw_prediction_symbol_date ON stock_watch_prediction(symbol, market, forecast_date);
CREATE INDEX IF NOT EXISTS idx_sw_prediction_status ON stock_watch_prediction(status, forecast_date);

CREATE TABLE IF NOT EXISTS stock_watch_fact_head (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    arrival_version BIGINT DEFAULT 0 NOT NULL,
    latest_observation_id BIGINT,
    last_source_time_epoch_ms BIGINT,
    last_source_date VARCHAR(16),
    last_source_quality VARCHAR(32),
    last_fact_hash VARCHAR(64),
    row_version BIGINT DEFAULT 0 NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_sw_fact_head_symbol UNIQUE (symbol, market)
);

CREATE TABLE IF NOT EXISTS stock_watch_fact_observation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    stock_name VARCHAR(128),
    observation_key VARCHAR(64) NOT NULL,
    arrival_version BIGINT NOT NULL,
    source_time_epoch_ms BIGINT NOT NULL,
    source_date VARCHAR(16) NOT NULL,
    source_time_quality VARCHAR(32) NOT NULL,
    fact_hash VARCHAR(64) NOT NULL,
    fact_snapshot_json CLOB NOT NULL,
    data_quality_json CLOB,
    sampled_at TIMESTAMP NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_sw_fact_observation_key UNIQUE (observation_key),
    CONSTRAINT uk_sw_fact_observation_version UNIQUE (symbol, market, arrival_version)
);
CREATE INDEX IF NOT EXISTS idx_sw_fact_observation_source ON stock_watch_fact_observation(symbol, market, source_time_epoch_ms);

CREATE TABLE IF NOT EXISTS stock_watch_intent_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    origin_observation_id BIGINT NOT NULL,
    activation_after_observation_id BIGINT NOT NULL,
    test_key VARCHAR(160) NOT NULL,
    claim_ref VARCHAR(64) NOT NULL,
    metric_path VARCHAR(128) NOT NULL,
    comparison_operator VARCHAR(32) NOT NULL,
    target_value_json CLOB NOT NULL,
    max_observation_age INT NOT NULL,
    required_observed_count INT NOT NULL,
    age_count INT DEFAULT 0 NOT NULL,
    observed_count INT DEFAULT 0 NOT NULL,
    test_verdict VARCHAR(32) DEFAULT 'UNRESOLVED' NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    last_evaluated_observation_id BIGINT,
    last_evaluated_source_date VARCHAR(16),
    resolved_observation_id BIGINT,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_sw_intent_test_origin_key UNIQUE (origin_observation_id, test_key)
);
CREATE INDEX IF NOT EXISTS idx_sw_intent_test_active ON stock_watch_intent_test(symbol, market, resolved_observation_id);

CREATE TABLE IF NOT EXISTS stock_watch_intent_test_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    observation_id BIGINT NOT NULL,
    observation_version BIGINT NOT NULL,
    test_id BIGINT NOT NULL,
    actual_value_json CLOB,
    match_status VARCHAR(32) NOT NULL,
    verdict_after VARCHAR(32) NOT NULL,
    age_after INT NOT NULL,
    observed_after INT NOT NULL,
    evaluated_at TIMESTAMP NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_sw_intent_test_result UNIQUE (observation_id, test_id)
);
CREATE INDEX IF NOT EXISTS idx_sw_intent_test_result_test ON stock_watch_intent_test_result(test_id, observation_version);

CREATE TABLE IF NOT EXISTS stock_watch_analysis_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_key VARCHAR(255) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    period_type VARCHAR(16),
    observation_key VARCHAR(128),
    generation INT DEFAULT 1 NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider VARCHAR(64),
    request_envelope_json CLOB NOT NULL,
    response_hash VARCHAR(64),
    response_envelope_hash VARCHAR(64),
    response_envelope_json CLOB,
    raw_response CLOB,
    content_status VARCHAR(32),
    completion_reason VARCHAR(128),
    provider_status VARCHAR(64),
    submitted_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message CLOB,
    row_version BIGINT DEFAULT 0 NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_sw_analysis_task_submission UNIQUE (submission_key)
);
CREATE INDEX IF NOT EXISTS idx_sw_analysis_task_observation ON stock_watch_analysis_task(symbol, market, observation_key);
CREATE INDEX IF NOT EXISTS idx_sw_analysis_task_status ON stock_watch_analysis_task(status, gmt_create);

CREATE TABLE IF NOT EXISTS stock_watch_delivery_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    stock_name VARCHAR(128),
    enabled TINYINT DEFAULT 0 NOT NULL,
    recipients_json CLOB NOT NULL,
    sender_mode VARCHAR(32) DEFAULT 'SYSTEM' NOT NULL,
    frequency_type VARCHAR(32) DEFAULT 'MANUAL' NOT NULL,
    interval_minutes INT DEFAULT 120 NOT NULL,
    trigger_policy_json CLOB,
    active_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_dispatch_status VARCHAR(32),
    last_dispatch_message CLOB,
    last_dispatch_at TIMESTAMP,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_delivery_config_symbol ON stock_watch_delivery_config(symbol, market);
CREATE INDEX IF NOT EXISTS idx_sw_delivery_config_enabled ON stock_watch_delivery_config(enabled, frequency_type);

CREATE TABLE IF NOT EXISTS stock_watch_delivery_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_id BIGINT NOT NULL,
    analysis_id BIGINT NOT NULL,
    symbol VARCHAR(16) NOT NULL,
    market VARCHAR(8) NOT NULL,
    recipient_hash VARCHAR(64) NOT NULL,
    recipient_masked VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message_id VARCHAR(255),
    error_message CLOB,
    sent_at TIMESTAMP,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_delivery_once ON stock_watch_delivery_log(analysis_id, recipient_hash);
CREATE INDEX IF NOT EXISTS idx_sw_delivery_config_status ON stock_watch_delivery_log(config_id, status);
CREATE INDEX IF NOT EXISTS idx_sw_delivery_symbol_time ON stock_watch_delivery_log(symbol, market, gmt_create);
