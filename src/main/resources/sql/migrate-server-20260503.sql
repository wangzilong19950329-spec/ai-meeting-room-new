-- 线上旧库升级到当前代码所需的最小结构迁移
-- 目标环境：MariaDB 10.5.x / MySQL 8.x
-- 用途：让当前代码可直接读取旧版 ai_meeting 库中的会议数据

USE ai_meeting;

ALTER TABLE `ai_meeting_room`
    ADD COLUMN IF NOT EXISTS `project_key` VARCHAR(128) NULL COMMENT '项目标识/工作区键' AFTER `description`,
    ADD COLUMN IF NOT EXISTS `project_context` TEXT NULL COMMENT '项目背景与共享上下文' AFTER `project_key`,
    ADD COLUMN IF NOT EXISTS `inherited_context` TEXT NULL COMMENT '从同项目历史会议继承的上下文' AFTER `project_context`,
    ADD COLUMN IF NOT EXISTS `decision_artifact` TEXT NULL COMMENT '结构化决策卡片JSON' AFTER `summary`,
    ADD COLUMN IF NOT EXISTS `conclusion_history` TEXT NULL COMMENT '结论版本历史JSON' AFTER `decision_artifact`;

ALTER TABLE `ai_meeting_room`
    ADD INDEX IF NOT EXISTS `idx_project_key` (`project_key`);

ALTER TABLE `ai_meeting_member`
    ADD COLUMN IF NOT EXISTS `actor_slot_key` VARCHAR(128) NULL COMMENT '成员绑定的执行槽位标识' AFTER `persona`,
    ADD COLUMN IF NOT EXISTS `actor_slot_name` VARCHAR(128) NULL COMMENT '成员绑定的执行槽位名称' AFTER `actor_slot_key`;

-- 与当前代码约定保持一致：6 = 结论修订中
ALTER TABLE `ai_meeting_room`
    MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-已创建 1-进行中 2-已达成结论 3-验证有效 4-验证无效 5-已取消 6-结论修订中';

-- 与当前代码约定保持一致：3 = 观察员补充 4 = 主持人指令
ALTER TABLE `ai_meeting_opinion`
    MODIFY COLUMN `opinion_type` TINYINT NOT NULL DEFAULT 0 COMMENT '0-陈述 1-反驳 2-让步 3-人类补充 4-主持人指令';

CREATE TABLE IF NOT EXISTS `ai_workflow_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `template_key` VARCHAR(128) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `description` TEXT NULL,
    `layout_config` TEXT NULL,
    `actor_slots_config` TEXT NULL,
    `built_in` TINYINT NOT NULL DEFAULT 0,
    `active` TINYINT NOT NULL DEFAULT 1,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_template_key` (`template_key`)
);

ALTER TABLE `ai_workflow_template`
    ADD COLUMN IF NOT EXISTS `layout_config` TEXT NULL COMMENT '画布布局配置' AFTER `description`,
    ADD COLUMN IF NOT EXISTS `actor_slots_config` TEXT NULL COMMENT '执行槽位配置' AFTER `layout_config`;

CREATE TABLE IF NOT EXISTS `ai_workflow_stage_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `template_id` BIGINT NOT NULL,
    `stage_key` VARCHAR(128) NOT NULL,
    `stage_name` VARCHAR(128) NOT NULL,
    `stage_order` INT NOT NULL,
    `stage_type` VARCHAR(64) NOT NULL,
    `actor_type` VARCHAR(64) NOT NULL,
    `actor_binding_mode` VARCHAR(64) NULL,
    `actor_slot_key` VARCHAR(128) NULL,
    `completion_rule` VARCHAR(128) NULL,
    `skill_key` VARCHAR(128) NULL,
    `skill_prompt` TEXT NULL,
    `skill_file_ids` TEXT NULL,
    `pass_target` VARCHAR(128) NULL,
    `reject_target` VARCHAR(128) NULL,
    `reopen_target` VARCHAR(128) NULL,
    `auto_start` TINYINT NOT NULL DEFAULT 0,
    `review_required` TINYINT NOT NULL DEFAULT 0,
    `max_review_attempts` INT NOT NULL DEFAULT 0,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_template_id` (`template_id`)
);

ALTER TABLE `ai_workflow_stage_template`
    ADD COLUMN IF NOT EXISTS `actor_binding_mode` VARCHAR(64) NULL COMMENT '节点执行绑定方式' AFTER `actor_type`,
    ADD COLUMN IF NOT EXISTS `actor_slot_key` VARCHAR(128) NULL COMMENT '节点绑定槽位标识' AFTER `actor_binding_mode`,
    ADD COLUMN IF NOT EXISTS `skill_key` VARCHAR(128) NULL COMMENT '节点执行skill标识' AFTER `completion_rule`,
    ADD COLUMN IF NOT EXISTS `skill_prompt` TEXT NULL COMMENT '节点skill补充指令' AFTER `skill_key`,
    ADD COLUMN IF NOT EXISTS `skill_file_ids` TEXT NULL COMMENT '节点绑定的skill文件ID列表JSON' AFTER `skill_prompt`,
    ADD COLUMN IF NOT EXISTS `search_strategy` VARCHAR(32) NULL COMMENT '联网搜索策略: OFF/OPTIONAL/REQUIRED' AFTER `skill_file_ids`,
    ADD COLUMN IF NOT EXISTS `max_review_attempts` INT NOT NULL DEFAULT 0 COMMENT '审核节点最大打回次数，0=不限制' AFTER `review_required`;

CREATE TABLE IF NOT EXISTS `ai_skill_file` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `file_name` VARCHAR(512) NOT NULL,
    `stored_name` VARCHAR(512) NOT NULL,
    `content_type` VARCHAR(256) NULL,
    `file_size` BIGINT NOT NULL,
    `storage_path` TEXT NOT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `ai_workflow_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `meeting_id` BIGINT NOT NULL,
    `template_id` BIGINT NOT NULL,
    `template_key` VARCHAR(128) NOT NULL,
    `current_stage_key` VARCHAR(128) NOT NULL,
    `current_stage_order` INT NOT NULL,
    `status` VARCHAR(64) NOT NULL,
    `blocked_reason` VARCHAR(512) NULL,
    `reopen_count` INT NOT NULL DEFAULT 0,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_meeting_id` (`meeting_id`)
);

CREATE TABLE IF NOT EXISTS `ai_workflow_stage_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_run_id` BIGINT NOT NULL,
    `meeting_id` BIGINT NOT NULL,
    `stage_template_id` BIGINT NOT NULL,
    `stage_key` VARCHAR(128) NOT NULL,
    `stage_name` VARCHAR(128) NOT NULL,
    `stage_order` INT NOT NULL,
    `stage_type` VARCHAR(64) NOT NULL,
    `actor_type` VARCHAR(64) NOT NULL,
    `attempt_no` INT NOT NULL DEFAULT 1,
    `status` VARCHAR(64) NOT NULL,
    `handoff_summary` TEXT NULL,
    `handoff_payload` TEXT NULL,
    `outcome` VARCHAR(128) NULL,
    `reject_reason` VARCHAR(1024) NULL,
    `started_at` DATETIME NULL,
    `completed_at` DATETIME NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_workflow_run_id` (`workflow_run_id`),
    KEY `idx_meeting_stage_key` (`meeting_id`, `stage_key`)
);

ALTER TABLE `ai_workflow_stage_run`
    ADD COLUMN IF NOT EXISTS `handoff_summary` TEXT NULL AFTER `status`;
ALTER TABLE `ai_workflow_stage_run`
    ADD COLUMN IF NOT EXISTS `handoff_payload` TEXT NULL AFTER `handoff_summary`;

CREATE TABLE IF NOT EXISTS `ai_workflow_stage_submission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_run_id` BIGINT NOT NULL,
    `stage_run_id` BIGINT NOT NULL,
    `meeting_id` BIGINT NOT NULL,
    `stage_key` VARCHAR(128) NOT NULL,
    `attempt_no` INT NOT NULL DEFAULT 1,
    `action_type` VARCHAR(64) NOT NULL,
    `actor_ref` VARCHAR(128) NULL,
    `content` TEXT NULL,
    `payload_json` TEXT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_meeting_id` (`meeting_id`),
    KEY `idx_stage_run_id` (`stage_run_id`)
);

CREATE TABLE IF NOT EXISTS `ai_meeting_tracking_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `meeting_id` BIGINT NOT NULL,
    `workflow_run_id` BIGINT NULL,
    `stage_run_id` BIGINT NULL,
    `item_type` VARCHAR(64) NOT NULL,
    `title` VARCHAR(512) NOT NULL,
    `description` TEXT NULL,
    `status` VARCHAR(64) NOT NULL,
    `owner_type` VARCHAR(64) NULL,
    `owner_ref` VARCHAR(128) NULL,
    `reopen_trigger` TINYINT NOT NULL DEFAULT 0,
    `required_for_continue` TINYINT NOT NULL DEFAULT 0,
    `due_at` DATETIME NULL,
    `last_update_at` DATETIME NULL,
    `closed_at` DATETIME NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_meeting_id` (`meeting_id`),
    KEY `idx_status` (`status`)
);

CREATE TABLE IF NOT EXISTS `ai_meeting_tracking_update` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tracking_item_id` BIGINT NOT NULL,
    `meeting_id` BIGINT NOT NULL,
    `update_type` VARCHAR(64) NOT NULL,
    `status_before` VARCHAR(64) NULL,
    `status_after` VARCHAR(64) NULL,
    `content` TEXT NULL,
    `evidence_ref` VARCHAR(1024) NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tracking_item_id` (`tracking_item_id`)
);

-- 迁移后自检
DESC `ai_meeting_room`;
SHOW INDEX FROM `ai_meeting_room`;
DESC `ai_workflow_run`;
DESC `ai_meeting_tracking_item`;
