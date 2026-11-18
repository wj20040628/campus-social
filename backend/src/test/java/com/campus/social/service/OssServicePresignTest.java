package com.campus.social.service;

import com.campus.social.config.OssProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载直链签名的主机选择：配了自定义域名就必须换成自家主机，
 * 没配就保持 bucket.aliyuncs.com。签名本身由 SDK 计算，这里只校验 URL 形状。
 */
class OssServicePresignTest {

    private static final String KEY = "files/4/20261028/abc.docx";
    private static final String DISPO = "attachment; filename*=UTF-8''%E6%A8%AA%E6%A0%BC%E7%BA%B8.docx";

    private OssProperties props(String customDomain) {
        OssProperties p = new OssProperties();
        p.setCredentialType("ak");
        p.setReadAccessKeyId("test-read-ak");
        p.setReadAccessKeySecret("test-read-sk");
        p.setStsAccessKeyId("test-sts-ak");
        p.setStsAccessKeySecret("test-sts-sk");
        p.setCustomDomain(customDomain);
        return p;
    }

    @Test
    void customDomainReplacesBucketHost() {
        OssService svc = new OssService(props("oss.yousheng8.cn"), null);
        String url = svc.presignGetUrlWithDisposition(KEY, 60, DISPO);

        assertTrue(url.startsWith("https://oss.yousheng8.cn/" + KEY + "?"), url);
        assertFalse(url.contains("aliyuncs.com"), url);
        assertFalse(url.contains("/yousheng-uploads/"), url);
        assertTrue(url.contains("OSSAccessKeyId="), url);
        assertTrue(url.contains("response-content-disposition="), url);
    }

    @Test
    void blankCustomDomainKeepsBucketHost() {
        OssService svc = new OssService(props("  "), null);
        String url = svc.presignGetUrlWithDisposition(KEY, 60, DISPO);

        assertTrue(url.startsWith(
                "https://yousheng-uploads.oss-cn-hangzhou.aliyuncs.com/" + KEY + "?"), url);
        assertTrue(url.contains("response-content-disposition="), url);
    }

    /** 图片/视频的 302 也要落到自定义域名，否则微信里仍是跨域主机。 */
    @Test
    void customDomainAlsoUsedForMediaRead() {
        OssService svc = new OssService(props("oss.yousheng8.cn"), null);

        String plain = svc.presignGetUrl("uploads/4/20261103/a.webp");
        assertTrue(plain.startsWith("https://oss.yousheng8.cn/uploads/4/20261103/a.webp?"), plain);
        assertFalse(plain.contains("aliyuncs.com"), plain);

        String thumb = svc.presignGetUrl("uploads/4/20261103/a.webp", "image/resize,w_480");
        assertTrue(thumb.startsWith("https://oss.yousheng8.cn/uploads/4/20261103/a.webp?"), thumb);
        assertTrue(thumb.contains("x-oss-process=image%2Fresize%2Cw_480")
                        || thumb.contains("x-oss-process=image/resize,w_480"), thumb);
    }

    @Test
    void blankCustomDomainKeepsBucketHostForMediaRead() {
        OssService svc = new OssService(props(""), null);

        String url = svc.presignGetUrl("uploads/4/20261103/a.webp");
        assertTrue(url.startsWith(
                "https://yousheng-uploads.oss-cn-hangzhou.aliyuncs.com/uploads/4/20261103/a.webp?"), url);
    }

    /** 环境变量容易填成带协议的形式，不能拼成 https://https://。 */
    @Test
    void customDomainAcceptsFullUrl() {
        OssService svc = new OssService(props("https://oss.yousheng8.cn/"), null);

        String url = svc.presignGetUrl("uploads/4/20261103/a.webp");
        assertTrue(url.startsWith("https://oss.yousheng8.cn/uploads/4/20261103/a.webp?"), url);
    }
}
