package com.campus.social.service;

/**
 * 可插拔的验证码/通知发送网关
 * - DevModeMessageGateway:控制台打印(MVP 默认,无需外部依赖)
 * - 接真实渠道时实现本接口(如 SmsGateway 接阿里短信、MailGateway 接 SMTP),
 *   通过 @Primary 或配置开关切换,业务层不用改
 */
public interface MessageGateway {

    /**
     * 发送验证码
     * @param target 邮箱或手机号
     * @param code   6 位数字
     * @return 是否发送成功
     */
    boolean sendCode(String target, String code);

    /**
     * 发送自由正文通知邮件(非验证码)。
     *
     * <p>用于「学号认证申诉处理结果」这类没有账号、只能靠邮箱触达的通知：
     * 申诉人注册未成功、站内通知没有收件人，处理说明必须发到其留下的联系邮箱。
     *
     * <p>语义与 {@link #sendCode} 一致：立即返回，真实投递在后台线程完成；
     * 返回 true 只代表「已受理」，不代表对方一定收到。
     *
     * @param target   收件邮箱
     * @param subject  邮件主题
     * @param htmlBody HTML 正文
     * @return 是否已受理提交
     */
    boolean sendNotice(String target, String subject, String htmlBody);
}
