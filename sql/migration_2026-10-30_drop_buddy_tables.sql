-- 2026-10-30 下线未启用的 F7 搭子预留表。
-- 背景：buddy_post/buddy_join 自建库起没有实体、Mapper、Service 或端点，
--       本地与生产均无数据；仅作为 SPEC F7 的预留 DDL 存在。
-- 说明：举报目标类型 5 仍保留为历史契约编号，但不再对应任何表。
DROP TABLE IF EXISTS `buddy_join`;
DROP TABLE IF EXISTS `buddy_post`;
