-- Additive table for per-call meeting usage. The application creates it at startup when missing.
CREATE TABLE IF NOT EXISTS `ai_meeting_call_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `meeting_id` BIGINT NOT NULL,
    `round_number` INT NULL,
    `member_id` BIGINT NULL,
    `stage` VARCHAR(32) NOT NULL,
    `channel` VARCHAR(16) NOT NULL,
    `model` VARCHAR(128) NULL,
    `status` VARCHAR(64) NULL,
    `duration_ms` BIGINT NOT NULL,
    `prompt_tokens` INT NULL,
    `completion_tokens` INT NULL,
    `cache_hit_tokens` INT NULL,
    `prompt_chars` INT NULL,
    `completion_chars` INT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_call_log_meeting` (`meeting_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议模型与审核搜索调用记账';
