package com.campus.social.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记该端点要求管理员角色（AC-20）。
 *
 * <p>非管理员一律被 {@link AdminInterceptor} 拦成 403 {@code NOT_ADMIN(1025)}。
 *
 * <p>存在的意义：改造前每个 admin 方法第一行各写一次
 * {@code roleService.requireAdmin(me)}，漏写一处就是一个越权口子。
 * 收口到拦截器后，遗漏表现为「端点没生效这个注解」这种看得见的缺口，
 * 而不是「某人忘了写一行判断」这种要靠 code review 撞运气发现的缺口。
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
