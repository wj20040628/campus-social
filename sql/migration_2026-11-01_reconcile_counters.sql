-- ============================================================
-- 冗余计数全量重算（2026-11-01，1.13.11）
--
-- 用途：部署迁移阶段先重算一次，让后续 sql/consistency_check.sql
--       能在存量漂移的库上通过；也避免「有漂移就永远无法部署」。
-- 口径与 ReconcileJob 完全一致，幂等，可重复执行。
-- 覆盖：群成员数、圈成员数、活动报名数/满员标记、加圈数、
--       关注/粉丝数、私信未读。
-- ============================================================

-- 1. 群成员数
UPDATE `group` g SET member_count = (
    SELECT COUNT(*) FROM group_member gm
    WHERE gm.group_id = g.id AND gm.is_deleted = 0
), update_time = NOW()
WHERE g.is_deleted = 0;

-- 2. 圈成员数（与 CircleMapper.reconcileMemberCount 一致，覆盖已删圈）
UPDATE circle c SET member_count = (
    SELECT COUNT(*) FROM circle_member cm
    WHERE cm.circle_id = c.id AND cm.is_deleted = 0
), update_time = NOW();

-- 3. 活动报名数与满员标记（只纠正 0/1，不改生命周期状态）
UPDATE activity a SET current_count = (
    SELECT COUNT(*) FROM activity_signup s
    WHERE s.activity_id = a.id AND s.is_deleted = 0
), status = CASE
    WHEN a.status IN (0,1) AND a.capacity > 0 AND (
        SELECT COUNT(*) FROM activity_signup s2
        WHERE s2.activity_id = a.id AND s2.is_deleted = 0
    ) >= a.capacity THEN 1
    WHEN a.status IN (0,1) THEN 0
    ELSE a.status END,
update_time = NOW()
WHERE a.is_deleted = 0;

-- 4. 用户加圈数（只算正常圈，与 ReconcileJob 同口径）
UPDATE user u SET joined_circle_count = (
    SELECT COUNT(*) FROM circle_member cm
    JOIN circle c ON c.id = cm.circle_id AND c.status = 0 AND c.is_deleted = 0
    WHERE cm.user_id = u.id AND cm.is_deleted = 0
), update_time = NOW()
WHERE u.is_deleted = 0;

-- 5. 关注/粉丝数（派生表先物化，规避 MySQL 1093）
UPDATE user u
LEFT JOIN (
    SELECT r.to_user_id AS uid, COUNT(*) AS cnt
    FROM relation r
    JOIN user ru ON ru.id = r.from_user_id AND ru.status <> 2 AND ru.is_deleted = 0
    WHERE r.type = 1 AND r.is_deleted = 0
    GROUP BY r.to_user_id
) f ON f.uid = u.id
LEFT JOIN (
    SELECT r.from_user_id AS uid, COUNT(*) AS cnt
    FROM relation r
    JOIN user ru ON ru.id = r.to_user_id AND ru.status <> 2 AND ru.is_deleted = 0
    WHERE r.type = 1 AND r.is_deleted = 0
    GROUP BY r.from_user_id
) g ON g.uid = u.id
SET u.follower_count = COALESCE(f.cnt, 0),
    u.following_count = COALESCE(g.cnt, 0),
    u.update_time = NOW();

-- 6. 私信未读
UPDATE chat_conversation c SET
    unread_a = (
        SELECT COUNT(*) FROM chat_message m
        WHERE m.conv_id = c.id AND m.receiver_id = c.member_a
          AND m.status = 0 AND m.is_deleted = 0
    ),
    unread_b = (
        SELECT COUNT(*) FROM chat_message m
        WHERE m.conv_id = c.id AND m.receiver_id = c.member_b
          AND m.status = 0 AND m.is_deleted = 0
    ),
    update_time = NOW()
WHERE c.is_deleted = 0;
