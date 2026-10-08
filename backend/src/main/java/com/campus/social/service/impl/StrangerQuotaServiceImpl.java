package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.StrangerQuotaService;
import com.campus.social.util.RedisLua;
import com.campus.social.vo.StrangerQuotaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 陌生人搭话配额实现（Redis ZSET 滑动窗口）。
 *
 * <p><b>窗口口径：自然日，非滚动 24 小时。</b>
 * SPEC §5.1 与前端文案口径都是「今日额度」与「次日 00:00 重置」，
 * 若按滚动 24 小时算，用户在凌晨 1 点发 5 条后，上午 9 点仍被锁死到次日 1 点，
 * 与「今天还能不能搭话」的用户心智不符。
 * 故 key 按日期分桶（{@code quota:stranger:newpeer:{userId}:{yyyyMMdd}}），
 * 跨日自动换桶，天然实现零点重置，不需要额外的重置任务。
 *
 * <p><b>为什么仍是滑动窗口脚本而不是简单 INCR：</b>
 * 配额语义是「向 5 个<b>不同</b>的新对象各发首条」，
 * 需要按 peerId 去重（同一 peer 重复发不重复计数），ZSET 的 member 天然承担去重职责。
 * INCR 做不到这一点。
 *
 * <p>窗口左边界取当日 00:00，配合按日分桶后 ZREMRANGEBYSCORE 实际是空操作，
 * 但保留它能让脚本在跨日边界、key 未及时轮转时仍然自愈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrangerQuotaServiceImpl implements StrangerQuotaService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String KEY_PREFIX = "quota:stranger:";

    private final RedisLua redisLua;
    private final UserMapper userMapper;
    private final CampusProperties campus;

    @Override
    public boolean tryConsumeNewPeer(long userId, long peerId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String key = KEY_PREFIX + "newpeer:" + userId + ":" + dayKey(now);
        long leftBound = leftBoundOf(now);
        long nowMs = System.currentTimeMillis();
        // member 用 peerId:ZSET 的 member 唯一,同一 peer 重复发只更新 score 不重复计数
        boolean ok = redisLua.tryAcquire(key, leftBound, campus.getDailyNewPeerLimit(),
                nowMs, String.valueOf(peerId), millisUntilNextDay(now));
        if (!ok) {
            log.info("stranger new-peer quota exhausted: user={}, peer={}", userId, peerId);
        }
        return ok;
    }

    @Override
    public boolean tryConsumePerPeer(long userId, long peerId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String key = KEY_PREFIX + "perpeer:" + userId + ":" + peerId + ":" + dayKey(now);
        long leftBound = leftBoundOf(now);
        long nowMs = System.currentTimeMillis();
        // member 必须每次唯一:同一 peer 的多条消息要各计一次,不能像新 peer 那样按 peerId 去重
        String member = nowMs + "-" + ThreadLocalRandom.current().nextLong(1_000_000L);
        boolean ok = redisLua.tryAcquire(key, leftBound, campus.getPerPeerDailyLimit(),
                nowMs, member, millisUntilNextDay(now));
        if (!ok) {
            log.info("stranger per-peer quota exhausted: user={}, peer={}", userId, peerId);
        }
        return ok;
    }

    /** 当日 00:00 的毫秒时间戳，作为滑动窗口左边界 */
    private long leftBoundOf(LocalDateTime now) {
        return now.toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    @Override
    public int consumeStrangerQuota(long userId, long peerId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        long leftBound = leftBoundOf(now);
        long nowMs = System.currentTimeMillis();
        long ttl = millisUntilNextDay(now);

        String newPeerKey = KEY_PREFIX + "newpeer:" + userId + ":" + dayKey(now);
        String perPeerKey = KEY_PREFIX + "perpeer:" + userId + ":" + peerId + ":" + dayKey(now);

        // 先读两个窗口的当前用量，纯只读、不记账。
        // 目的是把「注定要拒」的请求挡在记账之前：一旦开始扣减就没有回退手段
        // （ZSET 的 member 可能是本次新加的，也可能是历史就在的，ZREM 区分不了）。
        long newPeerUsed = redisLua.windowCount(newPeerKey, leftBound);
        long perPeerUsed = redisLua.windowCount(perPeerKey, leftBound);
        int newPeerLimit = campus.getDailyNewPeerLimit();
        int perPeerLimit = campus.getPerPeerDailyLimit();

        if (newPeerUsed >= newPeerLimit) {
            log.info("stranger new-peer quota exhausted: user={}, peer={}, used={}/{}",
                    userId, peerId, newPeerUsed, newPeerLimit);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "今日搭话额度已用完，明天再来");
        }
        if (perPeerUsed >= perPeerLimit) {
            log.info("stranger per-peer quota exhausted: user={}, peer={}, used={}/{}",
                    userId, peerId, perPeerUsed, perPeerLimit);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "今日对该对象的搭话额度已用完，明天再来");
        }

        // 两道都在额度内，再做真正的原子记账。
        // new-peer 的 member 用 peerId：同一 peer 当天重复发只刷新 score 不重复计数，
        // 这正是「最多向 5 个不同对象发起」的语义。
        boolean newPeerOk = redisLua.tryAcquire(newPeerKey, leftBound, newPeerLimit,
                nowMs, String.valueOf(peerId), ttl);
        if (!newPeerOk) {
            // 并发下另一个请求刚好抢走了最后一个名额。此时 per-peer 尚未扣，无需回退。
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "今日搭话额度已用完，明天再来");
        }
        // per-peer 的 member 必须每次唯一：同一 peer 的多条消息要各计一次
        String member = nowMs + "-" + ThreadLocalRandom.current().nextLong(1_000_000L);
        boolean perPeerOk = redisLua.tryAcquire(perPeerKey, leftBound, perPeerLimit, nowMs, member, ttl);
        if (!perPeerOk) {
            // 并发竞态：per-peer 名额被同一瞬间的另一个请求抢走。
            // 此时 new-peer 已记账且无法安全回退（peerId 可能本就已在集合里，
            // 盲目 ZREM 会抹掉历史记账），故只抛错不补偿——
            // 代价是极端并发下可能少一次向新对象搭话的机会，
            // 比「额度被扣了但消息没发出去」或「回退误删历史」两种错误轻得多。
            log.warn("per-peer quota lost race after new-peer booked: user={}, peer={}", userId, peerId);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "今日对该对象的搭话额度已用完，明天再来");
        }
        return (int) Math.max(0, newPeerLimit - (newPeerUsed + 1));
    }

    @Override
    public StrangerQuotaVO snapshot(long userId, Long peerId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        long leftBound = leftBoundOf(now);

        String newPeerKey = KEY_PREFIX + "newpeer:" + userId + ":" + dayKey(now);
        long used = redisLua.windowCount(newPeerKey, leftBound);
        int limit = campus.getDailyNewPeerLimit();
        int remaining = (int) Math.max(0, limit - used);

        Integer remainingPerPeer = null;
        if (peerId != null && peerId > 0) {
            String perPeerKey = KEY_PREFIX + "perpeer:" + userId + ":" + peerId + ":" + dayKey(now);
            long perUsed = redisLua.windowCount(perPeerKey, leftBound);
            remainingPerPeer = (int) Math.max(0, campus.getPerPeerDailyLimit() - perUsed);
        }

        // 我是否还接收陌生人消息:读自己的开关。这是「我能否被别人搭话」,
        // 与前面「我能向多少人搭话」是两个方向,前端要分开提示
        User me = userMapper.selectById(userId);
        boolean enabled = me == null || Integer.valueOf(1).equals(me.getStrangerMsgEnabled());

        return StrangerQuotaVO.builder()
                .dailyNewPeerLimit(limit)
                .dailyNewPeerUsed((int) used)
                .remainingNewPeer(remaining)
                .perPeerDailyLimit(campus.getPerPeerDailyLimit())
                .remainingPerPeer(remainingPerPeer)
                .resetAt(now.toLocalDate().plusDays(1).atStartOfDay())
                .strangerMsgEnabled(enabled)
                .build();
    }

    /** 日期分桶键(yyyyMMdd) */
    private String dayKey(LocalDateTime now) {
        return String.format("%04d%02d%02d", now.getYear(), now.getMonthValue(), now.getDayOfMonth());
    }

    /** 距次日 00:00 的毫秒数,用作 key 的 TTL——保证旧桶自然过期,不需清理任务 */
    private long millisUntilNextDay(LocalDateTime now) {
        LocalDateTime next = now.toLocalDate().plusDays(1).atStartOfDay();
        long millis = Duration.between(now, next).toMillis();
        // 加 1 小时余量:避免恰好在零点边界时 key 先于数据消失
        return millis + 3_600_000L;
    }
}
