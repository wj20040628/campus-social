-- 用户处置排序（2026-11-08）：最近处置时间
-- 封禁/禁言时写 user.last_punish_time，列表可按它倒序；
-- 不拿 audit_log 现算，避免每页都跑一次相关子查询。
-- 可重复执行：列与索引分别判存在，deploy.sh 重跑时自动跳过。
SET @has_last_punish := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user'
      AND COLUMN_NAME = 'last_punish_time'
);
SET @ddl := IF(@has_last_punish = 0,
    'ALTER TABLE `user` ADD COLUMN `last_punish_time` DATETIME NULL COMMENT ''最近一次被封禁/禁言的时刻;从未处置为NULL'' AFTER `purged_at`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx_last_punish := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user'
      AND INDEX_NAME = 'idx_last_punish'
);
SET @ddl := IF(@has_idx_last_punish = 0,
    'ALTER TABLE `user` ADD KEY `idx_last_punish` (`last_punish_time` DESC)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
