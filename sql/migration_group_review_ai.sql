-- 群聊 IM + AI 机审:增量迁移(已有库执行,新库已含于 schema.sql)
-- 说明:群独立一套表(群/群成员/群消息/群已读水位),单聊 chat_* 一行不动;
--      review_task 加 AI 机审三列(ai_verdict/ai_reason/ai_reviewed_at)。
-- 已按 schema.sql 全新建库的忽略本文件。

-- ============================================================
-- 群聊四表
-- ============================================================

CREATE TABLE IF NOT EXISTS `group` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(64)  NOT NULL,
    `avatar_url`    VARCHAR(512) NULL,
    `owner_id`      BIGINT       NOT NULL COMMENT '群主(user.id)',
    `member_count`  INT          NOT NULL DEFAULT 1,
    `last_msg_id`   BIGINT       NULL,
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0解散',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_group_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群';

CREATE TABLE IF NOT EXISTS `group_member` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `group_id`   BIGINT   NOT NULL,
    `user_id`    BIGINT   NOT NULL,
    `role`       TINYINT  NOT NULL DEFAULT 2 COMMENT '1群主 2成员',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gm` (`group_id`, `user_id`),
    KEY `idx_gm_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群成员';

CREATE TABLE IF NOT EXISTS `group_message` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `group_id`     BIGINT       NOT NULL,
    `sender_id`    BIGINT       NOT NULL,
    `content`      VARCHAR(2000) NOT NULL,
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读(发送方视角汇总)',
    `is_recalled`  TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已撤回(仅发送方 8s 内可撤)',
    `reply_to_id`  BIGINT       NULL COMMENT '引用回复:被引群消息 id(可空)',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_gm_time` (`group_id`, `id` DESC),
    KEY `idx_gm_reply` (`reply_to_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群消息';

CREATE TABLE IF NOT EXISTS `group_read` (
    `id`               BIGINT   NOT NULL AUTO_INCREMENT,
    `group_id`         BIGINT   NOT NULL,
    `user_id`          BIGINT   NOT NULL,
    `last_read_msg_id` BIGINT   NOT NULL DEFAULT 0,
    `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`       TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gr` (`group_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群已读水位';

-- ============================================================
-- 审核任务 AI 机审三列
-- ============================================================
ALTER TABLE `review_task`
    ADD COLUMN `ai_verdict`     VARCHAR(16) NULL COMMENT 'AI 机审结论:approve/reject/manual',
    ADD COLUMN `ai_reason`      VARCHAR(512) NULL COMMENT 'AI 机审理由',
    ADD COLUMN `ai_reviewed_at` DATETIME    NULL COMMENT 'AI 机审完成时间';
