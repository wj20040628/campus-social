-- ============================================================================
-- migration_2026-11-14_visit_log.sql · 访客访问记录
-- ----------------------------------------------------------------------------
-- 为什么单独建表，而不是复用 audit_log：
--   audit_log 记录的是「登录、处置、审核」等需要追责的动作，带操作人和
--   目标对象；访客没有账号、没有操作人，一次浏览也不是一次需要追责的动作。
--   塞进 audit_log 会让审计列表混入大量无主记录，也会让审计保留期与
--   访问统计保留期绑死（前者按监管要求至少 180 天，后者只需 90 天）。
--
-- 为什么一个会话一行，而不是一次心跳一行：
--   心跳每 30 秒一次；按心跳追加行会让表随停留时长线性膨胀，
--   而管理端只关心「这个会话待了多久」。进入时插入，之后只更新同一行，
--   写入量是常数级。
--
-- 字段口径：
--   visitor_id  浏览器本地生成的随机 UUID，不是设备指纹，不含身份信息；
--   session_id  标签页会话 ID，唯一键，保证 enter 重试不会插入重复行；
--   is_guest    1=游客会话。游客不建账号、不发 token，只能靠前端标记；
--   entry_path  入口路径。线下二维码固定落在 /guest，扫码人数按它统计；
--   source      来源标签：qr（二维码）、guest_login（登录页游客入口）、
--               direct（直接打开），或二维码链接的 from 参数；
--   active_seconds 页面在前台的累计秒数。切后台不计时，服务端按
--               「进入至今的墙钟时长 + 5 秒」做上限，防止客户端虚报；
--   ended       1=收到过结束上报。没收到时，最后心跳超过 60 秒也按已结束
--               结算，覆盖「关浏览器时 beacon 没送达」的情况。
--
-- 保留期 90 天，由 VisitLogPurgeJob 每天分批清理。IP 与浏览器信息属于
-- 个人信息，隐私政策「自动记录」与「保存期限」两处已同步写明。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `visit_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `visitor_id`     CHAR(36)     NOT NULL                COMMENT '匿名访客ID（浏览器本地生成）',
    `session_id`     CHAR(36)     NOT NULL                COMMENT '会话ID（标签页会话）',
    `is_guest`       TINYINT      NOT NULL DEFAULT 0      COMMENT '1游客 0其他',
    `entry_path`     VARCHAR(64)  NOT NULL                COMMENT '入口路径',
    `source`         VARCHAR(32)  NULL                    COMMENT '来源标签',
    `ip`             VARCHAR(64)  NULL                    COMMENT '来源IP',
    `ua`             VARCHAR(255) NULL                    COMMENT '浏览器标识（截断）',
    `entered_at`     DATETIME     NOT NULL                COMMENT '进入时间',
    `last_seen_at`   DATETIME     NOT NULL                COMMENT '最后心跳时间',
    `active_seconds` INT          NOT NULL DEFAULT 0      COMMENT '前台累计停留秒数',
    `ended`          TINYINT      NOT NULL DEFAULT 0      COMMENT '1已收到结束上报',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_visit_session` (`session_id`),
    -- 支撑「今日扫码 / 今日游客」与昨日对比
    KEY `idx_visit_entered` (`entered_at`),
    -- 支撑来源分组
    KEY `idx_visit_source` (`source`, `entered_at`),
    -- 支撑同一访客多会话的回查（后续转化统计用）
    KEY `idx_visit_visitor` (`visitor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='访客访问记录';

-- 回滚(留档,与项目其它 migration 同惯例):
-- DROP TABLE IF EXISTS `visit_log`;