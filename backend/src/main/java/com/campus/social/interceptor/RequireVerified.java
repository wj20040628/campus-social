package com.campus.social.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记该端点要求「已通过校内认证」（F2 / AC-11）。
 *
 * <p>加此注解的端点，未认证用户一律被 {@link VerifiedInterceptor} 拦成
 * 403 {@code USER_NOT_VERIFIED(1029)}。
 *
 * <p>为什么用注解而不是路径白名单表：路径表要在「配置处」和「Controller 处」
 * 两处维护，加端点时漏登记是静默失败；注解跟着方法走，漏了就是看得见的遗漏
 * （ARCHITECTURE.md §3 Q9.5）。
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireVerified {
}
