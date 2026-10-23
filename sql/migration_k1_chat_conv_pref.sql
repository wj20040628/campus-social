-- 会话偏好：置顶 / 免打扰 / 删除（2026-10-20）
-- 用途：手机端会话行右滑出现三个动作，桌面端同享同一份数据。
--
-- 为什么单独建表，而不是给 chat_conversation 加列：
--   1) chat_conversation 是**两人共享一行**（member_a/member_b），而置顶/免打扰/删除
--      是**每人独立**的。加列就得 pinned_a/pinned_b/muted_a/muted_b... 六个列，
--      群聊那边（group/group_member）还得再来一遍，两套代码两套接口。
--   2) 私信和群聊在这里是同一个用户诉求（把这条会话钉上去/静音/从列表里拿掉），
--      用 (user_id, kind, target_id) 一张表，一套读写接口就能覆盖两种会话。
--   3) 不改动 chat_conversation 现有列，历史数据零迁移风险。
--
-- kind/target_id 的口径：
--   dm    -> target_id = 对方的 user_id（不是 conv_id：conv 行可能被重建，
--            而 peer 是稳定的；换入口从主页直接发起也能对上同一条偏好）
--   group -> target_id = group.id
--
-- 删除为什么存 hidden_upto 而不是布尔：
--   用户删掉会话后，对方再发消息应该重新出现在列表里（否则等于拉黑）。
--   删的瞬间记下"当时最后一条消息 id"，列表过滤条件是
--   hidden=1 AND lastMsgId <= hidden_upto —— 来了新消息 lastMsgId 变大，
--   条件自然不成立，会话自己就回来了。发消息那条链路一行都不用改。

CREATE TABLE IF NOT EXISTS chat_conv_pref (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '偏好的归属人',
    kind        VARCHAR(8)   NOT NULL COMMENT '会话类型:dm=私信, group=群聊',
    target_id   BIGINT       NOT NULL COMMENT 'dm=对方user_id, group=group.id',
    pinned      TINYINT      NOT NULL DEFAULT 0 COMMENT '置顶:0否 1是',
    muted       TINYINT      NOT NULL DEFAULT 0 COMMENT '免打扰:0否 1是',
    hidden      TINYINT      NOT NULL DEFAULT 0 COMMENT '已从列表删除:0否 1是',
    hidden_upto BIGINT       NOT NULL DEFAULT 0 COMMENT '删除时的最后消息id;lastMsgId>此值则重新显示',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pref (user_id, kind, target_id) COMMENT '一个用户对一条会话只有一份偏好',
    KEY idx_user_kind (user_id, kind) COMMENT '列表一次取某用户某类型的全部偏好'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='会话偏好(置顶/免打扰/删除),按人存';
