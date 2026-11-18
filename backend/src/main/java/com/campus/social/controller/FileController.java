package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.FileService;
import com.campus.social.util.RequestIp;
import com.campus.social.vo.FilePageVO;
import com.campus.social.vo.FileQuotaVO;
import com.campus.social.vo.FileVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文件库（2026-10-27）。
 *
 * <p>上传与所有写操作要求登录；列表/详情/下载/预览对游客开放只读
 * （AuthInterceptor 游客白名单），游客只能看到已过审文件。
 * 分享落地页 {@code /public/files/{id}} 同样免登录。
 */
@RestController
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping
    public Result<FilePageVO> list(
            @RequestParam(value = "tab", defaultValue = "all") String tab,
            @RequestParam(value = "sub", required = false) String sub,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "sort", defaultValue = "latest") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(fileService.list(me == null ? 0L : me, tab, sub, category, keyword, sort, page, size));
    }

    @GetMapping("/quota")
    public Result<FileQuotaVO> quota(HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(fileService.quota(me));
    }

    @PostMapping
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam("category") String category,
            @RequestParam(value = "description", required = false) String description,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long fileId = fileService.upload(me, file, title, category, description);
        return Result.ok(Map.of("fileId", fileId, "status", 0));
    }

    @GetMapping("/{id}")
    public Result<FileVO> detail(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(fileService.detail(me == null ? 0L : me, id, RequestIp.of(req)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        fileService.delete(me, id);
        return Result.ok();
    }

    @PostMapping("/{id}/like")
    public Result<Void> like(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        fileService.like(me, id);
        return Result.ok();
    }

    @DeleteMapping("/{id}/like")
    public Result<Void> unlike(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        fileService.unlike(me, id);
        return Result.ok();
    }

    @PostMapping("/{id}/favorite")
    public Result<Void> favorite(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        fileService.favorite(me, id);
        return Result.ok();
    }

    @DeleteMapping("/{id}/favorite")
    public Result<Void> unfavorite(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        fileService.unfavorite(me, id);
        return Result.ok();
    }

    /** 免登录下载：只返回已过审文件的短签名 URL / 本地流，按 IP 或 uid 限流 */
    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return fileService.download(id, me, RequestIp.of(req));
    }

    /**
     * 在线预览：PDF / TXT / MD 直接 inline 返回，其余格式 422 提示下载。
     * 未过审文件只有上传者本人能预览（带 token 的 fetch 请求）。
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return fileService.preview(id, me, RequestIp.of(req));
    }
}
