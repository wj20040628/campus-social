package com.campus.social.util;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档类文件的扩展名 + 魔数双检（2026-10-27）。
 *
 * <p>与 {@link FileMagic} 分开：那个工具只认图片/视频，文档的白名单、
 * 容器校验和文本编码检查是另一套规则。两者都不看「声明后缀」单独放行。
 */
public final class FileDocMagic {

    /** v1 只收这些；zip/exe/脚本一律不进来（压缩包内容人工审核看不全） */
    private static final Set<String> EXT_ALLOW = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "md");

    /** OOXML 容器必须至少含这个条目，挡「随便一个 zip 改后缀」 */
    private static final String OOXML_MARKER = "[Content_Types].xml";

    private FileDocMagic() {
    }

    public static boolean isAllowedExt(String ext) {
        return ext != null && EXT_ALLOW.contains(ext.toLowerCase(Locale.ROOT));
    }

    public static Set<String> allowedExts() {
        return EXT_ALLOW;
    }

    /**
     * 按扩展名校验真实内容。不支持的扩展名直接 false。
     *
     * @param ext   小写扩展名（不含点）
     * @param bytes 完整文件字节（≤3MB，已在调用方卡过大小）
     */
    public static boolean matches(String ext, byte[] bytes) {
        if (ext == null || bytes == null || bytes.length < 4) {
            return false;
        }
        String e = ext.toLowerCase(Locale.ROOT);
        switch (e) {
            case "pdf":
                return startsWith(bytes, "%PDF".getBytes(StandardCharsets.US_ASCII));
            case "doc":
            case "ppt":
            case "xls":
                // OLE2 复合文档头
                return bytes.length >= 8
                        && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                        && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0
                        && (bytes[4] & 0xFF) == 0xA1 && (bytes[5] & 0xFF) == 0xB1
                        && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0xE1;
            case "docx":
            case "pptx":
            case "xlsx":
                return isOoxml(bytes);
            case "txt":
            case "md":
                return isUtf8Text(bytes);
            default:
                return false;
        }
    }

    private static boolean isOoxml(byte[] bytes) {
        // 本地文件头 PK\x03\x04；空 zip 不是合法 OOXML
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K'
                || bytes[2] != 3 || bytes[3] != 4) {
            return false;
        }
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (OOXML_MARKER.equals(entry.getName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static boolean isUtf8Text(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false; // 二进制文件常含 NUL；纯文本不该有
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
