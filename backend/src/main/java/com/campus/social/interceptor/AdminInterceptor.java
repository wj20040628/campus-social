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
 * 管理员拦截器（AC-20）。
 *
 * <p><b>生效范围要说准</b>：本拦截器注册在 {@code /admin/**} 路径上，
 * 但判定是**注解驱动**的——只拦打了 {@link RequireAdmin}（方法级或类级）的
 * handler。所以「漏标注解也拦得住」并不成立：/admin/** 下新增的控制器
 * 忘了打注解，这层不会兜底，只能靠 Controller 内 {@code roleService.requireAdmin}
 * 或测试发现。不在 /admin/** 路径下的管理端点（如 /review/**、/audit/**）
 * 则完全不经过本拦截器，只有 Controller 内调用那一层防护。
 *
 * <p>为什么既有拦截器又要 Controller 里调 {@code roleService.requireAdmin}：
 * 拦截器是注册路径 + 注解的兜底，Controller 内调用是方法级的显式声明
 * （读代码时一眼看到该端点要管理员）。两层并存不冲突——重复校验只是多一次主键查询，
 * 换取的是「任一层失效，另一层仍然拦得住」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;
    private final ObjectMapper om;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        boolean required = hm.hasMethodAnnotation(RequireAdmin.class)
                || hm.getBeanType().isAnnotationPresent(RequireAdmin.class);
        if (!required) {
            return true;
        }
        Long me = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (me == null) {
            /*
             * fail-closed（2026-10-24 对齐 AuthInterceptor 的同款修正）。
             * 原实现 log.error 后 return true —— 那是 fail-open：端点声明了
             * 「必须管理员」，我们手上却没有身份信息，却选择放行。
             * 触发条件（auth 拦截器顺序被改坏 / 白名单误放行某个 /admin 端点）
             * 当前不可达，但护栏的价值恰恰在它被触发的那一刻。
             * 401 而非 403：与 AuthInterceptor 同语义——「不知道你是谁」。
             */
            log.error("AdminInterceptor reached with null userId, treating as unauthenticated: {}",
                    request.getRequestURI());
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
            return false;
        }
        User u = userMapper.selectById(me);
        if (u == null || !Integer.valueOf(1).equals(u.getRole())) {
            log.warn("admin check failed: user={}, uri={}", me, request.getRequestURI());
            writeForbidden(response, ResultCode.NOT_ADMIN.getCode(), ResultCode.NOT_ADMIN.getMessage());
            return false;
        }
        return true;
    }

    private void writeJson(HttpServletResponse response, int status, int code, String msg)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(om.writeValueAsString(Result.fail(code, msg)));
    }

    private void writeForbidden(HttpServletResponse response, int code, String msg) throws java.io.IOException {
        writeJson(response, HttpStatus.FORBIDDEN.value(), code, msg);
    }
}
