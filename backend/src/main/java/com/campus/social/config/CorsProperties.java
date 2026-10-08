package com.campus.social.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 跨域与 WebSocket 允许来源白名单（TD-01 / TD-02，上线阻断项）。
 *
 * <p>改造前是 {@code allowedOriginPatterns("*")} 与 {@code setAllowedOrigins("*")}，
 * 是「dev 方便留的后门」。生产环境全开等于任意站点可读用户数据，
 * 故改为从环境变量注入的显式域名白名单。
 *
 * <p>读取顺序：环境变量 {@code CORS_ALLOWED_ORIGINS}（逗号分隔）优先，
 * 为空则回落到 {@code app.cors.allowed-origins} 配置项；
 * 两者都为空时只保留 localhost——<b>不回落到通配符</b>。
 * 通配符一旦被重新引入，本类的存在即失去意义。
 */
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** 显式白名单；为空时用 {@link #resolveOrigins()} 的 localhost 兜底 */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 本地开发默认放行的来源，避免每次起服务都要配环境变量 */
    private List<String> localhostOrigins = List.of(
            "http://localhost:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:5174"
    );

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getLocalhostOrigins() {
        return localhostOrigins;
    }

    public void setLocalhostOrigins(List<String> localhostOrigins) {
        this.localhostOrigins = localhostOrigins;
    }

    /**
     * 解析最终生效的白名单：环境变量 > 配置项 > localhost 兜底。
     * 任一环节都不会产出 {@code *}。
     */
    public List<String> resolveOrigins() {
        String env = System.getenv("CORS_ALLOWED_ORIGINS");
        List<String> result = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            Arrays.stream(env.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !"*".equals(s))
                    .forEach(result::add);
        }
        if (result.isEmpty() && allowedOrigins != null) {
            allowedOrigins.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !"*".equals(s))
                    .forEach(result::add);
        }
        if (result.isEmpty()) {
            result.addAll(localhostOrigins);
        }
        return result;
    }
}
