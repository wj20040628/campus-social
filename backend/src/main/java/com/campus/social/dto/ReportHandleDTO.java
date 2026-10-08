package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 举报处置（F4 管理端）。
 *
 * <p>{@code banDays} 不做 NotNull 校验：只有 action=ban 时才需要，
 * 在 service 里按动作校验比在 DTO 上加条件约束更清楚。
 * 取值 0/1/7/30，0 表示永久封禁。
 */
@Data
public class ReportHandleDTO {

    /** ban 成立并封禁 / reject 驳回 / ignore 忽略 */
    @NotBlank(message = "处置动作不能为空")
    private String action;

    /** action=ban 时必填。1/7/30，0 表示永久封禁 */
    private Integer banDays;

    @Size(max = 200, message = "处置备注最多 200 字")
    private String handleNote;
}