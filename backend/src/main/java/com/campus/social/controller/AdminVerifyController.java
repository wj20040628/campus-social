package com.campus.social.controller;

import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.AdminVerifyRejectDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.AdminVerifyService;
import com.campus.social.vo.AdminVerifyPageVO;
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
 * 学号核验审核端（上线阻塞项）。
 *
 * <p><b>为什么本类不打 {@code @RequireVerified}</b>：
 * 管理员自己很可能尚未通过校内认证，而认证恰恰要由管理员来批——
 * 打上这个注解，第一个管理员就永远无法给自己批学号，
 * 整条认证链路从 bootstrap 那一刻起就是死循环。
 * 权限判定只依赖类级 {@link RequireAdmin}（role=1，AC-20），
 * 这与「审核别人」这件事的实际权限模型一致：看的是你是不是管理员，
 * 不是你自己认证过没有。
 *
 * <p>三个端点都只回 {@code message}（openapi MessageResponse）：
 * 审批动作没有需要回传的业务对象，硬造一个 VO 只会让前端拿到一堆 null。
 */
@RestController
@RequestMapping("/admin/verify")
@RequireAdmin
public class AdminVerifyController {

    private final AdminVerifyService adminVerifyService;

    public AdminVerifyController(AdminVerifyService adminVerifyService) {
        this.adminVerifyService = adminVerifyService;
    }

    /**
     * 待审学号核验列表（游标分页）。
     *
     * <p>字段精简到 6 个是 OD-19 的要求：本页要在 375px 视口的手机上完成审核，
     * 管理员判断真伪需要的是「学号 + 院系年级 + 昵称」，其余一律不下发。
     */
    @GetMapping("/pending")
    public Result<AdminVerifyPageVO> pending(
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(adminVerifyService.pending(cursor, PageSize.clamp(size)));
    }

    /**
     * 通过学号核验：2 → 1。
     *
     * <p>目标不存在或状态非 2 时 404 {@code VERIFY_NOT_FOUND(1044)}：
     * 两个管理员在手机上同时点「通过」是常事，第二次必须被明确拒绝，
     * 而不是静默覆盖第一次的处置记录。
     */
    @PostMapping("/{userId}/approve")
    public Result<Map<String, String>> approve(@PathVariable("userId") long userId,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        adminVerifyService.approve(me, userId);
        return Result.ok(Map.of("message", "已通过该用户的学号核验"));
    }

    /**
     * 驳回学号核验：2 → 3。
     *
     * <p>{@code reason} 必填——用户被驳回后要靠这句话知道改哪里。
     * 空或超 200 字由 {@code @Valid} 拦成 400，不进 service。
     * 驳回后用户可修改并重新提交，这是契约明确保留的通路。
     */
    @PostMapping("/{userId}/reject")
    public Result<Map<String, String>> reject(@PathVariable("userId") long userId,
            @Valid @RequestBody AdminVerifyRejectDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        adminVerifyService.reject(me, userId, dto.getReason());
        return Result.ok(Map.of("message", "已驳回，理由已通知该用户"));
    }
}