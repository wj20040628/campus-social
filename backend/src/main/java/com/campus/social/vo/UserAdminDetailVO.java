package com.campus.social.vo;

import lombok.Data;

import java.util.List;

/**
 * 管理端用户处置详情（GET /admin/users/{id}/detail，2026-11-08）。
 *
 * <p>列表行只能回答「这个人现在怎么样」；封禁前的关键判断是「以前发生过什么」。
 * 本 VO 一次带回最近举报、账号处置轨迹与最近帖子，避免管理员逐个点主页翻。
 */
@Data
public class UserAdminDetailVO {

    /** 与列表行同一套字段（信誉/举报/发帖/封禁次数口径完全一致） */
    private UserAdminItemVO user;

    /** 最近 20 条与本人相关的举报（用户/帖子/评论三类目标） */
    private List<AdminUserReportBriefVO> reports;

    /** 最近 20 条账号级处置记录（封禁/解封/禁言/解除/注销/清理） */
    private List<AdminUserActionBriefVO> actions;

    /** 最近 10 条帖子（含审核中/下架，用于看内容画像） */
    private List<AdminUserPostBriefVO> posts;

    /** 已注销账号的留存信息；正常账号为 null */
    private AdminUserCancelledInfoVO cancelled;
}
