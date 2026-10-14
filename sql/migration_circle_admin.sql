-- 圈子「圈管理/吧主级权限」+「官方名保护」:增量迁移(已有库执行,新库已含于 schema.sql)
-- 说明:
--   1. post 加 is_top(圈内列表置顶)。
--   2. circle_member 加 ban_status / ban_until(软封禁:保留成员行,禁发帖/评论/点赞,保留查看)。
--   3. 新表 reserved_circle_name(预留官方圈名,建圈拦截;运营后台维护)。
-- 已按 schema.sql 全新建库的忽略本文件。

ALTER TABLE `post`
    ADD COLUMN `is_top` TINYINT NOT NULL DEFAULT 0 COMMENT '圈内置顶 0否1是';

ALTER TABLE `circle_member`
    ADD COLUMN `ban_status` TINYINT NOT NULL DEFAULT 0 COMMENT '圈内封禁 0正常 1封禁',
    ADD COLUMN `ban_until` DATETIME NULL COMMENT '封禁到期(东八区),NULL=长期;到点查询时动态解除';

CREATE TABLE IF NOT EXISTS `reserved_circle_name` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(64)  NOT NULL,
    `note`          VARCHAR(128) NULL COMMENT '预留原因/说明',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rname` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预留官方圈名(运营维护,建圈拦截)';
