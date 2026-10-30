package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.config.CampusProperties;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.StrangerQuotaService;
import com.campus.social.service.support.CampusAccessService;
import com.campus.social.service.support.StrangerPolicy;
import com.campus.social.util.RedisLua;
import com.campus.social.vo.StrangerQuotaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 陌生人搭话配额实现（Redis ZSET 滑动窗口）。
 *
 * <p><b>窗口口径：自然日，非滚动 24 小时。</b>
 * 用户心智是「今天还能不能发」与「次日 00:00 重置」，若按滚动 24 小时算，
 * 凌晨 1 点发完的人会被锁到次日 1 点，与「今天」不符。
 * 故 key 按日期分桶（{@code quota:stranger:perpeer:{userId}:{peerId}:{yyyyMMdd}}），
 * 跨日自动换桶，天然实现零点重置。
 *
 * <p><b>2026-10-28 规则变更</b>：去掉「每天最多向 N 个新对象发起会话」，
 * 只保留「对同一陌生人每天 1 条」。对象数量不是风险，对单个对象的骚扰频率才是；
 * 对方关注我或回复我之后，由 {@link StrangerPolicy} 判定不再是陌生人，直接跳过配额。
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
    private final StrangerPolicy strangerPolicy;
    private final CampusAccessService access;

    @Override
    public int consumePerPeer(long userId, long peerId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String key = KEY_PREFIX + "perpeer:" + userId + ":" + peerId + ":" + dayKey(now);
        long leftBound = leftBoundOf(now);
        long nowMs = System.currentTimeMillis();
        long ttl = millisUntilNextDay(now);
        int limit = campus.getPerPeerDailyLimit();

        // member 必须每次唯一：同一 peer 的多条消息要各计一次，不能去重
        String member = nowMs + "-" + ThreadLocalRandom.current().nextLong(1_000_000L);
        boolean ok = redisLua.tryAcquire(key, leftBound, limit, nowMs, member, ttl);
        if (!ok) {
            log.info("stranger per-peer quota exhausted: user={}, peer={}, limit={}",
                    userId, peerId, limit);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS,
                    "给同一位同学每天最多发 " + limit + " 条，明天再来；对方关注你或回复你后不再受限");
        }
        long used = redisLua.windowCount(key, leftBound);
        return (int) Math.max(0, limit - used);
    }

    @Override
    public StrangerQuotaVO snapshot(long userId, Long peerId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        long leftBound = leftBoundOf(now);

        Boolean isStranger = null;
        Integer remainingPerPeer = null;
        if (peerId != null && peerId > 0) {
            isStranger = strangerPolicy.isStranger(userId, peerId);
            if (isStranger) {
                String perPeerKey = KEY_PREFIX + "perpeer:" + userId + ":" + peerId + ":" + dayKey(now);
                long perUsed = redisLua.windowCount(perPeerKey, leftBound);
                remainingPerPeer = (int) Math.max(0, campus.getPerPeerDailyLimit() - perUsed);
            }
        }

        // 我是否还接收陌生人消息:读自己的开关。这是「我能否被别人搭话」,
        // 与前面「我能向谁搭话」是两个方向,前端要分开提示
        User me = userMapper.selectById(userId);
        boolean enabled = me == null || Integer.valueOf(1).equals(me.getStrangerMsgEnabled());

        // 行为门槛不满足时提前给出原因,避免前端先显示「还剩 1 条」再被发送打回
        String blockedReason = Boolean.TRUE.equals(isStranger) ? access.strangerChatBlockReason(me) : null;

        return StrangerQuotaVO.builder()
                .perPeerDailyLimit(campus.getPerPeerDailyLimit())
                .remainingPerPeer(remainingPerPeer)
                .isStranger(isStranger)
                .resetAt(now.toLocalDate().plusDays(1).atStartOfDay())
                .strangerMsgEnabled(enabled)
                .initiationBlockedReason(blockedReason)
                .build();
    }

    /** 日期分桶键(yyyyMMdd) */
    private String dayKey(LocalDateTime now) {
        return String.format("%04d%02d%02d", now.getYear(), now.getMonthValue(), now.getDayOfMonth());
    }

    /** 距次日 00:00 的毫秒数,用作 key 的 TTL——保证旧桶自然过期,不需清理任务 */
    private long millisUntilNextDay(LocalDateTime now) {
        return now.toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
                - now.atZone(ZONE).toInstant().toEpochMilli();
    }

    /** 当日 00:00 的毫秒时间戳，作为滑动窗口左边界 */
    private long leftBoundOf(LocalDateTime now) {
        return now.toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli();
    }
}
