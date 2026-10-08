package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.vo.AnnouncementDetailVO;
import com.campus.social.vo.AnnouncementListItemVO;
import com.campus.social.vo.AnnouncementReadVO;
import com.campus.social.vo.UnreadCountVO;

/**
 * 公告前台（F5，读取侧）。
 *
 * <p>与 {@link AnnouncementAdminService} 分开：前者只做「可见性过滤 + 组装」，
 * 后者做「写 + 触达统计」。两者的失败模式不同——前台漏判一次可见性就是
 * 信息泄露，后台算错触达率只是数字不好看。
 */
public interface AnnouncementService {

    /**
     * 前台列表：置顶优先，随后按 id 倒序（游标分页）。
     *
     * <p>置顶部分每页全量返回，游标只作用于普通流。详见
     * {@code AnnouncementMapper.selectPinnedFront} 的注释——混在一条流里会静默丢行。
     *
     * @param category 分类过滤；null 表示全部
     */
    CursorPage<AnnouncementListItemVO> frontList(Long schoolId, Long userId, Integer category, Long cursor, int size);

    /** 详情。已下架/草稿仅发布方本人可见 */
    AnnouncementDetailVO detail(Long userId, Long announcementId, Long schoolId);

    /** 已读回执，幂等（AC-09）。重复上报不新增行、不重复累加 read_count */
    AnnouncementReadVO markRead(Long userId, Long announcementId, Long schoolId);

    /** 未读公告数与最新一条未读 id（红点） */
    UnreadCountVO unreadCount(Long userId, Long schoolId);
}