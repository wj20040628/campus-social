package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 未读公告数（F5 红点，openapi {@code AnnouncementUnreadCountResponse}）。
 *
 * <p>{@code latestAnnouncementId} 让红点点击后能直达最新一条未读，
 * 而不是把用户丢回列表页让他自己找。
 */
@Data
@Builder
public class UnreadCountVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer unreadCount;

    /** 最新一条未读公告 id；无未读为 null */
    private Long latestAnnouncementId;
}