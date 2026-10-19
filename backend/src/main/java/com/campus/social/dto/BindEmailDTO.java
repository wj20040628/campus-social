package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 绑定 / 修改安全凭证邮箱:邮箱 + 该邮箱收到的绑定验证码。
 * 验证码走 send-code type=bind 发往目标邮箱;校验通过后写入 user.email(转小写),
 * 全局唯一(uk_user_email),与其他账号冲突则 EMAIL_ALREADY_BOUND。
 */
@Data
public class BindEmailDTO {

    @NotBlank
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "需为合法邮箱")
    private String email;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String code;
}
