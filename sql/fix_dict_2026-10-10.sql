-- 修复字典表乱码 + 换成黄河交通学院真实数据
-- 执行方式(必须带 utf8mb4):
--   mysql -uroot -p --default-character-set=utf8mb4 campus_social_v2 < fix_dict.sql
-- 背景:school_dept(55条)/school_grade(8条) 的 name 是「UTF-8 字节被按 GBK
-- 误解后重新存储」的双重编码乱码,前端学院下拉整列乱码。清空重插。
-- 年级范围:2026 年在校生为 2023-2026 级(本科四年/大专三年,2023 级是最老的大四)。

-- 1. 清空乱码字典(物理删除:乱码数据无法修复,且无外部引用——
--    user 表仅 erin 测试账号 dept_id=1,下方统一置 NULL)
DELETE FROM school_dept;
DELETE FROM school_grade;

-- 2. 院系:黄河交通学院招生网 2026 年公示的 7 个招生学院
INSERT INTO school_dept (name, school_id, sort, enabled) VALUES
('智慧交通与智能建造工程学院', 1, 1, 1),
('智能车辆与新能源工程学院',   1, 2, 1),
('智能制造与电气工程学院',     1, 3, 1),
('人工智能学院',               1, 4, 1),
('数字经济与智慧管理学院',     1, 5, 1),
('马克思主义学院',             1, 6, 1),
('艺术与设计学院',             1, 7, 1);

-- 3. 年级:2023-2026(最老 2023 级=大四;name 与 enroll_year 同源)
INSERT INTO school_grade (name, enroll_year, sort, enabled) VALUES
('2023级', 2023, 1, 1),
('2024级', 2024, 2, 1),
('2025级', 2025, 3, 1),
('2026级', 2026, 4, 1);

-- 4. 测试账号的字典引用已悬空,置 NULL 让其重新走引导
UPDATE user SET dept_id = NULL, grade_id = NULL WHERE dept_id IS NOT NULL AND school_id = 1;
