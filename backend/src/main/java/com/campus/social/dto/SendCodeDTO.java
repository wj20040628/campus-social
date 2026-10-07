package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发验证码入参
 * target:邮箱或手机号(后端按其类型决定走邮件网关/短信网关)
 */
@Data
public class SendCodeDTO {

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$|^1[3-9]\\d{9}$",
            message = "target 需为合法邮箱或 11 位手机号")
    private String target;

    /** 用途:register(注册) / login(登录) / bind(绑定第二账号) */
    @Pattern(regexp = "^(register|login|bind)$", message = "type 必须是 register/login/bind")
    private String type = "register";
}
