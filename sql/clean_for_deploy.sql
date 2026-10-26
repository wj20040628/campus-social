-- ============================================================
-- 部署前数据清理(2026-10-26)
--
-- 目标:数据库只保留
--   1) user 表 id IN (1,2) 两个账号(及其头像/封面文件)
--   2) 字典/配置数据:sensitive_word / school_dept / school_grade
--      / school_major / interest_tag / reserved_circle_name
--   其余用户生成数据全部清空。
--
-- 前置:**先备份,再执行本脚本**
--   mysqldump --single-transaction --routines --triggers \
--     --default-character-set=utf8mb4 campus_social_v2 > backup_before_clean.sql
--
-- 幂等:全部为 TRUNCATE / 条件 DELETE,可重复执行。
-- 注意:TRUNCATE 不可回滚,执行后只能靠备份恢复。
-- 公告表默认清空(现有 7 条均为 E2E 测试数据);如需保留官方公告,
--   注释掉下面对应两行。
-- ============================================================
USE `campus_social_v2`;

-- ------------------------------------------------------------
-- 1. 帖子与互动
-- ------------------------------------------------------------
TRUNCATE TABLE `feed_item`;
TRUNCATE TABLE `like`;
TRUNCATE TABLE `comment`;
TRUNCATE TABLE `post_favorite`;
TRUNCATE TABLE `post_topic`;
TRUNCATE TABLE `post`;
TRUNCATE TABLE `topic`;
TRUNCATE TABLE `relation`;

-- ------------------------------------------------------------
-- 2. 私信
-- ------------------------------------------------------------
TRUNCATE TABLE `chat_message`;
TRUNCATE TABLE `chat_conversation`;
TRUNCATE TABLE `chat_conv_pref`;

-- ------------------------------------------------------------
-- 3. 群聊
-- ------------------------------------------------------------
TRUNCATE TABLE `group_message`;
TRUNCATE TABLE `group_read`;
TRUNCATE TABLE `group_member`;
TRUNCATE TABLE `group`;

-- ------------------------------------------------------------
-- 4. 圈子 / 活动 / 集市 / 搭子(占位表)
-- ------------------------------------------------------------
TRUNCATE TABLE `circle_member`;
TRUNCATE TABLE `circle`;
TRUNCATE TABLE `activity_signup`;
TRUNCATE TABLE `activity`;
TRUNCATE TABLE `mkt_comment`;
TRUNCATE TABLE `mkt_activity`;
TRUNCATE TABLE `buddy_join`;
TRUNCATE TABLE `buddy_post`;

-- ------------------------------------------------------------
-- 5. 通知 / 审核 / 举报 / 反馈 / 审计
-- ------------------------------------------------------------
TRUNCATE TABLE `notify`;
TRUNCATE TABLE `review_task`;
TRUNCATE TABLE `report`;
TRUNCATE TABLE `feedback`;
TRUNCATE TABLE `audit_log`;

-- ------------------------------------------------------------
-- 6. 公告(当前 7 条均为 E2E 测试数据;要保留官方公告则注释本段)
-- ------------------------------------------------------------
TRUNCATE TABLE `announcement_read`;
TRUNCATE TABLE `announcement`;

-- ------------------------------------------------------------
-- 7. 上传归属记录
--    只清数据库记录,不动磁盘文件;uploads/1、uploads/2 是保留账号
--    的头像/封面,别删。头像归属校验对「查无记录」放行,不影响展示。
-- ------------------------------------------------------------
TRUNCATE TABLE `upload_file`;

-- ------------------------------------------------------------
-- 8. 用户:只留 id 1、2,新用户从 id 3 开始
-- ------------------------------------------------------------
TRUNCATE TABLE `user_tag`;
DELETE FROM `user` WHERE `id` NOT IN (1, 2);
ALTER TABLE `user` AUTO_INCREMENT = 3;

-- 保留账号的冗余计数归零
UPDATE `user`
SET `follower_count` = 0,
    `following_count` = 0,
    `unread_notify_count` = 0,
    `joined_circle_count` = 0
WHERE `id` IN (1, 2);

-- ------------------------------------------------------------
-- 9. 字典冗余计数归零(数据本身保留)
-- ------------------------------------------------------------
UPDATE `interest_tag` SET `use_count` = 0;

-- ------------------------------------------------------------
-- 10. 执行后自检(期望:user=2,敏感词=154,院系=7,年级=4,
--     专业=62,兴趣标签=60,预留圈名=38,其余为 0)
-- ------------------------------------------------------------
SELECT 'user' AS t, COUNT(*) AS c FROM `user`
UNION ALL SELECT 'sensitive_word', COUNT(*) FROM `sensitive_word`
UNION ALL SELECT 'school_dept', COUNT(*) FROM `school_dept`
UNION ALL SELECT 'school_grade', COUNT(*) FROM `school_grade`
UNION ALL SELECT 'school_major', COUNT(*) FROM `school_major`
UNION ALL SELECT 'interest_tag', COUNT(*) FROM `interest_tag`
UNION ALL SELECT 'reserved_circle_name', COUNT(*) FROM `reserved_circle_name`
UNION ALL SELECT 'post', COUNT(*) FROM `post`
UNION ALL SELECT 'comment', COUNT(*) FROM `comment`
UNION ALL SELECT 'circle', COUNT(*) FROM `circle`
UNION ALL SELECT 'chat_message', COUNT(*) FROM `chat_message`
UNION ALL SELECT 'group', COUNT(*) FROM `group`
UNION ALL SELECT 'notify', COUNT(*) FROM `notify`
UNION ALL SELECT 'audit_log', COUNT(*) FROM `audit_log`
UNION ALL SELECT 'announcement', COUNT(*) FROM `announcement`
UNION ALL SELECT 'upload_file', COUNT(*) FROM `upload_file`;
