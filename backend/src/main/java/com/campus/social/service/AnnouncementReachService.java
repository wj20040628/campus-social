package com.campus.social.service;

import com.campus.social.vo.AnnouncementRemindVO;
import com.campus.social.vo.UnreadUserPageVO;

/**
 * 公告触达追踪（F5，AC-10）。
 *
 * <p>从 {@link AnnouncementAdminService} 拆出来的原因：那边是「写公告」，
 * 这边是「看谁读了」——发布是低频操作，未读名单是高频查询，
 * 混在一个 impl 里会让那个类突破 300 行，且两类逻辑的权限模型不同
 * （发布只要管理员，未读名单必须是发布方本人或管理员）。
 */
public interface AnnouncementReachService {

    /** 「谁还没看」名单。调用方需已确认 adminId 是发布方或管理员 */
    UnreadUserPageVO unreadUsers(long announcementId, Long schoolId, Long cursor, int size);

    /**
     * 提醒未读用户：向当前在线且未读的用户推一条 WS。
     *
     * <p>离线用户不落库、不推送，下次上线靠未读红点发现。
     * 调用方需已确认 adminId 是发布方或管理员。
     */
    AnnouncementRemindVO remind(long adminId, long announcementId, Long schoolId);
}