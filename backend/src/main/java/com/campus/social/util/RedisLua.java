package com.campus.social.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 原子操作集中管理（Redis 5.0.14.1 兼容）。
 *
 * <p><b>为什么必须集中在这里</b>：本项目已在验证码链路上踩过一次坑——
 * {@code opsForValue().getAndDelete()} 底层发 GETDEL，Redis 5.0 抛
 * {@code ERR unknown command}，验证码全链路炸掉（SPEC §11 第 1 条）。
 * 6.2/6.0/7.0 新增指令在 5.0 上全部不可用，故所有原子读删一律走 Lua，
 * 且脚本只允许写在本文件，业务类不得内联。
 *
 * <p><b>禁用清单</b>（见 ARCHITECTURE.md §6.5）：{@code getAndDelete} /
 * {@code getAndExpire} / {@code randomMember} / {@code copy} /
 * {@code expire(key,ttl,ExpiryPolicy.*)}。
 *
 * <p>脚本模板出处：ARCHITECTURE.md §6.5。
 */
@Component
public class RedisLua {

    private final StringRedisTemplate redis;

    public RedisLua(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ---------------------------------------------------------------- 脚本 1

    /**
     * 脚本 1：GETDEL 替代 —— 原子取出并删除。
     * 语义与 Redis 6.2 的 GETDEL 完全一致，并发下只有一个调用方能拿到非空值。
     */
    private static final RedisScript<String> GET_AND_DELETE = RedisScript.of(
            "local v = redis.call('GET', KEYS[1]) "
                    + "if v then redis.call('DEL', KEYS[1]) return v else return '' end",
            String.class);

    // ---------------------------------------------------------------- 脚本 2

    /** 脚本 2：GETEX 替代 —— 取出并续期（Redis 6.2 GETEX 的等价实现） */
    private static final RedisScript<String> GET_AND_EXPIRE = RedisScript.of(
            "local v = redis.call('GET', KEYS[1]) "
                    + "if v then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return v",
            String.class);

    // ---------------------------------------------------------------- 脚本 3

    /** 脚本 3：SET KEEPTTL 替代 —— 覆盖写但保留原 TTL（Redis 6.0 SET KEEPTTL 的等价实现） */
    private static final RedisScript<Long> SET_KEEP_TTL = RedisScript.of(
            "local ttl = redis.call('PTTL', KEYS[1]) "
                    + "redis.call('SET', KEYS[1], ARGV[1]) "
                    + "if ttl > 0 then redis.call('PEXPIRE', KEYS[1], ttl) end "
                    + "return 1",
            Long.class);

    // ---------------------------------------------------------------- 脚本 4

    /**
     * 脚本 4：滑动窗口限频（F3 陌生人日配额的核心）。
     * KEYS[1]=窗口键 ARGV[1]=窗口左边界毫秒 ARGV[2]=上限
     * ARGV[3]=当前毫秒 ARGV[4]=成员 ARGV[5]=窗口毫秒
     * <p>返回 1=放行并已记账，0=超限。
     * <p>必须原样落地：ZREMRANGEBYSCORE + ZCARD + ZADD 三步在 Lua 内原子完成，
     * 拆成三条命令会有并发击穿窗口的窗口期。
     */
    private static final RedisScript<Long> SLIDING_WINDOW = RedisScript.of(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1]) "
                    + "local n = redis.call('ZCARD', KEYS[1]) "
                    + "if n < tonumber(ARGV[2]) then "
                    + "  redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4]) "
                    + "  redis.call('PEXPIRE', KEYS[1], ARGV[5]) "
                    + "  return 1 "
                    + "end "
                    + "return 0",
            Long.class);

    // ---------------------------------------------------------------- 只读计数

    /**
     * 只读计数：当前窗口内已有多少成员（ZRANGEBYSCORE，Redis 2.0 起可用）。
     * 只用于展示「今日已用 N 次」，<b>不</b>做记账，故不需要 Lua 原子性。
     */
    private static final RedisScript<Long> WINDOW_COUNT = RedisScript.of(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1]) "
                    + "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    // ---------------------------------------------------------------- 对外 API

    /** 原子取出并删除；键不存在返回空字符串 */
    public String getAndDelete(String key) {
        String v = redis.execute(GET_AND_DELETE, List.of(key));
        return v == null ? "" : v;
    }

    /** 原子取出并续期；键不存在返回空字符串 */
    public String getAndExpire(String key, Duration ttl) {
        String v = redis.execute(GET_AND_EXPIRE, List.of(key), String.valueOf(ttl.getSeconds()));
        return v == null ? "" : v;
    }

    /** 覆盖写并保留原 TTL */
    public boolean setKeepTtl(String key, String value) {
        Long r = redis.execute(SET_KEEP_TTL, List.of(key), value);
        return r != null && r > 0;
    }

    /**
     * 滑动窗口限频记账：未超限则记一次并返回 true，超限返回 false。
     *
     * @param windowKey   窗口键（建议带业务前缀与用户 id）
     * @param leftBoundMs 窗口左边界（毫秒时间戳，早于它的成员被剔除）
     * @param limit       窗口内上限
     * @param nowMs       当前毫秒时间戳
     * @param member      成员标识（同一 member 重复 ZADD 只更新 score，不重复计数）
     * @param windowMs    窗口长度（毫秒），同时作为键的 TTL
     */
    public boolean tryAcquire(String windowKey, long leftBoundMs, int limit,
            long nowMs, String member, long windowMs) {
        Long r = redis.execute(SLIDING_WINDOW, List.of(windowKey),
                String.valueOf(leftBoundMs),
                String.valueOf(limit),
                String.valueOf(nowMs),
                member,
                String.valueOf(windowMs));
        return r != null && r == 1L;
    }

    /** 只读：窗口内当前成员数（先剔除窗口外成员再计数，与 tryAcquire 口径一致） */
    public long windowCount(String windowKey, long leftBoundMs) {
        Long r = redis.execute(WINDOW_COUNT, List.of(windowKey), String.valueOf(leftBoundMs));
        return r == null ? 0L : r;
    }
}
