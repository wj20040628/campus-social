package com.campus.social.service;

/**
 * 角色服务:RBAC 校验
 * 简化模型:role 0普通 1管理员
 */
public interface RoleService {

    /** 该用户是否管理员 */
    boolean isAdmin(long userId);

    /** 断言管理员,否则抛 403 */
    void requireAdmin(long userId);
}
