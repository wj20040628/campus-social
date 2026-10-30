package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 今日破冰额度快照（openapi.yaml StrangerQuotaData）。
 *
 * <p>2026-10-28：不再有「新对象名额」，只剩「对同一陌生人每天 N 条」。
 * {@code isStranger=false} 表示对方已关注我或回复过我，额度不适用。
 */
@Data
@Builder
public class StrangerQuotaVO {

    /** 对同一陌生人每天最多几条，来自配置（默认 1） */
    private Integer perPeerDailyLimit;

    /**
     * 对指定 peer 的当日剩余条数。
     * 不传 peerId 或已不是陌生人时为 null——「剩余」必须针对具体对象才有意义。
     */
    private Integer remainingPerPeer;

    /** 是否仍是陌生人（传 peerId 时才有值；false = 对方已关注/回复，无额度限制） */
    private Boolean isStranger;

    /** 次日 00:00（服务器时区 Asia/Shanghai），前端据此做倒计时文案 */
    private LocalDateTime resetAt;

    /** 我是否接收陌生人消息。false 时前端应提示「你已关闭陌生人搭话」 */
    private Boolean strangerMsgEnabled;

    /**
     * 向陌生人发起搭话的行为门槛未达标原因（账号太新 / 信誉分不足 / 账号状态异常）。
     * null 表示可以发起；非陌生人不返回（对方已关注或回复过，不受门槛限制）。
     */
    private String initiationBlockedReason;
}
