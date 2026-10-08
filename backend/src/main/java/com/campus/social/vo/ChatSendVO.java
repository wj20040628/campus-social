package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 发送消息回执（F3）。
 *
 * <p>{@code remainingNewPeerQuota} 只在「本次是向新对象发起首条」时有值，
 * 非新对象场景为 null —— 前端据此决定要不要刷新「还能搭话 N 人」文案，
 * 盲目清零会让用户看到额度凭空蒸发。
 */
@Data
@Builder
public class ChatSendVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long msgId;

    /** 本次是否属于向陌生人发起（用于前端埋点） */
    private Boolean isStranger;

    /** 本次为向新对象发起首条时的剩余额度；否则 null */
    private Integer remainingNewPeerQuota;
}