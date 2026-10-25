package com.campus.social.controller;

import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.impl.BuddyMatchService;
import com.campus.social.vo.RecommendUserListVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发现页专属端点(2026-10-25)。
 *
 * <p>此前发现页直接复用 /onboarding/recommend-* 两个端点(api/discover.js 的
 * 注释里预留了这条扩展缝:「后端补 /discover 专属端点后,只改本文件」)。
 * 搭子匹配的口径与推荐关注不同(标签主导 + 双向拉黑 + 认证/陌生人开关过滤),
 * 值得一个独立端点;发现页其余两个 tab 继续复用 onboarding 端点不动。
 *
 * <p>登录即可浏览(不挂 @RequireVerified):看推荐无门槛,
 * 「想认识」搭话时 GreetButton / ChatGuard 自有认证拦截。
 */
@RestController
@RequestMapping("/discover")
public class DiscoverController {

    private final BuddyMatchService buddyMatchService;

    public DiscoverController(BuddyMatchService buddyMatchService) {
        this.buddyMatchService = buddyMatchService;
    }

    /**
     * 本周搭子:共同兴趣标签加权撮合,结果按周缓存(周一自然换一批)。
     */
    @GetMapping("/buddy")
    public Result<RecommendUserListVO> buddy(
            @RequestParam(value = "size", defaultValue = "6") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(buddyMatchService.weeklyBuddies(me, PageSize.clamp(size)));
    }
}
