-- 活动创建者 creator_id（2026-10-20）
-- 用途：活动报名通知(type=5)需要知道「谁创建的活动」才能推给创建者。
-- 旧数据该列为 NULL，signup 通知会优雅跳过（不报错）。
-- 位置放在 circle_id 之后，与业务语义「圈子下的活动、由某用户创建」一致。

ALTER TABLE activity
    ADD COLUMN creator_id BIGINT NULL COMMENT '活动创建者用户id，NULL=迁移前旧数据' AFTER circle_id;
