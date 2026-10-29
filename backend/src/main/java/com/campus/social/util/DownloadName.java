package com.campus.social.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 下载响应头里的文件名（2026-10-28）。
 *
 * <p><b>为什么不用 Spring 的 ContentDisposition</b>：它给非 ASCII 名生成的兜底是
 * RFC 2047（{@code filename="=?UTF-8?Q?=E5=BD=A2...?="}），国内安卓下载器
 * （实测夸克）会把 {@code filename=} 和 {@code filename*=} **两段拼起来**，
 * 用户看到「=E5=BD=A2=E7=AD=96...形策_原阅读模板.docx」。
 *
 * <p><b>只发 filename*</b>：RFC 5987 的百分号编码是唯一能表达中文、又不会被
 * 拼贴的写法。Chrome / Safari / Firefox / 安卓 DownloadManager 都认；
 * 极老的客户端会退化成 URL 最后一段（uuid.docx），至少不是乱码。
 *
 * <p>文件名来自用户上传，必须清掉控制字符与引号——CR/LF 进入响应头就是
 * 响应头注入（可被用来塞 Set-Cookie）。
 */
public final class DownloadName {

    private DownloadName() {
    }

    /**
     * @param fileName 展示用文件名（可含中文、空格）
     * @param ext      扩展名兜底，fileName 为空时用；可为空
     */
    public static String attachment(String fileName, String ext) {
        return "attachment; filename*=UTF-8''" + encode(safeName(fileName, ext));
    }

    /** 清掉换行/引号/控制字符，并保证非空 */
    static String safeName(String fileName, String ext) {
        String name = fileName == null ? "" : fileName.trim();
        name = name.replaceAll("[\\p{Cntrl}\"\\\\/]", "_");
        if (name.isBlank()) {
            name = "download" + (ext == null || ext.isBlank() ? "" : "." + ext.trim());
        }
        return name;
    }

    /** RFC 5987：空格写成 %20（不能是 +），星号必须转义 */
    static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
    }
}
