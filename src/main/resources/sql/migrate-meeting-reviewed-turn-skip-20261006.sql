-- Additive, repeatable migration for member-local reviewed-turn abstentions.
CREATE TABLE IF NOT EXISTS `ai_meeting_reviewed_turn_skip` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `meeting_id` BIGINT NOT NULL,
    `round_number` INT NOT NULL,
    `member_id` BIGINT NOT NULL,
    `target_question_id` BIGINT NOT NULL DEFAULT 0,
    `reason_code` VARCHAR(128) NOT NULL,
    `run_epoch` BIGINT NULL,
    `as_of` VARCHAR(10) NOT NULL,
    `context_opinion_id` BIGINT NOT NULL DEFAULT 0,
    `plan_id` BIGINT NULL,
    `gap_id` BIGINT NULL,
    `active_turn` TINYINT NULL DEFAULT 1,
    `retry_prepared_at` DATETIME NULL,
    `retry_prepared_epoch` BIGINT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_reviewed_skip_turn` (`meeting_id`, `round_number`, `member_id`, `target_question_id`, `active_turn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核会议议员本轮弃权账本';
