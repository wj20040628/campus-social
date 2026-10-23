package com.campus.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码（第一步）：提交「邮箱或学号」，后端向该账号绑定的安全凭证邮箱发重置验证码。
 *
 * <p><b>2026-10-21 从「只收学号」改成「学号或邮箱二选一」</b>：
 * 注册流程已改为邮箱必填、学号选填，若找回密码仍只认学号，
 * <b>注册时跳过学号的用户永远找不回密码</b> —— 他连一个可填进去的值都没有。
 * 另外记得邮箱、忘了学号的人也不该被挡在门外。
 *
 * <p>字段名沿用 {@code account} 而不是拆成两个可空字段：
 * 拆开会让「两个都空」与「两个都填」这两种非法输入都得单独判，
 * 而这里语义本来就是「一个能定位到你的标识」，一个字段更贴合。
 *
 * <p>仍在防学号/邮箱枚举：账号不存在时**静默成功**、不返回任何差异信息。
 */
@Data
public class ForgotPasswordDTO {

    /** 邮箱或学号。服务层按「含 @ 则当邮箱，否则当学号」分流 */
    @NotBlank(message = "请填写邮箱或学号")
    @Size(max = 128, message = "内容太长")
    private String account;
}
