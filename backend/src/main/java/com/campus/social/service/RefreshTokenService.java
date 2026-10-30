package com.campus.social.service;

import com.campus.social.dto.RefreshDTO;
import com.campus.social.vo.TokenPairVO;

public interface RefreshTokenService {

    /** 校验旧 refresh、原子滚动 jti，并按账号状态重签 */
    TokenPairVO refresh(RefreshDTO dto);

    /** 登出：吊销该账号 refresh 指纹并记审计 */
    void logout(long userId);
}
