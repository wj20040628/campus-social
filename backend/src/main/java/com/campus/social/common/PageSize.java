package com.campus.social.common;

/**
 * 分页 size 的统一钳制:防止调用方传超大 size(如 1000000)拉全表(DoS)。
 *
 * 上限 100:群消息/Feed 单页默认 20-30,100 已远超正常阅读粒度,再大无意义。
 * 下限 1:0/负值无意义,至少返回 1 条。
 *
 * 用法:在 controller 入口把 {@code @RequestParam size} 过一遍
 * {@code size = PageSize.clamp(size);} 再传给 service。
 */
public final class PageSize {

    /** 单页最大条数。改动前先评估对 DB 的写扩散/读放大影响。 */
    public static final int MAX = 100;
    public static final int MIN = 1;

    private PageSize() {}

    /** 钳制 size 到 [MIN, MAX];null 由调用方先兜底,这里只处理 int。 */
    public static int clamp(int size) {
        return Math.max(MIN, Math.min(MAX, size));
    }
}
