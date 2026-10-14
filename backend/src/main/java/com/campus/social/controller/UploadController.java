package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/upload")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * 上传:multipart 字段名 file
     * @param kind image | video
     * @return {"url": "/uploads/20260914/xxx.mp4", "kind": kind}
     */
    @PostMapping
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "kind", defaultValue = "image") String kind,
                                              HttpServletRequest req) {
        // M7:kind 枚举校验 —— 只接受 image / video,非法值 400(此前任意值静默按图片处理)
        if (!"image".equals(kind) && !"video".equals(kind)) {
            throw new com.campus.social.exception.BusinessException(
                    com.campus.social.common.ResultCode.BAD_REQUEST,
                    "kind 只支持 image / video");
        }
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        String rel = uploadService.upload(file, kind, me != null ? me : 0L);
        return Result.ok(Map.of("url", rel, "kind", kind));
    }
}
