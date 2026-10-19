-- ============================================================================
-- migration_g1_feedback.sql · 意见反馈(2026-10-18)
-- ----------------------------------------------------------------------------
-- 为什么要独立一张表,而不是复用 report(举报):
--   report 的语义是「对某个具体对象的违规举报」,有 target_type/target_id 多态
--   指向,且带 uk_report 幂等键(同一人对同一目标只能一条)。意见反馈是
--   「对产品本身的建议 / 问题报告」,没有举报对象,一个人可以提很多条,
--   塞进 report 只能把 target_type 填成 0 或 NULL —— 那样既污染举报列表,
--   又会让 AC-13 的幂等约束误伤(用户第二次反馈就成「重复举报」而被拒)。
--   与学号核验(review_task 之外的 verify 队列)同理:一个独立闭环。
--
-- media_json 存 [{type:'img'|'video', url:'...'}]:
--   复用发帖的媒体结构与 OSS 直传链路(前端 useMediaUpload 同款),
--   不另造媒体表 —— 反馈媒体的生命周期跟着反馈走,没有「被别处引用」的场景。
--
-- status 三态:
--   0 待处理 1 已采纳 2 已忽略
--   「采纳」与「忽略」都表示管理员看过;不设「已修复」是因为产品改没改
--   只有开发者知道,让管理员勾「已采纳」就够,避免逼着他编一个完成状态。
--
-- user_id 不加唯一约束:反馈本就允许重复提交(同一问题提两次是常事,
--   反而说明用户很在意)。想看趋势走 idx_status_time。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `feedback` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
    `user_id`      BIGINT       NOT NULL                COMMENT '提交人ID',
    `category`     TINYINT      NOT NULL                COMMENT '1功能建议 2使用问题 3内容问题 4界面体验 5其他',
    `content`      VARCHAR(1000) NOT NULL               COMMENT '文字描述',
    `media_json`   VARCHAR(2000) NULL                   COMMENT '图片/视频,[{type,url}] JSON 数组',
    `status`       TINYINT      NOT NULL DEFAULT 0      COMMENT '0待处理 1已采纳 2已忽略',
    `handler_id`   BIGINT       NULL                    COMMENT '处理管理员ID',
    `handle_time`  DATETIME     NULL                    COMMENT '处理时间',
    `handle_note`  VARCHAR(500) NULL                   COMMENT '处理备注,同步写 audit_log',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    -- 支撑「待处理优先 + 最新在前」的后台列表
    KEY `idx_status_time` (`status`, `create_time` DESC),
    -- 支撑「我提交过哪些反馈」的查询
    KEY `idx_user` (`user_id`, `id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意见反馈';

-- 回滚(留档,与项目其它 migration 同惯例):
-- DROP TABLE IF EXISTS `feedback`;
