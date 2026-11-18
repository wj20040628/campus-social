-- 在线状态「隐身」开关（2026-11-13）
-- presence_hidden = 1 表示用户在线但对外显示为离线；
-- 管理总览的在线人数仍统计该用户，公共在线人数与头像绿点不展示。
-- 可重复执行：deploy.sh 每次部署都会重跑迁移，列已存在时跳过。
SET @has_presence_hidden := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user'
      AND COLUMN_NAME = 'presence_hidden'
);
SET @ddl := IF(@has_presence_hidden = 0,
    'ALTER TABLE `user` ADD COLUMN `presence_hidden` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=在线但对外显示离线'' AFTER `mute_until`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
