-- ============================================================
-- 注销账号存量残留修复（1.13.10，2026-11-01）
--
-- 背景：1.13.9 已清退注销账号的群/圈成员关系和活动报名，但注销时
-- 仍有三类残留：
--   1) 用户创建的活动仍处于报名中/已满/进行中，无人能管理；
--   2) 集市发布未下线，仍出现在广场；
--   3) 关注关系未清理，关注/粉丝数仍把注销账号算在内。
--
-- 本脚本只处理 status=2（已注销）账号，口径与 1.13.10 应用内级联一致：
--   - 其创建的活动 status∈(0,1,2) → 置 4（已取消），并给仍在报名的
--     正常用户发一条 12 类通知（同一活动不重复发）。
--   - 其集市发布 is_enable=0（软删/已暂停的不动）。
--   - 涉及注销账号的 type=1 关注关系物理删除，并重算关系另一端
--     用户的 follower_count / following_count（排除注销账号）。
--   - 帖子/评论/私聊/黑名单保持原口径，不动。
--
-- 执行前必须备份：
--   mysqldump --single-transaction --quick --no-tablespaces \
--     -u<user> -p <database> > campus_before_cancel_residual_repair_20261101.sql
--
-- 脚本可重复执行：活动取消后不再命中 status∈(0,1,2)，通知有 NOT EXISTS
-- 去重，关注关系删除后计数按现存行重算，不会二次扣减。
-- 建议在宝塔数据库面板的 SQL 窗口执行，执行完看末尾校验查询是否全 0。
-- ============================================================

START TRANSACTION;

-- ------------------------------------------------------------
-- 1. 注销用户创建的活动：取消并通知仍报名的正常用户
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_cancel_created_activity;
CREATE TEMPORARY TABLE tmp_cancel_created_activity AS
SELECT a.id AS activity_id, a.creator_id
FROM activity a
JOIN user u ON u.id = a.creator_id AND u.status = 2
WHERE a.is_deleted = 0 AND a.status IN (0, 1, 2);

INSERT INTO notify (user_id, type, target_id, post_id, actor_id, content, is_read)
SELECT s.user_id, 12, t.activity_id, NULL, NULL, '活动创建者已注销，活动已取消', 0
FROM activity_signup s
JOIN tmp_cancel_created_activity t ON t.activity_id = s.activity_id
JOIN user recipient ON recipient.id = s.user_id AND recipient.status <> 2 AND recipient.is_deleted = 0
WHERE s.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM notify n
      WHERE n.user_id = s.user_id AND n.type = 12 AND n.target_id = t.activity_id
  );

UPDATE activity a
JOIN tmp_cancel_created_activity t ON t.activity_id = a.id
SET a.status = 4, a.update_time = NOW();

-- ------------------------------------------------------------
-- 2. 注销用户的集市发布：立即下线
-- ------------------------------------------------------------
UPDATE mkt_activity m
JOIN user u ON u.id = m.user_id AND u.status = 2
SET m.is_enable = 0
WHERE m.soft_delete = 0 AND m.is_enable = 1;

-- ------------------------------------------------------------
-- 3. 关注关系：删除涉及注销账号的 type=1 关系
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_cancel_follow_affected;
CREATE TEMPORARY TABLE tmp_cancel_follow_affected AS
SELECT r.to_user_id AS user_id
FROM relation r
JOIN user u ON u.id = r.from_user_id AND u.status = 2
WHERE r.type = 1
UNION
SELECT r.from_user_id AS user_id
FROM relation r
JOIN user u ON u.id = r.to_user_id AND u.status = 2
WHERE r.type = 1;

DELETE r
FROM relation r
JOIN user uf ON uf.id = r.from_user_id
JOIN user ut ON ut.id = r.to_user_id
WHERE r.type = 1 AND (uf.status = 2 OR ut.status = 2);

-- 重算受影响用户的关注/粉丝数（口径：只算未注销的对端）。
-- 先落临时表再 UPDATE：MySQL 不允许在 UPDATE 的子查询里再次引用被更新的 user。
DROP TEMPORARY TABLE IF EXISTS tmp_cancel_follow_count;
CREATE TEMPORARY TABLE tmp_cancel_follow_count AS
SELECT a.user_id,
       (
           SELECT COUNT(*)
           FROM relation r
           JOIN user tu ON tu.id = r.to_user_id AND tu.is_deleted = 0 AND tu.status <> 2
           WHERE r.from_user_id = a.user_id AND r.type = 1 AND r.is_deleted = 0
       ) AS following_count,
       (
           SELECT COUNT(*)
           FROM relation r
           JOIN user fu ON fu.id = r.from_user_id AND fu.is_deleted = 0 AND fu.status <> 2
           WHERE r.to_user_id = a.user_id AND r.type = 1 AND r.is_deleted = 0
       ) AS follower_count
FROM tmp_cancel_follow_affected a;

UPDATE user u
JOIN tmp_cancel_follow_count c ON c.user_id = u.id
SET u.following_count = c.following_count,
    u.follower_count = c.follower_count,
    u.update_time = NOW()
WHERE u.status <> 2;

COMMIT;

-- ------------------------------------------------------------
-- 4. 执行后校验（应全部为 0）
-- ------------------------------------------------------------
-- 注销账号仍以创建者身份挂着未取消的活动：
SELECT COUNT(*) AS cancelled_creator_open_activities
FROM activity a JOIN user u ON u.id = a.creator_id
WHERE u.status = 2 AND a.is_deleted = 0 AND a.status IN (0, 1, 2);

-- 注销账号仍在集市展示的发布：
SELECT COUNT(*) AS cancelled_user_live_mkt
FROM mkt_activity m JOIN user u ON u.id = m.user_id
WHERE u.status = 2 AND m.soft_delete = 0 AND m.is_enable = 1;

-- 涉及注销账号的关注关系残留：
SELECT COUNT(*) AS cancelled_follow_relations
FROM relation r
JOIN user uf ON uf.id = r.from_user_id
JOIN user ut ON ut.id = r.to_user_id
WHERE r.type = 1 AND (uf.status = 2 OR ut.status = 2);

-- 受影响用户的关注/粉丝数与现存关系不一致：
SELECT COUNT(*) AS affected_follow_count_drift
FROM user u
JOIN tmp_cancel_follow_affected a ON a.user_id = u.id
WHERE u.status <> 2
  AND (
      u.following_count <> (
          SELECT COUNT(*) FROM relation r
          JOIN user tu ON tu.id = r.to_user_id AND tu.is_deleted = 0 AND tu.status <> 2
          WHERE r.from_user_id = u.id AND r.type = 1 AND r.is_deleted = 0)
      OR u.follower_count <> (
          SELECT COUNT(*) FROM relation r
          JOIN user fu ON fu.id = r.from_user_id AND fu.is_deleted = 0 AND fu.status <> 2
          WHERE r.to_user_id = u.id AND r.type = 1 AND r.is_deleted = 0)
  );
