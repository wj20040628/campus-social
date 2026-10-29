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

    /**
     * 文件库条目（file_asset）。2026-10-27 新增。
     *
     * <p>处置对象是文件上传者；举报成立时把文件 status 置 3（下架），
     * 不删除行，保留审核与申诉证据。
     */
    public static final int FILE = 7;

    /**
     * 群消息（group_message）。2026-10-28 新增。
     *
     * <p>不复用 4（私聊消息）：两张表各自自增 id，同一个 id 在两表里
     * 指向完全不同的内容。群消息按 4 提交时，管理员会看到另一条私信的作者
     * 与正文，封错人、删错消息都是必然。
     */
    public static final int GROUP_MESSAGE = 8;

    /**
     * 圈子（circle）。2026-10-29 新增。
     *
     * <p>自评估报告承诺「用户可对圈子发起举报」。处置对象是圈子本身：
     * 管理员选「仅下架」= 解散圈子（status=1，圈内帖子级联 status=2），
     * 不封圈主；选「成立并封禁」才会同时封禁圈主。归因（圈主）与摘要（圈名）
     * 分别走 {@code selectCircleOwnerId} / {@code selectCircleSummary}。
     */
    public static final int CIRCLE = 9;
}
