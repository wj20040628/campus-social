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
 * 校园信息核对队列（管理端）。
 *
 * <p><b>权限只看管理员角色</b>（{@link RequireAdmin}，role=1，AC-20）。
 * 2026-10-30 起校园信息不再参与任何功能权限判定，
 * 这里核对的是「学号格式与所选年级/专业能否对上」，
 * 结果只影响个人资料上的登记状态，不影响发帖、搭话、建圈。
 *
 * <p>因此队列积压不会阻塞任何用户；管理员按自己的节奏处理即可。
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
     * 待核对的校园信息列表（游标分页）。
     *
     * <p>字段精简到 6 个是 OD-19 的要求：本页要在 375px 视口的手机上完成核对，
     * 管理员判断一致性需要的是「学号 + 院系年级 + 昵称」，其余一律不下发。
     */
    @GetMapping("/pending")
    public Result<AdminVerifyPageVO> pending(
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(adminVerifyService.pending(cursor, PageSize.clamp(size)));
    }

    /**
     * 标记校园信息已登记：2 → 1。
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
        return Result.ok(Map.of("message", "已登记该用户的校园信息"));
    }

    /**
     * 标记校园信息需修改：2 → 3。
     *
     * <p>{@code reason} 必填——用户要知道改哪里。
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