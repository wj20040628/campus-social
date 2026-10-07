package com.campus.social.interceptor;

import com.campus.social.common.Result;
import com.campus.social.common.ResultCode;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * HTTP JWT 鉴权拦截器
 * - 校验 Authorization: Bearer <access>
 * - 白名单路径放行
 * - 通过后把 userId 塞到 request attribute,供后续 Controller 读取
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 这些路径不需要登录(精确路径,无前缀) */
    private static final Set<String> WHITE_LIST = Set.of(
            "/auth/register",
            "/auth/login",
            "/auth/refresh",
            "/auth/send-code",
            "/auth/login-code"
    );

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS(CORS 预检)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().replace(request.getContextPath(), "");
        if (WHITE_LIST.contains(path)) {
            return true;
        }

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauth(response, "缺少 Authorization header");
            return false;
        }
        String token = auth.substring(7).trim();
        Claims claims = com.campus.social.util.JwtUtil.parseAccess(token);
        if (claims == null) {
            writeUnauth(response, "token 无效或已过期");
            return false;
        }
        Long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            writeUnauth(response, "token 损坏");
            return false;
        }
        request.setAttribute(ATTR_USER_ID, userId);
        request.setAttribute(ATTR_USERNAME, claims.get("username"));
        return true;
    }

    private void writeUnauth(HttpServletResponse response, String msg) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(Result.fail(ResultCode.UNAUTHORIZED.getCode(), msg))
        );
    }
}
