package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginDTO {

    /**
     * 登录标识:学号(主账号)或已绑定的邮箱。后端走 {@code username OR email} 双匹配。
     */
    @NotBlank
    @Size(max = 64)
    private String username;

    @NotBlank
    private String password;

    /**
     * 记住我:勾选 → refresh 7 天;不勾 → refresh 3 天。
     * 默认 false(与登录页「不勾保持 3 天」对齐)。
     */
    private Boolean rememberMe = false;
}
