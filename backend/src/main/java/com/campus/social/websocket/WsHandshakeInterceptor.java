package com.campus.social.websocket;

import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "wsUserId";

    private final UserMapper userMapper;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            reject(response, "missing_token");
            return false;
        }
        Claims claims = JwtUtil.parseAccess(token);
        if (claims == null) {
            reject(response, "invalid_or_expired");
            return false;
        }
        try {
            Long userId = Long.parseLong(claims.getSubject());
            // 封禁/注销状态校验:access token 有 15 分钟有效期,
            // 封禁/注销后该 token 在到期前仍能建连并收 broadcast。
            // 握手前查一次 user.status,非正常状态直接拒绝。
            User u = userMapper.selectById(userId);
            if (u == null || u.getStatus() == null || u.getStatus() != 0) {
                reject(response, "user_disabled");
                return false;
            }
            attributes.put(ATTR_USER_ID, userId);
            return true;
        } catch (NumberFormatException e) {
            reject(response, "malformed_token");
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

    /**
     * 浏览器 WS 握手只看 401 状态码,不会读任何 header —— 前端靠它触发
     * refresh 重连。X-WS-Auth-Error 仅作服务端调试用,值必须是 ASCII:
     * HTTP 响应头按 ISO-8859-1 编码,写中文会让 Tomcat 抛
     * IllegalArgumentException 并丢弃整个头(见历史 WARN)。
     */
    private void reject(ServerHttpResponse response, String code) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set("X-WS-Auth-Error", code);
    }
}
