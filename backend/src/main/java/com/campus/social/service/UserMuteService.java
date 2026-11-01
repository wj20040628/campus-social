package com.campus.social.service;

import com.campus.social.entity.User;

import java.time.LocalDateTime;

/**
 * 用户级禁言（2026-10-31）。
 *
 * <p>与封禁（{@code user.status=1}）的区别：禁言期间仍可登录、浏览、收通知，
 * 只是不能发布内容或消息。到期自动解除，不依赖定时任务。
 */
public interface UserMuteService {

    /** 禁言 days 天，返回截止时间。重复调用会顺延到新的截止时间。 */
    LocalDateTime mute(long userId, int days);

    /** 解除禁言（到期之外的救济入口）。 */
    void unmute(long userId);

    /** 当前禁言截止时间；未禁言或已过期返回 null 或过去时间。 */
    LocalDateTime mutedUntil(long userId);

    /**
     * 内容发布前校验。已禁言则抛 {@code USER_MUTED}，消息里带解除时间。
     *
     * @param action 用于提示文案，如「发帖」「发送消息」
     */
    void requireCanSpeak(long userId, String action);

    /** 已加载 User 的版本，供发帖/私信这类已经查过用户的链路复用 */
    void requireCanSpeak(User user, String action);
}
