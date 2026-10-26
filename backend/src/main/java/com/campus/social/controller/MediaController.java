package com.campus.social.controller;

import com.campus.social.service.OssService;
import com.campus.social.service.impl.UploadServiceImpl;
import com.campus.social.util.OssSignUtil;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OSS 读链路（P3）：私有 bucket 不可直访，浏览器经此端点拿短期签名 URL 再 302 重定向加载。
 * DB 仍存 /uploads/...，前端 resolveUrl 零改动。
 *
 * <p>OSS 配好时：GET /uploads/{userId}/{day}/{file} -> 302 到 presignGetUrl（1h 有效）；
 * 未配 AK（本地 dev 兜底）：直接读本地磁盘 absUploadRoot 下同名文件，等价原 WebMvcConfig 静态映射。
 *
 * <p>?t=1 缩略图：优先用上传时已存的 _t 实体（降本，无图片处理费）；不存在则 OSS x-oss-process
 * 实时出图（花图片处理费）；本地 dev 无处理能力，回退主图。前端拿到的显式 thumbUrl（含 _t.）
 * 会直接命中上面的「已是缩略图实体」分支，原样返回。
 */
@RestController
@RequestMapping("/uploads")
public class MediaController {

    private final OssService ossService;
    private final UploadServiceImpl uploadService;

    private static final List<String> THUMB_FMT = List.of("webp", "jpeg", "jpg", "png");
    private static final String CACHE_CONTROL = "public, max-age=3600";
    /** 实例角色模式的签名 URL 嵌的是 1 小时内的 STS 临时令牌，
     *  302 一旦被浏览器缓存，过期后复用同一条 URL 会 403 —— 读链路必须不缓存重定向 */
    private static final String CACHE_CONTROL_STS = "no-store";

    public MediaController(OssService ossService, UploadServiceImpl uploadService) {
        this.ossService = ossService;
        this.uploadService = uploadService;
    }

    @GetMapping("/**")
    public ResponseEntity<?> get(@RequestParam(value = "t", defaultValue = "0") int thumb,
                                 HttpServletRequest request) {
        // 取 /uploads/ 之后的相对路径（userId/day/file，或老格式 day/file）
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (!ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        if (!uri.startsWith("/uploads/")) {
            return ResponseEntity.notFound().build();
        }
        String rel = uri.substring("/uploads/".length());
        // 防目录穿越：相对路径不得含 .. 或反斜杠
        if (rel.isBlank() || rel.contains("..") || rel.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }

        if (ossService.isConfigured()) {
            return serveFromOss(rel, thumb == 1);
        }
        return serveLocal(rel, thumb == 1);
    }

    private ResponseEntity<?> serveFromOss(String rel, boolean thumb) {
        String objectKey = "uploads/" + rel;
        if (!thumb) {
            return redirect(ossService.presignGetUrl(objectKey));
        }
        // 已经是缩略图实体（URL 含 _t.），直接取
        if (objectKey.contains("_t.")) {
            return redirect(ossService.presignGetUrl(objectKey));
        }
        // 优先用上传时已存的 _t 实体（无图片处理费）
        String stored = resolveStoredThumb(objectKey);
        if (stored != null) {
            return redirect(ossService.presignGetUrl(stored));
        }
        // 无存储缩略图 -> 实时处理（花图片处理费，S12）
        return redirect(ossService.presignGetUrl(objectKey, "image/resize,w_480"));
    }

    /** 在 OSS 里按候选格式探测是否已存 _t 缩略图实体 */
    private String resolveStoredThumb(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        String base = dot < 0 ? objectKey : objectKey.substring(0, dot);
        for (String fmt : THUMB_FMT) {
            String cand = base + "_t." + fmt;
            if (ossService.objectExists(cand)) {
                return cand;
            }
        }
        return null;
    }

    private ResponseEntity<?> serveLocal(String rel, boolean thumb) {
        Path root = uploadService.getAbsUploadRoot();
        Path file = root.resolve(rel).normalize();
        // 防穿越：解析后必须仍在 root 内
        if (!file.startsWith(root) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        Path target = file;
        if (thumb && !rel.contains("_t.")) {
            Path thumbFile = resolveLocalThumb(root, rel);
            if (thumbFile != null) {
                target = thumbFile;
            }
        }
        try {
            String ext = extOf(target.getFileName().toString());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(OssSignUtil.mimeOf(ext)))
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .body(new FileSystemResource(target));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private Path resolveLocalThumb(Path root, String rel) {
        int dot = rel.lastIndexOf('.');
        String base = dot < 0 ? rel : rel.substring(0, dot);
        for (String fmt : THUMB_FMT) {
            Path cand = root.resolve(base + "_t." + fmt).normalize();
            if (cand.startsWith(root) && Files.exists(cand) && Files.isRegularFile(cand)) {
                return cand;
            }
        }
        return null;
    }

    private ResponseEntity<?> redirect(String url) {
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, url)
                .header(HttpHeaders.CACHE_CONTROL, ossService.isEcsRoleMode() ? CACHE_CONTROL_STS : CACHE_CONTROL)
                .build();
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }
}
