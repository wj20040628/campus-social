package com.campus.social.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.impl.PresenceServiceImpl;
import com.campus.social.vo.PresenceStatsVO;
import com.campus.social.vo.PresenceStatusVO;
import com.campus.social.websocket.WsSessionManager;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 在线状态缓存的读路径与失效路径。
 *
 * <p>目的不是测「能返回在线名单」（那是接口行为），而是钉住三条性能/正确性约束：
 * 同一批人在 TTL 内重复轮询不能再查库；隐身切换必须立即生效而不是等 TTL；
 * Redis 整体不可用时必须回落查库而不是把接口打挂。
 */
@ExtendWith(MockitoExtension.class)
class PresenceServiceImplCacheTest {

    @Mock private WsSessionManager sessionManager;
    @Mock private UserMapper userMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private PresenceServiceImpl service;

    /** 纯 Mockito 测试没有 MyBatis 上下文，需手动注册 User 的列缓存，否则 lambda 取列名会抛错。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new PresenceServiceImpl(sessionManager, userMapper, redis);
    }

    @Test
    void statusFiltersHiddenAndCancelledThenCachesEachUser() {
        when(sessionManager.onlineUserIds()).thenReturn(Set.of(1L, 2L, 3L));
        when(valueOps.multiGet(List.of("presence:vis:1", "presence:vis:2", "presence:vis:3")))
                .thenReturn(Arrays.asList(null, null, null));
        when(userMapper.selectList(any())).thenReturn(List.of(
                user(1L, 0, 0),   // 正常
                user(2L, 0, 1),   // 隐身
                user(3L, 2, 0))); // 已注销

        PresenceStatusVO vo = service.status(List.of(1L, 2L, 3L));

        assertEquals(List.of(1L), vo.getOnline());
        verify(valueOps).set("presence:vis:1", "1", Duration.ofSeconds(30));
        verify(valueOps).set("presence:vis:2", "0", Duration.ofSeconds(30));
        verify(valueOps).set("presence:vis:3", "0", Duration.ofSeconds(30));
    }

    @Test
    void statusUsesCachedFlagsWithoutTouchingDb() {
        when(sessionManager.onlineUserIds()).thenReturn(Set.of(1L, 2L));
        when(valueOps.multiGet(List.of("presence:vis:1", "presence:vis:2")))
                .thenReturn(List.of("1", "0"));

        PresenceStatusVO vo = service.status(List.of(1L, 2L));

        assertEquals(List.of(1L), vo.getOnline());
        verifyNoInteractions(userMapper);
    }

    @Test
    void statusIgnoresOfflineIdsWithoutQueryingAnything() {
        when(sessionManager.onlineUserIds()).thenReturn(Set.of(9L));

        assertTrue(service.status(List.of(5L)).getOnline().isEmpty());

        verifyNoInteractions(userMapper);
        verifyNoInteractions(valueOps);
    }

    @Test
    void statsCachesUsersButReadsOnlineFromMemory() {
        when(valueOps.get("presence:cnt:users")).thenReturn(null, "42");
        when(userMapper.selectCount(any())).thenReturn(42L);
        when(sessionManager.onlineCount()).thenReturn(5);

        PresenceStatsVO first = service.stats();
        assertEquals(42L, first.getUsers());
        assertEquals(5L, first.getOnline());
        verify(valueOps).set("presence:cnt:users", "42", Duration.ofSeconds(60));

        PresenceStatsVO second = service.stats();
        assertEquals(42L, second.getUsers());
        assertEquals(5L, second.getOnline());
        // 在线数不缓存：每次都要读当下内存值，否则刚上线的人会被挡在旧值外
        verify(sessionManager, times(2)).onlineCount();
        verify(userMapper, times(1)).selectCount(any());
    }

    /**
     * 隐身只影响头像绿点，不影响在线总数（2026-11-14 需求）。
     * 在线数既然直接读内存，就不该再为统计去查 user 表筛 presence_hidden。
     */
    @Test
    void statsCountsHiddenUsersWithoutTouchingDb() {
        when(valueOps.get("presence:cnt:users")).thenReturn("100");
        when(sessionManager.onlineCount()).thenReturn(4);

        PresenceStatsVO vo = service.stats();

        assertEquals(4L, vo.getOnline());
        verifyNoInteractions(userMapper);
    }

    @Test
    void setHiddenEvictsVisibilityOnly() {
        service.setHidden(7L, true);

        verify(userMapper).update(isNull(), any());
        // 在线总数含隐身，不随开关变化，不需要删任何计数键
        verify(redis).delete(List.of("presence:vis:7"));
    }

    @Test
    void redisFailureFallsBackToDb() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(sessionManager.onlineUserIds()).thenReturn(Set.of(1L));
        when(userMapper.selectList(any())).thenReturn(List.of(user(1L, 0, 0)));
        when(userMapper.selectCount(any())).thenReturn(10L);
        when(sessionManager.onlineCount()).thenReturn(3);

        assertEquals(List.of(1L), service.status(List.of(1L)).getOnline());

        PresenceStatsVO stats = service.stats();
        assertEquals(10L, stats.getUsers());
        assertEquals(3L, stats.getOnline());
        verify(valueOps, never()).multiGet(any());
    }

    private static User user(long id, int status, int hidden) {
        User u = new User();
        u.setId(id);
        u.setStatus(status);
        u.setPresenceHidden(hidden);
        return u;
    }
}
