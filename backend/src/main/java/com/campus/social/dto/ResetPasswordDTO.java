package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码(第二步):学号 + 邮箱收到的重置验证码 + 新密码。
 * 校验通过后更新密码并吊销该账号其他设备的 refresh(踢下线),防旧 token 继续用。
 */
@Data
public class ResetPasswordDTO {

    @NotBlank
    @Size(max = 64)
    private String studentNo;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String code;

    @NotBlank
    @Size(min = 6, max = 64, message = "新密码 6~64 位")
    private String newPassword;
}
