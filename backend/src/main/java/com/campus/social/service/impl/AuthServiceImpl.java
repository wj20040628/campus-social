package com.campus.social.service.impl;

import com.campus.social.dto.BindEmailDTO;
import com.campus.social.dto.ChangePasswordDTO;
import com.campus.social.dto.ForgotPasswordDTO;
import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.dto.RefreshDTO;
import com.campus.social.dto.RegisterDTO;
import com.campus.social.dto.ResetPasswordDTO;
import com.campus.social.dto.SendCodeDTO;
import com.campus.social.dto.SetPasswordDTO;
import com.campus.social.service.AccountEmailService;
import com.campus.social.service.AuthService;
import com.campus.social.service.LoginService;
import com.campus.social.service.PasswordService;
import com.campus.social.service.ProfileService;
import com.campus.social.service.RefreshTokenService;
import com.campus.social.service.RegisterService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.vo.TokenPairVO;
import com.campus.social.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号鉴权门面：实现 {@link AuthService} 的公开接口，把调用分发给各专项服务。
 *
 * <p>登录、注册、refresh 轮换、密码生命周期、资料维护与邮箱绑定分别由
 * {@link LoginService}、{@link RegisterService}、{@link RefreshTokenService}、
 * {@link PasswordService}、{@link ProfileService}、{@link AccountEmailService}
 * 承担（机制细节见各自的 javadoc）；本类只保留验证码入口转发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final VerifyCodeService verifyCodeService;
    /** 注册与验证码自动建号 */
    private final RegisterService registerService;
    /** 密码登录与验证码登录 */
    private final LoginService loginService;
    /** refresh 轮换与登出吊销 */
    private final RefreshTokenService refreshTokenService;
    /** 找回、重置、修改与首次设密 */
    private final PasswordService passwordService;
    /** 本人资料查询与维护 */
    private final ProfileService profileService;
    /** 安全凭证邮箱绑定 */
    private final AccountEmailService accountEmailService;

    @Override
    @Transactional
    public TokenPairVO register(RegisterDTO dto) {
        return registerService.register(dto);
    }

    @Override
    public TokenPairVO login(LoginDTO dto, String ip) {
        return loginService.login(dto, ip);
    }

    @Override
    @Transactional
    public TokenPairVO loginByCode(LoginByCodeDTO dto) {
        return loginService.loginByCode(dto);
    }

    @Override
    public String sendCode(SendCodeDTO dto) {
        return verifyCodeService.send(dto.getTarget(), dto.getType() == null ? "register" : dto.getType());
    }

    @Override
    public TokenPairVO refresh(RefreshDTO dto) {
        return refreshTokenService.refresh(dto);
    }

    @Override
    public void logout(long userId) {
        refreshTokenService.logout(userId);
    }

    @Override
    public void forgotPassword(ForgotPasswordDTO dto) {
        passwordService.forgotPassword(dto);
    }

    @Override
    public void resetPassword(ResetPasswordDTO dto) {
        passwordService.resetPassword(dto);
    }

    @Override
    @Transactional
    public void changePassword(long userId, ChangePasswordDTO dto) {
        passwordService.changePassword(userId, dto);
    }

    @Override
    public String sendSetPasswordCode(long userId) {
        return passwordService.sendSetPasswordCode(userId);
    }

    @Override
    @Transactional
    public void setPassword(long userId, SetPasswordDTO dto) {
        passwordService.setPassword(userId, dto);
    }

    @Override
    @Transactional
    public UserVO bindEmail(Long userId, BindEmailDTO dto) {
        return accountEmailService.bindEmail(userId, dto);
    }

    @Override
    public UserVO me(long userId) {
        return profileService.me(userId);
    }

    @Override
    @Transactional
    public UserVO updateProfile(long userId, String nickname, String avatarUrl, String studentNo, String bio) {
        return profileService.updateProfile(userId, nickname, avatarUrl, studentNo, bio);
    }

    @Override
    @Transactional
    public UserVO updateCover(long userId, String coverUrl) {
        return profileService.updateCover(userId, coverUrl);
    }

    @Override
    @Transactional
    public UserVO setStrangerMsgEnabled(long userId, boolean enabled) {
        return profileService.setStrangerMsgEnabled(userId, enabled);
    }
}
