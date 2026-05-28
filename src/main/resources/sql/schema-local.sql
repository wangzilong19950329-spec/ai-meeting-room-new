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
