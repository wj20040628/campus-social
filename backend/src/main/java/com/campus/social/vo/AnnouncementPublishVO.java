package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 公告发布/编辑回执（F5 管理端）。
 *
 * <p>{@code pushedOnlineCount} 让管理员知道「这条公告触达了多少人」，
 * 没有它就只能在对方抱怨「没收到」时靠猜。
 * 草稿为 0，因为草稿从未推送。
 */
@Data
@Builder
public class AnnouncementPublishVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 0草稿 1已发布 2已下架 */
    private Integer status;

    /** 0否 1是。编辑回执带此字段，前端可就地刷新置顶样式 */
    private Integer isTop;

    private java.time.LocalDateTime topExpireTime;

    /** 发布时通过 WS 触达的在线用户数；草稿为 0 */
    private Integer pushedOnlineCount;
}