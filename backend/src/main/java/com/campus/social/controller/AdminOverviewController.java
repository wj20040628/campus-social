package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.AdminOverviewService;
import com.campus.social.vo.AdminOverviewVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理总览（2026-11-07 新增）。
 *
 * <p>管理端此前只有五个入口上的待办角标，没有任何一屏能回答
 * 「今天有没有异常、站内现在多大规模」。本端点一次回齐四组计数，
 * 页面（/admin/overview）只负责渲染，判定口径全部在服务端。
 */
@RestController
@RequestMapping("/admin")
@RequireAdmin
public class AdminOverviewController {

    private final AdminOverviewService adminOverviewService;

    public AdminOverviewController(AdminOverviewService adminOverviewService) {
        this.adminOverviewService = adminOverviewService;
    }

    @GetMapping("/overview")
    public Result<AdminOverviewVO> overview() {
        return Result.ok(adminOverviewService.overview());
    }
}
