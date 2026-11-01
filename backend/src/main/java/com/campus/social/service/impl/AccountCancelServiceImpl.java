package com.campus.social.service.impl;

import com.campus.social.assembler.UserVoAssembler;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AccountCancelService;
import com.campus.social.service.AuditService;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.service.support.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 账号自助注销实现。
 *
 * <p>注销只做「去标识化 + 断登录」，不删用户行：举报处置、审计日志里的
 * {@code user_id} 仍需指向同一条记录，物理删行会让历史处置变成孤儿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountCancelServiceImpl implements AccountCancelService {

    /** 与 AuthInterceptor.STATUS_KEY_PREFIX 保持一致；注销后必须立即失效，否则旧 token 还能用 60 秒 */
    private static final String STATUS_KEY_PREFIX = "user:status:";

    private final UserMapper userMapper;
    private final VerifyCodeService verifyCodeService;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final StringRedisTemplate redis;
    /** 注销后清退群/圈成员关系与活动报名；不处理帖子/评论留痕 */
    private final AccountCancelCascadeService cascade;

    @Override
    public String sendCode(long userId) {
        User user = requireCancellable(userId);
        String email = requireEmail(user);
        verifyCodeService.send(email, "cancel");
        // 只回打码后的邮箱，避免把完整地址回显给已经拿到 token 的会话
        return UserVoAssembler.maskUsername(email);
    }

    @Override
    @Transactional
    public void cancel(long userId, String code) {
        User user = requireCancellable(userId);
        verifyCodeService.verify(requireEmail(user), code, "cancel");
        if (userMapper.cancelAccount(userId) == 0) {
            throw new BusinessException(UNPROCESSABLE, "账号状态已变化，请刷新后重试");
        }
        // user.status 已置 2，级联里的「正常成员」筛选会自动排除本人
        AccountCancelCascadeService.Result cascadeResult = cascade.purge(userId);
        tokenService.revoke(userId);
        redis.delete(STATUS_KEY_PREFIX + userId);
        auditService.record(userId, "account_cancel", "user", userId,
                "用户自助注销，昵称/邮箱/学号/头像/简介/校园信息已清空；级联清理："
                        + cascadeResult.summary(), null);
        log.info("account cancelled: uid={}, {}", userId, cascadeResult.summary());
    }

    private User requireCancellable(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(NOT_FOUND, "用户不存在");
        }
        if (Integer.valueOf(2).equals(user.getStatus())) {
            throw new BusinessException(UNPROCESSABLE, "账号已注销");
        }
        if (Integer.valueOf(1).equals(user.getRole())) {
            throw new BusinessException(UNPROCESSABLE, "管理员账号不能自助注销");
        }
        return user;
    }

    private String requireEmail(User user) {
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            throw new BusinessException(UNPROCESSABLE, "该账号未绑定邮箱，无法自助注销，请联系管理员");
        }
        return email;
    }
}
