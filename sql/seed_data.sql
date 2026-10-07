-- ============================================================
-- 校园社交 种子数据(演示/答辩用,可重复执行:INSERT IGNORE / 幂等)
-- 账号(密码见文末注释):
--   admin@campus.test / admin123   (role=1 管理员,可进内容审核后台)
--   alice@campus.test / user123    (普通用户)
--   bob@campus.test   / user123
--   carol@campus.test / user123
--   dave@campus.test  / user123
--   erin@campus.test  / user123
-- 说明:
--   - 1 条帖子命中 level-1 敏感词(status=0 待审)→ 演示 B1 审核链路
--   - 关注关系 + Feed 写扩散,使各用户 Feed 非空
--   - 敏感词已由 schema.sql 内置,这里不重复插
-- ============================================================

USE `campus_social_v2`;

-- ------------------------------------------------------------
-- 用户(id 固定,便于造关系;BCrypt12 哈希)
-- ------------------------------------------------------------
INSERT IGNORE INTO `user`
  (`id`,`username`,`password_hash`,`student_no`,`nickname`,`grade`,`dept`,`status`,`role`)
VALUES
  (1, 'admin@campus.test', '$2a$12$.nilz2k77212fl69tRFacO4lUVCMqlL7n5XFRjqELIpr/y32RQE0K', NULL, '管理员', NULL, NULL, 0, 1),
  (2, 'alice@campus.test', '$2a$12$ZjwycsiXoD4nepPAL29jueZXLR/E4vHGfXRG49gXhzZOH6mC/tASG', '20230001', 'Alice', '大三', '计算机学院', 0, 0),
  (3, 'bob@campus.test',   '$2a$12$ZjwycsiXoD4nepPAL29jueZXLR/E4vHGfXRG49gXhzZOH6mC/tASG', '20230002', 'Bob',   '大三', '计算机学院', 0, 0),
  (4, 'carol@campus.test', '$2a$12$ZjwycsiXoD4nepPAL29jueZXLR/E4vHGfXRG49gXhzZOH6mC/tASG', '20230003', 'Carol', '大二', '外语学院',   0, 0),
  (5, 'dave@campus.test',  '$2a$12$ZjwycsiXoD4nepPAL29jueZXLR/E4vHGfXRG49gXhzZOH6mC/tASG', '20230004', 'Dave',  '大二', '外语学院',   0, 0),
  (6, 'erin@campus.test',  '$2a$12$ZjwycsiXoD4nepPAL29jueZXLR/E4vHGfXRG49gXhzZOH6mC/tASG', '20230005', 'Erin',  '大一', '艺术学院',   0, 0);

-- ------------------------------------------------------------
-- 话题
-- ------------------------------------------------------------
INSERT IGNORE INTO `topic` (`id`,`name`,`post_count`) VALUES
  (1, '校园生活', 0),
  (2, '课程', 0),
  (3, '社团', 0),
  (4, '美食', 0);

-- ------------------------------------------------------------
-- 帖子(alice/bob/carol/dave/erin 各发;含 1 条命中 level-1 待审)
--   #1/#2/#4/#5 通过(status=1);#3 命中 level-1(示例敏感词B)→ 待审(0)
-- ------------------------------------------------------------
INSERT IGNORE INTO `post`
  (`id`,`author_id`,`content`,`status`,`like_count`,`comment_count`,`view_count`)
VALUES
  (1, 2, '开学第一周,图书馆占座攻略分享 #校园生活', 1, 12, 3, 128),
  (2, 3, '高数期末复习划重点,求交流 #课程', 1, 8, 5, 96),
  (3, 4, '社团招新,示例敏感词B,来报名 #社团', 0, 0, 0, 12),
  (4, 5, '食堂二楼新开了家面馆,实测 #美食', 1, 20, 7, 210),
  (5, 6, '艺术学院画展周末开放,欢迎来看 #校园生活', 1, 5, 2, 44);

-- 帖子-话题绑定(对应 post_count)
INSERT IGNORE INTO `post_topic` (`post_id`,`topic_id`) VALUES
  (1,1),(2,2),(3,3),(4,4),(5,1);
UPDATE `topic` SET `post_count` = (
  SELECT COUNT(*) FROM `post_topic` pt WHERE pt.topic_id = `topic`.id AND pt.is_deleted = 0
);

-- ------------------------------------------------------------
-- 审核任务:#3 命中送审(待审,含命中词),演示 B1 审核台
-- ------------------------------------------------------------
INSERT IGNORE INTO `review_task` (`post_id`,`status`,`hit_words`) VALUES
  (3, 0, '["示例敏感词B"]');

-- ------------------------------------------------------------
-- 关注关系:alice 关注 bob、carol;bob 关注 alice;dave 关注 alice、erin
-- 同步 user.following_count / follower_count(与后端事务口径一致)
-- ------------------------------------------------------------
INSERT IGNORE INTO `relation` (`from_user_id`,`to_user_id`,`type`) VALUES
  (2,3,1),(2,4,1),(3,2,1),(5,2,1),(5,6,1);

UPDATE `user` SET `following_count` = (
  SELECT COUNT(*) FROM `relation` r WHERE r.from_user_id = `user`.id AND r.type = 1 AND r.is_deleted = 0
) WHERE id IN (2,3,4,5,6);
UPDATE `user` SET `follower_count` = (
  SELECT COUNT(*) FROM `relation` r WHERE r.to_user_id = `user`.id AND r.type = 1 AND r.is_deleted = 0
) WHERE id IN (2,3,4,5,6);

-- ------------------------------------------------------------
-- Feed 写扩散:通过的帖子(status=1)投给"关注者 + 作者本人"
-- 演示:alice(关注 bob/carol)Feed 有 bob/carol 的帖子
-- fan_id = 关注者;post_id 限 status=1
-- ------------------------------------------------------------
INSERT IGNORE INTO `feed_item` (`fan_id`,`author_id`,`post_id`)
SELECT r.from_user_id AS fan_id, p.author_id, p.id
FROM `post` p
JOIN `relation` r ON r.to_user_id = p.author_id AND r.type = 1 AND r.is_deleted = 0
WHERE p.status = 1 AND p.is_deleted = 0;
-- 作者本人也进自己的 Feed
INSERT IGNORE INTO `feed_item` (`fan_id`,`author_id`,`post_id`)
SELECT p.author_id, p.author_id, p.id
FROM `post` p WHERE p.status = 1 AND p.is_deleted = 0;

-- ------------------------------------------------------------
-- 点赞(alice 给 bob 的帖 #2 点赞等,幂等键 user+target)
-- ------------------------------------------------------------
INSERT IGNORE INTO `like` (`user_id`,`target_type`,`target_id`) VALUES
  (2,1,2),(4,1,1),(5,1,1),(3,1,4);

-- ============================================================
-- 完成。登录任一账号即可演示:Feed / 关注 / 帖子 / 话题 / 审核后台(admin)
-- ============================================================
