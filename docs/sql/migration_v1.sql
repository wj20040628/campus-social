-- ============================================================================
-- 迁移:migration_v1 —— MVP 新增 9 张表 + user 表 6 个字段(2026-10-08)
--
-- 依据:docs/SPEC.md §6.1(新增表)/ §6.2(ALTER 现有表)
-- 目标:MySQL 8.4 LTS。本机 dev 主库为待装的 8.4 实例,端口 3307。
--
-- 第 8 节(院系与年级字典)是引导第 1 步从自由文本改为字典 id 的补丁,
-- 由本任务的执行者追加;其余各节为 MVP 首轮实现。
--
-- 执行前提:
--   1. 库 campus_social_v2 已由 sql/schema.sql 建好(22 张现有表)
--   2. 执行账号具备 CREATE / ALTER / INSERT 权限
--   3. 执行前先备份:mysqldump -P3307 -uroot -p campus_social_v2 > bak_v1.sql
--
-- 执行方式(一次性):
--   mysql -P3307 -uroot -p campus_social_v2 < docs/sql/migration_v1.sql
--
-- 幂等性说明:
--   - CREATE TABLE 全部带 IF NOT EXISTS,可重复执行
--   - 种子数据用 INSERT IGNORE,靠 uk_name 去重,可重复执行
--   - ALTER TABLE ADD COLUMN 在 MySQL 8.4 上**不支持** IF NOT EXISTS,
--     因此第 4 节不可重复执行。已加过列的库再跑会报 1060 Duplicate column,
--     此时单独执行第 4 节之外的部分即可(文末给了校验查询)
--
-- 字符集:utf8mb4(与现有 22 张表完全一致)
--
--   **这里刻意不写 COLLATE,只写 DEFAULT CHARSET=utf8mb4**,原因是一条实测出来的坑:
--
--   实测(2026-10-08,本机 MySQL 9.0.1):
--     SELECT @@collation_server                      -> utf8mb4_0900_ai_ci
--     SELECT DEFAULT_COLLATION_NAME FROM schemata
--       WHERE schema_name='campus_social_v2'         -> utf8mb4_unicode_ci
--     SELECT table_collation, COUNT(*) FROM tables
--       WHERE table_schema='campus_social_v2'
--       GROUP BY table_collation                     -> utf8mb4_0900_ai_ci, 22
--
--   即:现有 22 张表的实际 collation 是 utf8mb4_0900_ai_ci,**不是**库级声明的
--   utf8mb4_unicode_ci。原因是 schema.sql 每张表写的是 `DEFAULT CHARSET=utf8mb4`
--   而不带 COLLATE —— MySQL 在这种情况下取的是「该字符集的默认 collation」
--   (8.0 起为 utf8mb4_0900_ai_ci),而不是「库级默认 collation」。
--
--   SPEC §10.2 写的是 `utf8mb4 / utf8mb4_unicode_ci`,与磁盘实测不符。
--   若照 SPEC 字面给新表显式写 COLLATE=utf8mb4_unicode_ci,则
--   user JOIN user_tag / user JOIN report / user JOIN announcement 全部会抛
--   "Illegal mix of collations",且字符串比较走不上索引。
--
--   裁决:新表沿用与现有 22 张表**完全相同**的写法(`DEFAULT CHARSET=utf8mb4`,
--   不带 COLLATE),由 MySQL 解析成与兄弟表一致的 collation。
--   这样无论目标实例是 8.4 还是 9.x,新表与旧表永远同 collation。
--   已回传 team-lead,请求把 SPEC §10.2 的 collation 描述修正为
--   「字符集 utf8mb4;collation 与现有表对齐(实测 utf8mb4_0900_ai_ci)」。
-- ============================================================================

USE `campus_social_v2`;

-- ============================================================================
-- 第 1 节:F11 兴趣标签(本轮实现)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- interest_tag 标签字典
-- category: 1兴趣 2游戏 3运动 4学习 5生活
-- 人工维护,不做 UGC(避免脏标签与审核成本)
-- uk_name 保证标签唯一;use_count 由 user_tag 增减时事务内 SET x = x ± 1 维护
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `interest_tag` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '标签ID',
    `name`        VARCHAR(32) NOT NULL                COMMENT '标签名',
    `category`    TINYINT     NOT NULL                COMMENT '1兴趣 2游戏 3运动 4学习 5生活',
    `sort`        INT         NOT NULL DEFAULT 100    COMMENT '组内排序,升序;步长 10 便于插队',
    `use_count`   INT         NOT NULL DEFAULT 0      COMMENT '被多少用户选中,冷启动期为 0',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除,MyBatis-Plus 全局字段',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_category_sort` (`category`, `sort`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='兴趣标签字典';

-- ----------------------------------------------------------------------------
-- user_tag 用户-标签关系
-- uk_user_tag 保证同一用户对同一标签只有一行(PUT 覆盖写的幂等基础)
-- idx_tag 支撑「按标签反查人」(v1.1 发现页使用)
-- 注意:不建 FK 约束,与现有 schema.sql 一致(逻辑外键,应用层保证)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_tag` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL                COMMENT '用户ID',
    `tag_id`      BIGINT   NOT NULL                COMMENT '标签ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tag` (`user_id`, `tag_id`),
    KEY `idx_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-兴趣标签';

-- ============================================================================
-- 第 2 节:F4 举报(上线门槛项,本轮实现)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- report 举报
-- target_type 多态指向:1用户 2帖子 3评论 4聊天消息 5搭子
-- uk_report 是 AC-13 幂等的数据库兜底:同一人对同一目标只能有一条
-- idx_status_time 支撑后台「待审优先 + 最新在前」的列表查询
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `report` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '举报ID',
    `reporter_id`  BIGINT      NOT NULL                COMMENT '举报人ID',
    `target_type`  TINYINT     NOT NULL                COMMENT '1用户 2帖子 3评论 4聊天消息 5搭子',
    `target_id`    BIGINT      NOT NULL                COMMENT '被举报对象ID',
    `reason_type`  TINYINT     NOT NULL                COMMENT '1骚扰 2广告 3色情 4诈骗 5其他',
    `reason_text`  VARCHAR(500) NULL                   COMMENT '补充说明',
    `status`       TINYINT     NOT NULL DEFAULT 0      COMMENT '0待审 1成立 2驳回',
    `handler_id`   BIGINT      NULL                    COMMENT '处置管理员ID',
    `handle_time`  DATETIME    NULL                    COMMENT '处置时间',
    `handle_note`  VARCHAR(500) NULL                   COMMENT '处置备注,同步写 audit_log',
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report` (`reporter_id`, `target_type`, `target_id`),
    KEY `idx_status_time` (`status`, `create_time` DESC),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报';

-- ============================================================================
-- 第 3 节:F5 官方公告(本轮实现)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- announcement 公告
-- 不用 notify 表达的原因:notify 是用户级事件,公告是广播级内容,
-- 一人一行会造成 1 万用户 x 100 公告 = 100 万行的写入放大(SPEC §6.3)
-- idx_school_status_top_time 直接对应前台列表排序:
--   is_top DESC -> publish_time DESC,且 school_id / status 前置做等值过滤
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `announcement` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '公告ID',
    `title`           VARCHAR(120) NOT NULL               COMMENT '标题',
    `content`         TEXT        NOT NULL                COMMENT '正文,纯文本+换行,MVP 不做富文本',
    `category`        TINYINT     NOT NULL DEFAULT 6      COMMENT '1教务 2后勤 3活动 4社团 5紧急 6其他',
    `publisher_id`    BIGINT      NOT NULL                COMMENT '发布人ID',
    `school_id`       BIGINT      NOT NULL DEFAULT 1      COMMENT '学校ID,默认1,预留多校',
    `is_top`          TINYINT     NOT NULL DEFAULT 0      COMMENT '0否 1是',
    `top_expire_time` DATETIME    NULL                    COMMENT '置顶到期时间,到期后自然回落',
    `status`          TINYINT     NOT NULL DEFAULT 0      COMMENT '0草稿 1已发布 2已下架',
    `publish_time`    DATETIME    NULL                    COMMENT '发布时间,草稿为 NULL',
    `read_count`      INT         NOT NULL DEFAULT 0      COMMENT '已读人数,仅首次已读时 +1',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_school_status_top_time` (`school_id`, `status`, `is_top` DESC, `publish_time` DESC),
    KEY `idx_category` (`category`, `status`, `publish_time` DESC),
    KEY `idx_publisher` (`publisher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方公告';

-- ----------------------------------------------------------------------------
-- announcement_read 已读回执
-- uk_ann_user 是 AC-09 幂等的数据库兜底,重复上报走 INSERT IGNORE 不新增行
-- 只在实际有人读时写一行,未读数用 count 查询而非全量写入
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `announcement_read` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `announcement_id` BIGINT   NOT NULL                COMMENT '公告ID',
    `user_id`         BIGINT   NOT NULL                COMMENT '用户ID',
    `read_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '已读时间',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ann_user` (`announcement_id`, `user_id`),
    KEY `idx_user` (`user_id`, `read_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告已读回执';

-- ============================================================================
-- 第 4 节:user 表 ALTER(SPEC §6.2 + 一处增补申请)
-- ============================================================================

-- school_id:F2 校内归属。MVP 单校恒为 1,预留多校
ALTER TABLE `user`
    ADD COLUMN `school_id` BIGINT NOT NULL DEFAULT 1 COMMENT '学校ID,默认1,预留多校';

-- verify_status:F2 认证状态。0未认证 1已认证 2审核中 3驳回
-- AC-11 的判定依据:只有 1 才允许发起搭话与发布公告
ALTER TABLE `user`
    ADD COLUMN `verify_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未认证 1已认证 2审核中 3驳回';

-- stranger_msg_enabled:F3 强制护栏
-- 没有这个开关,免好友搭话上线即变骚扰工具(SPEC §6.2)
ALTER TABLE `user`
    ADD COLUMN `stranger_msg_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否接收陌生人消息 0否 1是';

-- ----------------------------------------------------------------------------
-- 【已批准】onboarding_step:F11 引导进度
--
-- 为什么必须有这一列(详见 ARCHITECTURE.md §3 Q10):
--   「用户是否已完成引导」无法从现有字段可靠推导。
--   AC-18 只约束第 1 步(dept 与 grade 非空),这一条可以推导;
--   但 POST /onboarding/complete 的幂等需要明确的「已完成」判据 ——
--   一个「0 标签 + 0 关注」的用户,与「第 3 步一个都没选就点了完成」的用户
--   在现有字段上完全同构,无法区分。
--   替代方案(用 Redis 键记录)不可靠:Redis 无持久化保证,
--   重启后用户会被重新推进引导流程。
--   代价:1 个 TINYINT 列,1 字节,不新增表,不需要 join。
--
-- 状态:team-lead 已批准,已回填 SPEC §6.2 与 §2 表。勿删。
-- ----------------------------------------------------------------------------
ALTER TABLE `user`
    ADD COLUMN `onboarding_step` TINYINT NOT NULL DEFAULT 0 COMMENT '引导进度 0未开始 1已填归属 2已选标签 3已完成';

-- ============================================================================
-- 第 5 节:buddy 搭子(本轮仅建表,不实现 Service / Controller)
--
-- ----------------------------------------------------------------------------
-- 本轮仅建表,不实现 Service / Controller / 页面。
-- 依据:SPEC §2.2 明确 buddy_post / buddy_join 归属 MVP+ 的 F7,
-- 本轮出 DDL 只是为了「先预留后释放」,避免第二批迭代再改一次表。
-- Phase 3 禁止实现这两个表的任何 Service、Controller、Mapper 业务方法与页面。
-- 对应 7 个 buddy_* 端点同样不实现,见 docs/api/openapi.yaml 的 info.description。
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- buddy_post 搭子卡片
-- 不复用 activity 的原因:activity 有报名截止 + 活动状态机 + 线下场地语义,
-- 搭子卡片是轻量招募("今晚王者缺一个"),语义不同,硬塞会让两张表都变脏。
-- 但报名防超卖逻辑必须复用 activity_signup 的
--   "INSERT 幂等键 + UPDATE ... WHERE joined_count < want_count 乐观锁"同事务写法
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `buddy_post` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '搭子卡片ID',
    `user_id`      BIGINT      NOT NULL                COMMENT '发起人ID',
    `tag_id`       BIGINT      NULL                    COMMENT '关联兴趣标签,可为空',
    `title`        VARCHAR(120) NOT NULL               COMMENT '一句话邀约,如「周五下午三点三号场,缺两个人」',
    `content`      VARCHAR(1000) NULL                  COMMENT '补充说明',
    `want_count`   INT         NOT NULL DEFAULT 1      COMMENT '招募人数',
    `joined_count` INT         NOT NULL DEFAULT 0      COMMENT '已报名人数,乐观锁防超卖',
    `status`       TINYINT     NOT NULL DEFAULT 0      COMMENT '0招募中 1已满 2已关闭 3下架',
    `expire_time`  DATETIME    NULL                    COMMENT '过期时间,如「今晚」的结束时刻',
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tag_status_time` (`tag_id`, `status`, `create_time` DESC),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='搭子卡片(本轮仅建表,不实现)';

-- ----------------------------------------------------------------------------
-- buddy_join 报名
-- uk_buddy_user 保证同一用户对同一卡片只有一行,是报名幂等的基础
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `buddy_join` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `buddy_id`    BIGINT   NOT NULL                COMMENT '搭子卡片ID',
    `user_id`     BIGINT   NOT NULL                COMMENT '报名人ID',
    `status`      TINYINT  NOT NULL DEFAULT 0      COMMENT '0待确认 1已加入 2已拒绝 3已退出',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_buddy_user` (`buddy_id`, `user_id`),
    KEY `idx_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='搭子报名(本轮仅建表,不实现)';

-- ============================================================================
-- 第 6 节:interest_tag 种子数据(60 条,可重复执行)
--
-- 选词原则:中国大学生真会点的标签,不是占位数据。
-- 游戏类必须覆盖 王者 / LOL / 原神 / CS / 桌游 / 剧本杀(SPEC 指定)。
-- sort 按组内 10、20、30 递增,留出插队空间,不用 1、2、3 连续编号。
-- ============================================================================

INSERT IGNORE INTO `interest_tag` (`name`, `category`, `sort`) VALUES
-- 1 兴趣
('摄影',     1, 10),
('吉他',     1, 20),
('画画',     1, 30),
('书法',     1, 40),
('追剧',     1, 50),
('动漫',     1, 60),
('电影',     1, 70),
('读书',     1, 80),
('唱歌',     1, 90),
('烘焙',     1, 100),
('咖啡',     1, 110),
('汉服',     1, 120),
('宠物',     1, 130),
-- 2 游戏
('王者荣耀', 2, 10),
('英雄联盟', 2, 20),
('原神',     2, 30),
('CS2',      2, 40),
('桌游',     2, 50),
('剧本杀',   2, 60),
('和平精英', 2, 70),
('星穹铁道', 2, 80),
('金铲铲之战', 2, 90),
('我的世界', 2, 100),
('双人成行', 2, 110),
('Switch联机', 2, 120),
('第五人格', 2, 130),
('永劫无间', 2, 140),
-- 3 运动
('篮球',     3, 10),
('羽毛球',   3, 20),
('乒乓球',   3, 30),
('跑步',     3, 40),
('健身',     3, 50),
('游泳',     3, 60),
('足球',     3, 70),
('排球',     3, 80),
('网球',     3, 90),
('爬山',     3, 100),
('骑行',     3, 110),
('瑜伽',     3, 120),
-- 4 学习
('考研',     4, 10),
('四六级',   4, 20),
('保研',     4, 30),
('雅思托福', 4, 40),
('编程',     4, 50),
('算法竞赛', 4, 60),
('数学建模', 4, 70),
('期末复习', 4, 80),
('自习室',   4, 90),
('论文写作', 4, 100),
('组队参赛', 4, 110),
-- 5 生活
('干饭',     5, 10),
('探店',     5, 20),
('拼车',     5, 30),
('夜宵',     5, 40),
('早八人',   5, 50),
('兼职实习', 5, 60),
('志愿活动', 5, 70),
('社团',     5, 80),
('老乡',     5, 90),
('露营',     5, 100);

-- ============================================================================
-- 第 7 节:执行后校验(应逐条手工确认)
-- ============================================================================

-- 7.1 表数应为 29(22 张现有 + 7 张新增)
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'campus_social_v2' AND table_type = 'BASE TABLE';
-- 期望:29

-- 7.2 新增 7 张表应全部存在
SELECT table_name, table_comment
FROM information_schema.tables
WHERE table_schema = 'campus_social_v2'
  AND table_name IN ('interest_tag','user_tag','report',
                     'announcement','announcement_read','buddy_post','buddy_join');
-- 期望:7 行

-- 7.3 user 表 4 个新列应存在且默认值正确
SELECT column_name, column_type, column_default, column_comment
FROM information_schema.columns
WHERE table_schema = 'campus_social_v2' AND table_name = 'user'
  AND column_name IN ('school_id','verify_status','stranger_msg_enabled','onboarding_step');
-- 期望:4 行,默认值分别为 1 / 0 / 1 / 0

-- 7.4 种子标签应为 60 条,且 5 个分类都有
SELECT category, COUNT(*) AS cnt
FROM interest_tag WHERE is_deleted = 0 GROUP BY category ORDER BY category;
-- 期望:1->13  2->14  3->12  4->11  5->10  合计 60

-- 7.5 字符集一致性(防止 Illegal mix of collations)
-- 判据不是「等于某个固定值」,而是「新表与现有 user 表同 collation」
SELECT t.table_name, t.table_collation
FROM information_schema.tables t
WHERE t.table_schema = 'campus_social_v2'
  AND t.table_type = 'BASE TABLE'
  AND t.table_collation <> (
        SELECT u.table_collation FROM information_schema.tables u
        WHERE u.table_schema = 'campus_social_v2' AND u.table_name = 'user');
-- 期望:0 行。任何一行都意味着该表与 user 联表会抛 Illegal mix of collations

-- 7.6 关键唯一索引应存在(幂等的数据库兜底)
SELECT table_name, index_name
FROM information_schema.statistics
WHERE table_schema = 'campus_social_v2'
  AND index_name IN ('uk_name','uk_user_tag','uk_report','uk_ann_user','uk_buddy_user')
GROUP BY table_name, index_name;
-- 期望:5 行

-- ============================================================================
-- 第 8 节:院系与年级字典(引导第 1 步,从自由文本改为字典 id)
--
-- 【为什么要补这两张表】
--   改造前 user.dept / user.grade 是用户自由填的字符串。AC-14(同院系推荐/筛选)
--   依赖 dept 相等判断,而「计算机学院」与「计科」在库里是两个不同的值,
--   同院系的人被漏掉一半。自由输入的收敛性无法靠前端约束保证——用户可以
--   绕过前端直接调接口,也可以只是换个简写——必须在数据层消灭自由度。
--
-- 【为什么 user 表同时保留 dept 名称列与新增 dept_id 列】
--   两个都留,各司其职:
--     dept_id / grade_id = 权威值。匹配(AC-14)一律比 id,
--                        院系改名(合并/更名是真实事件)只影响展示文本,
--                        不影响任何匹配逻辑——这正是存id 的核心收益。
--     dept / grade       = 名称快照。既有七八处列表查询(推荐理由、帖子作者卡、
--                        搜索结果、公告未读名单)直接在读这两个字符串,
--                        全改成 join 会把一次字典化变成一次大范围查询重写。
--                        快照由 service 在写入时从字典表一并落库。
--
-- 【兼容性】存量用户的 dept_id / grade_id 为 NULL。代码里所有「是否已填」
--   判断都保留了对名称列的回退(见 DictService#hasDept),推荐 SQL 也用
--   COALESCE 语义回退到名称比较,避免老用户被静默降级或被误判为未填。
--   存量回填见第 8.7 节(当前无需执行,实测 0 命中)。
--
-- 幂等性:CREATE TABLE 带 IF NOT EXISTS,种子用 INSERT IGNORE + uk_school_name
--   去重,与既有各节风格一致,已实测重复执行 55 条全部命中唯一键被跳过、条数不变。
--
--   【重复执行的已知行为】8.4 节的两条 ALTER 与第 4 节同理:
--   MySQL 8.4 的 ADD COLUMN 不支持 IF NOT EXISTS,已加过列的库再跑会报
--   1060 Duplicate column。注意该错误会中断整个脚本,导致 8.5/8.6 的种子
--   不会执行——所以「重复执行」请按下面顺序手动分两次跑,不要整文件重跑:
--     第一次:8.1-8.3(建表)+ 8.4(加列)+ 8.5/8.6(种子)+ 8.7(回填)
--     第二次(已建过列的库):只跑 8.1-8.3 与 8.5/8.6,跳过 8.4
--   实测:种子部分单独重复执行两次,dept 恒为 55 条、grade 恒为 8 条。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- school_dept 院系字典
--
-- uk_school_name 而不是只对 name 建唯一键:多校时两个学校可以都有
-- 「外国语学院」,只按 name 唯一会导致第二个学校插不进去。
-- 提前把 school_id 放进唯一键,免得多校上线时回头改主键约束(需停机窗口)。
--
-- enabled 与 is_deleted 不是冗余,是两种不同的退役语义:
--   enabled=0     学校撤销或合并了该院系。不再让新用户选,
--                 但历史用户仍持有该 id,主页必须仍能显示原名
--                 (按 enabled=1 过滤会让老用户主页的院系变成空白)。
--   is_deleted=1  这条字典记录彻底不要了(误录的脏数据)。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `school_dept` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '院系ID',
    `name`        VARCHAR(64) NOT NULL                COMMENT '院系名称',
    `school_id`   BIGINT      NOT NULL DEFAULT 1      COMMENT '所属学校ID,MVP 单校恒为 1',
    `sort`        INT         NOT NULL DEFAULT 100    COMMENT '排序权重,升序;热门院系给小值排前面,步长 10 便于插队',
    `enabled`     TINYINT     NOT NULL DEFAULT 1      COMMENT '1启用 0停用;停用=学校撤销或合并,不再让新用户选但历史数据仍可解析',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除,MyBatis-Plus 全局字段',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_school_name` (`school_id`, `name`),
    KEY `idx_school_enabled_sort` (`school_id`, `enabled`, `sort`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='院系字典';

-- ----------------------------------------------------------------------------
-- school_grade 年级字典
--
-- 为什么不带 school_id:年级是「入学年份」,2024 级在任何学校都是 2024 级,
-- 跨校同年级天然可比。院系带 school_id 而年级不带,这个不对称是有意的。
--
-- enroll_year 单独存一份而不从 name 里截:截字符串等于把解析规则复制到
-- 每一处消费方,而「2024级」「2024 级」「2024级本科」都能解析出 2024。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `school_grade` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '年级ID',
    `name`        VARCHAR(32) NOT NULL                COMMENT '年级名称,如 2024级',
    `enroll_year` SMALLINT    NOT NULL                COMMENT '入学年份,如 2024',
    `sort`        INT         NOT NULL DEFAULT 100    COMMENT '排序权重,升序;最近入学的年级给小值排最前',
    `enabled`     TINYINT     NOT NULL DEFAULT 1      COMMENT '1启用 0停用;已毕业的年级置 0,不再让新用户选但历史数据仍可解析',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_enabled_sort` (`enabled`, `sort`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年级字典';

-- ----------------------------------------------------------------------------
-- 8.4 user 表增补:字典 id
--
-- 存 id 而不是只存名称的理由(派单时点名要给的判断):
--   院系改名是真实会发生的事——高校合并潮下「XX学院」改名的案例每年都有。
--   存名称时,改名后老用户仍持旧名、新用户持新名,AC-14 的相等判断会把
--   明明同一个院系的人拆成两拨,推荐与筛选同时失效,而且这种错误不会报错,
--   只会表现为「推荐变差了」,极难定位。存 id 则改名只改字典表一行,
--   所有用户的匹配关系自动保持正确。
--   代价是需要处理存量数据,见 8.7(实测 0 命中,当前不回填,靠代码名称回退兜住)。
-- ----------------------------------------------------------------------------
ALTER TABLE `user`
    ADD COLUMN `dept_id` BIGINT NULL COMMENT '院系字典id,指向 school_dept.id;匹配与筛选的权威依据,存量行为 NULL';

ALTER TABLE `user`
    ADD COLUMN `grade_id` BIGINT NULL COMMENT '年级字典id,指向 school_grade.id;存量行为 NULL';

-- 不建外键约束,与现有 22 张表口径一致(逻辑外键,应用层保证)。
-- 理由:院系字典要能被单独维护与停用,外键的 RESTRICT 会让
-- 「停用一个还有用户的院系」这个正常运维动作直接失败。

-- ----------------------------------------------------------------------------
-- 8.5 school_dept 种子数据(55 条真实院系,可重复执行)
--
-- 选词原则:中国高校真实存在的院系设置,按「理工科为主、文科医教全覆盖」
-- 铺开,不使用「测试1」「示例2」这类占位数据。
-- sort 步长 10,留出运营插队空间;前几个给的是招生规模最大的传统大院,
-- 引导页默认排在最前。
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `school_dept` (`name`, `school_id`, `sort`) VALUES
-- 理工与信息
('计算机科学与技术学院', 1, 10),
('软件工程学院',         1, 20),
('电子信息工程',         1, 30),
('通信工程',             1, 40),
('自动化学院',           1, 50),
('人工智能',             1, 60),
('信息安全',             1, 70),
('数据科学与大数据技术', 1, 80),
-- 机电与土建
('机械工程',             1, 90),
('车辆工程',             1, 100),
('电气工程及其自动化',   1, 110),
('土木工程学院',         1, 120),
('建筑学院',             1, 130),
('材料科学与工程',       1, 140),
-- 数理与化学
('数学与统计学院',       1, 150),
('物理学院',             1, 160),
('化学化工学院',         1, 170),
('生物科学与技术学院',   1, 180),
-- 医学
('临床医学院',           1, 190),
('基础医学院',           1, 200),
('护理学院',             1, 210),
('药学院',               1, 220),
('公共卫生学院',         1, 230),
('口腔医学院',           1, 240),
-- 人文社科
('中文系',               1, 250),
('新闻与传播学院',       1, 260),
('外国语言文学学院',     1, 270),
('历史学院',             1, 280),
('哲学与社会发展学院',   1, 290),
('经济学院',             1, 300),
('管理学院',             1, 310),
('法学院',               1, 320),
('教育学院',             1, 330),
('心理与认知科学学院',   1, 340),
('社会学系',             1, 350),
-- 农林与环境
('农学院',               1, 360),
('园林与风景园林学院',   1, 370),
('环境科学与工程学院',   1, 380),
('地理与旅游学院',       1, 390),
-- 艺术与体育
('美术学院',             1, 400),
('音乐学院',             1, 410),
('设计艺术学院',         1, 420),
('体育学院',             1, 430),
-- 财经与商贸
('会计学院',             1, 440),
('金融学院',             1, 450),
('国际经济与贸易',       1, 460),
('工商管理学院',         1, 470),
-- 其他常见设置
('电气工程学院',         1, 480),
('能源与动力工程学院',   1, 490),
('航空航天学院',         1, 500),
('生命科学学院',         1, 510),
('海洋科学学院',         1, 520),
('地球科学学院',         1, 530),
('安全科学与工程学院',   1, 540),
('食品科学与工程学院',   1, 550);

-- ----------------------------------------------------------------------------
-- 8.6 school_grade 种子数据(可重复执行)
--
-- 覆盖近七届。已毕业的年级(2019 级及更早)保留在表里但 enabled=0:
--   它们仍需存在,因为两年前注册的老用户资料里就是这些年级,
--   删掉会让老用户的年级显示为空白。
--   sort 升序 => 最近入学的年级排在最前。
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `school_grade` (`name`, `enroll_year`, `sort`, `enabled`) VALUES
('2026级', 2026, 10, 1),
('2025级', 2025, 20, 1),
('2024级', 2024, 30, 1),
('2023级', 2023, 40, 1),
('2022级', 2022, 50, 1),
('2021级', 2021, 60, 1),
('2020级', 2020, 70, 0),
('2019级', 2019, 80, 0);

-- ----------------------------------------------------------------------------
-- 8.7 【当前无需执行】存量 dept_id / grade_id 回填
--
-- 本段在真实存量数据上实测为 0 命中,详见下方说明。当前阶段【不要执行】,
-- 下面两条 UPDATE 保留仅作参考,供日后确认了可靠映射规则时再启用。
--
-- 不是迁移的必需步骤:代码里所有「是否已填」判断与推荐 SQL 都已对
-- dept_id IS NULL 做了名称回退,存量用户不会被误判为未填,推荐也不降级。
--
-- 只做「名称完全相等」的精确匹配。匹配不上的行保持 NULL 是正确结果——
-- 猜错会让用户被归到错误的院系,比留空更糟。留空的行由用户下次进引导页时自行重选。
--
-- 【实测】在 campus_social_v2 的真实存量数据上跑过,两条回填都是 0 行命中。
-- 执行者不要以为「跑完就有 id 了」,原因如下,这两条口径差异不是 bug 而是客观现实:
--
--   1) 年级口径不同。存量 grade 存的是「大三/大二/大一」这种学习阶段口径,
--      而 school_grade.name 存的是「2026级」这种入学年份口径。
--      两套命名体系无法通过字符串相等对上,不是改字典能解决的,改字典只会
--      反过来让新旧用户在年级上更不一致。需要迁移的话只能靠入学年份反推,
--      而存量库里没有入学年份字段,信息已经丢失,做不了。
--
--   2) 院系是简称 vs 官方全称。存量「计算机学院」对应字典
--      「计算机科学与技术学院」,「人工智能学院」对应「人工智能」。
--      注意别用 LIKE 模糊匹配来「提高命中率」:存量「艺术学院」字面最接近的
--      字典项是「设计艺术学院」,模糊匹配会把用户静默归错到另一个院系,
--      这种错比留空危险得多,因为它不会报错,只表现为推荐结果变得莫名其妙。
--
-- 结论:当前阶段不跑这段是正确的。存量用户 dept_id/grade_id 为 NULL,
-- 由代码里的名称回退逻辑兜住(见 UserMapper#selectRecommendUsers 的
-- dept_id IS NULL 分支与 DictService#hasDept/hasGrade),不会误判为未填,
-- 推荐也不降级。待存量用户在引导页重选一次后 id 自然补齐。
--
-- 执行前请先跑一次 SELECT 看看影响面:
--   SELECT COUNT(*) FROM `user` WHERE dept_id IS NULL AND dept IS NOT NULL AND TRIM(dept) <> '';
-- ----------------------------------------------------------------------------
-- UPDATE `user` u
--   JOIN `school_dept` d
--     ON d.school_id = u.school_id AND d.name = TRIM(u.dept) AND d.is_deleted = 0
--  SET u.dept_id = d.id
--  WHERE u.dept_id IS NULL AND u.dept IS NOT NULL AND TRIM(u.dept) <> '';

-- UPDATE `user` u
--   JOIN `school_grade` g
--     ON g.name = TRIM(u.grade) AND g.is_deleted = 0
--  SET u.grade_id = g.id
--  WHERE u.grade_id IS NULL AND u.grade IS NOT NULL AND TRIM(u.grade) <> '';

-- ============================================================================
-- 第 9 节:第 8 节执行后校验
-- ============================================================================

-- 9.1 两张字典表应存在
SELECT table_name, table_comment
FROM information_schema.tables
WHERE table_schema = 'campus_social_v2'
  AND table_name IN ('school_dept', 'school_grade');
-- 期望:2 行

-- 9.2 院系种子应为 55 条,全部启用
SELECT COUNT(*) AS dept_total, SUM(enabled = 1) AS dept_enabled
FROM school_dept WHERE is_deleted = 0;
-- 期望:dept_total=55, dept_enabled=55

-- 9.3 年级种子应为 8 条,其中 6 条启用(2026-2021)、2 条停用(2020/2019)
SELECT COUNT(*) AS grade_total, SUM(enabled = 1) AS grade_enabled
FROM school_grade WHERE is_deleted = 0;
-- 期望:grade_total=8, grade_enabled=6

-- 9.4 user 表两个新列应存在且可空
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'campus_social_v2' AND table_name = 'user'
  AND column_name IN ('dept_id', 'grade_id');
-- 期望:2 行,is_nullable 均为 YES,column_default 均为 NULL

-- 9.5 唯一索引应生效(这是种子 INSERT IGNORE 幂等的基础)
SELECT table_name, index_name
FROM information_schema.statistics
WHERE table_schema = 'campus_social_v2'
  AND index_name IN ('uk_school_name', 'uk_name')
GROUP BY table_name, index_name;
-- 期望:school_dept.uk_school_name 与 school_grade.uk_name 各 1 行

-- 9.6 collation 一致性(与既有表同 collation,防 Illegal mix of collations)
SELECT t.table_name, t.table_collation
FROM information_schema.tables t
WHERE t.table_schema = 'campus_social_v2'
  AND t.table_name IN ('school_dept', 'school_grade')
  AND t.table_collation <> (
        SELECT u.table_collation FROM information_schema.tables u
        WHERE u.table_schema = 'campus_social_v2' AND u.table_name = 'user');
-- 期望:0 行

-- ============================================================================
-- 附:运维手册 —— 院系字典日常维护
--
-- 字典表由后台人工维护,本文件不提供增删改端点(开放写接口等于允许任意用户
-- 造院系,脏字典会直接毁掉 AC-14 的匹配价值)。以下为 DBA/运营手工操作指引。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 场景 1:新增一个院系
--
-- sort 步长 10,便于在中间插队而不必重排全表。不要给新院系用「最大 sort + 1」,
-- 那样插队时要改一列数据。用 5 / 15 这类半值即可。
-- ---------------------------------------------------------------------------
-- INSERT INTO `school_dept` (`name`, `school_id`, `sort`) VALUES ('人工智能学院', 1, 65);

-- ---------------------------------------------------------------------------
-- 场景 2:院系被撤销或合并(推荐做法:停用而非删除)
--
-- 例:「艺术学院」与「设计艺术学院」合并为「美术学院」。
-- 正确做法是把被合并的两个置enabled=0,而不是 DELETE。
--
-- 为什么要区分 enabled 与 is_deleted:
--   enabled=0 = 学校撤销/合并。**不再让新用户选,但历史数据仍可解析出名称**。
--              若DELETE 掉,所有已选该院系的用户主页院系会变空白——
--              这是「改一个字典项,毁掉一批用户资料」的事故。
--   is_deleted=1 = 彻底删记录。仅在字典项本身录错(如重复录入)时使用。
--
-- 停用后:新用户选不到(写入时返回 1040 DEPT_INVALID),
--         老用户仍能在主页看到「艺术学院」,推荐也仍按 dept_id 正常工作。
-- ---------------------------------------------------------------------------
-- UPDATE `school_dept` SET `enabled` = 0 WHERE `name` IN ('艺术学院', '设计艺术学院');

-- ---------------------------------------------------------------------------
-- 场景 3:院系改名(★ 必须同步回填名称快照,否则展示与匹配会脱节)
--
-- 背景:user.dept 是「名称快照」冗余列,dept_id 才是权威值。
-- 改名分两步,**顺序不能颠倒**:
--
--   第1 步:改字典
--     UPDATE `school_dept` SET `name` = '计算机学院' WHERE `name` = '计算机科学与技术学院';
--
--   第 2 步:回填快照(★ 漏了这步的后果见下)
--     UPDATE `user` u JOIN `school_dept` d ON d.id = u.dept_id
--        SET u.dept = d.name
--      WHERE u.dept_id IS NOT NULL AND u.dept <> d.name;
--
-- 【为什么第 2 步不能漏】匹配(AC-14 同院系推荐)走 dept_id,改名后**不受影响**;
-- 但列表页、帖子作者卡、搜索结果、`UserVO` 这些地方直接读 user.dept 字符串。
-- 漏回填的结果是:同一个人在不同页面上显示两个不同的院系名
--(推荐列表显示新名,个人主页显示旧名),而且不报错、不会有任何告警。
-- 这类「数据不一致但功能正常」的故障只能靠肉眼比对发现,极难定位。
--
-- 【为什么不能反过来只改 user.dept】那会造成「展示名称与匹配 id 指向不同院系」,
-- 比上面那种更糟:用户点进别人主页看到的院系和推荐里给他打分的院系对不上。
-- dept_id 是唯一权威,名称永远是它的快照。
--
-- 执行第 2 步前建议先 SELECT 看影响面:
--   SELECT COUNT(*) FROM `user` u JOIN `school_dept` d ON d.id = u.dept_id
--    WHERE u.dept_id IS NOT NULL AND u.dept <> d.name;
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 场景 4:年级退役(每年九月)
--
-- 已毕业的年级置 enabled=0,保留在表里供历史用户回显。
-- **不要 DELETE** —— 两年前注册的用户资料里就是那个年级,删了就变空白。
-- 每年只新增一行(当届),不删除任何行,所以字典表不存在膨胀问题。
-- ---------------------------------------------------------------------------
-- INSERT INTO `school_grade` (`name`, `enroll_year`, `sort`) VALUES ('2027级', 2027, 5);
-- UPDATE `school_grade` SET `enabled` = 0 WHERE `enroll_year` = 2020;

-- ============================================================================
-- 附:回滚脚本(仅在需要撤销本迁移时使用,勿常规执行)
--
-- ALTER TABLE `user` DROP COLUMN `grade_id`;
-- ALTER TABLE `user` DROP COLUMN `dept_id`;
-- DROP TABLE IF EXISTS `school_grade`;
-- DROP TABLE IF EXISTS `school_dept`;
-- DROP TABLE IF EXISTS `buddy_join`;
-- DROP TABLE IF EXISTS `buddy_post`;
-- DROP TABLE IF EXISTS `announcement_read`;
-- DROP TABLE IF EXISTS `announcement`;
-- DROP TABLE IF EXISTS `report`;
-- DROP TABLE IF EXISTS `user_tag`;
-- DROP TABLE IF EXISTS `interest_tag`;
-- ALTER TABLE `user` DROP COLUMN `onboarding_step`;
-- ALTER TABLE `user` DROP COLUMN `stranger_msg_enabled`;
-- ALTER TABLE `user` DROP COLUMN `verify_status`;
-- ALTER TABLE `user` DROP COLUMN `school_id`;
-- ============================================================================
