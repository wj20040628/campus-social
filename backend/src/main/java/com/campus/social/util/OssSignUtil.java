package com.campus.social.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * OSS 回调验签 + 编码辅助（OSS 接入 P2）。
 *
 * <p>回调鉴权采用「服务端共享密钥 HMAC」而非阿里云 RSA 公钥:
 * 服务端在 {@code GET /upload/sts} 里用 {@code OSS_CALLBACK_KEY} 对 object key 计算签名,
 * 塞进回调自定义变量 {@code x:sign};OSS 完成上传后把 object key 与 {@code x:sign} 原样回传
 * 到 {@code POST /upload/oss-callback};回调服务端用同一密钥重算并比对。
 *
 * <p>为什么够安全:密钥只存在于后端,前端只是「透传」{@code x:sign},拿不到也推不出;
 * 攻击者若想伪造回调,必须能提供某个 object key 的合法签名,而签名覆盖的 key 由服务端
 * 用 uuid 生成,攻击者无法预测他人 key,只能对自己被授权的前缀签名——而 key 里已含 userId,
 * 回调服务端据此解析出归属,伪造不出「别人的文件」。
 */
public final class OssSignUtil {

    private OssSignUtil() {
    }

    /**
     * HMAC-SHA256(callbackKey, objectKey|mimeType),输出小写 hex。
     *
     * <p><b>2026-10-23 改(审查 P0-14)</b>:签名材料从「只签 object」扩展为
     * {@code object + "|" + mimeType},堵住「改用扩展名/按扩展名传内容」这一类伪造 ——
     * 旧签名只覆盖 object,mimeType 与 size 均可被省略或篡改,大小/视频开关失效。
     *
     * <p><b>为什么 size 不进签名</b>:签发发生在 STS 下发时(上传前),此时真实大小未知,
     * 放进材料会导致签发端与回调端永远算不出同一个值。size 的正确性改由
     * {@link com.campus.social.service.OssService#objectSize} 从对象元数据复核:
     * 回调必须携带 size(缺失直接拒),且必须与 OSS 里的真实大小一致,否则拒收。
     */
    public static String signCallback(String callbackKey, String objectKey, String mimeType) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String material = objectKey + "|" + (mimeType == null ? "" : mimeType);
            byte[] raw = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("signCallback failed", e);
        }
    }

    /** UTF-8 字符串 → base64(给 x-oss-callback / callback-var 头用)。 */
    public static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 扩展名 → MIME(上传到 OSS 时写 Content-Type,避免浏览器按扩展名猜)。 */
    public static String mimeOf(String ext) {
        if (ext == null) {
            return "application/octet-stream";
        }
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            default -> "application/octet-stream";
        };
    }
}
