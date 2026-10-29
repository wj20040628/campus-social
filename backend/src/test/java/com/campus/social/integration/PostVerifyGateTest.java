package com.campus.social.integration;

import com.campus.social.controller.PostController;
import com.campus.social.dto.CreatePostDTO;
import com.campus.social.entity.User;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireVerified;
import com.campus.social.interceptor.VerifiedInterceptor;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.PostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发帖/评论的认证门槛（2026-10-29）。
 *
 * <p>自评估报告承诺「校内认证通过后才开放发帖」。这里锁两件事：
 * <ol>
 *   <li>PostController 的创建/编辑/评论方法确实带 {@link RequireVerified}
 *       —— 漏一个就是一条未认证可发布的旁路</li>
 *   <li>拦截器对未认证用户真的回 403/1029，对已认证用户放行
 *       —— 注解在、拦截器失效同样是静默放行</li>
 * </ol>
 */
class PostVerifyGateTest {

    private static final long USER_ID = 7L;

    @Test
    void createEditCommentRequireVerified() {
        for (String name : List.of("create", "edit", "comment")) {
            Method method = findMethod(name);
            assertNotNull(method.getAnnotation(RequireVerified.class),
                    "PostController." + name + " 必须带 @RequireVerified");
        }
    }

    @Test
    void unverifiedUserGets403() throws Exception {
        VerifiedInterceptor interceptor = interceptorWithStatus(0);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(request(), res, createHandler());

        assertFalse(pass, "未认证用户不应通过发帖拦截器");
        assertEquals(403, res.getStatus());
        assertTrue(res.getContentAsString().contains("1029"), "应回 USER_NOT_VERIFIED(1029)");
    }

    @Test
    void verifiedUserPasses() throws Exception {
        VerifiedInterceptor interceptor = interceptorWithStatus(1);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(request(), res, createHandler());

        assertTrue(pass, "已认证用户应通过发帖拦截器");
    }

    private Method findMethod(String name) {
        for (Method m : PostController.class.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("PostController 找不到方法: " + name);
    }

    private VerifiedInterceptor interceptorWithStatus(int verifyStatus) {
        UserMapper userMapper = mock(UserMapper.class);
        User u = new User();
        u.setId(USER_ID);
        u.setVerifyStatus(verifyStatus);
        when(userMapper.selectById(USER_ID)).thenReturn(u);
        return new VerifiedInterceptor(userMapper, new ObjectMapper());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, USER_ID);
        return req;
    }

    private HandlerMethod createHandler() throws Exception {
        PostController controller = new PostController(mock(PostService.class));
        return new HandlerMethod(controller,
                PostController.class.getMethod("create", CreatePostDTO.class, HttpServletRequest.class));
    }
}
