-- AI会议室 数据库初始化脚本
-- 创建数据库
CREATE DATABASE IF NOT EXISTS ai_meeting DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_meeting;

-- 会议室
CREATE TABLE `ai_meeting_room` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`            VARCHAR(256) NOT NULL                COMMENT '会议主题',
    `description`      TEXT                                 COMMENT '会议描述',
    `creator_id`       BIGINT       NOT NULL                COMMENT '创建者ID',
    `status`           TINYINT      NOT NULL DEFAULT 0      COMMENT '0-已创建 1-进行中 2-已达成结论 3-验证有效 4-验证无效 5-已取消',
    `max_rounds`       INT          NOT NULL DEFAULT 10     COMMENT '最大辩论轮数',
    `current_round`    INT          NOT NULL DEFAULT 0      COMMENT '当前轮次',
    `final_conclusion` TEXT                                 COMMENT '最终结论',
    `conclusion_time`  DATETIME                             COMMENT '达成结论时间',
    `summary`          TEXT                                 COMMENT 'AI生成的会议总结',
    `gmt_create`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_creator_id` (`creator_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会议室';

-- 会议成员（每次会议独立的一组AI成员）
CREATE TABLE `ai_meeting_member` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `meeting_id`  BIGINT        NOT NULL                COMMENT '会议ID',
    `member_name` VARCHAR(128)  NOT NULL                COMMENT '成员名称，如"理性分析者"',
    `model_type`  VARCHAR(64)   NOT NULL                COMMENT '模型标识，如 gpt-4, claude-3-opus, qwen-max',
    `persona`     VARCHAR(1024) NOT NULL                COMMENT '人设/系统提示词',
    `role`        TINYINT       NOT NULL DEFAULT 0      COMMENT '0-参与者 1-主持人',
    `total_score` INT           NOT NULL DEFAULT 0      COMMENT '累计积分',
    `gmt_create`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_meeting_id` (`meeting_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会议成员';

-- 发言/意见记录
CREATE TABLE `ai_meeting_opinion` (
    `id`                BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `meeting_id`        BIGINT   NOT NULL                COMMENT '会议ID',
    `member_id`         BIGINT   NOT NULL                COMMENT '发言成员ID',
    `round_number`      INT      NOT NULL                COMMENT '轮次',
    `parent_opinion_id` BIGINT                           COMMENT '回复的意见ID，NULL=原始发言',
    `content`           TEXT     NOT NULL                COMMENT '发言内容',
    `evidence`          TEXT                             COMMENT '支撑论据',
    `opinion_type`      TINYINT  NOT NULL DEFAULT 0      COMMENT '0-陈述 1-反驳 2-让步',
    `gmt_create`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_meeting_round` (`meeting_id`, `round_number`),
    KEY `idx_parent` (`parent_opinion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议发言记录';

-- 投票记录
CREATE TABLE `ai_meeting_vote` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `meeting_id`    BIGINT        NOT NULL                COMMENT '会议ID',
    `member_id`     BIGINT        NOT NULL                COMMENT '投票成员ID',
    `round_number`  INT           NOT NULL                COMMENT '轮次',
    `vote_position` VARCHAR(1024) NOT NULL                COMMENT '投票立场（一句话结论）',
    `confidence`    DECIMAL(3,2)  NOT NULL DEFAULT 0.50   COMMENT '置信度 0.00-1.00',
    `reasoning`     TEXT                                  COMMENT '投票理由',
    `gmt_create`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_meeting_member_round` (`meeting_id`, `member_id`, `round_number`),
    KEY `idx_meeting_round` (`meeting_id`, `round_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议投票记录';

-- 积分变动日志
CREATE TABLE `ai_meeting_score_log` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `meeting_id`          BIGINT       NOT NULL                COMMENT '会议ID',
    `member_id`           BIGINT       NOT NULL                COMMENT '成员ID',
    `score_delta`         INT          NOT NULL                COMMENT '积分变动，正=奖励，负=扣分',
    `reason`              VARCHAR(256) NOT NULL                COMMENT '原因',
    `contribution_detail` TEXT                                 COMMENT '贡献度明细JSON',
    `gmt_create`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_meeting_id` (`meeting_id`),
    KEY `idx_member_id` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分变动记录';

-- 结论聚类（记录每轮投票的语义聚类结果）
CREATE TABLE `ai_meeting_conclusion_cluster` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `meeting_id`      BIGINT        NOT NULL                COMMENT '会议ID',
    `round_number`    INT           NOT NULL                COMMENT '轮次',
    `cluster_label`   VARCHAR(1024) NOT NULL                COMMENT '聚类标签（概括性结论）',
    `member_count`    INT           NOT NULL DEFAULT 0      COMMENT '该聚类成员数',
    `member_ids`      VARCHAR(2048) NOT NULL                COMMENT '成员ID列表，逗号分隔',
    `agreement_ratio` DECIMAL(3,2)  NOT NULL DEFAULT 0.00   COMMENT '同意比例',
    `is_consensus`    TINYINT       NOT NULL DEFAULT 0      COMMENT '1=达成共识（≥70%）',
    `gmt_create`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_meeting_round` (`meeting_id`, `round_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结论聚类';
