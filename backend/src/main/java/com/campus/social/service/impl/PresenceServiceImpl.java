package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.PresenceService;
import com.campus.social.vo.PresenceStatsVO;
import com.campus.social.vo.PresenceStatusVO;
import com.campus.social.websocket.WsSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在线状态与在线人数。
 *
 * <p><b>为什么要缓存</b>：头像上的在线点由前端每 30 秒批量轮询一次，顶栏人数
 * 也是定时轮询。人数暴涨时，这些「每人每次轮询都查一次库」的读会先于业务
 * 请求把 8 条数据库连接占满。会话在线集合在进程内存里、本身不查库，
 * 真正贵的是「这批人里谁隐身/已注销」和「在册用户数」两条 SQL。
 *
 * <p><b>缓存粒度</b>：
 * <ul>
 *   <li>可见性按 userId 缓存({@code presence:vis:{id}})：同一位作者的头像会被
 *   多个客户端反复查询，按人缓存命中率最高；</li>
 *   <li>在册用户数缓存({@code presence:cnt:users}) 60 秒：注册/注销变化很慢。</li>
 * </ul>
 *
 * <p><b>在线数不再缓存、也不再查库</b>（2026-11-14）：顶栏数字直接取
 * {@link WsSessionManager#onlineCount()}（只扫进程内存，且<b>包含隐身用户</b>），
 * 变化由 PresenceStatsPushTask 每秒推送。原先按在线集合查库过滤隐身 + 30 秒
 * 缓存，数字最坏要一分半才动；现在线上数据不再依赖 SQL，隐身设置也不会
 * 让总数抖动。
 *
 * <p><b>头像绿点仍排除隐身</b>：可见性只影响「别人能不能看到我在线」，
 * 不影响「在线总数」——后者是社区活跃度展示。
 *
 * <p><b>Redis 不可用时回落查库</b>，不抛错——缓存是加速手段，不能变成单点故障
 * （与 FeedPageCacheService / DictServiceImpl 同一口径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceServiceImpl implements PresenceService {

    private static final int MAX_IDS = 100;

    /** 可见性缓存时长。setHidden 会立即删键，正常路径下不会等它过期。 */
    private static final Duration VIS_TTL = Duration.ofSeconds(30);

    /** 在册用户数变化很慢（注册/注销），缓存久一点。 */
    private static final Duration USERS_TTL = Duration.ofSeconds(60);

    private static final String USERS_KEY = "presence:cnt:users";
    private static final String VIS_PREFIX = "presence:vis:";

    private final WsSessionManager sessionManager;
    private final UserMapper userMapper;
    private final StringRedisTemplate redis;

    @Override
    public PresenceStatusVO status(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new PresenceStatusVO(List.of());
        }
        List<Long> ids = userIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .limit(MAX_IDS)
                .toList();
        if (ids.isEmpty()) {
            return new PresenceStatusVO(List.of());
        }
        Set<Long> online = sessionManager.onlineUserIds();
        List<Long> hit = ids.stream().filter(online::contains).toList();
        if (hit.isEmpty()) {
            return new PresenceStatusVO(List.of());
        }
        return new PresenceStatusVO(visibleAmong(hit));
    }

    /** 在「当前在线」集合里筛出未隐身、未注销的人。 */
    private List<Long> visibleAmong(List<Long> hit) {
        Map<Long, Boolean> flags = readVisFlags(hit);
        List<Long> missing = hit.stream().filter(id -> !flags.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            Map<Long, Boolean> fromDb = new HashMap<>();
            for (User u : userMapper.selectList(new LambdaQueryWrapper<User>()
                    .select(User::getId, User::getStatus, User::getPresenceHidden)
                    .in(User::getId, missing))) {
                fromDb.put(u.getId(), deliverable(u) && !isHidden(u));
            }
            for (Long id : missing) {
                // 查不到行（已物理删除）按不可见处理，与旧逻辑一致
                boolean visible = Boolean.TRUE.equals(fromDb.get(id));
                flags.put(id, visible);
                writeVisFlag(id, visible);
            }
        }
        return hit.stream().filter(id -> Boolean.TRUE.equals(flags.get(id))).toList();
    }

    private Map<Long, Boolean> readVisFlags(List<Long> ids) {
        Map<Long, Boolean> flags = new HashMap<>();
        try {
            List<String> keys = ids.stream().map(id -> VIS_PREFIX + id).toList();
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values == null) {
                return flags;
            }
            for (int i = 0; i < ids.size(); i++) {
                String v = values.get(i);
                if ("1".equals(v)) {
                    flags.put(ids.get(i), true);
                } else if ("0".equals(v)) {
                    flags.put(ids.get(i), false);
                }
            }
        } catch (Exception e) {
            // 读失败就当作全部未命中，整批回落查库
            log.warn("presence vis cache read failed, fallback to db. cause={}", e.toString());
        }
        return flags;
    }

    private void writeVisFlag(long id, boolean visible) {
        try {
            redis.opsForValue().set(VIS_PREFIX + id, visible ? "1" : "0", VIS_TTL);
        } catch (Exception e) {
            log.warn("presence vis cache write failed, ignore. cause={}", e.toString());
        }
    }

    @Override
    public PresenceStatsVO stats() {
        Long users = readCounter(USERS_KEY);
        if (users == null) {
            users = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().ne(User::getStatus, 2));
            writeCounter(USERS_KEY, users, USERS_TTL);
        }
        /*
         * 在线数 = 有活跃 WS 连接的用户数（已注销不会连上 WS；含隐身）。
         * 直接读内存，不做缓存：值本身就是瞬时的，缓存只会制造延迟。
         */
        long online = sessionManager.onlineCount();
        return new PresenceStatsVO(users, online);
    }

    private Long readCounter(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null || v.isBlank() ? null : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("presence counter cache read failed, fallback to db. cause={}", e.toString());
            return null;
        }
    }

    private void writeCounter(String key, long value, Duration ttl) {
        try {
            redis.opsForValue().set(key, Long.toString(value), ttl);
        } catch (Exception e) {
            log.warn("presence counter cache write failed, ignore. cause={}", e.toString());
        }
    }

    @Override
    public boolean setHidden(long userId, boolean hidden) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getPresenceHidden, hidden ? 1 : 0));
        // 在线总数含隐身，不随开关变化；只需让头像可见性立即失效
        evict(VIS_PREFIX + userId);
        return hidden;
    }

    private void evict(String... keys) {
        try {
            redis.delete(List.of(keys));
        } catch (Exception e) {
            // 删不掉只会让旧值多活一个 TTL（≤30 秒），不影响最终一致
            log.warn("presence cache evict failed, will expire by ttl. cause={}", e.toString());
        }
    }

    @Override
    public int onlineAllCount() {
        return sessionManager.onlineCount();
    }

    private boolean deliverable(User u) {
        return !Integer.valueOf(2).equals(u.getStatus());
    }

    private boolean isHidden(User u) {
        return Objects.equals(u.getPresenceHidden(), 1);
    }
}
