package com.campus.social.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 学号认证申诉（2026-10-19）。
 *
 * <p>提交方是被六层校验挡下、注册没成功的人 —— 没有账号、没有 token，
 * 所以走公开端点 {@code POST /auth/appeal-student-no}（AuthInterceptor 白名单已放行），
 * 不依赖登录态。复用 feedback 表、{@code user_id} 置 NULL，
 * 联系邮箱随 content 落库，管理端直接 mailto 联系提交人。
 *
 * <p>为什么必须收联系邮箱：没有账号就没有站内通知渠道，邮箱是管理员
 * 唯一能主动联系到提交人的路径。校验口径与前端的 StudentNoAppealDialog
 * 保持一致（学号 13/14 位数字 + 合法邮箱）。
 */
@Data
public class StudentNoAppealDTO {

    /** 申诉的学号：13~14 位纯数字，与后端 CampusVerifyServiceImpl 编码规则一致 */
    @NotBlank(message = "请填写学号")
    @Pattern(regexp = "^\\d{13,14}$", message = "学号格式不正确")
    private String studentNo;

    /** 联系邮箱（必填）：管理员据此联系提交人 */
    @NotBlank(message = "请填写联系邮箱")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 补充说明（选填）：最多 500 字 */
    @Size(max = 500, message = "补充说明最多 500 字")
    private String note;
}
