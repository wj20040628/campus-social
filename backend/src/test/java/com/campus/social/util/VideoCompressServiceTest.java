package com.campus.social.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策/解析逻辑为纯函数单测,恒跑;真实编解码用例依赖本机 ffmpeg
 * (环境变量 FFMPEG_PATH 或 PATH 上的 ffmpeg),缺失时 Assumptions 跳过,CI 无 ffmpeg 也绿。
 */
class VideoCompressServiceTest {

    @Test
    void needsCompressDecisionTable() {
        // 横屏盒子 1280x720
        assertTrue(VideoCompressService.needsCompress(1920, 1080, 30));
        assertTrue(VideoCompressService.needsCompress(3840, 2160, 30));
        assertFalse(VideoCompressService.needsCompress(1280, 720, 30));
        assertFalse(VideoCompressService.needsCompress(640, 480, 30));
        // 竖屏盒子 720x1280
        assertTrue(VideoCompressService.needsCompress(1080, 1920, 30));
        assertFalse(VideoCompressService.needsCompress(720, 1280, 30));
        // 方形按短边 720 收
        assertTrue(VideoCompressService.needsCompress(1000, 1000, 30));
        assertFalse(VideoCompressService.needsCompress(720, 720, 30));
        // 帧率超 30 也压(60fps 流量翻倍)
        assertTrue(VideoCompressService.needsCompress(1280, 720, 60));
        assertTrue(VideoCompressService.needsCompress(1280, 720, 59.94));
        assertFalse(VideoCompressService.needsCompress(1280, 720, 29.97));
        // 探测不全:尺寸未知不压;帧率未知(0)不触发帧率规则
        assertFalse(VideoCompressService.needsCompress(0, 0, 60));
        assertFalse(VideoCompressService.needsCompress(1280, 720, 0));
    }

    @Test
    void parseProbeReadsResolutionAndFps() {
        // 真实 ffmpeg -i 输出形态:流 id [0x1e0] 与 avc1 0x31637661 不得误判为分辨率
        String text = "Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'in.mp4':\n"
                + "  Stream #0:0[0x1e0]: Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), "
                + "1920x1080 [SAR 1:1 DAR 16:9], 60 fps, 60 tbr, 15360 tbn\n"
                + "  Stream #0:1: Audio: aac, 48000 Hz, stereo\n";
        VideoCompressService.VideoInfo info = VideoCompressService.parseProbe(text);
        assertNotNull(info);
        assertEquals(1920, info.width());
        assertEquals(1080, info.height());
        assertEquals(60, info.fps());
    }

    @Test
    void parseProbePortraitAndTbrFallback() {
        VideoCompressService.VideoInfo portrait = VideoCompressService.parseProbe(
                "  Stream #0:0: Video: h264, yuv420p, 1080x1920, 30 fps, 30 tbr\n");
        assertNotNull(portrait);
        assertEquals(1080, portrait.width());
        assertEquals(1920, portrait.height());
        // 部分容器只给 tbr 不给 fps,回退读 tbr
        VideoCompressService.VideoInfo webm = VideoCompressService.parseProbe(
                "  Stream #0:0: Video: vp9, yuv420p, 1280x720, 29.97 tbr, 1k tbn\n");
        assertNotNull(webm);
        assertEquals(1280, webm.width());
        assertEquals(720, webm.height());
        assertEquals(29.97, webm.fps());
    }

    @Test
    void parseProbeUnparseableReturnsNull() {
        assertNull(VideoCompressService.parseProbe(null));
        assertNull(VideoCompressService.parseProbe("not a video at all"));
        assertNull(VideoCompressService.parseProbe("  Stream #0:0: Audio: mp3, 44100 Hz\n"));
        assertNull(VideoCompressService.parseProbe("  Stream #0:0: Video: h264, none\n"));
    }

    @Test
    void disabledShortCircuitsBeforeFfmpegCheck() {
        VideoCompressService svc = new VideoCompressService();
        ReflectionTestUtils.setField(svc, "enabled", false);
        ReflectionTestUtils.setField(svc, "ffmpegPath", "definitely-not-exist-ffmpeg");
        assertNull(svc.compress(new byte[]{1, 2, 3}, "mp4"));
    }

    @Test
    void missingFfmpegKeepsOriginal() {
        VideoCompressService svc = serviceWith("definitely-not-exist-ffmpeg-xyz");
        assertNull(svc.compress(new byte[]{0, 0, 0, 1}, "mp4"));
    }

    @Test
    void compresses1080p60To720p30() throws Exception {
        String ffmpeg = ffmpegOrNull();
        Assumptions.assumeTrue(ffmpeg != null, "本机无 ffmpeg,跳过实测");
        byte[] src = generate(ffmpeg, 1920, 1080, 60, 18);
        VideoCompressService.VideoInfo srcInfo = probeBytes(ffmpeg, src);
        assertNotNull(srcInfo);
        assertEquals(60, srcInfo.fps(), "片源必须是 60fps,否则本用例没压到帧率规则");
        byte[] out = serviceWith(ffmpeg).compress(src, "mp4");
        assertNotNull(out, "1080p60 应触发压缩");
        assertTrue(out.length < src.length, "产物应更小: " + out.length + " vs " + src.length);
        VideoCompressService.VideoInfo info = probeBytes(ffmpeg, out);
        assertNotNull(info);
        assertEquals(1280, info.width());
        assertEquals(720, info.height());
        assertTrue(info.fps() > 0 && info.fps() <= 30, "帧率应压到 30 内且可探测: " + info.fps());
    }

    @Test
    void compressesPortraitOversizeTo720x1280() throws Exception {
        String ffmpeg = ffmpegOrNull();
        Assumptions.assumeTrue(ffmpeg != null, "本机无 ffmpeg,跳过实测");
        byte[] src = generate(ffmpeg, 1080, 1920, 30, 18);
        byte[] out = serviceWith(ffmpeg).compress(src, "mp4");
        assertNotNull(out, "竖屏 1080x1920 应触发压缩");
        VideoCompressService.VideoInfo info = probeBytes(ffmpeg, out);
        assertNotNull(info);
        assertEquals(720, info.width());
        assertEquals(1280, info.height());
    }

    @Test
    void keepsAlready720pUntouched() throws Exception {
        String ffmpeg = ffmpegOrNull();
        Assumptions.assumeTrue(ffmpeg != null, "本机无 ffmpeg,跳过实测");
        byte[] src = generate(ffmpeg, 1280, 720, 30, 23);
        assertNull(serviceWith(ffmpeg).compress(src, "mp4"), "已达标 720p30 不应重编码");
    }

    @Test
    void garbageInputKeepsOriginal() throws Exception {
        String ffmpeg = ffmpegOrNull();
        Assumptions.assumeTrue(ffmpeg != null, "本机无 ffmpeg,跳过实测");
        assertNull(serviceWith(ffmpeg).compress("this is not a video".getBytes(), "mp4"));
    }

    // ---- helpers ----

    private static VideoCompressService serviceWith(String ffmpeg) {
        VideoCompressService svc = new VideoCompressService();
        ReflectionTestUtils.setField(svc, "enabled", true);
        ReflectionTestUtils.setField(svc, "ffmpegPath", ffmpeg);
        return svc;
    }

    private static String ffmpegOrNull() {
        String path = System.getenv("FFMPEG_PATH");
        if (path == null || path.isBlank()) {
            path = "ffmpeg";
        }
        try {
            Process p = new ProcessBuilder(path, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 用 ffmpeg 自身生成测试片源(lavfi 合成图案+正弦音轨),不依赖任何素材文件 */
    private static byte[] generate(String ffmpeg, int w, int h, int rate, int crf) throws Exception {
        Path out = Files.createTempFile("ys-vtest-", ".mp4");
        try {
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=" + w + "x" + h + ":rate=" + rate,
                    "-f", "lavfi", "-i", "sine=frequency=440",
                    "-t", "2", "-c:v", "libx264", "-preset", "ultrafast", "-crf", String.valueOf(crf),
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "生成片源超时");
            assertEquals(0, p.exitValue());
            return Files.readAllBytes(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static VideoCompressService.VideoInfo probeBytes(String ffmpeg, byte[] data) throws Exception {
        Path in = Files.createTempFile("ys-vprobe-", ".mp4");
        Path log = Files.createTempFile("ys-vprobe-log-", ".txt");
        try {
            Files.write(in, data);
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-i", in.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            assertTrue(p.waitFor(30, TimeUnit.SECONDS));
            return VideoCompressService.parseProbe(Files.readString(log));
        } finally {
            Files.deleteIfExists(in);
            Files.deleteIfExists(log);
        }
    }
}