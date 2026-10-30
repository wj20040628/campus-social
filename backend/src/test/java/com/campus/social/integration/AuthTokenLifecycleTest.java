package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.ChangePasswordDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.dto.RefreshDTO;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.AuthService;
import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token 生命周期的特征测试：拆分 AuthServiceImpl 前锁定现有行为。
 * 覆盖签发 TTL 与 JwtUtil 对齐、记住我、refresh 滚动、登出与改密吊销。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AuthTokenLifecycleTest {

    private static final String IP = "203.0.113.88";
    private static final String SUFFIX = String.valueOf(System.nanoTime() % 1000000);

    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long userId;
    private String username;
    private String password;

    @BeforeEach
    void seed() {
        username = "token-" + SUFFIX + "@campus.test";
        password = "Pw-" + SUFFIX + "!";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, email) VALUES (?,?,?,0,0,1,1,1,0,?)",
                username, JwtUtil.hashPassword(password), username, username);
        userId = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        if (userId != null) {
            redis.delete("refresh:" + userId);
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user WHERE id = ?", userId);
        }
        redis.delete("login:fail:u:" + username);
        redis.delete("login:fail:ip:" + IP);
    }

    private LoginDTO loginDto(boolean remember) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setRememberMe(remember);
        return dto;
    }

    private long fingerprintTtlSeconds() {
        Long ttl = redis.getExpire("refresh:" + userId, TimeUnit.SECONDS);
        assertNotNull(ttl, "refresh 指纹必须存在");
        return ttl;
    }

    @Test
    void loginIssuesPairAndFingerprintTtlMatchesJwt() {
        long before = System.currentTimeMillis() / 1000;
        TokenPairVO pair = authService.login(loginDto(false), IP);
        long after = System.currentTimeMillis() / 1000;

        assertNotNull(pair.getAccessToken());
        assertNotNull(pair.getRefreshToken());
        assertTrue(pair.getAccessExpireAt() >= before + 900 && pair.getAccessExpireAt() <= after + 900,
                "accessExpireAt 应为 15 分钟后");

        Claims claims = JwtUtil.parseRefresh(pair.getRefreshToken());
        assertNotNull(claims);
        assertEquals(String.valueOf(userId), claims.getSubject());
        assertFalse(JwtUtil.parseRemember(claims));
        assertEquals(claims.getId(), redis.opsForValue().get("refresh:" + userId));

        long expected = JwtUtil.getRefreshTtlMillis() / 1000;
        assertTrue(Math.abs(fingerprintTtlSeconds() - expected) <= 5,
                "Redis 指纹 TTL 必须与 JWT refresh TTL 对齐");
    }

    @Test
    void rememberMeExtendsFingerprintToRememberTtl() {
        TokenPairVO pair = authService.login(loginDto(true), IP);

        Claims claims = JwtUtil.parseRefresh(pair.getRefreshToken());
        assertNotNull(claims);
        assertTrue(JwtUtil.parseRemember(claims));

        long expected = JwtUtil.getRememberTtlMillis() / 1000;
        assertTrue(Math.abs(fingerprintTtlSeconds() - expected) <= 5,
                "勾选记住我时 Redis 指纹必须使用 7 天 TTL");
    }

    @Test
    void refreshRotatesJtiAndKeepsRememberFlag() {
        TokenPairVO first = authService.login(loginDto(true), IP);
        String oldJti = JwtUtil.parseRefresh(first.getRefreshToken()).getId();

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken(first.getRefreshToken());
        TokenPairVO second = authService.refresh(dto);

        assertNotEquals(first.getRefreshToken(), second.getRefreshToken());
        Claims newClaims = JwtUtil.parseRefresh(second.getRefreshToken());
        assertNotNull(newClaims);
        assertNotEquals(oldJti, newClaims.getId());
        assertTrue(JwtUtil.parseRemember(newClaims));
        assertEquals(newClaims.getId(), redis.opsForValue().get("refresh:" + userId));

        long expected = JwtUtil.getRememberTtlMillis() / 1000;
        assertTrue(Math.abs(fingerprintTtlSeconds() - expected) <= 5,
                "刷新后仍应按记住我保持 7 天");
    }

    @Test
    void reuseOfRotatedRefreshIsRejected() {
        TokenPairVO first = authService.login(loginDto(false), IP);
        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken(first.getRefreshToken());
        authService.refresh(dto);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh(dto));
        assertEquals(ResultCode.REFRESH_TOKEN_REUSED.getCode(), ex.getCode());
    }

    @Test
    void logoutRevokesRefreshImmediately() {
        TokenPairVO pair = authService.login(loginDto(false), IP);
        authService.logout(userId);

        assertNull(redis.opsForValue().get("refresh:" + userId));

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken(pair.getRefreshToken());
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh(dto));
        assertEquals(ResultCode.REFRESH_TOKEN_INVALID.getCode(), ex.getCode());
    }

    @Test
    void changePasswordRevokesAllRefreshTokens() {
        TokenPairVO pair = authService.login(loginDto(false), IP);

        ChangePasswordDTO dto = new ChangePasswordDTO();
        dto.setOldPassword(password);
        dto.setNewPassword("New-" + SUFFIX + "!");
        authService.changePassword(userId, dto);

        assertNull(redis.opsForValue().get("refresh:" + userId));

        RefreshDTO refresh = new RefreshDTO();
        refresh.setRefreshToken(pair.getRefreshToken());
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh(refresh));
        assertEquals(ResultCode.REFRESH_TOKEN_INVALID.getCode(), ex.getCode());
    }
}
