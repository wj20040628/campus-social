package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.AiReviewStatusService;
import com.campus.social.vo.AiReviewStatusVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端审核配置自检（2026-11-08）。 */
@RestController
@RequestMapping("/admin/review")
@RequireAdmin
public class AdminReviewController {

    private final AiReviewStatusService aiReviewStatusService;

    public AdminReviewController(AiReviewStatusService aiReviewStatusService) {
        this.aiReviewStatusService = aiReviewStatusService;
    }

    /** 机审是否真正可用、缺哪个配置；不回传密钥。 */
    @GetMapping("/ai-status")
    public Result<AiReviewStatusVO> aiStatus() {
        return Result.ok(aiReviewStatusService.status());
    }
}
