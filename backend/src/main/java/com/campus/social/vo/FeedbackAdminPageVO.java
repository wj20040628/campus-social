package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 意见反馈管理端分页（2026-10-18）。
 *
 * <p>与 {@link ReportAdminPageVO} 同构，不复用 {@code CursorPage}：
 * 往 CursorPage 上加 pendingCount 会让所有不带计数的列表也多出一个 null 字段。
 *
 * <p>{@code pendingCount} 是 status=0 的<b>全量</b>总数而非本页统计 ——
 * 手机端顶栏红点要的是「还剩多少待办」，翻到第 3 页时红点不该显示 0。
 */
@Data
public class FeedbackAdminPageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<FeedbackAdminItemVO> list;

    /** 下一页游标；null 表示没有更多 */
    private Long nextCursor;

    private boolean hasMore;

    /** status=0 的总数，供手机端顶栏红点 */
    private Integer pendingCount;
}
