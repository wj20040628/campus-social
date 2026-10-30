package com.campus.social.service;

import com.campus.social.vo.AdminVerifyPageVO;

/**
 * 校园信息核对（admin 域）。
 *
 * <p>单独成接口而不并进 {@code CampusVerifyService}：那边是「用户提交资料」
 * （权限模型 = 本人），这里是「管理员处理队列」（权限模型 = role=1 + 越权到别人账号）。
 * 把两类权限模型混在一个接口里，漏鉴权的风险会随方法数量线性增长。
 *
 * <p>核验结果只影响资料标记，不授予或收回任何功能权限。
 */
public interface AdminVerifyService {

    /** 待核对列表，游标分页 */
    AdminVerifyPageVO pending(Long cursor, int size);

    /** 登记：verify_status 2 → 1。目标不存在或状态非 2 时 404 VERIFY_NOT_FOUND */
    void approve(long adminId, long userId);

    /** 退回修改：verify_status 2 → 3，reason 写 audit_log。目标不存在或状态非 2 时 404 */
    void reject(long adminId, long userId, String reason);
}