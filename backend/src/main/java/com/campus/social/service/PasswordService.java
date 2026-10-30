package com.campus.social.service;

import com.campus.social.dto.ChangePasswordDTO;
import com.campus.social.dto.ForgotPasswordDTO;
import com.campus.social.dto.ResetPasswordDTO;
import com.campus.social.dto.SetPasswordDTO;

public interface PasswordService {

    /** 找回密码第一步：向绑定邮箱发重置验证码；账号不存在时静默返回 */
    void forgotPassword(ForgotPasswordDTO dto);

    /** 找回密码第二步：邮箱验证码 + 新密码，成功后吊销全部 refresh */
    void resetPassword(ResetPasswordDTO dto);

    /** 登录态改密：校验原密码，成功后吊销全部 refresh */
    void changePassword(long userId, ChangePasswordDTO dto);

    /** 无密码账号发送首次设密验证码 */
    String sendSetPasswordCode(long userId);

    /** 无密码账号用邮箱验证码首次设密，成功后吊销全部 refresh */
    void setPassword(long userId, SetPasswordDTO dto);
}
