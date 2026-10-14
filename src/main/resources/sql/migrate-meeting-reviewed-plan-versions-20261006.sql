-- One-time upgrade for installations that already created the original
-- one-plan-per-member-turn ledger. Apply with a DDL-capable MySQL account.
-- The application checks metadata before executing each step automatically.
-- The old plan rows and all accepted search packets remain in place.
ALTER TABLE `ai_meeting_reviewed_search_plan`
    ADD COLUMN `context_opinion_id` BIGINT NOT NULL DEFAULT 0;

ALTER TABLE `ai_meeting_reviewed_search_plan`
    ADD COLUMN `supersedes_plan_id` BIGINT NULL;

ALTER TABLE `ai_meeting_reviewed_search_plan`
    DROP INDEX `uk_reviewed_plan_turn`,
    ADD UNIQUE KEY `uk_reviewed_plan_turn`
        (`meeting_id`, `round_number`, `member_id`, `target_question_id`, `as_of`, `context_opinion_id`);
