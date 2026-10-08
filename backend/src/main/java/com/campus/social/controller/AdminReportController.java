package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.ReportHandleDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.ReportAdminService;
import com.campus.social.service.ReportService;
import com.campus.social.vo.ReportAdminPageVO;
import com.campus.social.vo.ReportHandleVO;
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
 * 举报管理端（F4）。
 *
 * <p>类级 {@link RequireAdmin}：整个 {@code /admin/**} 路径已由
 * {@code AdminInterceptor} 统一拦截，这里再标一次是让「本类是管理端」
 * 这件事在代码里自解释，而不是要读者去翻 WebMvcConfig 才知道。
 *
 * <p>OD-19 要求后台在手机上可用，故单页字段做截断处理（见 ReportAdminServiceImpl）。
 */
@RestController
@RequestMapping("/admin/reports")
@RequireAdmin
public class AdminReportController {

    private final ReportAdminService reportAdminService;
    private final ReportService reportService;

    public AdminReportController(ReportAdminService reportAdminService, ReportService reportService) {
        this.reportAdminService = reportAdminService;
        this.reportService = reportService;
    }

    /**
     * 举报列表（游标分页）。
     *
     * @param status 0待审 1成立 2驳回；不传表示全部
     */
    @GetMapping
    public Result<ReportAdminPageVO> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(reportAdminService.list(status, cursor, size));
    }

    /**
     * 处置举报：ban 成立并封禁 / reject 驳回 / ignore 忽略。
     *
     * <p>重复处置同一举报回 409：两个管理员在手机上同时点「封禁」是常事，
     * 第二次必须被拒而不是静默覆盖第一次的处置记录。
     */
    @PostMapping("/{id}/handle")
    public Result<ReportHandleVO> handle(@PathVariable("id") long id,
            @Valid @RequestBody ReportHandleDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(reportService.handle(me, id, dto));
    }
}