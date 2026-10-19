package com.campus.social.service;

/**
 * 上传结果(OSS 接入 P2 降本①②)。
 *
 * <p>原图 URL 恒有;缩略图 URL 仅当「图片且压缩开启且成功生成」时非空。
 * 前端(头像/列表)可用 {@code thumbUrl} 取小图,详情用 {@code url} 原图;
 * 当前前端不读 thumbUrl 也不影响(多出的字段忽略即可,resolveUrl 零改动)。
 */
public record UploadResult(String url, String thumbUrl, String kind) {
}
