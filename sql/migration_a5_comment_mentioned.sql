-- A5 评论 @某人:comment 表加 mentioned 列(昵称→id 的 JSON)
-- 给已存在库用;全新建库直接把列加进 schema.sql 的 comment 表定义
ALTER TABLE `comment`
    ADD COLUMN `mentioned` VARCHAR(512) NULL
    COMMENT '被@用户:昵称→id(JSON),前端渲染 @昵称 时据此跳主页'
    AFTER `content`;
