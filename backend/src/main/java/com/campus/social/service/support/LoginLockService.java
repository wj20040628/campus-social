package com.campus.social.service.support;

import com.campus.social.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.campus.social.common.ResultCode.TOO_MANY_REQUESTS;

/**
 * 登录失败锁定：账号维度 5 次 / IP 维度 30 次，窗口 15 分钟。
 *
 * <p>账号键只在「账号存在且密码错误」时递增；账号不存在的失败只记 IP 键，
 * 避免不存在的账号名被滥用去误锁真实用户。
 */
@Component
@RequiredArgsConstructor
public class LoginLockService {

    private static final String ACCOUNT_KEY = "login:fail:u:";
    private static final String IP_KEY = "login:fail:ip:";
    private static final int ACCOUNT_LIMIT = 5;
    private static final int IP_LIMIT = 30;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public void checkAccount(String identifier) {
        check(ACCOUNT_KEY + identifier, ACCOUNT_LIMIT, "该账号失败次数过多,请 15 分钟后再试");
    }

    public void checkIp(String ip) {
        check(IP_KEY + ipKey(ip), IP_LIMIT, "当前网络登录失败过多,请 15 分钟后再试");
    }

    public void recordAccountFailure(String identifier) {
        record(ACCOUNT_KEY + identifier);
    }

    public void recordIpFailure(String ip) {
        record(IP_KEY + ipKey(ip));
    }

    /** 登录成功即解锁该账号 */
    public void clearAccount(String identifier) {
        redis.delete(ACCOUNT_KEY + identifier);
    }

    private void check(String key, int limit, String message) {
        String v = redis.opsForValue().get(key);
        if (v != null && Long.parseLong(v) >= limit) {
            throw new BusinessException(TOO_MANY_REQUESTS, message);
        }
    }

    private void record(String key) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) {
            redis.expire(key, WINDOW);
        }
    }

    private static String ipKey(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }
}
