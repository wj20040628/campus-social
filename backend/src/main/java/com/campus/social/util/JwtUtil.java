package com.campus.social.util;

import cn.hutool.crypto.digest.BCrypt;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 双 token 工具
 * access  15min - 走 Header,无状态
 * refresh 默认 3d / 勾选「记住我」7d - 存 Redis (key=refresh:{userId}),一次性可吊销
 *
 * <p>双 TTL(记住我,账号体系重构 2026-10-17):
 * 登录时若勾选「记住我」,refresh 有效期拉长到 {@code rememberTtlMillis}(7d);
 * 否则用 {@code refreshTtlMillis}(3d,与登录页「不勾保持 3 天」对齐)。
 * refresh token 内写入 {@code remember} claim,刷新时按旧 token 的 remember 选同 TTL
 * 重签,否则「记住我」会在首次静默刷新后悄悄失效。
 *
 * 安全原则:
 * - HS512 (HMAC 密钥走配置,不写代码)
 * - refresh 在签发时塞入 jti 作指纹,刷新成功后立刻从 Redis 删
 * - 校验签名+过期
 *
 * jjwt 0.12.x API:
 * - signWith(key):按 key 类型自动推断算法(Keys.hmacShaKeyFor → HS512)
 * - parseSignedClaims(token):取代旧版 parseClaimsJws(后者已 deprecated)
 */
public final class JwtUtil {

    public static final String HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * 未设置密码的占位哈希。
     *
     * <p>邮箱验证码自动建号不要求用户先设密码，但 {@code user.password_hash}
     * 是 NOT NULL。存一个不可能成为 BCrypt 哈希的哨兵值，比存随机哈希更明确：
     * {@link #isPasswordSet} 一眼能判出来，且 {@link #verifyPassword} 会直接返回 false，
     * 不会把哨兵值喂给 BCrypt 触发 {@code Invalid salt version}。
     */
    public static final String PASSWORD_UNSET = "__NO_PASSWORD__";

    private static SecretKey accessKey;
    private static SecretKey refreshKey;

    private static long accessTtlMillis;
    private static long refreshTtlMillis;
    private static long rememberTtlMillis;

    /** Spring 启动时由 JwtConfig 注入 */
    public static void init(String accessSecret, String refreshSecret,
                            long accessTtlMillis, long refreshTtlMillis,
                            long rememberTtlMillis) {
        accessKey = Keys.hmacShaKeyFor(accessSecret.getBytes(StandardCharsets.UTF_8));
        refreshKey = Keys.hmacShaKeyFor(refreshSecret.getBytes(StandardCharsets.UTF_8));
        JwtUtil.accessTtlMillis = accessTtlMillis;
        JwtUtil.refreshTtlMillis = refreshTtlMillis;
        JwtUtil.rememberTtlMillis = rememberTtlMillis;
    }

    /** 签发 access token */
    public static String issueAccess(long userId, String username) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("typ", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTtlMillis))
                .signWith(accessKey)   // 0.12 自动按 key 推断 HS512
                .compact();
    }

    /** 签发 refresh token(带 jti 指纹,便于吊销) */
    public static String issueRefresh(long userId, String jti) {
        return issueRefresh(userId, jti, false);
    }

    /**
     * 签发 refresh token(带 jti 指纹,便于吊销)。
     *
     * @param remember 是否「记住我」:true → 有效期用 {@code rememberTtlMillis}(7d),
     *                  false → 用 {@code refreshTtlMillis}(3d)
     */
    public static String issueRefresh(long userId, String jti, boolean remember) {
        long ttl = remember ? rememberTtlMillis : refreshTtlMillis;
        return Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim("typ", "refresh")
                .claim("remember", remember)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttl))
                .signWith(refreshKey)  // 0.12 自动按 key 推断 HS512
                .compact();
    }

    /** 解析+校验 access,失败返回 null */
    public static Claims parseAccess(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(accessKey)
                    .build()
                    .parseSignedClaims(token);
            Claims c = jws.getPayload();
            if (!"access".equals(c.get("typ", String.class))) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析+校验 refresh,失败返回 null */
    public static Claims parseRefresh(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(refreshKey)
                    .build()
                    .parseSignedClaims(token);
            Claims c = jws.getPayload();
            if (!"refresh".equals(c.get("typ", String.class))) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从已解析的 refresh claims 读回「记住我」标记。
     *
     * <p>刷新时据此选同 TTL 重签,保证「记住我」在静默刷新后仍然有效。
     * 老 token(升级前签发,无此 claim)读不到 → 默认 false(3d),行为向后兼容。
     */
    public static boolean parseRemember(Claims claims) {
        if (claims == null) {
            return false;
        }
        Boolean r = claims.get("remember", Boolean.class);
        return Boolean.TRUE.equals(r);
    }

    /**
     * 密码哈希。
     *
     * <p><b>cost 为什么是 10 而不是 12</b>：BCrypt 的 cost 每加 1 就翻倍计算量。
     * cost=12 单次约 300ms 纯 CPU，2 核服务器每秒只能处理 5-6 次登录，
     * 中午下课集中登录时前面的人就把 CPU 占满，后面全在排队。
     * cost=10 单次约 70-80ms，快 4 倍，仍是 OWASP 建议的下限之上。
     *
     * <p><b>降低 cost 不需要迁移存量密码</b>：BCrypt 把 cost 写在哈希串里
     * （形如 {@code $2a$12$...}），校验时 {@link #verifyPassword} 读的是
     * 这个串自带的 cost，不是这里的常量。所以老用户用 12 建的密码照样能登录，
     * 只有新注册和改密码才会用 10。想全量升级只能等用户下次改密码，
     * 这是 BCrypt 的固有限制，不是实现缺陷。
     */
    public static String hashPassword(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt(10));
    }

    /** 账号是否已经真正设过密码（哨兵值 / null 都算未设置） */
    public static boolean isPasswordSet(String hashed) {
        return hashed != null && !hashed.isBlank() && !PASSWORD_UNSET.equals(hashed);
    }

    public static boolean verifyPassword(String raw, String hashed) {
        if (!isPasswordSet(hashed)) {
            return false;
        }
        return BCrypt.checkpw(raw, hashed);
    }

    /** 默认 refresh TTL(毫秒),供调用方对齐 Redis key 的过期时间 */
    public static long getRefreshTtlMillis() {
        return refreshTtlMillis;
    }

    /** 「记住我」refresh TTL(毫秒),供调用方对齐 Redis key 的过期时间 */
    public static long getRememberTtlMillis() {
        return rememberTtlMillis;
    }

    private JwtUtil() {
    }
}
