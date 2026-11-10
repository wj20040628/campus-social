package com.campus.social.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 视频压缩(2026-11-10 OSS 降本③):上传经服务器时,超 720p 或超 30fps 的视频
 * 重编码为 720p/30fps H.264 MP4,从源头压流量(存量实测 1080p60 → 720p30 体积 -91%)。
 *
 * <p>与图片压缩同原则:压缩是增强不是必需 —— ffmpeg 缺失、探测失败、编码超时、
 * 产物反而更大时一律返回 null,调用方存原文件,上传永不因压缩失败。
 *
 * <p>仅作用于「后端经手字节」的 POST /upload 路径;生产为实例 RAM 角色模式,
 * 前端直传自动关闭(OssService.isDirectUploadEnabled),全部上传都经此路径。
 *
 * <p>依赖服务器安装 ffmpeg(静态二进制即可;无需 ffprobe,探测用 ffmpeg -i 的输出)。
 */
@Slf4j
@Component
public class VideoCompressService {

    /** 720p 盒子:横屏 1280x720,竖屏 720x1280,按画面方向自适应 */
    private static final int LONG_EDGE = 1280;
    private static final int SHORT_EDGE = 720;
    private static final double MAX_FPS = 30;
    /** 2c2g ECS 上 10MB 视频 veryfast 编码约几秒;120s 是兜底不是预期 */
    private static final int TIMEOUT_SECONDS = 120;

    private static final Pattern DIM = Pattern.compile("(\\d{2,5})x(\\d{2,5})");
    private static final Pattern FPS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+fps");
    private static final Pattern TBR = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+tbr");

    @Value("${upload.video.compress-enabled:true}")
    private boolean enabled;

    /** 默认走 PATH;systemd 默认 PATH 含 /usr/local/bin,静态二进制装那里即可 */
    @Value("${upload.video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    /** 可用性懒探测一次缓存:缺失时每次上传都 spawn 失败进程会刷日志 */
    private volatile Boolean ffmpegAvailable;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 超 720p/30fps 才重编码为 720p H.264 MP4;已达标/失败/产物更大返回 null(存原文件)。
     *
     * @param src 原始视频字节(已过魔数与 HEVC 校验)
     * @param ext 原始扩展名,仅作临时文件后缀;ffmpeg 按内容探测,不依赖后缀
     */
    public byte[] compress(byte[] src, String ext) {
        if (!enabled || src == null || src.length == 0 || !ffmpegAvailable()) {
            return null;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ys-vcompress-");
            Path input = dir.resolve("input." + ext);
            Files.write(input, src);
            VideoInfo info = probe(dir, input);
            if (info == null || !needsCompress(info.width(), info.height(), info.fps())) {
                return null;
            }
            Path output = dir.resolve("output.mp4");
            if (!encode(dir, input, output, info)) {
                return null;
            }
            byte[] out = Files.readAllBytes(output);
            // 已高度压缩的源可能越压越大,产物不小于原文件就没收益,存原文件
            if (out.length == 0 || out.length >= src.length) {
                return null;
            }
            log.info("video compressed: {}x{}@{}fps, {}B -> {}B (-{}%)",
                    info.width(), info.height(), info.fps(), src.length, out.length,
                    100 - out.length * 100 / src.length);
            return out;
        } catch (Exception e) {
            log.warn("video compress fail, keep original: {}", e.getMessage());
            return null;
        } finally {
            deleteDir(dir);
        }
    }

    /** 是否需要重编码:横屏超 1280x720 / 竖屏超 720x1280 / 超 30fps。尺寸未知不压。 */
    static boolean needsCompress(int w, int h, double fps) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        boolean overRes = w >= h
                ? (w > LONG_EDGE || h > SHORT_EDGE)
                : (w > SHORT_EDGE || h > LONG_EDGE);
        return overRes || fps > MAX_FPS;
    }

    /** 从 ffmpeg -i 输出解析首个视频流的分辨率与帧率;解析不到返回 null。 */
    static VideoInfo parseProbe(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\n")) {
            if (!line.contains("Video:")) {
                continue;
            }
            Matcher dim = DIM.matcher(line);
            if (!dim.find()) {
                continue;
            }
            int w = Integer.parseInt(dim.group(1));
            int h = Integer.parseInt(dim.group(2));
            double fps = 0;
            Matcher f = FPS.matcher(line);
            boolean found = f.find();
            if (!found) {
                f = TBR.matcher(line);
                found = f.find();
            }
            if (found) {
                fps = Double.parseDouble(f.group(1));
            }
            return new VideoInfo(w, h, fps);
        }
        return null;
    }

    private VideoInfo probe(Path dir, Path input) throws IOException, InterruptedException {
        // 不给输出文件时 ffmpeg 必退出码 1,这是探测不是失败;输出落文件避免管道缓冲区死锁
        Path logFile = dir.resolve("probe.log");
        run(logFile, List.of(ffmpegPath, "-hide_banner", "-i", input.toString()));
        return parseProbe(Files.readString(logFile));
    }

    private boolean encode(Path dir, Path input, Path output, VideoInfo info)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", input.toString(),
                // 表达式在滤镜内求值,作用在 autorotate 之后的画面上,旋转过的竖屏视频也能选对盒子
                "-vf", "scale=w='if(gt(iw,ih),min(1280,iw),min(720,iw))':h='if(gt(iw,ih),min(720,ih),min(1280,ih))'"
                        + ":force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-map", "0:v:0", "-map", "0:a?", "-sn",
                "-c:v", "libx264", "-crf", "26", "-preset", "veryfast",
                // 源可能是 yuv444p/10bit,不强制 420p 会编出浏览器播不了的高 profile
                "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "96k",
                "-movflags", "+faststart"));
        if (info.fps() > MAX_FPS) {
            args.addAll(List.of("-r", "30"));
        }
        args.add(output.toString());
        Path logFile = dir.resolve("encode.log");
        int exit = run(logFile, args);
        if (exit != 0) {
            String text = Files.readString(logFile);
            log.warn("video compress encode fail: exit={}, {}", exit,
                    text.substring(Math.max(0, text.length() - 300)));
            return false;
        }
        return true;
    }

    /** 跑 ffmpeg,输出(含 stderr)重定向到日志文件;超时强杀返回 -1 */
    private int run(Path logFile, List<String> args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            return -1;
        }
        return p.exitValue();
    }

    private boolean ffmpegAvailable() {
        Boolean cached = ffmpegAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (ffmpegAvailable == null) {
                ffmpegAvailable = checkFfmpeg();
                if (!ffmpegAvailable) {
                    log.warn("ffmpeg 不可用({}),视频上传保留原文件不压缩;安装 ffmpeg 后重启生效", ffmpegPath);
                }
            }
            return ffmpegAvailable;
        }
    }

    private boolean checkFfmpeg() {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly().waitFor();
                return false;
            }
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 探测结果:宽、高、帧率(解析不到帧率为 0,不触发帧率规则) */
    record VideoInfo(int width, int height, double fps) {
    }
}