package com.campus.social.service;

import com.campus.social.vo.AdminVerifyPageVO;

/**
 * 学号核验审核（admin 域）。
 *
 * <p>单独成接口而不并进 {@code CampusVerifyService}：那边是「用户提交认证」
 * （权限模型 = 本人），这里是「管理员审批」（权限模型 = role=1 + 越权到别人账号）。
 * 把两类权限模型混在一个接口里，漏鉴权的风险会随方法数量线性增长。
 */
public interface AdminVerifyService {

    /** 待审列表，游标分页 */
    AdminVerifyPageVO pending(Long cursor, int size);

    /** 通过：verify_status 2 → 1。目标不存在或状态非 2 时 404 VERIFY_NOT_FOUND */
    void approve(long adminId, long userId);

    /** 驳回：verify_status 2 → 3，reason 写 audit_log。目标不存在或状态非 2 时 404 */
    void reject(long adminId, long userId, String reason);
}