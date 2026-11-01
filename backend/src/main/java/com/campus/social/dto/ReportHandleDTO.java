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

    /** ban 成立并封禁 / mute 成立并禁言 / takedown 成立仅下架 / reject 驳回 / ignore 忽略 */
    @NotBlank(message = "处置动作不能为空")
    private String action;

    /** action=ban 时必填。1/7/30，0 表示永久封禁 */
    private Integer banDays;

    /** action=mute 时必填。1~365 天；禁言期间可登录浏览，不能发布内容和消息 */
    private Integer muteDays;

    /**
     * 处置备注。写入 audit_log，并随结果通知展示给被处置人（2026-10-28），
     * 别在这里写只有管理员该看的话。
     *
     * <p>2026-10-31 起，处罚类动作（ban/mute/takedown）必填：
     * 被处置人收到通知、事后申诉都依赖这一句话；reject/ignore 仍可留空。
     */
    @Size(max = 200, message = "处置备注最多 200 字")
    private String handleNote;
}