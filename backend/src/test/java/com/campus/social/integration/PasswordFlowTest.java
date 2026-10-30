package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.ForgotPasswordDTO;
import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.dto.RefreshDTO;
import com.campus.social.dto.ResetPasswordDTO;
import com.campus.social.dto.SetPasswordDTO;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.AuthService;
import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 密码流程特征测试：拆分 PasswordService 前锁定重置/设密的哈希更新与 refresh 吊销。
 * 验证码直接写 Redis，不依赖真实发信。
 */
@SpringBootTest
@ActiveProfiles("local")
class PasswordFlowTest {

    private static final String IP = "203.0.113.89";
    private static final String CODE = "654321";

    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long userId;
    private String email;

    @AfterEach
    void cleanup() {
        if (userId != null) {
            redis.delete("refresh:" + userId);
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user WHERE id = ?", userId);
        }
        if (email != null) {
            redis.delete("code:reset:" + email);
            redis.delete("code:setpwd:" + email);
            redis.delete("code:login:" + email);
            redis.delete("code:fail:reset:" + email);
            redis.delete("code:fail:setpwd:" + email);
            redis.delete("login:fail:u:" + email);
            redis.delete("login:fail:ip:" + IP);
        }
    }

    private void seedUser(String email, String passwordHash) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, email) VALUES (?,?,?,0,0,1,1,1,0,?)",
                email, passwordHash, "密码测试", email);
        userId = jdbc.queryForObject("SELECT id FROM user WHERE email = ?", Long.class, email);
    }

    @Test
    void resetPasswordByEmailCodeChangesHashAndRevokesRefresh() {
        String nano = String.valueOf(System.nanoTime());
        email = "pw-reset-" + nano + "@campus.test";
        String oldPw = "Old-" + nano + "!";
        seedUser(email, JwtUtil.hashPassword(oldPw));

        LoginDTO login = new LoginDTO();
        login.setUsername(email);
        login.setPassword(oldPw);
        TokenPairVO pair = authService.login(login, IP);
        assertNotNull(redis.opsForValue().get("refresh:" + userId));

        redis.opsForValue().set("code:reset:" + email, CODE, Duration.ofMinutes(5));
        ResetPasswordDTO dto = new ResetPasswordDTO();
        dto.setAccount(email);
        dto.setCode(CODE);
        String newPw = "New-" + nano + "!";
        dto.setNewPassword(newPw);
        authService.resetPassword(dto);

        String hash = jdbc.queryForObject("SELECT password_hash FROM user WHERE id = ?", String.class, userId);
        assertTrue(JwtUtil.verifyPassword(newPw, hash));
        assertFalse(JwtUtil.verifyPassword(oldPw, hash));
        assertNull(redis.opsForValue().get("refresh:" + userId), "重置密码必须吊销 refresh");

        RefreshDTO refresh = new RefreshDTO();
        refresh.setRefreshToken(pair.getRefreshToken());
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh(refresh));
        assertEquals(ResultCode.REFRESH_TOKEN_INVALID.getCode(), ex.getCode());
    }

    @Test
    void setPasswordByEmailCodeSetsHashAndRevokesExistingRefresh() {
        String nano = String.valueOf(System.nanoTime());
        email = "pw-set-" + nano + "@campus.test";
        seedUser(email, JwtUtil.PASSWORD_UNSET);

        redis.opsForValue().set("code:login:" + email, "123456", Duration.ofMinutes(5));
        LoginByCodeDTO login = new LoginByCodeDTO();
        login.setEmail(email);
        login.setCode("123456");
        authService.loginByCode(login);
        assertNotNull(redis.opsForValue().get("refresh:" + userId));

        redis.opsForValue().set("code:setpwd:" + email, CODE, Duration.ofMinutes(5));
        String newPw = "Set-" + nano + "!";
        SetPasswordDTO dto = new SetPasswordDTO();
        dto.setCode(CODE);
        dto.setNewPassword(newPw);
        authService.setPassword(userId, dto);

        String hash = jdbc.queryForObject("SELECT password_hash FROM user WHERE id = ?", String.class, userId);
        assertTrue(JwtUtil.verifyPassword(newPw, hash));
        assertNull(redis.opsForValue().get("refresh:" + userId), "首次设密必须吊销旧 refresh");
    }

    @Test
    void forgotPasswordUnknownAccountReturnsSilently() {
        ForgotPasswordDTO dto = new ForgotPasswordDTO();
        dto.setAccount("nobody-" + System.nanoTime() + "@campus.test");
        assertDoesNotThrow(() -> authService.forgotPassword(dto));
    }
}
