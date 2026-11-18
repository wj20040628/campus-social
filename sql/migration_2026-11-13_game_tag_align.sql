-- 兴趣「游戏」与二手「游戏组队」可选项对齐（2026-11-13）
-- 二手侧原有 PUBG、暗区突围，兴趣标签缺这两个；本迁移补齐，使两边游戏集合一致。
-- 可重复执行：uk_name 冲突时复活并归到游戏分类。
INSERT INTO interest_tag (name, category, sort) VALUES ('PUBG', 2, 130)
ON DUPLICATE KEY UPDATE category = 2, is_deleted = 0;
INSERT INTO interest_tag (name, category, sort) VALUES ('暗区突围', 2, 140)
ON DUPLICATE KEY UPDATE category = 2, is_deleted = 0;
