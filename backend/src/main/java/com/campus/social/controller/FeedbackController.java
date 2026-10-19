package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.common.ResultCode;
import com.campus.social.dto.FeedbackSubmitDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.FeedbackService;
import com.campus.social.vo.FeedbackMinePageVO;
import com.campus.social.vo.FeedbackSubmitVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意见反馈提交（2026-10-18，导航抽屉「意见反馈」）。
 *
 * <p>与 {@link ReportController} 同构：只放提交，审核在
 * {@link AdminFeedbackController}。提交成功即进入待处理队列（status=0），
 * 由管理员在后台处置。
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public Result<FeedbackSubmitVO> submit(@Valid @RequestBody FeedbackSubmitDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(feedbackService.submit(me, dto));
    }

    /**
     * 我的反馈列表(游标分页)。
     *
     * <p>只返回当前用户自己提交的反馈,不联 user 表(提交人就是自己,
     * 昵称没意义),也不回 handler_id 这类处理人 id。未登录回 401。
     */
    @GetMapping("/mine")
    public Result<FeedbackMinePageVO> mine(HttpServletRequest req,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (me == null) return Result.fail(ResultCode.UNAUTHORIZED, "未登录");
        return Result.ok(feedbackService.listMine(me, cursor, size));
    }
}
