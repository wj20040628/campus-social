package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 学号认证回执（F2 人工核验兜底）。
 *
 * <p><b>没有 reviewTaskId 字段</b>：openapi 的
 * {@code StudentNoVerifyResponse.reviewTaskId} 说「落进 review_task 的任务 id」，
 * 但 {@code review_task} 是帖子专用队列（{@code post_id BIGINT NOT NULL} +
 * {@code uk_post}），学号不是帖子，插不进去。
 * ARCHITECTURE §3 Q9.4 已裁定「学号核验不进 review_task，队列是 user 表本身」，
 * 故此处只回状态与预期时长。该冲突已上报。
 */
@Data
@Builder
public class StudentNoVerifyVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 固定返回 2 审核中 */
    private Integer verifyStatus;

    /** 人工核验的预期时长（小时），供前端文案 */
    private Integer estimatedHours;
}