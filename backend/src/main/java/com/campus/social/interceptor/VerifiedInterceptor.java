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
            // 走到这里说明拦截器顺序被改坏了（auth 没先跑）或有人绕过了 auth。
            // 原实现打条 error 然后 return true —— 那是 fail-open：
            // 端点明明声明了「必须已认证」，我们手上却没有身份信息，
            // 证明不了这个人是谁，却选择放行。护栏恰好在最该生效的时刻失效，
            // 而且放行后请求会一路走到业务层，无状态的响应特征让事后排查也失去线索。
            //
            // 401 而不是 403：403 的语义是「知道你是谁，但你没这个权限」，
            // 这里恰恰是「不知道你是谁」——与 AuthInterceptor 保持同一语义，
            // 前端收到 401 会走重新登录，而不是弹一个「无权限」后卡死。
            log.error("VerifiedInterceptor reached with null userId, treating as unauthenticated: {}",
                    request.getRequestURI());
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
            return false;
        }
        User u = userMapper.selectById(me);
        if (u == null || !Integer.valueOf(1).equals(u.getVerifyStatus())) {
            log.warn("verified check failed: user={}, uri={}", me, request.getRequestURI());
            writeJson(response, HttpStatus.FORBIDDEN.value(),
                    ResultCode.USER_NOT_VERIFIED.getCode(), ResultCode.USER_NOT_VERIFIED.getMessage());
            return false;
        }
        return true;
    }

    /**
     * 统一的短路响应写出。
     *
     * <p>status 与 body 里的业务码<b>刻意不同</b>：
     * 401 时业务码也是 401，403 时业务码是 1029（USER_NOT_VERIFIED）。
     * 前端 {@code request.js} 依HTTP status 分流，body.code 依业务分流，
     * 两者各走各的判断，写成一样会让其中一条判断失效。
     */
    private void writeJson(HttpServletResponse response, int status, int code, String msg)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(om.writeValueAsString(Result.fail(code, msg)));
    }
}
