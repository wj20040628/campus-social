package com.campus.social.service.support;

import com.campus.social.entity.FileAsset;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.FileAssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

import static com.campus.social.common.ResultCode.FILE_NOT_AVAILABLE;

/**
 * 文件资产读取与通用文本处理（2026-10-30 从 FileServiceImpl 抽出）。
 */
@Component
@RequiredArgsConstructor
public class FileAssetSupport {

    private final FileAssetMapper fileAssetMapper;

    public FileAsset requireAsset(long fileId) {
        FileAsset asset = fileAssetMapper.selectById(fileId);
        if (asset == null) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
        return asset;
    }

    public void requireApproved(long fileId) {
        FileAsset asset = requireAsset(fileId);
        if (!Integer.valueOf(1).equals(asset.getStatus())) {
            throw new BusinessException(FILE_NOT_AVAILABLE);
        }
    }

    public static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 原文件名只用于展示/下载响应头：去掉路径、控制字符，限长 120 */
    public static String safeFileName(String original, String ext) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) {
            return "file." + ext;
        }
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String suffix = dot > 0 ? name.substring(dot) : "";
            int baseLen = Math.max(1, 120 - suffix.length());
            name = name.substring(0, Math.min(baseLen, name.length())) + suffix;
        }
        return name;
    }

    public static String normIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        String t = ip.trim();
        return t.length() > 64 ? t.substring(0, 64) : t;
    }
}
