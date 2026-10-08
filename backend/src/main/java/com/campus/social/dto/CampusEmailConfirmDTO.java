package com.campus.social.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 确认校园邮箱验证码（F2）。
 *
 * <p>验证码是 6 位数字，用 {@code @Pattern} 而不是 {@code @Size}：
 * 长度对但内容是 {@code "abcdef"} 的码不可能通过校验，
 * 早失败比让用户白等一次 Redis 往返更友好。
 */
@Data
public class CampusEmailConfirmDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String code;
}