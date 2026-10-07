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
}
