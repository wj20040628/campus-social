-- 群禁言:group_member 表加 muted 列(群主可把某成员/全员设为 1,被禁言者发群消息被拦)
-- 给已存在库用;全新建库直接把列加进 schema.sql 的 group_member 表定义
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,用 information_schema 守卫,可重复执行(幂等)
-- 用法:mysql -u<user> -p < fix_group_mute.sql  (已存在该列则跳过)
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group_member'
      AND column_name = 'muted'
);
SET @stmt := IF(@has_col = 0,
    'ALTER TABLE `group_member` ADD COLUMN `muted` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否被群主禁言 0否 1是(群主恒 0)'' AFTER `role`',
    'SELECT 1');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
