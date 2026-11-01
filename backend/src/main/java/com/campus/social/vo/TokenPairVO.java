package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenPairVO {
    private String accessToken;
    private String refreshToken;
    /** access token 过期时间(秒级时间戳,UTC+8) */
    private long accessExpireAt;
    /**
     * 仅验证码登录自动建号时为 true。
     * 前端据此在首次进入首页时提示「设置密码」，已有账号登录/注册页注册均为 false。
     */
    private boolean newAccount;
}
