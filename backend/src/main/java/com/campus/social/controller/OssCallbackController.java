package com.campus.social.controller;

import com.campus.social.config.OssProperties;
import com.campus.social.entity.UploadFile;
import com.campus.social.mapper.UploadFileMapper;
import com.campus.social.service.OssService;
import com.campus.social.util.FileMagic;
import com.campus.social.util.OssSignUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Set;

/**
 * OSS 上传回调(直传链路 P2)。
 *
 * <p>前端拿 STS 直传 OSS 成功后,阿里云 OSS 主动 POST 到这里(走公网回调域名,
 * 已在 WebMvcConfig 放通鉴权拦截器,因为 OSS 服务器不带用户 token)。
 *
 * <p>职责(收到即代表「文件已落 OSS」,本端点只为它补「归属记录 + 安全校验」):
 * <ol>
 *   <li>验签:用 {@code OSS_CALLBACK_KEY} 对 object key 重算 HMAC,与回传的 {@code sign} 比对
 *       —— 只有经我们 {@code GET /upload/sts} 授权过的上传才有合法签名,挡掉伪造回调。</li>
 *   <li>解析 userId:object key 形如 {@code uploads/{userId}/{day}/{uuid}.ext},userId 由服务端
 *       生成、STS 策略又锁死只能写自己前缀,故可信任为上传者。</li>
 *   <li>魔数校验:经内网域名拉文件头(免流量费),核对真实内容类别与声明的 mimeType 一致,
 *       挡掉「用图片扩展名传可执行文件」——直传链路唯一的服务端内容校验点。</li>
 *   <li>防重放/幂等:同 object 已记过归属直接返回 200,避免 OSS 重试造成重复记录。</li>
 *   <li>落库:插 {@code upload_file},url 用规范相对路径 {@code /uploads/...},
 *       后续头像/群头像归属校验(isAvatarAllowed)与读链路(MediaController P3)都吃这同一个格式。</li>
 * </ol>
 *
 * <p>任一校验不过一律返回 403 让 OSS 把这次上传判失败(前端会看到上传失败),对象留在桶里
 * 成为孤儿,S6 孤儿清理统一回收;本端点不删对象(只读 AK 无 DeleteObject 权限)。
 */
@Controller
@RequestMapping("/upload")
public class OssCallbackController {

    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mov", "mkv");

    private final OssProperties props;
    private final OssService ossService;
    private final UploadFileMapper uploadFileMapper;

    @Value("${upload.max-image-bytes:7864320}")
    private long maxImageBytes;

    @Value("${upload.max-video-bytes:10485760}")
    private long maxVideoBytes;

    @Value("${upload.video-enabled:true}")
    private boolean videoEnabled;

    public OssCallbackController(OssProperties props, OssService ossService, UploadFileMapper uploadFileMapper) {
        this.props = props;
        this.ossService = ossService;
        this.uploadFileMapper = uploadFileMapper;
    }

    @PostMapping("/oss-callback")
    public ResponseEntity<String> callback(HttpServletRequest req) {
        String object = req.getParameter("object");
        String size = req.getParameter("size");
        String mimeType = req.getParameter("mimeType");
        String sign = req.getParameter("sign");

        if (object == null || sign == null) {
            return forbidden("missing params");
        }
        // 1) 验签
        if (props.getCallbackKey().isBlank()) {
            return forbidden("callback key not configured");
        }
        String expected = OssSignUtil.signCallback(props.getCallbackKey(), object);
        if (!expected.equals(sign)) {
            return forbidden("sign mismatch");
        }
        // 2) 解析 + 校验 object key 形态
        Long userId = parseUserId(object);
        if (userId == null) {
            return forbidden("bad object key");
        }
        // 3) 大小 / 视频开关(直传无 multipart 预校验,这里兜底)
        if (size != null) {
            try {
                long bytes = Long.parseLong(size);
                boolean isVideo = mimeType != null && mimeType.startsWith("video/");
                if (isVideo && !videoEnabled) {
                    return forbidden("video disabled");
                }
                long max = isVideo ? maxVideoBytes : maxImageBytes;
                if (bytes > max) {
                    return forbidden("too large");
                }
            } catch (NumberFormatException ignored) {
                return forbidden("bad size");
            }
        }
        // 4) 魔数校验(内网拉头,免流量费)
        byte[] head = ossService.readHeadBytes(object, 16);
        boolean isVideo = mimeType != null && mimeType.startsWith("video/");
        if (!FileMagic.matches(head, isVideo)) {
            return forbidden("content mismatch");
        }
        // 5) 幂等 + 落库
        String rel = "/" + object;
        if (uploadFileMapper.ownerOf(rel) != null) {
            return ok();
        }
        UploadFile uf = new UploadFile();
        uf.setUserId(userId);
        uf.setUrl(rel);
        uf.setKind(kindOf(object));
        uf.setIsDeleted(0);
        uploadFileMapper.insert(uf);
        return ok();
    }

    /** object = uploads/{userId}/{day}/{uuid}.ext → 取 userId;形态不符返回 null。 */
    private Long parseUserId(String object) {
        if (object == null || !object.startsWith(props.getKeyPrefix() + "/")) {
            return null;
        }
        String[] seg = object.split("/");
        if (seg.length < 4) {
            return null;
        }
        try {
            return Long.parseLong(seg[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String kindOf(String object) {
        String ext = object.contains(".") ? object.substring(object.lastIndexOf('.') + 1).toLowerCase() : "";
        return VIDEO_EXT.contains(ext) ? "video" : "image";
    }

    private ResponseEntity<String> ok() {
        return ResponseEntity.ok("{\"status\":\"ok\"}");
    }

    private ResponseEntity<String> forbidden(String reason) {
        return ResponseEntity.status(403).body("{\"status\":\"forbidden\",\"reason\":\"" + reason + "\"}");
    }
}
