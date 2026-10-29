package com.campus.social.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载文件名头（2026-10-28）。
 *
 * <p>回归的是一个线上可见的 bug：Spring 的 ContentDisposition 会生成
 * RFC2047 兜底名 {@code filename="=?UTF-8?Q?=E5=BD=A2...?="}，夸克等下载器
 * 把它和 filename* 拼起来，用户拿到「=E5=BD=A2=E7=AD=96...形策.docx」。
 */
class DownloadNameTest {

    @Test
    void chineseName_onlyFilenameStar() {
        String header = DownloadName.attachment("形策_原著阅读模板.docx", "docx");
        assertTrue(header.startsWith("attachment; filename*=UTF-8''"), header);
        // 只发 filename*：一旦再出现 filename=，夸克就会把两段拼起来
        assertFalse(header.contains("filename=\""), header);
        assertFalse(header.contains("=?"), "不能出现 RFC2047 兜底名: " + header);
        assertFalse(header.contains("+"), "空格必须是 %20，不能是 +: " + header);
        assertTrue(header.endsWith("%E5%BD%A2%E7%AD%96_%E5%8E%9F%E8%91%97%E9%98%85%E8%AF%BB%E6%A8%A1%E6%9D%BF.docx"), header);
    }

    @Test
    void crlfAndQuotes_strippedFromHeader() {
        String header = DownloadName.attachment("bad\r\nSet-Cookie: x=1\"evil\".docx", "docx");
        assertFalse(header.contains("\r"), "CR 不能进响应头");
        assertFalse(header.contains("\n"), "LF 不能进响应头");
        // 引号被替换成下划线后百分号编码里不会再出现 %22
        assertFalse(header.contains("%22"), header);
    }

    @Test
    void blankName_fallsBackToExt() {
        assertEquals("attachment; filename*=UTF-8''download.pdf", DownloadName.attachment("   ", "pdf"));
        assertEquals("attachment; filename*=UTF-8''download", DownloadName.attachment(null, null));
    }

    @Test
    void pathSeparators_notLeaking() {
        String header = DownloadName.attachment("a/b\\c.docx", "docx");
        assertFalse(header.contains("a/b"), header);
        assertTrue(header.contains("a_b_c.docx"), header);
    }
}
