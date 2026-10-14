-- Apply only when ai_meeting_room.round_summaries is absent.
-- The reviewed meeting schema initializer checks the column before running this migration.
ALTER TABLE `ai_meeting_room`
    ADD COLUMN `round_summaries` MEDIUMTEXT NULL COMMENT '逐轮摘要JSON' AFTER `decision_artifact`;
