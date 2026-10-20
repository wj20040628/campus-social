package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.FeedbackHandleDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.FeedbackService;
import com.campus.social.vo.FeedbackAdminPageVO;
import com.campus.social.vo.FeedbackSubmitVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意见反馈审核（2026-10-18）。
 *
 * <p>类级 {@link RequireAdmin} 与 {@code /admin/**} 的统一拦截重复，
 * 目的是让「本类是管理端」在代码里自解释。
 */
@RestController
@RequestMapping("/admin/feedback")
@RequireAdmin
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    public AdminFeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 反馈列表（游标分页）。
     *
     * @param status   0待处理 1已采纳 2已忽略；不传表示全部
     * @param category 1功能建议 2使用问题 3内容问题 4界面体验 5其他；不传表示全部类型
     * @param kind     认证申诉标记：1=只看申诉、0=只看非申诉、不传表示不限
     */
    @GetMapping
    public Result<FeedbackAdminPageVO> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "category", required = false) Integer category,
            @RequestParam(value = "kind", required = false) Integer kind,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(feedbackService.list(status, category, kind, cursor, size));
    }

    /**
     * 处置反馈：adopt 采纳 / ignore 忽略，必须写处理说明。
     *
     * <p>重复处置回 409：两个管理员在手机上同时点是常事，
     * 第二次必须被拒而不是静默覆盖前一次的处置记录。
     */
    @PostMapping("/{id}/handle")
    public Result<FeedbackSubmitVO> handle(@PathVariable("id") long id,
            @Valid @RequestBody FeedbackHandleDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(feedbackService.handle(me, id, dto));
    }
}
