package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.RegisterDTO;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册流程特征测试：拆分 RegisterService 前锁定行为。
 * 验证码直接写 Redis（code:register:{email}），不依赖真实发信。
 */
@SpringBootTest
@ActiveProfiles("local")
class RegisterFlowTest {

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
            redis.delete("code:register:" + email);
            redis.delete("code:fail:register:" + email);
        }
    }

    private RegisterDTO dto(String email, String nickname) {
        RegisterDTO dto = new RegisterDTO();
        dto.setEmail(email);
        dto.setEmailCode(CODE);
        dto.setPassword("Pw-" + System.nanoTime() + "-1");
        dto.setNickname(nickname);
        return dto;
    }

    @Test
    void registerConsumesCodeCreatesUserAndIssuesTokens() {
        email = "reg-" + System.nanoTime() + "@campus.test";
        redis.opsForValue().set("code:register:" + email, CODE, Duration.ofMinutes(5));

        TokenPairVO pair = authService.register(dto(email, "注册测试"));

        assertNotNull(pair.getAccessToken());
        assertNotNull(pair.getRefreshToken());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, username, email, verify_status, password_hash FROM user WHERE email = ?", email);
        userId = ((Number) row.get("id")).longValue();
        assertEquals(email, row.get("username"), "新账号 username 应存邮箱");
        assertEquals(0, ((Number) row.get("verify_status")).intValue(), "不填学号应保持未填写状态");
        assertTrue(JwtUtil.isPasswordSet((String) row.get("password_hash")));
        assertNotNull(redis.opsForValue().get("refresh:" + userId), "注册成功应立即签发 refresh 指纹");
        assertNull(redis.opsForValue().get("code:register:" + email), "注册成功后验证码必须被消费");
    }

    @Test
    void registerWithoutValidCodeIsRejected() {
        email = "reg-nocode-" + System.nanoTime() + "@campus.test";

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(dto(email, "无码注册")));

        assertEquals(ResultCode.CODE_INVALID.getCode(), ex.getCode());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE email = ?", Integer.class, email);
        assertEquals(0, count, "验证码无效时不得建号");
    }
}
