-- migration_g1b: notify 表加 content 列,承载反馈回复等自由文本通知内容
-- 2026-10-19

ALTER TABLE `notify`
    ADD COLUMN `content` VARCHAR(500) NULL
    COMMENT '自由文本通知内容(如反馈回复原文);旧类型通知恒为 NULL'
    AFTER `actor_id`;
