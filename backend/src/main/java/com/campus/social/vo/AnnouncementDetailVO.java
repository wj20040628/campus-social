package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告详情（F5，前台）。
 *
 * <p>{@code canViewUnreadList} 是 AC-10 的「谁还没看」入口开关：
 * 只有「自己是发布方 且 已认证」时才为 true。
 * 任何登录用户都能看未读名单会让公告发布方能导出全校用户的阅读画像，
 * 这不是公告功能该有的能力。
 */
@Data
@Builder
public class AnnouncementDetailVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    /** 正文，纯文本 + 换行，不做富文本（MVP 不做富文本编辑器） */
    private String content;

    /** 1教务 2后勤 3活动 4社团 5紧急 6其他 */
    private Integer category;

    /** 0否 1是 */
    private Integer isTop;

    private LocalDateTime topExpireTime;

    /** 0草稿 1已发布 2已下架 */
    private Integer status;

    private LocalDateTime publishTime;

    private Integer readCount;

    private Boolean isRead;

    /** 已读时间；未读为 null */
    private LocalDateTime readTime;

    private AnnouncementPublisherVO publisher;

    /** 我能否查看「谁还没看」名单（AC-10） */
    private Boolean canViewUnreadList;
}