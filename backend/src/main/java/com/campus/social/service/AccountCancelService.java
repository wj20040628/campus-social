package com.campus.social.service;

/**
 * 账号自助注销（《个人信息保护法》第 47 条删除权的落地入口）。
 *
 * <p>与 {@link BanService} 的区别：封禁是管理员对违规账号的处置（status=1，
 * 到期自动解除），注销是用户本人的一次性删除请求（status=2，不可逆）。
 * 注销后行记录保留用于审计追溯，但昵称、邮箱、学号、头像、简介与校园信息全部清空。
 */
public interface AccountCancelService {

    /**
     * 向本人绑定邮箱发送注销验证码，返回打码后的邮箱供前端展示。
     *
     * @throws com.campus.social.exception.BusinessException 已注销 / 是管理员 / 未绑定邮箱
     */
    String sendCode(long userId);

    /**
     * 校验验证码并注销账号。
     *
     * <p>成功后该账号无法再登录，refresh 令牌全部吊销，状态缓存立即失效；
     * 已发布的帖子、评论保留并显示为「已注销用户」，不再关联任何个人信息。
     */
    void cancel(long userId, String code);
}
