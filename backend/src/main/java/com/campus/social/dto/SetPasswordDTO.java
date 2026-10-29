package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 无密码账号首次设置密码(需登录):邮箱验证码 + 新密码。
 *
 * <p>与 {@link ChangePasswordDTO} 的区别:那边凭原密码,本 DTO 凭账号绑定邮箱收到的
 * {@code setpwd} 验证码;只对 {@code password_hash} 仍是哨兵值的账号开放。
 */
@Data
public class SetPasswordDTO {

    @NotBlank(message = "请输入验证码")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String code;

    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 64, message = "新密码 6~64 位")
    private String newPassword;
}
