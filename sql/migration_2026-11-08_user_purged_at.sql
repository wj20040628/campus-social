-- 已注销账号留存策略（2026-11-08）
-- 注销当下清空资料；私信/通知保留 N 天后由 CancelledAccountPurgeJob 清理。
-- 本列记录清理完成时间，保证定时任务幂等（不会每天重复扫同一批账号）。
-- 可重复执行：deploy.sh 每次部署都会重跑全部迁移，列已存在时跳过。
SET @has_purged_at := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user'
      AND COLUMN_NAME = 'purged_at'
);
SET @ddl := IF(@has_purged_at = 0,
    'ALTER TABLE `user` ADD COLUMN `purged_at` DATETIME NULL COMMENT ''已注销账号私信/通知清理完成时间;正常账号为NULL'' AFTER `mute_until`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
