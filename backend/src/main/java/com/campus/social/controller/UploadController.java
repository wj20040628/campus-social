package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.common.ResultCode;
import com.campus.social.config.OssProperties;
import com.campus.social.exception.BusinessException;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.OssService;
import com.campus.social.service.OssStsService;
import com.campus.social.service.UploadResult;
import com.campus.social.service.UploadService;
import com.campus.social.util.OssSignUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/upload")
public class UploadController {

    private final UploadService uploadService;
    private final OssService ossService;
    private final OssStsService ossStsService;
    private final OssProperties ossProps;

    public UploadController(UploadService uploadService, OssService ossService,
                            OssStsService ossStsService, OssProperties ossProps) {
        this.uploadService = uploadService;
        this.ossService = ossService;
        this.ossStsService = ossStsService;
        this.ossProps = ossProps;
    }

    /**
     * 上传:multipart 字段名 file
     * @param kind image | video
     * @return {"url": "/uploads/.../xxx.webp", "kind": kind, "thumbUrl"?: "/uploads/.../xxx_t.webp"}
     *         图片且压缩成功时附带 thumbUrl(头像/列表用小图,详情用 url 原图)
     */
    @PostMapping
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "kind", defaultValue = "image") String kind,
                                              HttpServletRequest req) {
        // M7:kind 枚举校验 —— 只接受 image / video,非法值 400(此前任意值静默按图片处理)
        if (!"image".equals(kind) && !"video".equals(kind)) {
            throw new com.campus.social.exception.BusinessException(
                    com.campus.social.common.ResultCode.BAD_REQUEST,
                    "kind 只支持 image / video");
        }
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        UploadResult res = uploadService.upload(file, kind, me != null ? me : 0L);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", res.url());
        m.put("kind", res.kind());
        if (res.thumbUrl() != null) {
            m.put("thumbUrl", res.thumbUrl());
        }
        return Result.ok(m);
    }

    /**
     * 能力探测(无副作用,只读):返回当前上传配置。
     * 前端据此判断视频功能开没开(videoEnabled)+ 单个视频能传多大(maxVideoBytes)。
     * videoEnabled=false 时前端隐藏视频入口;maxVideoBytes 用于本地拒收超大文件。
     *
     * @return {"videoEnabled":true,"maxVideoBytes":10485760,"maxImageBytes":7864320}
     */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.ok(uploadService.uploadConfig());
    }

    /**
     * 直传 OSS 的「临时凭据 + 上传目标 + 回调参数」一站式下发(直传链路 P2)。
     *
     * <p>前端在真正 PUT 到 OSS 之前调一次本接口,拿到:
     * <ul>
     *   <li>sts:STS 临时令牌(accessKeyId/secret/securityToken/expiration),scope 锁
     *       uploads/{me}/* ,过期时间前端用来判断要不要重取;</li>
     *   <li>key:服务端生成的完整对象 key(uploads/{me}/{day}/{uuid}.ext),前端不可改,
     *       保证越权写不了别人前缀,且回调能据此解析出归属;</li>
     *   <li>uploadHost:直传 PUT 目标 host(https://{bucket}.{endpoint});</li>
     *   <li>callback / callbackVar:分别塞进请求头 {@code x-oss-callback} /
     *       {@code x-oss-callback-var}(已 base64),OSS 上传完成后原样回传服务端做验签。</li>
     * </ul>
     *
     * <p>本地 dev 未配 AK 时 {@code ossService.isConfigured()=false},前端应走普通
     * {@code POST /upload} 服务端兜底,本接口直接 503 让前端降级。
     *
     * @param ext 文件扩展名(jpg/png/mp4...),用于决定对象 key 后缀与签名覆盖范围
     */
    /** 直传扩展名白名单(与 UploadServiceImpl 的 multipart 校验同口径;2026-10-23 加,审查 P0-13) */
    private static final java.util.Set<String> DIRECT_IMG_EXT =
            java.util.Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final java.util.Set<String> DIRECT_VIDEO_EXT =
            java.util.Set.of("mp4", "webm", "mov", "mkv");

    @GetMapping("/sts")
    public Result<Map<String, Object>> sts(@RequestParam("ext") String ext,
                                           HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (me == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        }
        if (!ossService.isConfigured()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "OSS 未配置,请走普通上传");
        }
        if (!ossProps.isDirectUploadEnabled()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "OSS 直传未开启,请走普通上传");
        }
        if (ossProps.getCallbackUrl().isBlank()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "OSS 回调地址未配置");
        }
        /*
         * ext 白名单(2026-10-23 加,审查 P0-13):之前 ext 原样拼进对象 key,
         * 攻击者拿 ext=gif 的 key + STS 后 PUT 内容 "GIF89a<script>..." 且
         * Content-Type: text/html —— 回调只验魔数前缀(GIF 魔数仅 3 字节),
         * 落库成合法 image,读链路 302 到 OSS 域即以 text/html 渲染 = 存储型 XSS。
         * 白名单与 POST /upload 的 IMG_EXT/VIDEO_EXT 同口径。
         */
        String e = ext == null ? "" : ext.trim().toLowerCase();
        if (e.startsWith(".")) {
            e = e.substring(1);
        }
        if (!DIRECT_IMG_EXT.contains(e) && !DIRECT_VIDEO_EXT.contains(e)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "不支持的文件类型,仅允许: " + String.join("/", DIRECT_IMG_EXT) + "/" + String.join("/", DIRECT_VIDEO_EXT));
        }

        OssStsService.StsToken sts = ossStsService.getStsToken(me);
        String key = ossService.generateObjectKey(me, e);
        // 服务端按扩展名推导 mimeType 并纳入签名(审查 P0-14):
        // 客户端 PUT 时的 Content-Type 不受控,回调里 OSS 回传的 mimeType 又来自对象元数据,
        // 签名覆盖 key|mimeType 后,篡改任一字段验签必失败。
        String mimeType = OssSignUtil.mimeOf(e);
        String sign = OssSignUtil.signCallback(ossProps.getCallbackKey(), key, mimeType);

        // 回调 JSON:OSS 上传完成后 POST 到 callbackUrl,body 含 object/size/mimeType/sign
        String callbackHost;
        try {
            callbackHost = new URL(ossProps.getCallbackUrl()).getHost();
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "OSS 回调地址非法");
        }
        String callbackJson = "{\"callbackUrl\":\"" + ossProps.getCallbackUrl()
                + "\",\"callbackHost\":\"" + callbackHost
                + "\",\"callbackBody\":\"object=${object}&size=${size}&mimeType=${mimeType}&sign=${x:sign}\""
                + ",\"callbackBodyType\":\"application/x-www-form-urlencoded\"}";
        // 自定义变量:服务端用共享密钥对 object|mimeType 签名,前端只透传,拿不到密钥
        String callbackVarJson = "{\"x:sign\":\"" + sign + "\"}";

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uploadHost", "https://" + ossProps.getBucket() + "." + ossProps.getEndpoint());
        m.put("key", key);
        m.put("callback", OssSignUtil.base64(callbackJson));
        m.put("callbackVar", OssSignUtil.base64(callbackVarJson));
        m.put("sts", Map.of(
                "accessKeyId", sts.accessKeyId(),
                "accessKeySecret", sts.accessKeySecret(),
                "securityToken", sts.securityToken(),
                "expiration", sts.expiration()));
        return Result.ok(m);
    }
}
