package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.AdminUserService;
import com.campus.social.vo.PendingSummaryVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端待办聚合（2026-10-24 新增）。
 *
 * <p>六个管理入口此前各自为政，每个队列的待审数要进对应页面才能看到。
 * 本端点一次回四条队列的待办数，抽屉/侧栏的管理入口挂角标用——
 * 「有没有活、先去哪页」一眼可见，不必逐页巡检。
 */
@RestController
@RequestMapping("/admin")
@RequireAdmin
public class AdminSummaryController {

    private final AdminUserService adminUserService;

    public AdminSummaryController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /** 四条队列待办数：举报 / 机审 / 学号核验 / 反馈。 */
    @GetMapping("/pending-summary")
    public Result<PendingSummaryVO> pendingSummary() {
        return Result.ok(adminUserService.pendingSummary());
    }
}
