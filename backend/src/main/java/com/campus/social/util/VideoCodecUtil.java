package com.campus.social.util;

/**
 * 视频编码探测(OSS 接入收尾 / 视频兼容性)。
 *
 * <p>浏览器(尤其 Chrome/Edge,无 HEVC 扩展)不支持 H.265/HEVC 播放,
 * 用户手机拍的 mp4 默认常是 HEVC,传上去后 {@code <video>} 报
 * MEDIA_ERR_SRC_NOT_SUPPORTED(中文界面显示「没有找到支持的视频格式和 MIME 类型」),
 * 且 Safari 能播、Chrome 不能,表现诡异。上传即拦下,返回明确提示,比传完才失败友好。
 *
 * <p>判定:扫全量视频字节找 HEVC 样本条目 / 配置 box 的 fourcc:
 * hvc1 / hev1(HEVC 视频轨)、dvh1(Dolby Vision,基于 HEVC)、hvcC(HEVC 配置 box)。
 * 上传接口已把全文件读进内存(data),故扫全量可覆盖 moov 在文件末尾(faststart 未启用)的情况。
 */
public final class VideoCodecUtil {

    private VideoCodecUtil() {
    }

    public static boolean isHevc(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        return contains(data, "hvc1") || contains(data, "hev1")
                || contains(data, "dvh1") || contains(data, "hvcC");
    }

    private static boolean contains(byte[] data, String s) {
        byte[] pat = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int n = data.length - pat.length;
        for (int i = 0; i <= n; i++) {
            boolean ok = true;
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }
}
