-- ============================================================
-- 存量库升级:2026-10-23 schema 基线合并同步
--
-- 背景:schema.sql 已重构为完整基线(37 张表,建库只跑一个文件)。
--      但已存在的库跑 CREATE TABLE IF NOT EXISTS 不会加列/加表,必须走本文件。
--
-- 幂等设计:MySQL 8.4 的 ALTER TABLE ADD COLUMN / ADD KEY 不支持 IF NOT EXISTS,
--   因此每条都用 information_schema 守卫 + PREPARE/EXECUTE 包裹,
--   可**重复执行**:已加的跳过,没加的执行。
--   (守卫模式照抄 sql/fix_group_mute.sql 的既有写法)
--
-- 执行:
--   mysql -uroot -p --default-character-set=utf8mb4 campus_social_v2 < sql/migration_2026-10-23_baseline_sync.sql
--
-- 前置:先备份
--   mysqldump -uroot -p campus_social_v2 > bak_before_baseline_sync.sql
-- ============================================================

USE `campus_social_v2`;

-- ============================================================
-- 第 1 节:补建 11 张只存在于迁移脚本中的表
--   (与 schema.sql 中的定义完全一致;已存在则跳过)
-- ============================================================
-- 这些表在 docs/sql/migration_v1.sql、sql/migration_f12_school_major.sql、
-- sql/migration_g1_feedback.sql、sql/migration_j1_post_favorite.sql、
-- sql/migration_k1_chat_conv_pref.sql 里已有定义。
-- 若你的库是从那批迁移脚本建的,这里会全部跳过;否则补建。
-- 直接照抄 schema.sql 的 DDL(IF NOT EXISTS 天然幂等),避免两处定义漂移。

CREATE TABLE IF NOT EXISTS `interest_tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `name` VARCHAR(32) NOT NULL,
    `category` TINYINT NOT NULL, `sort` INT NOT NULL DEFAULT 100,
    `use_count` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_name` (`name`),
    KEY `idx_category_sort` (`category`,`sort`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='兴趣标签字典';

CREATE TABLE IF NOT EXISTS `user_tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `user_id` BIGINT NOT NULL, `tag_id` BIGINT NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_user_tag` (`user_id`,`tag_id`), KEY `idx_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-兴趣标签';

CREATE TABLE IF NOT EXISTS `report` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `reporter_id` BIGINT NOT NULL,
    `target_type` TINYINT NOT NULL, `target_id` BIGINT NOT NULL,
    `reason_type` TINYINT NOT NULL, `reason_text` VARCHAR(500) NULL,
    `status` TINYINT NOT NULL DEFAULT 0, `handler_id` BIGINT NULL,
    `handle_time` DATETIME NULL, `handle_note` VARCHAR(500) NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_report` (`reporter_id`,`target_type`,`target_id`),
    KEY `idx_status_time` (`status`,`create_time` DESC), KEY `idx_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报';

CREATE TABLE IF NOT EXISTS `announcement` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `title` VARCHAR(120) NOT NULL,
    `content` TEXT NOT NULL, `category` TINYINT NOT NULL DEFAULT 6,
    `publisher_id` BIGINT NOT NULL, `school_id` BIGINT NOT NULL DEFAULT 1,
    `is_top` TINYINT NOT NULL DEFAULT 0, `top_expire_time` DATETIME NULL,
    `status` TINYINT NOT NULL DEFAULT 0, `publish_time` DATETIME NULL,
    `read_count` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_school_status_top_time` (`school_id`,`status`,`is_top` DESC,`publish_time` DESC),
    KEY `idx_category` (`category`,`status`,`publish_time` DESC), KEY `idx_publisher` (`publisher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方公告';

CREATE TABLE IF NOT EXISTS `announcement_read` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `announcement_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL, `read_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_ann_user` (`announcement_id`,`user_id`),
    KEY `idx_user` (`user_id`,`read_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告已读回执';

CREATE TABLE IF NOT EXISTS `school_dept` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `name` VARCHAR(64) NOT NULL,
    `school_id` BIGINT NOT NULL DEFAULT 1, `sort` INT NOT NULL DEFAULT 100,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_school_name` (`school_id`,`name`),
    KEY `idx_school_enabled_sort` (`school_id`,`enabled`,`sort`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='院系字典';

CREATE TABLE IF NOT EXISTS `school_grade` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `name` VARCHAR(32) NOT NULL,
    `enroll_year` SMALLINT NOT NULL, `sort` INT NOT NULL DEFAULT 100,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_name` (`name`), KEY `idx_enabled_sort` (`enabled`,`sort`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年级字典';

CREATE TABLE IF NOT EXISTS `school_major` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `dept_id` BIGINT NOT NULL,
    `school_id` BIGINT NOT NULL DEFAULT 1, `name` VARCHAR(64) NOT NULL,
    `code` CHAR(6) NOT NULL, `level` TINYINT NOT NULL,
    `code_type` TINYINT NOT NULL DEFAULT 1, `sort` INT NOT NULL DEFAULT 100,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_school_code` (`school_id`,`code`),
    UNIQUE KEY `uk_dept_name` (`dept_id`,`name`),
    KEY `idx_dept_level_enabled_sort` (`dept_id`,`level`,`enabled`,`sort`,`id`),
    CONSTRAINT `fk_major_dept` FOREIGN KEY (`dept_id`) REFERENCES `school_dept` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专业字典(学院下属专业,支撑级联下拉与学号专业码校验)';

CREATE TABLE IF NOT EXISTS `feedback` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `user_id` BIGINT NOT NULL,
    `category` TINYINT NOT NULL, `content` VARCHAR(1000) NOT NULL,
    `media_json` VARCHAR(2000) NULL, `status` TINYINT NOT NULL DEFAULT 0,
    `handler_id` BIGINT NULL, `handle_time` DATETIME NULL, `handle_note` VARCHAR(500) NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`), KEY `idx_status_time` (`status`,`create_time` DESC),
    KEY `idx_user` (`user_id`,`id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意见反馈';

CREATE TABLE IF NOT EXISTS `chat_conv_pref` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `user_id` BIGINT NOT NULL,
    `kind` VARCHAR(8) NOT NULL, `target_id` BIGINT NOT NULL,
    `pinned` TINYINT NOT NULL DEFAULT 0, `muted` TINYINT NOT NULL DEFAULT 0,
    `hidden` TINYINT NOT NULL DEFAULT 0, `hidden_upto` BIGINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_pref` (`user_id`,`kind`,`target_id`),
    KEY `idx_user_kind` (`user_id`,`kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话偏好(置顶/免打扰/删除),按人存';

CREATE TABLE IF NOT EXISTS `post_favorite` (
    `id` BIGINT NOT NULL AUTO_INCREMENT, `user_id` BIGINT NOT NULL,
    `post_id` BIGINT NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_fav_user_post` (`user_id`,`post_id`),
    KEY `idx_fav_user_id` (`user_id`,`id`), KEY `idx_fav_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子收藏';

-- ============================================================
-- 第 2 节:user 表补列(幂等)
-- 顺序约束:email 的 AFTER `school_id` 依赖 school_id 先存在 ——
--   旧库这些列全缺,所以 school_id/verify_status/... 必须排在 email 之前。
-- ============================================================
-- 守卫:列不存在才执行,存在则 SET @sql = 'DO 0'(空操作)
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `school_id` BIGINT NOT NULL DEFAULT 1 COMMENT ''学校ID,默认1,预留多校''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='school_id';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `verify_status` TINYINT NOT NULL DEFAULT 0 COMMENT ''0未认证 1已认证 2审核中 3驳回''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='verify_status';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `stranger_msg_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT ''是否接收陌生人消息''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='stranger_msg_enabled';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `onboarding_step` TINYINT NOT NULL DEFAULT 0 COMMENT ''引导进度''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='onboarding_step';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `email` VARCHAR(64) NULL COMMENT ''安全凭证邮箱,有值时唯一'' AFTER `school_id`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='email';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD UNIQUE KEY `uk_user_email` (`email`)',
    'DO 0') INTO @sql
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='user' AND index_name='uk_user_email';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `bio` VARCHAR(200) NULL COMMENT ''个人介绍'' AFTER `nickname`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='bio';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `cover_url` VARCHAR(512) NULL COMMENT ''个人主页封面图'' AFTER `avatar_url`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='cover_url';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `school_id` BIGINT NOT NULL DEFAULT 1 COMMENT ''学校ID,默认1,预留多校''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='school_id';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `verify_status` TINYINT NOT NULL DEFAULT 0 COMMENT ''0未认证 1已认证 2审核中 3驳回''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='verify_status';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `stranger_msg_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT ''是否接收陌生人消息''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='stranger_msg_enabled';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `onboarding_step` TINYINT NOT NULL DEFAULT 0 COMMENT ''引导进度''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='onboarding_step';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `dept_id` BIGINT NULL COMMENT ''院系字典id''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='dept_id';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `grade_id` BIGINT NULL COMMENT ''年级字典id''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='grade_id';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `major_id` BIGINT NULL COMMENT ''专业字典id''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='major_id';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `notify_pref` TEXT NULL COMMENT ''通知偏好JSON''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='notify_pref';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `user` ADD COLUMN `joined_circle_count` INT NOT NULL DEFAULT 0 COMMENT ''加入的圈子数''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user' AND column_name='joined_circle_count';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============================================================
-- 第 3 节:其余表补列 / 补索引 / 改可空(幂等)
-- ============================================================

-- P0-5:notify.actor_id 改可空(AI 机审无操作人)
SELECT IF(COUNT(*)=1,
    'ALTER TABLE `notify` MODIFY COLUMN `actor_id` BIGINT NULL COMMENT ''触发者 userId;NULL=系统(AI 机审/定时任务)''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='notify' AND column_name='actor_id' AND is_nullable='NO';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- notify.content(反馈回复原文)
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `notify` ADD COLUMN `content` VARCHAR(500) NULL COMMENT ''自由文本通知内容'' AFTER `actor_id`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='notify' AND column_name='content';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- notify.post_id 索引(删帖按 post_id 清理通知,P0-9)
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `notify` ADD KEY `idx_post` (`post_id`)',
    'DO 0') INTO @sql
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='notify' AND index_name='idx_post';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- post.favorite_count
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `post` ADD COLUMN `favorite_count` INT NOT NULL DEFAULT 0 COMMENT ''收藏数'' AFTER `comment_count`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='post' AND column_name='favorite_count';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- post.is_top(圈内置顶)
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `post` ADD COLUMN `is_top` TINYINT NOT NULL DEFAULT 0 COMMENT ''圈内置顶''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='post' AND column_name='is_top';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- post 全文索引
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `post` ADD FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram',
    'DO 0') INTO @sql
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='post' AND index_name='ft_content';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- activity.creator_id
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `activity` ADD COLUMN `creator_id` BIGINT NULL COMMENT ''活动创建者用户id'' AFTER `circle_id`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='activity' AND column_name='creator_id';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- comment.mentioned
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `comment` ADD COLUMN `mentioned` VARCHAR(512) NULL COMMENT ''被@用户:昵称→id(JSON)'' AFTER `content`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='comment' AND column_name='mentioned';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- chat_message.is_recalled / reply_to_id
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `chat_message` ADD COLUMN `is_recalled` TINYINT NOT NULL DEFAULT 0 COMMENT ''0正常 1已撤回''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='chat_message' AND column_name='is_recalled';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `chat_message` ADD COLUMN `reply_to_id` BIGINT NULL COMMENT ''引用回复:被引消息 id''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='chat_message' AND column_name='reply_to_id';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- circle_member.ban_status / ban_until
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `circle_member` ADD COLUMN `ban_status` TINYINT NOT NULL DEFAULT 0 COMMENT ''圈内封禁''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='circle_member' AND column_name='ban_status';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `circle_member` ADD COLUMN `ban_until` DATETIME NULL COMMENT ''封禁到期,NULL=长期''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='circle_member' AND column_name='ban_until';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- review_task.hit_words / ai_verdict / ai_reason / ai_reviewed_at
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `review_task` ADD COLUMN `hit_words` VARCHAR(256) NULL COMMENT ''命中敏感词(JSON数组)'' AFTER `reason`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='review_task' AND column_name='hit_words';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `review_task` ADD COLUMN `ai_verdict` VARCHAR(16) NULL COMMENT ''AI 机审结论''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='review_task' AND column_name='ai_verdict';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `review_task` ADD COLUMN `ai_reason` VARCHAR(512) NULL COMMENT ''AI 机审理由''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='review_task' AND column_name='ai_reason';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `review_task` ADD COLUMN `ai_reviewed_at` DATETIME NULL COMMENT ''AI 机审完成时间''',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='review_task' AND column_name='ai_reviewed_at';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- group.announcement
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `group` ADD COLUMN `announcement` TEXT NULL COMMENT ''群公告'' AFTER `last_msg_id`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='group' AND column_name='announcement';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- group_member.muted
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `group_member` ADD COLUMN `muted` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否被群主禁言'' AFTER `role`',
    'DO 0') INTO @sql
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='group_member' AND column_name='muted';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- like 索引
SELECT IF(COUNT(*)=0,
    'ALTER TABLE `like` ADD INDEX `idx_like_target` (`target_type`,`target_id`)',
    'DO 0') INTO @sql
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='like' AND index_name='idx_like_target';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============================================================
-- 第 4 节:feed_item 唯一键(P0-7)
-- 加之前必须先删掉历史重复行,否则 ALTER 会失败。
-- 重复行成因:审核并发通过时同帖被扩散两次。
-- ============================================================
DELETE f1 FROM `feed_item` f1
    INNER JOIN `feed_item` f2
    WHERE f1.fan_id = f2.fan_id AND f1.post_id = f2.post_id AND f1.id > f2.id;

SELECT IF(COUNT(*)=0,
    'ALTER TABLE `feed_item` ADD UNIQUE KEY `uk_fan_post` (`fan_id`,`post_id`)',
    'DO 0') INTO @sql
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='feed_item' AND index_name='uk_fan_post';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============================================================
-- 第 5 节:执行后校验(逐条人工确认,应全部返回 1 / 37 / 0)
-- ============================================================
-- 1) 表总数(期望 37)
SELECT COUNT(*) AS expect_37_tables FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_type='BASE TABLE';

-- 2) user 关键列齐全(期望 11 行)
SELECT column_name FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user'
  AND column_name IN ('email','bio','cover_url','school_id','verify_status','stranger_msg_enabled',
                      'onboarding_step','dept_id','grade_id','major_id','notify_pref','joined_circle_count')
ORDER BY column_name;

-- 3) notify.actor_id 应可空
SELECT is_nullable FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='notify' AND column_name='actor_id';

-- 4) feed_item 唯一键应存在
SELECT COUNT(*) AS expect_1_uk_fan_post FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='feed_item' AND index_name='uk_fan_post';

-- 5) 重复 feed_item 应为 0
SELECT COUNT(*) AS expect_0_duplicates FROM (
    SELECT fan_id, post_id FROM `feed_item` GROUP BY fan_id, post_id HAVING COUNT(*) > 1
) t;
