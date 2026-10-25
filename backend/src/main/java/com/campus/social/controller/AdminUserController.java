package com.campus.social.controller;

import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.UserBanDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.AdminUserService;
import com.campus.social.vo.UserAdminPageVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员用户处置（2026-10-24 新增，补齐的管理空白）。
 *
 * <p>此前封人只能走举报流程、解封没有任何端点（永久封禁误判后
 * 只能改数据库）。本控制器提供：找人 → 直接封禁 / 手动解封。
 *
 * <p>类级 {@link RequireAdmin}：/admin/** 由 AdminInterceptor 拦 + 注解双保险，
 * 与其余 Admin 控制器同构。
 */
@RestController
@RequestMapping("/admin/users")
@RequireAdmin
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * 用户列表（游标分页）：昵称/登录名/学号模糊搜 + 状态筛选。
     * username 打码下发（口径同审计日志）。
     */
    @GetMapping
    public Result<UserAdminPageVO> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(adminUserService.page(keyword, status, cursor, PageSize.clamp(size)));
    }

    /**
     * 直接封禁（不依赖举报流程）。
     * 422：封自己 / 封管理员；404：用户不存在。
     */
    @PostMapping("/{userId}/ban")
    public Result<Map<String, String>> ban(@PathVariable("userId") long userId,
            @Valid @RequestBody UserBanDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        adminUserService.ban(me, userId, dto.getBanDays(), dto.getReason());
        return Result.ok(Map.of("message",
                dto.getBanDays() == 0 ? "已永久封禁该用户" : "已封禁该用户 " + dto.getBanDays() + " 天"));
    }

    /**
     * 手动解封——此前唯一的解封路径是到期定时任务，永久封禁不可撤销。
     * 审计归因到操作管理员（account_unban，operator=本人）。
     */
    @PostMapping("/{userId}/unban")
    public Result<Map<String, String>> unban(@PathVariable("userId") long userId,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        adminUserService.unban(me, userId);
        return Result.ok(Map.of("message", "已解封该用户"));
    }
}
