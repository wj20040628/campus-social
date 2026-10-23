package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.dto.ActivityDTO;
import com.campus.social.vo.ActivitySignupVO;
import com.campus.social.vo.ActivityVO;

import java.util.List;

/**
 * 活动
 */
public interface ActivityService {

    long create(long creatorId, ActivityDTO dto);

    CursorPage<ActivityVO> listByCircle(long circleId, Long lastId, int size, long viewerId);

    ActivityVO detail(long activityId, long viewerId);

    /**
     * 活动的报名者名单（2026-10-21 新增）。
     *
     * <p>按报名先后排序（先报的在前）—— 名单顺序在用户眼里就是「谁先来的」，
     * 打乱会让人以为系统在随便排。
     *
     * @param viewerId 当前查看者，用于标记 {@code isMe}；0 表示未登录
     */
    List<ActivitySignupVO> signups(long activityId, long viewerId);

    /** 报名:幂等键 + 乐观锁防超卖(同事务) */
    void signup(long activityId, long userId);

    /** 取消报名 */
    void cancel(long activityId, long userId);

    /** 取消整个活动(创建者/管理员):置 status=4 并通知所有已报名者 */
    void cancelActivity(long activityId, long operatorId);
}
