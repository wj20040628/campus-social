-- ============================================================
-- 迁移:A4 私信撤回/引用(2026-09-24)
-- 给已存在的 chat_message 表加 is_recalled + reply_to_id。
-- 已按 schema.sql 全新建库的忽略本文件(直接重跑 schema.sql 含本 ALTER)。
-- ============================================================
ALTER TABLE `chat_message`
    ADD COLUMN `is_recalled` TINYINT NOT NULL DEFAULT 0 COMMENT '0正常 1已撤回(仅发送方 60s 内可撤)',
    ADD COLUMN `reply_to_id` BIGINT NULL COMMENT '引用回复:被引消息 id(可空)',
    ADD KEY `idx_reply_to` (`reply_to_id`);
