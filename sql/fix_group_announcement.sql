-- 群公告:group 表加 announcement 列(群主可设/清,空=无),展示在群信息头
-- 给已存在库用;全新建库直接把列加进 schema.sql 的 group 表定义
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,用 information_schema 守卫,可重复执行(幂等)
-- 用法:mysql -u<user> -p < fix_group_announcement.sql  (已存在该列则跳过)
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'group'
      AND column_name = 'announcement'
);
SET @stmt := IF(@has_col = 0,
    'ALTER TABLE `group` ADD COLUMN `announcement` TEXT NULL COMMENT ''群公告(群主可设/清,空=无)'' AFTER `last_msg_id`',
    'SELECT 1');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
