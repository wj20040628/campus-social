package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 发送消息回执（F3）。
 *
 * <p>2026-10-28：不再有「新对象名额」。{@code remainingPerPeerQuota}
 * 只在本次按陌生人规则发送时有值；对方已关注/回复时无额度限制，为 null。
 */
@Data
@Builder
public class ChatSendVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long msgId;

    /** 本次是否属于向陌生人发送（用于前端规则提示与埋点） */
    private Boolean isStranger;

    /** 本次发送后对同一陌生人的剩余条数；非陌生人场景为 null */
    private Integer remainingPerPeerQuota;

    /**
     * 打码后的消息文本（2026-10-24 加）。
     * 发送路径入库前会做一级敏感词替换(MessageContentGuard),
     * 前端乐观气泡必须渲染这个版本而不是本地草稿——否则发送者自己
     * 看到原文、接收方看到星号,同一句话两个人读到的内容不一样。
     */
    private String content;
}
