-- ============================================================
-- 迁移:B1 内容审核补全(2026-09-24)
-- 给已存在的 review_task 表加 hit_words 列。
-- schema.sql 用 CREATE TABLE IF NOT EXISTS,重跑不会加列,故单独一份。
-- 已按 schema.sql 全新建库的忽略本文件即可(hit_words 已在 schema.sql 内)。
-- ============================================================
ALTER TABLE `review_task`
    ADD COLUMN `hit_words` VARCHAR(256) NULL COMMENT '命中敏感词(JSON数组,送审时记录,审核台展示用)' AFTER `reason`;
