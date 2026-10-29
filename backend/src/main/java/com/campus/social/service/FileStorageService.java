package com.campus.social.service;

import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.model.OSSObject;
import com.campus.social.service.impl.UploadServiceImpl;
import com.campus.social.util.DownloadName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 文件库对象存储（2026-10-27）。
 *
 * <p><b>为什么不复用 UploadServiceImpl</b>：那条链路把对象写到
 * {@code uploads/} 前缀，而 {@code /uploads/**} 是免登录公共读（MediaController
 * 直接 302 到签名 URL）。文件在审核通过前必须不可访问，审核后下载也要限流，
 * 所以文件放独立的 {@code files/} 前缀，读只能经过 FileController 的下载端点。
 *
 * <p>本地开发无 OSS 时写 {@code upload.dir/files/...}，同样不经过 MediaController
 * 的 {@code /uploads/**} 路由，行为与生产一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 下载方式：auto=先探测再决定（默认）/ oss=强制直链 / proxy=强制服务端转发 */
    private static final String MODE_AUTO = "auto";
    private static final String MODE_OSS = "oss";
    private static final String MODE_PROXY = "proxy";

    private final OssService ossService;
    private final UploadServiceImpl uploadService;

    @Value("${file.presign-ttl-seconds:60}")
    private int presignTtlSeconds;

    @Value("${file.download.mode:" + MODE_AUTO + "}")
    private String downloadMode;

    /** OSS 是否支持用 signature URL 覆盖 Content-Disposition。null=还没探测 */
    private volatile Boolean ossOverrideOk;

    /**
     * 存文件并返回对象 key。Content-Disposition 在写入时固定为 attachment，
     * 下载链接无法把它覆盖成内联渲染。
     */
    public String store(long userId, String ext, byte[] bytes, String downloadName) {
        String key = "files/" + userId + "/" + LocalDate.now().format(DAY)
                + "/" + IdUtil.fastUUID() + "." + ext;
        String disposition = ContentDisposition.attachment()
                .filename(downloadName, StandardCharsets.UTF_8)
                .build()
                .toString();
        if (ossService.isConfigured()) {
            ossService.putObject(userId, key, new ByteArrayInputStream(bytes),
                    MediaType.APPLICATION_OCTET_STREAM_VALUE, disposition);
        } else {
            writeLocal(key, bytes);
        }
        return key;
    }

    /** @return false 表示对象删除失败，调用方可留待下次重试 */
    public boolean delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return true;
        }
        if (ossService.isConfigured()) {
            return ossService.deleteObject(objectKey);
        }
        try {
            Files.deleteIfExists(localPath(objectKey));
            return true;
        } catch (IOException e) {
            log.warn("local file delete fail: key={}, err={}", objectKey, e.getMessage());
            return false;
        }
    }

    /**
     * 下载响应。
     *
     * <p><b>默认走 OSS 直链</b>（2026-10-28 二次修订）：302 到签名 URL，并在 URL 里带
     * {@code response-content-disposition} 覆盖响应头——这样文件字节不经过 ECS，
     * 3Mbps 的小机器不会被下载拖死，同时文件名仍然是我们的 RFC 5987 写法
     * （对象元数据里那个 RFC2047 兜底名会被覆盖掉，夸克不再拼出「=E5=BD=A2…」）。
     *
     * <p><b>为什么需要探测</b>：OSS 对 GET 签名 URL 覆盖 Content-Type 是明确拒绝的
     * （2026-10-26 踩过 400），Content-Disposition 是否同样受限没有官方保证。
     * 首次下载时服务端拿签名 URL 打一个 {@code Range: bytes=0-0} 的探针，
     * 结果缓存到 JVM 生命周期：可用就一直走直链；不可用就整体回落到服务端转发，
     * 保证下载永远能成，只是那时会占带宽（日志里有 warn 标记）。
     *
     * <p>回落到转发模式后下载地址回到同域，微信不会再弹「即将离开微信」；
     * 直链模式下微信仍会提示离开微信——这是直链方案的固有代价，
     * 要两者兼得只能等备案后用自定义域名 CNAME 到 OSS。
     */
    public ResponseEntity<?> downloadResponse(String objectKey, String downloadName, String ext) {
        String disposition = DownloadName.attachment(downloadName, ext);
        if (ossService.isConfigured()) {
            if (MODE_PROXY.equalsIgnoreCase(downloadMode)) {
                return proxyDownload(objectKey, disposition);
            }
            String url = ossService.presignGetUrlWithDisposition(
                    objectKey, presignTtlSeconds, disposition);
            if (MODE_OSS.equalsIgnoreCase(downloadMode) || overrideSupported(url)) {
                return ResponseEntity.status(302)
                        .header(HttpHeaders.LOCATION, url)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .build();
            }
            return proxyDownload(objectKey, disposition);
        }
        return localDownload(objectKey, disposition);
    }

    /** 直链方案是否可用。null=未探测；探测失败一次即永久回落，直到重启 */
    private boolean overrideSupported(String signedUrl) {
        Boolean cached = ossOverrideOk;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (ossOverrideOk != null) {
                return ossOverrideOk;
            }
            boolean ok = probeSignedUrl(signedUrl);
            ossOverrideOk = ok;
            if (ok) {
                log.info("file download: OSS 支持 response-content-disposition，下载走直链");
            } else {
                log.warn("file download: OSS 拒绝 response-content-disposition，"
                        + "本次进程内下载回落到服务端转发（会占用 ECS 带宽）");
            }
            return ok;
        }
    }

    /**
     * 探针：只取 1 字节，但要同时确认两件事——状态 200/206，**且返回的
     * Content-Disposition 真的是我们那份**（含 filename*、不含 RFC2047 兜底）。
     * 只看状态码不够：OSS 可能照常返回 200 却忽略覆盖参数，
     * 那样文件名还是坏的，探针却报「可用」。
     *
     * <p>网络抖动也按「不支持」处理——回落到转发最多是慢，比文件名坏或下载失败强。
     */
    private boolean probeSignedUrl(String signedUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(signedUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                log.warn("file download probe: status={}", code);
                return false;
            }
            String dispo = conn.getHeaderField("Content-Disposition");
            boolean overridden = dispositionOverridden(dispo);
            if (!overridden) {
                log.warn("file download probe: disposition 未被覆盖: {}", dispo);
            }
            return overridden;
        } catch (Exception e) {
            log.warn("file download probe fail: {}", e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 判据：返回头里是我们的 RFC5987 写法，而不是对象上那份 RFC2047 兜底名。
     * 单独成包级静态方法是为了能单测——这个判断错了会静默把坏文件名发给用户。
     */
    static boolean dispositionOverridden(String dispo) {
        return dispo != null && dispo.contains("filename*=UTF-8''") && !dispo.contains("=?");
    }

    /** 回落方案：由本服务转发字节，占 ECS 带宽但一定能下 */
    private ResponseEntity<?> proxyDownload(String objectKey, String disposition) {
        OSSObject obj = ossService.openObject(objectKey);
        if (obj == null) {
            return ResponseEntity.notFound().build();
        }
        long length = obj.getObjectMetadata().getContentLength();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(length)
                .body(new KnownLengthStream(obj.getObjectContent(), length));
    }

    /** 本地开发：直接读磁盘，与生产转发分支同一套响应头 */
    private ResponseEntity<?> localDownload(String objectKey, String disposition) {
        Path path = localPath(objectKey);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }

    /**
     * 已知长度的流式资源。
     *
     * <p>不能直接用 {@link InputStreamResource}：它的 {@code contentLength()} 会把
     * 整条流读完才知道长度，等于把流式下载变成全量读取。这里覆盖成已知值。
     */
    private static final class KnownLengthStream extends InputStreamResource {
        private final long length;

        KnownLengthStream(InputStream in, long length) {
            super(in);
            this.length = length;
        }

        @Override
        public long contentLength() {
            return length;
        }
    }

    /**
     * 在线预览（2026-10-27）。
     *
     * <p>不能复用下载的 302：对象写入时被设成 attachment + octet-stream，
     * OSS 直链会让浏览器下载；签名 URL 又不允许覆盖 Content-Type
     * （2026-10-26 已踩过 400 InvalidRequest）。所以预览统一由后端取字节、
     * 用正确的 Content-Type + inline 返回。文件上限 3MB，内存读取可接受。
     *
     * <p>Cache-Control 给 60 秒私有缓存：重复翻页/刷新不重复拉 OSS；
     * 文件下架后最多 60 秒缓存失效。
     */
    public ResponseEntity<?> previewResponse(String objectKey, String fileName, String contentType) {
        long size;
        if (ossService.isConfigured()) {
            byte[] data = ossService.getObjectBytes(objectKey);
            if (data == null) {
                return ResponseEntity.notFound().build();
            }
            size = data.length;
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                            .filename(fileName, StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentLength(size)
                    .body(data);
        }
        Path path = localPath(objectKey);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        try {
            size = Files.size(path);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(size)
                .body(new FileSystemResource(path));
    }

    /**
     * 管理员审核下载：带 token 的请求走 axios/fetch，不能 302 到 OSS
     * （浏览器直连 OSS 不带 Authorization，且跨域）。≤3MB 一次性读回内存。
     */
    public ResponseEntity<?> downloadBytesResponse(String objectKey, String downloadName) {
        byte[] data;
        if (ossService.isConfigured()) {
            data = ossService.getObjectBytes(objectKey);
        } else {
            Path path = localPath(objectKey);
            try {
                data = Files.exists(path) ? Files.readAllBytes(path) : null;
            } catch (IOException e) {
                log.warn("local file read fail: key={}, err={}", objectKey, e.getMessage());
                data = null;
            }
        }
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(downloadName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(data);
    }

    private void writeLocal(String objectKey, byte[] bytes) {        Path target = localPath(objectKey);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream os = Files.newOutputStream(target)) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.error("file write fail: key={}", objectKey, e);
            throw new com.campus.social.exception.BusinessException(
                    com.campus.social.common.ResultCode.INTERNAL_ERROR, "文件存储失败");
        }
    }

    private Path localPath(String objectKey) {
        Path root = uploadService.getAbsUploadRoot();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return target;
    }
}
