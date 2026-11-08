package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 作者侧审核结果(自己的帖子为什么被驳/通过)。
 * 由 review_task + post join 出,只暴露作者该看到的信息:
 * 结论、驳回理由、命中的敏感词、AI 机审预判。敏感词/理由用于让作者"改了再提交"。
 */
@Data
@Builder
public class MyReviewVO {
    private Long postId;

    /** 帖子当前状态:0审核中 1通过 2驳回 */
    private Integer status;

    /** 命中的敏感词(送审时落库;空=无) */
    private List<String> hitWords;

    /** 最终驳回理由(人审 or AI 驳回时填) */
    private String reason;

    /** AI 机审结论:approve / reject / manual(空=未机审或无 key 兜底) */
    private String aiVerdict;

    /** AI 机审理由 */
    private String aiReason;

    /** 实际处理人 id；空 = AI 机审或系统 */
    private Long reviewerId;

    /** 处理人展示名：圈主/圈管理昵称；平台管理员只显示「平台管理员」 */
    private String reviewerName;

    /** 处理人角色：圈主 / 圈管理；平台管理员与 AI 机审的名称已自说明，留空 */
    private String reviewerRole;

    /** 处理完成时间；待审为空 */
    private LocalDateTime reviewedAt;

    private LocalDateTime createTime;
}
