package com.campus.social.config;

import com.campus.social.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * JWT 初始化（启动时读配置注入 JwtUtil）
 */
@Slf4j
@Configuration
public class JwtConfig {

    @Value("${jwt.access-secret}")
    private String accessSecret;

    @Value("${jwt.refresh-secret}")
    private String refreshSecret;

    @Value("${jwt.access-ttl-millis:900000}")     // 15min
    private long accessTtlMillis;

    @Value("${jwt.refresh-ttl-millis:604800000}") // 7d
    private long refreshTtlMillis;

    @PostConstruct
    public void init() {
        // 校验密钥长度(HS512 至少 64 字节)
        if (accessSecret.getBytes().length < 64) {
            throw new IllegalStateException("jwt.access-secret 至少 64 字节(96 字符 base64)");
        }
        if (refreshSecret.getBytes().length < 64) {
            throw new IllegalStateException("jwt.refresh-secret 至少 64 字节(96 字符 base64)");
        }
        JwtUtil.init(accessSecret, refreshSecret, accessTtlMillis, refreshTtlMillis);
        log.info("JwtUtil initialized, accessTtl={}ms, refreshTtl={}ms", accessTtlMillis, refreshTtlMillis);
    }
}
