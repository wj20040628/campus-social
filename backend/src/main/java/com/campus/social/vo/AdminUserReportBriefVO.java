package com.campus.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 用户详情里的单条举报摘要（GET /admin/users/{id}/detail，2026-11-08）。 */
@Data
public class AdminUserReportBriefVO {
    private Long id;
    /** 1用户 2帖子 3评论 */
    private Integer targetType;
    private Long targetId;
    /** 1骚扰 2广告 3色情 4诈骗 5其他 */
    private Integer reasonType;
    private String reasonText;
    /** 0待审 1成立 2驳回 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime handleTime;
    private String handleAction;
    private Integer handleDays;
    private String handleNote;
}
