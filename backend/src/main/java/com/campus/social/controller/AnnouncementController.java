package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.Result;
import com.campus.social.config.CampusProperties;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.AnnouncementService;
import com.campus.social.vo.AnnouncementDetailVO;
import com.campus.social.vo.AnnouncementListItemVO;
import com.campus.social.vo.AnnouncementReadVO;
import com.campus.social.vo.UnreadCountVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公告前台（F5 读取侧）。
 *
 * <p>{@code /announcement/unread-count} 与 {@code /announcement/{id}} 共存不冲突：
 * Spring 的路径匹配优先精确段，字面量 {@code unread-count} 不会被
 * 当成 {@code {id}}。但 {@code {id}} 声明成 {@code Long} 而非 {@code long} 是有意的——
 * 若声明成 long，客户端误传非数字会得到一个语焉不详的 400，
 * 而 Long 会由 Spring 的类型转换失败统一给出 400，两者都不理想，
 * 但 Long 至少不会把 {@code /unread-count} 这类路径吃掉。
 */
@RestController
@RequestMapping("/announcement")
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final CampusProperties campus;

    public AnnouncementController(AnnouncementService announcementService, CampusProperties campus) {
        this.announcementService = announcementService;
        this.campus = campus;
    }

    /** 前台列表：置顶优先，其余按 id 倒序 */
    @GetMapping
    public Result<CursorPage<AnnouncementListItemVO>> list(
            @RequestParam(value = "category", required = false) Integer category,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(announcementService.frontList(campus.getSchoolId(), me, category, cursor, size));
    }

    /** 详情。草稿与已下架仅发布方本人可见 */
    @GetMapping("/{id}")
    public Result<AnnouncementDetailVO> detail(@PathVariable("id") Long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(announcementService.detail(me, id, campus.getSchoolId()));
    }

    /** 已读回执，幂等：重复上报不新增记录、不重复累加已读人数 */
    @PostMapping("/{id}/read")
    public Result<AnnouncementReadVO> markRead(@PathVariable("id") Long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(announcementService.markRead(me, id, campus.getSchoolId()));
    }

    /** 未读数与最新一条未读 id（红点）。放在最后声明以免与 {id} 路径混淆 */
    @GetMapping("/unread-count")
    public Result<UnreadCountVO> unreadCount(HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(announcementService.unreadCount(me, campus.getSchoolId()));
    }
}