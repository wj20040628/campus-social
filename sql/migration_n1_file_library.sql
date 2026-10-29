-- 文件库（2026-10-27）
-- 设计要点：
--   1) 文件对象存 OSS 私有前缀 files/，不入 /uploads/ 公共读链路；
--   2) 人工审核用 file_asset.status 自带队列（review_task 是帖子专用，不能复用）；
--   3) 容量口径 = 待审(0) + 已过审(1)，驳回/下架/删除都不占；
--   4) 收藏独立表（post_favorite 绑死 post_id）；点赞复用 `like`，target_type=3。

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件库';

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件收藏';

ALTER TABLE `like`
    MODIFY COLUMN `target_type` TINYINT NOT NULL COMMENT '1帖 2评 3文件';
