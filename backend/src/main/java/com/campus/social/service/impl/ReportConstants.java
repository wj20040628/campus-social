package com.campus.social.service.impl;

/**
 * 举报处置的动作码与状态码（2026-11-14 拆类时收口）。
 *
 * <p>动作码同时出现在入口分派与各处置实现里，字符串在两处各写一份就会漂移；
 * 状态码是 report.status 的库内取值，同样只允许存在一份。
 */
final class ReportConstants {

    /** report.status */
    static final int STATUS_PENDING = 0;
    static final int STATUS_CONFIRMED = 1;
    static final int STATUS_REJECTED = 2;

    /** 处置动作（与 openapi / 前端共用） */
    static final String ACTION_BAN = "ban";
    static final String ACTION_MUTE = "mute";
    static final String ACTION_TAKEDOWN = "takedown";
    static final String ACTION_REJECT = "reject";
    static final String ACTION_IGNORE = "ignore";

    private ReportConstants() {
    }
}