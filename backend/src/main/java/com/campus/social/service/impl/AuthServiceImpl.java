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
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.service.LoginService;
import com.campus.social.service.PasswordService;
import com.campus.social.service.ProfileService;
import com.campus.social.service.RefreshTokenService;
import com.campus.social.service.RegisterService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.vo.TokenPairVO;
import com.campus.social.vo.UserVO;
import com.campus.social.assembler.UserVoAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.campus.social.common.ResultCode.*;

/**
 * 账号鉴权门面：实现 {@link AuthService} 的公开接口，把调用分发给各专项服务。
 *
 * <p>登录、注册、refresh 轮换与密码生命周期分别由 {@link LoginService}、
 * {@link RegisterService}、{@link RefreshTokenService}、{@link PasswordService}
 * 承担（机制细节见各自的 javadoc）；资料维护相关端点暂留本类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final VerifyCodeService verifyCodeService;
    private final com.campus.social.service.AuditService auditService;
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

    /**
     * 绑定 / 修改安全凭证邮箱(需登录)。
     *
     * <p>流程:先 {@code POST /auth/send-code} type=bind 把码发到目标邮箱,
     * 再带码调用本端点。校验通过后写 {@code user.email}(转小写),全局唯一
     * (uk_user_email)——与其他账号冲突回 {@code EMAIL_ALREADY_BOUND}。
     */
    @Override
    @Transactional
    public UserVO bindEmail(Long userId, BindEmailDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        // 先校验绑定验证码(发往目标邮箱,type=bind,一次性消费)
        verifyCodeService.verify(email, dto.getCode(), "bind");
        // 唯一性:其他账号已绑此邮箱 → 拒绝(自己换绑自己的邮箱不算冲突)
        User existing = userMapper.selectByEmail(email);
        if (existing != null && !existing.getId().equals(userId)) {
            throw new BusinessException(EMAIL_ALREADY_BOUND, "该邮箱已被其他账号绑定");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(USER_CANCELLED, "用户不存在");
        }
        user.setEmail(email);
        userMapper.updateById(user);
        auditService.record(userId, "bind_email", "user", userId, "绑定邮箱=" + email, null);
        log.info("bindEmail: uid={}, email={}", userId, UserVoAssembler.maskUsername(email));
        return toVo(user);
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

    // ----------------------------------------------------------------

    /**
     * 「我的资料」装配：全项目唯一允许带 role 的路径。
     *
     * <p>本类只剩 {@link #bindEmail} 用它返回本人资料；资料查询与更新走
     * {@code ProfileService}，那边同样只对本人响应使用 {@code ofPrivate}。
     * 其他任何位置（Feed、评论区、私聊、通知、他人主页）都必须走
     * {@code UserVoAssembler.of}。
     */
    private UserVO toVo(User user) {
        return UserVoAssembler.ofPrivate(user);
    }
}
