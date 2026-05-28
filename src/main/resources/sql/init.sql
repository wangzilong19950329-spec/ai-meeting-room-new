-- AI会议室 数据库初始化脚本
-- 创建数据库
CREATE DATABASE IF NOT EXISTS ai_meeting DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_meeting;

-- 会议室
CREATE TABLE `ai_meeting_room` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`            VARCHAR(256) NOT NULL                COMMENT '会议主题',
    `description`      TEXT                                 COMMENT '会议描述',
    `project_key`      VARCHAR(128)                         COMMENT '项目标识/工作区键',
    `project_context`  TEXT                                 COMMENT '项目背景与共享上下文',
    `inherited_context` TEXT                                COMMENT '从同项目历史会议继承的上下文',
    `creator_id`       BIGINT       NOT NULL                COMMENT '创建者ID',
    `status`           TINYINT      NOT NULL DEFAULT 0      COMMENT '0-已创建 1-进行中 2-已达成结论 3-验证有效 4-验证无效 5-已取消',
    `max_rounds`       INT          NOT NULL DEFAULT 10     COMMENT '最大辩论轮数',
    `current_round`    INT          NOT NULL DEFAULT 0      COMMENT '当前轮次',
    `final_conclusion` TEXT                                 COMMENT '最终结论',
    `conclusion_time`  DATETIME                             COMMENT '达成结论时间',
    `summary`          TEXT                                 COMMENT 'AI生成的会议总结',
    `decision_artifact` TEXT                                COMMENT '结构化决策卡片JSON',
    `conclusion_history` TEXT                               COMMENT '结论版本历史JSON',
    `gmt_create`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_creator_id` (`creator_id`),
    KEY `idx_status` (`status`),
    KEY `idx_project_key` (`project_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会议室';

CREATE TABLE `ai_workflow_template` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_key` VARCHAR(128) NOT NULL                COMMENT '模板唯一标识',
    `name`         VARCHAR(128) NOT NULL                COMMENT '模板名称',
    `description`  TEXT                                 COMMENT '模板描述',
    `layout_config` TEXT                                COMMENT '画布布局配置',
    `actor_slots_config` TEXT                           COMMENT '执行槽位配置',
    `built_in`     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=系统内置',
    `active`       TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用',
    `gmt_create`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_template_key` (`template_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议工作流模板';

CREATE TABLE `ai_workflow_stage_template` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_id`     BIGINT       NOT NULL                COMMENT '模板ID',
    `stage_key`       VARCHAR(128) NOT NULL                COMMENT '阶段唯一标识',
    `stage_name`      VARCHAR(128) NOT NULL                COMMENT '阶段名称',
    `stage_order`     INT          NOT NULL                COMMENT '阶段顺序',
    `stage_type`      VARCHAR(64)  NOT NULL                COMMENT '阶段类型',
    `actor_type`      VARCHAR(64)  NOT NULL                COMMENT '执行者类型',
    `actor_binding_mode` VARCHAR(64)                     COMMENT '节点执行绑定方式',
    `actor_slot_key`  VARCHAR(128)                        COMMENT '节点绑定槽位标识',
    `completion_rule` VARCHAR(128)                          COMMENT '完成规则',
    `skill_key`       VARCHAR(128)                          COMMENT '节点执行skill标识',
    `skill_prompt`    TEXT                                  COMMENT '节点skill补充指令',
    `skill_file_ids`  TEXT                                  COMMENT '节点绑定的skill文件ID列表JSON',
    `search_strategy` VARCHAR(32)                           COMMENT '联网搜索策略: OFF/OPTIONAL/REQUIRED',
    `pass_target`     VARCHAR(128)                          COMMENT '通过后目标阶段',
    `reject_target`   VARCHAR(128)                          COMMENT '打回目标阶段',
    `reopen_target`   VARCHAR(128)                          COMMENT '重开目标阶段',
    `auto_start`      TINYINT      NOT NULL DEFAULT 0      COMMENT '1=自动激活',
    `review_required` TINYINT      NOT NULL DEFAULT 0      COMMENT '1=必须人工审核，0=可跳过审核',
    `max_review_attempts` INT      NOT NULL DEFAULT 0      COMMENT '审核节点最大打回次数，0=不限制',
    `gmt_create`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_template_id` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流阶段模板';

CREATE TABLE `ai_skill_file` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_name`    VARCHAR(512) NOT NULL                COMMENT '原始文件名',
    `stored_name`  VARCHAR(512) NOT NULL                COMMENT '存储文件名',
    `content_type` VARCHAR(256)                         COMMENT 'MIME 类型',
    `file_size`    BIGINT       NOT NULL                COMMENT '文件大小',
    `storage_path` TEXT         NOT NULL                COMMENT '磁盘存储路径',
    `gmt_create`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 文件库';

CREATE TABLE `ai_workflow_run` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `meeting_id`          BIGINT       NOT NULL                COMMENT '会议ID',
    `template_id`         BIGINT       NOT NULL                COMMENT '模板ID',
    `template_key`        VARCHAR(128) NOT NULL                COMMENT '模板标识',
    `current_stage_key`   VARCHAR(128) NOT NULL                COMMENT '当前阶段key',
    `current_stage_order` INT          NOT NULL                COMMENT '当前阶段顺序',
    `status`              VARCHAR(64)  NOT NULL                COMMENT '运行状态',
    `blocked_reason`      VARCHAR(512)                         COMMENT '阻塞原因',
    `reopen_count`        INT          NOT NULL DEFAULT 0      COMMENT '重开次数',
    `gmt_create`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_meeting_id` (`meeting_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议工作流运行实例';

CREATE TABLE `ai_workflow_stage_run` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `workflow_run_id`  BIGINT       NOT NULL                COMMENT '工作流运行ID',
    `meeting_id`       BIGINT       NOT NULL                COMMENT '会议ID',
    `stage_template_id` BIGINT      NOT NULL                COMMENT '阶段模板ID',
    `stage_key`        VARCHAR(128) NOT NULL                COMMENT '阶段key',
    `stage_name`       VARCHAR(128) NOT NULL                COMMENT '阶段名称',
    `stage_order`      INT          NOT NULL                COMMENT '阶段顺序',
    `stage_type`       VARCHAR(64)  NOT NULL                COMMENT '阶段类型',
    `actor_type`       VARCHAR(64)  NOT NULL                COMMENT '执行者类型',
    `attempt_no`       INT          NOT NULL DEFAULT 1      COMMENT '尝试次数',
    `status`           VARCHAR(64)  NOT NULL                COMMENT '阶段状态',
    `handoff_summary`  TEXT                                  COMMENT '上一节点交付物摘要',
    `handoff_payload`  TEXT                                  COMMENT '上一节点交付物结构化数据',
    `outcome`          VARCHAR(128)                          COMMENT '阶段结果',
    `reject_reason`    VARCHAR(1024)                         COMMENT '打回原因',
    `started_at`       DATETIME                              COMMENT '开始时间',
    `completed_at`     DATETIME                              COMMENT '完成时间',
    `gmt_create`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_workflow_run_id` (`workflow_run_id`),
    KEY `idx_meeting_stage_key` (`meeting_id`, `stage_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流阶段运行实例';

CREATE TABLE `ai_workflow_stage_submission` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `workflow_run_id` BIGINT       NOT NULL                COMMENT '工作流运行ID',
    `stage_run_id`    BIGINT       NOT NULL                COMMENT '阶段运行ID',
    `meeting_id`      BIGINT       NOT NULL                COMMENT '会议ID',
    `stage_key`       VARCHAR(128) NOT NULL                COMMENT '阶段标识',
    `attempt_no`      INT          NOT NULL DEFAULT 1      COMMENT '阶段尝试次数',
    `action_type`     VARCHAR(64)  NOT NULL                COMMENT '动作类型，如SUBMIT/APPROVE/REJECT/BYPASS',
    `actor_ref`       VARCHAR(128)                          COMMENT '执行者标识',
    `content`         TEXT                                  COMMENT '提交/审核内容',
    `payload_json`    TEXT                                  COMMENT '扩展载荷',
    `gmt_create`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_meeting_id` (`meeting_id`),
    KEY `idx_stage_run_id` (`stage_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阶段提交与审核记录';

CREATE TABLE `ai_meeting_tracking_item` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `creator_id`           BIGINT                                COMMENT '创建者ID',
    `meeting_id`           BIGINT                                COMMENT '会议ID，可为空表示自定义追踪',
    `workflow_run_id`      BIGINT                                COMMENT '工作流运行ID',
    `stage_run_id`         BIGINT                                COMMENT '来源阶段运行ID',
    `source_type`          VARCHAR(32)  NOT NULL DEFAULT 'MEETING' COMMENT '来源类型：MEETING/CUSTOM',
    `item_type`            VARCHAR(64)  NOT NULL                COMMENT '追踪项类型',
    `title`                VARCHAR(512) NOT NULL                COMMENT '标题',
    `description`          TEXT                                  COMMENT '描述',
    `status`               VARCHAR(64)  NOT NULL                COMMENT '状态',
    `owner_type`           VARCHAR(64)                           COMMENT '责任方类型',
    `owner_ref`            VARCHAR(128)                          COMMENT '责任方引用',
    `reopen_trigger`       TINYINT      NOT NULL DEFAULT 0      COMMENT '1=完成后触发重开',
    `required_for_continue` TINYINT     NOT NULL DEFAULT 0      COMMENT '1=继续会议前必须完成',
    `reopen_policy`        VARCHAR(32)  DEFAULT 'SUGGEST'       COMMENT '重开策略：MANUAL/SUGGEST/AUTO',
    `auto_refresh_enabled` TINYINT      NOT NULL DEFAULT 1      COMMENT '1=允许自动刷新',
    `refresh_mode`         VARCHAR(32)  DEFAULT 'DAILY'         COMMENT '刷新模式：MANUAL/DAILY/INTERVAL',
    `refresh_interval_hours` INT        DEFAULT 24              COMMENT '刷新间隔小时',
    `watch_query`          VARCHAR(1024)                         COMMENT '跟踪查询语句',
    `watch_scope`          VARCHAR(128)                          COMMENT '跟踪范围标签',
    `next_check_at`        DATETIME                              COMMENT '下次刷新时间',
    `last_checked_at`      DATETIME                              COMMENT '最近刷新时间',
    `last_check_status`    VARCHAR(64)                           COMMENT '最近刷新状态',
    `last_check_note`      TEXT                                  COMMENT '最近刷新摘要',
    `last_check_fingerprint` VARCHAR(128)                        COMMENT '最近刷新指纹',
    `change_severity`      VARCHAR(32)                           COMMENT '变化等级：NONE/MINOR/MATERIAL',
    `due_at`               DATETIME                              COMMENT '截止时间',
    `last_update_at`       DATETIME                              COMMENT '最新更新时间',
    `closed_at`            DATETIME                              COMMENT '关闭时间',
    `gmt_create`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modify`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_meeting_id` (`meeting_id`),
    KEY `idx_status` (`status`),
    KEY `idx_next_check` (`next_check_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议追踪项';

CREATE TABLE `ai_meeting_tracking_update` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tracking_item_id` BIGINT      NOT NULL                COMMENT '追踪项ID',
    `meeting_id`      BIGINT                                COMMENT '会议ID',
    `update_type`     VARCHAR(64)  NOT NULL                COMMENT '更新类型',
    `status_before`   VARCHAR(64)                           COMMENT '变更前状态',
    `status_after`    VARCHAR(64)                           COMMENT '变更后状态',
    `content`         TEXT                                  COMMENT '更新说明',
    `evidence_ref`    VARCHAR(1024)                         COMMENT '证据引用',
    `gmt_create`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tracking_item_id` (`tracking_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='追踪项更新记录';

-- 会议成员（每次会议独立的一组AI成员）
CREATE TABLE `ai_meeting_member` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `meeting_id`  BIGINT        NOT NULL                COMMENT '会议ID',
    `member_name` VARCHAR(128)  NOT NULL                COMMENT '成员名称，如"理性分析者"',
    `model_type`  VARCHAR(64)   NOT NULL                COMMENT '模型标识，如 gpt-4, claude-3-opus, qwen-max',
    `persona`     VARCHAR(1024) NOT NULL                COMMENT '人设/系统提示词',
    `actor_slot_key` VARCHAR(128)                       COMMENT '成员绑定的执行槽位标识',
    `actor_slot_name` VARCHAR(128)                      COMMENT '成员绑定的执行槽位名称',
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
