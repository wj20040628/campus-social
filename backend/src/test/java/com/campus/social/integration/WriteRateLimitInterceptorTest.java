package com.campus.social.integration;

import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.WriteRateLimitInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import com.campus.social.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写接口限流特征测试：用户维度、IP 维度、两个维度都不半记账、非写请求放行。
 *
 * <p>直接调拦截器而不是起 HTTP 端口：限流与鉴权、业务校验解耦，
 * 这里只锁定「幂等键、窗口、阈值、429 响应」四件事；另有一条 MockMvc
 * 用例确认拦截器确实注册进了 MVC 链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class WriteRateLimitInterceptorTest {

    private static final String IP = "198.51.100.42";

    @Autowired
    private WriteRateLimitInterceptor interceptor;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MeterRegistry meterRegistry;

    private MockHttpServletResponse lastResponse;

    @BeforeEach
    @AfterEach
    void clearLimitKeys() {
        Set<String> keys = redis.keys("rl:w:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private boolean call(String method, String uri, Long userId, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("X-Real-IP", ip);
        if (userId != null) {
            request.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        }
        lastResponse = new MockHttpServletResponse();
        return interceptor.preHandle(request, lastResponse, new Object());
    }

    @Test
    void postCreateAllowsTenPerMinuteThenReturns429() throws Exception {
        long userId = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            assertTrue(call("POST", "/post", userId, IP), "前 10 次应放行，第 " + (i + 1) + " 次被拒");
        }
        assertFalse(call("POST", "/post", userId, IP), "第 11 次应超限");
        assertEquals(429, lastResponse.getStatus());
        assertEquals("60", lastResponse.getHeader("Retry-After"));
        String body = new String(lastResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":429"), body);
        assertTrue(body.contains("发帖太频繁"), body);
    }

    @Test
    void limitIsIsolatedPerUser() throws Exception {
        long userA = System.nanoTime();
        long userB = userA + 1;
        for (int i = 0; i < 10; i++) {
            assertTrue(call("POST", "/post", userA, IP));
        }
        assertFalse(call("POST", "/post", userA, IP));
        assertTrue(call("POST", "/post", userB, IP), "另一个用户不应被连带限流");
    }

    @Test
    void ipLimitStopsDifferentUsersFromSameOrigin() throws Exception {
        long base = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertTrue(call("POST", "/report", base + i, IP),
                    "同一 IP 的第 " + (i + 1) + " 个用户应放行");
        }
        assertFalse(call("POST", "/report", base + 100, IP),
                "同一 IP 第 101 个用户应被 IP 维度拦下");
        assertEquals(429, lastResponse.getStatus());
    }

    @Test
    void rejectedIpRequestDoesNotConsumeUserQuota() throws Exception {
        long base = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertTrue(call("POST", "/report", base + i, IP));
        }
        long blockedUser = base + 100;
        assertFalse(call("POST", "/report", blockedUser, IP));
        // 换一个干净 IP，同一用户仍有完整 10 次额度（若刚才半记账，会只剩 9 次）
        String cleanIp = "198.51.100.43";
        for (int i = 0; i < 10; i++) {
            assertTrue(call("POST", "/report", blockedUser, cleanIp),
                    "干净 IP 下第 " + (i + 1) + " 次应放行，说明被拒请求没有扣用户额度");
        }
        assertFalse(call("POST", "/report", blockedUser, cleanIp));
    }

    @Test
    void chatSendAndChatMessageShareOneBudget() throws Exception {
        long userId = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            String uri = i % 2 == 0 ? "/chat/send" : "/chat/message";
            assertTrue(call("POST", uri, userId, IP), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(call("POST", "/chat/send", userId, IP),
                "两个兼容路径应共享同一窗口");
    }

    @Test
    void getRequestsAndUnmatchedWritesAreNotLimited() throws Exception {
        long userId = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            assertTrue(call("GET", "/post", userId, IP), "GET 不应限流");
            assertTrue(call("POST", "/post/1/like", userId, IP), "未配置的写接口不应限流");
        }
        assertTrue(call("POST", "/post", null, IP), "无登录属性时由鉴权拦截器处理，这里放行");
    }

    @Test
    void interceptorIsRegisteredInMvcChainAndReturnsHttp429() throws Exception {
        String username = "rl-http-" + System.nanoTime() + "@campus.test";
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,1,1,0)",
                username, JwtUtil.PASSWORD_UNSET, "限流测试");
        Long userId = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        String ip = "198.51.100.44";
        double blockedBefore = meterRegistry
                .counter("campus.rate_limit.blocked", "action", "report-submit").count();
        try {
            String token = JwtUtil.issueAccess(userId, username);
            // 前 10 次通过拦截器，随后因举报 DTO 为空被参数校验拒绝（400）
            for (int i = 0; i < 10; i++) {
                mockMvc.perform(post("/report")
                                .header("Authorization", "Bearer " + token)
                                .header("X-Real-IP", ip)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isBadRequest());
            }
            mockMvc.perform(post("/report")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Real-IP", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isTooManyRequests());
            assertTrue(meterRegistry.counter("campus.rate_limit.blocked",
                            "action", "report-submit").count() > blockedBefore,
                    "429 应计入 campus.rate_limit.blocked");
        } finally {
            jdbc.update("DELETE FROM user WHERE id = ?", userId);
            redis.delete("user:status:" + userId);
        }
    }
}
