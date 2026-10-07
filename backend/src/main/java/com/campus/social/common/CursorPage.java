package com.campus.social.common;

/**
 * 游标分页
 * cursor 用上一页最后一行的 id（BIGINT）
 * 列表项 + 下一页游标 + 是否还有更多
 */
public class CursorPage<T> {
    private java.util.List<T> list;
    private Long nextCursor;   // 上一页最后一条的 id；为 null 表示没有更多
    private boolean hasMore;

    public CursorPage(java.util.List<T> list, Long nextCursor, boolean hasMore) {
        this.list = list;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(java.util.Collections.emptyList(), null, false);
    }

    public java.util.List<T> getList() {
        return list;
    }

    public void setList(java.util.List<T> list) {
        this.list = list;
    }

    public Long getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(Long nextCursor) {
        this.nextCursor = nextCursor;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }
}
