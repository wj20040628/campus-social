package com.campus.social.service;

import com.campus.social.entity.AuditLog;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.impl.AuditServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * audit_log 的 IP 补齐（2026-10-29）。
 *
 * <p>自评估报告写的是「audit_log（操作人、动作、对象、详情、IP、时间）」，
 * 但改造前登录与管理员处置一律传 null/""，整列为空。这里锁住补全规则：
 * 调用方没传就从当前请求取（XFF 第一段），显式传入则不覆盖，无请求上下文保持 null。
 */
class AuditIpTest {

    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final AuditServiceImpl service =
            new AuditServiceImpl(auditLogMapper, mock(UserMapper.class));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void fillsIpFromRequestWhenCallerPassesNull() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        bind(req);

        service.record(1L, "login", "user", 1L, "identifier=x", null);

        assertEquals("203.0.113.9", captured().getIp(), "应取 XFF 第一段");
    }

    @Test
    void keepsExplicitIp() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        bind(req);

        service.record(1L, "login", "user", 1L, "identifier=x", "198.51.100.7");

        assertEquals("198.51.100.7", captured().getIp(), "显式传入的 IP 不应被覆盖");
    }

    @Test
    void noRequestLeavesIpNull() {
        service.record(1L, "login", "user", 1L, "identifier=x", null);

        assertNull(captured().getIp(), "定时任务/异步线程没有请求上下文时保持 null");
    }

    private void bind(MockHttpServletRequest req) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private AuditLog captured() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }
}
