package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.NotifyService;
import com.campus.social.vo.NotifyVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/notify")
public class NotifyController {

    private final NotifyService notifyService;

    public NotifyController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @GetMapping
    public Result<CursorPage<NotifyVO>> list(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(notifyService.list(me, lastId, size));
    }

    @PostMapping("/read")
    public Result<Void> read(@RequestParam("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        notifyService.read(id, me);
        return Result.ok();
    }

    @PostMapping("/read-all")
    public Result<Void> readAll(HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        notifyService.readAll(me);
        return Result.ok();
    }

    @GetMapping("/unread")
    public Result<Map<String, Integer>> unread(HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(Map.of("count", notifyService.unreadCount(me)));
    }
}
