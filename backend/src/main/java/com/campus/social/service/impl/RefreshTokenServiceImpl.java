package com.campus.social.service.impl;

import com.campus.social.dto.RefreshDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.BanService;
import com.campus.social.service.RefreshTokenService;
import com.campus.social.service.support.AuthTokenSupport;
import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.campus.social.common.ResultCode.*;

/**
 * refresh 轮换与登出吊销。
 *
 * <p>先用 Lua CAS 原子滚动 jti（并发同 jti 只有一个成功），再校验账号状态：
 * 冻结与注销都会吊销指纹并拒绝；重用检测命中时全量吊销。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final UserMapper userMapper;
    private final BanService banService;
    private final AuthTokenSupport authTokenSupport;
    private final AuditService auditService;

    @Override
    public TokenPairVO refresh(RefreshDTO dto) {
        Claims claims = JwtUtil.parseRefresh(dto.getRefreshToken());
        if (claims == null) {
            throw new BusinessException(REFRESH_TOKEN_INVALID);
        }
        Long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new BusinessException(REFRESH_TOKEN_INVALID);
        }
        String oldJti = claims.getId();
        // 记住我保活：按旧 token 的 remember 选同 TTL 重签
        boolean remember = JwtUtil.parseRemember(claims);
        AuthTokenSupport.RefreshTicket ticket = authTokenSupport.newRefresh(userId, remember);

        int cas = authTokenSupport.rotate(
                userId, oldJti, ticket.jti(), authTokenSupport.ttlSeconds(remember));
        if (cas != 1) {
            // -1=无记录（自然过期或已登出）；0=旧 jti 不匹配（重用，已被全吊销）
            if (cas == -1) {
                throw new BusinessException(REFRESH_TOKEN_INVALID, "refresh token 已失效,请重新登录");
            }
            throw new BusinessException(REFRESH_TOKEN_REUSED, "refresh token 已被吊销,请重新登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            authTokenSupport.revoke(userId);
            throw new BusinessException(REFRESH_TOKEN_INVALID, "用户不存在");
        }
        // 冻结与注销都必须拒绝；原先只判 status==1，注销账号仍能一直 refresh 续命
        if (user.getStatus() == 1) {
            authTokenSupport.revoke(userId);
            throw banService.frozenException(user.getId());
        }
        if (user.getStatus() == 2) {
            authTokenSupport.revoke(userId);
            throw new BusinessException(USER_CANCELLED);
        }
        return authTokenSupport.pair(userId, user.getUsername(), ticket.token());
    }

    @Override
    public void logout(long userId) {
        authTokenSupport.revoke(userId);
        log.info("logout: uid={}", userId);
        auditService.record(userId, "logout", "user", userId, "退出登录", null);
    }
}
