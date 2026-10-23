package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码（第二步）：邮箱或学号 + 邮箱收到的重置验证码 + 新密码。
 * 校验通过后更新密码并吊销该账号其他设备的 refresh（踢下线），防旧 token 继续用。
 *
 * <p>2026-10-21：{@code studentNo} 改为 {@code account}，与第一步保持一致 ——
 * 第一步用邮箱查到账号，第二步却要求再输学号，等于把刚绕过的那道坎又立回来。
 */
@Data
public class ResetPasswordDTO {

    /** 邮箱或学号，与第一步填的必须是同一个值 */
    @NotBlank(message = "请填写邮箱或学号")
    @Size(max = 128, message = "内容太长")
    private String account;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String code;

    @NotBlank
    @Size(min = 6, max = 64, message = "新密码 6~64 位")
    private String newPassword;
}
