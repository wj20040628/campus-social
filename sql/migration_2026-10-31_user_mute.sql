-- 用户级禁言（2026-10-31）
--
-- 背景：举报处置此前只有 ban（封禁，整号不可用）与 takedown（仅下架内容），
-- 缺少「账号还能登录浏览，但一段时间内不能发帖/评论/私信/群聊/上传」这一档。
-- 本列保存禁言截止时间；到点自动解除，不需要定时任务。
--
-- 幂等：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守卫，
-- 可重复执行。执行：
--   mysql --default-character-set=utf8mb4 campus_social_v2 < sql/migration_2026-10-31_user_mute.sql
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND column_name = 'mute_until'
);
SET @stmt := IF(@has_col = 0,
    'ALTER TABLE `user` ADD COLUMN `mute_until` DATETIME NULL COMMENT ''禁言截止时间,NULL=未禁言;到点自动解除'' AFTER `status`',
    'SELECT 1');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
