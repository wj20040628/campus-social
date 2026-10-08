package com.campus.social.config;

import com.campus.social.interceptor.AdminInterceptor;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.VerifiedInterceptor;
import com.campus.social.service.impl.UploadServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置:
 * - 注册 JWT 鉴权拦截器
 * - 注册未认证拦截器与管理员拦截器
 * - 跨域(白名单收窄,TD-01)
 * - 上传目录静态资源映射
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final VerifiedInterceptor verifiedInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final UploadServiceImpl uploadService;
    private final CorsProperties corsProperties;

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    public WebMvcConfig(AuthInterceptor authInterceptor,
            VerifiedInterceptor verifiedInterceptor,
            AdminInterceptor adminInterceptor,
            UploadServiceImpl uploadService,
            CorsProperties corsProperties) {
        this.authInterceptor = authInterceptor;
        this.verifiedInterceptor = verifiedInterceptor;
        this.adminInterceptor = adminInterceptor;
        this.uploadService = uploadService;
        this.corsProperties = corsProperties;
    }

    /**
     * 拦截器顺序不可调换：VerifiedInterceptor 与 AdminInterceptor 都读
     * {@code AuthInterceptor.ATTR_USER_ID}，必须排在鉴权拦截器之后。
     * 否则 userId 取不到，未认证拦截会被静默跳过——那正是它要防的越权口子。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/register",
                        "/auth/login",
                        "/auth/refresh",
                        "/auth/send-code",
                        "/auth/login-code",
                        "/uploads/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/health",
                        "/ws/**"
                );
        registry.addInterceptor(verifiedInterceptor)
                .addPathPatterns("/**")
                .order(2);
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**")
                .order(3);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 上传目录静态资源(已 excludePathPatterns,无需鉴权)
        // 与写盘共用 UploadServiceImpl 启动时锁定的绝对路径,保证读写同目录
        String resolved = uploadService.getAbsUploadRoot().toAbsolutePath().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + resolved + "/");
    }

    /**
     * 跨域白名单(TD-01)。
     *
     * <p>改造前是 {@code allowedOriginPatterns("*")}，任意站点都能带 Authorization
     * 打这套 API，是上线阻断项。现改为从 CORS_ALLOWED_ORIGINS 环境变量注入的域名白名单，
     * 未配置时只放行本机开发端口，<b>任何情况下都不回落到通配符</b>。
     *
     * <p>allowCredentials 保持 false：本项目用 Bearer Token 而非 Cookie 会话，
     * 开启 credentials 反而要求 origin 不能用通配符，徒增约束。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(corsProperties.resolveOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Requested-With")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
