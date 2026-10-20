package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.RelationService;
import com.campus.social.vo.RelationStatusVO;
import com.campus.social.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/relation")
public class RelationController {

    private final RelationService relationService;

    public RelationController(RelationService relationService) {
        this.relationService = relationService;
    }

    @PostMapping("/follow")
    public Result<Void> follow(@RequestParam("toUserId") long toUserId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        relationService.follow(me, toUserId);
        return Result.ok();
    }

    @DeleteMapping("/follow")
    public Result<Void> unfollow(@RequestParam("toUserId") long toUserId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        relationService.unfollow(me, toUserId);
        return Result.ok();
    }

    @GetMapping("/following")
    public Result<CursorPage<UserVO>> following(@RequestParam("uid") long uid,
                                                @RequestParam(value = "lastId", required = false) Long lastId,
                                                @RequestParam(value = "size", defaultValue = "20") int size,
                                                HttpServletRequest req) {
        // 他人主页查指定用户的"他关注的":用 uid(而非登录人 me)。
        // 校验 uid 存在,不存在则回 404(避免拿任意 uid 当查询条件)。
        return Result.ok(relationService.following(uid, lastId, size));
    }

    @GetMapping("/followers")
    public Result<CursorPage<UserVO>> followers(@RequestParam("uid") long uid,
                                                @RequestParam(value = "lastId", required = false) Long lastId,
                                                @RequestParam(value = "size", defaultValue = "20") int size,
                                                HttpServletRequest req) {
        // 他人主页查指定用户的"他的粉丝":用 uid(而非登录人 me)
        return Result.ok(relationService.followers(uid, lastId, size));
    }

    @PostMapping("/friend")
    public Result<Void> friend(@RequestParam("uid") long uid, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        relationService.addFriend(me, uid);
        return Result.ok();
    }

    @DeleteMapping("/friend")
    public Result<Void> unfriend(@RequestParam("uid") long uid, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        relationService.removeFriend(me, uid);
        return Result.ok();
    }

    @PostMapping("/blacklist")
    public Result<Void> blacklist(@RequestParam("uid") long uid, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        relationService.blacklist(me, uid);
        return Result.ok();
    }

    @DeleteMapping("/blacklist")
    public Result<Void> unblacklist(@RequestParam("uid") long uid, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        relationService.unblacklist(me, uid);
        return Result.ok();
    }

    /**
     * 我拉黑的用户列表(设置-隐私的黑名单管理用)。
     * 只查"我拉黑的人",不查"拉黑我的人" —— 后者不该让用户看到。
     */
    @GetMapping("/blacklist")
    public Result<CursorPage<UserVO>> blacklistList(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(relationService.blacklistList(me, lastId, size));
    }

    @GetMapping("/status")
    public Result<RelationStatusVO> status(@RequestParam("uid") long uid, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(relationService.status(me, uid));
    }
}
