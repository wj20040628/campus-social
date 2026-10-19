package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.FeedbackSubmitDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.FeedbackService;
import com.campus.social.vo.FeedbackSubmitVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
