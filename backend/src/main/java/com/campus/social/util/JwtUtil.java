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
 * refresh 7d    - 存 Redis (key=refresh:{userId}),一次性可吊销
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

    private static SecretKey accessKey;
    private static SecretKey refreshKey;

    private static long accessTtlMillis;
    private static long refreshTtlMillis;

    /** Spring 启动时由 JwtConfig 注入 */
    public static void init(String accessSecret, String refreshSecret,
                            long accessTtlMillis, long refreshTtlMillis) {
        accessKey = Keys.hmacShaKeyFor(accessSecret.getBytes(StandardCharsets.UTF_8));
        refreshKey = Keys.hmacShaKeyFor(refreshSecret.getBytes(StandardCharsets.UTF_8));
        JwtUtil.accessTtlMillis = accessTtlMillis;
        JwtUtil.refreshTtlMillis = refreshTtlMillis;
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
        return Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim("typ", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTtlMillis))
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

    /** 密码哈希 */
    public static String hashPassword(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt(12));
    }

    public static boolean verifyPassword(String raw, String hashed) {
        return BCrypt.checkpw(raw, hashed);
    }

    private JwtUtil() {
    }
}
