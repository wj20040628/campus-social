-- ============================================================================
-- 迁移:migration_v2 —— user 表新增 email 安全凭证列(2026-10-17)
--
-- 依据:账号体系重构(学号为主账号,邮箱仅作安全凭证:找回密码/绑定/次要登录)
-- 目标:MySQL 8.4 LTS(本机 dev 实测 9.0.1,行为一致)
--
-- 为什么另起一个文件,不追加进 migration_v1.sql:
--   migration_v1.sql 第 4 / 8.4 节的 ALTER TABLE ADD COLUMN 在 MySQL 8.4 上
--   **不支持 IF NOT EXISTS**,已在 v1 文件头注明「不可重复执行,整文件重跑会报
--   1060 Duplicate column 并中断后续语句」。把本次改动追加进 v1 会破坏那条纪律,
--   也逼着已执行过 v1 的库必须整文件重跑。独立成 v2 既幂等可控,也和历史迁移平级。
--
-- 执行前提:
--   1. 库 campus_social_v2 存在,user 表已含 v1 的全部列
--   2. 执行账号具备 ALTER 权限
--   3. 执行前先备份:mysqldump -uroot -p campus_social_v2 > bak_v2_email.sql
--
-- 执行方式(一次性):
--   mysql -uroot -p campus_social_v2 < docs/sql/migration_v2.sql
--
-- 幂等性说明:
--   - ALTER TABLE ADD COLUMN 在 8.4 不支持 IF NOT EXISTS;本文件只动了 user 表,
--     已加过列的库重跑会报 1060,此时按文末「若已执行过」单独跳过 ADD COLUMN、
--     只跑校验查询即可。
--   - ADD UNIQUE KEY 同样不可重复,报 1061 Duplicate key 时跳过该条即可。
--
-- 字符集:与 v1 完全一致 —— 只写 DEFAULT CHARSET=utf8mb4,不带 COLLATE。
--   原因:v1 文件头已实测,varchar 列若显式写 COLLATE 会和现有 22 张表
--   (utf8mb4_0900_ai_ci)产生 Illegal mix of collations。email 是普通字符串列,
--   走默认 collation 即可与兄弟列同 collation。
-- ============================================================================

USE `campus_social_v2`;

-- ----------------------------------------------------------------------------
-- email:安全凭证邮箱,有值时唯一
--
-- 语义(与 v1 的 username 列解耦):
--   - v1 时代 username 存的是「邮箱或手机号」,是登录主标识。
--   - 重构后 username 改为存「学号」(主账号),email 单列出来只承担
--     安全凭证职责:找回密码、绑定、以及「已绑定邮箱」用户的邮箱验证码登录。
--   - 普通登录用学号(或绑定的邮箱)走 username/email 两个等值匹配,
--     email 不再作为唯一登录主标识,但可以 UNIQUE(有值时)防止两个账号绑同一邮箱。
--
-- 为什么可空 + 有值时唯一,而不是 NOT NULL UNIQUE:
--   注册时邮箱选填;未绑邮箱的用户 email 为 NULL。InnoDB 的 UNIQUE 对 NULL
--   天然放行(多行 NULL 不冲突),无需 partial index。只有填了邮箱才需唯一。
-- ----------------------------------------------------------------------------
ALTER TABLE `user`
    ADD COLUMN `email` VARCHAR(64) NULL COMMENT '安全凭证邮箱,有值时唯一。仅用于找回密码/绑定/已绑定用户的邮箱验证码登录';

ALTER TABLE `user`
    ADD UNIQUE KEY `uk_user_email` (`email`);

-- ============================================================================
-- 执行后校验(应逐条手工确认)
-- ============================================================================

-- 1. email 列应存在且可空、默认 NULL
SELECT column_name, column_type, is_nullable, column_default, column_comment
FROM information_schema.columns
WHERE table_schema = 'campus_social_v2' AND table_name = 'user'
  AND column_name = 'email';
-- 期望:1 行,is_nullable=YES,column_default=NULL

-- 2. uk_user_email 应存在
SELECT table_name, index_name, non_unique
FROM information_schema.statistics
WHERE table_schema = 'campus_social_v2'
  AND index_name = 'uk_user_email';
-- 期望:1 行,non_unique=0(唯一)

-- 3. collation 一致性(防 Illegal mix of collations):user 表所有列 collation 应一致
SELECT DISTINCT character_set_name, collation_name
FROM information_schema.columns
WHERE table_schema = 'campus_social_v2' AND table_name = 'user'
  AND character_set_name IS NOT NULL;
-- 期望:只有 1 行(全表同字符集/排序规则)

-- ============================================================================
-- 回滚脚本(仅在需要撤销本迁移时使用,勿常规执行)
-- ============================================================================
-- ALTER TABLE `user` DROP INDEX `uk_user_email`;
-- ALTER TABLE `user` DROP COLUMN `email`;
