package com.campus.social.service;

import com.campus.social.dto.AnnouncementCreateDTO;
import com.campus.social.dto.AnnouncementUpdateDTO;
import com.campus.social.vo.AdminAnnouncementPageVO;
import com.campus.social.vo.AnnouncementPublishVO;
import com.campus.social.vo.AnnouncementRemindVO;
import com.campus.social.vo.UnreadUserPageVO;

/**
 * 公告管理端（F5，写入侧）。
 *
 * <p>与 {@link AnnouncementService} 分开：那边只做读取与可见性过滤，
 * 这边做发布、编辑、触达统计与在线提醒。混在一起会让两边都破 300 行，
 * 且「谁能看见」与「谁能改」这两类判断放在一起最容易改错一边漏一边。
 */
public interface AnnouncementAdminService {

    /** 发布公告。publishNow=false 时存草稿且不推送 */
    AnnouncementPublishVO create(long publisherId, AnnouncementCreateDTO dto);

    /** 编辑公告（PATCH 语义，只改传入的字段） */
    AnnouncementPublishVO update(long adminId, long announcementId, AnnouncementUpdateDTO dto);

    /** 后台列表（含草稿与已下架，带触达率） */
    AdminAnnouncementPageVO adminList(Long schoolId, Integer status, Long cursor, int size);

    /**
     * 「谁还没看」名单（AC-10）。
     *
     * <p>只允许发布方本人且已认证时调用，权限判定在 controller 之外的 service 层做，
     * 不能只靠前端藏入口。
     */
    UnreadUserPageVO unreadUsers(long adminId, long announcementId, Long cursor, int size);

    /**
     * 提醒未读用户：向当前在线且未读的用户推一条 WS。
     *
     * <p>离线用户不落库、不推送，下次上线靠未读红点发现。
     */
    AnnouncementRemindVO remind(long adminId, long announcementId);
}