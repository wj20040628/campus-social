package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 自助注销账号：邮箱验证码二次确认。
 *
 * <p>为什么用验证码而不是密码：邮箱验证码登录建的账号没有密码
 * （{@code JwtUtil.isPasswordSet} 为 false），用密码会把这类账号挡在门外。
 * 邮箱是注册必填项，所有账号都有，是唯一覆盖全部账号的凭证。
 */
@Data
public class CancelAccountDTO {

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String code;
}
