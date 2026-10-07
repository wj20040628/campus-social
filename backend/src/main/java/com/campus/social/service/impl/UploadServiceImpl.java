package com.campus.social.service.impl;

import cn.hutool.core.io.FileUtil;
import com.campus.social.exception.BusinessException;
import com.campus.social.service.UploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

import static com.campus.social.common.ResultCode.*;

/**
 * 本地磁盘上传
 * 量纲(前后端同标准):单图 ≤ 7.5MB,视频 ≤ 50MB
 * 目录:按天分目录,便于清理
 *
 * 关键:upload.dir 在 @PostConstruct 里一次性规范化为【绝对路径】并建好根目录,
 * 写盘(transferTo)和 WebMvcConfig 静态映射共用同一个绝对路径,消除进程 CWD 漂移——
 * 否则 Tomcat 会把 MultipartFile.transferTo(相对路径) 解析到临时目录,导致
 * "写进了 A 处、静态映射读的是 B 处" 的空白问题。
 */
@Slf4j
@Service
public class UploadServiceImpl implements UploadService {

    @Value("${upload.dir:./uploads}")
    private String baseDir;

    /** 启动时固定的绝对路径:写盘与静态映射共用 */
    private Path absUploadRoot;

    @Value("${upload.max-image-bytes:7864320}")
    private long maxImageBytes;

    @Value("${upload.max-video-bytes:52428800}")
    private long maxVideoBytes;

    private static final Set<String> IMG_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mov", "mkv");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 启动时把 upload.dir 规范化为绝对路径 + 建好根目录(此时 CWD 已知,一次锁定) */
    @PostConstruct
    public void init() {
        absUploadRoot = Paths.get(baseDir).toAbsolutePath().normalize();
        FileUtil.mkdir(absUploadRoot.toFile());
        log.info("upload dir resolved to: {}", absUploadRoot.toAbsolutePath());
    }

    @Override
    public String upload(MultipartFile file, String kind) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BAD_REQUEST, "文件为空");
        }
        String ext = extOf(file.getOriginalFilename()).toLowerCase();
        long max = "video".equals(kind) ? maxVideoBytes : maxImageBytes;
        Set<String> allow = "video".equals(kind) ? VIDEO_EXT : IMG_EXT;
        if (!allow.contains(ext)) {
            throw new BusinessException(BAD_REQUEST, "不支持的文件类型: ." + ext);
        }
        if (file.getSize() > max) {
            throw new BusinessException(PAYLOAD_TOO_LARGE,
                    "文件超过 " + (kind.equals("video") ? "50MB" : "7.5MB") + " 限制");
        }

        String day = LocalDate.now().format(DAY);
        // 基于启动时锁定的绝对路径,保证与静态映射同目录
        Path targetDir = absUploadRoot.resolve(day);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            File dir = FileUtil.mkdir(targetDir.toFile());
            File dest = new File(dir, fileName);
            // 用绝对路径 File 走 OutputStream,避开 MultipartFile.transferTo(相对路径)
            // 被 Tomcat 解析到临时目录的坑
            try (OutputStream os = new FileOutputStream(dest)) {
                file.getInputStream().transferTo(os);
            }
        } catch (IOException e) {
            log.error("upload fail: dest={}", targetDir, e);
            throw new BusinessException(INTERNAL_ERROR, "上传失败");
        }
        // 返回相对路径,前端拼 origin(铁律:不写死域名/端口)
        String rel = "/uploads/" + day + "/" + fileName;
        log.info("upload ok: kind={}, size={}, absPath={}, rel={}", kind, file.getSize(), targetDir.resolve(fileName), rel);
        return rel;
    }

    private String extOf(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }

    /** 供 WebMvcConfig 静态映射读取,保证读写同目录 */
    public Path getAbsUploadRoot() {
        return absUploadRoot;
    }
}
