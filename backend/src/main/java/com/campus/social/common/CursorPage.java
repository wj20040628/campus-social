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
    /**
     * 符合条件的总条数。可空 —— 游标分页本身不需要它,
     * 只有「N 个圈」这类计数要准的时候才由调用方塞进来。
     *
     * <p>为什么不拿已加载条数当总数:列表是无限滚动、每页 20 条,
     * 没拉完时 list.size() 只是「已经拉到的」,不是「一共有的」。
     * 为 null 表示后端没算,前端此时宁可不显示数字,也不显示错的。
     */
    private Long total;

    public CursorPage(java.util.List<T> list, Long nextCursor, boolean hasMore) {
        this.list = list;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public CursorPage(java.util.List<T> list, Long nextCursor, boolean hasMore, Long total) {
        this(list, nextCursor, hasMore);
        this.total = total;
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

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }
}
