-- ============================================================
-- 校园社交网站 数据库 schema
-- MySQL 8.x  /  字符集 utf8mb4  /  InnoDB
-- 通用字段（每张表）：id, create_time, update_time, is_deleted
-- 逻辑外键（不建 FK 约束，由应用层保证一致性）
--
-- 注意:本文件用 CREATE TABLE IF NOT EXISTS,全新建库直接跑本文件即可。
-- 已存在的库要加新列,跑对应 migration_b1_*.sql(增量 ALTER),不要重跑本文件。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `campus_social_v2`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `campus_social_v2`;

-- ------------------------------------------------------------
-- 用户
-- status: 0正常 1冻结 2注销
-- follower_count / following_count / unread_notify_count 冗余计数
--   在关注/取关/新通知 事务内 SET x = x ± 1，绝不读改写
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`              VARCHAR(64)  NOT NULL                COMMENT '邮箱或手机号',
    `password_hash`         VARCHAR(128) NOT NULL                COMMENT 'BCrypt',
    `student_no`            VARCHAR(32)  NULL                    COMMENT '学号(可选)',
    `nickname`              VARCHAR(64)  NULL,
    `avatar_url`            VARCHAR(512) NULL,
    `grade`                 VARCHAR(16)  NULL                    COMMENT '年级',
    `dept`                  VARCHAR(64)  NULL                    COMMENT '院系',
    `status`                TINYINT      NOT NULL DEFAULT 0      COMMENT '0正常 1冻结 2注销',
    `role`                  TINYINT      NOT NULL DEFAULT 0      COMMENT '角色:0普通用户 1管理员',
    `follower_count`        INT          NOT NULL DEFAULT 0,
    `following_count`       INT          NOT NULL DEFAULT 0,
    `unread_notify_count`   INT          NOT NULL DEFAULT 0,
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`            TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_student_no` (`student_no`),
    KEY `idx_dept_grade` (`dept`, `grade`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ------------------------------------------------------------
-- 关系
-- type: 1关注 2好友 3黑名单
-- 好友双向各存一行；关注计数在事务内 ±1 user.follower_count / following_count
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `relation` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `from_user_id`    BIGINT   NOT NULL,
    `to_user_id`      BIGINT   NOT NULL,
    `type`            TINYINT  NOT NULL COMMENT '1关注 2好友 3黑名单',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rel` (`from_user_id`, `to_user_id`, `type`),
    KEY `idx_to` (`to_user_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注/好友/黑名单';

-- ------------------------------------------------------------
-- 帖子
-- status: 0审核中 1通过 2下架 3删除
-- media: JSON 数组 [{"type":"img|video","url":"...","dur":秒}]
-- like_count / comment_count 冗余缓存,事务内原子 ±1
-- hot_score 由 HotScoreTask 定时算 (like+2*comment)/(age_hours+2)^1.5
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `author_id`       BIGINT       NOT NULL,
    `content`         MEDIUMTEXT   NULL,
    `media`          JSON         NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0审核中 1通过 2下架 3删除',
    `like_count`      INT          NOT NULL DEFAULT 0,
    `comment_count`   INT          NOT NULL DEFAULT 0,
    `view_count`      INT          NOT NULL DEFAULT 0 COMMENT '浏览量,进详情 +1',
    `circle_id`       BIGINT       NULL,
    `hot_score`       DOUBLE       NOT NULL DEFAULT 0,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_author` (`author_id`),
    KEY `idx_status_time` (`status`, `create_time`),
    KEY `idx_view` (`view_count`),
    KEY `idx_like` (`like_count`),
    KEY `idx_comment` (`comment_count`),
    KEY `idx_circle` (`circle_id`),
    FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子/动态';

-- ------------------------------------------------------------
-- Feed 写扩散收件箱
-- 游标基于 last_id (idx_fan_time: fan_id, id DESC)
-- 发帖事务1 → 发 MQ post.created → 消费者按 500 条/批 INSERT feed_item(事务2)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `feed_item` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `fan_id`          BIGINT   NOT NULL,
    `author_id`       BIGINT   NOT NULL,
    `post_id`         BIGINT   NOT NULL,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_fan_time` (`fan_id`, `id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='写扩散 Feed 收件箱';

-- ------------------------------------------------------------
-- 评论
-- level ≤ 3 (1帖 2评 3评中评)；parent_id NULL=一级；reply_to_user_id 被回复的人
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `post_id`             BIGINT       NOT NULL,
    `user_id`             BIGINT       NOT NULL,
    `parent_id`           BIGINT       NULL,
    `level`               TINYINT      NOT NULL DEFAULT 1,
    `reply_to_user_id`    BIGINT       NULL,
    `content`             VARCHAR(2000) NOT NULL,
    `mentioned`           VARCHAR(512)  NULL COMMENT '被@用户:昵称→id(JSON),渲染 @昵称 时跳主页',
    `status`              TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0删除',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_post_parent` (`post_id`, `parent_id`, `level`),
    KEY `idx_reply_to` (`post_id`, `reply_to_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论';

-- ------------------------------------------------------------
-- 点赞
-- target_type: 1帖 2评
-- 独立表 + 冗余 count，事务内 ±1 post.like_count
-- 唯一键保证幂等(重复点赞不报错)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `like` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT   NOT NULL,
    `target_type`     TINYINT  NOT NULL COMMENT '1帖 2评',
    `target_id`       BIGINT   NOT NULL,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_like` (`user_id`, `target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞';

-- ------------------------------------------------------------
-- 私信会话 (单聊双方,member_a < member_b 归一)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_conversation` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `member_a`        BIGINT   NOT NULL,
    `member_b`        BIGINT   NOT NULL,
    `last_msg_id`     BIGINT   NULL,
    `unread_a`        INT      NOT NULL DEFAULT 0,
    `unread_b`        INT      NOT NULL DEFAULT 0,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv` (`member_a`, `member_b`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信会话';

-- ------------------------------------------------------------
-- 私信消息
-- status: 0未读 1已读 (receiver 侧)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `conv_id`         BIGINT       NOT NULL,
    `sender_id`       BIGINT       NOT NULL,
    `receiver_id`     BIGINT       NOT NULL,
    `content`         VARCHAR(2000) NOT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读(receiver侧)',
    `is_recalled`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已撤回(仅发送方 60s 内可撤)',
    `reply_to_id`     BIGINT       NULL COMMENT '引用回复:被引消息 id(可空)',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_conv_time` (`conv_id`, `create_time` DESC),
    KEY `idx_reply_to` (`reply_to_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信消息';

-- ------------------------------------------------------------
-- 通知
-- type: 1被关注 2被赞 3被评 4私信 5活动
-- user.unread_notify_count 冗余
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notify` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT   NOT NULL,
    `type`            TINYINT  NOT NULL COMMENT '1被关注 2被赞 3被评 4私信 5活动',
    `target_id`       BIGINT   NOT NULL COMMENT '关联实体 id (帖子/评论/会话/活动)',
    `post_id`         BIGINT   NULL COMMENT '被指向的帖子 id(点赞/评论跳帖子详情用),其他类型可空',
    `actor_id`        BIGINT   NOT NULL COMMENT '触发者 userId',
    `is_read`         TINYINT  NOT NULL DEFAULT 0,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_read` (`user_id`, `is_read`, `create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知';

-- ------------------------------------------------------------
-- 圈子
-- status: 0正常 1封禁
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `circle` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `name`            VARCHAR(64)  NOT NULL,
    `owner_id`        BIGINT       NOT NULL,
    `member_count`    INT          NOT NULL DEFAULT 0,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1封禁',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈子';

-- ------------------------------------------------------------
-- 圈子成员
-- role: 1creator 2admin 3member
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `circle_member` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `circle_id`       BIGINT   NOT NULL,
    `user_id`         BIGINT   NOT NULL,
    `role`            TINYINT  NOT NULL DEFAULT 3 COMMENT '1creator 2admin 3member',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cm` (`circle_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈子成员';

-- ------------------------------------------------------------
-- 活动
-- 状态机: 0报名中 →1已满 →2进行中 →3已结束; 任意 →4已取消
-- capacity: 名额上限, 0 不限
-- current_count 冗余, 报名事务内 ±1
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `activity` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `title`           VARCHAR(128) NOT NULL,
    `circle_id`       BIGINT       NULL,
    `capacity`        INT          NOT NULL DEFAULT 0 COMMENT '名额,0不限',
    `current_count`   INT          NOT NULL DEFAULT 0,
    `start_time`      DATETIME     NOT NULL,
    `end_time`        DATETIME     NOT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0报名中 1已满 2进行中 3已结束 4已取消',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_circle` (`circle_id`),
    KEY `idx_status_time` (`status`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动';

-- ------------------------------------------------------------
-- 活动报名 (幂等键: activity_id + user_id)
-- 报名同事务内: INSERT ON DUPLICATE KEY + 乐观锁 UPDATE
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `activity_signup` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `activity_id`     BIGINT   NOT NULL,
    `user_id`         BIGINT   NOT NULL,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_as` (`activity_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动报名';

-- ------------------------------------------------------------
-- 敏感词
-- level: 1替换 2拒发
-- 应用层构建 DFA 字典缓存 + 定时刷新
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sensitive_word` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `word`            VARCHAR(64)  NOT NULL,
    `level`           TINYINT      NOT NULL COMMENT '1替换 2拒发',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词';

-- ------------------------------------------------------------
-- 审核任务
-- post.status=0 入队；reviewer 处理：status 1通过 2驳回
-- review_task(post_id UK) 防重复审
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `review_task` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `post_id`         BIGINT   NOT NULL,
    `reviewer_id`     BIGINT   NULL,
    `status`          TINYINT  NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2驳回',
    `reason`          VARCHAR(512) NULL,
    `hit_words`       VARCHAR(256) NULL COMMENT '命中敏感词(JSON数组,送审时记录,审核台展示用)',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post` (`post_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容审核任务';

-- ------------------------------------------------------------
-- 审计日志:关键写操作留痕(登录/发帖/删帖/报名/拉黑/审核 等)
-- 只增不删(无 update_time/is_deleted 的常规逻辑删,保留全部历史)
-- user_id NULL=系统/定时任务
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT       NULL                    COMMENT '操作者, NULL=系统',
    `action`          VARCHAR(64)  NOT NULL                COMMENT '动作枚举:login/register/create_post/delete_post/signup/blacklist/review_approve/review_reject/...',
    `target_type`     VARCHAR(32)  NULL                    COMMENT '操作对象类型:post/relation/activity/user...',
    `target_id`       BIGINT       NULL                    COMMENT '操作对象 id',
    `detail`          VARCHAR(1024) NULL                    COMMENT 'JSON 或其他详情',
    `ip`              VARCHAR(64)  NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `create_time`),
    KEY `idx_action` (`action`, `create_time`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';

-- ------------------------------------------------------------
-- 话题:帖子可带 #话题#,Feed 可按话题筛选(快手式)
-- name 唯一;post_count 冗余计数,upsert 时用 ON DUPLICATE KEY 累加
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `topic` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(64)  NOT NULL COMMENT '话题名(不带#)',
    `post_count`  INT          NOT NULL DEFAULT 0,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='话题';

-- 帖子-话题 多对多
CREATE TABLE IF NOT EXISTS `post_topic` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `post_id`    BIGINT   NOT NULL,
    `topic_id`   BIGINT   NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pt` (`post_id`, `topic_id`),
    KEY `idx_topic` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子-话题关联';

-- ------------------------------------------------------------
-- 群聊(在单聊私信之上扩展:群/N 人/群已读水位)
-- 单聊 chat_conversation/chat_message 保持 1:1 不变,群独立一套表。
-- 群已读不用"每列 unread"(N 人不可行),改用 group_read 水位表:
--   每个成员存"我读到的最后一条 group_message.id"
--   群未读 = 水位之后、非我发的消息数
-- ------------------------------------------------------------

-- 群
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

-- 群成员(物理删退群,软删只留痕迹——沿用关系表"物理删"约定)
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

-- 群消息
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

-- 群已读水位:每个成员存"我读到的最后一条 group_message.id"
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

-- ------------------------------------------------------------
-- 审核任务 AI 机审列(在既有 review_task 上加)
-- 新建库直接进 schema;已有库跑 migration_review_task_ai.sql
-- ------------------------------------------------------------
ALTER TABLE `review_task`
    ADD COLUMN `ai_verdict`    VARCHAR(16)  NULL COMMENT 'AI 机审结论:approve/reject/manual',
    ADD COLUMN `ai_reason`     VARCHAR(512) NULL COMMENT 'AI 机审理由',
    ADD COLUMN `ai_reviewed_at` DATETIME    NULL COMMENT 'AI 机审完成时间';

-- ------------------------------------------------------------
-- 初始化数据：内置敏感词（level 1 替换 / level 2 拒发）
-- 实际词库可后续扩充
-- ------------------------------------------------------------
INSERT IGNORE INTO `sensitive_word` (`word`, `level`) VALUES
    ('测试违禁词', 2),
    ('示例敏感词A', 2),
    ('示例敏感词B', 1);
