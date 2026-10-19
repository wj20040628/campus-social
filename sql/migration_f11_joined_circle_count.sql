-- F11 引导 + 个人主页「加入圈子数」冗余计数
--   与 follower_count / following_count 同口径:在加圈/退圈 事务内 SET x = x ± 1,
--   绝不读改写;个人主页直接读 user.joined_circle_count,不每次 JOIN circle_member 重算。
--   已按 schema.sql 全新建库的忽略本文件(列已并入 schema.sql)。

ALTER TABLE `user`
    ADD COLUMN `joined_circle_count` INT NOT NULL DEFAULT 0 COMMENT '加入的圈子数(冗余,加圈/退圈事务内±1)';

-- 存量对账:按 circle_member 实有成员行回填(逻辑删除的行不计)
UPDATE `user` u
SET u.joined_circle_count = (
    SELECT COUNT(*) FROM circle_member cm
    WHERE cm.user_id = u.id AND cm.is_deleted = 0
)
WHERE u.is_deleted = 0;
