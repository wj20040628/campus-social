package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码(需登录):校验原密码通过后改成新密码。
 *
 * <p>与 {@link ResetPasswordDTO} 的区别:找回密码走「邮箱验证码」证明身份,
 * 本 DTO 走「原密码」证明身份,用于已登录用户在账号与安全里主动改密。
 */
@Data
public class ChangePasswordDTO {

    @NotBlank(message = "请输入原密码")
    @Size(max = 64)
    private String oldPassword;

    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 64, message = "新密码 6~64 位")
    private String newPassword;
}
