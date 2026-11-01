package com.campus.social.interceptor;

import com.campus.social.common.Result;
import com.campus.social.common.ResultCode;
import com.campus.social.util.RedisLua;
import com.campus.social.util.RequestIp;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 写接口频率限制（用户 + IP 双维度）。
 *
 * <p>只覆盖内容写入与举报类接口。以下路径已有各自机制，不在这里重复限：
 * 登录/验证码（失败锁定与发码冷却）、集市发布/留言（{@code MktRateLimiter}）、
 * 文件上传（每日配额与单文件上限）。
 *
 * <p>Redis 异常时 fail-open：限流是抗滥用措施，不应让缓存抖动把所有写操作打成失败；
 * 写路径自身的权限、禁言、配额校验仍然生效。
 */
@Slf4j
@Component
public class WriteRateLimitInterceptor implements HandlerInterceptor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final String PREFIX = "rl:w:";

    private record Rule(String action, String method, String pattern,
            long windowMs, int userLimit, int ipLimit, String message) {
    }

    /**
     * IP 上限显著高于用户上限：校园网常共用出口 IP，按用户限制才是主约束，
     * IP 维度只用于拦住「同一出口批量注册小号」这类绕过。
     */
    private static final List<Rule> RULES = List.of(
            new Rule("post-create", "POST", "/post", 60_000, 10, 60,
                    "发帖太频繁，请稍后再试"),
            new Rule("post-edit", "PUT", "/post/*", 60_000, 20, 120,
                    "编辑太频繁，请稍后再试"),
            new Rule("comment-create", "POST", "/post/*/comment", 60_000, 20, 200,
                    "评论太频繁，请稍后再试"),
            new Rule("chat-send", "POST", "/chat/send", 60_000, 30, 300,
                    "发消息太频繁，请稍后再试"),
            new Rule("chat-send", "POST", "/chat/message", 60_000, 30, 300,
                    "发消息太频繁，请稍后再试"),
            new Rule("group-message", "POST", "/group/*/message", 60_000, 30, 300,
                    "群消息发得太频繁，请稍后再试"),
            new Rule("report-submit", "POST", "/report", 3_600_000, 10, 100,
                    "举报太频繁，请稍后再试"),
            new Rule("feedback-submit", "POST", "/feedback", 3_600_000, 5, 50,
                    "反馈提交太频繁，请稍后再试"),
            new Rule("relation-follow", "POST", "/relation/follow", 60_000, 30, 200,
                    "关注操作太频繁，请稍后再试"),
            new Rule("relation-blacklist", "POST", "/relation/blacklist", 60_000, 30, 200,
                    "拉黑操作太频繁，请稍后再试"),
            new Rule("group-create", "POST", "/group", 3_600_000, 5, 30,
                    "创建群聊太频繁，请稍后再试"),
            new Rule("circle-create", "POST", "/circle", 3_600_000, 5, 30,
                    "创建圈子太频繁，请稍后再试")
    );

    private final RedisLua redisLua;
    private final MeterRegistry meterRegistry;

    public WriteRateLimitInterceptor(RedisLua redisLua, MeterRegistry meterRegistry) {
        this.redisLua = redisLua;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Rule rule = match(request.getMethod(), pathOf(request));
        if (rule == null) {
            return true;
        }
        Object attr = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (!(attr instanceof Long userId)) {
            // 未登录请求由 AuthInterceptor 先行 401，这里不重复处理
            return true;
        }
        String ip = RequestIp.of(request);
        if (ip == null || ip.isBlank()) {
            ip = "unknown";
        }
        long nowMs = System.currentTimeMillis();
        String member = nowMs + "-" + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        String userKey = PREFIX + rule.action() + ":u:" + userId;
        String ipKey = PREFIX + rule.action() + ":ip:" + ip;
        boolean allowed;
        try {
            allowed = redisLua.tryAcquireBoth(userKey, rule.userLimit(),
                    ipKey, rule.ipLimit(), nowMs, rule.windowMs(), member);
        } catch (Exception e) {
            meterRegistry.counter("campus.rate_limit.fail_open", "action", rule.action()).increment();
            log.warn("write rate limit unavailable, fail-open: action={}, userId={}",
                    rule.action(), userId, e);
            return true;
        }
        if (allowed) {
            return true;
        }
        meterRegistry.counter("campus.rate_limit.blocked", "action", rule.action()).increment();
        log.info("write rate limit hit: action={}, userId={}, ip={}",
                rule.action(), userId, ip);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(Math.max(1, rule.windowMs() / 1000)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(
                Result.fail(ResultCode.TOO_MANY_REQUESTS.getCode(), rule.message())));
        return false;
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().replace(request.getContextPath(), "");
    }

    private static Rule match(String method, String path) {
        for (Rule rule : RULES) {
            if (rule.method().equalsIgnoreCase(method) && MATCHER.match(rule.pattern(), path)) {
                return rule;
            }
        }
        return null;
    }
}
