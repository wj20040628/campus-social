package com.campus.social.service;

import com.campus.social.common.CursorPage;

import com.campus.social.dto.CircleDTO;

import com.campus.social.vo.CircleVO;

/**
 * 圈子 + 活动
 */
public interface CircleService {

    long createCircle(long owner, CircleDTO dto);

    CursorPage<CircleVO> list(long me, Long lastId, int size);

    CircleVO detail(long circleId, long viewerId);

    /** 加入圈子(幂等,member_count±1) */
    void join(long circleId, long userId);

    void leave(long circleId, long userId);

    /** 查圈子内的帖子(简化:circle_id 过滤的 post) */
    CursorPage<com.campus.social.vo.PostVO> circlePosts(long circleId, Long lastId, int size, long viewerId);
}
