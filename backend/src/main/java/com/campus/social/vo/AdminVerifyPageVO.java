package com.campus.social.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 学号核验待审列表分页（openapi AdminVerifyPageData）。
 *
 * <p>不复用 {@code CursorPage}、也不挂 {@code pendingCount}：
 * 举报列表需要全量待审数是因为手机端顶栏有红点，而核验列表没有这个 UI 元素。
 * 契约的 {@code required: [list, hasMore]} 也只要求三项，
 * 多给一个字段等于承诺了一份前端用不到的维护成本。
 */
@Data
public class AdminVerifyPageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<AdminVerifyPendingItemVO> list;

    /** 下一页游标（最后一条的 userId），没有更多时为 null */
    private Long nextCursor;

    private boolean hasMore;
}