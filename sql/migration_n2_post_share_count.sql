-- 帖子分享次数（2026-10-27）
--
-- 背景：帖子详情已有「生成分享卡片 → 保存/分享」入口，但没有可量化的反馈。
-- 本列只存计数，不加唯一约束：用户明确要求「每分享一次加一」，自己重复分享也计。
--
-- 兼容：旧行默认 0；PostVO 未取到时前端按 0 渲染。
ALTER TABLE `post`
    ADD COLUMN `share_count` INT NOT NULL DEFAULT 0 COMMENT '分享次数,每次成功保存/分享卡片 +1' AFTER `favorite_count`;
