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

    /**
     * refresh 默认有效期(不勾「记住我」)。账号体系重构 2026-10-17 起改为 3 天,
     * 与登录页「不勾保持 3 天」对齐。旧值 7d 已是历史默认,这里翻转为 3d。
     */
    @Value("${jwt.refresh-ttl-millis:259200000}") // 3d
    private long refreshTtlMillis;

    /**
     * refresh 有效期(勾选「记住我」)。7 天,对应登录页「勾选记住我 7 天」。
     */
    @Value("${jwt.refresh-ttl-remember-millis:604800000}") // 7d
    private long rememberTtlMillis;

    @PostConstruct
    public void init() {
        // 校验密钥长度(HS512 至少 64 字节)
        if (accessSecret.getBytes().length < 64) {
            throw new IllegalStateException("jwt.access-secret 至少 64 字节(96 字符 base64)");
        }
        if (refreshSecret.getBytes().length < 64) {
            throw new IllegalStateException("jwt.refresh-secret 至少 64 字节(96 字符 base64)");
        }
        JwtUtil.init(accessSecret, refreshSecret, accessTtlMillis, refreshTtlMillis, rememberTtlMillis);
        log.info("JwtUtil initialized, accessTtl={}ms, refreshTtl={}ms, rememberTtl={}ms",
                accessTtlMillis, refreshTtlMillis, rememberTtlMillis);
    }
}
