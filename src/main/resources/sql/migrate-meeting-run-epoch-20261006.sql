-- Applied by MeetingReviewedSearchLedgerSchemaInitializer only when run_epoch
-- is missing, using JDBC metadata to remain compatible with older MySQL.
ALTER TABLE `ai_meeting_room`
    ADD COLUMN `run_epoch` BIGINT NOT NULL DEFAULT 0 COMMENT '审核会议异步任务代次';
