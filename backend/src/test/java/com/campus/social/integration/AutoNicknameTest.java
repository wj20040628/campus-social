package com.campus.social.integration;

import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动昵称（2026-10-28）：邮箱验证码首次登录建号时，昵称必须是
 * 「用户」+ 6 位数字，且不能与已有账号重复。
 *
 * <p>背景：原来是 4 位（1000-9999 共 9000 种），按生日悖论百来人时
 * 已有近一半概率撞名——昵称不是唯一键，撞了不报错，但新用户会看到
 * 自己和一个陌生人同名。本用例直接走真实登录建号链路，不 mock 用户名生成。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AutoNicknameTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 1_000_000);

    @Autowired
    private AuthService authService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    private final String email = "nick-" + SUFFIX + "@example.com";
    private final String email2 = "nick2-" + SUFFIX + "@example.com";

    @AfterEach
    void cleanup() {
        for (String m : new String[]{email, email2}) {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id IN "
                    + "(SELECT id FROM user WHERE email = ?)", m);
            jdbc.update("DELETE FROM user WHERE email = ?", m);
            redis.delete("code:login:" + m);
        }
    }

    @Test
    void emailRegistrationGetsSixDigitUniqueNickname() {
        String nick1 = registerAndReadNickname(email);
        String nick2 = registerAndReadNickname(email2);

        assertTrue(nick1.matches("用户\\d{6}"),
                "昵称应为「用户」+6 位数字，实际=" + nick1);
        assertTrue(nick2.matches("用户\\d{6}"),
                "昵称应为「用户」+6 位数字，实际=" + nick2);
        assertTrue(!nick1.equals(nick2),
                "两个新账号的自动昵称不应相同：" + nick1);
    }

    private String registerAndReadNickname(String mail) {
        // 直接种验证码，跳过发信；类型与 AuthServiceImpl.loginByCode 读取的一致
        redis.opsForValue().set("code:login:" + mail, "123456", Duration.ofMinutes(10));
        LoginByCodeDTO dto = new LoginByCodeDTO();
        dto.setEmail(mail);
        dto.setCode("123456");
        authService.loginByCode(dto);
        return jdbc.queryForObject("SELECT nickname FROM user WHERE email = ?", String.class, mail);
    }
}
