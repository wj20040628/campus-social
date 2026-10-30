package com.campus.social.vo;

/**
 * 集市活动状态（「我的发布」4 态）。
 * 由服务端按固定优先级推导，前端不再自行判断。
 */
public enum MktStatus {
    /** 🟢 投放中：is_enable=1 且 未满员 且 未过期 且 未软删 */
    LAUNCHING,
    /** 🟡 已满员：is_full=1 */
    FULL,
    /** ⚠️ 手动暂停：is_enable=0 */
    PAUSED,
    /** ⚫ 已过期：expire_time <= now */
    EXPIRED;

    /**
     * 优先级（从高到低）：软删(调用方已过滤) > 满员 > 暂停 > 过期 > 投放中。
     * 满员优先于暂停/过期：满了就停接收，无论开关状态。
     */
    public static MktStatus derive(boolean isFull, boolean isEnable, boolean expired) {
        if (isFull) {
            return FULL;
        }
        if (!isEnable) {
            return PAUSED;
        }
        if (expired) {
            return EXPIRED;
        }
        return LAUNCHING;
    }
}
