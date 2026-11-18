package com.campus.social.service;

import com.campus.social.dto.RegisterDTO;
import com.campus.social.vo.TokenPairVO;

public interface RegisterService {

    /** 邮箱验证码注册：建号、可选学号核验、签发 token */
    TokenPairVO register(RegisterDTO dto);
}
