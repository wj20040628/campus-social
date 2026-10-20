-- 帖子收藏（2026-10-20）
-- 用途：个人主页需要「收藏」指标与我的收藏列表；此前后端完全没有收藏域，
--       like 表虽有 target_type 看似可扩展，但表名与语义不符，混存会让后来人读不懂。
--
-- 设计要点：
--   1) uk_fav_user_post 唯一键兜底「同一人不能重复收藏同一条」，
--      就算业务代码判断写错也不会产生重复行；
--   2) 取消收藏走**物理删除**，不做软删。软删会留下行继续占用唯一键，
--      再次收藏时 INSERT 必撞 Duplicate entry —— 与 like 表踩过的坑完全相同
--      （见 LikeMapper#hardDelete 注释）；
--   3) favorite_count 冗余在 post 上，与 like_count/comment_count 对称，
--      避免列表每行都去 count 一次。

CREATE TABLE IF NOT EXISTS post_favorite (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    user_id     BIGINT NOT NULL COMMENT '收藏人',
    post_id     BIGINT NOT NULL COMMENT '被收藏的帖子',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT NOT NULL DEFAULT 0 COMMENT '保留列，实际取消收藏走物理删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fav_user_post (user_id, post_id),
    KEY idx_fav_user_id (user_id, id) COMMENT '按收藏时间倒序翻页',
    KEY idx_fav_post (post_id) COMMENT '删帖时清理收藏'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子收藏';

ALTER TABLE post
    ADD COLUMN favorite_count INT NOT NULL DEFAULT 0 COMMENT '收藏数(冗余,与like_count同构)' AFTER comment_count;
