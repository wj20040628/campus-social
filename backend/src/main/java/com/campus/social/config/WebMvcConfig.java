package com.campus.social.config;

import com.campus.social.interceptor.AdminInterceptor;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.VerifiedInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置:
 * - 注册 JWT 鉴权拦截器
 * - 注册未认证拦截器与管理员拦截器
 * - 跨域(白名单收窄,TD-01)
 * - 上传目录读链路改由 MediaController 接管(P3):OSS 配好 302 到签名 URL,未配 AK 读本地磁盘
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final VerifiedInterceptor verifiedInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final CorsProperties corsProperties;

    @Value("${upload.dir:./uploads}")
    private String uploadDir;

    public WebMvcConfig(AuthInterceptor authInterceptor,
            VerifiedInterceptor verifiedInterceptor,
            AdminInterceptor adminInterceptor,
            CorsProperties corsProperties) {
        this.authInterceptor = authInterceptor;
        this.verifiedInterceptor = verifiedInterceptor;
        this.adminInterceptor = adminInterceptor;
        this.corsProperties = corsProperties;
    }

    /**
     * 拦截器顺序不可调换：VerifiedInterceptor 与 AdminInterceptor 都读
     * {@code AuthInterceptor.ATTR_USER_ID}，必须排在鉴权拦截器之后。
     * 否则 userId 取不到，未认证拦截会被静默跳过——那正是它要防的越权口子。
     *
     * <p><b>auth 的 order 从「默认值 0」改成显式的 0</b>：原先 auth 没写
     * {@code .order()}，靠InterceptorRegistration 的默认值 0 生效，
     * 而 verified 写的是 2。于是「auth 必须先跑」这条约束是由两个数字的
     * 大小关系偶然维持的——没人知道它是有意的，改配置的人看不出这层依赖。
     * 显式写出来之后，它至少是一条看得见的约定。
     *
     * <p><b>这条约定目前没有任何东西在守</b>：实测把verified 改成
     * {@code order(-100)} 想让它先跑，运行时 auth 仍然先跑（auth 的
     * {@code addPathPatterns("/**")} 覆盖了全部 {@code @RequireVerified} 端点，
     * 它必然短路）。也就是说当前 me == null 分支不可达，是纯防御。
     * 留着的理由不是「它防住了什么」，而是：一旦将来有人给 auth 加了
     * excludePathPatterns（比如放行某个免登录端点）而把某个受保护端点
     * 漏进排除列表，这个分支就是唯一会拦住他的东西。
     * fail-closed 与 fail-open 的差别只在它真的被触发的那个时刻，
     * 而那时没人还有空判断它是不是死代码。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .order(0)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/register",
                        "/auth/login",
                        "/auth/refresh",
                        "/auth/send-code",
                        "/auth/login-code",
                        "/upload/config",
                        // 学校字典:纯只读全局参照数据,注册页(未登录)也要拉院系/年级/专业,
                        // 不放行会在打开注册页时就被 401 踢回登录页(DictController 仅 GET,无越权风险)。
                        "/dict/**",
                        "/uploads/**",
                        // OSS 上传回调:由阿里云 OSS 服务器调用,不带用户 token,必须放行
                        // (否则 auth 拦截器 401,回调失败 → 上传文件无 upload_file 记录)
                        "/upload/oss-callback",
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
