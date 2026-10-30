package com.campus.social.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 取请求来源 IP（2026-10-29）。
 *
 * <p>为什么要有这个类：部署在 Nginx 之后，{@code remoteAddr} 恒为 127.0.0.1，
 * 真实来源在 {@code X-Forwarded-For} 的第一段。三个 Controller 各写过一份
 * 相同逻辑，审计日志又到处传 null——自评估报告承诺 audit_log 含 IP，
 * 抽查时那一列却是空的。这里收成一份，审计在调用方没传 IP 时自动补齐。
 *
 * <p>信任口径：Nginx 用 {@code proxy_set_header X-Real-IP $remote_addr} 覆写该头，
 * 客户端伪造无效，所以优先读 X-Real-IP。{@code $proxy_add_x_forwarded_for} 会把
 * 真实来源**追加在 XFF 末尾**，而第一段是客户端自带、可任意伪造的 ——
 * 之前取第一段等于让攻击者随意换 IP，绕过登录失败锁定与游客下载限额（2026-10-30 修）。
 */
public final class RequestIp {

    private RequestIp() {
    }

    public static String of(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 没有 X-Real-IP 时取最后一段（Nginx 追加的真实来源），不是第一段
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return req.getRemoteAddr();
    }

    /** 当前线程绑定的请求 IP；定时任务/异步线程没有请求时返回 null */
    public static String current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return of(sra.getRequest());
        }
        return null;
    }
}
