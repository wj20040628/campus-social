package com.campus.social.service;

import com.campus.social.dto.LoginByCodeDTO;
import com.campus.social.dto.LoginDTO;
import com.campus.social.vo.TokenPairVO;

public interface LoginService {

    /** 密码登录：三列 OR 匹配、失败锁定、封禁提示 */
    TokenPairVO login(LoginDTO dto, String ip);

    /** 邮箱验证码登录：未注册邮箱自动建号 */
    TokenPairVO loginByCode(LoginByCodeDTO dto);
}
