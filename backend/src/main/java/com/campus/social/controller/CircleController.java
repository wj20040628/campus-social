package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.Result;
import com.campus.social.dto.ActivityDTO;
import com.campus.social.dto.CircleBanMemberDTO;
import com.campus.social.dto.CircleDTO;
import com.campus.social.dto.CircleMovePostDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.ActivityService;
import com.campus.social.service.CircleAdminService;
import com.campus.social.service.CircleService;
import com.campus.social.vo.ActivityVO;
import com.campus.social.vo.CircleMemberVO;
import com.campus.social.vo.CircleVO;
import com.campus.social.vo.PostVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class CircleController {

    private final CircleService circleService;
    private final CircleAdminService circleAdminService;
    private final ActivityService activityService;

    public CircleController(CircleService circleService,
                            CircleAdminService circleAdminService,
                            ActivityService activityService) {
        this.circleService = circleService;
        this.circleAdminService = circleAdminService;
        this.activityService = activityService;
    }

    // ---------------- 圈子 ----------------

    @PostMapping("/circle")
    public Result<Map<String, Long>> createCircle(@Valid @RequestBody CircleDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long id = circleService.createCircle(me, dto);
        return Result.ok(Map.of("circleId", id));
    }

    @GetMapping("/circle")
    public Result<CursorPage<CircleVO>> list(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(circleService.list(me != null ? me : 0, lastId, size));
    }

    @GetMapping("/circle/{id}")
    public Result<CircleVO> detail(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(circleService.detail(id, me != null ? me : 0));
    }

    @PostMapping("/circle/{id}/join")
    public Result<Void> join(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleService.join(id, me);
        return Result.ok();
    }

    @PostMapping("/circle/{id}/leave")
    public Result<Void> leave(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleService.leave(id, me);
        return Result.ok();
    }

    // ---------------- 圈管理 / 吧主级权限 ----------------
    // 吧主级动作 top/takedown/move 需 role<=2;成员处置 ban/appoint 需 role=1 圈主。
    // 非圈成员/无权限一律 403 NOT_CIRCLE_OWNER(1018)。

    /** 置顶圈内帖子(同圈仅留 1 个置顶)。 */
    @PostMapping("/circle/{id}/post/{postId}/top")
    public Result<Void> topPost(@PathVariable("id") long id, @PathVariable("postId") long postId,
                                HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.topPost(postId, me);
        return Result.ok();
    }

    /** 取消置顶。 */
    @DeleteMapping("/circle/{id}/post/{postId}/top")
    public Result<Void> untopPost(@PathVariable("id") long id, @PathVariable("postId") long postId,
                                  HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.untopPost(postId, me);
        return Result.ok();
    }

    /** 圈内帖子下架(status=2 软,作者可自查)。 */
    @PostMapping("/circle/{id}/post/{postId}/takedown")
    public Result<Void> takedownPost(@PathVariable("id") long id, @PathVariable("postId") long postId,
                                     HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.takedownPost(postId, me);
        return Result.ok();
    }

    /** 移帖:改归属圈(toCircleId=null 移回广场)。 */
    @PostMapping("/circle/{id}/post/{postId}/move")
    public Result<Void> movePost(@PathVariable("id") long id, @PathVariable("postId") long postId,
                                 @RequestBody CircleMovePostDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.movePost(postId, dto.getToCircleId(), me);
        return Result.ok();
    }

    /** 圈主任命圈管理(role 3→2)。 */
    @PostMapping("/circle/{id}/member/{userId}/admin")
    public Result<Void> appointAdmin(@PathVariable("id") long id, @PathVariable("userId") long userId,
                                     HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.appointAdmin(id, userId, me);
        return Result.ok();
    }

    /** 取消圈管理(role 2→3)。 */
    @DeleteMapping("/circle/{id}/member/{userId}/admin")
    public Result<Void> revokeAdmin(@PathVariable("id") long id, @PathVariable("userId") long userId,
                                    HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.revokeAdmin(id, userId, me);
        return Result.ok();
    }

    /** 封禁圈内成员(软封禁:保留成员行,禁发帖/评论/点赞;until=null 长期)。 */
    @PostMapping("/circle/{id}/member/{userId}/ban")
    public Result<Void> banMember(@PathVariable("id") long id, @PathVariable("userId") long userId,
                                  @RequestBody(required = false) CircleBanMemberDTO dto,
                                  HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.banMember(id, userId, dto == null ? null : dto.getUntil(), me);
        return Result.ok();
    }

    /** 解除成员封禁。 */
    @PostMapping("/circle/{id}/member/{userId}/unban")
    public Result<Void> unbanMember(@PathVariable("id") long id, @PathVariable("userId") long userId,
                                    HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        circleAdminService.unbanMember(id, userId, me);
        return Result.ok();
    }

    /** 列圈内成员(供管理面板):role + banStatus + banUntil。 */
    @GetMapping("/circle/{id}/members")
    public Result<List<CircleMemberVO>> members(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long viewer = me != null ? me : 0;
        return Result.ok(circleAdminService.members(id, viewer));
    }

    @GetMapping("/circle/{id}/posts")
    public Result<CursorPage<PostVO>> circlePosts(
            @PathVariable("id") long id,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(circleService.circlePosts(id, lastId, size, me != null ? me : 0));
    }

    // ---------------- 活动 ----------------

    @PostMapping("/activity")
    public Result<Map<String, Long>> createActivity(@Valid @RequestBody ActivityDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long id = activityService.create(me, dto);
        return Result.ok(Map.of("activityId", id));
    }

    @GetMapping("/activity/circle")
    public Result<CursorPage<ActivityVO>> activitiesByCircle(
            @RequestParam("circleId") long circleId,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(activityService.listByCircle(circleId, lastId, size, me != null ? me : 0));
    }

    @GetMapping("/activity/{id}")
    public Result<ActivityVO> activityDetail(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(activityService.detail(id, me != null ? me : 0));
    }

    @PostMapping("/activity/{id}/signup")
    public Result<Void> signup(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        activityService.signup(id, me);
        return Result.ok();
    }

    @PostMapping("/activity/{id}/cancel")
    public Result<Void> cancel(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        activityService.cancel(id, me);
        return Result.ok();
    }
}
