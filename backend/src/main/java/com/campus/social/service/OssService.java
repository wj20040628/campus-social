package com.campus.social.service;

import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.common.auth.CredentialsProvider;
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
    /** 内网域名客户端:ECS 后端读写 OSS 都走它,免公网流量费、不占 3Mbps 出带宽 */
    private final OSS internalClient;
    /** 自定义域名客户端(CNAME 到桶):只用来生成下载直链;未配置时为 null */
    private final OSS downloadClient;
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
            this.internalClient = new OSSClientBuilder().build("https://" + props.getInternalEndpoint(), creds);
            this.downloadClient = buildDownloadClient(creds);
            log.info("OSS 使用 ECS 实例 RAM 角色凭据, roleName={}", props.getEcsRoleName().isBlank() ? "(自动发现)" : props.getEcsRoleName());
            if (props.isDirectUploadEnabled()) {
                log.warn("前端直传需要固定 AK 换 STS, ecs_ram_role 模式下已自动关闭");
            }
        } else if (!props.getReadAccessKeyId().isBlank() && !props.getReadAccessKeySecret().isBlank()) {
            this.readClient = new OSSClientBuilder().build(
                    "https://" + props.getEndpoint(),
                    props.getReadAccessKeyId(),
                    props.getReadAccessKeySecret());
            this.internalClient = new OSSClientBuilder().build(
                    "https://" + props.getInternalEndpoint(),
                    props.getReadAccessKeyId(),
                    props.getReadAccessKeySecret());
            this.downloadClient = buildDownloadClient(null);
        } else {
            this.readClient = null;
            this.internalClient = null;
            this.downloadClient = null;
        }
    }

    /**
     * 下载直链客户端：endpoint 换成自定义域名并开启 CNAME 支持，
     * 生成出来的签名 URL 主机就是自家域名。
     *
     * <p>换主机不影响签名：V1 的 canonical resource 固定是 {@code /bucket/key}
     * （见 SDK SignUtils），与 URL 主机无关；OSS 收到 CNAME 请求后按绑定的桶校验。
     */
    private OSS buildDownloadClient(CredentialsProvider provider) {
        String domain = props.getCustomDomain();
        if (domain == null || domain.isBlank()) {
            return null;
        }
        ClientBuilderConfiguration cfg = new ClientBuilderConfiguration();
        cfg.setSupportCname(true);
        String endpoint = "https://" + domain.trim();
        if (provider != null) {
            return new OSSClientBuilder().build(endpoint, provider, cfg);
        }
        if (props.getReadAccessKeyId().isBlank() || props.getReadAccessKeySecret().isBlank()) {
            return null;
        }
        return new OSSClientBuilder().build(endpoint,
                props.getReadAccessKeyId(), props.getReadAccessKeySecret(), cfg);
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
     * 指定有效期的读签名 URL（文件下载用短 TTL：60 秒）。
     * 链接一旦被转发也很快失效，降低匿名下载被当 CDN 用的风险。
     */
    public String presignGetUrl(String objectKey, int ttlSeconds) {
        return presignGetUrl(objectKey, null, ttlSeconds);
    }

    /**
     * 带 OSS 图片处理（x-oss-process）的读签名 URL，用于实时缩略图/水印等。
     * process 为空则等价于 {@link #presignGetUrl(String)}。
     */
    public String presignGetUrl(String objectKey, String process) {
        return presignGetUrl(objectKey, process, props.getPresignTtlSeconds());
    }

    public String presignGetUrl(String objectKey, String process, int ttlSeconds) {
        if (readClient == null) {
            throw new IllegalStateException("OSS 未配置");
        }
        Date expiration = new Date(System.currentTimeMillis() + ttlSeconds * 1000L);
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

    /**
     * 带 disposition 覆盖的读签名 URL（文件下载用，2026-10-28）。
     *
     * <p>为什么覆盖而不是改元数据：对象上的 Content-Disposition 是上传时写的，
     * Spring 生成的是 RFC2047 兜底名（{@code filename="=?UTF-8?Q?...?="}），
     * 夸克这类下载器会把它和 filename* 拼起来。改元数据只能救新文件，
     * 老文件得逐个 copyObject；用 URL 参数覆盖则对所有文件一次生效。
     *
     * <p>用 addQueryParameter 而不是 ResponseHeaderOverrides：后者会把对象里
     * 没设的字段也塞进查询串，一旦带上 content-type 就会命中 OSS 的
     * 「不允许覆盖 Content-Type」限制（2026-10-26 踩过 400）。
     */
    public String presignGetUrlWithDisposition(String objectKey, int ttlSeconds, String disposition) {
        OSS client = downloadClient != null ? downloadClient : readClient;
        if (client == null) {
            throw new IllegalStateException("OSS 未配置");
        }
        Date expiration = new Date(System.currentTimeMillis() + ttlSeconds * 1000L);
        GeneratePresignedUrlRequest req =
                new GeneratePresignedUrlRequest(props.getBucket(), objectKey, HttpMethod.GET);
        req.setExpiration(expiration);
        req.addQueryParameter("response-content-disposition", disposition);
        return client.generatePresignedUrl(req).toString();
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
        putObject(userId, objectKey, in, contentType, null);
    }

    /**
     * 服务端上传（文件库用）：额外写入 Content-Disposition。
     *
     * <p>文件下载必须强制附件而不是内联渲染——尤其 PDF/DOCX 被浏览器
     * 直接打开时可能触发外部程序或脚本。OSS 的 GET 签名 URL 禁止覆盖
     * Content-Type（2026-10-26 已踩过），所以元数据只能在写入时定好。
     */
    public void putObject(Long userId, String objectKey, InputStream in,
            String contentType, String contentDisposition) {
        ObjectMetadata meta = new ObjectMetadata();
        if (contentType != null && !contentType.isBlank()) {
            meta.setContentType(contentType);
        }
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            meta.setContentDisposition(contentDisposition);
        }
        if (ecsRoleMode) {
            /*
             * 服务端兜底上传必须走内网端点(2026-10-29 修):
             * ECS 出公网带宽只有 3Mbps,经公网端点把 20MB 视频写进 OSS 要 ~50 秒,
             * 用户端只看到转圈,以为上传卡死。内网端点同地域免费且不占公网带宽。
             * 万一内网不可达,回退公网(ByteArrayInputStream 可 reset,不会传半个文件)。
             */
            try {
                internalClient.putObject(props.getBucket(), objectKey, in, meta);
                return;
            } catch (RuntimeException e) {
                log.warn("OSS 内网上传失败,回退公网端点: key={}, err={}", objectKey, e.getMessage());
                if (!in.markSupported()) throw e;
                try {
                    in.reset();
                } catch (java.io.IOException ioe) {
                    throw new RuntimeException(ioe);
                }
                readClient.putObject(props.getBucket(), objectKey, in, meta);
                return;
            }
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
     * 删除对象（文件删除/驳回清理用）。
     *
     * <p>失败只返回 false 不抛：删除是尽力而为，DB 记录已不可见，
     * 对象残留不应把用户操作打回失败。清理任务会按 7 天窗口重试。
     */
    public boolean deleteObject(String objectKey) {
        if (readClient == null || objectKey == null || objectKey.isBlank()) {
            return false;
        }
        try {
            readClient.deleteObject(props.getBucket(), objectKey);
            return true;
        } catch (Exception e) {
            log.warn("OSS delete fail: key={}, err={}", objectKey, e.getMessage());
            return false;
        }
    }

    /**
     * 拉对象头若干字节(回调魔数校验用)。优先走内网域名(免公网流量费),
     * 内网不可达(如本机开发机不在阿里云内网)时自动用外网客户端兜底,避免请求卡在 TCP 超时。
     * 失败(对象不存在 / AK 未配 / 网络)返回空数组,交由调用方按 fail-closed 处理。
     */
    public byte[] readHeadBytes(String objectKey, int len) {
        byte[] via = tryReadHead(internalClient, objectKey, len);
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
     * 读整个对象（管理员审核下载用：后台要带 token，不能走 302 让浏览器直连 OSS）。
     * 文件 ≤3MB，一次性读入内存可接受。失败返回 null，由调用方回 404。
     */
    public byte[] getObjectBytes(String objectKey) {
        byte[] via = tryGetBytes(internalClient, objectKey);
        if (via != null) {
            return via;
        }
        return tryGetBytes(readClient, objectKey);
    }

    private byte[] tryGetBytes(OSS client, String objectKey) {
        if (client == null) {
            return null;
        }
        try (OSSObject obj = client.getObject(props.getBucket(), objectKey);
                InputStream in = obj.getObjectContent()) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.debug("read object fail: key={}", objectKey, e);
            return null;
        }
    }

    /**
     * 打开对象做流式下载（2026-10-28）。
     *
     * <p><b>为什么下载不再 302 到 OSS</b>：对象写入时 Spring 生成的
     * Content-Disposition 带了一个 RFC2047 兜底名
     * （{@code filename="=?UTF-8?Q?=E5=BD=A2...?="}），而国内安卓下载器
     * （实测夸克）既读 {@code filename=} 又读 {@code filename*=}，把两段拼起来，
     * 文件名变成「=E5=BD=A2...形策_原著阅读模板.docx」。
     * 302 到 OSS 时我们无法改响应头（GET 签名 URL 禁止覆盖 Content-Type，
     * 2026-10-26 已踩过），只能自己把字节吐出去，头由我们写。
     *
     * <p>顺带解决微信里的「即将离开微信打开」提示：下载地址回到同域，
     * 微信不再把用户往外部域名外跳。
     *
     * <p>走内网域名，ECS→OSS 这段不计公网流量；对外只消耗 ECS 带宽。
     * 调用方负责关闭返回的 OSSObject（它持有连接）。
     *
     * @return 对象流；对象不存在或 OSS 不可用时返回 null
     */
    public OSSObject openObject(String objectKey) {
        if (internalClient != null) {
            OSSObject obj = tryOpen(internalClient, objectKey);
            if (obj != null) {
                return obj;
            }
        }
        return tryOpen(readClient, objectKey);
    }

    private OSSObject tryOpen(OSS client, String objectKey) {
        if (client == null) {
            return null;
        }
        try {
            return client.getObject(props.getBucket(), objectKey);
        } catch (Exception e) {
            log.debug("open object fail: key={}", objectKey, e);
            return null;
        }
    }

    /**
     * 查对象真实大小(2026-10-23 加,审查 P0-14):回调里的 size 参数由请求方给出,
     * 签发时又无法预知,不能作为大小限制的依据 —— 必须回查 OSS 元数据。
     *
     * @return 字节数;查不到(对象不存在/AK 未配/网络失败)返回 -1,
     *         调用方按 fail-closed 拒绝(不能把 -1 当成"很小"放行)
     */
    public long objectSize(String objectKey) {
        long via = tryStatSize(internalClient, objectKey);
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
