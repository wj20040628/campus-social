package com.campus.social.service;

import com.campus.social.exception.BusinessException;

import java.time.LocalDateTime;

/**
 * 封禁期限管理（F4 action=ban）。
 *
 * <p><b>为什么不把解封时间写进 user 表</b>：{@code migration_v1.sql} 第4 节
 * 只给 user 增了 4 列（school_id / verify_status / stranger_msg_enabled /
 * onboarding_step），没有 {@code banned_until}；而本轮硬约束是不许自行加减字段。
 * 若把 {@code banDays} 悄悄忽略、只置 {@code status=1}，
 * 「封 7 天」会变成永久封禁——这是最危险的一类沉默逻辑错误
 * （用户以为 7 天后能回来，实际再也回不来）。
 *
 * <p>故解封时间落 Redis ZSET（{@code ban:expiring}，score=解封毫秒时间戳，
 * member=userId），由 {@code BanExpireJob} 定期把到期的恢复为正常。
 * 选Redis 的理由：它是本项目已有的、易失但可重建的存储层，
 * 与陌生人配额同源；且封禁到期恢复这件事即使丢一次，
 * 最坏结果是用户多等一个job 周期，而不是数据错乱。
 *
 * <p><b>已知局限</b>：Redis 重启会丢失未到期的解封计划，
 * 这些人将保持封禁状态直到管理员手动解封。这一点已在上报材料中列为blocking。
 */
public interface BanService {

    /**
     * 执行封禁。
     *
     * @param banDays 0 表示永久封禁；1/7/30 表示期限封禁
     * @return 解封时间；永久封禁返回 null
     */
    LocalDateTime ban(long userId, int banDays);

    /** 管理员手动解封（历史兼容入口，归因系统） */
    void unban(long userId);

    /**
     * 解封（2026-10-24 加 adminId 重载）。
     *
     * <p>为什么必须区分操作者：此前 unban 只写 {@code recordSystem}
     * （operator=系统），「到期自动解封」与「管理员手动解封」在 audit_log 里
     * 长得一模一样——手动解封无法归因，出争议时说不清是谁放的人。
     *
     * @param adminId 操作管理员 id；null 或 <=0 表示系统任务（到期自动解封）
     */
    void unban(long userId, Long adminId);

    /** 查解封时间；永久封禁或未被封禁返回 null */
    LocalDateTime bannedUntil(long userId);

    /**
     * 构造「账号已冻结」的用户可见异常，含解封时间与申诉提示。
     * 登录与 refresh 共用，避免两处文案漂移。
     */
    BusinessException frozenException(long userId);

    /**
     * 取出已到期的封禁计划（供定时任务调用）。
     *
     * <p>只读不删：调用方拿到 id 后要去改 {@code user.status}，
     * 中间若失败还需保留计划供下次重试。删的动作由 {@link #clearPlan} 单独提供。
     *
     * @return 到期的 userId 字符串集合；无到期时返回空集
     */
    java.util.Set<String> findExpired(long nowMs);

    /** 移除已处理完的封禁计划（恢复 status 成功后调用） */
    void clearPlan(String userId);
}