package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.AuditService;
import com.campus.social.service.RoleService;
import com.campus.social.vo.AuditLogVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 管理后台·操作日志(仅 role=1 管理员)
 */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final AuditService auditService;
    private final RoleService roleService;

    public AuditController(AuditService auditService, RoleService roleService) {
        this.auditService = auditService;
        this.roleService = roleService;
    }

    @GetMapping("/logs")
    public Result<CursorPage<AuditLogVO>> logs(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "30") int size,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "keyword", required = false) String keyword,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        roleService.requireAdmin(me); // 仅管理员
        return Result.ok(auditService.queryLogs(lastId, size, action, userId, keyword));
    }
}
