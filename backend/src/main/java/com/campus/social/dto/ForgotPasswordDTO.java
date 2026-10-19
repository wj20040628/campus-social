package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 找回密码(第一步):提交学号,后端向该账号绑定的安全凭证邮箱发重置验证码。
 * 未绑定邮箱的账号直接拒绝(EMAIL_NOT_BOUND),不泄露账号是否存在以外的信息。
 */
@Data
public class ForgotPasswordDTO {

    @NotBlank
    @jakarta.validation.constraints.Size(max = 64)
    private String studentNo;
}
