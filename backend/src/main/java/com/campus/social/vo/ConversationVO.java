package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项(含对方用户卡片 + 未读数 + 最后一条消息摘要)
 */
@Data
@Builder
public class ConversationVO {
    private Long convId;
    /** 对方(非当前用户) */
    private UserVO peer;
    /** 当前用户侧的未读数 */
    private int unread;
    /** 最后一条消息摘要 */
    private String lastPreview;
    private Long lastMsgId;
    private LocalDateTime lastMsgTime;
    /**
     * 是否「待我回应的搭话纸条」:最后一条消息是**对方发**的、且最后活动在 24h 内。
     * 与私信未读解耦(未读要永久累积),纸条 24h 不回应即失效(见 TabBar 角标语义)。
     * 供 MainLayout 统计 TabBar「消息」角标用,纯计算不落库。
     */
    private boolean pendingReply;
}
