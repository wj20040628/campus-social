-- ============================================================
-- M2 举报信誉分制回填(2026-10-25)
-- 信誉分改为动态查询 audit_log(action=report_upheld/account_ban, target=user, 近180天)。
-- 新处置由 ReportServiceImpl.handleBan 落 report_upheld 审计;
-- 本迁移把**存量成立举报**(report.status=1)按内容表反查作者,回填同款审计,
-- 使历史前科在分数制下不蒸发。
-- 幂等:detail 唯一标识(report id),NOT EXISTS 防重跑;重复执行零新增。
-- 局限:内容行已被物理删除/软删的存量举报无法归因(join 不到作者),跳过——
--      与运行时 resolveUserId 返回 null 的行为一致。
-- ============================================================

-- 1) 举报用户本人(target_type=1):target_id 即被举报人
INSERT INTO audit_log (user_id, action, target_type, target_id, detail, create_time)
SELECT r.handler_id, 'report_upheld', 'user', r.target_id,
       CONCAT('存量举报#', r.id, '成立回填,信誉-20'), r.handle_time
FROM report r
WHERE r.target_type = 1 AND r.status = 1 AND r.is_deleted = 0 AND r.handler_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM audit_log a
                  WHERE a.action = 'report_upheld' AND a.target_type = 'user'
                    AND a.target_id = r.target_id AND a.detail = CONCAT('存量举报#', r.id, '成立回填,信誉-20'));

-- 2) 举报帖子(target_type=2):作者 = post.author_id
INSERT INTO audit_log (user_id, action, target_type, target_id, detail, create_time)
SELECT r.handler_id, 'report_upheld', 'user', p.author_id,
       CONCAT('存量举报#', r.id, '成立回填,信誉-20'), r.handle_time
FROM report r JOIN post p ON p.id = r.target_id
WHERE r.target_type = 2 AND r.status = 1 AND r.is_deleted = 0 AND r.handler_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM audit_log a
                  WHERE a.action = 'report_upheld' AND a.target_type = 'user'
                    AND a.target_id = p.author_id AND a.detail = CONCAT('存量举报#', r.id, '成立回填,信誉-20'));

-- 3) 举报评论(target_type=3):作者 = comment.user_id
INSERT INTO audit_log (user_id, action, target_type, target_id, detail, create_time)
SELECT r.handler_id, 'report_upheld', 'user', c.user_id,
       CONCAT('存量举报#', r.id, '成立回填,信誉-20'), r.handle_time
FROM report r JOIN comment c ON c.id = r.target_id
WHERE r.target_type = 3 AND r.status = 1 AND r.is_deleted = 0 AND r.handler_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM audit_log a
                  WHERE a.action = 'report_upheld' AND a.target_type = 'user'
                    AND a.target_id = c.user_id AND a.detail = CONCAT('存量举报#', r.id, '成立回填,信誉-20'));

-- 4) 举报聊天消息(target_type=4):作者 = chat_message.sender_id(软删行仍可归因)
INSERT INTO audit_log (user_id, action, target_type, target_id, detail, create_time)
SELECT r.handler_id, 'report_upheld', 'user', m.sender_id,
       CONCAT('存量举报#', r.id, '成立回填,信誉-20'), r.handle_time
FROM report r JOIN chat_message m ON m.id = r.target_id
WHERE r.target_type = 4 AND r.status = 1 AND r.is_deleted = 0 AND r.handler_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM audit_log a
                  WHERE a.action = 'report_upheld' AND a.target_type = 'user'
                    AND a.target_id = m.sender_id AND a.detail = CONCAT('存量举报#', r.id, '成立回填,信誉-20'));

-- 5) 举报集市活动(target_type=6):发布者 = mkt_activity.user_id
INSERT INTO audit_log (user_id, action, target_type, target_id, detail, create_time)
SELECT r.handler_id, 'report_upheld', 'user', k.user_id,
       CONCAT('存量举报#', r.id, '成立回填,信誉-20'), r.handle_time
FROM report r JOIN mkt_activity k ON k.id = r.target_id
WHERE r.target_type = 6 AND r.status = 1 AND r.is_deleted = 0 AND r.handler_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM audit_log a
                  WHERE a.action = 'report_upheld' AND a.target_type = 'user'
                    AND a.target_id = k.user_id AND a.detail = CONCAT('存量举报#', r.id, '成立回填,信誉-20'));
