package com.campus.social.service.impl;

import cn.hutool.core.io.FileUtil;
import com.campus.social.entity.UploadFile;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UploadFileMapper;
import com.campus.social.service.OssService;
import com.campus.social.service.UploadResult;
import com.campus.social.service.UploadService;
import com.campus.social.util.FileMagic;
import com.campus.social.util.ImageCompressService;
import com.campus.social.util.OssSignUtil;
import com.campus.social.util.VideoCodecUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static com.campus.social.common.ResultCode.*;

/**
 * 本地磁盘上传
 * 量纲(前后端同标准,均可配):单图 ≤ 7.5MB,视频 ≤ 10MB(存储止血,原 50MB)
 * 视频功能总开关:upload.video-enabled=false 时拒收一切 video 上传
 * 目录:按天分目录,便于清理
 *
 * 关键:upload.dir 在 @PostConstruct 里一次性规范化为【绝对路径】并建好根目录,
 * 写盘(transferTo)和 WebMvcConfig 静态映射共用同一个绝对路径,消除进程 CWD 漂移——
 * 否则 Tomcat 会把 MultipartFile.transferTo(相对路径) 解析到临时目录,导致
 * "写进了 A 处、静态映射读的是 B 处" 的空白问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private final UploadFileMapper uploadFileMapper;
    private final OssService ossService;
    private final ImageCompressService imageCompressService;

    @Value("${upload.dir:./uploads}")
    private String baseDir;

    /** 启动时固定的绝对路径:写盘与静态映射共用 */
    private Path absUploadRoot;

    @Value("${upload.max-image-bytes:7864320}")
    private long maxImageBytes;

    @Value("${upload.max-video-bytes:10485760}")
    private long maxVideoBytes;

    /** 视频功能总开关:false=拒收一切 video 上传(前端据此隐藏视频入口)。 */
    @Value("${upload.video-enabled:true}")
    private boolean videoEnabled;

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
    public UploadResult upload(MultipartFile file, String kind, long userId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BAD_REQUEST, "文件为空");
        }
        String ext = extOf(file.getOriginalFilename()).toLowerCase();
        long max = "video".equals(kind) ? maxVideoBytes : maxImageBytes;
        Set<String> allow = "video".equals(kind) ? VIDEO_EXT : IMG_EXT;
        // 视频功能总开关:false 时直接拒收 video 上传(比等大小/类型/魔数校验更早,省 I/O)
        if ("video".equals(kind) && !videoEnabled) {
            throw new BusinessException(BAD_REQUEST, "视频上传已暂停,请改用图片");
        }
        if (!allow.contains(ext)) {
            throw new BusinessException(BAD_REQUEST, "不支持的文件类型: ." + ext);
        }
        if (file.getSize() > max) {
            throw new BusinessException(PAYLOAD_TOO_LARGE,
                    "文件超过 " + (kind.equals("video") ? mb(max) : mb(maxImageBytes)) + " 限制");
        }
        // 一次性读全量字节:魔数校验 + 压缩 + 上传 OSS / 落盘都复用,避免多次消费 MultipartFile 流。
        // 魔数校验:只看扩展名不够 —— 攻击者可用 .jpg 后缀套 shell 脚本/可执行文件。
        // 与实际内容签名比对,不符拒收(共用 FileMagic,与直传回调校验同一套规则)。
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            log.error("read upload bytes fail", e);
            throw new BusinessException(INTERNAL_ERROR, "读取文件失败");
        }
        byte[] head = data.length >= MAGIC_LEN ? Arrays.copyOf(data, MAGIC_LEN) : data;
        if (!FileMagic.matches(head, "video".equals(kind))) {
            throw new BusinessException(BAD_REQUEST,
                    "文件内容与后缀 ." + ext + " 不符,请上传真实的图片/视频文件");
        }
        // 视频编码预检:浏览器(尤其 Chrome/Edge,无 HEVC 扩展)不支持 H.265/HEVC,
        // 传上去也播不了(报「没有找到支持的视频格式和 MIME 类型」),且 Safari 能播 Chrome 不能。
        // 上传即拦下返回明确提示,比传完才失败友好。
        if ("video".equals(kind) && VideoCodecUtil.isHevc(data)) {
            throw new BusinessException(BAD_REQUEST,
                    "视频编码不支持(H.265/HEVC)，请用 H.264 编码的 mp4 重新上传");
        }

        // ---- 决定实际落库内容:图片压缩 / 缩略图(降本①②) ----
        byte[] storeData = data;
        String storeExt = ext;
        ImageCompressService.CompressedImage thumb = null;
        String thumbRel = null;
        if ("image".equals(kind) && imageCompressService.isEnabled()) {
            // GIF 动画保留原图(转 WebP 掉动画),但仍生成静态 WebP 缩略图
            boolean transcodeMain = !"gif".equals(ext);
            ImageCompressService.CompressedImage comp =
                    transcodeMain ? imageCompressService.compressOriginal(data) : null;
            if (comp != null) {
                storeData = comp.bytes();
                storeExt = comp.format();
            }
            thumb = imageCompressService.makeThumbnail(data);
        }

        // 统一对象 key / 相对 URL:uploads/{userId}/{day}/{uuid}.ext
        // OSS 直传与本地兜底共用同一形态,读链路(MediaController P3)按 URL 直接映射回 OSS key,
        // DB 仍存 /uploads/...,前端 resolveUrl 零改动。
        String key = ossService.generateObjectKey(userId, storeExt);
        String rel = "/" + key;
        if (thumb != null) {
            // 缩略图 key = 主图 key 末尾扩展名前插 _t(同源 uuid,便于对应/清理)
            thumbRel = "/" + key.replaceFirst("(\\.[^.]+)$", "_t." + thumb.format());
        }

        if (ossService.isConfigured()) {
            // 服务端兜底直传 OSS(STS 临时令牌,scope 锁 uploads/{userId}/*)。
            // 生产上普通上传一般走前端直传(P4),这里是 dev / 异常兜底,以及将来可能保留的服务端入口。
            ossService.putObject(userId, key, new ByteArrayInputStream(storeData), OssSignUtil.mimeOf(storeExt));
            if (thumb != null && thumbRel != null) {
                ossService.putObject(userId, thumbRel.substring(1),
                        new ByteArrayInputStream(thumb.bytes()), OssSignUtil.mimeOf(thumb.format()));
            }
        } else {
            // 本地磁盘兜底(开发态未配 AK):写到 absUploadRoot/{userId}/{day}/{fileName},
            // 与 key 形态一致,WebMvcConfig 静态映射 /uploads/** 直接服务。
            writeLocal(key, storeData);
            if (thumb != null && thumbRel != null) {
                writeLocal(thumbRel.substring(1), thumb.bytes());
            }
        }

        // 记录归属:头像/群头像引用该文件时校验「确属该用户上传」,
        // 堵住引用他人文件的越权。userId 由 controller 注入(当前登录人)。
        // 缩略图也记归属:头像若用 thumbUrl,校验 isAvatarAllowed 才能通过;同 owner。
        insertOwnership(rel, userId, kind);
        if (thumbRel != null) {
            insertOwnership(thumbRel, userId, "image");
        }

        log.info("upload ok: kind={}, size={}, rel={}, thumb={}, owner={}, oss={}",
                kind, file.getSize(), rel, thumbRel, userId, ossService.isConfigured());
        return new UploadResult(rel, thumbRel, kind);
    }

    /** 本地磁盘兜底写:objectKey=uploads/{userId}/{day}/{file} → absUploadRoot/{userId}/{day}/{file} */
    private void writeLocal(String objectKey, byte[] bytes) {
        String[] ks = objectKey.split("/"); // [uploads, userId, day, fileName]
        Path targetDir = absUploadRoot.resolve(ks[1]).resolve(ks[2]);
        try {
            File dir = FileUtil.mkdir(targetDir.toFile());
            File dest = new File(dir, ks[3]);
            try (OutputStream os = new FileOutputStream(dest)) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.error("upload fail: dest={}", targetDir, e);
            throw new BusinessException(INTERNAL_ERROR, "上传失败");
        }
    }

    /** 插 upload_file 归属记录(mapper 为 null 的单测场景跳过) */
    private void insertOwnership(String url, long userId, String kind) {
        UploadFile uf = new UploadFile();
        uf.setUserId(userId);
        uf.setUrl(url);
        uf.setKind(kind);
        uf.setIsDeleted(0);
        if (uploadFileMapper != null) {
            uploadFileMapper.insert(uf);
        }
    }

    @Override
    public boolean isAvatarAllowed(String avatarUrl, long me) {
        // 只有 /uploads 前缀的 URL 需要校验归属 —— 外部图床/老数据没有 upload_file 记录,放行
        String path = toPath(avatarUrl);
        if (path == null || !path.startsWith("/uploads/")) {
            return true; // 非本服务上传目录,无归属记录,放行
        }
        // 查归属
        Long owner = uploadFileMapper.ownerOf(path);
        if (owner == null) {
            // 无记录:新格式(含 userId 段的 /uploads/{userId}/...)视作越权引用,拒(S2 防越权);
            // 老格式(2 段 /uploads/{day}/{file},upload_file 表里没有)兼容放行,避免历史头像全挂。
            boolean legacy = path.substring("/uploads/".length()).split("/").length < 3;
            return legacy;
        }
        return owner == me;
    }

    /** 绝对 URL 取 path;非绝对(相对 /uploads/...)原样当路径用 */
    private String toPath(String avatarUrl) {
        try {
            return new java.net.URL(avatarUrl).getPath();
        } catch (Exception e) {
            return avatarUrl;
        }
    }

    private String extOf(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }

    /** bytes → 人类可读 MB 文案(上限值可能调,不能写死) */
    private static String mb(long bytes) {
        return (bytes / 1024 / 1024) + "MB";
    }

    @Override
    public Map<String, Object> uploadConfig() {
        return Map.of(
                "videoEnabled", videoEnabled,
                "maxVideoBytes", maxVideoBytes,
                "maxImageBytes", maxImageBytes,
                // 前端据此决定走直传(P4,true)还是普通 POST /upload 服务端兜底(false,本地 dev/备案中)
                "ossEnabled", ossService.isConfigured() && ossService.isDirectUploadEnabled()
        );
    }

    /** 供 WebMvcConfig 静态映射读取,保证读写同目录 */
    public Path getAbsUploadRoot() {
        return absUploadRoot;
    }
}
