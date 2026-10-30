package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.dto.BindEmailDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AccountEmailService;
import com.campus.social.service.AuditService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.campus.social.common.ResultCode.*;

/**
 * 安全凭证邮箱绑定实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountEmailServiceImpl implements AccountEmailService {

    private final UserMapper userMapper;
    private final VerifyCodeService verifyCodeService;
    private final AuditService auditService;

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

    /** 「我的资料」装配：绑定邮箱返回的是本人资料，允许带 role */
    private UserVO toVo(User user) {
        return UserVoAssembler.ofPrivate(user);
    }
}
