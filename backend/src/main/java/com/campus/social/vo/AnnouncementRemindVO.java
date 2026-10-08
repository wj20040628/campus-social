package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 在线提醒回执（F5，openapi {@code AnnouncementRemindResponse}）。
 *
 * <p>{@code remindedCount} 小于 {@code totalUnread} 是正常的：
 * 离线用户不落库、不推送，下次上线靠未读红点发现。
 * 若前端把两者差距当成错误提示，管理员会以为功能坏了。
 */
@Data
@Builder
public class AnnouncementRemindVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 本次实际推达的在线未读用户数 */
    private Integer remindedCount;

    /** 未读总数 */
    private Integer totalUnread;
}