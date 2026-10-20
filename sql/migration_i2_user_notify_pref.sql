-- 通知偏好（2026-10-20）
-- 用途：用户可按类型关闭某类通知（免打扰）。存 JSON：{"disabled":[2,3]}
-- 解析约定在 PostNotifySupport.pushNotify 与 UserController 两处一致：
--   列值 NULL 或 "" = 全开；否则取 disabled 数组里的类型 id 跳过投递。

ALTER TABLE user
    ADD COLUMN notify_pref TEXT NULL COMMENT '通知偏好JSON:{"disabled":[类型id]}';
