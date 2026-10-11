package com.campus.social.service.impl;

import com.campus.social.exception.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传魔数校验的单测。
 *
 * 设计:不依赖 Spring 容器 / 真实文件 IO,用反射把 UploadServiceImpl 的
 * @Value 字段灌上,absUploadRoot 指向临时目录,直接走 upload() 完整路径:
 *
 *  - 真 JPEG / PNG / GIF / WEBP / MP4 / WebM / MKV 头字节 → 通过
 *  - PHP 脚本伪装 .jpg(最常见攻击载荷)→ 拒收
 *  - HTML 伪装 .mp4 → 拒收
 *  - 跨类伪装(GIF 头套 .png)→ 拒收
 *  - 纯 shell 脚本 .sh → 拒收(扩展名白名单挡掉)
 *  - 空文件 → 走 file.isEmpty() 先于签名校验
 *  - 合法上传 → 写盘并返回 /uploads/… 相对路径
 */
class UploadSignatureTest {

    private static UploadServiceImpl svc;
    private static Path tmpRoot;

    @BeforeAll
    static void bootUp() throws Exception {
        svc = new UploadServiceImpl();
        tmpRoot = java.nio.file.Files.createTempDirectory("upload-sig-test");
        setField(svc, "absUploadRoot", tmpRoot);
        setField(svc, "maxImageBytes", 7864320L);
        setField(svc, "maxVideoBytes", 52428800L);
    }

    @Test
    void realSignaturesAccepted() throws UnsupportedEncodingException {
        // JPEG: FFD8FF
        assertOk("jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02});
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        assertOk("png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A});
        // GIF: "GIF8"
        assertOk("gif", "GIF89a".getBytes("US-ASCII"));
        // WEBP: 1A 45 46 46 'WEBP'
        assertOk("webp", new byte[]{0x1A, 'E', 'F', 'F', 'W', 'E', 'B', 'P'});
        // MP4: 00000021 'ftyp'
        assertOk("mp4",
                new byte[]{0x00, 0x00, 0x00, 0x21, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
        // WebM / MKV: EBML 头 1A 45 DF A3
        byte[] ebml = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42};
        assertOk("webm", ebml);
        assertOk("mkv", ebml);
    }

    @Test
    void spoofedExtensionsRejected() {
        // PHP 脚本伪装 .jpg:攻击者最常见的载荷
        BusinessException e1 = assertThrows(BusinessException.class, () ->
                svc.upload(mf("evil.jpg", "image/jpeg",
                        "<?php system($_GET['cmd']); ?>\n".getBytes("US-ASCII")), "image"));
        assertTrue(e1.getMessage().contains(".jpg"));

        // HTML 伪装 .mp4
        assertThrows(BusinessException.class, () ->
                svc.upload(mf("evil.mp4", "video/mp4",
                        "<html><script>alert(1)</script></html>".getBytes("US-ASCII")), "video"));

        // 跨类伪装:GIF 头套 .png
        assertThrows(BusinessException.class, () ->
                svc.upload(mf("evil.png", "image/png", "GIF89a".getBytes("US-ASCII")), "image"));

        // 纯 shell 脚本:.sh 不在扩展名白名单,直接被挡
        assertThrows(BusinessException.class, () ->
                svc.upload(mf("shell.sh", "text/x-shellscript",
                        "#!/bin/sh\nrm -rf /".getBytes("US-ASCII")), "image"));
    }

    @Test
    void emptyFileRejected() {
        BusinessException e = assertThrows(BusinessException.class, () ->
                svc.upload(mf("empty.jpg", "image/jpeg", new byte[0]), "image"));
        assertEquals("文件为空", e.getMessage());
    }

    @Test
    void realValidUploadSucceedsAndWritesToDisk() {
        String rel = svc.upload(
                mf("photo.jpg", "image/jpeg",
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                                   'J', 'F', 'I', 'F', 0x00, 0x01, 0x02, 0x03}),
                "image");
        assertTrue(rel.startsWith("/uploads/"), "应返回 /uploads/… 相对路径,实际 " + rel);
        assertTrue(rel.endsWith(".jpg"));
        // 落盘校验:文件确实存在于临时目录下
        // rel 形如 /uploads/20261010/abcd.jpg
        String day = rel.replace("/uploads/", "").split("/")[0];
        String fileName = rel.substring(rel.lastIndexOf('/') + 1);
        Path expected = tmpRoot.resolve(day).resolve(fileName);
        assertTrue(java.nio.file.Files.exists(expected), "应写入 " + expected);
    }

    // ─── helpers ───

    /** 按扩展名判定 kind,构造 MockMultipartFile 走完整 upload()。 */
    private void assertOk(String ext, byte[] head) throws UnsupportedEncodingException {
        boolean video = ext.equals("mp4") || ext.equals("webm") || ext.equals("mkv");
        String kind = video ? "video" : "image";
        String rel = svc.upload(mf("real-" + ext + "." + ext, mimeOf(ext), head), kind);
        assertTrue(rel.endsWith("." + ext), "返回路径应保留 .%s 后缀,实际 %s".formatted(ext, rel));
    }

    private static MockMultipartFile mf(String name, String mime, byte[] content) {
        return new MockMultipartFile("f", name, mime, content);
    }

    private static String mimeOf(String ext) {
        return switch (ext) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            default -> "application/octet-stream";
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
