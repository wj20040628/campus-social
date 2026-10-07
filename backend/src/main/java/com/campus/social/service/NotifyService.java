package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.vo.NotifyVO;

/**
 * 通知中心
 */
public interface NotifyService {

    CursorPage<NotifyVO> list(long userId, Long lastId, int size);

    /** 标记单条已读 */
    void read(long notifyId, long userId);

    /** 全部已读(并 user.unread_notify_count 归零) */
    void readAll(long userId);

    /** 未读总数(从 user.unread_notify_count 读,避免 COUNT) */
    int unreadCount(long userId);
}
