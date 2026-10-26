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
 * <p>凭据两种模式：固定 AK（读客户端用只读 AK，上传用 AK 换 STS 临时令牌）；
 * ecs_ram_role（ECS 实例 RAM 角色，读写都用实例元数据自动发放、自动轮换的临时凭证，服务器无 AK）。
 */
@Service
@Slf4j
public class OssService {

    private final OssProperties props;
    private final OssStsService stsService;
    private final OSS readClient;
    /** 内网域名读客户端:ECS 后端拉文件头/回调魔数校验走它,免公网流量费 */
    private final OSS internalReadClient;
    /** 走 ECS 实例 RAM 角色凭据:读写都不需要固定 AK,由实例元数据自动发放并轮换 */
    private final boolean ecsRoleMode;

    public OssService(OssProperties props, OssStsService stsService) {
        this.props = props;
        this.stsService = stsService;
        this.ecsRoleMode = "ecs_ram_role".equalsIgnoreCase(props.getCredentialType());
        if (ecsRoleMode) {
            // 同一个凭据提供者给两个客户端:凭据在 credentials-java 内部缓存/提前刷新,
            // 不需要每次请求都打实例元数据。
            EcsRamRoleCredentialsProvider creds = new EcsRamRoleCredentialsProvider(props.getEcsRoleName());
            this.readClient = new OSSClientBuilder().build("https://" + props.getEndpoint(), creds);
            this.internalReadClient = new OSSClientBuilder().build("https://" + props.getInternalEndpoint(), creds);
            log.info("OSS 使用 ECS 实例 RAM 角色凭据, roleName={}", props.getEcsRoleName().isBlank() ? "(自动发现)" : props.getEcsRoleName());
            if (props.isDirectUploadEnabled()) {
                log.warn("前端直传需要固定 AK 换 STS, ecs_ram_role 模式下已自动关闭");
            }
        } else if (!props.getReadAccessKeyId().isBlank() && !props.getReadAccessKeySecret().isBlank()) {
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
        /*
         * 不设置 response-content-type(2026-10-26 移除)。
         * 阿里云 OSS 对 GET 签名 URL 明确禁止覆盖 Content-Type:带上会直接 400
         * InvalidRequest(0017-00000902),线上表现为所有图片 302 后加载失败。
         * 内容类型改由写入时保证:服务端上传在 putObject 里按后缀设置 ObjectMetadata;
         * 前端直传(当前关闭)由回调做魔数校验,不依赖 URL 覆盖响应头。
         */
        if (process != null && !process.isBlank()) {
            req.setProcess(process);
        }
        URL url = readClient.generatePresignedUrl(req);
        return url.toString();
    }

    /** OSS 是否可用：实例角色模式直接可用；固定 AK 模式要求两对 AK 均非空。 */
    public boolean isConfigured() {
        if (ecsRoleMode) {
            return true;
        }
        return !props.getStsAccessKeyId().isBlank() && !props.getStsAccessKeySecret().isBlank()
                && !props.getReadAccessKeyId().isBlank() && !props.getReadAccessKeySecret().isBlank();
    }

    /** 是否走 ECS 实例 RAM 角色（无固定 AK）；读链路的 302 不能长时间缓存签名 URL */
    public boolean isEcsRoleMode() {
        return ecsRoleMode;
    }

    /** 是否开启前端直传（STS）。关时前端走 POST /upload 服务端兜底。
     *  备案过审前为 false：直传依赖 OSS 回调到已备案域名，备案中不可达会传失败。
     *  实例角色模式没有固定 AK 可换 STS，直传自动关闭。 */
    public boolean isDirectUploadEnabled() {
        return props.isDirectUploadEnabled() && !ecsRoleMode;
    }

    /** 服务端兜底上传：固定 AK 模式用 STS 临时令牌（scope 限 uploads/{userId}/*）；
     *  实例角色模式直接用角色的临时凭据，权限由 RAM 策略约束在桶的 uploads/ 前缀内。 */
    public void putObject(Long userId, String objectKey, InputStream in, String contentType) {
        ObjectMetadata meta = new ObjectMetadata();
        if (contentType != null && !contentType.isBlank()) {
            meta.setContentType(contentType);
        }
        if (ecsRoleMode) {
            readClient.putObject(props.getBucket(), objectKey, in, meta);
            return;
        }
        OssStsService.StsToken sts = stsService.getStsToken(userId);
        OSS client = new OSSClientBuilder().build(
                "https://" + props.getEndpoint(),
                sts.accessKeyId(), sts.accessKeySecret(), sts.securityToken());
        try {
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

    /**
     * 查对象真实大小(2026-10-23 加,审查 P0-14):回调里的 size 参数由请求方给出,
     * 签发时又无法预知,不能作为大小限制的依据 —— 必须回查 OSS 元数据。
     *
     * @return 字节数;查不到(对象不存在/AK 未配/网络失败)返回 -1,
     *         调用方按 fail-closed 拒绝(不能把 -1 当成"很小"放行)
     */
    public long objectSize(String objectKey) {
        long via = tryStatSize(internalReadClient, objectKey);
        if (via >= 0) {
            return via;
        }
        return tryStatSize(readClient, objectKey);
    }

    private long tryStatSize(OSS client, String objectKey) {
        if (client == null) {
            return -1;
        }
        try {
            ObjectMetadata meta = client.getObjectMetadata(props.getBucket(), objectKey);
            return meta.getContentLength();
        } catch (Exception e) {
            log.debug("stat object fail: key={}", objectKey, e);
            return -1;
        }
    }
}
