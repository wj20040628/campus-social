package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.FileReviewDTO;
import com.campus.social.dto.FileTakedownDTO;
import com.campus.social.interceptor.RequireAdmin;
import com.campus.social.service.AdminFileService;
import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileVO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员文件审核（2026-10-27）。
 *
 * <p>队列就是 {@code file_asset.status=0}；审核与下架都走条件 UPDATE，
 * 两个管理员并发点同一条只有一个成功，另一个收到 422 刷新队列。
 */
@RestController
@RequestMapping("/admin/files")
@RequireAdmin
public class AdminFileController {

    private final AdminFileService adminFileService;

    public AdminFileController(AdminFileService adminFileService) {
        this.adminFileService = adminFileService;
    }

    @GetMapping
    public Result<FilePageVO> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(adminFileService.list(status, keyword, page, size));
    }

    @GetMapping("/{id}")
    public Result<FileVO> detail(@PathVariable("id") long id) {
        return Result.ok(adminFileService.detail(id));
    }

    /** 管理员审核时可下载待审文件（普通下载端点只放已过审） */
    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable("id") long id) {
        return adminFileService.download(id);
    }

    @PostMapping("/{id}/review")
    public Result<Void> review(@PathVariable("id") long id,
            @Valid @RequestBody FileReviewDTO dto,
            jakarta.servlet.http.HttpServletRequest req) {
        Long adminId = (Long) req.getAttribute(com.campus.social.interceptor.AuthInterceptor.ATTR_USER_ID);
        adminFileService.review(adminId, id, dto.getAction(), dto.getReason());
        return Result.ok();
    }

    @PostMapping("/{id}/takedown")
    public Result<Void> takedown(@PathVariable("id") long id,
            @Valid @RequestBody FileTakedownDTO dto,
            jakarta.servlet.http.HttpServletRequest req) {
        Long adminId = (Long) req.getAttribute(com.campus.social.interceptor.AuthInterceptor.ATTR_USER_ID);
        adminFileService.takedown(adminId, id, dto.getReason());
        return Result.ok();
    }
}
