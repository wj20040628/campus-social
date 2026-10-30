package com.campus.social.service.support;

import com.campus.social.util.JwtUtil;
import com.campus.social.vo.TokenPairVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * access / refresh 的签发、Redis 指纹轮换与吊销。
 *
 * <p>业务校验（账号状态、封禁、验证码、密码）留在 AuthServiceImpl；
 * 这里只负责 token 机制本身，便于单独测试与后续演进。
 *
 * <p>refresh 指纹设计：key=refresh:{userId} 存当前 jti，TTL 与 JwtUtil 签发的
 * token 有效期严格一致；刷新用 Lua CAS 原子滚动，登出与改密直接删 key。
 */
@Component
@RequiredArgsConstructor
public class TokenService {

    private final StringRedisTemplate redis;

    /** refresh:{userId} = jti */
    private static final String REFRESH_KEY = "refresh:";

    /** 与 JwtConfig 的 access-ttl-millis（900000ms=15min）保持一致 */
    private static final long ACCESS_TTL_SECONDS = 15 * 60;

    private static final RedisScript<Long> REFRESH_CAS_SCRIPT;

    static {
        String lua =
                "local cur = redis.call('GET', KEYS[1])\n" +
                "if cur == false then return -1 end\n" +
                "if cur ~= ARGV[1] then\n" +
                "  redis.call('DEL', KEYS[1])\n" +
                "  return 0\n" +
                "end\n" +
                "redis.call('SET', KEYS[1], ARGV[2], 'EX', tonumber(ARGV[3]))\n" +
                "return 1";
        REFRESH_CAS_SCRIPT = new DefaultRedisScript<>(lua, Long.class);
    }

    /** 待 CAS 的新 refresh：jti 用于比对，token 用于返回给客户端 */
    public record RefreshTicket(String jti, String token) {
    }

    /** 首次签发（注册/登录）：写 Redis 指纹并返回完整 token 对 */
    public TokenPairVO issue(long userId, String username, boolean remember) {
        RefreshTicket ticket = newRefresh(userId, remember);
        redis.opsForValue().set(REFRESH_KEY + userId, ticket.jti(), ttl(remember));
        return pair(userId, username, ticket.token());
    }

    /** 只生成新 refresh（含新 jti），不写 Redis；供 refresh 流程先 CAS 后使用 */
    public RefreshTicket newRefresh(long userId, boolean remember) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        return new RefreshTicket(jti, JwtUtil.issueRefresh(userId, jti, remember));
    }

    /**
     * 校验旧 jti，匹配则滚动写入 newJti。
     *
     * @return 1=滚动成功；0=旧 jti 不匹配（重用攻击，已全吊销）；-1=无记录（自然过期或已登出）
     */
    public int rotate(long userId, String oldJti, String newJti, long ttlSeconds) {
        Long result = redis.execute(
                REFRESH_CAS_SCRIPT,
                List.of(REFRESH_KEY + userId),
                oldJti, newJti, String.valueOf(ttlSeconds));
        if (result == null) {
            return -1;
        }
        return result.intValue();
    }

    /** 吊销该账号的全部 refresh（登出、改密、重置密码、首次设密共用） */
    public void revoke(long userId) {
        redis.delete(REFRESH_KEY + userId);
    }

    /** 签发 access 并组装返回体；refreshToken 由调用方提供（首次签发或滚动后的新 token） */
    public TokenPairVO pair(long userId, String username, String refreshToken) {
        long accessExpireAt = System.currentTimeMillis() / 1000 + ACCESS_TTL_SECONDS;
        return TokenPairVO.builder()
                .accessToken(JwtUtil.issueAccess(userId, username))
                .refreshToken(refreshToken)
                .accessExpireAt(accessExpireAt)
                .build();
    }

    /** Redis 指纹 TTL 秒数，必须与签发 token 的有效期一致 */
    public long ttlSeconds(boolean remember) {
        return ttl(remember).getSeconds();
    }

    private static Duration ttl(boolean remember) {
        long ms = remember ? JwtUtil.getRememberTtlMillis() : JwtUtil.getRefreshTtlMillis();
        return Duration.ofMillis(ms);
    }
}
