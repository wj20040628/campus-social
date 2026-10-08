package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.OnboardingCompleteDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.OnboardingService;
import com.campus.social.vo.OnboardingCompleteVO;
import com.campus.social.vo.OnboardingTagsVO;
import com.campus.social.vo.RecommendCircleListVO;
import com.campus.social.vo.RecommendUserListVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F11 新用户引导端点。
 *
 * <p>本类只做参数取值与响应包装，全部业务在 {@link OnboardingService}。
 * 不加类级 {@code @RequireAdmin}——四个动作全是「我自己」的读写。
 */
@RestController
@RequestMapping("/onboarding")
public class OnboardingController {

    /**
     * size 的服务端硬上限。
     *
     * <p>不依赖前端传参来限制：{@code @RequestParam} 的值完全由调用方决定，
     * 一个手写的请求就能传 {@code size=100000} 把整表拉出来。
     * 契约给的是 maximum，这里做 clamp 而不是拒绝——
     * 传 100 与传 60 的语义都是「给我尽可能多」，回 400 反而是无谓的失败。
     */
    private static final int MAX_TAG_SIZE = 60;
    private static final int MAX_RECOMMEND_USER_SIZE = 20;
    private static final int MAX_RECOMMEND_CIRCLE_SIZE = 20;

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /** 引导第 2 步的候选标签（每组梯度裁剪，总量 ≤ size） */
    @GetMapping("/tags")
    public Result<OnboardingTagsVO> listTags(
            @RequestParam(value = "size", defaultValue = "60") int size) {
        return Result.ok(onboardingService.listTags(clamp(size, MAX_TAG_SIZE)));
    }

    /** 完成引导（幂等：重复提交返回 duplicated=true，不重复写入） */
    @PostMapping("/complete")
    public Result<OnboardingCompleteVO> complete(@Valid @RequestBody OnboardingCompleteDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(onboardingService.complete(me, dto));
    }

    /** 引导第 3 步的推荐关注人选。空列表带 emptyHint，不白屏（AC-19） */
    @GetMapping("/recommend-users")
    public Result<RecommendUserListVO> recommendUsers(
            @RequestParam(value = "size", defaultValue = "12") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(onboardingService.recommendUsers(me, clamp(size, MAX_RECOMMEND_USER_SIZE)));
    }

    /** 推荐圈子。冷启动期允许为空，带 emptyHint（AC-19） */
    @GetMapping("/recommend-circles")
    public Result<RecommendCircleListVO> recommendCircles(
            @RequestParam(value = "size", defaultValue = "8") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(onboardingService.recommendCircles(me, clamp(size, MAX_RECOMMEND_CIRCLE_SIZE)));
    }

    /** 非正数归一到 1：size=0 或负数不是「不要」，而是参数被写坏了，按 1 处理最不易误解 */
    private int clamp(int size, int max) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, max);
    }
}
