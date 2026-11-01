package com.campus.social.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 直接禁言用户（AdminUserController，2026-10-31）。
 *
 * <p>与举报处置的 {@code muteDays} 同一语义：账号仍可登录浏览，
 * 但禁言期内不能发帖/评论/私信/群消息。与 {@link UserBanDTO} 的差别是
 * 不提供永久档——永久对应封禁，禁言只是限期措施。
 */
@Data
public class UserMuteDTO {

    /** 1~365 天；上限与举报处置的 MAX_MUTE_DAYS 一致 */
    @NotNull(message = "禁言天数不能为空")
    @Min(value = 1, message = "禁言天数至少 1 天")
    @Max(value = 365, message = "禁言天数最多 365 天")
    private Integer muteDays;

    /** 必填：理由会同步给被处置人，并进操作日志 */
    @NotBlank(message = "禁言理由不能为空")
    @Size(max = 200, message = "禁言理由最多 200 字")
    private String reason;
}
