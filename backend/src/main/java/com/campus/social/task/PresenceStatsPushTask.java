package com.campus.social.task;

import com.campus.social.websocket.WsPushService;
import com.campus.social.websocket.WsSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 在线人数变化推送（2026-11-14）。
 *
 * <p>为什么不在 WsHandler 的 add/remove 里直接广播：
 * 一次登录会同时建立 /ws/chat 与 /ws/notify 两条连接，加上断线重连的抖动，
 * 直接广播会让「同一批人上线」推很多次。改成每秒看一次数字，变了才推：
 * 延迟上限 1 秒，稳态零流量。
 *
 * <p>为什么数字不用缓存：{@link WsSessionManager#onlineCount()} 只扫进程内存，
 * 本就不查库/不查 Redis，缓存它反而把「刚上线」挡在 TTL 外面。
 *
 * <p><b>单实例前提</b>：会话表在进程内存里，多实例部署时各算各的。
 * 当前只有一台 ECS（见发布记录），扩容时要改成 Redis PubSub 汇总。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceStatsPushTask {

    private final WsSessionManager sessionManager;
    private final WsPushService pushService;

    /** 上次推出去的数字。-1 表示进程启动后还没推过。 */
    private volatile int lastPushed = -1;

    @Scheduled(fixedDelay = 1000)
    public void pushIfChanged() {
        try {
            int online = sessionManager.onlineCount();
            if (online == lastPushed) {
                return;
            }
            lastPushed = online;
            pushService.pushPresenceStats(online);
        } catch (Exception e) {
            // 推送失败不影响在线状态本身，下一跳会再对一次
            log.warn("presence stats push fail: {}", e.getMessage());
        }
    }
}