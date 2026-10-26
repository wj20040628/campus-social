package com.campus.social.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 封禁申诉（2026-10-26）。
 *
 * <p>提交方是被封禁、登录不进来的用户 —— 没有可用 token，走公开端点
 * {@code POST /auth/appeal-ban}（拦截器白名单放行）。复用 feedback 表，
 * {@code user_id} 置 NULL，联系邮箱随 content 落库，管理员处置后按邮箱回信。
 *
 * <p>与学号认证申诉不同：封禁用户大多有注册邮箱但不一定有学号（注册时学号选填），
 * 所以这里<b>邮箱必填、学号选填</b>。没有邮箱就没有任何触达渠道。
 */
@Data
public class BanAppealDTO {

    /** 注册邮箱（必填）：管理员据此核对账号并回信 */
    @NotBlank(message = "请填写注册邮箱")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 学号（选填）：填了能帮管理员更快定位账号；空串视为没填 */
    @Pattern(regexp = "^$|^\\d{13,14}$", message = "学号格式不正确")
    private String studentNo;

    /** 补充说明（选填）：最多 500 字 */
    @Size(max = 500, message = "补充说明最多 500 字")
    private String note;
}
