package com.campus.social.vo;

import lombok.Data;

/**
 * 管理总览（GET /admin/overview，2026-11-07 新增）。
 *
 * <p>此前管理端只有各页入口上的待办角标，没有一屏能看全「今天有没有异常、
 * 站内现在多大规模」。本 VO 把四组数字一次回给前端：
 * <ul>
 *   <li>{@link #pending}：五条队列的待办数，口径复用 /admin/pending-summary，
 *       两处数字必须一致——入口角标与总览对不上会让管理员不知道该信哪个；</li>
 *   <li>今日：从自然日 00:00 起算的新增用户 / 帖子 / 举报；</li>
 *   <li>规模：在册用户、已注销、公开帖子、评论、正常圈子、正常群组、在投集市、已发布公告；</li>
 *   <li>风控：封禁中、禁言中。</li>
 * </ul>
 * 所有计数都是实时 COUNT，不做缓存：校园量级下每个查询走索引，
 * 而「总览显示旧数字」比慢几十毫秒更糟。
 */
@Data
public class AdminOverviewVO {

    /** 五条队列待办数（与 /admin/pending-summary 同一份口径） */
    private PendingSummaryVO pending;

    /** 在册用户（status != 2，含封禁中） */
    private long activeUsers;

    /** 已注销账号（status = 2） */
    private long cancelledUsers;

    /** 封禁中（status = 1） */
    private long bannedUsers;

    /** 禁言中（status = 0 且 mute_until > NOW()） */
    private long mutedUsers;

    /** 已发布帖子（status = 1，未删除） */
    private long posts;

    /** 正常评论（status = 1，未删除） */
    private long comments;

    /** 正常圈子（status = 0） */
    private long circles;

    /** 正常群组（status = 1） */
    private long groups;

    /** 在投集市（未删除、开启、未过期） */
    private long mktActive;

    /** 已发布公告（status = 1） */
    private long announcements;

    /** 今日新增在册用户 */
    private long todayNewUsers;

    /** 今日新发帖子（不含已删除） */
    private long todayNewPosts;

    /** 今日新增举报 */
    private long todayReports;

    /*
     * 以下五项是 2026-11-08 的「可行动化」补充：
     * 只给今日数字看不出趋势，只给待办数看不出积压多久。
     */

    /** 昨日新增在册用户（对比今日用） */
    private long yesterdayNewUsers;

    /** 昨日新发帖子 */
    private long yesterdayNewPosts;

    /** 昨日新增举报 */
    private long yesterdayReports;

    /** 最久一条机审待审任务已等待的小时数；无待审为 0 */
    private long oldestPendingReviewHours;

    /** 最久一条待处置举报已等待的小时数；无待办为 0 */
    private long oldestPendingReportHours;
}
