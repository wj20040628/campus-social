package com.campus.social.service;

import com.campus.social.dto.BindEmailDTO;
import com.campus.social.vo.UserVO;

/**
 * 安全凭证邮箱绑定：验证码校验、全局唯一与本人资料回填。
 */
public interface AccountEmailService {

    /**
     * 绑定 / 修改安全凭证邮箱（需登录）。
     *
     * <p>先通过 type=bind 的验证码确认目标邮箱所有权，再写入
     * {@code user.email}（转小写，uk_user_email 全局唯一）。
     */
    UserVO bindEmail(Long userId, BindEmailDTO dto);
}
