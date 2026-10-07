package com.campus.social.service;

/**
 * 验证码管理:Redis 存储 + 限频 + 按用途分桶
 * key: code:{type}:{target}  值:6 位码  TTL:5min
 * 限频 key: code:limit:{target}  每 60s 最多 1 次,每账号每日最多 10 次
 */
public interface VerifyCodeService {

    /**
     * 发送验证码(限频 + 调 MessageGateway)
     * @param target 邮箱或手机号
     * @param type   register/login/bind
     * @return 发送结果说明
     */
    String send(String target, String type);

    /** 校验并消费:匹配则删除(一次性),否则抛业务异常 */
    void verify(String target, String code, String type);
}
