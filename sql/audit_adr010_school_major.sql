-- ============================================================
-- ADR-010 只读审计：school_major 外键与字典引用完整性
--
-- 用法（只读，不建表、不改数据）：
--   mysql -h... -u... -p <目标库> < sql/audit_adr010_school_major.sql
--
-- 审计对象：
--   1) school_major.dept_id 是否指向存在的学院
--   2) 唯一索引前置条件：同校专业码 / 同院专业名是否重复（含逻辑删除行，
--      因为 MySQL 唯一索引不会跳过 is_deleted=1）
--   3) user.dept_id / grade_id / major_id 是否指向字典，专业是否属于所填学院
--   4) dept_id 与 id 的列类型是否对齐（外键要求同类型）
--
-- 判定（见 docs/decisions/ADR-010.md）：
--   第 1 类必须为 0；第 2 类必须先去重；第 4 类必须类型一致，才可考虑加外键。
-- ============================================================

-- ------------------------------------------------------------
-- A. 汇总计数（预期全为 0 才能走选项 A）
-- ------------------------------------------------------------
SELECT 'A1 school_major 孤儿 dept_id（全部行）' AS check_item, COUNT(*) AS bad_rows
FROM school_major m
LEFT JOIN school_dept d ON d.id = m.dept_id
WHERE d.id IS NULL
UNION ALL
SELECT 'A2 school_major 孤儿 dept_id（未删除行）', COUNT(*)
FROM school_major m
LEFT JOIN school_dept d ON d.id = m.dept_id
WHERE d.id IS NULL AND m.is_deleted = 0
UNION ALL
SELECT 'A3 school_major dept_id 为空', COUNT(*)
FROM school_major WHERE dept_id IS NULL
UNION ALL
SELECT 'A4 同校专业码重复（全部行）', COUNT(*)
FROM (SELECT school_id, code FROM school_major
      GROUP BY school_id, code HAVING COUNT(*) > 1) t
UNION ALL
SELECT 'A5 同校专业码重复（未删除行）', COUNT(*)
FROM (SELECT school_id, code FROM school_major
      WHERE is_deleted = 0 GROUP BY school_id, code HAVING COUNT(*) > 1) t
UNION ALL
SELECT 'A6 同院专业名重复（全部行）', COUNT(*)
FROM (SELECT dept_id, name FROM school_major
      GROUP BY dept_id, name HAVING COUNT(*) > 1) t
UNION ALL
SELECT 'A7 同院专业名重复（未删除行）', COUNT(*)
FROM (SELECT dept_id, name FROM school_major
      WHERE is_deleted = 0 GROUP BY dept_id, name HAVING COUNT(*) > 1) t
UNION ALL
SELECT 'A8 user 孤儿 dept_id', COUNT(*)
FROM `user` u LEFT JOIN school_dept d ON d.id = u.dept_id
WHERE u.dept_id IS NOT NULL AND d.id IS NULL
UNION ALL
SELECT 'A9 user 孤儿 major_id', COUNT(*)
FROM `user` u LEFT JOIN school_major m ON m.id = u.major_id
WHERE u.major_id IS NOT NULL AND m.id IS NULL
UNION ALL
SELECT 'A10 user 孤儿 grade_id', COUNT(*)
FROM `user` u LEFT JOIN school_grade g ON g.id = u.grade_id
WHERE u.grade_id IS NOT NULL AND g.id IS NULL
UNION ALL
SELECT 'A11 user 专业不属于所填学院', COUNT(*)
FROM `user` u
JOIN school_major m ON m.id = u.major_id
WHERE u.dept_id IS NOT NULL AND m.dept_id <> u.dept_id
UNION ALL
SELECT 'A12 user 引用停用/删除学院', COUNT(*)
FROM `user` u JOIN school_dept d ON d.id = u.dept_id
WHERE d.enabled = 0 OR d.is_deleted = 1
UNION ALL
SELECT 'A13 user 引用停用/删除专业', COUNT(*)
FROM `user` u JOIN school_major m ON m.id = u.major_id
WHERE m.enabled = 0 OR m.is_deleted = 1
UNION ALL
SELECT 'A14 user 只填专业未填学院', COUNT(*)
FROM `user` u
WHERE u.major_id IS NOT NULL AND u.dept_id IS NULL;

-- ------------------------------------------------------------
-- B. 重复明细（最多 20 组，供清理/确认使用）
-- ------------------------------------------------------------
SELECT 'B1 同校专业码重复（含删除行）' AS check_item,
       school_id, code, COUNT(*) AS n,
       GROUP_CONCAT(CONCAT(id, ':', name, ':', IF(is_deleted = 0, 'active', 'deleted'))
                    ORDER BY id SEPARATOR ' | ') AS rows_detail
FROM school_major
GROUP BY school_id, code
HAVING COUNT(*) > 1
ORDER BY n DESC, school_id, code
LIMIT 20;

SELECT 'B2 同院专业名重复（含删除行）' AS check_item,
       dept_id, name, COUNT(*) AS n,
       GROUP_CONCAT(CONCAT(id, ':', code, ':', IF(is_deleted = 0, 'active', 'deleted'))
                    ORDER BY id SEPARATOR ' | ') AS rows_detail
FROM school_major
GROUP BY dept_id, name
HAVING COUNT(*) > 1
ORDER BY n DESC, dept_id, name
LIMIT 20;

-- ------------------------------------------------------------
-- C. 结构对齐（外键要求两侧列类型一致）
-- ------------------------------------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('school_major', 'school_dept')
  AND column_name IN ('id', 'dept_id')
ORDER BY TABLE_NAME, COLUMN_NAME;

SELECT 'C1 school_major 现有索引' AS check_item, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, NON_UNIQUE
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'school_major'
GROUP BY INDEX_NAME, NON_UNIQUE
ORDER BY INDEX_NAME;

-- ------------------------------------------------------------
-- D. 分布（判断影响面，不作为通过/失败判定）
-- ------------------------------------------------------------
SELECT 'D1 school_major 按删除/启用' AS check_item,
       is_deleted, enabled, COUNT(*) AS n
FROM school_major GROUP BY is_deleted, enabled ORDER BY is_deleted, enabled;

SELECT 'D2 school_dept 按删除/启用' AS check_item,
       is_deleted, enabled, COUNT(*) AS n
FROM school_dept GROUP BY is_deleted, enabled ORDER BY is_deleted, enabled;

SELECT 'D3 user 字典填写覆盖' AS check_item,
       COUNT(*) AS total_users,
       SUM(dept_id IS NOT NULL) AS with_dept,
       SUM(major_id IS NOT NULL) AS with_major,
       SUM(grade_id IS NOT NULL) AS with_grade
FROM `user`;
