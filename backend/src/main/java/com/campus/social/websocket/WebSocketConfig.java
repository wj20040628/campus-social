package com.campus.social.websocket;

import com.campus.social.config.CorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册
 * /ws/chat 私信
 * /ws/notify 通知
 * 两者共用一个 WsSessionManager 实例,按 userId 维护多 session
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WsHandler wsHandler;
    private final WsHandshakeInterceptor wsInterceptor;
    private final CorsProperties corsProperties;

    public WebSocketConfig(WsHandler wsHandler,
            WsHandshakeInterceptor wsInterceptor,
            CorsProperties corsProperties) {
        this.wsHandler = wsHandler;
        this.wsInterceptor = wsInterceptor;
        this.corsProperties = corsProperties;
    }

    /**
     * origin 白名单（TD-02）。
     *
     * <p>改造前是 {@code setAllowedOrigins("*")}：任意站点都能发起 WS 握手。
     * WS 握手会自动带上浏览器已存的 Authorization 上下文，
     * 全开等于把私信流暴露给任意第三方页面，是上线阻断项。
     *
     * <p>与 HTTP CORS 共用同一份白名单，避免两处配置漂移。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/ws/chat", "/ws/notify")
                .addInterceptors(wsInterceptor)
                .setAllowedOrigins(corsProperties.resolveOrigins().toArray(new String[0]));
    }
}
