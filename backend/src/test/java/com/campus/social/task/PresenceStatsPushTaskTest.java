package com.campus.social.task;

import com.campus.social.websocket.WsPushService;
import com.campus.social.websocket.WsSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在线人数推送的节流规则：数字不变不发，变了只发一次。
 *
 * <p>这条规则是「每秒检测」不变成骚扰推送的前提：一次登录会同时建立两条
 * WS 连接，若按连接事件直推，同一时刻会连推好几帧。
 */
@ExtendWith(MockitoExtension.class)
class PresenceStatsPushTaskTest {

    @Mock private WsSessionManager sessionManager;
    @Mock private WsPushService pushService;

    @Test
    void pushesOnlyWhenTheNumberChanges() {
        PresenceStatsPushTask task = new PresenceStatsPushTask(sessionManager, pushService);
        when(sessionManager.onlineCount()).thenReturn(3);

        task.pushIfChanged();
        task.pushIfChanged();

        verify(pushService, times(1)).pushPresenceStats(3);

        when(sessionManager.onlineCount()).thenReturn(4);
        task.pushIfChanged();

        verify(pushService).pushPresenceStats(4);
    }

    @Test
    void failureDoesNotBreakTheNextTick() {
        PresenceStatsPushTask task = new PresenceStatsPushTask(sessionManager, pushService);
        when(sessionManager.onlineCount()).thenThrow(new RuntimeException("boom"));

        task.pushIfChanged(); // 不抛给调度线程

        // 已 stub 成抛异常后，再 when(mock.method()) 会当场抛，必须改用 doReturn
        doReturn(2).when(sessionManager).onlineCount();
        task.pushIfChanged();

        verify(pushService).pushPresenceStats(2);
    }
}