-- 禁言管理补强（2026-10-31，随 1.13.4 上线）
--
-- 1. report 增加处置动作与天数：
--    管理端「已处理」列表此前只能凭 status 猜处置结果，「举报成立」既可能是
--    永久封禁也可能是仅下架。handle_action/handle_days 记录真实动作，
--    历史行保持 NULL，前端回退到旧文案。
-- 2. user.mute_until 索引：
--    用户处置页新增「禁言中」筛选（mute_until > NOW()），校园规模下非必需，
--    但加一次索引的成本远低于以后回补。
--
-- 幂等：MySQL 8 不支持 ADD COLUMN/INDEX IF NOT EXISTS，用 information_schema
-- 守卫，可重复执行。执行：
--   mysql --default-character-set=utf8mb4 campus_social_v2 < sql/migration_2026-10-31_mute_admin_polish.sql

SET @has_action := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'report'
      AND column_name = 'handle_action'
);
SET @stmt := IF(@has_action = 0,
    'ALTER TABLE `report` ADD COLUMN `handle_action` VARCHAR(16) NULL COMMENT ''处置动作 ban/mute/takedown/reject/ignore;历史行 NULL'' AFTER `handle_note`',
    'SELECT 1');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @has_days := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'report'
      AND column_name = 'handle_days'
);
SET @stmt := IF(@has_days = 0,
    'ALTER TABLE `report` ADD COLUMN `handle_days` INT NULL COMMENT ''处罚天数:封禁 0=永久;禁言 1~365;其余动作为 NULL'' AFTER `handle_action`',
    'SELECT 1');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @has_idx := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND index_name = 'idx_mute_until'
);
SET @stmt := IF(@has_idx = 0,
    'ALTER TABLE `user` ADD INDEX `idx_mute_until` (`mute_until`)',
    'SELECT 1');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
