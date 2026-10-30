package com.campus.social.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端 IP 取值口径（2026-10-30）。
 *
 * <p>登录失败锁定与游客下载限额都按 IP 计数，取错段等于让客户端随意换身份：
 * Nginx 的 X-Real-IP 是覆写的，可信；XFF 第一段来自客户端，可伪造，
 * 真实来源由 Nginx 追加在末尾。
 */
class RequestIpTest {

    @Test
    void prefersRealIpOverSpoofedForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.9");
        req.addHeader("X-Real-IP", "203.0.113.9");
        assertEquals("203.0.113.9", RequestIp.of(req));
    }

    @Test
    void fallsBackToLastForwardedHopNotFirst() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.9");
        assertEquals("203.0.113.9", RequestIp.of(req));
    }

    @Test
    void fallsBackToRemoteAddrWithoutProxyHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertEquals("127.0.0.1", RequestIp.of(req));
    }
}
