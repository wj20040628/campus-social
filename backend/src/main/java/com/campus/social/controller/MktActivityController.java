package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.PageSize;
import com.campus.social.dto.MktActivityDTO;
import com.campus.social.dto.MktCommentDTO;
import com.campus.social.dto.MktReactivDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireVerified;
import com.campus.social.service.MktActivityService;
import com.campus.social.common.Result;
import com.campus.social.vo.MktActivityVO;
import com.campus.social.vo.MktCommentVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 校园限时活动集市（mkt）REST 入口。
 * 沟通只走站内公共留言区；不存、不外发任何对外联系方式。
 * 时间统一东八区。
 */
@RestController
@RequestMapping("/mkt")
public class MktActivityController {

    private final MktActivityService service;

    public MktActivityController(MktActivityService service) {
        this.service = service;
    }

    /**
     * 创建一条集市活动，返回新 id。
     *
     * <p>{@code @RequireVerified}（2026-10-21 新增）：未认证用户不能发布集市活动。
     * 集市是「对人发起邀约并可能线下见面」的场景，是校外身份最需要被拦住的地方之一。
     * 注意只拦 create —— 编辑/暂停/满员等后续操作是属主对自己的活动，不需要再验一次。
     */
    @RequireVerified
    @PostMapping
    public Result<Long> create(@Valid @RequestBody MktActivityDTO dto, HttpServletRequest req) {
        long me = currentUserId(req);
        return Result.ok(service.create(me, dto));
    }

    /** 广场：投放中、未满员、未过期、开启；可一级分类过滤。游标分页。 */
    @GetMapping("/plaza")
    public Result<CursorPage<MktActivityVO>> plaza(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        size = PageSize.clamp(size);
        return Result.ok(service.plaza(category, lastId, size));
    }

    /** 我的发布：带服务端推导的 4 态 status。游标分页。 */
    @GetMapping("/my")
    public Result<CursorPage<MktActivityVO>> my(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        size = PageSize.clamp(size);
        return Result.ok(service.myList(currentUserId(req), lastId, size));
    }

    /** 详情：软删 404；过期/暂停/满员仍可看可留言。游客可读(分享落地页,2026-10-25)。 */
    @GetMapping("/{id}")
    public Result<MktActivityVO> detail(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(service.detail(id, me != null ? me : 0L));
    }

    /** 编辑：仅改文案字段；不改 expire_time。 */
    @PutMapping("/{id}")
    public Result<Void> edit(@PathVariable("id") long id,
            @Valid @RequestBody MktActivityDTO dto,
            HttpServletRequest req) {
        service.edit(id, currentUserId(req), dto);
        return Result.ok();
    }

    /** 开 / 关。 */
    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable("id") long id, HttpServletRequest req) {
        service.toggleEnable(id, currentUserId(req), true);
        return Result.ok();
    }

    @PostMapping("/{id}/pause")
    public Result<Void> pause(@PathVariable("id") long id, HttpServletRequest req) {
        service.toggleEnable(id, currentUserId(req), false);
        return Result.ok();
    }

    /** 手动满员标记。 */
    @PostMapping("/{id}/full")
    public Result<Void> full(@PathVariable("id") long id,
            @RequestParam("full") boolean full,
            HttpServletRequest req) {
        service.markFull(id, currentUserId(req), full);
        return Result.ok();
    }

    /** 重新激活：刷新到期时间 + last_launch_time；24h/3 次限流。 */
    @PostMapping("/{id}/reactivate")
    public Result<Void> reactivate(@PathVariable("id") long id,
            @Valid @RequestBody MktReactivDTO dto,
            HttpServletRequest req) {
        service.reactivate(id, currentUserId(req), dto);
        return Result.ok();
    }

    /** 软删。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") long id, HttpServletRequest req) {
        service.delete(id, currentUserId(req));
        return Result.ok();
    }

    /** 发留言（限流 + 敏感词）；对发布者下发站内通知。 */
    @PostMapping("/{id}/comment")
    public Result<Void> addComment(@PathVariable("id") long id,
            @Valid @RequestBody MktCommentDTO dto,
            HttpServletRequest req) {
        service.addComment(id, currentUserId(req), dto);
        return Result.ok();
    }

    /** 留言列表：游标分页。 */
    @GetMapping("/{id}/comments")
    public Result<CursorPage<MktCommentVO>> comments(
            @PathVariable("id") long id,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        size = PageSize.clamp(size);
        return Result.ok(service.listComments(id, lastId, size));
    }

    private long currentUserId(HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (me == null) {
            // AuthInterceptor 已拦未登录；此处兜底
            throw new com.campus.social.exception.BusinessException(
                    com.campus.social.common.ResultCode.UNAUTHORIZED);
        }
        return me;
    }
}
