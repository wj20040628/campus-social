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

    /**
     * 当前在线的用户 id 集合（F5 公告推送用）。
     *
     * <p>公告是广播级内容，但<b>只推给在线的人</b>：SPEC §5.3 明确
     * 「发布时不写全员 notify 行，离线用户靠 unread-count 红点拉取」。
     * 若改成给全校每人写一行 notify，1 万用户 × 100 公告 = 100 万行写入放大，
     * 且已读要UPDATE 1 万行——这正是 SPEC §6.3 禁止的做法。
     *
     * <p>返回快照而非视图：调用方会拿着这个集合做一段时间的广播，
     * 期间有人上下线不应导致 ConcurrentModificationException。
     *
     * <p><b>已知局限</b>：单机内存 Session 不能跨实例。部署第 2 个实例后，
     * 用户连在 A 实例而公告发布在 B 实例时会漏推。
     * 届时须改 Redis PubSub，当前单机部署不涉及（见 memory 记录）。
     */
    public java.util.Set<Long> onlineUserIds() {
        java.util.Set<Long> online = new java.util.HashSet<>();
        userSessions.forEach((uid, set) -> {
            if (set.stream().anyMatch(WebSocketSession::isOpen)) {
                online.add(uid);
            }
        });
        return online;
    }

    private void closeQuietly(WebSocketSession s) {
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
