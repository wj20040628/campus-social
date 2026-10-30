package com.campus.social.integration;

import com.campus.social.common.ResultCode;
import com.campus.social.dto.BindEmailDTO;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuthService;
import com.campus.social.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绑定安全凭证邮箱特征测试：验证码消费、转小写落库、全局唯一冲突。
 *
 * <p>验证码不走真实邮件，直接写 {@code code:bind:<email>} 再调用绑定端点。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class BindEmailFlowTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);
    private static final String NEW_EMAIL = "bind-new-" + SUFFIX + "@campus.test";
    private static final String TAKEN_EMAIL = "bind-taken-" + SUFFIX + "@campus.test";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    private Long mine;
    private Long other;

    @BeforeEach
    void seed() {
        User a = new User();
        a.setUsername("bind-me-" + SUFFIX + "@campus.test");
        a.setPasswordHash("x");
        a.setNickname("待绑定");
        a.setStatus(0);
        userMapper.insert(a);
        mine = a.getId();

        User b = new User();
        b.setUsername("bind-other-" + SUFFIX + "@campus.test");
        b.setPasswordHash("x");
        b.setNickname("已占用");
        b.setEmail(TAKEN_EMAIL);
        b.setStatus(0);
        userMapper.insert(b);
        other = b.getId();
    }

    @AfterEach
    void cleanup() {
        for (String email : new String[]{NEW_EMAIL, TAKEN_EMAIL}) {
            redis.delete("code:bind:" + email);
            redis.delete("code:fail:bind:" + email);
        }
        if (mine != null) {
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", mine);
            jdbc.update("DELETE FROM user WHERE id = ?", mine);
        }
        if (other != null) {
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", other);
            jdbc.update("DELETE FROM user WHERE id = ?", other);
        }
    }

    private BindEmailDTO dto(String email, String code) {
        BindEmailDTO d = new BindEmailDTO();
        d.setEmail(email);
        d.setCode(code);
        return d;
    }

    @Test
    void bindEmailConsumesCodeStoresLowercaseAndWritesAudit() {
        redis.opsForValue().set("code:bind:" + NEW_EMAIL, "654321", Duration.ofMinutes(5));

        UserVO vo = authService.bindEmail(mine, dto("  " + NEW_EMAIL.toUpperCase() + "  ", "654321"));

        User after = userMapper.selectById(mine);
        assertEquals(NEW_EMAIL, after.getEmail(), "邮箱应去空格并转小写落库");
        assertTrue(Boolean.TRUE.equals(vo.getEmailBound()));
        assertNull(redis.opsForValue().get("code:bind:" + NEW_EMAIL), "验证码应一次性消费");

        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE user_id = ? AND action = 'bind_email'",
                Integer.class, mine);
        assertTrue(audit != null && audit >= 1, "绑定邮箱应写审计");
    }

    @Test
    void emailOwnedByAnotherAccountIsRejected() {
        redis.opsForValue().set("code:bind:" + TAKEN_EMAIL, "123456", Duration.ofMinutes(5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.bindEmail(mine, dto(TAKEN_EMAIL, "123456")));
        assertEquals(ResultCode.EMAIL_ALREADY_BOUND.getCode(), ex.getCode());
        assertNull(userMapper.selectById(mine).getEmail(), "冲突时不应写入邮箱");
    }

    @Test
    void wrongCodeLeavesEmailUnchanged() {
        redis.opsForValue().set("code:bind:" + NEW_EMAIL, "111111", Duration.ofMinutes(5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.bindEmail(mine, dto(NEW_EMAIL, "222222")));
        assertEquals(ResultCode.CODE_INVALID.getCode(), ex.getCode());
        assertNull(userMapper.selectById(mine).getEmail());
    }
}
