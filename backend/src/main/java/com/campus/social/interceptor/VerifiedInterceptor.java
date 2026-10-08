package com.campus.social.interceptor;

import com.campus.social.common.Result;
import com.campus.social.common.ResultCode;
import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 未认证拦截器（AC-11）。
 *
 * <p>只对打了 {@link RequireVerified} 的端点生效。执行顺序必须在
 * {@link AuthInterceptor} 之后，因为要读它写入的 {@code ATTR_USER_ID}。
 *
 * <p>为什么用拦截器而不是 AOP 切面：
 * <ol>
 *   <li>拦截器能直接写 HTTP 403 并短路；AOP 抛异常后还要 GlobalExceptionHandler 转一次，多一跳</li>
 *   <li>{@code preHandle} 的 handler 参数直接是 HandlerMethod，取注解零成本；
 *       AOP 要维护切点表达式的包名字符串，重构时易失效</li>
 *   <li>与现有 AuthInterceptor 同构，团队已有心智模型</li>
 *   <li>当前 pom.xml 没有 spring-boot-starter-aop，不引入新依赖</li>
 * </ol>
 *
 * <p>口径说明：
 * <ul>
 *   <li><b>不缓存 verify_status</b>：user 表单主键查询在 MVP 量级是微秒级，
 *       加缓存要处理失效，属过度设计</li>
 *   <li><b>管理员不豁免</b>：AC-11 未区分角色，统一规则，
 *       避免留下「管理员可绕过认证」的口子</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifiedInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;
    private final ObjectMapper om;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        // 方法级注解优先于类级：方法上打了就一定生效，不存在「类上打了覆盖方法上不打」
        boolean required = hm.hasMethodAnnotation(RequireVerified.class)
                || hm.getBeanType().isAnnotationPresent(RequireVerified.class);
        if (!required) {
            return true;
        }
        Long me = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (me == null) {
            // 401 已由 AuthInterceptor 短路,这里不该走到;真走到说明拦截器顺序被改坏了
            log.error("VerifiedInterceptor reached with null userId: {}", request.getRequestURI());
            return true;
        }
        User u = userMapper.selectById(me);
        if (u == null || !Integer.valueOf(1).equals(u.getVerifyStatus())) {
            log.warn("verified check failed: user={}, uri={}", me, request.getRequestURI());
            writeForbidden(response, ResultCode.USER_NOT_VERIFIED.getCode(),
                    ResultCode.USER_NOT_VERIFIED.getMessage());
            return false;
        }
        return true;
    }

    private void writeForbidden(HttpServletResponse response, int code, String msg) throws java.io.IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(om.writeValueAsString(Result.fail(code, msg)));
    }
}
