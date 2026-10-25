package com.campus.social.service;

import com.campus.social.vo.PendingSummaryVO;
import com.campus.social.vo.UserAdminPageVO;

/**
 * 管理员用户处置（2026-10-24 补的管理空白）。
 *
 * <p><b>为什么必须有</b>：此前封人的唯一入口是举报处置
 * （ReportServiceImpl.handleBan），解封更是**没有任何端点**——
 * {@code BanService.unban} 的唯一调用方是到期定时任务，
 * 永久封禁一旦误判只能改数据库。本服务补齐两件事：
 * <ol>
 *   <li>按昵称/学号/登录名找用户（管理员处置的起点是「找到人」）</li>
 *   <li>直接封禁 / 手动解封（不依赖「先有人举报」）</li>
 * </ol>
 *
 * <p>不做的事：重置密码、授予/收回管理员角色。前者涉及凭证安全需单独设计，
 * 后者当前单管理员由种子数据产生，多管理员运营时再议。
 */
public interface AdminUserService {

    /**
     * 用户处置列表（游标分页）。
     *
     * @param keyword 昵称/登录名/学号模糊匹配；空 = 不筛
     * @param status  0 正常 / 1 冻结（封禁中）；空 = 全部
     */
    UserAdminPageVO page(String keyword, Integer status, Long cursor, int size);

    /** 直接封禁（不走举报流程）。禁止封自己与其他管理员。 */
    void ban(long adminId, long userId, int banDays, String reason);

    /** 手动解封（到期任务之外的救济路径）。审计归因到 adminId。 */
    void unban(long adminId, long userId);

    /** 四条队列的待办聚合（管理入口角标）。 */
    PendingSummaryVO pendingSummary();
}
