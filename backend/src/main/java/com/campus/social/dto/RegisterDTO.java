package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参(账号体系重构 2026-10-17)
 *
 * <p>学号为主账号:username 一列内部统一存学号,故这里只收 studentNo。
 * 邮箱降为「选填安全凭证」,仅用于找回密码 / 绑定 / 已绑定用户的邮箱验证码登录,
 * 注册时<b>不校验</b>(不再发验证码),有值才写库并 UNIQUE。
 * 手机彻底取消。
 */
@Data
public class RegisterDTO {

    /** 学号:主账号,必填,13/14 位纯数字 */
    @NotBlank
    @Pattern(regexp = "^\\d{13,14}$", message = "学号需为 13~14 位数字")
    private String studentNo;

    @NotBlank
    @Size(min = 6, max = 64, message = "密码 6~64 位")
    private String password;

    /** 昵称:必填(原可空,重构后作为首屏展示名必须有) */
    @NotBlank
    @Size(max = 32, message = "昵称最长 32 字")
    private String nickname;

    /**
     * 安全凭证邮箱:选填。合法邮箱格式即可,注册时不发码、不校验所有权。
     * 存库前统一转小写。有值时全局唯一(uk_user_email)。
     */
    @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "邮箱格式不合法")
    private String email;

    // ---- 以下为「注册即认证」可选归属字段:学号非空时必填,学号为空时不传 ----
    /** 学校 id，MVP 单校恒为 1；不传按配置默认值处理 */
    private Long schoolId;

    /** 院系字典 id，学号非空时必填（取自学院下拉） */
    private Long deptId;

    /** 年级字典 id，学号非空时必填（取自年级下拉） */
    private Long gradeId;

    /** 专业字典 id，学号非空时必填（按学院 + 本专科过滤后的下拉） */
    private Long majorId;
}
