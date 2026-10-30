package com.campus.social.controller;

import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.AnnouncementCreateDTO;
import com.campus.social.dto.AnnouncementUpdateDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.AnnouncementAdminService;
import com.campus.social.vo.AdminAnnouncementPageVO;
import com.campus.social.vo.AnnouncementPublishVO;
import com.campus.social.vo.AnnouncementRemindVO;
import com.campus.social.vo.UnreadUserPageVO;
import com.campus.social.config.CampusProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公告管理端（F5 写入侧）。
 *
 * <p>类级 {@link RequireAdmin}：{@code /admin/**} 已由 AdminInterceptor 统一拦截，
 * 这里再标一次是让「本类是管理端」在代码里自解释。
 *
 * <p>2026-10-30：公告发布只要求管理员，校园信息不再是门槛。
 * 管理员自身可能没填校园信息，把它当门槛会让第一个管理员无法发公告；
 * 公告的权威性由 {@code role=1} 与后台审核链保证。
 *
 * <p>写操作（发布 / 编辑）在 service 层再判一次管理员，
 * 防止绕过 Controller 直调 service；读操作（列表 / 未读画像 / 催读）
 * 由类级 {@code @RequireAdmin} 与 service 的 {@code requirePublisherOrAdmin} 把关。
 */
@RestController
@RequestMapping("/admin/announcement")
@RequireAdmin
public class AdminAnnouncementController {

    private final AnnouncementAdminService adminService;
    private final CampusProperties campus;

    public AdminAnnouncementController(AnnouncementAdminService adminService, CampusProperties campus) {
        this.adminService = adminService;
        this.campus = campus;
    }

    /**
     * 发布公告。publishNow=false 时存草稿且不推送。
     * 只校验管理员（类级 @RequireAdmin），校园信息不参与。
     */
    @PostMapping
    public Result<AnnouncementPublishVO> create(@Valid @RequestBody AnnouncementCreateDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(adminService.create(me, dto));
    }

    /** 编辑公告（PATCH 语义：只改传入的字段）。权限与 create 同级：仅管理员。 */
    @PatchMapping("/{id}")
    public Result<AnnouncementPublishVO> update(@PathVariable("id") long id,
            @Valid @RequestBody AnnouncementUpdateDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(adminService.update(me, id, dto));
    }

    /** 后台列表：含草稿与已下架，带触达率 */
    @GetMapping
    public Result<AdminAnnouncementPageVO> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(adminService.adminList(campus.getSchoolId(), status, cursor, PageSize.clamp(size)));
    }

    /**
     * 「谁还没看」名单（AC-10）。
     *
     * <p>权限在 service 层再判一次「是否发布方或管理员」，
     * 不只靠 {@code @RequireAdmin}：任意运营账号都不该看到全校阅读画像。
     */
    @GetMapping("/{id}/unread-users")
    public Result<UnreadUserPageVO> unreadUsers(@PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "50") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(adminService.unreadUsers(me, id, cursor, PageSize.clamp(size)));
    }

    /**
     * 提醒未读用户。同一管理员对同一条公告 60 秒内只能提醒一次，
     * 防止连点把 WS 出口打满（影响的是全校聊天与通知推送）。
     */
    @PostMapping("/{id}/remind")
    public Result<AnnouncementRemindVO> remind(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(adminService.remind(me, id));
    }
}