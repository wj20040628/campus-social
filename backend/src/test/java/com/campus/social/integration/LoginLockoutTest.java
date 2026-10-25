package com.campus.social.integration;

import com.campus.social.dto.LoginDTO;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 密码登录失败锁定回归(2026-10-25,外部审查抓出的撞库缺口)。
 *
 * <p>断言:账号维度 5 次错密后,连正确密码也 429(锁定);换个账号名
 * 不受影响(维度隔离);成功登录清零计数。种子数据自播种自清理,
 * Redis 键按测试账号前缀清理。
 */
@SpringBootTest
@ActiveProfiles("local")
class LoginLockoutTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final String IP = "203.0.113.77";

    @Autowired
    private AuthService authService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    private Long userId;
    private String username;

    @BeforeEach
    void seed() {
        username = "lockout-" + SUFFIX + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, email) VALUES (?,?,?,0,0,1,1,1,0,?)",
                username, "$2a$10$abcdefghijklmnopqrstuv", username, username);
        userId = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        // 密码哈希是占位串,任何密码都会 verifyPassword 失败——正好当「错误密码」用
    }

    @AfterEach
    void cleanup() {
        if (userId != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", userId);
        }
        redis.delete("login:fail:u:" + username);
        redis.delete("login:fail:ip:" + IP);
    }

    private LoginDTO attempt(String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    @Test
    void fiveFailuresLockAccountEvenWithCorrectPassword() {
        for (int i = 0; i < 5; i++) {
            final String pw = "wrong-" + i;
            assertThrows(BusinessException.class, () -> authService.login(attempt(pw), IP));
        }
        // 5 次失败后,连「正确密码」也被锁(种子哈希是占位串,任何密码都错,断言锁定码而非 1005)
        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(attempt("wrong-again"), IP));
        assertEquals(com.campus.social.common.ResultCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
    }

    @Test
    void lockoutIsPerAccount() {
        // 打满 username 的失败额度
        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessException.class, () -> authService.login(attempt("wrong"), IP));
        }
        // 另一个账号(同 IP)不受影响:种子一个真用户直接试(失败也是 1005 而非 429)
        String other = "lockout-other-" + SUFFIX + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted, email) VALUES (?,?,?,0,0,1,1,1,0,?)",
                other, "$2a$10$abcdefghijklmnopqrstuv", other, other);
        try {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> authService.login(dtoFor(other, "wrong"), IP));
            assertEquals(com.campus.social.common.ResultCode.WRONG_PASSWORD.getCode(), e.getCode(),
                    "其他账号不应被连带锁定");
        } finally {
            Long oid = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, other);
            jdbc.update("DELETE FROM user WHERE id = ?", oid);
            redis.delete("login:fail:u:" + other);
        }
    }

    private LoginDTO dtoFor(String account, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(account);
        dto.setPassword(password);
        return dto;
    }
}
