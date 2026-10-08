package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 今日破冰额度快照（openapi.yaml StrangerQuotaData）。
 */
@Data
@Builder
public class StrangerQuotaVO {

    /** 每日最多可向几个新对象发起，来自配置 */
    private Integer dailyNewPeerLimit;

    private Integer dailyNewPeerUsed;

    /** 今日剩余可发起的新对象数 */
    private Integer remainingNewPeer;

    private Integer perPeerDailyLimit;

    /** 对指定 peer 的当日剩余条数；不传 peerId 时为 null——「剩余」必须针对具体对象才有意义 */
    private Integer remainingPerPeer;

    /** 次日 00:00（服务器时区 Asia/Shanghai），前端据此做倒计时文案 */
    private LocalDateTime resetAt;

    /** 我是否接收陌生人消息。false 时前端应提示「你已关闭陌生人搭话」 */
    private Boolean strangerMsgEnabled;
}
