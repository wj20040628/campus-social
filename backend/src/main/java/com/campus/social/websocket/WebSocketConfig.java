package com.campus.social.websocket;

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

    public WebSocketConfig(WsHandler wsHandler,
            WsHandshakeInterceptor wsInterceptor) {
        this.wsHandler = wsHandler;
        this.wsInterceptor = wsInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/ws/chat", "/ws/notify")
                .addInterceptors(wsInterceptor)
                .setAllowedOrigins("*");
    }
}
