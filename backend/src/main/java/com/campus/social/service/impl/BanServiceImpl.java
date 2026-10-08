package com.campus.social.service.impl;

import com.campus.social.mapper.UserMapper;
import com.campus.social.service.BanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 封禁期限管理实现（Redis ZSET 记解封时间）。
 *
 * <p>数据结构：一个 ZSET {@code ban:expiring}，
 * member=userId、score=解封时刻的毫秒时间戳。
 * 用一个集合装所有待解封用户，而不是每人一个 key：
 * {@code BanExpireJob} 每次只需 {@code ZRANGEBYSCORE 0 now} 一次就能取到全部到期者，
 * 若每人一个 key 就得用 KEYS 扫全库（阻塞主线程）或维护一份额外的索引。
 *
 * <p>永久封禁不写入 ZSET——没有到期时间可写，管理员手动解封即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BanServiceImpl implements BanService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 待解封用户 ZSET：member=userId，score=解封毫秒时间戳 */
    private static final String KEY_EXPIRING = "ban:expiring";

    /** user.status=1 冻结 */
    private static final int STATUS_FROZEN = 1;

    /** user.status=0 正常 */
    private static final int STATUS_NORMAL = 0;

    private final StringRedisTemplate redis;
    private final UserMapper userMapper;

    /** 解封计划最长保留天数，够覆盖最长的 30 天封禁并留余量 */
    @Value("${app.ban.max-plan-days:45}")
    private int maxPlanDays;

    @Override
    public LocalDateTime ban(long userId, int banDays) {
        if (banDays <= 0) {
            // 永久封禁：从 ZSET 中移除可能存在的旧计划，避免上一轮限期封禁的
            // score 残留导致 job 把它错误地恢复成正常。
            redis.opsForZSet().remove(KEY_EXPIRING, String.valueOf(userId));
            userMapper.updateAccountStatus(userId, STATUS_FROZEN);
            log.info("user permanently banned: user={}", userId);
            return null;
        }
        long untilMs = System.currentTimeMillis() + Duration.ofDays(banDays).toMillis();
        redis.opsForZSet().add(KEY_EXPIRING, String.valueOf(userId), untilMs);
        // 整个 ZSET 的 TTL 按最长计划给，保证不会在计划到期前 key 就消失。
        redis.expire(KEY_EXPIRING, Duration.ofDays(maxPlanDays));
        userMapper.updateAccountStatus(userId, STATUS_FROZEN);
        LocalDateTime until = LocalDateTime.ofInstant(Instant.ofEpochMilli(untilMs), ZONE);
        log.info("user banned until {}: user={}, days={}", until, userId, banDays);
        return until;
    }

    @Override
    public void unban(long userId) {
        redis.opsForZSet().remove(KEY_EXPIRING, String.valueOf(userId));
        userMapper.updateAccountStatus(userId, STATUS_NORMAL);
        log.info("user unbanned by admin: user={}", userId);
    }

    @Override
    public LocalDateTime bannedUntil(long userId) {
        Double score = redis.opsForZSet().score(KEY_EXPIRING, String.valueOf(userId));
        if (score == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(score.longValue()), ZONE);
    }

    /**
     * 取出已到期的封禁用户（供 job 调用）。
     *
     * <p>返回的是「已到期」集合，调用方负责把他们的 status 恢复为正常。
     * 放在 service 而不放在 job 里，是为了让 job 只做调度、不含业务规则。
     *
     * @return 到期的 userId 集合；无到期时返回空集
     */
    @Override
    public Set<String> findExpired(long nowMs) {
        Set<String> expired = redis.opsForZSet().rangeByScore(KEY_EXPIRING, 0, nowMs);
        return expired == null ? Set.of() : expired;
    }

    /** 移除已处理的封禁计划（job 在恢复 status 后调用） */
    @Override
    public void clearPlan(String userId) {
        redis.opsForZSet().remove(KEY_EXPIRING, userId);
    }
}