package com.campus.social.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载探针的判据（2026-10-28）。
 *
 * <p>直链方案依赖「签名 URL 的 response-content-disposition 覆盖生效」。
 * 探针如果只看状态码，OSS 照常回 200 却忽略覆盖参数时会被判成可用，
 * 用户的文件名继续是坏的——所以判据必须落在响应头上。
 */
class FileStorageServiceDispositionTest {

    @Test
    void overriddenHeader_isAccepted() {
        String ours = "attachment; filename*=UTF-8''%E5%BD%A2%E7%AD%96_%E5%8E%9F%E8%91%97.docx";
        assertTrue(FileStorageService.dispositionOverridden(ours));
    }

    @Test
    void objectMetadataFallback_isRejected() {
        // 对象上那份 Spring 生成的兜底名：夸克会把它和 filename* 拼起来
        String legacy = "attachment; filename=\"=?UTF-8?Q?=E5=BD=A2=E7=AD=96.docx?=\"; "
                + "filename*=UTF-8''%E5%BD%A2%E7%AD%96.docx";
        assertFalse(FileStorageService.dispositionOverridden(legacy),
                "出现 =? 说明覆盖没生效，应回落到服务端转发");
    }

    @Test
    void missingOrPlainHeader_isRejected() {
        assertFalse(FileStorageService.dispositionOverridden(null));
        assertFalse(FileStorageService.dispositionOverridden("attachment"));
        assertFalse(FileStorageService.dispositionOverridden("inline"));
    }
}
