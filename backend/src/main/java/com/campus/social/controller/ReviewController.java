package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.ReviewRejectDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.ReviewService;
import com.campus.social.service.RoleService;
import com.campus.social.vo.MyReviewVO;
import com.campus.social.vo.ReviewTaskVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/review")
public class ReviewController {

    private final ReviewService reviewService;
    private final RoleService roleService;

    public ReviewController(ReviewService reviewService, RoleService roleService) {
        this.reviewService = reviewService;
        this.roleService = roleService;
    }

    /** 作者侧:我自己的帖子审核结果(被驳/通过 + 理由 + 命中词 + AI 预判),任意登录用户可查自己的 */
    @GetMapping("/mine")
    public Result<CursorPage<MyReviewVO>> mine(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(reviewService.myReviews(me, lastId, PageSize.clamp(size)));
    }

    /**
     * 待审队列（2026-10-24 圈主协审后按身份分流,不再一刀切管理员):
     * 管理员 → 平台队列(非圈内任务 + 超过升级窗的圈内任务);
     * 圈主   → 自己圈内未升级的待审任务(普通人自然为空,不泄露任何数据)。
     */
    @GetMapping("/pending")
    public Result<CursorPage<ReviewTaskVO>> pending(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(reviewService.pending(me, lastId, PageSize.clamp(size)));
    }

    @GetMapping("/processed")
    public Result<CursorPage<ReviewTaskVO>> processed(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        roleService.requireAdmin(me);
        return Result.ok(reviewService.processed(me, lastId, PageSize.clamp(size)));
    }

    /** 通过审核:平台管理员或该帖所属圈的圈主(权限在 service 内判定,NOT_ADMIN 拒绝) */
    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        reviewService.approve(id, me);
        return Result.ok();
    }

    /** 驳回:权限同 approve;reason 必填——作者要靠它知道改哪里 */
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable("id") long id,
                               @RequestBody @Valid ReviewRejectDTO dto,
                               HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        reviewService.reject(id, me, dto.getReason());
        return Result.ok();
    }
}
