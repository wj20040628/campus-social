-- 2026-10-31 修复历史注释乱码（仅修改表/列 COMMENT，不改类型、默认值、索引）
-- 来源：生产库 2026-10-30 备份的 CREATE TABLE 定义；已在同结构隔离库验证
-- 执行：mysql --default-character-set=utf8mb4 campus_social_v2 < 本文件
-- 说明：可重复执行；执行前请对目标库再做一次备份并核对表结构

ALTER TABLE `announcement`
  COMMENT='官方公告',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  MODIFY COLUMN `title` varchar(120) NOT NULL COMMENT '标题',
  MODIFY COLUMN `content` text NOT NULL COMMENT '正文,纯文本+换行,MVP 不做富文本',
  MODIFY COLUMN `category` tinyint NOT NULL DEFAULT '6' COMMENT '1教务 2后勤 3活动 4社团 5紧急 6其他',
  MODIFY COLUMN `publisher_id` bigint NOT NULL COMMENT '发布人ID',
  MODIFY COLUMN `school_id` bigint NOT NULL DEFAULT '1' COMMENT '学校ID,默认1,预留多校',
  MODIFY COLUMN `is_top` tinyint NOT NULL DEFAULT '0' COMMENT '0否 1是',
  MODIFY COLUMN `top_expire_time` datetime DEFAULT NULL COMMENT '置顶到期时间,到期后自然回落',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT '0' COMMENT '0草稿 1已发布 2已下架',
  MODIFY COLUMN `publish_time` datetime DEFAULT NULL COMMENT '发布时间,草稿为 NULL',
  MODIFY COLUMN `read_count` int NOT NULL DEFAULT '0' COMMENT '已读人数,仅首次已读时 +1';

ALTER TABLE `announcement_read`
  COMMENT='公告已读回执',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  MODIFY COLUMN `announcement_id` bigint NOT NULL COMMENT '公告ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `read_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '已读时间';

ALTER TABLE `chat_conv_pref`
  COMMENT='会话偏好(置顶/免打扰/删除),按人存',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '偏好的归属人',
  MODIFY COLUMN `kind` varchar(8) COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话类型:dm=私信, group=群聊',
  MODIFY COLUMN `target_id` bigint NOT NULL COMMENT 'dm=对方user_id, group=group.id',
  MODIFY COLUMN `pinned` tinyint NOT NULL DEFAULT '0' COMMENT '置顶:0否 1是',
  MODIFY COLUMN `muted` tinyint NOT NULL DEFAULT '0' COMMENT '免打扰:0否 1是',
  MODIFY COLUMN `hidden` tinyint NOT NULL DEFAULT '0' COMMENT '已从列表删除:0否 1是',
  MODIFY COLUMN `hidden_upto` bigint NOT NULL DEFAULT '0' COMMENT '删除时的最后消息id;lastMsgId>此值则重新显示',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `comment`
  MODIFY COLUMN `mentioned` varchar(512) DEFAULT NULL COMMENT '被@用户:昵称→id(JSON)';

ALTER TABLE `feedback`
  COMMENT='意见反馈',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
  MODIFY COLUMN `category` tinyint NOT NULL COMMENT '1功能建议 2使用问题 3内容问题 4界面体验 5其他',
  MODIFY COLUMN `content` varchar(1000) NOT NULL COMMENT '文字描述',
  MODIFY COLUMN `media_json` varchar(2000) DEFAULT NULL COMMENT '图片/视频,[{type,url}] JSON 数组',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待处理 1已采纳 2已忽略',
  MODIFY COLUMN `handler_id` bigint DEFAULT NULL COMMENT '处理管理员ID',
  MODIFY COLUMN `handle_time` datetime DEFAULT NULL COMMENT '处理时间',
  MODIFY COLUMN `handle_note` varchar(500) DEFAULT NULL COMMENT '处理备注,同步写 audit_log';

ALTER TABLE `file_asset`
  COMMENT='文件库',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '上传者',
  MODIFY COLUMN `category` varchar(24) NOT NULL COMMENT '模板/课件讲义/指导书/实验报告/考试真题/其他',
  MODIFY COLUMN `file_name` varchar(160) NOT NULL COMMENT '原文件名，仅展示与下载响应头用',
  MODIFY COLUMN `object_key` varchar(255) NOT NULL COMMENT 'OSS/本地对象 key：files/{userId}/{day}/{uuid}.{ext}',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待审 1通过 2驳回 3下架';

ALTER TABLE `file_favorite`
  COMMENT='文件收藏',
  MODIFY COLUMN `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '保留列，实际取消收藏走物理删除';

ALTER TABLE `group`
  MODIFY COLUMN `announcement` text COMMENT '群公告(群主可设/清,空=无)';

ALTER TABLE `group_member`
  MODIFY COLUMN `muted` tinyint NOT NULL DEFAULT '0' COMMENT '是否被群主禁言 0否 1是(群主恒 0)';

ALTER TABLE `interest_tag`
  COMMENT='兴趣标签字典',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '标签ID',
  MODIFY COLUMN `name` varchar(32) NOT NULL COMMENT '标签名',
  MODIFY COLUMN `category` tinyint NOT NULL COMMENT '1兴趣 2游戏 3运动 4学习 5生活',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT '100' COMMENT '组内排序,升序;步长 10 便于插队',
  MODIFY COLUMN `use_count` int NOT NULL DEFAULT '0' COMMENT '被多少用户选中,冷启动期为 0',
  MODIFY COLUMN `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除,MyBatis-Plus 全局字段';

ALTER TABLE `like`
  MODIFY COLUMN `target_type` tinyint NOT NULL COMMENT '1帖 2评 3文件';

ALTER TABLE `mkt_activity`
  COMMENT='校园限时活动集市',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '发布者',
  MODIFY COLUMN `category` varchar(32) NOT NULL COMMENT '一级分类(9选1)',
  MODIFY COLUMN `sub_tag` varchar(64) DEFAULT NULL COMMENT '子关键词(可选)',
  MODIFY COLUMN `title` varchar(64) NOT NULL COMMENT '15-25 字符',
  MODIFY COLUMN `content` text NOT NULL COMMENT '1-500 字',
  MODIFY COLUMN `target_num` int DEFAULT NULL COMMENT '期望人数(纯展示,无自动报名/计数)',
  MODIFY COLUMN `price` decimal(10,2) DEFAULT NULL COMMENT '0=免费',
  MODIFY COLUMN `is_enable` tinyint NOT NULL DEFAULT '1' COMMENT '手动开关 1开 0暂停',
  MODIFY COLUMN `expire_time` datetime NOT NULL COMMENT '投放截止时间',
  MODIFY COLUMN `is_full` tinyint NOT NULL DEFAULT '0' COMMENT '手动满员标记',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次创建,固定不变',
  MODIFY COLUMN `last_launch_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次激活/重投放,广场排序键';

ALTER TABLE `mkt_comment`
  COMMENT='集市活动留言';

ALTER TABLE `notify`
  MODIFY COLUMN `content` varchar(500) DEFAULT NULL COMMENT '自由文本通知内容(如反馈回复原文);旧类型通知恒为 NULL';

ALTER TABLE `post`
  MODIFY COLUMN `favorite_count` int NOT NULL DEFAULT '0' COMMENT '收藏数(冗余,与like_count同构)',
  MODIFY COLUMN `share_count` int NOT NULL DEFAULT '0' COMMENT '分享次数,每次成功保存/分享卡片 +1',
  MODIFY COLUMN `view_count` int NOT NULL DEFAULT '0' COMMENT '浏览量,进详情 +1',
  MODIFY COLUMN `is_anonymous` tinyint NOT NULL DEFAULT '0' COMMENT '树洞匿名帖 0否1是(作者不出现在任何展示出口,管理端可见)';

ALTER TABLE `post_favorite`
  COMMENT='帖子收藏',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '收藏人',
  MODIFY COLUMN `post_id` bigint NOT NULL COMMENT '被收藏的帖子',
  MODIFY COLUMN `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '保留列,实际取消收藏走物理删除';

ALTER TABLE `post_topic`
  COMMENT='帖子-话题关联';

ALTER TABLE `report`
  COMMENT='举报',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '举报ID',
  MODIFY COLUMN `reporter_id` bigint NOT NULL COMMENT '举报人ID',
  MODIFY COLUMN `target_type` tinyint NOT NULL COMMENT '1用户 2帖子 3评论 4聊天消息 5搭子',
  MODIFY COLUMN `target_id` bigint NOT NULL COMMENT '被举报对象ID',
  MODIFY COLUMN `reason_type` tinyint NOT NULL COMMENT '1骚扰 2广告 3色情 4诈骗 5其他',
  MODIFY COLUMN `reason_text` varchar(500) DEFAULT NULL COMMENT '补充说明',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待审 1成立 2驳回',
  MODIFY COLUMN `handler_id` bigint DEFAULT NULL COMMENT '处置管理员ID',
  MODIFY COLUMN `handle_time` datetime DEFAULT NULL COMMENT '处置时间',
  MODIFY COLUMN `handle_note` varchar(500) DEFAULT NULL COMMENT '处置备注,同步写 audit_log';

ALTER TABLE `school_dept`
  COMMENT='院系字典',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '院系ID',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '院系名称',
  MODIFY COLUMN `school_id` bigint NOT NULL DEFAULT '1' COMMENT '所属学校ID,MVP 单校恒为 1',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT '100' COMMENT '排序权重,升序;步长 10 便于插队',
  MODIFY COLUMN `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用;停用后不再让新用户选但历史数据仍可解析',
  MODIFY COLUMN `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除,MyBatis-Plus 全局字段';

ALTER TABLE `school_grade`
  COMMENT='年级字典',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '年级ID',
  MODIFY COLUMN `name` varchar(32) NOT NULL COMMENT '年级名称,如 2024级',
  MODIFY COLUMN `enroll_year` smallint NOT NULL COMMENT '入学年份,如 2024',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT '100' COMMENT '排序权重,升序;最近入学的年级给最小',
  MODIFY COLUMN `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用;已毕业年级置 0';

ALTER TABLE `topic`
  COMMENT='话题';

ALTER TABLE `upload_file`
  COMMENT='上传文件归属表',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  MODIFY COLUMN `user_id` bigint DEFAULT NULL COMMENT '上传者id(user.id);群头像归属到建群/改头像成员',
  MODIFY COLUMN `url` varchar(512) NOT NULL COMMENT '相对访问 URL,如 /uploads/20261013/uuid.mp4',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `is_deleted` int NOT NULL DEFAULT '0' COMMENT '逻辑删除:0 正常,1 已删(MyBatis-Plus @TableLogic)';

ALTER TABLE `user`
  MODIFY COLUMN `bio` varchar(200) DEFAULT NULL COMMENT '个人介绍,NULL/空=还没写',
  MODIFY COLUMN `cover_url` varchar(512) DEFAULT NULL COMMENT '个人主页封面图(相对路径),NULL=默认渐变封面',
  MODIFY COLUMN `school_id` bigint NOT NULL DEFAULT '1' COMMENT '学校ID,默认1,预留多校',
  MODIFY COLUMN `campus` tinyint DEFAULT NULL COMMENT '校区 1东 2西 NULL未填;与院系解耦独立自选,改不触发重审',
  MODIFY COLUMN `verify_status` tinyint NOT NULL DEFAULT '0' COMMENT '校园信息状态:0未填写 1已登记 2核对中 3需修改(仅资料展示,不参与权限判定)',
  MODIFY COLUMN `stranger_msg_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否接收陌生人消息 0否 1是(F3 护栏)',
  MODIFY COLUMN `onboarding_step` tinyint NOT NULL DEFAULT '0' COMMENT '引导进度 0未开始 1已填归属 2已选标签 3已完成',
  MODIFY COLUMN `dept_id` bigint DEFAULT NULL COMMENT '院系字典id,指向 school_dept.id;匹配与筛选的权威依据,存量行为 NULL',
  MODIFY COLUMN `grade_id` bigint DEFAULT NULL COMMENT '年级字典id,指向 school_grade.id;存量行为 NULL',
  MODIFY COLUMN `email` varchar(64) DEFAULT NULL COMMENT '安全凭证邮箱,有值时唯一(找回密码/绑定/邮箱验证码登录)',
  MODIFY COLUMN `notify_pref` text COMMENT '通知偏好JSON:{"disabled":[类型id]}';

ALTER TABLE `user_tag`
  COMMENT='用户-兴趣标签',
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `tag_id` bigint NOT NULL COMMENT '标签ID';
