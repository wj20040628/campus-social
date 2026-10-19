-- ============================================================================
-- migration_f12: 新增 school_major 专业字典 + user 表增补 major_id
--
-- 背景:注册系统要支撑「学院 → 专业」二级联动下拉,以及学号六层校验
-- (纯数字 / 长度 / 年级 / 层次 / 专业码 / 班内序号)。此前专业这一级无表,
-- 专业码 → 学院的映射硬编码在 Java(StudentNoAutoPassSupport)与前端(schoolDict.js),
-- 双份数据源会漂移。本迁移把映射收归为数据库唯一数据源。
--
-- 运行:mysql.exe -uroot -p062828 campus_social_v2 < 本文件
-- 幂等:建表用 IF NOT EXISTS;user 列增补按既有迁移约定(一次执行)。
-- ============================================================================

-- 1) 专业字典(学院下属专业)
--    为什么 level 放在专业而不是学院:一个学院同时有本科和专科专业,
--    层次是专业的属性。级联下拉按「学院 + 本专科」过滤,故索引含 level。
--    为什么 code 用 CHAR(6):学号专业码定长 6 位、可含前导 0(如 080901),
--    CHAR 锁死长度、比较不做尾部空格处理,与定长编码语义一致。
--    班内序号(学号末 2 位)不在此表存储:它是学号里的每位学生序号,
--    由应用层校验范围(00~69),不属于专业元数据。
CREATE TABLE IF NOT EXISTS `school_major` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '专业ID',
    `dept_id`     BIGINT      NOT NULL                COMMENT '所属学院ID,指向 school_dept.id;学院→专业级联的父键',
    `school_id`   BIGINT      NOT NULL DEFAULT 1      COMMENT '所属学校ID,MVP 单校恒为 1;与 school_dept.school_id 对齐(刻意冗余,便于按校过滤专业,必须=dept.school_id)',
    `name`        VARCHAR(64) NOT NULL                COMMENT '专业名称,如 计算机科学与技术',
    `code`        CHAR(6)     NOT NULL                COMMENT '6 位校内专业编码,对应学号位 3-8(=大类4位+专业位号2位);定长、可含前导 0',
    `level`       TINYINT     NOT NULL                COMMENT '层次:1=本科 2=专科;对应学号层次标记(本科05/5、专科06/6)',
    `code_type`   TINYINT     NOT NULL DEFAULT 1      COMMENT '专业码类型:1=本科国标码 2=专科校内实锤码 3=高职占位码;区分编码来源与可信度',
    `sort`        INT         NOT NULL DEFAULT 100    COMMENT '排序权重,升序;同院内热门专业给小值,步长 10 便于插队',
    `enabled`     TINYINT     NOT NULL DEFAULT 1      COMMENT '1启用 0停用;该专业停招则不再让新用户选,但历史数据仍可解析',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除,MyBatis-Plus 全局字段',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_school_code` (`school_id`, `code`) COMMENT '同一学校内 6 位专业码唯一;学号按码查专业的唯一入口',
    UNIQUE KEY `uk_dept_name` (`dept_id`, `name`)     COMMENT '同一学院内专业名唯一,防止重复录入',
    KEY `idx_dept_level_enabled_sort` (`dept_id`, `level`, `enabled`, `sort`, `id`) COMMENT '学院→专业下拉:按学院+层次过滤+启用+排序(层次来自本专科下拉)',
    CONSTRAINT `fk_major_dept` FOREIGN KEY (`dept_id`)
        REFERENCES `school_dept` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专业字典(学院下属专业,支撑级联下拉与学号专业码校验)';

-- 2) user 表增补 major_id(注册收集专业后的权威归属列,与 dept_id/grade_id 同口径)
--    层次(level)可由 major.level 派生,不冗余存储;如需按层次直接过滤可后续补 user.level。
--    注:按既有迁移约定一次执行;重复执行会因「列已存在」报错,属预期。
ALTER TABLE `user`
    ADD COLUMN `major_id` BIGINT NULL COMMENT '专业字典id,指向 school_major.id;匹配与筛选的权威依据,存量行为 NULL';
