package com.campus.social.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交举报（F4）。
 *
 * <p>{@code reasonText} 上限按 openapi 契约取 200 字，但 {@code report.reason_text}
 * 列宽 500，两者取小。举报人不是专业人士，写超过 200 字本身就说明该走客服。
 */
@Data
public class ReportSubmitDTO {

    /**
     * 1用户 2帖子 3评论 4聊天消息 5搭子 6集市活动。
     *
     * <p>上限从 5 提到 6（2026-10-21）：集市活动举报原先误用 5，
     * 而 5 在契约里是「搭子（buddy_post，表已建但无端点）」——
     * 结果是集市举报提交成功、管理员处置时回 422，等于举报无效。
     * 6 是集市活动的独立编号，取值含义见
     * {@code com.campus.social.common.ReportTargetType}。
     */
    @NotNull(message = "举报对象类型不能为空")
    @Min(value = 1, message = "举报对象类型不合法")
    @Max(value = 6, message = "举报对象类型不合法")
    private Integer targetType;

    @NotNull(message = "举报对象不能为空")
    private Long targetId;

    /** 1骚扰 2广告 3色情 4诈骗 5其他 */
    @NotNull(message = "举报原因不能为空")
    @Min(value = 1, message = "举报原因不合法")
    @Max(value = 5, message = "举报原因不合法")
    private Integer reasonType;

    @Size(max = 200, message = "补充说明最多 200 字")
    private String reasonText;
}