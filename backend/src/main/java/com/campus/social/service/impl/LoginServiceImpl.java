package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.BanService;
import com.campus.social.service.LoginService;
import com.campus.social.service.RegisterService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.service.support.TokenService;
import com.campus.social.service.support.LoginLockService;
import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.campus.social.common.ResultCode.*;

/**
 * 登录：密码登录与邮箱验证码登录。
 *
 * <p>密码登录三路 OR 匹配 username / email / student_no；失败锁定由
 * {@link LoginLockService} 负责，账号不存在时只计 IP 维度。验证码登录遇到
 * 未注册邮箱时复用 {@link RegisterService#createEmailAccount} 自动建号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {

    private final UserMapper userMapper;
    private final AuditService auditService;
    private final BanService banService;
    private final RegisterService registerService;
    private final VerifyCodeService verifyCodeService;
    private final LoginLockService loginLockService;
    private final TokenService tokenService;

    @Override
    public TokenPairVO login(LoginDTO dto, String ip) {
        String id = dto.getUsername() == null ? "" : dto.getUsername().trim().toLowerCase();
        loginLockService.checkAccount(id);
        loginLockService.checkIp(ip);
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, id)
                        .or()
                        .eq(User::getEmail, id)
                        .or()
                        .eq(User::getStudentNo, id)
                        // 逻辑删除的行不该能登录：手写 Wrapper 需显式排除
                        .eq(User::getIsDeleted, 0));
        if (user == null) {
            // 账号不存在只计 IP 维度，避免不存在的账号名被用来误锁真实用户
            auditService.record(0L, "login_fail", null, null, "identifier=" + id + ", 账号不存在", null);
            loginLockService.recordIpFailure(ip);
            throw new BusinessException(WRONG_PASSWORD);
        }
        if (user.getStatus() == 1) {
            auditService.record(user.getId(), "login_fail", null, null, "identifier=" + id + ", 账号已冻结", null);
            throw banService.frozenException(user.getId());
        }
        if (user.getStatus() == 2) {
            auditService.record(user.getId(), "login_fail", null, null, "identifier=" + id + ", 账号已注销", null);
            throw new BusinessException(USER_CANCELLED);
        }
        // 验证码自动建号没有密码（哨兵值），不能喂给 BCrypt，也不计密码错误
        if (!JwtUtil.isPasswordSet(user.getPasswordHash())) {
            auditService.record(user.getId(), "login_fail", null, null,
                    "identifier=" + id + ", 账号尚未设置密码", null);
            throw new BusinessException(PASSWORD_NOT_SET);
        }
        if (!JwtUtil.verifyPassword(dto.getPassword(), user.getPasswordHash())) {
            auditService.record(user.getId(), "login_fail", null, null, "identifier=" + id + ", 密码错误", null);
            loginLockService.recordAccountFailure(id);
            loginLockService.recordIpFailure(ip);
            throw new BusinessException(WRONG_PASSWORD);
        }
        loginLockService.clearAccount(id);
        boolean remember = Boolean.TRUE.equals(dto.getRememberMe());
        auditService.record(user.getId(), "login", "user", user.getId(), "identifier=" + id, null);
        return tokenService.issue(user.getId(), user.getUsername(), remember);
    }

    @Override
    @Transactional
    public TokenPairVO loginByCode(LoginByCodeDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        if (email.length() > 64) {
            throw new BusinessException(BAD_REQUEST, "邮箱过长");
        }
        // 校验邮箱验证码（登录用途，一次性消费）
        verifyCodeService.verify(email, dto.getCode(), "login");

        User user = userMapper.selectByEmail(email);
        if (user == null) {
            // 验证码登录兼做注册：未注册邮箱直接建号并登录
            user = registerService.createEmailAccount(email);
        }
        if (user.getStatus() == 1) {
            throw banService.frozenException(user.getId());
        }
        if (user.getStatus() == 2) {
            throw new BusinessException(USER_CANCELLED);
        }
        log.info("loginByCode: email={}", UserVoAssembler.maskUsername(email));
        return tokenService.issue(user.getId(), user.getUsername(), false);
    }
}
