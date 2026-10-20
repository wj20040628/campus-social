-- 个人主页自定义封面（2026-10-20）
-- 用途：主页封面从「固定渐变」改为「可自定义」，用户点封面换图、可恢复默认。
--
-- 设计要点：
--   1) NULL = 用默认渐变封面，不用空串表示「没设置」——
--      空串会让前端 background-image: url('') 发出一次对当前页的额外请求；
--   2) 只存相对路径（/uploads/...），与 avatar_url 一致，
--      域名/IP 变更时不用批量刷库；
--   3) 不冗余存储图片主色/缩略图：封面是背景图，浏览器 cover 裁剪即可，
--      多存一份派生数据就多一处要维护的同步点。

ALTER TABLE user
    ADD COLUMN cover_url VARCHAR(512) NULL
        COMMENT '个人主页封面图(相对路径)，NULL=默认渐变封面' AFTER avatar_url;
