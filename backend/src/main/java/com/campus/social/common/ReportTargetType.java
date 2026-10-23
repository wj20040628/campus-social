package com.campus.social.common;

/**
 * 举报目标类型（report.target_type）常量。
 *
 * <p>此前这些数字散落在 ReportServiceImpl 的 switch 里、前端 ReportSheet 的
 * TARGET 对象里、以及 MktDetail 的模板字面量 {@code :target-type="5"} 里，
 * 三处各写一份，含义靠注释维持 —— 2026-10-21 就因此出了真实故障：
 * 前端集市活动传 5，而契约里 5 是「搭子（buddy_post，未上线）」，
 * 结果集市活动的举报**能提交、管理员却无法处置**（回 422「搭子需上线后处理」）。
 *
 * <p>把编号收到一处之后，「5 是搭子、6 才是集市活动」这件事只有一份定义，
 * 前端通过 openapi 契约同步，不会再出现两边对不上的情况。
 */
public final class ReportTargetType {

    private ReportTargetType() {
    }

    /** 用户 */
    public static final int USER = 1;

    /** 帖子 */
    public static final int POST = 2;

    /** 评论 */
    public static final int COMMENT = 3;

    /** 聊天消息 */
    public static final int CHAT_MESSAGE = 4;

    /**
     * 搭子（buddy_post）。
     * MVP+ 第二批：表已建、无端点，故只支持驳回/忽略，不支持封禁。
     */
    public static final int BUDDY_POST = 5;

    /**
     * 集市活动（mkt_activity）。2026-10-21 新增。
     *
     * <p>为什么不复用 5：搭子的处置对象是 buddy_post.author_id，
     * 集市的处置对象是 mkt_activity.user_id —— 不同的表、不同的字段。
     * 复用会让「封禁作者」的解析逻辑在两种对象间漂移，
     * 而类型编号一旦失去唯一含义，switch 里就再也说不清哪个分支对哪种对象。
     */
    public static final int MKT_ACTIVITY = 6;
}
