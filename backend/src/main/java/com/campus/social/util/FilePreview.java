package com.campus.social.util;

import java.util.Locale;

/**
 * 文件在线预览的格式白名单（2026-10-27）。
 *
 * <p>只放浏览器能原生渲染的格式，避免为了「看起来支持预览」把 docx 直接
 * inline 吐给浏览器 —— 那只会得到一个下载或乱码页，比明确提示更糟。
 *
 * <p>txt 与 md 统一按 text/plain + UTF-8 返回：markdown 原文本身是可读的，
 * 而把 md 渲染成 HTML 需要额外的转换与 XSS 防护，不值得为「预览」引入。
 * 字符集必须显式带上，否则 Windows 浏览器可能按 GBK 解码，中文全是乱码。
 */
public final class FilePreview {

    private FilePreview() {
    }

    /** @return 可预览时的 Content-Type；不支持预览返回 null */
    public static String contentType(String ext) {
        if (ext == null || ext.isBlank()) {
            return null;
        }
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "application/pdf";
            case "txt", "md" -> "text/plain;charset=UTF-8";
            default -> null;
        };
    }
}
