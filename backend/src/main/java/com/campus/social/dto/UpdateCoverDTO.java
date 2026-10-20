package com.campus.social.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设置 / 清除个人主页封面（{@code PUT /user/cover}）。
 *
 * <p><b>为什么不并进 {@link UpdateProfileDTO}</b>：那个 DTO 里所有字段都是
 * 「null = 本次不改」，而封面必须能表达第三种意图——<b>清空回默认</b>。
 * 并进去就只能靠"传空串"区分，与 {@code avatarUrl}（空串会被当成一次普通赋值）
 * 的口径打架，下一个改这块的人必然写错。
 *
 * <p>本 DTO 语义相反且明确：<b>传什么就是什么</b>，{@code null} / 空白
 * 一律表示「恢复默认封面」。
 */
@Data
public class UpdateCoverDTO {

    /**
     * 封面图地址（相对路径 {@code /uploads/...}）。
     *
     * <p>与 {@code user.cover_url} 列宽一致（512）。
     * {@code null} 或空白 = 恢复默认渐变封面，不是「不改」。
     */
    @Size(max = 512, message = "封面地址过长")
    private String coverUrl;
}
