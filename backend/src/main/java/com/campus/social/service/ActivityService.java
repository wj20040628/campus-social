package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.dto.ActivityDTO;
import com.campus.social.vo.ActivityVO;

/**
 * 活动
 */
public interface ActivityService {

    long create(long creatorId, ActivityDTO dto);

    CursorPage<ActivityVO> listByCircle(long circleId, Long lastId, int size, long viewerId);

    ActivityVO detail(long activityId, long viewerId);

    /** 报名:幂等键 + 乐观锁防超卖(同事务) */
    void signup(long activityId, long userId);

    /** 取消报名 */
    void cancel(long activityId, long userId);

    /** 取消整个活动(创建者/管理员):置 status=4 并通知所有已报名者 */
    void cancelActivity(long activityId, long operatorId);
}
