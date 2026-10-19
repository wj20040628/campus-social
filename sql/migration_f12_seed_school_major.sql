-- ============================================================================
-- migration_f12_seed: school_major 专业种子数据(黄河交通学院)
--
-- 用途:让注册「学院 → 专业」级联下拉在 school_major 为空时有可选项。
-- 运行前提:migration_f12_school_major.sql 已执行(表已建)。
-- 运行:mysql.exe -uroot -p062828 campus_social_v2 < 本文件
-- 幂等:全部 INSERT IGNORE,重复执行不会重复插、不会撞主键。
--
-- ⚠️ 重要:本文件是「按公开招生信息整理的占位种子」,不是官方权威目录。
--   - 本科(level=1)用真实国标 6 位码(code_type=1),专业名/大类码与 codebase 的
--     MAJOR_CLASS_TO_DEPTS 对齐,学号「专业大类(位3-6)↔学院」交叉校验能跑通。
--   - 专科(level=2)行是占位(code_type=3),code 取自高职国标但**未逐一核对**,
--     上线前请按黄河交通学院当年《招生专业目录》逐行核对 name / code / level / 学院,
--     错的专业码不会让系统崩,但会让真实专科生被「专业不存在」拦下。
--   - dept_id 一律按学院「名称」反查(school_dept),不写死 id,避免与你库里
--     school_dept 的实际主键漂移。
--   - school_id 恒为 1(单校)。
-- ============================================================================

-- ---------------- 人工智能学院(计算机类 0809) ----------------
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '计算机科学与技术', '080901', 1, 1, 10, 1 FROM school_dept d WHERE d.name = '人工智能学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '软件工程', '080902', 1, 1, 20, 1 FROM school_dept d WHERE d.name = '人工智能学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '网络工程', '080903', 1, 1, 30, 1 FROM school_dept d WHERE d.name = '人工智能学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '物联网工程', '080905', 1, 1, 40, 1 FROM school_dept d WHERE d.name = '人工智能学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '数据科学与大数据技术', '080910', 1, 1, 50, 1 FROM school_dept d WHERE d.name = '人工智能学院' AND d.school_id = 1;

-- ---------------- 智能制造与电气工程学院 ----------------
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '机械设计制造及其自动化', '080202', 1, 1, 10, 1 FROM school_dept d WHERE d.name = '智能制造与电气工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '机械电子工程', '080204', 1, 1, 20, 1 FROM school_dept d WHERE d.name = '智能制造与电气工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '电气工程及其自动化', '080601', 1, 1, 30, 1 FROM school_dept d WHERE d.name = '智能制造与电气工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '电子信息工程', '080701', 1, 1, 40, 1 FROM school_dept d WHERE d.name = '智能制造与电气工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '通信工程', '080703', 1, 1, 50, 1 FROM school_dept d WHERE d.name = '智能制造与电气工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '自动化', '080801', 1, 1, 60, 1 FROM school_dept d WHERE d.name = '智能制造与电气工程学院' AND d.school_id = 1;

-- ---------------- 智能车辆与新能源工程学院 ----------------
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '车辆工程', '080207', 1, 1, 10, 1 FROM school_dept d WHERE d.name = '智能车辆与新能源工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '汽车服务工程', '080208', 1, 1, 20, 1 FROM school_dept d WHERE d.name = '智能车辆与新能源工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '能源与动力工程', '080501', 1, 1, 30, 1 FROM school_dept d WHERE d.name = '智能车辆与新能源工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '新能源科学与工程', '080503', 1, 1, 40, 1 FROM school_dept d WHERE d.name = '智能车辆与新能源工程学院' AND d.school_id = 1;

-- ---------------- 智慧交通与智能建造工程学院 ----------------
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '交通运输', '081801', 1, 1, 10, 1 FROM school_dept d WHERE d.name = '智慧交通与智能建造工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '交通工程', '081802', 1, 1, 20, 1 FROM school_dept d WHERE d.name = '智慧交通与智能建造工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '土木工程', '081001', 1, 1, 30, 1 FROM school_dept d WHERE d.name = '智慧交通与智能建造工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '道路桥梁与渡河工程', '081006', 1, 1, 40, 1 FROM school_dept d WHERE d.name = '智慧交通与智能建造工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '智能建造', '081008', 1, 1, 50, 1 FROM school_dept d WHERE d.name = '智慧交通与智能建造工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '测绘工程', '081201', 1, 1, 60, 1 FROM school_dept d WHERE d.name = '智慧交通与智能建造工程学院' AND d.school_id = 1;

-- ---------------- 数字经济与智慧管理学院 ----------------
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '工程管理', '120103', 1, 1, 10, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '工程造价', '120105', 1, 1, 20, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '大数据管理与应用', '120108', 1, 1, 30, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '工商管理', '120201', 1, 1, 40, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '会计学', '120203', 1, 1, 50, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '财务管理', '120204', 1, 1, 60, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '物流管理', '120601', 1, 1, 70, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '供应链管理', '120604', 1, 1, 80, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '电子商务', '120801', 1, 1, 90, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '金融工程', '020302', 1, 1, 100, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '投资学', '020304', 1, 1, 110, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;

-- ---------------- 马克思主义学院 ----------------
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '思想政治教育', '030503', 1, 1, 10, 1 FROM school_dept d WHERE d.name = '马克思主义学院' AND d.school_id = 1;

-- ---------------- 艺术与设计学院 ----------------
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '视觉传达设计', '130502', 1, 1, 10, 1 FROM school_dept d WHERE d.name = '艺术与设计学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '环境设计', '130503', 1, 1, 20, 1 FROM school_dept d WHERE d.name = '艺术与设计学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '产品设计', '130504', 1, 1, 30, 1 FROM school_dept d WHERE d.name = '艺术与设计学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '数字媒体艺术', '130508', 1, 1, 40, 1 FROM school_dept d WHERE d.name = '艺术与设计学院' AND d.school_id = 1;

-- ---------------- 专科(level=2)占位行 ⚠️ 需按官方专科目录核对 ----------------
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '汽车制造与试验技术', '560701', 2, 3, 10, 1 FROM school_dept d WHERE d.name = '智能车辆与新能源工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '道路与桥梁工程技术', '500211', 2, 3, 10, 1 FROM school_dept d WHERE d.name = '智慧交通与智能建造工程学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '大数据技术', '510205', 2, 3, 10, 1 FROM school_dept d WHERE d.name = '人工智能学院' AND d.school_id = 1;
INSERT IGNORE INTO school_major (dept_id, school_id, name, code, level, code_type, sort, enabled)
SELECT d.id, 1, '金融服务与管理', '530201', 2, 3, 10, 1 FROM school_dept d WHERE d.name = '数字经济与智慧管理学院' AND d.school_id = 1;
