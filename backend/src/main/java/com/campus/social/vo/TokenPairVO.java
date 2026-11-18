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
}
