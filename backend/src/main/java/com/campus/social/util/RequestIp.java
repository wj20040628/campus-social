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
 * <p>信任口径：只信任 Nginx 追加的 XFF。若将来把 8080 直接暴露公网，
 * 客户端可伪造该头——目前安全组只开 80/443，8080 仅本机监听。
 */
public final class RequestIp {

    private RequestIp() {
    }

    public static String of(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
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
