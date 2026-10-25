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
--   - 敏感词词库在本文件末尾(schema.sql 只建表不插词;与
--     sql/migration_sensitive_words.sql 同源,改词两处同步——OD-18 手工同步纪律)
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

-- ============================================================
-- 敏感词词库(2026-10-24,与 sql/migration_sensitive_words.sql 同一份词表)
-- 源头是迁移脚本:改词先改那边,再把 USE 之后的 INSERT 段复制过来,防两处漂移。
-- level: 1 替换(受信用户直发/其余送审) 2 拒发;分级原则见迁移脚本头注释。
-- ============================================================
-- ---------------- 涉政/涉恐/邪教(2 拒发) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('颠覆国家政权', 2),
('分裂国家', 2),
('恐怖袭击', 2),
('制造炸弹', 2),
('自制炸弹', 2),
('邪教组织', 2),
('法轮功', 2);

-- ---------------- 毒品(2 拒发) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('冰毒', 2),
('摇头丸', 2),
('K粉', 2),
('大麻', 2),
('海洛因', 2),
('麻古', 2),
('迷药', 2),
('蒙汗药', 2),
('三唑仑', 2);

-- ---------------- 枪支爆炸物(2 拒发) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('枪支买卖', 2),
('出售枪支', 2),
('买枪', 2),
('卖枪', 2),
('管制刀具', 2),
('雷管', 2),
('炸药', 2);

-- ---------------- 赌博(2 拒发,参与行为) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('赌球', 2),
('网赌', 2),
('六合彩', 2),
('押大小', 2),
('博彩平台', 2),
('现金网', 2);

-- ---------------- 色情交易(2 拒发) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('嫖娼', 2),
('卖淫', 2),
('援交', 2),
('裸聊', 2),
('一夜情', 2),
('约炮', 2),
('约啪', 2),
('色情服务', 2),
('找小姐', 2),
('幼女', 2);

-- ---------------- 性犯罪名词(1 替换:反性侵/安全讨论是正当场景) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('强奸', 1);

-- ---------------- 诈骗/资金盘/校园贷(2 拒发,对学生伤害最大的一类) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('刷单', 2),
('刷单返利', 2),
('杀猪盘', 2),
('洗钱', 2),
('跑分兼职', 2),
('兼职跑分', 2),
('收银行卡', 2),
('出售银行卡', 2),
('收电话卡', 2),
('收U', 2),
('出U', 2),
('拉人头', 2),
('刷流水', 2),
('无抵押贷款', 2),
('分期套现', 2),
('花呗套现', 2),
('校园贷', 2),
('裸贷', 2),
('套路贷', 2),
('培训贷', 2),
('美容贷', 2);

-- ---------------- 证件/账号/信息买卖(2 拒发) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('办证', 2),
('刻章', 2),
('代开发票', 2),
('发票代开', 2),
('出售身份证', 2),
('收身份证', 2),
('卖号', 2),
('收号', 2),
('出售账号', 2),
('卖学生资料', 2),
('收购学生信息', 2);

-- ---------------- 考试作弊/学术不端·重档(2 拒发:国家教育考试违法) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('代考', 2),
('替考', 2),
('代写论文', 2),
('论文代写', 2),
('论文代做', 2);

-- ---------------- 学术不端·轻档/灰产名词(1 替换:可正当讨论,替换即留痕) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('代写', 1),
('代做作业', 1),
('代刷网课', 1),
('代签到', 1),
('包过', 1),
('保过', 1),
('传销', 1);

-- ---------------- 人肉/开盒(2 拒发:校园实名社区对隐私零容忍) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('开盒', 2),
('人肉搜索', 2);

-- ---------------- 人身攻击/低俗(1 替换:打星即威慑,信任闸门放行老用户) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('傻逼', 1),
('草泥马', 1),
('操你妈', 1),
('贱人', 1),
('装逼', 1),
('我操', 1);

-- ---------------- 广告引流(1 替换:只收短语和黑话,不收「微信」裸词) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('加微信', 1),
('加vx', 1),
('vx', 1),
('薇信', 1),
('薇芯', 1),
('扫码', 1),
('招代理', 1),
('招兼职', 1),
('网赚', 1),
('秒赞', 1),
('涨粉', 1),
('高佣金', 1),
('秒到账', 1);

-- ============================================================
-- 第二批:校园高频场景专项(2026-10-24 补,来源见下)
-- 依据:公安/高校 2025 年反诈通报里的校园高发套路——
--   刷单返利(大学生受骗第一)、注销校园贷骗局(四川农大《2025 电信网络
--   诈骗大学生十大常见案例》)、两卡(帮信罪)、打字员/点赞员任务骗局、
--   考试答案售卖、黄牛代抢、收费内推/保offer、学生证出租出借。
-- 分级同第一批:骗局专属话术 2,可正当讨论的名词 1。
-- ============================================================

-- ---------------- 兼职任务骗局(2 拒发:警方口径「点赞/打字类兼职=诈骗」) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('打字员', 2),
('点赞员', 2),
('手工活兼职', 2),
('手工活外发', 2),
('点赞赚钱', 2),
('关注赚钱', 2),
('评论返现', 2),
('转发赚钱', 2),
('阅读赚钱', 2);

-- ---------------- 校园贷诈骗话术/超利贷(2 拒发) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('注销校园贷', 2),
('714高炮', 2);

-- ---------------- 两卡(帮信罪,2 拒发:出租出借均违法) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('高价收卡', 2),
('出租银行卡', 2),
('出租电话卡', 2);

-- ---------------- 考试答案售卖(2 拒发:组织考试作弊违法) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('四六级答案', 2),
('英语四级答案', 2),
('六级答案', 2),
('考研答案', 2),
('期末答案', 2),
('计算机二级答案', 2),
('助考团队', 2);

-- ---------------- 求职陷阱(2 拒发:人社部确认收费内推/保offer 为骗局) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('保offer', 2),
('收费内推', 2),
('内推收费', 2);

-- ---------------- 证件租借/票务黄牛(2 拒发) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('出租学生证', 2),
('收学生证', 2),
('黄牛票', 2),
('澳门赌场', 2);

-- ---------------- 灰产名词/软话术(1 替换:可正当讨论,替换即留痕) ----------------
INSERT IGNORE INTO sensitive_word (word, level) VALUES
('冒充客服', 1),
('冒充老师', 1),
('内部渠道', 1),
('代抢', 1),
('刷好评', 1),
('水军', 1),
('代练', 1),
('代跑', 1),
('代抢课', 1),
('降重', 1),
('拉新', 1),
('校园代理', 1);
