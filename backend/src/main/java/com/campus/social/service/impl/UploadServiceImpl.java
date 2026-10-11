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
import java.util.Arrays;
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
    /** 魔数校验读取的字节数。PNG/JPEG/GIF/WEBP 签名都在 12 字节内;
     *  MP4 的 "ftyp" 在第 4-7 字节,MOV/QuickTime 是 "qt  " 占位。取 16 够用。 */
    private static final int MAGIC_LEN = 16;
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
        // 魔数校验:只看扩展名不够 —— 攻击者可用 .jpg 后缀套 shell 脚本/可执行文件。
        // 读文件头字节,与实际声明的后缀比对签名,不符拒收。
        byte[] head = headBytes(file);
        if (!signatureMatches(ext, "video".equals(kind), head)) {
            throw new BusinessException(BAD_REQUEST,
                    "文件内容与后缀 ." + ext + " 不符,请上传真实的图片/视频文件");
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

    /** 读文件头 MAGIC_LEN 字节(不足则读到末尾)。失败返回空数组 → 签名校验直接拒收(保守策略)。 */
    private byte[] headBytes(MultipartFile file) {
        byte[] buf = new byte[MAGIC_LEN];
        int n = 0;
        try (java.io.InputStream in = file.getInputStream()) {
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
            }
        } catch (IOException e) {
            log.warn("read file head fail", e);
            return new byte[0];
        }
        return n == buf.length ? buf : Arrays.copyOf(buf, n);
    }

    /** 签名与后缀一致性判定。

     比对的是「实际字节」而非扩展名白名单 —— 扩展名白名单只挡了"声明",
     挡不住"内容是 PHP 但声明 .jpg"。逐类判定:

     - JPEG  (\xFF\xD8\xFF)
     - PNG   (\x89PNG\r\n\x1A\n)
     - GIF   ("GIF8")
     - WEBP  (\x1A\x45\x46\x46)
     - MP4/MOV/QuickTime: 第 4-7 字节是 "ftyp"/"qt  ",且第 0-3 字节是长度 > 8;
       宽松判定:head 长度 >= 12 且 (head[4..8)=="ftyp" 或 head[4..8)=="qt  ")
     - WebM  (\x1A\x45\xDF\xA3,EBML 头)

     未知后缀返回 false → 拒收。这是保守策略:宁可误拒,不可放过。
    */
    private boolean signatureMatches(String ext, boolean isVideo, byte[] head) {
        if (head.length < 4) return false;
        int b0 = head[0] & 0xFF, b1 = head[1] & 0xFF, b2 = head[2] & 0xFF, b3 = head[3] & 0xFF;
        switch (ext) {
            case "jpg":
            case "jpeg":
                return b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF;
            case "png":
                // \x89 P N G
                return b0 == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "gif":
                return head[0] == 'G' && head[1] == 'I' && head[2] == 'F';
            case "webp":
                // \x1A E F F
                return b0 == 0x1A && head[1] == 'E' && head[2] == 'F';
            case "mp4":
            case "mov":
            case "webm":
            case "mkv":
                if (isVideo) {
                    // MP4/MOV/QuickTime: 第 4-7 字节是 "ftyp" 或 "qt  "
                    if (head.length >= 12) {
                        String brand = new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                        if ("ftyp".equals(brand) || "qt  ".equals(brand)) return true;
                    }
                    // WebM/MKV: EBML 头 \x1A\x45\xDF\xA3
                    if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** 供 WebMvcConfig 静态映射读取,保证读写同目录 */
    public Path getAbsUploadRoot() {
        return absUploadRoot;
    }
}
