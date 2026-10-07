package com.campus.social.service.impl;

import com.campus.social.service.MessageGateway;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 真实发信网关:走 JavaMailSender(SMTP)
 * 由 app.campus.message-gateway=prod + spring.mail.* 配置激活
 * 支持 QQ/163/Gmail 邮箱的 SMTP 授权码
 *
 * 异步发送:sendCode 立刻返回 true(验证码已先入 Redis),邮件后台慢慢投递,
 * 避免前端接口因 SMTP 慢/超时被卡住。配合 @Async + 有界线程池(见 AsyncConfig)。
 *
 * 注:@Async 只在跨 Bean 调用时走代理。同类内 sendCode 直接调 deliverAsync 会同步执行,
 * 故通过 @Lazy 自注入自身代理,sendCode 里走 self.deliverAsync(...) 才真正异步。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.campus.message-gateway", havingValue = "prod")
public class SmtpMessageGateway implements MessageGateway {

    private final JavaMailSender mailSender;
    private final String from;
    /** 自身代理,用于触发 @Async(避免同类内部调用绕过代理) */
    private final SmtpMessageGateway self;

    public SmtpMessageGateway(JavaMailSender mailSender,
                              @Value("${spring.mail.username}") String from,
                              @Lazy SmtpMessageGateway self) {
        this.mailSender = mailSender;
        this.from = from;
        this.self = self;
    }

    @Override
    public boolean sendCode(String target, String code) {
        if (target == null || !target.contains("@")) {
            // 手机号:真实短信网关(未接入,先返回失败,由业务层兜底)
            log.warn("未接入短信网关,手机号 {} 无法真实发送", target);
            return false;
        }
        // 立即返回 true(验证码已在 Redis,用户可继续校验),邮件投递转后台
        // 通过 self(代理)调用,@Async 才生效
        self.deliverAsync(target, code);
        log.info("验证码邮件已提交后台投递: target={}", target);
        return true;
    }

    /** @Async:Spring 代理在 mailExecutor 线程池上执行实际 SMTP 投递 */
    @Async("mailExecutor")
    public CompletableFuture<Void> deliverAsync(String target, String code) {
        try {
            deliver(target, code);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.error("验证码邮件投递失败: target={}, err={}", target, e.getMessage(), e);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /** 实际 SMTP 投递(在后台线程执行,不阻塞调用方) */
    private void deliver(String target, String code) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(target);
        helper.setSubject("校园社交注册/登录验证码");
        helper.setText(buildHtmlBody(code), true);
        mailSender.send(msg);
        log.info("验证码邮件投递成功: target={}", target);
    }

    private String buildHtmlBody(String code) {
        return "<div style='font-family:sans-serif;padding:20px;'>"
                + "<h2 style='color:#409EFF'>校园社交</h2>"
                + "<p>您的验证码:</p>"
                + "<div style='font-size:32px;font-weight:bold;letter-spacing:4px;color:#409EFF'>" + code + "</div>"
                + "<p style='color:#999;font-size:13px'>验证码 5 分钟内有效,请勿泄露给他人。</p>"
                + "</div>";
    }
}
