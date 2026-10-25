package com.campus.social.vo;

import lombok.Data;

import java.util.List;

/**
 * 用户处置列表分页外壳（AdminUserController，openapi schema 同名）。
 * 与 ReportAdminPageVO 同构：nextCursor/hasMore 驱动无限滚动，
 * total 是**符合当前筛选**的全量数（不是本页条数）。
 */
@Data
public class UserAdminPageVO {

    private List<UserAdminItemVO> list;

    private Long nextCursor;

    private boolean hasMore;

    /** 当前筛选条件下的总用户数；「共 N 人」用它，不能拿 list.length 冒充 */
    private Long total;
}
