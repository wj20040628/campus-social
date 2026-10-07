package com.campus.social.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 显式声明 JavaMailSender,不依赖 MailSenderAutoConfiguration 的隐式条件
 * (prod 模式 SmtpMessageGateway 需要注入它,显式建 bean 保证一定有)
 * 配置来自 spring.mail.* (MailProperties 自动绑定 host/port/username/password)
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender(MailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort() != null ? properties.getPort() : 587);
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());
        properties.getProperties().forEach((k, v) ->
                sender.getJavaMailProperties().setProperty(k, String.valueOf(v)));
        return sender;
    }
}
