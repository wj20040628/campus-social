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
    /** 每日发送上限:账号体系重构后邮箱验证码登录 + 找回密码都走邮件,
     *  上限从 10 提到 20(仍远小于阿里云后台 20 封/日的硬配额,双重保险)。 */
    private static final long DAILY_LIMIT = 20;

    /** 合法收件目标:邮箱或中国大陆手机号。非法格式一律在入口拒绝 */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    @Override
    public String send(String target, String type) {
        // 入参格式校验:必须在写 Redis 与调用发信通道之前拦截,
        // 否则任意字符串会成为 Redis key,并被送去真实的短信/邮件通道
        if (target == null) {
            throw new BusinessException(BAD_REQUEST, "请输入正确的手机号或邮箱");
        }
        target = target.trim();
        boolean isEmail = EMAIL.matcher(target).matches();
        boolean isPhone = PHONE.matcher(target).matches();
        if (!isEmail && !isPhone) {
            throw new BusinessException(BAD_REQUEST, "请输入正确的手机号或邮箱");
        }
        // 邮箱统一小写:Redis key / 比对 / 发信目标都走小写,
        // 防 AbC@x.com 与 abc@x.com 被当成两个收件人、还能各自发码绕过日额。
        // 手机无大小写,原样。
        String norm = isEmail ? target.toLowerCase() : target;

        // 硬约束 1(type=login 邮箱验证码登录):仅「已绑定安全凭证邮箱」的用户可用。
        // 未绑定 → 直接拒绝,不发码、不计日额。这是后端强制兜底,
        // 前端即便把 Tab2 暴露给未绑定用户也无法真正发码。
        if ("login".equals(type)) {
            User bound = userMapper.selectByEmail(norm);
            if (bound == null) {
                throw new BusinessException(EMAIL_NOT_REGISTERED, "该邮箱尚未绑定账号,无法用于验证码登录");
            }
        }

        // 硬约束 2(复用未过期验证码):已有一条有效码则直接复用,
        // 不重新生成、不重发邮件、不计日额。邮箱通道消耗极小(见账号体系重构方案)。
        String codeKey = "code:" + type + ":" + norm;
        String existing = redis.opsForValue().get(codeKey);
        if (existing != null && !existing.isEmpty()) {
            log.info("verify code reused(not resent): target={}, type={}", norm, type);
            return "验证码已发送,5 分钟内有效";
        }

        // 限频:60s 内不能重发新码。SET NX 原子抢占,杜绝 check-then-set 的并发绕过
        // (硬约束 3 之 60s 单邮箱限频)
        String rateKey = "code:limit:" + norm;
        Boolean first = redis.opsForValue().setIfAbsent(rateKey, "1", RATE_LIMIT);
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(CODE_TOO_FREQUENT, "发送过于频繁,请 1 分钟后再试");
        }

        // 每日上限(硬约束 3 之每日 20 封)
        String dailyKey = "code:daily:" + type + ":" + norm;
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
        redis.opsForValue().set(codeKey, code, CODE_TTL);

        // 发送(失败则清理已生成的码 + 释放限频锁,避免"发了但校验不通过"且锁死 1 分钟)
        boolean ok = messageGateway.sendCode(norm, code);
        if (!ok) {
            redis.delete(codeKey);
            redis.delete(rateKey);
            throw new BusinessException(CODE_LIMIT_REACHED.getCode(),
                    "验证码发送失败(通道不可用),请稍后重试");
        }
        log.info("verify code sent: target={}, type={}", norm, type);
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
        // 与 send 同口径:邮箱统一小写后再拼 key,否则 AbC@x.com 存的码用 abc@x.com 取不到
        if (target == null) {
            throw new BusinessException(CODE_INVALID, "验证码已失效,请重新获取");
        }
        String norm = target.trim();
        if (EMAIL.matcher(norm).matches()) {
            norm = norm.toLowerCase();
        }
        String codeKey = "code:" + type + ":" + norm;
        /*
         * 先比对、命中才删(2026-10-23 改,审查 P1-25):
         * 旧写法是"原子取出并删除"——错一次就销毁真码。攻击者循环
         * POST /auth/login-code {email: victim, code: "000000"} 即可持续毁掉
         * 受害者的真码,叠加 60s 发码限频,受害者邮箱登录/改密长期不可用。
         *
         * 新顺序:GET(不删) → 比对 → 一致才 DEL(原子读删,防同码双用)。
         * 不一致时只累计失败次数,超过 5 次才 DEL 真码(防暴力枚举 6 位码)。
         * 失败计数 key 与真码同 TTL,自然过期。
         */
        String expected = redis.opsForValue().get(codeKey);
        if (expected == null || expected.isEmpty()) {
            throw new BusinessException(CODE_INVALID, "验证码已失效,请重新获取");
        }
        if (!expected.equals(code)) {
            String failKey = "code:fail:" + type + ":" + norm;
            Long fails = redis.opsForValue().increment(failKey);
            if (fails != null && fails == 1L) {
                // 首次失败时给计数键一个与真码同量级的过期时间,避免永久占位
                redis.expire(failKey, CODE_TTL);
            }
            if (fails != null && fails >= 5) {
                // 连续 5 次错 → 作废本次码,逼用户重新获取(此时真正的码已被锁死)
                redis.delete(codeKey);
                redis.delete(failKey);
                throw new BusinessException(CODE_INVALID, "错误次数过多,请重新获取验证码");
            }
            throw new BusinessException(CODE_INVALID, "验证码错误");
        }
        // 命中:原子读删,杜绝同一码并发双用
        redisLua.getAndDelete(codeKey);
        redis.delete("code:fail:" + type + ":" + norm);
    }
}
