package com.campus.social.integration;

import com.campus.social.dto.LoginByCodeDTO;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证码登录特征测试：拆分 LoginService 前锁定「未注册邮箱自动建号」与「老账号直接登录」。
 * 验证码直接写 Redis（code:login:{email}），不依赖真实发信。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class LoginCodeFlowTest {

    private static final String CODE = "123456";

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
            redis.delete("code:login:" + email);
            redis.delete("code:fail:login:" + email);
        }
    }

    private LoginByCodeDTO dto(String email) {
        LoginByCodeDTO dto = new LoginByCodeDTO();
        dto.setEmail(email);
        dto.setCode(CODE);
        return dto;
    }

    @Test
    void unknownEmailCreatesPasswordlessAccountAndIssuesTokens() {
        email = "code-" + System.nanoTime() + "@campus.test";
        redis.opsForValue().set("code:login:" + email, CODE, Duration.ofMinutes(5));

        TokenPairVO pair = authService.loginByCode(dto(email));

        assertNotNull(pair.getAccessToken());
        assertNotNull(pair.getRefreshToken());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, username, password_hash, verify_status FROM user WHERE email = ?", email);
        userId = ((Number) row.get("id")).longValue();
        assertEquals(email, row.get("username"));
        assertEquals(JwtUtil.PASSWORD_UNSET, row.get("password_hash"), "自动建号不应设置密码");
        assertEquals(0, ((Number) row.get("verify_status")).intValue());
        assertNotNull(redis.opsForValue().get("refresh:" + userId));
        assertNull(redis.opsForValue().get("code:login:" + email), "登录成功后验证码必须被消费");
    }

    @Test
    void existingAccountLogsInWithoutCreatingDuplicate() {
        email = "code-existing-" + System.nanoTime() + "@campus.test";
        String hash = JwtUtil.hashPassword("Pw-" + System.nanoTime());
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, email) VALUES (?,?,?,0,0,1,1,1,0,?)",
                email, hash, "验证码登录", email);
        userId = jdbc.queryForObject("SELECT id FROM user WHERE email = ?", Long.class, email);
        redis.opsForValue().set("code:login:" + email, CODE, Duration.ofMinutes(5));

        TokenPairVO pair = authService.loginByCode(dto(email));

        assertNotNull(pair.getAccessToken());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE email = ?", Integer.class, email);
        assertEquals(1, count, "已存在账号不得重复建号");
        String storedHash = jdbc.queryForObject("SELECT password_hash FROM user WHERE id = ?", String.class, userId);
        assertEquals(hash, storedHash, "验证码登录不得改动已有密码");
        assertNotNull(redis.opsForValue().get("refresh:" + userId));
    }
}
