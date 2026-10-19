package com.campus.social;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// proxyTargetClass=true:异步走 CGLIB 类代理(代理是具体类的子类)。
// 否则默认 JDK 接口代理,会导致网关内 self 自注入(声明为具体类 AliyunDmMailGateway/
// SmtpMessageGateway)在 @Async 激活时抛 BeanNotOfRequiredTypeException,应用起不来。
@EnableAsync(proxyTargetClass = true)
public class CampusSocialApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusSocialApplication.class, args);
    }
}
