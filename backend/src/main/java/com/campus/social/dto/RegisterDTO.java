package com.campus.social.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参（2026-10-21 重构：注册轻、认证后置）。
 *
 * <p><b>为什么改</b>：改版前 studentNo 是 {@code @NotBlank}，学号成了必填项 ——
 * 想先进来看看的人被挡在门外，必须先填对 13~14 位学号 + 学院 + 年级 + 专业；
 * 而注册成功又直接置 verify_status=1，于是「未认证」这个状态在现实中根本不存在，
 * AC-11「未认证不能搭话」永远不会触发。
 *
 * <p><b>现在的口径</b>：
 * <ul>
 *   <li>邮箱必填且<b>验证所有权</b>（6 位验证码）—— 它是登录主键之一，不能是随手填的字符串</li>
 *   <li>学号选填 —— 填了就走六层校验、立即认证；不填则注册为未认证，之后在 /onboarding 补</li>
 *   <li>归属四字段（学校/学院/本专科/专业/年级）只在填了学号时必填，由服务层校验</li>
 * </ul>
 *
 * <p><b>为什么邮箱要做主键</b>：学号是 13~14 位数字（记不住、填错改不了），
 * 且 SPEC AC-12 明确要求对外不得显示学号 —— 我们却把它当成了 username。
 * 邮箱唯一、可改、记得住、能找回密码，是更合适的登录凭据。
 * 学号此后只存在于 {@code user.student_no} 一列，不再对外暴露。
 *
 * <p>手机号彻底取消。
 */
@Data
public class RegisterDTO {

    /**
     * 邮箱：必填主键之一，必须通过验证码验证所有权。
     *
     * <p>存库前统一转小写，全局唯一（uk_user_email）。
     * 用 {@code @Email} + {@code @NotBlank} 双重约束：前者管格式，后者管非空 ——
     * 只写 {@code @Email} 时空串能通过（Hibernate Validator 认为空值是合法邮箱）。
     */
    @NotBlank(message = "请填写邮箱")
    @Email(message = "邮箱格式不合法")
    @Size(max = 128, message = "邮箱最长 128 位")
    private String email;

    /**
     * 邮箱验证码（6 位）。由 {@code POST /auth/send-code} 发到上面那个邮箱。
     *
     * <p>服务层用 {@code VerifyCodeService.verify(email, code, "register")} 校验并消费。
     * 放在 DTO 里而不是另开端点：注册是一个整体动作，验证码只是它的一个输入，
     * 分成两步会让「码对了但注册失败」留下一个已消费的码。
     */
    @NotBlank(message = "请填写邮箱验证码")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String emailCode;

    @NotBlank
    @Size(min = 6, max = 64, message = "密码 6~64 位")
    private String password;

    /** 昵称：必填。站内展示名，可后改，不做主键（会重复、会变） */
    @NotBlank
    @Size(max = 32, message = "昵称最长 32 字")
    private String nickname;

    /**
     * 学号：<b>选填</b>。填了就走六层校验、立即认证；不填则注册为未认证。
     *
     * <p>去掉 {@code @NotBlank} 只留 Pattern：空值应当合法（那是「先注册、后认证」的路径），
     * 但一旦填了就必须是 13~14 位纯数字，否则连六层校验都进不去。
     */
    @Pattern(regexp = "^$|^\\d{13,14}$", message = "学号需为 13~14 位数字")
    private String studentNo;

    // ---- 以下归属字段仅在 studentNo 非空时必填，由 AuthServiceImpl 显式校验 ----
    /** 学校 id，MVP 单校恒为 1；不传按配置默认值处理 */
    private Long schoolId;

    /** 院系字典 id（学院下拉） */
    private Long deptId;

    /**
     * 本专科：1 本科 / 2 专科。
     *
     * <p><b>注意它不写 user 表</b> —— 只用于筛选专业下拉、以及六层校验第 4 层
     * （学号里的层次标记要与它比对）。专业 id 本身已隐含层次，
     * 所以落库的是 majorId，level 只是选择过程中的中间值。这里收它是因为
     * 前端选专业时必须有它才能拉对下拉；后端不校验它也可以，但校验更严格。
     */
    private Integer level;

    /** 年级字典 id（年级下拉） */
    private Long gradeId;

    /** 专业字典 id（按学院 + 本专科过滤后的下拉） */
    private Long majorId;
}
