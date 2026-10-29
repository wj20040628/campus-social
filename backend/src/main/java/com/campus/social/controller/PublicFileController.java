package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.service.FileService;
import com.campus.social.vo.FileVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件公开分享页数据（免登录）。
 *
 * <p>只返回已过审文件，且不暴露 objectKey / 驳回理由 / 我的状态。
 * 分享页 {@code /f/{id}} 与下载链接都依赖它。
 */
@RestController
@RequestMapping("/public/files")
public class PublicFileController {

    private final FileService fileService;

    public PublicFileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/{id}")
    public Result<FileVO> detail(@PathVariable("id") long id, HttpServletRequest req) {
        return Result.ok(fileService.publicDetail(id, clientIp(req)));
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real : req.getRemoteAddr();
    }
}
