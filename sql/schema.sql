-- ============================================================
-- 校园社交(友声)数据库完整基线 schema
-- MySQL 8.4 LTS / 字符集 utf8mb4 / InnoDB
-- 通用字段(每张表):id, create_time, update_time, is_deleted
-- 逻辑外键(不建 FK 约束,由应用层保证一致性)
--
-- 【2026-10-23 重构:本文件恢复为唯一完整基线】
--   重构前的问题(代码审查报告 P0-1):
--     - 只有 26 张表,而实体有 37 个 —— 缺 11 张表,建库必全站 500;
--     - user 表缺 10 列(email/verify_status/notify_pref/bio 等);
--     - post 缺 favorite_count、activity 缺 creator_id;
--     - 文件尾用裸 ALTER 追加 review_task.ai_* 三列,重复执行必报 1060。
--   现在:全部 DDL(散落在 docs/sql/migration_v1|v2.sql 与 sql/migration_*.sql
--   中)已合并回本文件,全新建库只需跑这一个文件即可。
--
--   已存在的库不要重跑本文件(全是 CREATE IF NOT EXISTS,不会加列),
--   升级请按顺序跑 sql/migration_*.sql;本次重构的增量见
--   sql/migration_2026-10-23_baseline_sync.sql。
--
-- 字符集约定:所有表统一 `DEFAULT CHARSET=utf8mb4` 且不写 COLLATE ——
--   MySQL 会取该字符集的默认 collation(8.0+ 为 utf8mb4_0900_ai_ci),
--   与历史 22 张表完全一致。显式写 COLLATE 会造成跨表 JOIN 抛
--   "Illegal mix of collations" 且字符串比较走不上索引。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `campus_social_v2`
    DEFAULT CHARACTER SET utf8mb4;
    -- 注:库级 collation 不显式指定,与表级保持一致

USE `campus_social_v2`;

-- ------------------------------------------------------------
-- 用户
-- status: 0正常 1冻结 2注销
-- role: 0普通 1管理员
-- verify_status: 0未认证 1已认证 2审核中 3驳回
--   AC-11 判定依据:只有 1 才允许发起搭话与发布公告
-- follower_count / following_count / unread_notify_count / joined_circle_count 冗余计数
--   在关注/取关/新通知/加圈退圈 事务内 SET x = x ± 1,绝不读改写
-- 字典 id 与名称快照双存:
--   dept_id/grade_id/major_id = 权威值(匹配一律比 id,改名不影响匹配)
--   dept/grade               = 名称快照(列表直接读,避免 join)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`              VARCHAR(64)  NOT NULL                COMMENT '登录主标识:学号(或历史遗留的邮箱/手机号)',
    `password_hash`         VARCHAR(128) NOT NULL                COMMENT 'BCrypt',
    `student_no`            VARCHAR(32)  NULL                    COMMENT '学号(可选)',
    `nickname`              VARCHAR(64)  NULL,
    `bio`                   VARCHAR(200) NULL                    COMMENT '个人介绍,NULL/空=还没写',
    `avatar_url`            VARCHAR(512) NULL,
    `cover_url`             VARCHAR(512) NULL                    COMMENT '个人主页封面图(相对路径),NULL=默认渐变封面',
    `grade`                 VARCHAR(16)  NULL                    COMMENT '年级名称快照',
    `grade_id`              BIGINT       NULL                    COMMENT '年级字典id → school_grade.id',
    `dept`                  VARCHAR(64)  NULL                    COMMENT '院系名称快照',
    `dept_id`               BIGINT       NULL                    COMMENT '院系字典id → school_dept.id',
    `major_id`              BIGINT       NULL                    COMMENT '专业字典id → school_major.id;学号专业码校验的权威归属',
    `school_id`             BIGINT       NOT NULL DEFAULT 1      COMMENT '学校ID,默认1,预留多校',
    `campus`                TINYINT      NULL                    COMMENT '校区 1东 2西 NULL未填;与院系解耦独立自选,改不触发重审',
    `email`                 VARCHAR(64)  NULL                    COMMENT '安全凭证邮箱,有值时唯一(找回密码/绑定/邮箱验证码登录)',
    `verify_status`         TINYINT      NOT NULL DEFAULT 0      COMMENT '0未认证 1已认证 2审核中 3驳回',
    `stranger_msg_enabled`  TINYINT      NOT NULL DEFAULT 1      COMMENT '是否接收陌生人消息 0否 1是(F3 护栏)',
    `onboarding_step`       TINYINT      NOT NULL DEFAULT 0      COMMENT '引导进度 0未开始 1已填归属 2已选标签 3已完成',
    `status`                TINYINT      NOT NULL DEFAULT 0      COMMENT '0正常 1冻结 2注销',
    `role`                  TINYINT      NOT NULL DEFAULT 0      COMMENT '角色:0普通用户 1管理员',
    `notify_pref`           TEXT         NULL                    COMMENT '通知偏好JSON:{"disabled":[类型id]}',
    `follower_count`        INT          NOT NULL DEFAULT 0,
    `following_count`       INT          NOT NULL DEFAULT 0,
    `unread_notify_count`   INT          NOT NULL DEFAULT 0,
    `joined_circle_count`   INT          NOT NULL DEFAULT 0      COMMENT '加入的圈子数(冗余,加圈/退圈事务内±1)',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`            TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_student_no` (`student_no`),
    UNIQUE KEY `uk_user_email` (`email`),
    KEY `idx_dept_grade` (`dept`, `grade`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ------------------------------------------------------------
-- 关系
-- type: 1关注 2好友 3黑名单
-- 好友双向各存一行；关注计数在事务内 ±1 user.follower_count / following_count
-- 注意:2026-10-23 起 POST /relation/friend 已移除(审查 P0-4:单方面成立可绕过
--   陌生人搭话护栏)。好友关系如需恢复必须做成申请-同意两阶段。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `relation` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `from_user_id`    BIGINT   NOT NULL,
    `to_user_id`      BIGINT   NOT NULL,
    `type`            TINYINT  NOT NULL COMMENT '1关注 2好友 3黑名单',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rel` (`from_user_id`, `to_user_id`, `type`),
    KEY `idx_to` (`to_user_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注/好友/黑名单';

-- ------------------------------------------------------------
-- 帖子
-- status: 0审核中 1通过 2下架 3删除
-- media: JSON 数组 [{"type":"img|video","url":"...","dur":秒}]
-- like_count / comment_count / favorite_count 冗余缓存,事务内原子 ±1
-- hot_score 由 ReconcileJob 定期重算 (like+2*comment)/(age_hours+2)^1.5
-- is_top: 圈内置顶(同圈仅 1 个)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `author_id`       BIGINT       NOT NULL,
    `content`         MEDIUMTEXT   NULL,
    `media`           JSON         NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0审核中 1通过 2下架 3删除',
    `like_count`      INT          NOT NULL DEFAULT 0,
    `comment_count`   INT          NOT NULL DEFAULT 0,
    `favorite_count`  INT          NOT NULL DEFAULT 0 COMMENT '收藏数(冗余,与like_count同构)',
    `share_count`     INT          NOT NULL DEFAULT 0 COMMENT '分享次数,每次成功保存/分享卡片 +1',
    `view_count`      INT          NOT NULL DEFAULT 0 COMMENT '浏览量,进详情 +1',
    `circle_id`       BIGINT       NULL,
    `hot_score`       DOUBLE       NOT NULL DEFAULT 0,
    `is_top`          TINYINT      NOT NULL DEFAULT 0 COMMENT '圈内置顶 0否1是(圈内列表置顶帖排最前)',
    `is_anonymous`    TINYINT      NOT NULL DEFAULT 0 COMMENT '树洞匿名帖 0否1是(作者不出现在任何展示出口,管理端可见)',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_author` (`author_id`),
    KEY `idx_status_time` (`status`, `create_time`),
    KEY `idx_view` (`view_count`),
    KEY `idx_like` (`like_count`),
    KEY `idx_comment` (`comment_count`),
    KEY `idx_circle` (`circle_id`),
    FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子/动态';

-- ------------------------------------------------------------
-- Feed 写扩散收件箱
-- 游标基于 last_id (idx_fan_time: fan_id, id DESC)
-- 发帖事务1 → 同步写扩散(500 条/批 INSERT IGNORE)
-- uk_fan_post(2026-10-23 加,审查 P0-7):同一粉丝对同一帖只允许一行。
--   审核并发通过(AI+人审同时点)此前会双写,导致 Feed 同帖出现两次、通知双发;
--   唯一键 + INSERT IGNORE 让重复扩散静默去重。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `feed_item` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `fan_id`          BIGINT   NOT NULL,
    `author_id`       BIGINT   NOT NULL,
    `post_id`         BIGINT   NOT NULL,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_fan_post` (`fan_id`, `post_id`),
    KEY `idx_fan_time` (`fan_id`, `id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='写扩散 Feed 收件箱';

-- ------------------------------------------------------------
-- 评论
-- level ≤ 3 (1帖 2评 3评中评)；parent_id NULL=一级；reply_to_user_id 被回复的人
-- mentioned: 被 @ 用户「昵称→id」JSON,前端渲染 @昵称 时据此跳主页
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `post_id`             BIGINT       NOT NULL,
    `user_id`             BIGINT       NOT NULL,
    `parent_id`           BIGINT       NULL,
    `level`               TINYINT      NOT NULL DEFAULT 1,
    `reply_to_user_id`    BIGINT       NULL,
    `content`             VARCHAR(2000) NOT NULL,
    `mentioned`           VARCHAR(512)  NULL COMMENT '被@用户:昵称→id(JSON),渲染 @昵称 时跳主页',
    `status`              TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0删除',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_post_parent` (`post_id`, `parent_id`, `level`),
    KEY `idx_reply_to` (`post_id`, `reply_to_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论';

-- ------------------------------------------------------------
-- 点赞
-- target_type: 1帖 2评 3文件
-- 独立表 + 冗余 count，事务内 ±1 post.like_count
-- 唯一键保证幂等(重复点赞不报错)
-- idx_like_target: 支撑「按对象反查点赞」与删帖/删评论时清理
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `like` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT   NOT NULL,
    `target_type`     TINYINT  NOT NULL COMMENT '1帖 2评 3文件',
    `target_id`       BIGINT   NOT NULL,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_like` (`user_id`, `target_type`, `target_id`),
    KEY `idx_like_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞';

-- ------------------------------------------------------------
-- 私信会话 (单聊双方,member_a < member_b 归一)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_conversation` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `member_a`        BIGINT   NOT NULL,
    `member_b`        BIGINT   NOT NULL,
    `last_msg_id`     BIGINT   NULL,
    `unread_a`        INT      NOT NULL DEFAULT 0,
    `unread_b`        INT      NOT NULL DEFAULT 0,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv` (`member_a`, `member_b`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信会话';

-- ------------------------------------------------------------
-- 私信消息
-- status: 0未读 1已读 (receiver 侧)
-- is_recalled: 发送方 60s 内可撤
-- reply_to_id: 引用回复的被引消息 id
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `conv_id`         BIGINT       NOT NULL,
    `sender_id`       BIGINT       NOT NULL,
    `receiver_id`     BIGINT       NOT NULL,
    `content`         VARCHAR(2000) NOT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读(receiver侧)',
    `is_recalled`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已撤回(仅发送方 60s 内可撤)',
    `reply_to_id`     BIGINT       NULL COMMENT '引用回复:被引消息 id(可空)',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_conv_time` (`conv_id`, `create_time` DESC),
    KEY `idx_reply_to` (`reply_to_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信消息';

-- ------------------------------------------------------------
-- 会话偏好(置顶 / 免打扰 / 删除),按人存
-- kind: dm=私信(target_id=对方 user_id) / group=群聊(target_id=group.id)
-- 为什么独立表:chat_conversation 是两人共享一行,偏好是每人独立的;
--   pinned_a/pinned_b/muted_a/... 会翻倍列数,群聊还得再来一遍。
-- hidden + hidden_upto:删除记「当时最后一条消息 id」,来了新消息自动回到列表
--   (hidden=1 且 lastMsgId <= hidden_upto 才隐藏)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_conv_pref` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '偏好的归属人',
    `kind`        VARCHAR(8)   NOT NULL COMMENT '会话类型:dm=私信, group=群聊',
    `target_id`   BIGINT       NOT NULL COMMENT 'dm=对方user_id, group=group.id',
    `pinned`      TINYINT      NOT NULL DEFAULT 0 COMMENT '置顶:0否 1是',
    `muted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '免打扰:0否 1是',
    `hidden`      TINYINT      NOT NULL DEFAULT 0 COMMENT '已从列表删除:0否 1是',
    `hidden_upto` BIGINT       NOT NULL DEFAULT 0 COMMENT '删除时的最后消息id;lastMsgId>此值则重新显示',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pref` (`user_id`, `kind`, `target_id`) COMMENT '一个用户对一条会话只有一份偏好',
    KEY `idx_user_kind` (`user_id`, `kind`) COMMENT '列表一次取某用户某类型的全部偏好'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话偏好(置顶/免打扰/删除),按人存';

-- ------------------------------------------------------------
-- 通知
-- type: 1被关注 2被赞 3被评 4私信 5活动 6送审 7审核通过 8审核驳回 9被@ 11反馈回复
-- user.unread_notify_count 冗余
-- actor_id: NULL 合法 —— AI 系统判定没有具体操作人(2026-10-23 改,审查 P0-5:
--   原 NOT NULL 导致 AI 机审写通知必报 1364,作者永远收不到机审结果)
-- content: 自由文本通知内容(反馈回复原文等),旧类型恒为 NULL
-- post_id: 被指向帖子(点赞/评论/提及/审核跳详情用);删帖时按此列清理通知
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notify` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT   NOT NULL,
    `type`            TINYINT  NOT NULL COMMENT '1被关注 2被赞 3被评 4私信 5活动 6送审 7审核通过 8审核驳回 9被@ 11反馈回复 13文件过审 14文件驳回 15文件下架',
    `target_id`       BIGINT   NOT NULL COMMENT '关联实体 id (帖子/评论/会话/活动)',
    `post_id`         BIGINT   NULL COMMENT '被指向的帖子 id(点赞/评论/审核跳帖子详情用),其他类型可空',
    `actor_id`        BIGINT   NULL COMMENT '触发者 userId;NULL=系统(AI 机审/定时任务)',
    `content`         VARCHAR(500) NULL COMMENT '自由文本通知内容(如反馈回复原文);旧类型通知恒为 NULL',
    `is_read`         TINYINT  NOT NULL DEFAULT 0,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_read` (`user_id`, `is_read`, `create_time` DESC),
    KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知';

-- ------------------------------------------------------------
-- 圈子
-- status: 0正常 1封禁
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `circle` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `name`            VARCHAR(64)  NOT NULL,
    `owner_id`        BIGINT       NOT NULL,
    `member_count`    INT          NOT NULL DEFAULT 0,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1封禁',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈子';

-- ------------------------------------------------------------
-- 圈子成员
-- role: 1creator 2admin 3member
-- ban_status/ban_until: 圈内软封禁(保留成员行,禁发帖/评论/点赞,保留查看);到点动态解除
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `circle_member` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `circle_id`       BIGINT   NOT NULL,
    `user_id`         BIGINT   NOT NULL,
    `role`            TINYINT  NOT NULL DEFAULT 3 COMMENT '1圈主 2圈管理 3成员',
    `ban_status`      TINYINT  NOT NULL DEFAULT 0 COMMENT '圈内封禁 0正常 1封禁',
    `ban_until`       DATETIME NULL COMMENT '封禁到期(东八区),NULL=长期;到点查询时动态解除',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cm` (`circle_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='圈子成员';

-- ------------------------------------------------------------
-- 预留官方圈名(运营维护):建圈时撞名直接拦,占用的旧圈由管理员移交/封禁
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reserved_circle_name` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(64)  NOT NULL,
    `note`          VARCHAR(128) NULL COMMENT '预留原因/说明',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rname` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预留官方圈名';

-- ------------------------------------------------------------
-- 活动
-- 状态机: 0报名中 →1已满 →2进行中 →3已结束; 任意 →4已取消
-- capacity: 名额上限, 0 不限
-- current_count 冗余, 报名事务内 ±1
-- creator_id: 活动创建者,报名通知(type=5)需要;NULL=迁移前旧数据
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `activity` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `title`           VARCHAR(128) NOT NULL,
    `circle_id`       BIGINT       NULL,
    `creator_id`      BIGINT       NULL COMMENT '活动创建者用户id;NULL=迁移前旧数据',
    `capacity`        INT          NOT NULL DEFAULT 0 COMMENT '名额,0不限',
    `current_count`   INT          NOT NULL DEFAULT 0,
    `start_time`      DATETIME     NOT NULL,
    `end_time`        DATETIME     NOT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0报名中 1已满 2进行中 3已结束 4已取消',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_circle` (`circle_id`),
    KEY `idx_status_time` (`status`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动';

-- ------------------------------------------------------------
-- 活动报名 (幂等键: activity_id + user_id)
-- 报名同事务内: INSERT ON DUPLICATE KEY + 乐观锁 UPDATE
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `activity_signup` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `activity_id`     BIGINT   NOT NULL,
    `user_id`         BIGINT   NOT NULL,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_as` (`activity_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动报名';

-- ------------------------------------------------------------
-- 校园限时活动集市(mkt)
-- 与既有 activity/activity_signup(圈子签到活动) 完全并行, 语义不同:
-- 这里是「一条限时投放的帖子」, 广场可见 + 详情页公共留言区。
-- 无审核流程, 无对外联系方式入口。广场可见性 = 纯查询过滤(无定时任务):
--   soft_delete=0 AND is_enable=1 AND is_full=0 AND expire_time>NOW()
-- 排序永远按 last_launch_time 倒序(最近激活), 不做热度排序。
-- 时间统一东八区(GMT+8)。重激活 24h/3 次限流走 Redis, 不落表。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mkt_activity` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`          BIGINT       NOT NULL COMMENT '发布者',
    `category`         VARCHAR(32)  NOT NULL COMMENT '一级分类(9选1)',
    `sub_tag`          VARCHAR(64)  NULL COMMENT '子关键词(可选)',
    `title`            VARCHAR(64)  NOT NULL COMMENT '15-25 字符',
    `content`          TEXT         NOT NULL COMMENT '1-500 字',
    `target_num`       INT          NULL COMMENT '期望人数(纯展示,无自动报名/计数)',
    `price`            DECIMAL(10,2) NULL COMMENT '0=免费',
    `location`         VARCHAR(128) NULL,
    `is_enable`        TINYINT      NOT NULL DEFAULT 1 COMMENT '手动开关 1开 0暂停',
    `expire_time`      DATETIME     NOT NULL COMMENT '投放截止时间',
    `is_full`          TINYINT      NOT NULL DEFAULT 0 COMMENT '手动满员标记',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次创建,固定不变',
    `last_launch_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次激活/重投放,广场排序键',
    `soft_delete`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_launch` (`last_launch_time`),
    KEY `idx_user` (`user_id`),
    KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校园限时活动集市';

-- 集市活动留言(硬绑 mkt_activity, 不复用 post 的 comment)
CREATE TABLE IF NOT EXISTS `mkt_comment` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `activity_id`   BIGINT       NOT NULL,
    `user_id`       BIGINT       NOT NULL,
    `content`       TEXT         NOT NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_act` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='集市活动留言';

-- ------------------------------------------------------------
-- 敏感词
-- level: 1替换 2拒发
-- 应用层构建 DFA 字典缓存 + 定时刷新
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sensitive_word` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `word`            VARCHAR(64)  NOT NULL,
    `level`           TINYINT      NOT NULL COMMENT '1替换 2拒发',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词';

-- ------------------------------------------------------------
-- 审核任务
-- post.status=0 入队；reviewer 处理：status 1通过 2驳回
-- review_task(post_id UK) 防重复审
-- hit_words: 命中敏感词(JSON数组,送审时记录,审核台展示用)
-- ai_verdict/ai_reason/ai_reviewed_at: AI 机审预判(manual 时留给人审参考)
--   三列已在基线内(原先是文件尾的裸 ALTER,重复执行必报 1060,审查 P0-1)
-- 状态跃迁必须用条件 UPDATE(status=0 才流转),见 ReviewTaskMapper#casMarkProcessed
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `review_task` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT,
    `post_id`         BIGINT   NOT NULL,
    `reviewer_id`     BIGINT   NULL,
    `status`          TINYINT  NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2驳回',
    `reason`          VARCHAR(512) NULL,
    `hit_words`       VARCHAR(256) NULL COMMENT '命中敏感词(JSON数组,送审时记录,审核台展示用)',
    `ai_verdict`      VARCHAR(16)  NULL COMMENT 'AI 机审结论:approve/reject/manual',
    `ai_reason`       VARCHAR(512) NULL COMMENT 'AI 机审理由',
    `ai_reviewed_at`  DATETIME     NULL COMMENT 'AI 机审完成时间',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post` (`post_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容审核任务';

-- ------------------------------------------------------------
-- 审计日志:关键写操作留痕(登录/发帖/删帖/报名/拉黑/审核 等)
-- 只增不删(无 update_time/is_deleted 的常规逻辑删,保留全部历史)
-- user_id NULL=系统/定时任务
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT       NULL                    COMMENT '操作者, NULL=系统',
    `action`          VARCHAR(64)  NOT NULL                COMMENT '动作枚举:login/register/create_post/delete_post/signup/blacklist/review_approve/review_reject/...',
    `target_type`     VARCHAR(32)  NULL                    COMMENT '操作对象类型:post/relation/activity/user...',
    `target_id`       BIGINT       NULL                    COMMENT '操作对象 id',
    `detail`          VARCHAR(1024) NULL                    COMMENT 'JSON 或其他详情',
    `ip`              VARCHAR(64)  NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `create_time`),
    KEY `idx_action` (`action`, `create_time`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';

-- ------------------------------------------------------------
-- 话题:帖子可带 #话题#,Feed 可按话题筛选(快手式)
-- name 唯一;post_count 冗余计数,upsert 时用 ON DUPLICATE KEY 累加
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `topic` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(64)  NOT NULL COMMENT '话题名(不带#)',
    `post_count`  INT          NOT NULL DEFAULT 0,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='话题';

-- 帖子-话题 多对多
CREATE TABLE IF NOT EXISTS `post_topic` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `post_id`    BIGINT   NOT NULL,
    `topic_id`   BIGINT   NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pt` (`post_id`, `topic_id`),
    KEY `idx_topic` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子-话题关联';

-- ------------------------------------------------------------
-- 群聊(在单聊私信之上扩展:群/N 人/群已读水位)
-- 单聊 chat_conversation/chat_message 保持 1:1 不变,群独立一套表。
-- 群已读不用"每列 unread"(N 人不可行),改用 group_read 水位表:
--   每个成员存"我读到的最后一条 group_message.id"
--   群未读 = 水位之后、非我发的消息数
-- ------------------------------------------------------------

-- 群
CREATE TABLE IF NOT EXISTS `group` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(64)  NOT NULL,
    `avatar_url`    VARCHAR(512) NULL,
    `owner_id`      BIGINT       NOT NULL COMMENT '群主(user.id)',
    `member_count`  INT          NOT NULL DEFAULT 1,
    `last_msg_id`   BIGINT       NULL,
    `announcement`  TEXT         NULL COMMENT '群公告(群主可设/清,空=无)',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0解散',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_group_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群';

-- 群成员(物理删退群,软删只留痕迹——沿用关系表"物理删"约定)
CREATE TABLE IF NOT EXISTS `group_member` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `group_id`   BIGINT   NOT NULL,
    `user_id`    BIGINT   NOT NULL,
    `role`       TINYINT  NOT NULL DEFAULT 2 COMMENT '1群主 2成员',
    `muted`      TINYINT  NOT NULL DEFAULT 0 COMMENT '是否被群主禁言 0否 1是(群主恒 0)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gm` (`group_id`, `user_id`),
    KEY `idx_gm_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群成员';

-- 群消息
CREATE TABLE IF NOT EXISTS `group_message` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `group_id`     BIGINT       NOT NULL,
    `sender_id`    BIGINT       NOT NULL,
    `content`      VARCHAR(2000) NOT NULL,
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读(发送方视角汇总)',
    `is_recalled`  TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已撤回(仅发送方 8s 内可撤)',
    `reply_to_id`  BIGINT       NULL COMMENT '引用回复:被引群消息 id(可空)',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_gm_time` (`group_id`, `id` DESC),
    KEY `idx_gm_reply` (`reply_to_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群消息';

-- 群已读水位:每个成员存"我读到的最后一条 group_message.id"
CREATE TABLE IF NOT EXISTS `group_read` (
    `id`               BIGINT   NOT NULL AUTO_INCREMENT,
    `group_id`         BIGINT   NOT NULL,
    `user_id`          BIGINT   NOT NULL,
    `last_read_msg_id` BIGINT   NOT NULL DEFAULT 0,
    `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`       TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gr` (`group_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群已读水位';

-- 上传文件归属:记录「哪个用户上传了哪个 url」,头像/群头像引用文件时校验归属,
-- 防任意登录用户引用他人上传文件 URL 当自己头像(越权)。
-- url 由 UUID 命名天然唯一,不再设唯一键(软删后同名 url 可能重传)。
CREATE TABLE IF NOT EXISTS `upload_file` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT      NOT NULL COMMENT '上传者 id',
    `url`         VARCHAR(512) NOT NULL COMMENT '相对访问 URL,如 /uploads/20261013/xxx.mp4',
    `kind`        VARCHAR(16) NOT NULL COMMENT 'image | video',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_owner` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传文件归属';

-- ============================================================
-- 以下 11 张表原先只存在于迁移脚本中(审查 P0-1),
-- 2026-10-23 合并进基线,让「建库只跑 schema.sql」重新成立。
-- ============================================================

-- ------------------------------------------------------------
-- 兴趣标签字典(原 docs/sql/migration_v1.sql 第 1 节)
-- category: 1兴趣 2游戏 3运动 4学习 5生活
-- 人工维护,不做 UGC(避免脏标签与审核成本)
-- uk_name 保证标签唯一;use_count 由 user_tag 增减时事务内 SET x = x ± 1 维护
-- ------------------------------------------------------------
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

-- ------------------------------------------------------------
-- 用户-标签关系(原 docs/sql/migration_v1.sql 第 1 节)
-- uk_user_tag 保证同一用户对同一标签只有一行(PUT 覆盖写的幂等基础)
-- idx_tag 支撑「按标签反查人」
-- 注意:取消选择走物理删除(逻辑删会占住唯一键,再选必撞 Duplicate)
-- ------------------------------------------------------------
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

-- ------------------------------------------------------------
-- 举报(原 docs/sql/migration_v1.sql 第 2 节)
-- target_type 多态指向:1用户 2帖子 3评论 4聊天消息 5搭子
-- uk_report 是 AC-13 幂等的数据库兜底:同一人对同一目标只能有一条
-- idx_status_time 支撑后台「待审优先 + 最新在前」的列表查询
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `report` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '举报ID',
    `reporter_id`  BIGINT      NOT NULL                COMMENT '举报人ID',
    `target_type`  TINYINT     NOT NULL                COMMENT '1用户 2帖子 3评论 4聊天消息 5搭子',
    `target_id`    BIGINT      NOT NULL                COMMENT '被举报对象ID',
    `reason_type`  TINYINT     NOT NULL                COMMENT '1骚扰 2广告 3色情 4诈骗 5其他',
    `reason_text`  VARCHAR(500) NULL                   COMMENT '补充说明',
    `target_snapshot` TEXT     NULL                    COMMENT '举报时目标快照(JSON: 标题/正文/作者/媒体/时间)',
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

-- ------------------------------------------------------------
-- 官方公告(原 docs/sql/migration_v1.sql 第 3 节)
-- 不用 notify 表达的原因:notify 是用户级事件,公告是广播级内容,
-- 一人一行会造成 1 万用户 x 100 公告 = 100 万行的写入放大
-- idx_school_status_top_time 直接对应前台列表排序:
--   is_top DESC -> publish_time DESC,且 school_id / status 前置做等值过滤
-- ------------------------------------------------------------
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

-- ------------------------------------------------------------
-- 公告已读回执(原 docs/sql/migration_v1.sql 第 3 节)
-- uk_ann_user 是 AC-09 幂等的数据库兜底,重复上报走 INSERT IGNORE 不新增行
-- 只在实际有人读时写一行,未读数用 count 查询而非全量写入
-- ------------------------------------------------------------
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

-- ------------------------------------------------------------
-- 院系字典(原 docs/sql/migration_v1.sql 第 8 节)
-- uk_school_name 而不是只对 name 建唯一键:多校时两个学校可以都有「外国语学院」
-- enabled 与 is_deleted 是两种不同的退役语义:
--   enabled=0  学校撤销/合并了该院系,不再让新用户选,但历史用户主页仍要显示原名
--   is_deleted=1 记录彻底不要了(误录脏数据)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `school_dept` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '院系ID',
    `name`        VARCHAR(64) NOT NULL                COMMENT '院系名称',
    `school_id`   BIGINT      NOT NULL DEFAULT 1      COMMENT '所属学校ID,MVP 单校恒为 1',
    `sort`        INT         NOT NULL DEFAULT 100    COMMENT '排序权重,升序;步长 10 便于插队',
    `enabled`     TINYINT     NOT NULL DEFAULT 1      COMMENT '1启用 0停用;停用后不再让新用户选但历史数据仍可解析',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除,MyBatis-Plus 全局字段',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_school_name` (`school_id`, `name`),
    KEY `idx_school_enabled_sort` (`school_id`, `enabled`, `sort`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='院系字典';

-- ------------------------------------------------------------
-- 年级字典(原 docs/sql/migration_v1.sql 第 8 节)
-- 不带 school_id:年级是「入学年份」,跨校同年级天然可比(刻意的不对称)
-- enroll_year 单独存而不从 name 截:避免把解析规则复制到每个消费方
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `school_grade` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '年级ID',
    `name`        VARCHAR(32) NOT NULL                COMMENT '年级名称,如 2024级',
    `enroll_year` SMALLINT    NOT NULL                COMMENT '入学年份,如 2024',
    `sort`        INT         NOT NULL DEFAULT 100    COMMENT '排序权重,升序;最近入学的年级给最小',
    `enabled`     TINYINT     NOT NULL DEFAULT 1      COMMENT '1启用 0停用;已毕业年级置 0',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_enabled_sort` (`enabled`, `sort`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年级字典';

-- ------------------------------------------------------------
-- 专业字典(原 sql/migration_f12_school_major.sql)
-- 学院下属专业,支撑「学院 → 专业」二级联动与学号六层校验
-- 为什么 level 放在专业而不是学院:一个学院同时有本科和专科专业,层次是专业的属性
-- 为什么 code 用 CHAR(6):学号专业码定长 6 位、可含前导 0
-- 本表是全项目唯一保留外键约束的表(学院被引用时不允许删)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `school_major` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '专业ID',
    `dept_id`     BIGINT      NOT NULL                COMMENT '所属学院ID → school_dept.id',
    `school_id`   BIGINT      NOT NULL DEFAULT 1      COMMENT '所属学校ID,必须=dept.school_id',
    `name`        VARCHAR(64) NOT NULL                COMMENT '专业名称,如 计算机科学与技术',
    `code`        CHAR(6)     NOT NULL                COMMENT '6 位校内专业编码(学号位 3-8),定长、可含前导 0',
    `level`       TINYINT     NOT NULL                COMMENT '层次:1=本科 2=专科',
    `code_type`   TINYINT     NOT NULL DEFAULT 1      COMMENT '专业码类型:1本科国标码 2专科校内码 3高职占位码',
    `sort`        INT         NOT NULL DEFAULT 100    COMMENT '排序权重,升序;步长 10 便于插队',
    `enabled`     TINYINT     NOT NULL DEFAULT 1      COMMENT '1启用 0停用;停招专业不再让新用户选',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除,MyBatis-Plus 全局字段',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_school_code` (`school_id`, `code`) COMMENT '同一学校内 6 位专业码唯一;学号按码查专业的唯一入口',
    UNIQUE KEY `uk_dept_name` (`dept_id`, `name`)     COMMENT '同一学院内专业名唯一,防止重复录入',
    KEY `idx_dept_level_enabled_sort` (`dept_id`, `level`, `enabled`, `sort`, `id`) COMMENT '学院→专业下拉:按学院+层次过滤+启用+排序',
    CONSTRAINT `fk_major_dept` FOREIGN KEY (`dept_id`)
        REFERENCES `school_dept` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专业字典(学院下属专业,支撑级联下拉与学号专业码校验)';

-- ------------------------------------------------------------
-- 意见反馈(原 sql/migration_g1_feedback.sql)
-- user_id 不加唯一约束:反馈本就允许重复提交(同一问题提两次是常事)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `feedback` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
    `user_id`      BIGINT       NOT NULL                COMMENT '提交人ID',
    `category`     TINYINT      NOT NULL                COMMENT '1功能建议 2使用问题 3内容问题 4界面体验 5其他',
    `content`      VARCHAR(1000) NOT NULL               COMMENT '文字描述',
    `media_json`   VARCHAR(2000) NULL                   COMMENT '图片/视频,[{type,url}] JSON 数组',
    `status`       TINYINT      NOT NULL DEFAULT 0      COMMENT '0待处理 1已采纳 2已忽略',
    `handler_id`   BIGINT       NULL                    COMMENT '处理管理员ID',
    `handle_time`  DATETIME     NULL                    COMMENT '处理时间',
    `handle_note`  VARCHAR(500) NULL                    COMMENT '处理备注,同步写 audit_log',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_status_time` (`status`, `create_time` DESC),
    KEY `idx_user` (`user_id`, `id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意见反馈';

-- ------------------------------------------------------------
-- 帖子收藏(原 sql/migration_j1_post_favorite.sql)
-- uk_fav_user_post 唯一键兜底「同一人不能重复收藏同一条」
-- 取消收藏走物理删除:软删会留下行继续占用唯一键,再收藏必撞 Duplicate
-- favorite_count 冗余在 post 上,与 like_count/comment_count 对称
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_favorite` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT   NOT NULL COMMENT '收藏人',
    `post_id`     BIGINT   NOT NULL COMMENT '被收藏的帖子',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT  NOT NULL DEFAULT 0 COMMENT '保留列,实际取消收藏走物理删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_fav_user_post` (`user_id`, `post_id`),
    KEY `idx_fav_user_id` (`user_id`, `id`) COMMENT '按收藏时间倒序翻页',
    KEY `idx_fav_post` (`post_id`) COMMENT '删帖时清理收藏'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子收藏';

-- ------------------------------------------------------------
-- 文件库(原 sql/migration_n1_file_library.sql)
-- 文件对象存 OSS 私有前缀 files/,不入 /uploads/ 公共读链路
-- status 自带审核队列:0待审 1通过 2驳回 3下架
-- 容量口径 = status IN (0,1);驳回/下架/删除不占
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `file_asset` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT       NOT NULL COMMENT '上传者',
    `title`           VARCHAR(80)  NOT NULL,
    `description`     VARCHAR(200) NULL,
    `category`        VARCHAR(24)  NOT NULL COMMENT '模板/课件讲义/指导书/实验报告/考试真题/其他',
    `file_name`       VARCHAR(160) NOT NULL COMMENT '原文件名，仅展示与下载响应头用',
    `object_key`      VARCHAR(255) NOT NULL COMMENT 'OSS/本地对象 key：files/{userId}/{day}/{uuid}.{ext}',
    `ext`             VARCHAR(16)  NOT NULL,
    `mime_type`       VARCHAR(64)  NOT NULL,
    `size_bytes`      BIGINT       NOT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2驳回 3下架',
    `reject_reason`   VARCHAR(200) NULL,
    `reviewer_id`     BIGINT       NULL,
    `review_time`     DATETIME     NULL,
    `view_count`      INT          NOT NULL DEFAULT 0,
    `download_count`  INT          NOT NULL DEFAULT 0,
    `like_count`      INT          NOT NULL DEFAULT 0,
    `favorite_count`  INT          NOT NULL DEFAULT 0,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_status_id` (`status`, `id`),
    KEY `idx_user_id` (`user_id`, `id`),
    KEY `idx_category_status` (`category`, `status`, `id`),
    KEY `idx_user_review` (`user_id`, `status`, `review_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件库';

CREATE TABLE IF NOT EXISTS `file_favorite` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT   NOT NULL,
    `file_id`     BIGINT   NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT  NOT NULL DEFAULT 0 COMMENT '保留列，实际取消收藏走物理删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_fav_user_file` (`user_id`, `file_id`),
    KEY `idx_fav_user_id` (`user_id`, `id`),
    KEY `idx_fav_file` (`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件收藏';

-- ============================================================
-- 内置种子:敏感词(level 1 替换 / level 2 拒发)
-- 实际词库可后续扩充;INSERT IGNORE 靠 uk_word 去重,可重复执行
-- ============================================================
INSERT IGNORE INTO `sensitive_word` (`word`, `level`) VALUES
    ('测试违禁词', 2),
    ('示例敏感词A', 2),
    ('示例敏感词B', 1),
    -- 集市活动禁发:商业/代做/代考/违规跑腿/赌博/烟酒(level 2 拒发)
    ('商业广告', 2),
    ('有偿代做', 2),
    ('代考', 2),
    ('代写作业', 2),
    ('赌博', 2),
    ('烟酒', 2),
    ('违规跑腿', 2);
