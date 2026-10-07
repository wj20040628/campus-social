package com.campus.social.service.impl;

import com.campus.social.common.ResultCode;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.campus.social.common.ResultCode.*;

/**
 * 角色实现:查 user.role 判断管理员
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final UserMapper userMapper;

    @Override
    public boolean isAdmin(long userId) {
        User u = userMapper.selectById(userId);
        return u != null && u.getRole() != null && u.getRole() == 1;
    }

    @Override
    public void requireAdmin(long userId) {
        if (!isAdmin(userId)) {
            throw new BusinessException(NOT_ADMIN, "需要管理员权限");
        }
    }
}
