package com.campus.social.util;

import com.campus.social.common.ResultCode;
import com.campus.social.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 集市活动限流（发布 / 留言 / 重激活），复用既有 {@link RedisLua} 滑动窗口，
 * 不新引 bucket4j/redisson。
 *
 * <p><b>短窗口、非按日分桶</b>（区别于 {@code StrangerQuotaServiceImpl} 的自然日配额）：
 * 窗口是「最近 N 分钟」的滚动区间，key 用固定业务前缀 + userId（重激活用 activityId），
 * 左边界 = nowMs - windowMs，TTL = windowMs，旧窗自然过期、无清理任务。
 *
 * <p>超限抛 {@code BusinessException(TOO_MANY_REQUESTS)}（429），与 {@code ResultCode} 约定一致。
 *
 * <p><b>member 每次唯一</b>（{@code nowMs-随机}）：同一窗口内多次操作各计一次。
 * 这里没有「同 peer 去重」语义（配额类才有），故不复用 peerId 作 member。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MktRateLimiter {

    private static final String PREFIX = "mkt:";

    /** 发布：同账号 5 分钟内最多 3 条活动。 */
    public static final int PUBLISH_WINDOW_MS = 5 * 60 * 1000;
    public static final int PUBLISH_LIMIT = 3;

    /** 留言：同账号 1 分钟内最多 2 条。 */
    public static final int COMMENT_WINDOW_MS = 60 * 1000;
    public static final int COMMENT_LIMIT = 2;

    /** 重激活：同一条活动 24 小时内最多 3 次。 */
    public static final int REACTIVATE_WINDOW_MS = 24 * 60 * 60 * 1000;
    public static final int REACTIVATE_LIMIT = 3;

    private final RedisLua redisLua;

    /** 发布活动前调用。 */
    public void guardPublish(long userId) {
        check("publish", userId, PUBLISH_WINDOW_MS, PUBLISH_LIMIT,
                "发布活动过于频繁，请稍后再试");
    }

    /** 发留言前调用。 */
    public void guardComment(long userId) {
        check("comment", userId, COMMENT_WINDOW_MS, COMMENT_LIMIT,
                "留言过于频繁，请稍后再试");
    }

    /** 重新激活前调用（按活动维度限流，24h/3 次）。 */
    public void guardReactivate(long activityId) {
        check("reactivate", activityId, REACTIVATE_WINDOW_MS, REACTIVATE_LIMIT,
                "24 小时内重新激活次数已达上限，请稍后再试");
    }

    private void check(String kind, long subjectId, int windowMs, int limit, String message) {
        String key = PREFIX + kind + ":" + subjectId;
        long nowMs = System.currentTimeMillis();
        long leftBound = nowMs - windowMs;
        String member = nowMs + "-" + ThreadLocalRandom.current().nextLong(1_000_000L);
        boolean ok = redisLua.tryAcquire(key, leftBound, limit, nowMs, member, windowMs);
        if (!ok) {
            log.info("mkt rate limit hit: kind={}, subject={}", kind, subjectId);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, message);
        }
    }
}
