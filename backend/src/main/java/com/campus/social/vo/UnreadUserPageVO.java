package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 「谁还没看」名单页（F5，AC-10，openapi {@code AnnouncementUnreadUsersResponse}）。
 *
 * <p>{@code totalUnread} 是全量未读人数而非本页条数：管理员要看的是
 * 「还有多少人没看」这个总数，翻页时它不该变。
 */
@Data
public class UnreadUserPageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<UnreadUserVO> list;

    /** 下一页游标；null 表示没有更多 */
    private Long nextCursor;

    private boolean hasMore;

    /** 未读总人数 */
    private Integer totalUnread;
}