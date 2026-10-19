package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 意见反馈-我的分页（2026-10-19，「我的反馈」页）。
 *
 * <p>与 {@link FeedbackAdminPageVO} 同构但不带 {@code pendingCount}:
 * 用户自己的列表不需要「还剩多少待办」这种管理视角汇总。
 */
@Data
public class FeedbackMinePageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<FeedbackMineItemVO> list;

    /** 下一页游标；null 表示没有更多 */
    private Long nextCursor;

    private boolean hasMore;
}
