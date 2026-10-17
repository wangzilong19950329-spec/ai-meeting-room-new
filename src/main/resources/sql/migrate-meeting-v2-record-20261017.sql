-- Additive table for roundtable v2 checkpoints. The application creates it at startup when missing.
CREATE TABLE IF NOT EXISTS `ai_meeting_v2_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `meeting_id` BIGINT NOT NULL,
    `kind` VARCHAR(24) NOT NULL,
    `member_id` BIGINT NOT NULL DEFAULT 0,
    `payload` MEDIUMTEXT NOT NULL,
    `run_epoch` BIGINT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_v2_record` (`meeting_id`, `kind`, `member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圆桌 v2 阶段产物检查点';
