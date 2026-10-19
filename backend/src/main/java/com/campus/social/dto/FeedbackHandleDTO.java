package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 意见反馈处置（2026-10-18 管理端）。
 *
 * <p>动作只有两个：adopt 采纳 / ignore 忽略。刻意不做「已修复」——
 * 产品改没改只有开发者知道，让管理员去勾一个自己无法证实的状态，
 * 只会让这个字段慢慢变成人人随手点的 noise。
 *
 * <p>{@code handleNote} 必填：反馈是人对人的沟通，管理员什么都不写就点采纳，
 * 等于让提交者收到一句没有信息量的「收到」。
 */
@Data
public class FeedbackHandleDTO {

    /** adopt 采纳 / ignore 忽略 */
    @NotBlank(message = "处置动作不能为空")
    private String action;

    @NotBlank(message = "请写一句处理说明")
    @Size(max = 200, message = "处理说明最多 200 字")
    private String handleNote;
}
