package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.service.UploadService;
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
     * @return {"url": "/uploads/20260914/xxx.mp4"}
     */
    @PostMapping
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "kind", defaultValue = "image") String kind) {
        String rel = uploadService.upload(file, kind);
        return Result.ok(Map.of("url", rel, "kind", kind));
    }
}
