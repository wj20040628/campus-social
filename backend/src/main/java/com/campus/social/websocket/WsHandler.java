package com.campus.social.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 统一 WsHandler
 * 服务端→客户端:服务端自己调 WsSessionManager.broadcast(),不依赖客户端发指令
 * 客户端→服务端:仅处理心跳 ping(回 pong)
 */
@Slf4j
@Component
public class WsHandler extends TextWebSocketHandler {

    private final WsSessionManager sessionManager;

    public WsHandler(WsSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionManager.add(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        // 客户端心跳:{"type":"ping","ts":...}
        if (payload.startsWith("{\"type\":\"ping\"")) {
            try {
                session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            } catch (Exception e) {
                log.warn("WS pong fail: {}", e.getMessage());
            }
        } else {
            log.debug("WS unknown msg from {}: {}", session.getId(), payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionManager.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS transport error: session={}, err={}", session.getId(), exception.getMessage());
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
        }
    }
}
