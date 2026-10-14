-- Additive migration for existing MySQL deployments. The schema initializer also
-- runs these CREATE TABLE IF NOT EXISTS statements at startup for this revision.
CREATE TABLE IF NOT EXISTS `ai_meeting_reviewed_search_plan` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `meeting_id` BIGINT NOT NULL,
    `round_number` INT NOT NULL,
    `member_id` BIGINT NOT NULL,
    `target_question_id` BIGINT NOT NULL DEFAULT 0,
    `as_of` VARCHAR(10) NOT NULL,
    `context_opinion_id` BIGINT NOT NULL DEFAULT 0,
    `supersedes_plan_id` BIGINT NULL,
    `normalized_plan_json` MEDIUMTEXT NOT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_reviewed_plan_turn` (`meeting_id`, `round_number`, `member_id`,
        `target_question_id`, `as_of`, `context_opinion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核搜索议员资料计划';

CREATE TABLE IF NOT EXISTS `ai_meeting_reviewed_search_gap` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `meeting_id` BIGINT NOT NULL,
    `round_number` INT NOT NULL,
    `as_of` VARCHAR(10) NOT NULL,
    `question_hash` CHAR(64) NOT NULL,
    `exact_question` MEDIUMTEXT NOT NULL,
    `request_id` VARCHAR(96) NOT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    `packet_json` MEDIUMTEXT,
    `status_reason` VARCHAR(128),
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_reviewed_gap_identity` (`meeting_id`, `round_number`, `as_of`, `question_hash`),
    UNIQUE KEY `uk_reviewed_gap_request` (`request_id`),
    KEY `idx_reviewed_gap_meeting_status` (`meeting_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核搜索缺口单次提交账本';
