-- ============================================================
-- 迁移:C3 搜索升级 FULLTEXT + ngram(2026-09-24)
-- 给已存在的 post 表加中文全文索引(必须 ngram 分词器,按单字切分不准)。
-- 已按 schema.sql 全新建库的忽略本文件(直接重跑 schema.sql 含本 ALTER)。
-- 需 MySQL 8.x+(ngram 解析器 InnoDB 内置)。
-- ============================================================
ALTER TABLE `post`
    ADD FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram;
