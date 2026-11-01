-- ============================================================
-- 注销账号存量关系修复（1.13.9，2026-11-01）
--
-- 背景：1.13.8 及以前，账号注销只改 user 行，不清理 group_member /
-- circle_member / activity_signup。结果：
--   1) 群主/圈主注销后组织无主，没人能改名/踢人/转让/解散；
--   2) 活动报名永久占用名额；
--   3) 成员数把已注销账号算在内。
--
-- 本脚本只处理 status=2（已注销）的账号，口径与 1.13.9 应用内级联一致：
--   - 群/圈直接退出；群主/圈主优先移交给仍在世的正常成员
--     （圈子优先圈管理，其次按加入顺序）；无人可接则解散。
--   - 活动报名物理删除，current_count 回补；活动本身保留。
--   - 帖子/评论、私聊历史保留，不动。
--
-- 执行前必须备份：
--   mysqldump --single-transaction --quick --no-tablespaces \
--     -u<user> -p <database> > campus_before_cancel_repair_20261101.sql
--
-- 脚本可重复执行：第二次执行时，已清退的关系已不存在，不会二次扣减。
-- 建议在宝塔数据库面板的 SQL 窗口执行，执行完看末尾校验查询是否全 0。
-- ============================================================

START TRANSACTION;

-- ------------------------------------------------------------
-- 1. 活动报名：删除已注销账号的报名，回补名额
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_cancel_signup;
CREATE TEMPORARY TABLE tmp_cancel_signup AS
SELECT s.activity_id, s.user_id
FROM activity_signup s
JOIN user u ON u.id = s.user_id AND u.status = 2
WHERE s.is_deleted = 0;

UPDATE activity a
JOIN (
    SELECT activity_id, COUNT(*) AS cnt
    FROM tmp_cancel_signup
    GROUP BY activity_id
) t ON t.activity_id = a.id
SET a.current_count = GREATEST(0, a.current_count - t.cnt),
    a.status = IF(a.capacity > 0 AND a.status = 1
                  AND GREATEST(0, a.current_count - t.cnt) < a.capacity, 0, a.status),
    a.update_time = NOW();

DELETE s
FROM activity_signup s
JOIN tmp_cancel_signup t
  ON t.activity_id = s.activity_id AND t.user_id = s.user_id;

-- ------------------------------------------------------------
-- 2. 群主注销：选接任者；无人可接则解散
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_group_owner;
CREATE TEMPORARY TABLE tmp_group_owner AS
SELECT g.id AS group_id,
       g.owner_id AS old_owner_id,
       (
         SELECT gm.user_id
         FROM group_member gm
         JOIN user u ON u.id = gm.user_id AND u.is_deleted = 0 AND u.status = 0
         WHERE gm.group_id = g.id AND gm.is_deleted = 0
         ORDER BY gm.id ASC
         LIMIT 1
       ) AS new_owner_id
FROM `group` g
JOIN user u ON u.id = g.owner_id AND u.status = 2
WHERE g.status = 1 AND g.is_deleted = 0;

UPDATE `group` g
JOIN tmp_group_owner t ON t.group_id = g.id
SET g.owner_id = t.new_owner_id, g.update_time = NOW()
WHERE t.new_owner_id IS NOT NULL;

UPDATE group_member gm
JOIN tmp_group_owner t
  ON t.group_id = gm.group_id AND t.new_owner_id = gm.user_id
SET gm.role = 1, gm.muted = 0, gm.update_time = NOW()
WHERE gm.is_deleted = 0;

DROP TEMPORARY TABLE IF EXISTS tmp_group_disband;
CREATE TEMPORARY TABLE tmp_group_disband AS
SELECT group_id FROM tmp_group_owner WHERE new_owner_id IS NULL;

DELETE gm
FROM group_member gm
JOIN tmp_group_disband d ON d.group_id = gm.group_id;

DELETE gr
FROM group_read gr
JOIN tmp_group_disband d ON d.group_id = gr.group_id;

DELETE gmsg
FROM group_message gmsg
JOIN tmp_group_disband d ON d.group_id = gmsg.group_id;

UPDATE `group` g
JOIN tmp_group_disband d ON d.group_id = g.id
SET g.status = 0, g.is_deleted = 1, g.update_time = NOW();

-- ------------------------------------------------------------
-- 3. 已注销账号退出所有群（含上面已移交的旧群主行）
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_group_removed;
CREATE TEMPORARY TABLE tmp_group_removed AS
SELECT gm.group_id, gm.user_id
FROM group_member gm
JOIN user u ON u.id = gm.user_id AND u.status = 2
WHERE gm.is_deleted = 0;

DELETE gm
FROM group_member gm
JOIN tmp_group_removed r
  ON r.group_id = gm.group_id AND r.user_id = gm.user_id;

DELETE gr
FROM group_read gr
JOIN tmp_group_removed r
  ON r.group_id = gr.group_id AND r.user_id = gr.user_id;

-- ------------------------------------------------------------
-- 4. 重算受影响群的 member_count（以实际成员行为准）
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_group_affected;
CREATE TEMPORARY TABLE tmp_group_affected AS
SELECT group_id FROM tmp_group_owner
UNION
SELECT group_id FROM tmp_group_removed;

-- 临时表在同一语句里只能引用一次，这里用相关子查询而不是重复 IN 子查询
UPDATE `group` g
JOIN tmp_group_affected a ON a.group_id = g.id
SET g.member_count = (
        SELECT COUNT(*) FROM group_member gm
        WHERE gm.group_id = g.id AND gm.is_deleted = 0
    ),
    g.update_time = NOW();

-- ------------------------------------------------------------
-- 5. 圈主注销：选接任者（优先圈管理）；无人可接则解散
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_circle_owner;
CREATE TEMPORARY TABLE tmp_circle_owner AS
SELECT c.id AS circle_id,
       c.owner_id AS old_owner_id,
       (
         SELECT cm.user_id
         FROM circle_member cm
         JOIN user u ON u.id = cm.user_id AND u.is_deleted = 0 AND u.status = 0
         WHERE cm.circle_id = c.id AND cm.is_deleted = 0 AND cm.role IN (2, 3)
           AND (cm.ban_status = 0 OR (cm.ban_until IS NOT NULL AND cm.ban_until < NOW()))
         ORDER BY CASE WHEN cm.role = 2 THEN 0 ELSE 1 END, cm.id ASC
         LIMIT 1
       ) AS new_owner_id
FROM circle c
JOIN user u ON u.id = c.owner_id AND u.status = 2
WHERE c.status = 0 AND c.is_deleted = 0;

UPDATE circle c
JOIN tmp_circle_owner t ON t.circle_id = c.id
SET c.owner_id = t.new_owner_id, c.update_time = NOW()
WHERE t.new_owner_id IS NOT NULL;

UPDATE circle_member cm
JOIN tmp_circle_owner t
  ON t.circle_id = cm.circle_id AND t.new_owner_id = cm.user_id
SET cm.role = 1, cm.ban_status = 0, cm.ban_until = NULL, cm.update_time = NOW()
WHERE cm.is_deleted = 0;

DROP TEMPORARY TABLE IF EXISTS tmp_circle_disband;
CREATE TEMPORARY TABLE tmp_circle_disband AS
SELECT circle_id FROM tmp_circle_owner WHERE new_owner_id IS NULL;

-- 解散前先回退所有成员（含已注销）的 joined_circle_count
UPDATE user u
JOIN circle_member cm ON cm.user_id = u.id
JOIN tmp_circle_disband d ON d.circle_id = cm.circle_id
SET u.joined_circle_count = GREATEST(0, u.joined_circle_count - 1),
    u.update_time = NOW()
WHERE cm.is_deleted = 0;

DELETE cm
FROM circle_member cm
JOIN tmp_circle_disband d ON d.circle_id = cm.circle_id;

UPDATE post p
JOIN tmp_circle_disband d ON d.circle_id = p.circle_id
SET p.status = 2, p.update_time = NOW()
WHERE p.is_deleted = 0;

UPDATE circle c
JOIN tmp_circle_disband d ON d.circle_id = c.id
SET c.status = 1, c.member_count = 0, c.update_time = NOW();

-- ------------------------------------------------------------
-- 6. 已注销账号退出所有圈，并重算活跃圈的 member_count
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_circle_removed;
CREATE TEMPORARY TABLE tmp_circle_removed AS
SELECT cm.circle_id, cm.user_id
FROM circle_member cm
JOIN user u ON u.id = cm.user_id AND u.status = 2
WHERE cm.is_deleted = 0;

DROP TEMPORARY TABLE IF EXISTS tmp_circle_affected;
CREATE TEMPORARY TABLE tmp_circle_affected AS
SELECT cm.circle_id
FROM circle_member cm
JOIN user u ON u.id = cm.user_id AND u.status = 2
JOIN circle c ON c.id = cm.circle_id AND c.status = 0 AND c.is_deleted = 0
WHERE cm.is_deleted = 0
UNION
SELECT circle_id FROM tmp_circle_owner WHERE new_owner_id IS NOT NULL;

DELETE cm
FROM circle_member cm
JOIN tmp_circle_removed r
  ON r.circle_id = cm.circle_id AND r.user_id = cm.user_id;

-- 同群计数：临时表只引用一次
UPDATE circle c
JOIN tmp_circle_affected a ON a.circle_id = c.id
SET c.member_count = (
        SELECT COUNT(*) FROM circle_member cm
        WHERE cm.circle_id = c.id AND cm.is_deleted = 0
    ),
    c.update_time = NOW();

-- ------------------------------------------------------------
-- 7. 已注销账号的「加入的圈子数」归零
-- ------------------------------------------------------------
UPDATE user
SET joined_circle_count = 0, update_time = NOW()
WHERE status = 2 AND joined_circle_count <> 0;

COMMIT;

-- ------------------------------------------------------------
-- 8. 执行后校验（应全部为 0）
-- ------------------------------------------------------------
-- 已注销账号仍残留的群成员行：
SELECT COUNT(*) AS cancelled_group_members
FROM group_member gm JOIN user u ON u.id = gm.user_id
WHERE u.status = 2 AND gm.is_deleted = 0;

-- 已注销账号仍残留的圈成员行：
SELECT COUNT(*) AS cancelled_circle_members
FROM circle_member cm JOIN user u ON u.id = cm.user_id
WHERE u.status = 2 AND cm.is_deleted = 0;

-- 已注销账号仍残留的活动报名：
SELECT COUNT(*) AS cancelled_signups
FROM activity_signup s JOIN user u ON u.id = s.user_id
WHERE u.status = 2 AND s.is_deleted = 0;

-- 仍是已注销账号当群主的正常群：
SELECT COUNT(*) AS cancelled_group_owners
FROM `group` g JOIN user u ON u.id = g.owner_id
WHERE u.status = 2 AND g.status = 1 AND g.is_deleted = 0;

-- 仍是已注销账号当圈主的正常圈：
SELECT COUNT(*) AS cancelled_circle_owners
FROM circle c JOIN user u ON u.id = c.owner_id
WHERE u.status = 2 AND c.status = 0 AND c.is_deleted = 0;

-- 本次受影响对象的 member_count 与实际成员行不一致（排除已解散对象）：
SELECT COUNT(*) AS group_count_drift
FROM `group` g
JOIN tmp_group_affected a ON a.group_id = g.id
WHERE g.status = 1 AND g.is_deleted = 0
  AND g.member_count <> (SELECT COUNT(*) FROM group_member gm
                         WHERE gm.group_id = g.id AND gm.is_deleted = 0);

SELECT COUNT(*) AS circle_count_drift
FROM circle c
JOIN tmp_circle_affected a ON a.circle_id = c.id
WHERE c.status = 0 AND c.is_deleted = 0
  AND c.member_count <> (SELECT COUNT(*) FROM circle_member cm
                         WHERE cm.circle_id = c.id AND cm.is_deleted = 0);
