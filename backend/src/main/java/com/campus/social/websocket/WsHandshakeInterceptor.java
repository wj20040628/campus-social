package com.campus.social.websocket;

import com.campus.social.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器
 *
 * 浏览器 WebSocket 不能自定义 header,只能 query 带 token:
 *   ws://host/ws?access_token=xxx
 *
 * beforeHandshake:
 *   解析 access_token → 验签+过期 → 把 userId 塞进 attributes
 *   失败返回 false + 写 401 响应,前端识别后调 /auth/refresh 再重连
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "wsUserId";

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            reject(response, "缺少 access_token");
            return false;
        }
        Claims claims = JwtUtil.parseAccess(token);
        if (claims == null) {
            reject(response, "access_token 无效或已过期");
            return false;
        }
        try {
            Long userId = Long.parseLong(claims.getSubject());
            attributes.put(ATTR_USER_ID, userId);
            return true;
        } catch (NumberFormatException e) {
            reject(response, "token 损坏");
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        if (exception != null) {
            System.err.println("WS handshake error: " + exception.getMessage());
        }
    }

    private String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest sreq) {
            HttpServletRequest servlet = sreq.getServletRequest();
            String fromParam = servlet.getParameter("access_token");
            if (fromParam != null && !fromParam.isBlank()) {
                return fromParam;
            }
        }
        // 兜底:从 query string 取
        URI uri = request.getURI();
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && "access_token".equals(pair.substring(0, idx))) {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }

    /** 浏览器 WS 握手只看状态码,无需 JSON body */
    private void reject(ServerHttpResponse response, String reason) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        // 可选:把原因写到 header,便于调试
        response.getHeaders().set("X-WS-Auth-Error", reason);
    }
}
