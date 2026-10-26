-- ============================================================
-- 校园社交 · 只保留管理员 user.id = 1
--
-- 效果：
--   1) user 表只留 id=1（admin@campus.test，role=1 管理员）
--   2) 其余 36 张表全部 TRUNCATE —— 包括管理员自己发过的
--      帖子/评论/私信/通知等，全部归零
--   3) 字典表一个字都不动：
--        sensitive_word        敏感词库
--        school_dept           学院
--        school_grade          年级
--        school_major          专业
--        interest_tag          兴趣标签
--        topic                 话题
--        reserved_circle_name  预留圈名
--      只把它们的冗余计数（use_count / post_count）归零
--
-- 前置条件（必须）：先做 mysqldump 备份。
--   mysqldump --single-transaction --routines --triggers \
--     --default-character-set=utf8mb4 --no-tablespaces \
--     -u campus -p campus_social_v2 > /opt/backup_before_clean.sql
--
-- 安全性：
--   * 幂等，可重复执行
--   * id=1 不存在或不是管理员时直接中止，不会清出一个
--     「没人能进后台」的站
--   * 表名单从 information_schema 动态取，migration 后新增的表
--     也会被清到，不会漏
--   * 注意：本脚本假设数据库里没有 Flyway 的 flyway_schema_history
--     表（本项目未用 Flyway）。若以后引入 Flyway，务必把它加进
--     下面的保留白名单，否则应用下次启动会重跑全部 migration。
-- ============================================================
USE `campus_social_v2`;

DROP PROCEDURE IF EXISTS clean_keep_user1;

DELIMITER $$
CREATE PROCEDURE clean_keep_user1()
BEGIN
  DECLARE v_role TINYINT DEFAULT NULL;
  DECLARE v_done INT     DEFAULT 0;
  DECLARE v_tab  VARCHAR(64);

  DECLARE cur CURSOR FOR
    SELECT table_name
      FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_type   = 'BASE TABLE'
       AND table_name NOT IN (
           'user',
           'sensitive_word', 'school_dept', 'school_grade',
           'school_major', 'interest_tag', 'topic', 'reserved_circle_name'
       );

  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  -- ---------- 1) 安全检查 ----------
  -- 用 MAX() 而不是 SELECT ... INTO：聚合永远返回一行，
  -- 不会触发上面的 NOT FOUND handler 把游标循环提前结束。
  SELECT IFNULL(MAX(role), -1) INTO v_role FROM `user` WHERE id = 1;

  IF v_role = -1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '已中止：user 表里没有 id=1，请先核对账号';
  END IF;

  IF v_role <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '已中止：user.id=1 不是管理员(role<>1)，清完将无人能进后台';
  END IF;

  -- ---------- 2) 清空所有非白名单表 ----------
  SET FOREIGN_KEY_CHECKS = 0;

  OPEN cur;
  clean_loop: LOOP
    FETCH cur INTO v_tab;
    IF v_done = 1 THEN
      LEAVE clean_loop;
    END IF;

    SET @ddl = CONCAT('TRUNCATE TABLE `', v_tab, '`');
    PREPARE st FROM @ddl;
    EXECUTE st;
    DEALLOCATE PREPARE st;
  END LOOP;
  CLOSE cur;

  SET FOREIGN_KEY_CHECKS = 1;

  -- ---------- 3) 账号只留 id=1，新用户从 id=2 开始 ----------
  DELETE FROM `user` WHERE id <> 1;
  ALTER TABLE `user` AUTO_INCREMENT = 2;

  -- ---------- 4) 保留账号的冗余计数归零 ----------
  UPDATE `user`
     SET follower_count      = 0,
         following_count     = 0,
         unread_notify_count = 0,
         joined_circle_count = 0
   WHERE id = 1;

  -- ---------- 5) 字典表的冗余计数归零（字典数据本身不动）----------
  UPDATE `interest_tag` SET use_count  = 0;
  UPDATE `topic`        SET post_count = 0;
END$$
DELIMITER ;

CALL clean_keep_user1();
DROP PROCEDURE clean_keep_user1;

-- ============================================================
-- 执行后自检
--   字典表行数应与执行前一致（下面括号里是 seed 初始值，仅供参考；
--   若线上扩充过词库/专业，数字会比这个大，属正常）
--   内容表应全为 0，user 应为 1
-- ============================================================
SELECT 'user'                   AS `表`, COUNT(*) AS `行数`, '应为 1' AS `期望` FROM `user`
UNION ALL SELECT 'sensitive_word',      COUNT(*), '与执行前一致(seed=154)' FROM `sensitive_word`
UNION ALL SELECT 'school_dept',         COUNT(*), '与执行前一致(seed=7)'   FROM `school_dept`
UNION ALL SELECT 'school_grade',        COUNT(*), '与执行前一致(seed=4)'   FROM `school_grade`
UNION ALL SELECT 'school_major',        COUNT(*), '与执行前一致(seed=62)'  FROM `school_major`
UNION ALL SELECT 'interest_tag',        COUNT(*), '与执行前一致(seed=60)'  FROM `interest_tag`
UNION ALL SELECT 'topic',               COUNT(*), '字典保留,post_count 归零' FROM `topic`
UNION ALL SELECT 'reserved_circle_name',COUNT(*), '与执行前一致(seed=38)'  FROM `reserved_circle_name`
UNION ALL SELECT 'post',                COUNT(*), '应为 0' FROM `post`
UNION ALL SELECT 'comment',             COUNT(*), '应为 0' FROM `comment`
UNION ALL SELECT 'post_favorite',       COUNT(*), '应为 0' FROM `post_favorite`
UNION ALL SELECT 'chat_message',        COUNT(*), '应为 0' FROM `chat_message`
UNION ALL SELECT 'chat_conversation',   COUNT(*), '应为 0' FROM `chat_conversation`
UNION ALL SELECT 'group',               COUNT(*), '应为 0' FROM `group`
UNION ALL SELECT 'group_message',       COUNT(*), '应为 0' FROM `group_message`
UNION ALL SELECT 'circle',              COUNT(*), '应为 0' FROM `circle`
UNION ALL SELECT 'circle_member',       COUNT(*), '应为 0' FROM `circle_member`
UNION ALL SELECT 'relation',            COUNT(*), '应为 0' FROM `relation`
UNION ALL SELECT 'notify',              COUNT(*), '应为 0' FROM `notify`
UNION ALL SELECT 'review_task',         COUNT(*), '应为 0' FROM `review_task`
UNION ALL SELECT 'report',              COUNT(*), '应为 0' FROM `report`
UNION ALL SELECT 'feedback',            COUNT(*), '应为 0' FROM `feedback`
UNION ALL SELECT 'audit_log',           COUNT(*), '应为 0' FROM `audit_log`
UNION ALL SELECT 'announcement',        COUNT(*), '应为 0' FROM `announcement`
UNION ALL SELECT 'upload_file',         COUNT(*), '应为 0' FROM `upload_file`
UNION ALL SELECT 'user_tag',            COUNT(*), '应为 0' FROM `user_tag`;

-- 最后确认留下的那个账号
SELECT id, username, nickname, role, status, verify_status,
       follower_count, following_count, unread_notify_count, joined_circle_count
  FROM `user`;