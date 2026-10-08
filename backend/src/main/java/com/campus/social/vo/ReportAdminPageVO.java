package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端举报分页（F4，openapi.yaml:1252-1268）。
 *
 * <p>契约要求 data 上除 list/nextCursor/hasMore 外还挂一个同级的
 * {@code pendingCount}，故不复用 {@code CursorPage}——往它上面加字段
 * 会让所有不带计数的列表也多出一个 null 字段。
 *
 * <p>{@code pendingCount} 是 status=0 的<b>全量</b>总数而非本页统计：
 * 手机端审核页的顶栏红点需要「还剩多少待办」，翻到第 3 页时红点不该显示 0。
 */
@Data
public class ReportAdminPageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<ReportVO> list;

    /** 下一页游标；null 表示没有更多 */
    private Long nextCursor;

    private boolean hasMore;

    /** status=0 的总数，供手机端顶栏红点 */
    private Integer pendingCount;
}