package com.campus.social.service.impl;

import com.campus.social.service.MessageGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发模式网关:验证码直接打控制台
 * 默认启用(app.campus.message-gateway=dev,未配置时也走 dev)
 *
 * 接真实渠道:实现 MessageGateway + @ConditionalOnProperty(name="app.campus.message-gateway", havingValue="prod")
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.campus.message-gateway", havingValue = "dev", matchIfMissing = true)
public class DevModeMessageGateway implements MessageGateway {

    @Override
    public boolean sendCode(String target, String code) {
        // 本地开发:打印到控制台,用户直接从日志读验证码
        log.info("[DEV 模式] 验证码发送 → target={}, code={}", target, code);
        return true;
    }
}
