package com.campus.social.config;

import com.campus.social.interceptor.AuthInterceptor;
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
 * - 跨域(开发期前后端分离)
 * - 上传目录静态资源映射
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final UploadServiceImpl uploadService;

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    public WebMvcConfig(AuthInterceptor authInterceptor, UploadServiceImpl uploadService) {
        this.authInterceptor = authInterceptor;
        this.uploadService = uploadService;
    }

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
                        "/ws/**"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 上传目录静态资源(已 excludePathPatterns,无需鉴权)
        // 与写盘共用 UploadServiceImpl 启动时锁定的绝对路径,保证读写同目录
        String resolved = uploadService.getAbsUploadRoot().toAbsolutePath().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + resolved + "/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
