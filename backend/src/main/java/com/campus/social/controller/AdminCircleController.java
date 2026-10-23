package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.ReservedNameDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.CircleAdminService;
import com.campus.social.vo.CircleVO;
import com.campus.social.vo.ReservedNameVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 圈子官方名保护 + 管理员处置(仅 role=1 管理员)。
 *
 * <p>类比贴吧「吧主」模型里平台侧的兜底:用户抢注了官方名建圈后,
 * 管理员可 {@code transfer}(把圈主位交给目标成员)或 {@code ban}(封禁整个圈子),
 * 同时运营在 {@code reserved-names} 维护「哪些名字是官方保留、建圈直接拦」。
 *
 * <p>类级 {@link RequireAdmin}:整个 {@code /admin/**} 由 {@code AdminInterceptor} 统一拦截,
 * 非管理员 403 {@code NOT_ADMIN(1025)}。前端路由再叠加一道 requiresAdmin 守卫。
 */
@RestController
@RequestMapping("/admin/circles")
@RequireAdmin
public class AdminCircleController {

    private final CircleAdminService circleAdminService;

    public AdminCircleController(CircleAdminService circleAdminService) {
        this.circleAdminService = circleAdminService;
    }

    // ---------------- 圈子处置 ----------------

    /** 移交圈主:把圈主位交给圈内某成员(原圈主降级为普通成员)。 */
    @PostMapping("/{id}/transfer")
    public Result<Map<String, String>> transfer(@PathVariable("id") long id,
                                                @RequestBody Map<String, Long> body,
                                                HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        Long newOwnerId = body.get("newOwnerId");
        if (newOwnerId == null) {
            return Result.fail(400, "newOwnerId 不能为空");
        }
        circleAdminService.transferCircle(id, newOwnerId, me);
        return Result.ok(Map.of("message", "已移交圈主"));
    }

    /** 封禁圈子(status=1,不出现在广场/列表)。 */
    @PostMapping("/{id}/ban")
    public Result<Map<String, String>> ban(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.banCircle(id, me);
        return Result.ok(Map.of("message", "已封禁该圈子"));
    }

    /** 解封圈子。 */
    @PostMapping("/{id}/unban")
    public Result<Map<String, String>> unban(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.unbanCircle(id, me);
        return Result.ok(Map.of("message", "已解封该圈子"));
    }

    /**
     * 恢复解散的圈子(思路 B 兜底,仅管理员)。
     * 对称 disbandCircle:圈 status 1→0 + 圈内帖子 status 2→1。
     * 圈主侧不提供恢复入口(解散文案承诺「不可恢复」),恢复只走平台兜底通道。
     */
    @PostMapping("/{id}/restore")
    public Result<Map<String, String>> restore(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.restoreCircle(id, me);
        return Result.ok(Map.of("message", "已恢复该圈子"));
    }

    /**
     * 全量圈子列表:查 status=0 + status=1,按 id 倒序。
     * 区别于 {@code CircleService.list}(只查 status=0):被封禁/解散的圈子也要在管理后台可见,
     * 否则管理员封了之后找不到入口解封/恢复(真 bug:旧 list 按 status 过滤导致封禁后消失)。
     */
    @GetMapping
    public Result<CursorPage<CircleVO>> listAll(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "100") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(circleAdminService.listForAdmin(lastId, PageSize.clamp(size), me != null ? me : 0));
    }

    // ---------------- 预留官方名(运营后台) ----------------

    /** 预留某圈名(建圈撞名直接拦)。 */
    @PostMapping("/reserved-names")
    public Result<Map<String, String>> addReservedName(@Valid @RequestBody ReservedNameDTO dto,
                                                       HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.addReservedName(dto, me);
        return Result.ok(Map.of("message", "已预留该圈名"));
    }

    /** 移除预留名。 */
    @DeleteMapping("/reserved-names/{name}")
    public Result<Map<String, String>> removeReservedName(@PathVariable("name") String name,
                                                          HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.removeReservedName(name, me);
        return Result.ok(Map.of("message", "已移除预留名"));
    }

    /** 预留名列表(游标分页)。 */
    @GetMapping("/reserved-names")
    public Result<CursorPage<ReservedNameVO>> listReservedNames(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(circleAdminService.listReservedNames(lastId, PageSize.clamp(size), me != null ? me : 0));
    }
}
