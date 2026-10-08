package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.MessageGateway;
import com.campus.social.service.VerifyCodeService;
import com.campus.social.util.RedisLua;
import com.campus.social.vo.TokenPairVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.regex.Pattern;

import static com.campus.social.common.ResultCode.*;

/**
 * 验证码实现:Redis 存码 + 限频 + 按用途分桶
 *
 * key 设计:
 *   code:{type}:{target}      值=6位码, TTL=5min
 *   code:limit:{target}       每 60s 最多 1 次(防刷)
 *   code:daily:{type}:{target}  每账号每日 10 次(防耗尽短信通道)
 *
 * verify() 校验后删除(一次性),防重放
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyCodeServiceImpl implements VerifyCodeService {

    private final StringRedisTemplate redis;
    private final MessageGateway messageGateway;
    private final UserMapper userMapper;
    private final RedisLua redisLua;
    private final SecureRandom random = new SecureRandom();

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RATE_LIMIT = Duration.ofSeconds(60);
    private static final long DAILY_LIMIT = 10;

    /** 合法收件目标:邮箱或中国大陆手机号。非法格式一律在入口拒绝 */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    @Override
    public String send(String target, String type) {
        // 入参格式校验:必须在写 Redis 与调用发信通道之前拦截,
        // 否则任意字符串会成为 Redis key,并被送去真实的短信/邮件通道
        if (target == null
                || (!EMAIL.matcher(target).matches() && !PHONE.matcher(target).matches())) {
            throw new BusinessException(BAD_REQUEST, "请输入正确的手机号或邮箱");
        }

        // 限频:60s 内不能重发。用 SET NX 原子抢占,杜绝 check-then-set 的并发绕过
        String rateKey = "code:limit:" + target;
        Boolean first = redis.opsForValue().setIfAbsent(rateKey, "1", RATE_LIMIT);
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(CODE_TOO_FREQUENT, "发送过于频繁,请 1 分钟后再试");
        }

        // 每日上限
        String dailyKey = "code:daily:" + type + ":" + target;
        Long daily = redis.opsForValue().increment(dailyKey);
        if (daily != null && daily == 1) {
            // 首次计数时设 1d TTL(Lua 式原子:已存在 key 不重置,首建当天有效)
            redis.expire(dailyKey, Duration.ofDays(1));
        }
        if (daily != null && daily > DAILY_LIMIT) {
            throw new BusinessException(CODE_LIMIT_REACHED, "今日验证码发送次数已达上限,请明日再试");
        }

        // 生成 6 位码
        String code = String.valueOf(random.nextInt(900_000) + 100_000);
        String codeKey = "code:" + type + ":" + target;
        redis.opsForValue().set(codeKey, code, CODE_TTL);

        // 发送(失败则清理已生成的码 + 释放限频锁,避免"发了但校验不通过"且锁死 1 分钟)
        boolean ok = messageGateway.sendCode(target, code);
        if (!ok) {
            redis.delete(codeKey);
            redis.delete(rateKey);
            throw new BusinessException(CODE_LIMIT_REACHED.getCode(),
                    "验证码发送失败(通道不可用),请稍后重试");
        }
        log.info("verify code sent: target={}, type={}", target, type);
        return "验证码已发送,5 分钟内有效";
    }

    /**
     * 「原子取出并删除」已收敛到 {@link com.campus.social.util.RedisLua}。
     *
     * <p>搬迁理由：GETDEL 命令自 Redis 6.2 才引入，Redis 5.x 执行会抛
     * {@code ERR unknown command}；Spring Data Redis 3.2 的
     * {@code opsForValue().getAndDelete()} 底层即发 GETDEL。
     * 本项目已在验证码链路上踩过一次（SPEC §11 第 1 条）。
     *
     * <p>ARCHITECTURE.md §6.5 要求「所有原子读删一律走 Lua，集中放 util/RedisLua.java，
     * 禁止散落」，§9.3 进一步裁决把本文件内的内联脚本抽出去。纯搬迁，语义与行为不变。
     */
    @Override
    public void verify(String target, String code, String type) {
        String codeKey = "code:" + type + ":" + target;
        // 原子取出并删除:同一 code 并发多次 verify 只有一次能拿到非空值,杜绝同码双用
        String expected = redisLua.getAndDelete(codeKey);
        if (expected == null || expected.isEmpty()) {
            throw new BusinessException(CODE_INVALID, "验证码已失效,请重新获取");
        }
        if (!expected.equals(code)) {
            throw new BusinessException(CODE_INVALID, "验证码错误");
        }
    }
}
