package com.campus.social.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * 图片压缩 / 转 WebP / 缩略图(OSS 接入 P2 降本①②)。
 *
 * <p>仅作用于「后端经手字节」的上传路径({@code POST /upload} 服务端兜底,以及将来可能的
 * 服务端入口);前端直传(P4)字节不过服务器,压缩须在浏览器侧做,或读时靠 OSS
 * {@code x-oss-process}(P3 的 MediaController)。
 *
 * <p>设计要点:
 * <ul>
 *   <li>用 {@link ImageReader#setSourceSubsampling} 按目标最长边下采样读取,内存占用远低于
 *       全量解码(4000×3000 原图 ≈ 36MB,下采样到 480px 仅几十 KB),对 2c2g ECS 友好。</li>
 *   <li>输出优先 WebP(需 webp-imageio 插件在 classpath,有则 {@code ImageIO.write(img,"webp",..)});
 *       插件缺失或编码失败自动回退 JPEG(纯 JDK),保证「压缩是增强不是必需」——绝不因压缩让上传失败。</li>
 *   <li>任何异常都返回 null,调用方退回「存原图」。</li>
 *   <li>GIF 不转主图(会掉动画),但仍生成静态 WebP 缩略图。</li>
 * </ul>
 */
@Slf4j
@Component
public class ImageCompressService {

    @Value("${upload.image.max-edge:1920}")
    private int maxEdge;

    @Value("${upload.image.thumb-edge:480}")
    private int thumbEdge;

    @Value("${upload.image.webp-quality:82}")
    private float webpQuality;

    @Value("${upload.image.enabled:true}")
    private boolean enabled;

    /** webp 插件是否可用(懒探测一次后缓存) */
    private static volatile Boolean webpAvailable;

    public boolean isEnabled() {
        return enabled;
    }

    /** 压缩原图:缩放到 max-edge 内 + 编码 webp/jpeg。非图片或失败返回 null。 */
    public CompressedImage compressOriginal(byte[] src) {
        return encode(src, maxEdge);
    }

    /** 生成缩略图:缩放到 thumb-edge 内 + 编码 webp/jpeg。非图片或失败返回 null。 */
    public CompressedImage makeThumbnail(byte[] src) {
        return encode(src, thumbEdge);
    }

    private CompressedImage encode(byte[] src, int targetEdge) {
        if (!enabled) {
            return null;
        }
        BufferedImage img = readScaled(src, targetEdge);
        if (img == null) {
            return null;
        }
        try {
            if (webpAvailable()) {
                byte[] out = writeWebp(img);
                if (out != null && out.length > 0) {
                    return new CompressedImage("webp", out);
                }
            }
            byte[] out = writeJpeg(img, webpQuality);
            if (out != null && out.length > 0) {
                return new CompressedImage("jpeg", out);
            }
        } catch (Exception e) {
            log.warn("image encode fail (targetEdge={})", targetEdge, e);
        }
        return null;
    }

    /** 按目标最长边下采样读取(整数 subsampling,降低内存) */
    private BufferedImage readScaled(byte[] src, int targetEdge) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(src))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                int scale = Math.max(1, Math.max(w, h) / targetEdge);
                ImageReadParam param = reader.getDefaultReadParam();
                if (scale > 1) {
                    param.setSourceSubsampling(scale, scale, 0, 0);
                }
                return ensureOpaque(reader.read(0, param));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.warn("image read fail", e);
            return null;
        }
    }

    /** 统一转 RGB(无 alpha),避免 WebP/JPEG 直出透明像素时黑底/异常 */
    private BufferedImage ensureOpaque(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private byte[] writeWebp(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            return ImageIO.write(img, "webp", bos) ? bos.toByteArray() : null;
        } catch (IOException e) {
            log.warn("write webp fail", e);
            return null;
        }
    }

    private byte[] writeJpeg(BufferedImage img, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        if (writer == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JPEGImageWriteParam jp = (JPEGImageWriteParam) writer.getDefaultWriteParam();
        jp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        jp.setCompressionQuality(Math.max(0f, Math.min(1f, quality / 100f)));
        try {
            writer.setOutput(new MemoryCacheImageOutputStream(bos));
            writer.write(null, new IIOImage(img, null, null), jp);
        } catch (Exception e) {
            log.warn("write jpeg fail", e);
            return null;
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    private static boolean webpAvailable() {
        if (webpAvailable == null) {
            synchronized (ImageCompressService.class) {
                if (webpAvailable == null) {
                    webpAvailable = ImageIO.getImageWritersByMIMEType("image/webp").hasNext();
                    if (!webpAvailable) {
                        log.warn("webp-imageio 插件未加载,图片压缩回退 JPEG");
                    }
                }
            }
        }
        return webpAvailable;
    }

    /** 压缩产物:format=webp|jpeg,bytes=编码后字节 */
    public record CompressedImage(String format, byte[] bytes) {
    }
}
