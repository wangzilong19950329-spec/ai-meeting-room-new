-- One-time upgrade. The application checks metadata before each equivalent DDL step.
-- Active skips remain unique. NULL active_turn preserves multiple immutable past failures.
ALTER TABLE `ai_meeting_reviewed_turn_skip`
    ADD COLUMN `active_turn` TINYINT NULL DEFAULT 1;
ALTER TABLE `ai_meeting_reviewed_turn_skip`
    ADD COLUMN `retry_prepared_at` DATETIME NULL;
ALTER TABLE `ai_meeting_reviewed_turn_skip`
    ADD COLUMN `retry_prepared_epoch` BIGINT NULL;
ALTER TABLE `ai_meeting_reviewed_turn_skip`
    DROP INDEX `uk_reviewed_skip_turn`,
    ADD UNIQUE KEY `uk_reviewed_skip_turn`
        (`meeting_id`, `round_number`, `member_id`, `target_question_id`, `active_turn`);
