package com.campus.social.service;

import com.campus.social.dto.RegisterDTO;
import com.campus.social.entity.User;
import com.campus.social.vo.TokenPairVO;

public interface RegisterService {

    /** 邮箱验证码注册：建号、可选学号核验、签发 token */
    TokenPairVO register(RegisterDTO dto);

    /** 验证码登录遇到未注册邮箱时自动建号（无密码哨兵值） */
    User createEmailAccount(String email);
}
