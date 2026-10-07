package com.campus.social.service;

/**
 * 文件上传(本地磁盘)
 * 返回相对 URL(如 /uploads/20260914/xxx.jpg),前端拼 location.origin
 */
public interface UploadService {

    /**
     * @return 相对访问路径(如 /uploads/xxx.png)
     * @throws com.campus.social.exception.BusinessException 超限/类型不符
     */
    String upload(org.springframework.web.multipart.MultipartFile file, String kind);

    /** kind: "image" | "video" */
}
