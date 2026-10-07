package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参
 * username:邮箱或手机号(双账号:一个账号只能绑定一种)
 * verifyCode:邮箱/手机对应的 6 位验证码,发送方需先调 /auth/send-code
 * phone:可选,作为第二账号
 */
@Data
public class RegisterDTO {

    @NotBlank
    @Size(min = 5, max = 64)
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$|^1[3-9]\\d{9}$",
            message = "username 需为合法邮箱或 11 位手机号")
    private String username;

    @NotBlank
    @Size(min = 6, max = 64, message = "密码 6~64 位")
    private String password;

    /** 6 位验证码 */
    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String verifyCode;

    @Size(max = 32)
    private String studentNo;   // 可选

    @Size(max = 64)
    private String nickname;    // 可选
}
