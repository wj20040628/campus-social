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
 * <p>覆盖全部 {@code /admin/**} 路径，与 {@link VerifiedInterceptor} 同构：
 * 直接写 HTTP 403 并短路，不进 Controller。
 *
 * <p>为什么既有拦截器又要 Controller 里调 {@code roleService.requireAdmin}：
 * 拦截器是路径级的兜底（漏标注解也拦得住），Controller 内调用是方法级的显式声明
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
            log.error("AdminInterceptor reached with null userId: {}", request.getRequestURI());
            return true;
        }
        User u = userMapper.selectById(me);
        if (u == null || !Integer.valueOf(1).equals(u.getRole())) {
            log.warn("admin check failed: user={}, uri={}", me, request.getRequestURI());
            writeForbidden(response, ResultCode.NOT_ADMIN.getCode(), ResultCode.NOT_ADMIN.getMessage());
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
