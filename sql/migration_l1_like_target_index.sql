-- like 表「按目标反查」索引（2026-10-22）
--
-- 为什么需要它：
--   `like` 表原本只有两个索引：PRIMARY(id) 和 uk_like(user_id, target_type, target_id)。
--   唯一索引最左列是 user_id，只能高效服务「某用户赞过哪些东西」；
--   反过来问「某条帖子/评论被赞了几次」时用不上它 ——
--   EXPLAIN 实测 type=ALL、key=NULL，是全表扫描。
--
--   三个受害点：
--     1) PostMapper.reconcileHotAndLikes 每 10 分钟对所有帖子做 UPDATE，
--        每一行都跑一次 SELECT COUNT(*) FROM `like` WHERE target_type=1 AND target_id=p.id。
--        缺索引时这是「帖子数 × 点赞数」的嵌套全表扫，数据量一上来会直接把库压住。
--     2) LikeMapper 里评论点赞的批量统计（WHERE target_type=2 GROUP BY target_id）。
--     3) 删帖 / 删评论时清理点赞（DELETE ... WHERE target_type=? AND target_id=?）。
--
-- 为什么列顺序是 (target_type, target_id)：
--   两个条件都是等值比较，理论上任一顺序都能命中；把区分度低的 target_type 放前面，
--   符合「先按类型切一刀、再按 id 定位」的查询形态，索引本身也更紧凑。
--
-- 为什么不做成 (target_type, target_id, is_deleted) 覆盖索引：
--   覆盖索引能让 COUNT(*) 不回表，但会给点赞写入多加一列维护成本。
--   校园场景的 like 表规模（万级）用两列已经足够，不必提前优化写放大。

-- MySQL 8.0 不支持 CREATE INDEX IF NOT EXISTS（那是 MariaDB 的语法），
-- 这里用 information_schema 先判存在，保证重复执行不会报 1061。
SET @idx_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'like'
      AND index_name = 'idx_like_target'
);
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE `like` ADD INDEX idx_like_target (target_type, target_id)',
    'SELECT ''idx_like_target 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 执行后自检：type 应为 ref、key 应为 idx_like_target，不再是 ALL。
-- EXPLAIN SELECT COUNT(*) FROM `like` WHERE target_type = 1 AND target_id = 1 AND is_deleted = 0;
