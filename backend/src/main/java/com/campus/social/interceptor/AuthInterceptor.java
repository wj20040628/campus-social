package com.campus.social.interceptor;

import com.campus.social.common.Result;
import com.campus.social.common.ResultCode;
import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Set;

/**
 * HTTP JWT 鉴权拦截器
 * - 校验 Authorization: Bearer <access>
 * - 白名单路径放行
 * - 通过后把 userId 塞到 request attribute,供后续 Controller 读取
 * - 校验账号状态(冻结/注销),见下方「封禁横切」说明
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 这些路径不需要登录(精确路径,无前缀) */
    private static final Set<String> WHITE_LIST = Set.of(
            "/auth/register",
            "/auth/login",
            "/auth/refresh",
            "/auth/send-code",
            "/auth/login-code",
            // 学号认证申诉(2026-10-19):提交方是「注册失败、还没账号」的人,
            // 没有 token,必须放行;联系邮箱随申诉落库,管理员据此联系。
            "/auth/appeal-student-no",
            // 上传能力探测:前端进发帖页前拉一次,判断视频功能开没开 + 单文件上限。
            // 只读、无副作用,不绑登录态(未登录也能知道「视频被关了」)。
            "/upload/config",
            // 学校字典(院系/年级/专业级联):纯只读全局参照数据,注册页和引导页都要用,
            // 而注册页是未登录态 —— 不放行会在打开注册页时就被 401 踢回登录页。
            // 见 WebMvcConfig.excludePathPatterns 同步放行。
            "/dict/school",
            "/dict/majors"
    );

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";

    /** 账号状态缓存键前缀:user:status:{id};值 = status 数字 */
    private static final String STATUS_KEY_PREFIX = "user:status:";
    /** 缓存 TTL 60s:封禁生效最多延迟 1 分钟,远好于「直到 token 过期(15min~7d)」 */
    private static final Duration STATUS_TTL = Duration.ofSeconds(60);

    private final UserMapper userMapper;
    private final StringRedisTemplate redis;

    public AuthInterceptor(UserMapper userMapper, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS(CORS 预检)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().replace(request.getContextPath(), "");
        if (WHITE_LIST.contains(path)) {
            return true;
        }

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauth(response, "缺少 Authorization header");
            return false;
        }
        String token = auth.substring(7).trim();
        Claims claims = com.campus.social.util.JwtUtil.parseAccess(token);
        if (claims == null) {
            writeUnauth(response, "token 无效或已过期");
            return false;
        }
        Long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            writeUnauth(response, "token 损坏");
            return false;
        }

        /*
         * 封禁横切(2026-10-23 加,审查 P1-2):
         * 之前只在 3 条写路径(发帖/私聊/登录刷新)检查 user.status,
         * 被封禁的用户仍可在 access token 有效期内(15min,最长 7d)继续
         * 评论/点赞/收藏/关注/上传。
         *
         * 这里统一兜住:token 验签后查账号状态,冻结(1)/注销(2)一律拒绝。
         * 为免每个请求一次 SELECT,状态写 Redis 缓存(TTL 60s):
         *   - 封禁/解封/注销时由 BanServiceImpl 主动删缓存 → 立即生效;
         *   - 缓存缺失才回源查库并回填;
         *   - 查库失败按「放行」处理(fail-open),不让数据库抖动把全站打成 401 ——
         *     写路径仍有各自的二次校验(P1-2 修复前的既有检查)。
         */
        String key = STATUS_KEY_PREFIX + userId;
        String cached = redis.opsForValue().get(key);
        if (cached == null) {
            User u = userMapper.selectById(userId);
            if (u != null) {
                cached = String.valueOf(u.getStatus());
                redis.opsForValue().set(key, cached, STATUS_TTL);
            }
        }
        if ("1".equals(cached)) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    ResultCode.USER_FROZEN.getCode(), "账号已被冻结,请联系管理员");
            return false;
        }
        if ("2".equals(cached)) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    ResultCode.USER_CANCELLED.getCode(), "账号已注销");
            return false;
        }

        request.setAttribute(ATTR_USER_ID, userId);
        request.setAttribute(ATTR_USERNAME, claims.get("username"));
        return true;
    }

    private void writeUnauth(HttpServletResponse response, String msg) throws java.io.IOException {
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                ResultCode.UNAUTHORIZED.getCode(), msg);
    }

    private void writeJson(HttpServletResponse response, int status, int code, String msg)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(Result.fail(code, msg))
        );
    }
}
