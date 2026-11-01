-- ============================================================
-- 数据一致性校验（2026-11-01，1.13.11）
--
-- 用途：部署脚本在迁移完成后执行；返回一行 violations，
--       必须为 0 才允许继续启动。
-- 覆盖：注销残留、四类冗余计数、私信未读。
-- 只读查询，可重复执行。
-- ============================================================

SELECT (
    -- 已注销账号仍残留的群/圈/报名/关注关系
    (SELECT COUNT(*) FROM group_member gm
     JOIN user u ON u.id = gm.user_id AND u.status = 2
     WHERE gm.is_deleted = 0)
  + (SELECT COUNT(*) FROM circle_member cm
     JOIN user u ON u.id = cm.user_id AND u.status = 2
     WHERE cm.is_deleted = 0)
  + (SELECT COUNT(*) FROM activity_signup s
     JOIN user u ON u.id = s.user_id AND u.status = 2
     WHERE s.is_deleted = 0)
  + (SELECT COUNT(*) FROM relation r
     JOIN user uf ON uf.id = r.from_user_id
     JOIN user ut ON ut.id = r.to_user_id
     WHERE r.type = 1 AND r.is_deleted = 0 AND (uf.status = 2 OR ut.status = 2))
  + (SELECT COUNT(*) FROM `group` g
     JOIN user u ON u.id = g.owner_id AND u.status = 2
     WHERE g.is_deleted = 0)
  + (SELECT COUNT(*) FROM circle c
     JOIN user u ON u.id = c.owner_id AND u.status = 2
     WHERE c.is_deleted = 0)
  + (SELECT COUNT(*) FROM activity a
     JOIN user u ON u.id = a.creator_id AND u.status = 2
     WHERE a.is_deleted = 0 AND a.status IN (0,1,2))
  + (SELECT COUNT(*) FROM mkt_activity m
     JOIN user u ON u.id = m.user_id AND u.status = 2
     WHERE m.soft_delete = 0 AND m.is_enable = 1)

    -- 四类冗余计数漂移
  + (SELECT COUNT(*) FROM `group` g
     WHERE g.is_deleted = 0
       AND g.member_count <> (
           SELECT COUNT(*) FROM group_member gm
           WHERE gm.group_id = g.id AND gm.is_deleted = 0))
  + (SELECT COUNT(*) FROM circle c
     WHERE c.is_deleted = 0
       AND c.member_count <> (
           SELECT COUNT(*) FROM circle_member cm
           WHERE cm.circle_id = c.id AND cm.is_deleted = 0))
  + (SELECT COUNT(*) FROM activity a
     WHERE a.is_deleted = 0
       AND a.current_count <> (
           SELECT COUNT(*) FROM activity_signup s
           WHERE s.activity_id = a.id AND s.is_deleted = 0))
  + (SELECT COUNT(*) FROM user u
     WHERE u.is_deleted = 0
       AND u.joined_circle_count <> (
           SELECT COUNT(*) FROM circle_member cm
           JOIN circle c ON c.id = cm.circle_id AND c.status = 0 AND c.is_deleted = 0
           WHERE cm.user_id = u.id AND cm.is_deleted = 0))

    -- 关注/粉丝数与私信未读漂移（与 ReconcileJob 同口径）
  + (SELECT COUNT(*) FROM user u
     WHERE u.is_deleted = 0 AND (
         u.follower_count <> (
             SELECT COUNT(*) FROM relation r
             JOIN user ru ON ru.id = r.from_user_id AND ru.status <> 2 AND ru.is_deleted = 0
             WHERE r.to_user_id = u.id AND r.type = 1 AND r.is_deleted = 0)
         OR u.following_count <> (
             SELECT COUNT(*) FROM relation r
             JOIN user ru ON ru.id = r.to_user_id AND ru.status <> 2 AND ru.is_deleted = 0
             WHERE r.from_user_id = u.id AND r.type = 1 AND r.is_deleted = 0)))
  + (SELECT COUNT(*) FROM chat_conversation c
     WHERE c.is_deleted = 0 AND (
         c.unread_a <> (
             SELECT COUNT(*) FROM chat_message m
             WHERE m.conv_id = c.id AND m.receiver_id = c.member_a
               AND m.status = 0 AND m.is_deleted = 0)
         OR c.unread_b <> (
             SELECT COUNT(*) FROM chat_message m
             WHERE m.conv_id = c.id AND m.receiver_id = c.member_b
               AND m.status = 0 AND m.is_deleted = 0)))
) AS violations;
