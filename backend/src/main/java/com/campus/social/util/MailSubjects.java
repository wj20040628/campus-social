package com.campus.social.util;

/**
 * 验证码邮件主题拼装。
 *
 * <p>手机邮件通知只展示发件人 + 主题，正文要解锁点开才看得到；
 * 把 6 位码拼进主题后，通知栏直接可读。规则独立成纯函数便于单测。
 */
public final class MailSubjects {

    private static final String DEFAULT_BASE = "友声验证码";

    /**
     * 主题带码。
     *
     * @param base 配置的主题基串，可含 {@code {code}} 占位指定位置；
     *             为空时用默认「友声验证码」
     * @param code 6 位验证码
     */
    public static String withCode(String base, String code) {
        String b = base == null ? "" : base.trim();
        if (b.isEmpty()) {
            b = DEFAULT_BASE;
        }
        return b.contains("{code}") ? b.replace("{code}", code) : b + " " + code;
    }

    private MailSubjects() {
    }
}
