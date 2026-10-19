package com.campus.social.service;

import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.campus.social.config.OssProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * OSS 业务封装（OSS 接入 P1 基础设施）。
 *
 * <ul>
 *   <li>{@link #generateObjectKey}：后端按 userId/日期生成对象 key，防越权写他人前缀（S1）</li>
 *   <li>{@link #presignGetUrl}：私有 bucket 读用，后端用只读 AK 生成短期签名 URL，
 *       供后续 MediaController 做 302 重定向（P3）</li>
 *   <li>{@link #putObject}：服务端兜底上传（本地 dev / 生产兜底），经 STS 临时令牌，
 *       scope 锁 uploads/{userId}/*（P2 接管 UploadServiceImpl 时用）</li>
 * </ul>
 *
 * <p>读客户端用外网域名 + 只读 AK 构建一次复用；上传走 STS 临时令牌，每次新建用完即关。
 */
@Service
@Slf4j
public class OssService {

    private final OssProperties props;
    private final OssStsService stsService;
    private final OSS readClient;
    /** 内网域名读客户端:ECS 后端拉文件头/回调魔数校验走它,免公网流量费 */
    private final OSS internalReadClient;

    public OssService(OssProperties props, OssStsService stsService) {
        this.props = props;
        this.stsService = stsService;
        // 读客户端仅在 read AK 配齐时构建:本地 dev 清空 AK(isConfigured=false)时,
        // 不能用空 AK 实例化 OSS 客户端(否则启动报 InvalidCredentialsException 拖垮整棵 bean 树)。
        // 读客户端只在 isConfigured()=true 时被使用,届时必已构建。
        if (!props.getReadAccessKeyId().isBlank() && !props.getReadAccessKeySecret().isBlank()) {
            this.readClient = new OSSClientBuilder().build(
                    "https://" + props.getEndpoint(),
                    props.getReadAccessKeyId(),
                    props.getReadAccessKeySecret());
            this.internalReadClient = new OSSClientBuilder().build(
                    "https://" + props.getInternalEndpoint(),
                    props.getReadAccessKeyId(),
                    props.getReadAccessKeySecret());
        } else {
            this.readClient = null;
            this.internalReadClient = null;
        }
    }

    /**
     * 生成对象 key：uploads/{userId}/{yyyyMMdd}/{uuid}.{ext}
     * userId 来自登录态而非前端传参，避免越权写他人前缀。
     */
    public String generateObjectKey(long userId, String ext) {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String uuid = IdUtil.fastUUID();
        String e = (ext == null || ext.isBlank()) ? "" : (ext.startsWith(".") ? ext : "." + ext);
        return props.getKeyPrefix() + "/" + userId + "/" + day + "/" + uuid + e;
    }

    /** 生成有效期内的读签名 URL（私有 bucket 不可直访，浏览器借此加载） */
    public String presignGetUrl(String objectKey) {
        return presignGetUrl(objectKey, null);
    }

    /**
     * 带 OSS 图片处理（x-oss-process）的读签名 URL，用于实时缩略图/水印等。
     * process 为空则等价于 {@link #presignGetUrl(String)}。
     */
    public String presignGetUrl(String objectKey, String process) {
        Date expiration = new Date(System.currentTimeMillis() + props.getPresignTtlSeconds() * 1000L);
        GeneratePresignedUrlRequest req =
                new GeneratePresignedUrlRequest(props.getBucket(), objectKey, HttpMethod.GET);
        req.setExpiration(expiration);
        if (process != null && !process.isBlank()) {
            req.setProcess(process);
        }
        URL url = readClient.generatePresignedUrl(req);
        return url.toString();
    }

    /** OSS 是否可用：AK 均已配置。本地未填 AK 时为 false，upload 自动回落本地磁盘。 */
    public boolean isConfigured() {
        return !props.getStsAccessKeyId().isBlank() && !props.getStsAccessKeySecret().isBlank()
                && !props.getReadAccessKeyId().isBlank() && !props.getReadAccessKeySecret().isBlank();
    }

    /** 是否开启前端直传（STS）。关时前端走 POST /upload 服务端兜底。
     *  备案过审前为 false：直传依赖 OSS 回调到已备案域名，备案中不可达会传失败。 */
    public boolean isDirectUploadEnabled() {
        return props.isDirectUploadEnabled();
    }

    /** 服务端兜底上传：用 STS 临时令牌，scope 限 uploads/{userId}/* */
    public void putObject(Long userId, String objectKey, InputStream in, String contentType) {
        OssStsService.StsToken sts = stsService.getStsToken(userId);
        OSS client = new OSSClientBuilder().build(
                "https://" + props.getEndpoint(),
                sts.accessKeyId(), sts.accessKeySecret(), sts.securityToken());
        try {
            ObjectMetadata meta = new ObjectMetadata();
            if (contentType != null && !contentType.isBlank()) {
                meta.setContentType(contentType);
            }
            client.putObject(props.getBucket(), objectKey, in, meta);
        } finally {
            client.shutdown();
        }
    }

    /**
     * 拉对象头若干字节(回调魔数校验用)。优先走内网域名(免公网流量费),
     * 内网不可达(如本机开发机不在阿里云内网)时自动用外网客户端兜底,避免请求卡在 TCP 超时。
     * 失败(对象不存在 / AK 未配 / 网络)返回空数组,交由调用方按 fail-closed 处理。
     */
    public byte[] readHeadBytes(String objectKey, int len) {
        byte[] via = tryReadHead(internalReadClient, objectKey, len);
        if (via.length > 0) {
            return via;
        }
        // 内网拉头失败 → 外网客户端兜底(本机开发机连不上内网域名时仍能校验/探测,不再卡超时)
        byte[] viaExt = tryReadHead(readClient, objectKey, len);
        if (viaExt.length > 0) {
            return viaExt;
        }
        return new byte[0];
    }

    private byte[] tryReadHead(OSS client, String objectKey, int len) {
        try {
            GetObjectRequest req = new GetObjectRequest(props.getBucket(), objectKey);
            req.setRange(0, len - 1);
            OSSObject obj = client.getObject(req);
            try (InputStream in = obj.getObjectContent()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            log.debug("read object head fail: key={}", objectKey, e);
            return new byte[0];
        }
    }

    /** 对象是否存在（内网读头 1 字节；不存在 / AK 未配 / 网络失败均返回 false，fail-closed） */
    public boolean objectExists(String objectKey) {
        return readHeadBytes(objectKey, 1).length > 0;
    }
}
