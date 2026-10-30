package com.campus.social.service;

import com.campus.social.vo.UserVO;

/**
 * 本人资料维护：查询、更新资料、封面与陌生人开关。
 *
 * <p>所有返回值都装配为「我的资料」视图（含 role 等私有字段），
 * 调用方必须是本人登录态。
 */
public interface ProfileService {

    /** 当前用户 */
    UserVO me(long userId);

    /**
     * 更新资料：昵称 / 头像 / 学号 / 介绍。
     *
     * <p>不含院系与年级：这两项已字典化，只能经 {@code POST /user/school-profile}
     * 与 {@code POST /onboarding/complete} 按字典 id 写入。
     */
    UserVO updateProfile(long userId, String nickname, String avatarUrl, String studentNo, String bio);

    /**
     * 设置 / 清除个人主页封面。
     *
     * <p>语义是<b>「传什么就是什么」</b>：{@code coverUrl} 为 null / 空白即恢复默认封面，
     * 与 {@link #updateProfile} 的「null = 本次不改」不是同一套语义。
     */
    UserVO updateCover(long userId, String coverUrl);

    /** 设置「是否允许陌生人给自己发消息」开关 */
    UserVO setStrangerMsgEnabled(long userId, boolean enabled);
}
