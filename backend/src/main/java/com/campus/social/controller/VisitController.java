package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.VisitReportDTO;
import com.campus.social.service.VisitService;
import com.campus.social.util.RequestIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名访客访问记录（2026-11-14）。
 *
 * <p>两个端点都在鉴权白名单里：游客没有 token，进不了登录态。
 * 写入量由前端 30 秒心跳决定，限流按 IP 走 WriteRateLimitInterceptor。
 * 返回体固定为空，前端不需要处理业务数据。
 */
@RestController
@RequestMapping("/visit")
public class VisitController {

    private final VisitService visitService;

    public VisitController(VisitService visitService) {
        this.visitService = visitService;
    }

    @PostMapping("/enter")
    public Result<Void> enter(@Valid @RequestBody VisitReportDTO dto, HttpServletRequest request) {
        visitService.enter(dto, RequestIp.of(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        return Result.ok();
    }

    @PostMapping("/ping")
    public Result<Void> ping(@Valid @RequestBody VisitReportDTO dto) {
        visitService.ping(dto);
        return Result.ok();
    }
}