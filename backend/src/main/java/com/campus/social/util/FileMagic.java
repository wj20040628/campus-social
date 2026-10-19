package com.campus.social.util;

/**
 * 文件魔数校验(与扩展名白名单互补,OSS 接入 P2 复用)。
 *
 * <p>只看「实际字节」不看「声明后缀」——扩展名白名单只挡了声明,挡不住
 * 「内容是 PHP 脚本却声明 .jpg」。按真实内容类别判定(图片 / 视频),
 * 比逐扩展名判定更稳,也避免与上传时声明的扩展名耦合。
 */
public final class FileMagic {

    private FileMagic() {
    }

    /**
     * @param head    文件头若干字节(不足 4 字节直接判否,保守)
     * @param isVideo true=按视频签名判定,false=按图片签名判定
     */
    public static boolean matches(byte[] head, boolean isVideo) {
        if (head == null || head.length < 4) {
            return false;
        }
        int b0 = head[0] & 0xFF, b1 = head[1] & 0xFF, b2 = head[2] & 0xFF, b3 = head[3] & 0xFF;
        if (!isVideo) {
            // JPEG  \xFF\xD8\xFF
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                return true;
            }
            // PNG   \x89 P N G
            if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') {
                return true;
            }
            // GIF   G I F
            if (b0 == 'G' && b1 == 'I' && b2 == 'F') {
                return true;
            }
            // WEBP  \x1A E F F
            return b0 == 0x1A && b1 == 'E' && b2 == 'F';
        } else {
            // MP4/MOV/QuickTime: 第 4-7 字节是 "ftyp" / "qt  "
            if (head.length >= 12) {
                String brand = new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if ("ftyp".equals(brand) || "qt  ".equals(brand)) {
                    return true;
                }
            }
            // WebM/MKV: EBML 头 \x1A\x45\xDF\xA3
            return b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3;
        }
    }
}
