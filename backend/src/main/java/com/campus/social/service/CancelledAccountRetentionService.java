package com.campus.social.service;

/**
 * 已注销账号留存清理（2026-11-08）。
 *
 * <p>注销当下清空资料、解绑微信、清退成员关系；私信与通知还要给对方
 * 留一段回溯期，到期后由定时任务物理删除。帖子/评论与举报/审计属于
 * 内容留痕与监管记录，按隐私政策口径去标识化保留，不在此清理。
 */
public interface CancelledAccountRetentionService {

    /** 清理一个已注销账号的私信、会话偏好与通知，并写入 purged_at。 */
    PurgeResult purgeUser(long userId);

    /** 各表实际删除行数，写日志用。 */
    record PurgeResult(int notifications, int messages, int prefs, int conversations) {
    }
}
