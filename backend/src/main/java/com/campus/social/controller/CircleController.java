package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.Result;
import com.campus.social.dto.ActivityDTO;
import com.campus.social.dto.CircleDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.ActivityService;
import com.campus.social.service.CircleService;
import com.campus.social.vo.ActivityVO;
import com.campus.social.vo.CircleVO;
import com.campus.social.vo.PostVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class CircleController {

    private final CircleService circleService;
    private final ActivityService activityService;

    public CircleController(CircleService circleService, ActivityService activityService) {
        this.circleService = circleService;
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
