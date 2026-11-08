package com.campus.social.service;

import com.campus.social.vo.AdminOverviewVO;

/**
 * 管理总览（2026-11-07 新增）。
 *
 * <p>为什么不并进 AdminUserService：那份服务管的是「处置某个人」，
 * 总览管的是「站内整体状态」，读写对象和变更理由都不是一类事。
 * 唯一交叉的是待办数字，直接复用 {@link AdminUserService#pendingSummary()}，
 * 避免两条 SQL 各算各的、角标与总览对不上。
 */
public interface AdminOverviewService {

    /** 一屏返回待办、今日、规模、风控四组计数。 */
    AdminOverviewVO overview();
}
