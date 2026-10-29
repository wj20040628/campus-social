package com.campus.social.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证码邮件主题回归:6 位码必须在主题里,手机通知栏不点开也能看到。
 */
class MailSubjectsTest {

    @Test
    void appendsCodeWhenNoPlaceholder() {
        assertEquals("友声验证码 123456", MailSubjects.withCode("友声验证码", "123456"));
    }

    @Test
    void replacesPlaceholderInPlace() {
        assertEquals("123456 是你的验证码", MailSubjects.withCode("{code} 是你的验证码", "123456"));
    }

    @Test
    void fallsBackToDefaultWhenBaseBlank() {
        assertEquals("友声验证码 123456", MailSubjects.withCode("  ", "123456"));
        assertEquals("友声验证码 123456", MailSubjects.withCode(null, "123456"));
    }
}
