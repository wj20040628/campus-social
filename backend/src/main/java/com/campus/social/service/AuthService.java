package com.campus.social.service;

import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.dto.RegisterDTO;
import com.campus.social.dto.RefreshDTO;
import com.campus.social.dto.SendCodeDTO;
import com.campus.social.dto.ForgotPasswordDTO;
import com.campus.social.dto.ResetPasswordDTO;
import com.campus.social.dto.ChangePasswordDTO;
import com.campus.social.dto.BindEmailDTO;
import com.campus.social.vo.TokenPairVO;
import com.campus.social.vo.UserVO;

public interface AuthService {

    /** 注册:邮箱或手机号 + 密码 + 验证码,返回双 token */
    TokenPairVO register(RegisterDTO dto);

    /** 密码登录 */
    TokenPairVO login(LoginDTO dto);

    /** 邮箱验证码登录(免密码) */
    TokenPairVO loginByCode(LoginByCodeDTO dto);

    /**
     * 刷新 token:
     * 1. 解析 refreshToken(jti 指纹)
     * 2. 查 Redis refresh:{userId} 是否还在
     * 3. 比对 jti:旧 token 已被用则 401(防重用)
     * 4. 删旧、签新
     */
    TokenPairVO refresh(RefreshDTO dto);

    /** 登出:删 Redis refresh */
    void logout(long userId);

    /** 当前用户 */
    UserVO me(long userId);

    /**
     * 更新资料：昵称 / 头像 / 学号（邮箱手机号不可改）。
     *
     * <p>不含院系与年级：这两项已字典化，只能经 {@code POST /user/school-profile}
     * 与 {@code POST /onboarding/complete} 按字典 id 写入。
     */
    UserVO updateProfile(long userId, String nickname, String avatarUrl, String studentNo);

    /**
     * 设置「是否允许陌生人给自己发消息」开关（AC-05 护栏偏好）。
     *
     * <p>与 {@link #updateProfile} 拆开：这是单一布尔偏好，单独端点语义更清晰，
     * 也避免和昵称/头像/学号那套字段混在同一个 DTO 里互相牵连。
     */
    UserVO setStrangerMsgEnabled(long userId, boolean enabled);

    /** 发送邮箱/手机验证码(注册/登录/绑定) */
    String sendCode(SendCodeDTO dto);

    /**
     * 找回密码(第一步):提交学号,向绑定的安全凭证邮箱发重置验证码。
     * 账号不存在静默成功(防学号枚举);未绑邮箱回 EMAIL_NOT_BOUND。
     */
    void forgotPassword(ForgotPasswordDTO dto);

    /**
     * 找回密码(第二步):学号 + 邮箱重置验证码 + 新密码。
     * 校验通过后改密并吊销其他设备的 refresh。
     */
    void resetPassword(ResetPasswordDTO dto);

    /**
     * 修改密码(需登录):校验原密码通过后更新为新密码。
     *
     * <p>与 {@link #resetPassword} 的区别:后者是无登录态、凭邮箱验证码找回;
     * 本方法是登录态、凭原密码主动改密。
     */
    void changePassword(long userId, ChangePasswordDTO dto);

    /**
     * 绑定 / 修改安全凭证邮箱(需登录):校验绑定验证码后写入 user.email(转小写,全局唯一)。
     */
    UserVO bindEmail(Long userId, BindEmailDTO dto);
}
