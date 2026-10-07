package com.campus.social.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WS 会话管理器: 按 userId 维护多 session(一个用户可能同时打开 chat+notify 两个 WS)
 * 推送时对该 user 所有 session 广播
 *
 * 线程安全:ConcurrentHashMap + CopyOnWriteArraySet
 */
@Slf4j
@Component
public class WsSessionManager {

    /** userId -> 该用户的所有 WS session(同一 user 可能同时连 /ws/chat 和 /ws/notify) */
    private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    /** 已注册 session(按 sessionId)*/
    private final Map<String, WebSocketSession> allSessions = new ConcurrentHashMap<>();

    public void add(WebSocketSession session) {
        Object uidAttr = session.getAttributes().get(WsHandshakeInterceptor.ATTR_USER_ID);
        if (uidAttr == null) {
            log.warn("WS session without userId attr, rejecting: {}", session.getId());
            closeQuietly(session);
            return;
        }
        Long uid = (Long) uidAttr;
        userSessions.computeIfAbsent(uid, k -> new CopyOnWriteArraySet<>()).add(session);
        allSessions.put(session.getId(), session);
        log.info("WS connected: user={}, sessionId={}, endpoint={}",
                uid, session.getId(), session.getUri());
    }

    public void remove(WebSocketSession session) {
        allSessions.remove(session.getId());
        Object uidAttr = session.getAttributes().get(WsHandshakeInterceptor.ATTR_USER_ID);
        if (uidAttr instanceof Long uid) {
            Set<WebSocketSession> set = userSessions.get(uid);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) userSessions.remove(uid);
            }
        }
        log.info("WS disconnected: user={}, sessionId={}", uidAttr, session.getId());
    }

    /** 给某个 userId 的所有 session 广播一条文本消息 */
    public void broadcast(long userId, String message) {
        Set<WebSocketSession> set = userSessions.get(userId);
        if (set == null || set.isEmpty()) {
            log.debug("WS broadcast: user={} not online, msg dropped", userId);
            return;
        }
        TextMessage tm = new TextMessage(message);
        for (WebSocketSession s : set) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(tm);
                } catch (Exception e) {
                    log.warn("WS send fail, sessionId={}, err={}", s.getId(), e.getMessage());
                }
            }
        }
    }

    /** 该用户是否在线(任意 endpoint) */
    public boolean isOnline(long userId) {
        Set<WebSocketSession> set = userSessions.get(userId);
        if (set == null) return false;
        return set.stream().anyMatch(WebSocketSession::isOpen);
    }

    /**
     * 群多播:给一批 userId 各自的所有 session 广播同一条文本消息。
     * 循环现有单用户 broadcast(幂等去重:重复 userId 只发一次),离线用户直接丢弃。
     */
    public void broadcastAll(java.util.Collection<Long> userIds, String message) {
        if (userIds == null || userIds.isEmpty()) return;
        java.util.Set<Long> dedup = new java.util.LinkedHashSet<>(userIds);
        for (Long uid : dedup) {
            broadcast(uid, message);
        }
    }

    /** 当前在线 session 数(运维用) */
    public int size() {
        return allSessions.size();
    }

    private void closeQuietly(WebSocketSession s) {
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
