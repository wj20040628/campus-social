package com.campus.social.vo;

import lombok.Data;

/**
 * 近 7 日按天聚合的一行（2026-11-14 加）。
 *
 * <p>口径与 {@link GuestVisitVO} 当日数字一致：
 * 扫码访客按 visitor_id 去重，扫码打开按入口 /guest 的会话数，
 * 游客会话按 is_guest = 1。没有数据的日子由 service 补零，
 * 保证前端拿到的永远是连续 7 天。
 */
@Data
public class VisitDayStatVO {

    /** yyyy-MM-dd（服务器时区的自然日） */
    private String date;

    private long scanVisitors;

    private long scanSessions;

    private long guestSessions;
}