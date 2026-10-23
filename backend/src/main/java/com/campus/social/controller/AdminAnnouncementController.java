package com.campus.social.controller;

import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.AnnouncementCreateDTO;
import com.campus.social.dto.AnnouncementUpdateDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.interceptor.RequireVerified;
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
 * <p>AC-11：未通过校内认证的用户不能发布公告。
 * 校验**分两层**，缺一不可：
 * <ol>
 *   <li>本类的<b>写入类</b>端点（发布 / 编辑）打 {@link RequireVerified}，
 *       由 {@code VerifiedInterceptor} 在进Controller 前拦成 403 {@code USER_NOT_VERIFIED}。
 *       拦截器层的价值是：新端点只要落在本类就自动受管，
 *       不会像纯 service 校验那样「新增方法忘了调requireVerified」而静默漏掉</li>
 *   <li>{@code AnnouncementAdminServiceImpl.requireVerified} 保留。
 *       它是service 层的兜底，防止将来绕过 Controller 直接调 service</li>
 * </ol>
 *
 * <p><b>为什么 {@code update} 也要认证校验</b>：编辑能改 title / content，
 * 改完立即推给全校在线用户，效果与发布等价。
 * 只拦 create 不拦 update，等于留了一条「先让未认证者建草稿、
 * 再由任何管理员编辑上架」的绕行路径——AC-11 会被架空。
 *
 * <p><b>为什么只读端点不挂</b>：{@code adminList} / {@code unreadUsers} / {@code remind}
 * 不产生新的对外内容（remind 只是催已存在的公告），
 * 且已分别由 {@code @RequireAdmin} 与 service层的
 * {@code requirePublisherOrAdmin} 把关。给读操作加认证门槛
 * 只会让「管理员忘了认证就看不到后台」成为运维事故，不产生安全收益。
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
     * 发布公告。publishNow=false 时存草稿且不推送
     *
     * <p>即使存草稿也要认证：草稿随时能被上架并推给全校，
     * 放行未认证者的草稿等于把上架前的最后一道门让出去。
     */
    @RequireVerified
    @PostMapping
    public Result<AnnouncementPublishVO> create(@Valid @RequestBody AnnouncementCreateDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(adminService.create(me, dto));
    }

    /**
     * 编辑公告（PATCH 语义：只改传入的字段）。不允许把已发布公告打回草稿
     *
     * <p>认证要求与 create 同级，理由见类注释：编辑内容并上架
     * 对全校用户的效果与发布等价。
     */
    @RequireVerified
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